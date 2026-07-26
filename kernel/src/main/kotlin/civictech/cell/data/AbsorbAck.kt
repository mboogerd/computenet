package civictech.cell.data

import civictech.cell.CurrentContext
import civictech.cell.control.Progress
import civictech.cell.port.FanOutlet
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
