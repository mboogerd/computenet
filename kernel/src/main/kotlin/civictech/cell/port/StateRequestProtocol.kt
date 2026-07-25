package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.TagFrontier
import civictech.cell.replication.Interest
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.gen.wire.ProtocolCardinality
import civictech.gen.wire.ProtocolDirection

/**
 * On-demand pull (spec 20/21 §Pull, the G-18 residual, decided in 93 I-16): a
 * consumer asks an upstream producer for current state without relinking.
 * [replyTo] names the requester's port on the link's metadata plane — a
 * management-class message: null context, no `Owned`/`Leased`, bypasses
 * data-path parking, idempotent, and travels upstream. [since] `null` means
 * full state-from-empty; non-null means only tags beyond the frontier per
 * source (cells without a per-source-monotonic tag clock fall back to full
 * state regardless).
 *
 * [scope] (PN-3c, spec 42 §Interest-scoped instance sets) restricts the reply
 * to the sub-state the requester's [Interest] admits: a partial-interest
 * consumer (a shard peer, a scatter-gather leg) pulls exactly its slice instead
 * of the producer's whole state. `null` ⇒ [Interest.Total] ⇒ the whole state,
 * byte-identical to the pre-scope reply — so every existing call site is
 * unchanged.
 *
 * The reply is ordinary data: a single-wave state-as-delta stamped with a
 * fresh wave from the producer outlet's own counter (FIFO/sequencing) and a
 * non-null [civictech.cell.MessageContext.baseline], delivered only to
 * [replyTo] — never broadcast.
 */
data class StateRequest(
    val replyTo: PortRef,
    val since: TagFrontier?,
    val scope: Interest? = null,
)

/**
 * Consumer-side retained pull currency (PN-3c, spec 42 §Interest-scoped
 * instance sets): the [TagFrontier] a consumer has caught up to **per source
 * instance** it pulls from — deliberately *not* one merged frontier.
 *
 * Shard holdings are non-contiguous: instance A may hold counters {1,3,5} of a
 * shared upstream source while instance B holds {2,4} of the same source. A
 * single pointwise-max `since` merged across instances would carry A's `5` into
 * the currency handed to B, so B's next incremental [StateRequest] would report
 * every tag ≤ 5 as already-seen and silently drop B's own {2,4} (or a later
 * unseen tag below the merged max). Keeping one frontier per instance is the
 * whole point: a per-instance `since` is monotone *within* that instance (its
 * own holdings are contiguous under its own pulls) and never contaminated by a
 * sibling's progress.
 */
class RetainedFrontiers {
    private val perInstance = mutableMapOf<CellRef, TagFrontier>()

    /** The currency to send as [StateRequest.since] for [instance]; `null` before its first reply. */
    fun sinceFor(instance: CellRef): TagFrontier? = perInstance[instance]

    /**
     * Fold a baseline reply's [frontier] into [instance]'s slot alone (pointwise
     * max *within* the one instance — never across instances). Idempotent.
     */
    fun record(instance: CellRef, frontier: TagFrontier) {
        perInstance[instance] = merge(perInstance[instance], frontier)
    }

    private fun merge(a: TagFrontier?, b: TagFrontier): TagFrontier {
        if (a == null) return b
        val merged = a.perSource.toMutableMap()
        b.perSource.forEach { (src, ctr) -> merged.merge(src, ctr, ::maxOf) }
        return TagFrontier(merged)
    }
}

@Contract(management = true)
@Protocol(
    "state-request",
    ProtocolDirection.UPSTREAM,
    band = 0,
    lane = "state-request",
    cardinality = ProtocolCardinality.FAN_IN_MERGE,
)
fun interface StateRequestProtocol {
    fun stateRequest(message: StateRequest)
}
