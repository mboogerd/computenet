package civictech.cell

import civictech.cell.port.PortRef
import civictech.cell.wire.UuidSerializer
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import java.io.Serializable
import java.util.*

/**
 * Wave id (G-20 decision): per-source monotonic counters. Convergence, not
 * simultaneity, across sources — see spec 20/22.
 */
@kotlinx.serialization.Serializable
@SerialName("Timestamp")
data class Timestamp(
    @kotlinx.serialization.Serializable(with = UuidSerializer::class) val sourceId: UUID,
    val counter: Long,
) : Serializable

/**
 * Rides every data-path invocation (G-4). Outlets stamp it: a fresh [Timestamp]
 * when emission is spontaneous (no incoming context), the incoming timestamp with
 * a rewritten [sourcePort] when reactive. Cell authors never touch it.
 */
@kotlinx.serialization.Serializable
@SerialName("MessageContext")
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

    /**
     * Suspend-capable variant of [with]: the coroutine context element
     * re-installs [ctx] on every resumption, so a wave survives suspension
     * instead of welding into whatever ran meanwhile on the same thread.
     */
    suspend fun <R> withSuspending(ctx: MessageContext?, block: suspend () -> R): R =
        withContext(local.asContextElement(ctx)) { block() }

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
