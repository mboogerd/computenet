package civictech.cell.host

import civictech.cell.CellRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Refs whose delivery is deliberately parked for a repartition flip window
 * (spec 20/24 §Partitioned state "park the flip window", [24-PART-04], CP-D4):
 * per-ref, so a held key range confines its parking to itself — every other
 * range flows unblocked (the funnel rule, 93 I-19). This reuses the ordinary
 * park/replay path, not a second buffer — [onRelease] is expected to invoke
 * the caller's existing replay so a held ref's parked queue drains through
 * the same path it would have taken unheld.
 *
 * Boundary: a hold binds only the paths that consult it. [LocationRegistry]'s
 * `install` (the publish path) drains a ref's parked queue into the newly
 * installed location without consulting this set at all, so a publish during
 * an active flip window drains despite the hold. That asymmetry is current
 * behaviour, pinned by `RepartitionHoldTest`'s "BS-10 install drains despite
 * an active hold" and filed as OQ-3 on epic computenet-iyi — not fixed here.
 */
class DeliveryHold(private val onRelease: (CellRef) -> Unit) {

    private val held = ConcurrentHashMap.newKeySet<CellRef>()

    /** Park [ref]'s deliveries (per-ref, funnel rule) until [release] — the flip-window buffer. */
    fun hold(ref: CellRef) {
        held += ref
    }

    /**
     * Is [ref] currently held (mid-migration)? Read-only view of the same
     * flip-window set (spec 20/24 §Partitioned state, PN-5): a scatter-gather
     * pull leg to a migrating shard defers rather than reading torn state — the
     * consumer's per-shard `since` makes the deferred leg's later pull fresh.
     */
    fun isHeld(ref: CellRef): Boolean = ref in held

    /**
     * Stop holding [ref] and drain everything parked during the window, in park
     * order, by invoking [onRelease] unconditionally — including when [ref] was
     * never held, or has no current location, in which case this is a harmless
     * no-op.
     */
    fun release(ref: CellRef) {
        held -= ref
        onRelease(ref)
    }
}
