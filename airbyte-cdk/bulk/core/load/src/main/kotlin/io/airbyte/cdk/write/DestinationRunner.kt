package io.airbyte.cdk.write

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs a DestinationTask state machine, either from an arbitrary initial task
 * or by wrapping a StandardDestination in the default state machine entry point.
 */
class DestinationRunner(
    private val initialTask: DestinationTask
) {
    constructor(destination: StandardDestination): this(
        DefaultSetup(destination)
    )

    private val log = KotlinLogging.logger {}

    // TODO: From configuration
    companion object {
        const val MAX_TASKS = 10
        const val WAIT_TIME_MS = 500L
    }

    private val catalog = Catalog() // TODO: from real catalog
    private val recordQueue = FakeMessageQueue(catalog, 4, 2, 0.5)
    private val streamComplete = mutableMapOf<Stream, Boolean>()

    private val queue = Channel<DestinationTask>(Channel.UNLIMITED)
    private val nTasks = AtomicInteger(0)
    private val nTasksPerType = mutableMapOf<String, AtomicInteger>()
    private val nTasksPerTypePerStream = mutableMapOf<String, MutableMap<Stream, AtomicInteger>>()
    private val seenUniqueIds = mutableSetOf<String>()
    private val taskCounters = mutableMapOf<String, Int>()

    /**
     * Enqueue a task for execution.
     */
    private suspend fun enqueue(task: DestinationTask) {
        queue.send(task)
    }

    suspend fun run() = coroutineScope {
        log.info { "Starting DestinationRunner; enqueueing initial task: $initialTask" }
        enqueue(initialTask)

        var done = false
        while (!done) {
            // Wait if we're at max concurrency
            while (nTasks.get() >= MAX_TASKS) {
                log.debug { "At max concurrency; waiting $WAIT_TIME_MS ms" }
                delay(WAIT_TIME_MS)
            }

            // Get the next task
            val task = queue.tryReceive().getOrNull()
            if (task == null) {
                log.debug { "No tasks enqueued; waiting $WAIT_TIME_MS ms" }
                delay(WAIT_TIME_MS)
                continue
            }

            /**
             * Handle control tasks.
             *
             * Note: this flow could be handled within the tasks themselves.
             * I started with this approach to limit the flexibility of
             * the state machine, especially fanout, to what is strictly
             * needed for the StandardDestination lifecycle.
             */
            if (task is ControlTask) {
                when (task) {
                    is Decrementing -> {
                        taskCounters.getOrPut(task.id) { 0 }.let {
                            val underlying = task.task()
                            log.info { "Decrementing task counter: '${task.id}' $it => ${it - 1}; underlying=$underlying)" }
                            taskCounters[task.id] = it - 1
                            enqueue(underlying)
                        }
                    }

                    is ExactlyOnce -> {
                        val underlying = task.task()
                        if (!seenUniqueIds.contains(task.id)) {
                            log.info { "Enqueueing ExactlyOnce task: ${task.id}; underlying=$underlying" }
                            seenUniqueIds.add(task.id)
                            enqueue(underlying)
                        } else {
                            log.info { "Skipping ExactlyOnce task: ${task.id}; underlying=$underlying" }
                        }
                    }

                    is ForEachAvailable -> {
                        val head = task.taskFor(0)
                        enqueue(head)
                        val count = head.concurrency?.available() ?: 0
                        log.info { "Enqueueing ${max(count, 1)} instances of ForEachAccumulator task: $head" }
                        for (i in 1..count) {
                            enqueue(task.taskFor(i))
                        }
                    }

                    is ForEachStream -> {
                        val tasks = catalog.streams.associateWith { task.taskFor(it) }
                        log.info { "Enqueueing ForEachStream tasks: $tasks" }
                        tasks.values.forEach { enqueue(it) }
                    }

                    is Incrementing ->
                        taskCounters.getOrPut(task.id) { 0 }.let {
                            val underlying = task.task()
                            log.info { "Incrementing task counter: '${task.id}' $it => ${it + 1}; underlying=$underlying)" }
                            taskCounters[task.id] = it + 1
                            enqueue(underlying)
                        }

                    is WhenAllComplete -> {
                        val count = taskCounters.getOrDefault(task.id, 0)
                        if (count == 0) {
                            val underlying = task.task()
                            log.info { "Enqueueing WhenAllComplete-guarded task: '${task.id}' == 0; underlying=$underlying" }
                            enqueue(underlying)
                        } else {
                            log.info { "Re-enqueueing WhenAllComplete-guard for task: '${task.id}' == $count" }
                        }
                    }

                    is WhenStreamComplete -> {
                        if (streamComplete[task.stream] == true) {
                            val underlying = task.getTask()
                            log.info { "Enqueuing WhenStreamComplete-guarded task: underlying=$underlying; stream=${task.stream})" }
                            enqueue(task.getTask())
                        } else {
                            log.info { "Re-enqueueing WhenStreamComplete guard (stream=${task.stream})" }
                            enqueue(task)
                        }
                    }

                    is Done -> {
                        log.info { "Received terminating Done task; exiting" }
                        done = true
                    }
                }
                continue
            }

            // Enforce concurrency limits per type / gather counters
            val counters = mutableListOf<AtomicInteger>()
            if (task.concurrency != null) {
                val (taskId, perSync, perStream) = task.concurrency!!

                if (perSync > 0) {
                    val counter = nTasksPerType
                        .getOrPut(taskId) { AtomicInteger(0) }

                    if (counter.incrementAndGet() > perSync) {
                        enqueue(task)
                        val count = counter.decrementAndGet()
                        log.debug { "Re-enqueueing task $task due to per-sync concurrency guard: $taskId; $count/$perSync" }
                        continue
                    } else {
                        counters.add(counter)
                    }
                }

                if (perStream > 0 && task is PerStream) {
                    val counter = nTasksPerTypePerStream
                        .getOrPut(taskId) { mutableMapOf() }
                        .getOrPut(task.stream) { AtomicInteger(0) }

                    if (counter.incrementAndGet() > perStream) {
                        enqueue(task)
                        val count = counter.decrementAndGet()
                        log.debug { "Re-enqueueing task $task due to per-stream concurrency guard: $taskId; $count/$perStream" }
                        continue
                    } else {
                        counters.add(counter)
                    }
                }
            }

            /**
             * Primer any record consumers with data from the message queue.
             *
             * NOTE: These semantics are definitely wrong. We're blocking
             * the main loop to wait for data & paying queueing and dispatch
             * costs potentially per record. We should just spawn one
             * accumulator per stream x concurrency and hand them each the
             * reader end of an async queue, and let them yield and re-enqueue
             * whenever they finish a batch.
             */
            var requeue = false
            if (task is RecordConsumer) {
                val payload = recordQueue.take(task.stream)
                if (payload.isEmpty()) {
                    log.debug { "Deferring RecordConsumer $task for stream ${task.stream} due to empty queue" }
                    enqueue(task)
                    continue
                }

                task.payload = payload
                task.endOfStream = recordQueue.streamComplete(task.stream)
                task.forceFlush = false // TODO: From per-stream timer

                if (!task.endOfStream) {
                    requeue = true
                } else {
                    streamComplete[task.stream] = true
                }
            }

            // Dispatch the task
            launch {
                nTasks.incrementAndGet()
                log.info { "Executing task: $task" }
                val nextTask = task.execute()
                log.info { "Received next task: $nextTask" }
                nTasks.decrementAndGet()
                counters.forEach { it.decrementAndGet() }
                enqueue(nextTask)
                if (requeue) {
                    enqueue(task)
                }
            }
        }
    }
}