package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.MapDelta

@CellBase
interface JoinApi<K, V, W> {
    val left: Serve<Propagate<MapDelta<K, V>>>
    val right: Serve<Propagate<MapDelta<K, W>>>
    val outlet: Subscribe<Propagate<MapDelta<K, Pair<V, W>>>>
}

/**
 * Incremental keyed inner join over two map streams: a key appears downstream
 * while both sides hold it; either side's put refreshes the pair, either
 * side's removal retracts it. Inherits [MapDelta]'s documented convergence
 * limit (G-23): untagged, so concurrent same-key puts resolve by arrival
 * order — single-writer-per-key or single-stream inputs converge.
 *
 * **G-23 discharged for tagged-map-fed inputs (96 §E1.5).** As for
 * [CombineLatestCell]/[LookupJoinCell]: when the `left` or `right` arm's
 * `MapDelta` arrives via `OrMapCell` → [civictech.cell.data.op.UntagCell]
 * rather than a raw multi-writer `MapDelta` source, that arm is no longer
 * arrival-order biased — `UntagCell` projects the OR-map's already-converged,
 * dot-resolved exposed value, so two peers each running `OrMapCell →
 * UntagCell → JoinCell` over the same replicated map converge identically.
 * This cell is functionally unchanged either way — the discharge is a fact
 * about the upstream composition, not a code path here.
 */
class JoinCell<K, V, W>(ref: CellRef = CellRef(UUID.randomUUID())) :
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, which is
    // untouched.
    JoinCellBase<K, V, W>(ref), Stateful, BoundedStateful {
    private val leftMap = mutableMapOf<K, V>()
    private val rightMap = mutableMapOf<K, W>()

    init {
        // late-join catch-up (G-22): the current join as a delta-from-empty
        outlet.catchUpOnLinked {
            joined().takeIf { it.isNotEmpty() }?.let { MapDelta(it, emptySet()) }
        }
    }

    override fun onLeft(value: MapDelta<K, V>) {
        val puts = mutableMapOf<K, Pair<V, W>>()
        val removals = mutableSetOf<K>()
        value.puts.forEach { (k, v) ->
            leftMap[k] = v
            rightMap[k]?.let { w -> puts[k] = v to w }
        }
        value.removals.forEach { k ->
            if (leftMap.remove(k) != null && k in rightMap) removals += k
        }
        emit(puts, removals)
    }

    override fun onRight(value: MapDelta<K, W>) {
        val puts = mutableMapOf<K, Pair<V, W>>()
        val removals = mutableSetOf<K>()
        value.puts.forEach { (k, w) ->
            rightMap[k] = w
            leftMap[k]?.let { v -> puts[k] = v to w }
        }
        value.removals.forEach { k ->
            if (rightMap.remove(k) != null && k in leftMap) removals += k
        }
        emit(puts, removals)
    }

    private fun joined(): Map<K, Pair<V, W>> =
        leftMap.mapNotNull { (k, v) -> rightMap[k]?.let { w -> k to (v to w) } }.toMap()

    private fun emit(puts: Map<K, Pair<V, W>>, removals: Set<K>) {
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        } else {
            outlet.absorbAck() // a key present on only one side — ack the swallowed wave (CP-A3)
        }
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(leftMap), HashMap(rightMap))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (l, r) = state as ArrayList<Serializable>
        leftMap.clear(); leftMap.putAll(l as Map<K, V>)
        rightMap.clear(); rightMap.putAll(r as Map<K, W>)
    }

    /**
     * One page of this join's two input indexes (V1C-OPS).
     *
     * | ordinal | sub-state | key | value |
     * |---|---|---|---|
     * | 0 | `"left"` | `K` | `V` |
     * | 1 | `"right"` | `K` | `W` |
     *
     * Same order as [snapshot]'s `arrayListOf(leftMap, rightMap)`. **The two
     * share key type `K` and overlap in content** — a joined key is in both —
     * which is exactly why an entry is `(subState, key)` and not `key`
     * (Decision A): a key held on both sides is *two* entries, one per side,
     * never one collapsed entry and never one key returned twice. The cursor is
     * lexicographic `(subStateOrdinal, key)` over the two frozen key sequences
     * ([OperatorPaging]).
     *
     * The **joined output** is not paged: it is derived (`joined()`
     * recomputes it from the two inputs on demand) and is not in [snapshot], so
     * Decision E keeps it out. A bounded read of a `JoinCell` shows the two
     * input sides, not the pairs.
     *
     * [StatePage.frontier] is null: `MapDelta` is untagged (G-23), so this cell
     * holds no tags to build a frontier from. That makes `StatePage`'s
     * across-page stability check and the `since` escalation path unavailable
     * here, and [supportsSince] stays `false` — the request is refused on the
     * caller's thread rather than answered wider than asked.
     *
     * `[24-OP-JOIN-01]` is untouched: this method only reads.
     */
    override fun readBounded(request: StateRead): StatePage =
        pageOver(request, listOf(mapSubState("left", leftMap), mapSubState("right", rightMap)))
}
