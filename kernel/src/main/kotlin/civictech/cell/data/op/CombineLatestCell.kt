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
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.view.MapDiffPublisher

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
 * The diff/emit fold is [MapDiffPublisher] (RS-5.4: adopted here since it was a
 * byte-for-byte copy of that helper's `publish`/`catchUpDelta`). A wave whose
 * recompute leaves **every** touched key unchanged emits nothing at all, and
 * therefore **absorb-acks** it (`cell.control.absorbAck`, CP-A3, via
 * [emitOrAbsorb]) so a downstream glitch-free join's per-source watermark still
 * advances past the wave this cell silently swallowed — spec 20/22
 * §Completeness over silent or stuck edges, whose MUST this cell used to be the
 * one flagged divergence from (96 §E2.2 residual, closed).
 *
 * ### `emitOnFrontier` — the opt-in null-extension gate (20/24, 96 §E2.4)
 *
 * A key present on only one side emits `combine(k, v, null)`: an **absence
 * assertion**, and the internal-consistency essay's exact outer-join failure
 * mode — ungated, a null-extended row can ride the outlet and be retracted
 * moments later in the *same* wave, as the other side's real value arrives on
 * the sibling inlet. Constructed with `emitOnFrontier = true`, this cell instead
 * buffers each wave's input deltas across both inlets ([WaveGate]), folds them
 * together at wave completeness, and recomputes the touched keys **once**
 * against both sides' settled state — so a null-extension for a key whose other
 * side arrived in the same wave is never emitted, while a genuinely one-sided
 * key still null-extends at completeness (outer semantics unchanged; only the
 * timing gates). The default stays ungated and byte-identical. Read
 * [WaveGate]'s phantom-expected-edge caveat before enabling it: the gate suits
 * the shared-source diamond, not two independent roots.
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
    /**
     * Opt-in frontier-gated emission (20/24, 96 §E2.4) — see the class KDoc.
     * `false` (the default) is the shipped, ungated behavior, unchanged.
     *
     * It sits *before* [combine] deliberately: [combine] stays the last
     * parameter so every existing trailing-lambda construction
     * (`CombineLatestCell<…>(ref) { k, v, w -> … }`) keeps compiling untouched.
     */
    emitOnFrontier: Boolean = false,
    private val combine: (K, V?, W?) -> R?,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : CombineLatestCellBase<K, V, W, R>(ref), Stateful, BoundedStateful {
    private val leftMap = mutableMapOf<K, V>()
    private val rightMap = mutableMapOf<K, W>()
    private val publisher = MapDiffPublisher<K, R>() // last-published R per key (the combined map)

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<K>? =
        if (!emitOnFrontier) null
        else WaveGate(left, right) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

    init {
        // late-join catch-up (G-22): the current combined map as a delta-from-empty
        outlet.catchUpOnLinked { publisher.catchUpDelta() }
    }

    override fun onLeft(value: MapDelta<K, V>) {
        val fold = GatedFold { applyLeft(value) }
        if (gate?.offerLeft(fold) == true) return
        emitChanges(fold.applyAndTouch())
    }

    override fun onRight(value: MapDelta<K, W>) {
        val fold = GatedFold { applyRight(value) }
        if (gate?.offerRight(fold) == true) return
        emitChanges(fold.applyAndTouch())
    }

    /** Fold a left delta into the latest-value map; returns the keys it touches. */
    private fun applyLeft(value: MapDelta<K, V>): Set<K> {
        value.puts.forEach { (k, v) -> leftMap[k] = v }
        value.removals.forEach { leftMap.remove(it) }
        return value.puts.keys + value.removals
    }

    /** Fold a right delta into the latest-value map; returns the keys it touches. */
    private fun applyRight(value: MapDelta<K, W>): Set<K> {
        value.puts.forEach { (k, w) -> rightMap[k] = w }
        value.removals.forEach { rightMap.remove(it) }
        return value.puts.keys + value.removals
    }

    /**
     * One completed wave (gated only): fold **both** sides' buffered deltas,
     * then recompute the union of their touched keys once, against the settled
     * state — which is what makes a same-wave null-extension unobservable. The
     * emission runs inside the buffered context so the outlet's reactive
     * stamping keys it to the completed input wave, however completeness was
     * reached; a wave known only from acks carries no context of its own, so its
     * ack is minted from the wave position directly ([CoalescingCombineCell]'s
     * pattern).
     */
    private fun flush(timestamp: Timestamp, context: MessageContext?, folds: List<GatedFold<K>>) {
        val touched = LinkedHashSet<K>()
        folds.forEach { touched += it.applyAndTouch() }
        CurrentContext.with(context ?: MessageContext(timestamp, outlet.ref)) { emitChanges(touched) }
    }

    // combine over the current latest-value pair; a key absent from both sides is
    // dead (group-death by absence), never handed to `combine` as (null, null).
    private fun recompute(k: K): R? =
        if (k in leftMap || k in rightMap) combine(k, leftMap[k], rightMap[k]) else null

    /**
     * Effective-only emit, or absorb-ack the wave this cell swallowed (CP-A3,
     * spec 20/22 §Completeness over silent or stuck edges): a value-equal
     * recompute leaves the publisher with nothing to emit, and without the ack a
     * downstream glitch-free join could only settle this arm from a *later* real
     * change. [civictech.cell.control.absorbAck] itself skips baseline and
     * spontaneous (context-free) emissions, so the `onLinked` catch-up path
     * above needs no special-casing.
     */
    private fun emitChanges(touched: Set<K>) {
        val delta = publisher.publish(touched, ::recompute)
        emitOrAbsorb(
            delta == null,
            emit = { outlet.call.propagate(delta!!) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the gate's transient
     * wave buffer is dropped — its deltas were never folded into [leftMap] /
     * [rightMap] and never observed downstream.
     */
    override fun onDeactivate(ctx: CellContext) {
        gate?.clear()
    }

    override fun snapshot(): Serializable = arrayListOf(HashMap(leftMap), HashMap(rightMap))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (l, r) = state as ArrayList<Serializable>
        leftMap.clear(); leftMap.putAll(l as Map<K, V>)
        rightMap.clear(); rightMap.putAll(r as Map<K, W>)
        // rebuild the published map by recomputation over the restored inputs
        val rebuilt = mutableMapOf<K, R>()
        (leftMap.keys + rightMap.keys).forEach { k -> recompute(k)?.let { rebuilt[k] = it } }
        publisher.reset(rebuilt)
    }

    /**
     * One page of this combine's two input sides (V1C-OPS).
     *
     * | ordinal | sub-state | key | value |
     * |---|---|---|---|
     * | 0 | `"left"` | `K` | `V` |
     * | 1 | `"right"` | `K` | `W` |
     *
     * Same order as [snapshot]'s `arrayListOf(leftMap, rightMap)`; the two share
     * key type `K` and overlap, so a key held on both sides is *two* labelled
     * entries (Decision A), and the cursor is lexicographic
     * `(subStateOrdinal, key)` ([OperatorPaging]).
     *
     * The **combined output map** is not paged: `publisher` is rebuilt from the
     * restored inputs by [restore] and is not in [snapshot], so Decision E keeps
     * it out. A bounded read of a `CombineLatestCell` shows the two inputs, not
     * `combine`'s results.
     *
     * [StatePage.frontier] is null — `MapDelta` is untagged (G-23) — so the
     * across-page stability check and the `since` escalation path are
     * unavailable and [supportsSince] stays `false`. No `[24-OP-*]` requirement
     * id covers this cell; the contract preserved is its own KDoc, and this
     * method only reads.
     */
    override fun readBounded(request: StateRead): StatePage =
        pageOver(request, listOf(mapSubState("left", leftMap), mapSubState("right", rightMap)))
}
