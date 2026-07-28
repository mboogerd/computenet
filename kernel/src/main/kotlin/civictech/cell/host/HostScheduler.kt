package civictech.cell.host

import java.util.concurrent.CompletableFuture

/**
 * Owns the "when does the host queue drain" decision, so that one [civictech.cell.host.ManagedHost]
 * class serves both production (threaded) and deterministic (simulated) execution.
 */
interface HostScheduler {

    /** The concurrency color of the execution context this scheduler drains (spec 32). */
    val color: HostColor

    /**
     * Enqueue a task. Ordering contract: ascending [priority], then submission order (FIFO).
     * Submitted actions must not throw; error policy belongs to the host.
     * Actions are suspend-typed so one contract serves both colors; on a
     * [HostColor.BLOCKING] scheduler they never actually suspend (spawn validation, 32).
     *
     * **Threading contract (T18): submission is legal from any thread, and every
     * implementation must accept it.** Enqueue is the one seam a foreign thread
     * touches, and the host already depends on that in two places: dead-letter
     * emission hops off whatever thread raised the failure (T04 finding 6,
     * `ManagedHost.deadLetters`), and `ManagedHost.snapshotOf` is invoked by an
     * observer on its own thread (the inspector's content search). This was the
     * de-facto contract before it was written down — the two production
     * schedulers hold concurrent queues — and the one implementation that
     * assumed same-thread callers, `SimulationController`'s, now guards its
     * queue rather than leaving the assumption unstated and unenforced.
     *
     * "Thread-safe" here means exactly *enqueue*. **Draining stays
     * single-threaded per host**: one virtual thread, one coroutine, or the
     * simulation controller's own thread. That is the actor property (spec 32),
     * not an implementation detail, and nothing about this contract concurrently
     * executes two tasks of one host.
     *
     * The ordering contract above is unweakened: `(priority, sequence)` is
     * stamped at enqueue and drained in exactly that order. What a foreign-thread
     * caller cannot claim is *which* sequence number it wins against a concurrent
     * submit — that race is the caller's, not the scheduler's, and a caller that
     * needs a definite position must establish happens-before itself.
     */
    fun submit(priority: Int, action: suspend () -> Unit)

    /**
     * Await a management result. Only legal from outside the host's execution context
     * (callers of spawn/lookup/connect); host tasks never await.
     */
    fun <T> await(future: CompletableFuture<T>): T

    fun shutdown()
}

internal class ScheduledTask(
    private val priority: Int,
    private val sequence: Long,
    val action: suspend () -> Unit,
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int =
        compareValuesBy(this, other, { it.priority }, { it.sequence })
}
