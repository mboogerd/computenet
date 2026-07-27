package civictech.cell.host

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Production 🔵 scheduler: one virtual thread draining a priority queue,
 * FIFO among equal priorities (C-8).
 */
class VirtualThreadScheduler(name: String) : HostScheduler {

    override val color: HostColor = HostColor.BLOCKING

    private val queue = PriorityBlockingQueue<ScheduledTask>()
    private val sequencer = AtomicLong()

    /**
     * T04 finding 5: set once the drain loop exits for any reason, so a dead
     * host fails loudly ([submit] throws) instead of silently accepting
     * traffic that will never drain.
     */
    @Volatile
    private var terminated = false

    private val thread: Thread = Thread.ofVirtual().name(name).start {
        // runBlocking only adapts the suspend-typed action contract; on a 🔵 host
        // actions never genuinely suspend (spawn validation, spec 32), so the
        // event loop never parks mid-task.
        runBlocking {
            try {
                try {
                    while (!Thread.interrupted()) {
                        val task = queue.take()
                        try {
                            task.action()
                        } catch (t: VirtualMachineError) {
                            // OOM etc. stay fatal — do not paper over a dying JVM.
                            throw t
                        } catch (t: Throwable) {
                            // Backstop only (finding 5: was `catch (e: Exception)`,
                            // so a TODO()/StackOverflowError/NoClassDefFoundError
                            // escaped all backstops and killed the drain loop
                            // silently while `submit` kept succeeding). Hosts wrap
                            // actions with their own error policy; this is the last
                            // line of defense that keeps the loop alive.
                            t.printStackTrace()
                        }
                    }
                } catch (_: InterruptedException) {
                    // stop thread
                }
            } finally {
                terminated = true
            }
        }
    }

    override fun submit(priority: Int, action: suspend () -> Unit) {
        check(!terminated) { "host scheduler terminated" }
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
