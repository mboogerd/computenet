package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface CombineLatestApi<K, V, W, R> {
    val left: Serve<Propagate<MapDelta<K, V>>>
    val right: Serve<Propagate<MapDelta<K, W>>>
    val outlet: Subscribe<Propagate<MapDelta<K, R>>>
}

/**
 * Incremental keyed *outer* combine over two map streams — the outer sibling of
 * [JoinCell]. Holds the latest value per key from each side and emits a combined
 * value on any change, with **outer** semantics: a key present on only one side
 * still produces output, computed as `combine(k, v, null)` / `combine(k, null, w)`;
 * a key on both sides is `combine(k, v, w)`. `combine` returning `null` drops the
 * key from the output (group-death / filtering — e.g. "only emit keys both sides
 * hold"), and a key absent from *both* sides is likewise removed regardless of
 * what `combine` would return, so there are no ghost keys.
 *
 * Emission is **effective-only** by value equality of `R` (21): a delta that
 * leaves a key's combined value unchanged emits nothing for that key. All keys
 * touched by one input delta emit as one [MapDelta] under that input's wave (22).
 *
 * Single writer of its output stream — so not `Replicable`, like [GroupByCell]:
 * the combined map is a deterministic function of the two convergent inputs, so
 * peers recompute from their replicated inputs and converge with no cell-level
 * gossip. Inherits [MapDelta]'s documented convergence limit (G-23) exactly as
 * [JoinCell] does — untagged, so concurrent same-key puts resolve by arrival
 * order; single-writer-per-key or single-stream inputs converge.
 */
class CombineLatestCell<K, V, W, R>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val combine: (K, V?, W?) -> R?,
) : CombineLatestCellBase<K, V, W, R>(ref), Stateful {
    private val leftMap = mutableMapOf<K, V>()
    private val rightMap = mutableMapOf<K, W>()
    private val emitted = mutableMapOf<K, R>() // last published R per key (the combined map)

    init {
        // late-join catch-up (G-22): the current combined map as a delta-from-empty
        outlet.catchUpOnLinked { if (emitted.isEmpty()) null else MapDelta(emitted.toMap(), emptySet()) }
    }

    override fun onLeft(value: MapDelta<K, V>) {
        value.puts.forEach { (k, v) -> leftMap[k] = v }
        value.removals.forEach { leftMap.remove(it) }
        emitChanges(value.puts.keys + value.removals)
    }

    override fun onRight(value: MapDelta<K, W>) {
        value.puts.forEach { (k, w) -> rightMap[k] = w }
        value.removals.forEach { rightMap.remove(it) }
        emitChanges(value.puts.keys + value.removals)
    }

    // combine over the current latest-value pair; a key absent from both sides is
    // dead (group-death by absence), never handed to `combine` as (null, null).
    private fun recompute(k: K): R? =
        if (k in leftMap || k in rightMap) combine(k, leftMap[k], rightMap[k]) else null

    private fun emitChanges(touched: Set<K>) {
        val puts = mutableMapOf<K, R>()
        val removals = mutableSetOf<K>()
        touched.forEach { k ->
            val old = emitted[k]
            when (val now = recompute(k)) {
                null -> if (old != null) { emitted.remove(k); removals += k } // combine→null or absent-both
                old -> Unit // effective-only: value-equals gates emission
                else -> { emitted[k] = now; puts[k] = now }
            }
        }
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        }
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(leftMap), HashMap(rightMap))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (l, r) = state as ArrayList<Serializable>
        leftMap.clear(); leftMap.putAll(l as Map<K, V>)
        rightMap.clear(); rightMap.putAll(r as Map<K, W>)
        // rebuild the published map by recomputation over the restored inputs
        emitted.clear()
        (leftMap.keys + rightMap.keys).forEach { k -> recompute(k)?.let { emitted[k] = it } }
    }
}
