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
