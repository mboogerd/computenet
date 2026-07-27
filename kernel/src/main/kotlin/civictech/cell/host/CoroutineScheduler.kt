package civictech.cell.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** [CoroutineScheduler.DrainingThreadElement]'s key — file-scoped since a companion object is not allowed on an inner class. */
internal object DrainingThreadKey : CoroutineContext.Key<CoroutineScheduler.DrainingThreadElement>

/**
 * Production 🟣 scheduler (G-27): one coroutine draining the (priority, sequence)
 * queue sequentially — each task runs to completion before the next starts, so a
 * suspended task parks the whole host. That is actor semantics by design: finer
 * granularity means smaller hosts (spec 33), not concurrent cells within one.
 */
class CoroutineScheduler(
    name: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : HostScheduler {

    override val color: HostColor = HostColor.SUSPENDING

    private val queue = PriorityBlockingQueue<ScheduledTask>()
    private val sequencer = AtomicLong()
    private val tokens = Channel<Unit>(Channel.UNLIMITED)

    // T04 finding 5.4: belt-and-braces CoroutineExceptionHandler — with the
    // Throwable backstop around task.action() below this should be
    // unreachable, but an exception escaping the drain coroutine itself
    // (rather than a task) must not silently cancel the scope.
    private val scope = CoroutineScope(
        dispatcher + CoroutineName(name) + CoroutineExceptionHandler { _, e -> e.printStackTrace() },
    )

    @Volatile
    private var drainingThread: Thread? = null

    /**
     * T04 finding 5: set once the drain coroutine exits for any reason, so a
     * dead host fails loudly ([submit] throws) instead of silently accepting
     * traffic that will never drain.
     */
    @Volatile
    private var terminated = false

    /**
     * T04 finding 7.2: [drainingThread] used to be set once *before*
     * `task.action()` started and cleared once it returned — correct only if
     * the task never actually suspends across a thread hop. A genuinely
     * suspending task resuming on a different worker left the stale
     * pre-suspension thread in [drainingThread], so the self-await deadlock
     * guard in [await] stopped firing for a same-context re-entrant await
     * after resumption. A [ThreadContextElement] runs on every resumption
     * (not just task entry), so [drainingThread] always tracks the actual
     * current-execution thread.
     */
    internal inner class DrainingThreadElement : ThreadContextElement<Thread?> {
        override val key: CoroutineContext.Key<*> get() = DrainingThreadKey
        override fun updateThreadContext(context: CoroutineContext): Thread? {
            val previous = drainingThread
            drainingThread = Thread.currentThread()
            return previous
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Thread?) {
            drainingThread = oldState
        }
    }

    init {
        scope.launch {
            try {
                // one token per submission: the queue re-sorts by priority between drains
                for (token in tokens) {
                    val task = queue.poll() ?: continue
                    withContext(DrainingThreadElement()) {
                        try {
                            task.action()
                        } catch (t: VirtualMachineError) {
                            // OOM etc. stay fatal — do not paper over a dying JVM.
                            throw t
                        } catch (t: Throwable) {
                            // Backstop only (finding 5: was `catch (e: Exception)`,
                            // so a TODO()/StackOverflowError/NoClassDefFoundError
                            // escaped and cancelled the coroutine scope silently
                            // while `submit` kept succeeding). Hosts wrap actions
                            // with their own error policy; this is the last line
                            // of defense that keeps the loop alive.
                            t.printStackTrace()
                        }
                    }
                }
            } finally {
                terminated = true
            }
        }
    }

    override fun submit(priority: Int, action: suspend () -> Unit) {
        check(!terminated) { "host scheduler terminated" }
        queue.put(ScheduledTask(priority, sequencer.incrementAndGet(), action))
        tokens.trySend(Unit)
    }

    override fun <T> await(future: CompletableFuture<T>): T {
        check(Thread.currentThread() != drainingThread) {
            "await called from the host's own execution context (would deadlock)"
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun shutdown() {
        tokens.close()
        scope.cancel()
    }
}
