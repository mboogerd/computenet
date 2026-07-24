package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface LookupJoinApi<K, V, J, D, R> {
    val fact: Serve<Propagate<MapDelta<K, V>>>
    val dimension: Serve<Propagate<MapDelta<J, D>>>
    val outlet: Subscribe<Propagate<MapDelta<K, R>>>
}

/**
 * Incremental **foreign-key / dimension join** over two map streams — the
 * referential-lookup member of the join family (beside [JoinCell]'s same-key
 * inner join and [CombineLatestCell]'s same-key outer combine). It enriches a
 * **fact** stream keyed by `K` with a **dimension** stream keyed by `J`, each
 * fact projecting to its dimension key via `fk: K -> J`, emitting
 * `combine(k, v, dims[fk(k)])` per fact.
 *
 * `combine`'s `D?` gives **left-outer** semantics: a fact whose dimension row is
 * absent still emits (`D = null`); returning `null` filters the fact out of the
 * output (group-death — e.g. a `need > 0` guard). The output key type is the
 * fact key `K`: the dimension is a lookup table, never a key of the result.
 *
 * Crucially **reactive on both sides**. A change to one dimension row re-emits
 * *every* fact that references it — not just facts arriving afterwards — driven
 * by an internal reverse index `byDim: J -> Set<K>`: a dimension delta touching
 * `j` recomputes exactly `byDim[j]`, never a full fact rescan. All facts touched
 * by one input delta emit as one [MapDelta] under that input's wave (22), so a
 * single dimension change fans out to one wave-grouped output delta.
 *
 * Emission is **effective-only** by value equality of `R` (21): a change leaving
 * a fact's `R` unchanged emits nothing for that fact.
 *
 * Single writer of its output stream, like [GroupByCell] / [CombineLatestCell]:
 * the enriched map is a deterministic function of the two convergent inputs, so
 * peers recompute from their replicated inputs and converge with no cell-level
 * gossip. Inherits [MapDelta]'s documented convergence limit (G-23) — untagged,
 * so concurrent same-key puts resolve by arrival order; single-writer-per-key or
 * single-stream inputs converge.
 */
class LookupJoinCell<K, V, J, D, R>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val fk: (K) -> J,
    private val combine: (K, V, D?) -> R?,
) : LookupJoinCellBase<K, V, J, D, R>(ref), Stateful {
    private val facts = mutableMapOf<K, V>()
    private val dims = mutableMapOf<J, D>()
    private val byDim = mutableMapOf<J, MutableSet<K>>() // reverse index J -> facts referencing it
    private val emitted = mutableMapOf<K, R>() // last published R per fact key (the enriched map)

    /** Convenience: ref-optional, `fk`/`combine`-only. */
    constructor(fk: (K) -> J, combine: (K, V, D?) -> R?) : this(CellRef(UUID.randomUUID()), fk, combine)

    init {
        // late-join catch-up (G-22): the current enriched map as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (emitted.isNotEmpty()) {
                outlet.at(link.to).propagate(MapDelta(emitted.toMap(), emptySet()))
            }
        }
    }

    override fun onFact(value: MapDelta<K, V>) {
        value.puts.forEach { (k, v) ->
            facts[k] = v
            byDim.getOrPut(fk(k)) { mutableSetOf() }.add(k)
        }
        value.removals.forEach { k ->
            if (facts.remove(k) != null) deindex(k)
        }
        emitChanges(value.puts.keys + value.removals)
    }

    override fun onDimension(value: MapDelta<J, D>) {
        // fan-out: one dimension delta recomputes exactly the facts under
        // each touched j (byDim), never a full fact rescan
        val touched = mutableSetOf<K>()
        value.puts.forEach { (j, d) ->
            dims[j] = d
            byDim[j]?.let { touched += it }
        }
        value.removals.forEach { j ->
            dims.remove(j)
            byDim[j]?.let { touched += it } // left-outer: referencing facts re-emit with D=null
        }
        emitChanges(touched)
    }

    private fun deindex(k: K) {
        val j = fk(k)
        byDim[j]?.let { set ->
            set.remove(k)
            if (set.isEmpty()) byDim.remove(j)
        }
    }

    // enrich a live fact with its current dimension value (D? ⇒ left-outer); a
    // key with no fact is dead, never handed to `combine`.
    private fun recompute(k: K): R? {
        val v = facts[k] ?: return null
        return combine(k, v, dims[fk(k)])
    }

    private fun emitChanges(touched: Set<K>) {
        val puts = mutableMapOf<K, R>()
        val removals = mutableSetOf<K>()
        touched.forEach { k ->
            val old = emitted[k]
            when (val now = recompute(k)) {
                null -> if (old != null) { emitted.remove(k); removals += k } // combine→null or fact gone
                old -> Unit // effective-only: value-equals gates emission
                else -> { emitted[k] = now; puts[k] = now }
            }
        }
        if (puts.isNotEmpty() || removals.isNotEmpty()) {
            outlet.call.propagate(MapDelta(puts, removals))
        }
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(facts), HashMap(dims))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (f, d) = state as ArrayList<Serializable>
        facts.clear(); facts.putAll(f as Map<K, V>)
        dims.clear(); dims.putAll(d as Map<J, D>)
        // rebuild the reverse index and the published map from the restored
        // inputs — fk is a pure function of K, so byDim is derivable from facts
        byDim.clear()
        facts.keys.forEach { k -> byDim.getOrPut(fk(k)) { mutableSetOf() }.add(k) }
        emitted.clear()
        facts.keys.forEach { k -> recompute(k)?.let { emitted[k] = it } }
    }
}
