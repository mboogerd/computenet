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
 *
 * ### `emitOnFrontier` — the opt-in net-effect gate (KE2 §5.2, `[24-OP-SEMIJOIN-04]`'s shape)
 *
 * Ungated, a key whose one side arrives while the other side retracts it in
 * the *same* wave can ride the outlet as a put and be removed moments later —
 * the within-wave flicker of a shared-source diamond. Constructed with
 * `emitOnFrontier = true`, this cell instead buffers each wave's input deltas
 * across both inlets ([WaveGate]), folds them together at wave completeness,
 * and reconciles every touched key **once**: a key both sides hold after the
 * wave is put, a key that was joined *before* the wave and no longer is is
 * removed, and anything else — including a key that joined and unjoined inside
 * the wave — emits nothing. A wave whose net effect is empty absorb-acks
 * instead. Because the cell keeps no output ledger (the join is derived from
 * the two input maps), "joined before the wave" is recorded by the wave's
 * first fold touching the key, in a per-flush scratch map. The default stays
 * ungated and byte-identical. Read [WaveGate]'s phantom-expected-edge caveat
 * before enabling it: the gate suits the shared-source diamond, not two
 * independent roots.
 */
class JoinCell<K, V, W>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    /**
     * Opt-in frontier-gated emission (KE2 §5.2) — see the class KDoc. `false`
     * (the default) is the shipped, ungated behavior, unchanged.
     */
    emitOnFrontier: Boolean = false,
) :
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, which is
    // untouched.
    JoinCellBase<K, V, W>(ref), Stateful, BoundedStateful, FrontierGateable {
    private val leftMap = mutableMapOf<K, V>()
    private val rightMap = mutableMapOf<K, W>()

    /** [FrontierGateable]: `true` iff constructed with `emitOnFrontier = true`. */
    override val frontierGated: Boolean = emitOnFrontier

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<K>? =
        if (!emitOnFrontier) null
        else WaveGate(left, right) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /**
     * Gated only: per touched key, whether it was joined when the in-flight
     * wave's first fold reached it — the pre-wave state a removal is judged
     * against. Filled by the folds [flush] runs, emptied when that flush ends.
     */
    private val wasJoined = mutableMapOf<K, Boolean>()

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

    init {
        // late-join catch-up (G-22): the current join as a delta-from-empty
        outlet.catchUpOnLinked {
            joined().takeIf { it.isNotEmpty() }?.let { MapDelta(it, emptySet()) }
        }
    }

    override fun onLeft(value: MapDelta<K, V>) {
        if (gate?.offerLeft(GatedFold { applyLeft(value) }) == true) return
        // The ungated path below is the shipped handler verbatim, so
        // `emitOnFrontier = false` stays byte-identical. A gated cell also lands
        // here for a delta the gate admits to no completeness set (catch-up,
        // straggler, unmatched edge): applying and emitting it immediately is
        // exactly right.
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
        if (gate?.offerRight(GatedFold { applyRight(value) }) == true) return
        // ungated (or gate-exempt) — the shipped handler verbatim; see [onLeft].
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

    // ---- the emitOnFrontier path: apply now, reconcile at completeness ----

    /**
     * Record the pre-wave joined state of every key a delta touches (the first
     * fold of the wave to reach a key wins), then fold the delta into [map].
     * Returns the keys the completed wave must reconcile.
     */
    private fun <X> applySide(map: MutableMap<K, X>, value: MapDelta<K, X>): Set<K> {
        val keys = value.puts.keys + value.removals
        keys.forEach { k -> wasJoined.putIfAbsent(k, k in leftMap && k in rightMap) }
        value.puts.forEach { (k, x) -> map[k] = x }
        value.removals.forEach { map.remove(it) }
        return keys
    }

    private fun applyLeft(value: MapDelta<K, V>): Set<K> = applySide(leftMap, value)

    private fun applyRight(value: MapDelta<K, W>): Set<K> = applySide(rightMap, value)

    /**
     * One completed wave (gated only): apply **both** sides' buffered deltas in
     * arrival order, then reconcile the union of their touched keys once
     * against the settled maps — put what is joined now, remove what was joined
     * before the wave and is not now, and nothing else. The emission runs inside
     * the buffered context so the outlet's reactive stamping keys it to the
     * completed input wave; a wave known only from acks carries no context of
     * its own, so its ack is minted from the wave position directly
     * ([CoalescingCombineCell]'s pattern).
     */
    @Suppress("UNCHECKED_CAST")
    private fun flush(timestamp: Timestamp, context: MessageContext?, folds: List<GatedFold<K>>) {
        try {
            val touched = LinkedHashSet<K>()
            folds.forEach { touched += it.applyAndTouch() }
            val puts = mutableMapOf<K, Pair<V, W>>()
            val removals = mutableSetOf<K>()
            touched.forEach { k ->
                if (k in leftMap && k in rightMap) {
                    puts[k] = (leftMap[k] as V) to (rightMap[k] as W)
                } else if (wasJoined[k] == true) {
                    removals += k
                }
            }
            CurrentContext.with(context ?: MessageContext(timestamp, outlet.ref)) { emit(puts, removals) }
        } finally {
            wasJoined.clear()
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the gate's transient
     * wave buffer is dropped — its deltas were never folded into [leftMap] /
     * [rightMap] and never observed downstream.
     */
    override fun onDeactivate(ctx: CellContext) {
        gate?.clear()
        wasJoined.clear()
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
