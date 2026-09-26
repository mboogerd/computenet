package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.control.absorbAck
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
 * comes from the publisher. A wave whose recompute leaves **every** touched
 * fact unchanged — the commonest case being a dimension delta that no live fact
 * references, or one whose new value `combine` ignores — emits nothing at all
 * and therefore **absorb-acks** it (`cell.control.absorbAck`, CP-A3, via
 * [emitOrAbsorb]) so a downstream glitch-free join's per-source watermark still
 * advances past the wave this cell silently swallowed (spec 20/22 §Completeness
 * over silent or stuck edges; the second, unflagged half of 96 §E2.2's
 * value-equal-swallow residual, closed).
 *
 * Single writer of its output stream, like [GroupByCell] / [CombineLatestCell]:
 * the enriched map is a deterministic function of the two convergent inputs, so
 * peers recompute from their replicated inputs and converge with no cell-level
 * gossip. Inherits [MapDelta]'s documented convergence limit (G-23) — untagged,
 * so concurrent same-key puts resolve by arrival order; single-writer-per-key or
 * single-stream inputs converge.
 *
 * **G-23 discharged for tagged-map-fed inputs (96 §E1.5).** As for
 * [CombineLatestCell]: when the `fact` or `dimension` arm's `MapDelta` arrives
 * via `OrMapCell` → [civictech.cell.data.op.UntagCell] rather than a raw
 * multi-writer `MapDelta` source, that arm is no longer arrival-order biased —
 * `UntagCell` projects the OR-map's already-converged, dot-resolved exposed
 * value, so two peers each running `OrMapCell → UntagCell → LookupJoinCell`
 * over the same replicated map converge identically. This cell is functionally
 * unchanged either way — the discharge is a fact about the upstream
 * composition, not a code path here.
 *
 * ### `emitOnFrontier` — the opt-in net-effect gate (KE2 §5.2, `[24-OP-SEMIJOIN-04]`'s shape)
 *
 * Ungated, a fact whose dimension row is retracted in the *same* wave that the
 * fact arrives can ride the outlet enriched and be re-emitted null-extended
 * moments later (or the reverse) — one key put twice with different values
 * inside one wave of a shared-source diamond. Constructed with
 * `emitOnFrontier = true`, this cell instead buffers each wave's `fact` and
 * `dimension` deltas ([WaveGate]), folds them together at wave completeness,
 * and publishes the touched facts **once** against the settled state. Because
 * [MapDiffPublisher] is effective-only, that one publish *is* the wave's net
 * diff: a fact whose enriched value ends the wave where it started emits
 * nothing, and a wave whose net effect is empty absorb-acks instead. The
 * default stays ungated and byte-identical. Read [WaveGate]'s
 * phantom-expected-edge caveat before enabling it: the gate suits the
 * shared-source diamond, not two independent roots.
 */
