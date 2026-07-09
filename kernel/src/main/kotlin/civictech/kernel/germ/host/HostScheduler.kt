package civictech.kernel.germ.host

import java.util.concurrent.CompletableFuture

/**
 * Owns the "when does the host queue drain" decision, so that one [civictech.kernel.germ.ManagedHost]
 * class serves both production (threaded) and deterministic (simulated) execution.
 */
interface HostScheduler {

    /**
     * Enqueue a task. Ordering contract: ascending [priority], then submission order (FIFO).
     * Submitted actions must not throw; error policy belongs to the host.
     */
    fun submit(priority: Int, action: () -> Unit)

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
    val action: () -> Unit,
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int =
        compareValuesBy(this, other, { it.priority }, { it.sequence })
}
