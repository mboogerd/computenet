package civictech.cell.control

import civictech.cell.CurrentContext
import civictech.cell.link.LinkRole
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols

/**
 * Metadata-plane absorb-ack (spec 20/22 §Completeness over silent or stuck
 * edges, G-40, CP-A3): an absorbing operator that consumes a reactive wave
 * without emitting a delta advances a downstream glitch-free join's per-source
 * watermark past the wave it silently swallowed — the second of the three
 * watermark-advance mechanisms (delta, `Progress`, later wave / monotone max).
 * Without it the join can only settle a bridged/absorbing arm from real data
 * arrivals; a mid-pipeline filter/join/antijoin that drops the final wave
 * strands it forever (the CP-A3 control).
 *
 * The ack rides the wave the downstream *would* have seen on this edge — the
 * incoming context's timestamp, which [FanOutlet] preserves on a reactive
 * emission — so it keys the exact `(sourceId, counter)` watermark slot a real
 * delta would have. It fans over the outlet's real links only, so a
 * topology-blind subscriber (`Use.fixed`, plain `subscribe`) pays nothing.
 * Baseline (`StateRequest` catch-up) and spontaneous emissions carry no wave
 * position and are skipped — they are excluded from every completeness set.
 */
internal fun FanOutlet<*>.absorbAck() {
    val ctx = CurrentContext.get() ?: return
    if (ctx.baseline != null) return
    if (linking.links.isEmpty()) return
    val ack = Progress(ctx.timestamp.sourceId, ctx.timestamp.counter)
    linking.links.forEach { Protocols.sendDownstream(it, Protocols.Progress, ack) }
}

/**
 * Relays an upstream absorb-ack through a transparent operator hop.
 *
 * [Progress] is metadata-plane traffic, so forwarding preserves its original
 * `(sourceId, thru)` watermark verbatim: the hop neither mints a wave nor
 * rewrites source/tag identity. [ProtocolSupport]'s descriptor-directed relay
 * fans it over this inlet's owning cell's downstream links and carries a
 * visited-edge set, so a local cycle cannot amplify the acknowledgement.
 *
 * Relaying one input edge's settlement is sound only when it settles the whole
 * transparent hop. This helper therefore relays only while the hop has exactly
 * one open [LinkRole.Consume] input edge in total. The receiver is counted by
 * default; [otherInlets] must name every sibling inlet that can feed the same
 * operator. Source-to-edge reachability is not available at this layer, so the
 * count is deliberately conservative across sources: a multi-input hop never
 * claims whole-hop settlement from one edge's [Progress]. A proper per-edge
 * watermark fold can relax that limit later. [ProtocolSupport] evaluates the
 * predicate for every arriving acknowledgement, and [FanInlet.linking] exposes
 * only the currently active links, so links added after construction count and
 * an unlinked edge stops counting before the next acknowledgement.
 *
 * A frontier installed on this inlet is also a terminal: local delivery happens
 * first and the dynamic predicate then suppresses relay. This matters when a
 * policy is installed after cell construction — one [Progress] must either be
 * consumed by that frontier or pass through this transparent hop, never both.
 */
internal fun FanInlet<*>.relayAbsorbAcks(vararg otherInlets: FanInlet<*>) {
    val support = ProtocolSupport.of(this)
    support.relay(Protocols.Progress) {
        support.handles(Protocols.Progress) ||
            (sequenceOf(this) + otherInlets.asSequence())
                .flatMap { it.linking.links.asSequence() }
                .count { it.role == LinkRole.Consume } != 1
    }
}
