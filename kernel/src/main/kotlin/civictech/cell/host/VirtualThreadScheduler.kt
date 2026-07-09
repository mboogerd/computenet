package civictech.cell.host

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Production scheduler: one virtual thread draining a priority queue,
 * FIFO among equal priorities (C-8).
 */
class VirtualThreadScheduler(name: String) : HostScheduler {

    private val queue = PriorityBlockingQueue<ScheduledTask>()
    private val sequencer = AtomicLong()

    private val thread: Thread = Thread.ofVirtual().name(name).start {
        try {
            while (!Thread.interrupted()) {
                val task = queue.take()
                try {
                    task.action()
                } catch (e: Exception) {
                    // Backstop only; hosts wrap actions with their own error policy.
                    e.printStackTrace()
                }
            }
        } catch (_: InterruptedException) {
            // stop thread
        }
    }

    override fun submit(priority: Int, action: () -> Unit) {
        queue.put(ScheduledTask(priority, sequencer.incrementAndGet(), action))
    }

    override fun <T> await(future: CompletableFuture<T>): T {
        check(Thread.currentThread() != thread) {
            "await called from the host's own execution context (would deadlock)"
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun shutdown() {
        thread.interrupt()
    }
}
