package civictech.bench.macro

import civictech.cell.host.HostColor
import civictech.cell.host.HostScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Records failures that a production [HostScheduler] drain loop would otherwise
 * print and continue past.
 *
 * The wrapped action is always rethrown after it is recorded, so this decorator
 * does not replace or weaken the scheduler's own failure policy. [submitted] is
 * incremented before delegation and [completed] only after an action returns or
 * throws. [idle] reads completed first and submitted second: a concurrent submit
 * between those reads can therefore only produce a conservative `false`, never a
 * false `true` while new work is queued.
 */
class InstrumentedScheduler(private val inner: HostScheduler) : HostScheduler {
    private val submittedCount = AtomicLong()
    private val completedCount = AtomicLong()
    private val capturedFailures = ConcurrentLinkedQueue<Throwable>()
    private val idleLock = ReentrantLock()
    private val becameIdle = idleLock.newCondition()

    override val color: HostColor get() = inner.color

    /** Number of actions submitted through this decorator. */
    val submitted: Long get() = submittedCount.get()

    /** Number of submitted actions that have returned or thrown. */
    val completed: Long get() = completedCount.get()

    /** A snapshot of failures observed so far, in observation order. */
    val failures: List<Throwable> get() = capturedFailures.toList()

    /** Whether every action submitted through this decorator has completed. */
    val idle: Boolean
        get() {
            val completedSnapshot = completedCount.get()
            return completedSnapshot == submittedCount.get()
        }

    override fun submit(priority: Int, action: suspend () -> Unit) {
        submittedCount.incrementAndGet()
        inner.submit(priority) {
            try {
                action()
            } catch (failure: Throwable) {
                capturedFailures += failure
                throw failure
            } finally {
                val completed = completedCount.incrementAndGet()
                if (completed == submittedCount.get()) {
                    idleLock.withLock { becameIdle.signalAll() }
                }
            }
        }
    }

    override fun <T> await(future: CompletableFuture<T>): T = inner.await(future)

    override fun shutdown() = inner.shutdown()

    /**
     * Wait outside the scheduler's execution context until [idle] is true or
     * [millis] elapses. Completion signals the condition directly, so benchmark
     * timing does not include a polling interval. This deliberately does not call
     * [inner.await].
     */
    fun awaitIdle(millis: Long): Boolean {
        require(millis >= 0) { "millis must not be negative" }

        return idleLock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(millis)
            while (!idle) {
                if (remainingNanos <= 0L) return@withLock false
                remainingNanos = becameIdle.awaitNanos(remainingNanos)
            }
            true
        }
    }
}
