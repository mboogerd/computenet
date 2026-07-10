package civictech.cell.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private val scope = CoroutineScope(dispatcher + CoroutineName(name))

    @Volatile
    private var drainingThread: Thread? = null

    init {
        scope.launch {
            // one token per submission: the queue re-sorts by priority between drains
            for (token in tokens) {
                val task = queue.poll() ?: continue
                drainingThread = Thread.currentThread()
                try {
                    task.action()
                } catch (e: Exception) {
                    // Backstop only; hosts wrap actions with their own error policy.
                    e.printStackTrace()
                } finally {
                    drainingThread = null
                }
            }
        }
    }

    override fun submit(priority: Int, action: suspend () -> Unit) {
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
