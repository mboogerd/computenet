package civictech.cell

import civictech.cell.port.PortRef
import java.io.Serializable
import java.util.*

/**
 * Wave id (G-20 decision): per-source monotonic counters. Convergence, not
 * simultaneity, across sources — see spec 20/22.
 */
data class Timestamp(val sourceId: UUID, val counter: Long) : Serializable

/**
 * Rides every data-path invocation (G-4). Outlets stamp it: a fresh [Timestamp]
 * when emission is spontaneous (no incoming context), the incoming timestamp with
 * a rewritten [sourcePort] when reactive. Cell authors never touch it.
 */
data class MessageContext(
    val timestamp: Timestamp,
    val sourcePort: PortRef,
) : Serializable

/**
 * Host-/thread-local current context. All writes go through [with] (set /
 * try / finally-restore) — a missed restore silently welds waves together,
 * especially under the single-threaded SimulationController.
 */
object CurrentContext {
    private val local = ThreadLocal<MessageContext?>()

    fun get(): MessageContext? = local.get()

    fun <R> with(ctx: MessageContext?, block: () -> R): R {
        val previous = local.get()
        local.set(ctx)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }
}
