package civictech.cell.port

import civictech.cell.data.Propagate

/**
 * Late-join catch-up (G-22): on every new link, send the current state as a
 * delta-from-empty to just the new subscriber. [snapshot] returns null when
 * there is nothing to catch up (the empty-state guard every hand-rolled copy
 * of this block carried). Installs [LinkSupport.onLinked] — the single-slot
 * hook; a cell needing additional onLinked behavior composes it manually.
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
    linking.onLinked = { link ->
        snapshot()?.let { at(link.to).propagate(it) }
    }
}