class LookupJoinCell<K, V, J, D, R>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val fk: (K) -> J,
    /**
     * Opt-in frontier-gated emission (KE2 §5.2) — see the class KDoc. `false`
     * (the default) is the shipped, ungated behavior, unchanged.
     *
     * It sits *before* [combine] deliberately: [combine] stays the last
     * parameter so every existing trailing-lambda construction
     * (`LookupJoinCell<…>(ref, fk = …) { k, v, d -> … }`) keeps compiling
     * untouched.
     */
    emitOnFrontier: Boolean = false,
    private val combine: (K, V, D?) -> R?,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : LookupJoinCellBase<K, V, J, D, R>(ref), Stateful, BoundedStateful, FrontierGateable {
    private val facts = mutableMapOf<K, V>()
    private val dims = mutableMapOf<J, D>()
    private val byDim = mutableMapOf<J, MutableSet<K>>() // reverse index J -> facts referencing it
    private val publisher = MapDiffPublisher<K, R>() // last-published R per fact key (the enriched map)

    /** [FrontierGateable]: `true` iff constructed with `emitOnFrontier = true`. */
    override val frontierGated: Boolean = emitOnFrontier

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<K>? =
        if (!emitOnFrontier) null
        else WaveGate(fact, dimension) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

    /** Convenience: ref-optional, `fk`/`combine`-only. */
    constructor(fk: (K) -> J, combine: (K, V, D?) -> R?) : this(CellRef(UUID.randomUUID()), fk, combine = combine)

    init {
        // late-join catch-up (G-22): the current enriched map as a delta-from-empty
        outlet.linking.onLinked = { link ->
            publisher.catchUpDelta()?.let { outlet.at(link.to).propagate(it) }
        }
    }

    override fun onFact(value: MapDelta<K, V>) {
        if (gate?.offerLeft(GatedFold { applyFact(value) }) == true) return
        // The ungated path below is the shipped handler verbatim, so
        // `emitOnFrontier = false` stays byte-identical. A gated cell also lands
        // here for a delta the gate admits to no completeness set (catch-up,
        // straggler, unmatched edge): applying and emitting it immediately is
        // exactly right.
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
        if (gate?.offerRight(GatedFold { applyDimension(value) }) == true) return
        // ungated (or gate-exempt) — the shipped handler verbatim; see [onFact].
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

    // ---- the emitOnFrontier path: apply now, publish at completeness ----

    /** Fold a fact delta into [facts] and [byDim]; returns the fact keys it touches. */
    private fun applyFact(value: MapDelta<K, V>): Set<K> {
        value.puts.forEach { (k, v) ->
            facts[k] = v
            byDim.getOrPut(fk(k)) { mutableSetOf() }.add(k)
        }
        value.removals.forEach { k ->
            if (facts.remove(k) != null) deindex(k)
        }
        return value.puts.keys + value.removals
    }

    /**
     * Fold a dimension delta into [dims]; returns the facts referencing any
     * touched `j`, read off [byDim] as it stands *now*. A fact the same wave
     * adds after this fold is touched by its own fold, and one it removes
     * before is dead at flush either way, so the union over the wave's folds
     * is order-independent.
     */
    private fun applyDimension(value: MapDelta<J, D>): Set<K> {
        value.puts.forEach { (j, d) -> dims[j] = d }
        value.removals.forEach { dims.remove(it) }
        val touched = LinkedHashSet<K>()
        (value.puts.keys + value.removals).forEach { j -> byDim[j]?.let { touched += it } }
        return touched
    }

    /**
     * One completed wave (gated only): apply **both** inlets' buffered deltas in
     * arrival order, then publish the union of touched facts once against the
     * settled state — [MapDiffPublisher] diffs against the last *published*
     * value, so this is the wave's net effect. The emission runs inside the
     * buffered context so the outlet's reactive stamping keys it to the
     * completed input wave; a wave known only from acks carries no context of
     * its own, so its ack is minted from the wave position directly
     * ([CoalescingCombineCell]'s pattern).
     */
    private fun flush(timestamp: Timestamp, context: MessageContext?, folds: List<GatedFold<K>>) {
        val touched = LinkedHashSet<K>()
        folds.forEach { touched += it.applyAndTouch() }
        CurrentContext.with(context ?: MessageContext(timestamp, outlet.ref)) { emitChanges(touched) }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the gate's transient
     * wave buffer is dropped — its deltas were never folded into [facts] /
     * [dims] and never observed downstream.
     */
    override fun onDeactivate(ctx: CellContext) {
        gate?.clear()
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

    /**
     * Effective-only emit, or absorb-ack the wave this cell swallowed (CP-A3,
     * spec 20/22 §Completeness over silent or stuck edges): a value-equal
     * recompute — or an empty `touched` set, the dimension delta that no live
     * fact references — leaves the publisher with nothing to emit, and without
     * the ack a downstream glitch-free join could only settle this arm from a
     * *later* real change. [civictech.cell.control.absorbAck] itself skips
     * baseline and spontaneous (context-free) emissions, so the per-link
     * `onLinked` catch-up unicast above needs no special-casing.
     */
    private fun emitChanges(touched: Set<K>) {
        val delta = publisher.publish(touched, ::recompute)
        emitOrAbsorb(
            delta == null,
            emit = { outlet.call.propagate(delta!!) },
            absorbAck = { outlet.absorbAck() },
        )
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
