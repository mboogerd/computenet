package civictech.cell.link

import civictech.cell.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortNatures
import civictech.cell.protocol.Protocols
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.StateRequest
import civictech.nature.PullService

/**
 * Late-join catch-up (G-22): on every new link, send the current state as a
 * delta-from-empty to just the new subscriber. [snapshot] returns null when
 * there is nothing to catch up (the empty-state guard every hand-rolled copy
 * of this block carried).
 *
 * PN-9: registered on the [LinkSupport.onLinkedListeners] **multicast** instead
 * of the single-slot [LinkSupport.onLinked]. Before, this *assigned* `onLinked`,
 * so a cell that needed any other on-link behavior stomped catch-up (or, as
 * `Replication` did, worked around it by manually re-firing the one slot).
 * Now catch-up, pull-serve, and a replication re-announce all compose — each is
 * an independent on-link hook, none overwrites another. The manual re-fire sites
 * use [LinkSupport.fireLinked] so an anti-entropy re-announce still re-pushes it.
 *
 * ponytail (PN-2): the plan calls for this push catch-up to ride
 * [FanOutlet.baselineTo] so push and pull catch-up are marked identically as a
 * baseline. That change is deferred, but **not** for the reason recorded here
 * before. The superseded note claimed the reply "inflates the
 * `waveState().highWater` that [civictech.cell.replication] reads directly as a
 * source's delivered high-water". No file under [civictech.cell.replication]
 * calls `waveState()` at all: replication's delivered watermark advances from a
 * **tap** (`WatermarkCell.trackDeliveriesOf`, wired by `Replication`), and a
 * targeted [FanOutlet.at] delivery resolves one consumer/tap entry directly
 * rather than iterating `tapOrder`, so it fires no tap and moves no watermark
 * row. Cross-replica settlement reads origin tags out of payloads, a distinct
 * key space, and is likewise unaffected.
 *
 * The counter *is* consumed and `waveState().highWater` *does* rise — but the
 * resulting gaps in the broadcast counter sequence are invisible to every
 * wave-plane observer: `WaveFrontier` keys pending waves by *arrived* timestamp
 * and settles edges by `>=`, nothing enumerates expected counters, and a
 * baseline arm is released immediately and admitted to no completeness set.
 * The single production reader of `waveState()` is
 * [civictech.cell.evolve.Evolution]'s preserved-epoch transfer
 * (`adoptWaveState`), for which counting the reply is **required rather than
 * harmful**: a counter excluded from that high-water is one a promoted
 * candidate would re-mint, aliasing a `(sourceId, counter)` pair a pull reply
 * already used in the same source lane (93 I-14 Rule S1).
 *
 * What the switch would genuinely cost is one counter per link install and one
 * baseline-stamped arrival where an unstamped one arrives today; the residual
 * hazard is an `Effectful` inlet's durable processed frontier (`ManagedHost`'s
 * PORT_API branch, which tests `cell is Effectful && timestamp != null` and does
 * **not** test `ctx.baseline`) — the only counter observer that does not exempt
 * baselines. In-order arrival makes that benign, so it is reachable only where
 * counters can regress in a lane (the landed-RESTART defect C-12, spec 20/21).
 * If PN-2's switch is ever taken, that is the case to test.
 *
 * Full analysis, including the enumerated observer table:
 * `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`
 * §1.2-§1.3. PN-2's replay-is-a-baseline mechanism does not depend on any of it.
 */
fun <D : Any> FanOutlet<Propagate<D>>.catchUpOnLinked(snapshot: () -> D?) {
    linking.onLinkedListeners += { link ->
        snapshot()?.let { at(link.to).propagate(it) }
    }
}

/**
 * Pull-serve (spec 20/21 §Pull, decided 93 I-16/I-24): register a
 * [Protocols.StateRequest] handler on this outlet that answers an on-demand pull
 * with a single-wave state-as-delta baseline reply. Extracted from `SetCell`
 * (PN-9) so pull-serving is an installable outlet policy rather than a hand-rolled
 * one-off — it composes with [catchUpOnLinked] (a separate on-link hook) and any
 * ON-LINK multicast listener. [serve] runs with the outlet as receiver, so it can
 * call [FanOutlet.baselineTo] directly.
 */
fun <Api : Any> FanOutlet<Api>.pullServe(serve: FanOutlet<Api>.(StateRequest) -> Unit) {
    ProtocolSupport.of(this).handle(Protocols.StateRequest) { _, message ->
        serve(message as StateRequest)
    }
    // FU-5: the handler registration IS the offer. Fold BASELINE_SERVING onto the
    // outlet's declared vector so a PullOnOpen consumer's requirement reconciles.
    PortNatures.stamp(this, PortNatures.of(this).with(PullService.BASELINE_SERVING))
}
