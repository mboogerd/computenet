package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.view.MapDiffPublisher

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
 * a fact's `R` unchanged emits nothing for that fact. The diff/emit fold is
 * [MapDiffPublisher] (RS-5.4: adopted here since it was a byte-for-byte copy of
 * that helper's `publish`/`catchUpDelta`) — the catch-up DELIVERY mechanism
 * stays the original per-link `onLinked` unicast, only its delta content now
 * comes from the publisher.
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
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : LookupJoinCellBase<K, V, J, D, R>(ref), Stateful, BoundedStateful {
    private val facts = mutableMapOf<K, V>()
    private val dims = mutableMapOf<J, D>()
    private val byDim = mutableMapOf<J, MutableSet<K>>() // reverse index J -> facts referencing it
    private val publisher = MapDiffPublisher<K, R>() // last-published R per fact key (the enriched map)

    /** Convenience: ref-optional, `fk`/`combine`-only. */
    constructor(fk: (K) -> J, combine: (K, V, D?) -> R?) : this(CellRef(UUID.randomUUID()), fk, combine)

    init {
        // late-join catch-up (G-22): the current enriched map as a delta-from-empty
        outlet.linking.onLinked = { link ->
            publisher.catchUpDelta()?.let { outlet.at(link.to).propagate(it) }
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
        publisher.publish(touched, ::recompute)?.let { outlet.call.propagate(it) }
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
        val rebuilt = mutableMapOf<K, R>()
        facts.keys.forEach { k -> recompute(k)?.let { rebuilt[k] = it } }
        publisher.reset(rebuilt)
    }

    /**
     * One page of this lookup join's two inputs (V1C-OPS).
     *
     * | ordinal | sub-state | key | value |
     * |---|---|---|---|
     * | 0 | `"facts"` | `K` | `V` |
     * | 1 | `"dims"` | `J` | `D` |
     *
     * Same order as [snapshot]'s `arrayListOf(facts, dims)`. The two key spaces
     * are **different types** here, which is precisely why one cursor has to
     * order *across* them rather than within one: the cursor is lexicographic
     * `(subStateOrdinal, key)` over the two frozen key sequences, so a resume
     * that exhausts `"facts"` continues at the head of `"dims"`
     * ([OperatorPaging], Decision B). It is also why a page is only meaningful
     * with the label: a `K` and a `J` can be the same runtime value.
     *
     * **Neither the reverse index nor the enriched output is paged.** `byDim`
     * and `publisher` are rebuilt from the restored inputs by [restore] and are
     * not in [snapshot], so Decision E keeps them out: a bounded read of a
     * `LookupJoinCell` shows the **fact and dimension inputs**, not the enriched
     * output map.
     *
     * [StatePage.frontier] is null — `MapDelta` is untagged (G-23) — so the
     * across-page stability check and the `since` escalation path are
     * unavailable and [supportsSince] stays `false`. No `[24-OP-*]` requirement
     * id covers this cell; the contract preserved is its own KDoc, and this
     * method only reads.
     */
    override fun readBounded(request: StateRead): StatePage =
        pageOver(request, listOf(mapSubState("facts", facts), mapSubState("dims", dims)))
}
