package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ExclusiveEntry
import civictech.cell.Timestamp
import civictech.cell.data.Replicable
import civictech.cell.data.Windows
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
 * **Callers and their units.** [GroupByCell] evicts by *window*
 * (`[24-WL-06]`): a window passes once `keyTime(k) <= floor`, and a window is
 * never evicted piecemeal. The join family — [JoinSetCell] today (KE4.5,
 * `computenet-3vd7k`), [SemiJoinCell] and [IntersectSetCell] on the same
 * pattern — evicts by *row* (`[24-WL-16]`), using [lateSplit] for the
 * arrival guard and [passedRows] to pick the unit.
 *
 * **The row rule — `[24-WL-09]` in row form.** A row of a lateness-declaring
 * inlet is evicted iff that inlet's `timeFn(row)` lies strictly below the
 * floor: exactly the `[24-WL-07]` late-drop threshold. So an evicted row can
 * be neither re-admitted (its re-add is late-dropped by [lateSplit]) nor
 * retracted (its later del finds no live tag and is a no-op, `[24-WL-08]`) —
 * the state no admissible del can reach is exactly the state removed. A row
 * of an inlet declaring no lateness is never evicted, and a windowed join's
 * window may be evicted in part. Minted pairs/entries are **never** a unit:
 * the evicted rows' dels (this function's result) go through the caller's
 * ordinary fold, and a pair/entry leaves only as that fold's consequence,
 * with its advertised exit tag (M11.2 tag hygiene). Since a later del of an
 * evicted row is a no-op, a pair never exits twice.
 *
 * **Exclusives (`[24-WL-17]`).** A unit holding an `Owned`/`Leased` element is
 * refused — per window for [GroupByCell], per row for the join family
 * ([passedRows]'s second half) — and recorded as [ExclusiveEvictionRefused],
 * never thrown and never discharged.
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

    /**
     * The `[24-WL-07]` arrival guard for one inlet, row form: split [value]'s
     * adds at `lateness.timeFn(e) < floor` (strict — an add *at* the floor is
     * admitted). Returns the admitted delta — the remaining adds and **every**
     * del untouched, since dels are never filtered by time (`[24-WL-08]`) — and
     * the dropped adds, tags verbatim, for the caller's late outlet. Returns
     * [value] itself when nothing is dropped.
     */
    fun <E> lateSplit(
        lateness: Windows.Lateness<E>,
        floor: Long,
        value: SetDelta<E>,
    ): Pair<SetDelta<E>, Map<E, Set<Timestamp>>> {
        val (below, rest) = value.adds.entries.partition { lateness.timeFn(it.key) < floor }
        if (below.isEmpty()) return value to emptyMap()
        return SetDelta(rest.associate { it.key to it.value }, value.dels) to below.associate { it.key to it.value }
    }

    /**
     * A gated cell's flush-time counterpart of [lateSplit]: [value] was
     * admitted at arrival, but a floor rise has since passed some of its adds
     * while the wave sat in the `WaveGate`. Those rows are **evicted before
     * they land** — removed from the adds, not forwarded late and not counted
     * as dropped, because they were admitted, not late — exactly what the
     * ungated cell under the same arrival order does (admit, then evict at the
     * rise), so the settled state holds no row the floor has passed
     * (`[24-WL-16]`, `[24-WL-19]`, `[24-WL-10]`). An exclusive add is kept — it
     * lands live rather than being silently dropped — and returned in the
     * second component, one entry per kept row, for the caller to record as an
     * immediate `[24-WL-17]` refusal (`ExclusiveEvictionRefused`) rather than
     * waiting for the next floor rise to re-evaluate it (`[24-WL-17]`,
     * `[24-WL-10]`'s "less any whose eviction refused"). Dels are untouched
     * (`[24-WL-08]`). Returns [value] itself (with an empty refusal list) when
     * nothing passed.
     */
    fun <E> dropPassedAdds(lateness: Windows.Lateness<E>, floor: Long, value: SetDelta<E>): Pair<SetDelta<E>, List<E>> {
        val refused = mutableListOf<E>()
        val kept = value.adds.filterKeys { row ->
            val passed = lateness.timeFn(row) < floor
            if (passed && ExclusiveEntry.isExclusive(row)) refused += row
            !passed || ExclusiveEntry.isExclusive(row)
        }
        val result = if (kept.size == value.adds.size) value else SetDelta(kept, value.dels)
        return result to refused
    }

    /**
     * The row-form eviction units of [state] at [floor] (`[24-WL-16]`): every
     * live row whose `lateness.timeFn(row)` is strictly below [floor], split
     * into the evictees (to hand to [evict]) and the rows refused because they
     * are themselves `Owned`/`Leased` (`[24-WL-17]`, detected by
     * [ExclusiveEntry.isExclusive] without borrowing or taking them).
     */
    fun <E> passedRows(state: TagState<E>, lateness: Windows.Lateness<E>, floor: Long): Pair<Set<E>, List<E>> {
        val evictees = LinkedHashSet<E>()
        val refused = mutableListOf<E>()
        state.elements.forEach { row ->
            if (lateness.timeFn(row) >= floor) return@forEach
            if (ExclusiveEntry.isExclusive(row)) refused += row else evictees += row
        }
        return evictees to refused
    }
}

/**
 * `[24-WL-17]` per eviction unit: a passed unit of [cellRef] — a window keyed
 * [windowKey] for [GroupByCell] ([unit] `"window"`), or for the join family
 * the row itself ([unit] `"row"`, [windowKey] the row) — holds
 * [exclusiveCount] `Owned`/`Leased` element(s), so the cell refused to evict
 * it — the unit is left untouched, nothing is emitted for it, and the
 * exclusives are never taken, released or borrowed by the cell. Every other
 * passed unit is still evicted in the same delta.
 *
 * Recorded as cell accounting (`GroupByCell.refusedWindows()`,
 * `JoinSetCell.refusedRows()`, and each cell's `refusedEvictions`), **not
 * thrown**: the refusal is per unit, not per
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
    /** The eviction unit's name in the message only: `"window"` (default, [GroupByCell]) or `"row"` (join family). */
    val unit: String = "window",
) : IllegalStateException(
    "Cell $cellRef: eviction of passed $unit $windowKey refused ([24-WL-17]): it holds " +
        "$exclusiveCount Owned/Leased element(s) whose obligation the cell may not discharge; " +
        "the $unit stays live until its exclusives are retracted",
)
