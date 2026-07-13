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
 *
 * [reBaseline] is non-null exactly on a RESTART re-baseline emission (spec
 * 20/21 closing paragraph on Pull; 20/22 §Source identity; 93 I-22 R2/R4/R5):
 * an ordinary catch-up delta carrying a supersede bit and the dead epochs'
 * source ids, transparently forwarded like any other context field, never a
 * new wire type.
 *
 * [baseline] is non-null exactly on a catch-up baseline emission (spec 20/21
 * §Pull, 20/22 §Interaction, decided in 93 I-24): a topology-versioned
 * state-as-delta reply to a `StateRequest` pull, causally anchored at the
 * stamped link-install event and carrying a merge-tag [TagFrontier] for
 * dedup/incremental-pull currency — never a wave position. A glitch-free
 * consumer installs it as arm state and MUST NOT admit it to any
 * wave-completeness set.
 *
 * [hop] (spec 20/22 §MessageContext rule 2, 93 I-5): incremented on every
 * transparent-flow hop, reset to 0 by cycle-head re-origination
 * ([civictech.cell.port.FeedbackInlet]) — a pure divergence guard, never
 * part of the wave join key. A host dead-letters an invocation whose hop
 * exceeds its configured bound as a
 * [civictech.cell.port.CycleError] — the backstop for headless loops and
 * cross-host cycles no link-time check can see.
 */
@kotlinx.serialization.Serializable
@SerialName("MessageContext")
data class MessageContext(
    val timestamp: Timestamp,
    val sourcePort: PortRef,
    val reBaseline: ReBaselineNotice? = null,
    val baseline: TagFrontier? = null,
    val hop: Int = 0,
) : Serializable

/**
 * Merge-tag frontier (spec 20/21 §Pull, 20/22 §Interaction, decided in 93
 * I-24): the highest tag counter observed per tag source — dedup/
 * incremental-pull currency, never a wave position (tags and waves stay
 * separate uses of one clock shape). Carried non-null on
 * [MessageContext.baseline] to mark a catch-up baseline delta, and as
 * `StateRequest.since` for incremental pull; valid only for
 * per-source-monotone tag families (full-state fallback otherwise, `since =
 * null`).
 */
@kotlinx.serialization.Serializable
@SerialName("TagFrontier")
data class TagFrontier(
    val perSource: Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long>,
) : Serializable

/**
 * The `supersede`/`supersedes` half of a `ReBaseline` emission (93 I-22),
 * carried on [MessageContext] rather than as a new wire type — "an ordinary
 * catch-up delta with a mode." [supersedes] names the dead (superseded)
 * outlet `sourceId`s; `supersede = true` is push-authoritative (single-writer
 * roots: convergent consumers drop un-reasserted tags from those sources and
 * fence them as dead lanes), `false` is pull-merge (derived/replicated
 * cells: forward idempotent merge only, no retraction).
 */
@kotlinx.serialization.Serializable
@SerialName("ReBaselineNotice")
data class ReBaselineNotice(
    val supersedes: Set<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID>,
    val supersede: Boolean,
) : Serializable

/**
 * Thread-local staging for the [ReBaselineNotice] an outlet's *next*
 * spontaneous emission should carry (spec 93 I-22 R2). Read exactly once, at
 * the point an outlet mints a fresh context (`CurrentContext.get() == null`);
 * reactive (transparent-flow) emissions never consult it — the notice rides
 * along via [MessageContext.reBaseline] once minted.
 */
object PendingReBaseline {
    private val local = ThreadLocal<ReBaselineNotice?>()

    fun get(): ReBaselineNotice? = local.get()

    fun <R> with(notice: ReBaselineNotice?, block: () -> R): R {
        val previous = local.get()
        local.set(notice)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }
}

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
