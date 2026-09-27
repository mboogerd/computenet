package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Replicable
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState

/**
 * The destructive half of waterline-driven eviction (spec 24 §Lateness and
 * waterlines, `[24-WL-05]`/`[24-WL-06]`/`[24-WL-18]`), kept behind one seam so
 * the single-instance restriction is checked where the host is a parameter
 * rather than re-derived per operator (an in-class `this is Replicable` on a
 * final class is statically decidable and so says nothing).
 *
 * `[24-WL-18]`: a `Replicable` host's state is refused — eviction is
 * destructive, and on a replicated structure it is safe only once the floor is
 * tied to the replication layer's stable frontier (`Replication.stableFrontier`,
 * lxo-D3), which is a separate item. Until then the seam serves single-instance
 * state only and throws for anything else, leaving the state untouched.
 *
 * Deliberately minimal: [GroupByCell] is today's only caller. KE4.5
 * (`computenet-3vd7k`) is this seam's next owner and grows it into the join
 * family's eviction path.
 */
internal object WaterlineEviction {
    /**
     * Kill every live element of [state] that [evictee] admits and return the
     * killed tags as `dels` (via `TagState.evictBelow`), for the caller to fold
     * through its ordinary retraction path. Throws [IllegalStateException]
     * before touching [state] when [host] is [Replicable] (`[24-WL-18]`).
     */
    fun <E> evict(host: Cell, state: TagState<E>, evictee: (E) -> Boolean): SetDelta<E> {
        check(host !is Replicable<*>) {
            "Cell ${host.ref}: destructive eviction of Replicable state is refused ([24-WL-18]); " +
                "single-instance state only — lifting it ties the floor to Replication.stableFrontier, " +
                "a separate item"
        }
        return state.evictBelow(evictee)
    }
}

/**
 * `[24-WL-17]` per eviction unit: a passed window of [cellRef] keyed [windowKey]
 * holds [exclusiveCount] `Owned`/`Leased` element(s), so the cell refused to
 * evict it — the window is left untouched, nothing is emitted for it, and the
 * exclusives are never taken, released or borrowed by the cell. Every other
 * passed window is still evicted in the same delta.
 *
 * Recorded as cell accounting (`GroupByCell.refusedWindows()` and
 * `refusedEvictions`), **not thrown**: the refusal is per unit, not per
 * `WaterlineDelta`. It is an [IllegalStateException] so a caller that wants to
 * escalate a refusal can throw it as-is. Not routed to an error outlet, which
 * would need a `data -> host` package edge (nt17o-D3).
 *
 * Only a top-level exclusive element is detected: an exclusive nested inside a
 * plain element (a `Pair<Owned<..>, ..>`) is not — the payload walk that could
 * find it is private to `Proxy.discharge`, and the container gap is filed as
 * computenet-woto.
 */
class ExclusiveEvictionRefused(
    val cellRef: CellRef,
    val windowKey: Any?,
    val exclusiveCount: Int,
) : IllegalStateException(
    "Cell $cellRef: eviction of passed window $windowKey refused ([24-WL-17]): it holds " +
        "$exclusiveCount Owned/Leased element(s) whose obligation the cell may not discharge; " +
        "the window stays live until its exclusives are retracted",
)
