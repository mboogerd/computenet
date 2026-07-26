package civictech.cell.port

import civictech.cell.data.Propagate

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
 * baseline. That change is deferred: [FanOutlet.baselineTo] consumes the
 * outlet's own wave counter (the I-16 reply-sequencing rule), which inflates the
 * `waveState().highWater` that [civictech.cell.replication] reads directly as a
 * source's delivered high-water — a counter-neutral baseline emission is the
 * prerequisite and belongs with the `Baseline`/`StateRequest` consolidation, not
 * this ticket. PN-2's replay-is-a-baseline mechanism does not depend on it.
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
}
