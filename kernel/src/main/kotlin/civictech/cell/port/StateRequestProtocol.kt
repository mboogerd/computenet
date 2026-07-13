package civictech.cell.port

import civictech.cell.TagFrontier
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
 * The reply is ordinary data: a single-wave state-as-delta stamped with a
 * fresh wave from the producer outlet's own counter (FIFO/sequencing) and a
 * non-null [civictech.cell.MessageContext.baseline], delivered only to
 * [replyTo] — never broadcast.
 */
data class StateRequest(val replyTo: PortRef, val since: TagFrontier?)

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
