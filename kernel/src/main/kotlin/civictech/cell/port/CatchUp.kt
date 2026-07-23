package civictech.cell.port

import civictech.cell.data.Propagate

/**
 * Late-join catch-up (G-22): on every new link, send the current state as a
 * delta-from-empty to just the new subscriber. [snapshot] returns null when
 * there is nothing to catch up (the empty-state guard every hand-rolled copy
 * of this block carried). Installs [LinkSupport.onLinked] — the single-slot
 * hook; a cell needing additional onLinked behavior composes it manually.
 */
fun <D : Any> FanOutlet<Propagate<D>>.catchUpOnLinked(snapshot: () -> D?) {
    linking.onLinked = { link ->
        snapshot()?.let { at(link.to).propagate(it) }
    }
}
