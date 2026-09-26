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
import civictech.cell.data.delta.SetDelta

@CellBase
interface JoinSetApi<A, B, C> {
    val left: Serve<Propagate<SetDelta<A>>>
    val right: Serve<Propagate<SetDelta<B>>>
    val outlet: Subscribe<Propagate<SetDelta<C>>>
}

/**
 * Incremental relational equi-join over tagged set streams (M11.5): a pair
 * `(a, b)` is live iff both rows are live and their keys match; each live
 * pair carries one minted tag (`MintedTags` — pairs re-enter when a removed
 * row returns, so input tags can't be borrowed), emitted under
 * `combine(a, b)`. Many-to-one `combine` collapses correctly: the output
 * element holds one tag per contributing pair and stays live until the last
 * pair dies (deleting the whole element on one pair's exit is the divergent
 * naive form, control-tested). Many-to-many keys yield all pairs.
 *
 * `combine` outputs must be `@Serializable` app types if they cross the wire
 * (`Pair` is not WireCodec-registered). This is the relational join over
 * convergent set streams; `JoinCell` remains the LWW dictionary join over
 * single-writer map streams.
 *
 * ### `emitOnFrontier` — the opt-in flicker gate (KE2 §5.2, `computenet-0favn`)
 *
 * The equi-join is non-monotone in the same way [SemiJoinCell] is: a pair
 * enters when both rows are live and exits when either leaves, so in a
 * shared-source diamond one arm's add and the other arm's opposing del of the
 * same wave can make a pair enter and exit on two separate invocations. With
 * `emitOnFrontier = true` the cell buffers each wave's input deltas across both
 * inlets ([WaveGate]), folds them into state at wave completeness, and
 * reconciles every touched pair **once** — the `[24-OP-SEMIJOIN-04]` shape,
 * extended to this cell. A transient pair is never minted, so `MintedTags`
 * hygiene holds (no tombstone for a tag never advertised), and a wave whose net
 * effect is empty absorb-acks. Read [SemiJoinCell]'s `emitOnFrontier` section
 * and [WaveGate]'s phantom-expected-edge and "One root is NOT sufficient"
 * caveats before enabling it; the default stays ungated and byte-identical.
 */
class JoinSetCell<A, B, K, C>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    /**
     * Opt-in frontier-gated emission (`[24-OP-SEMIJOIN-04]`'s shape, KE2 §5.2) —
     * see the class KDoc. `false` (the default) is the shipped, ungated
     * behavior, unchanged. It sits *before* [combine] so [combine] stays the
     * last parameter and trailing-lambda construction keeps compiling.
     */
    emitOnFrontier: Boolean = false,
    private val combine: (A, B) -> C,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : JoinSetCellBase<A, B, C>(ref), Stateful, BoundedStateful, FrontierGateable {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<Pair<A, B>> = MintedLedger(ref, "join")

    override val frontierGated: Boolean = emitOnFrontier

    /** The `emitOnFrontier` fold, or null when the cell runs the ungated default. */
    private val gate: WaveGate<Pair<A, B>>? =
        if (!emitOnFrontier) null
        else WaveGate(left, right) { timestamp, context, folds -> flush(timestamp, context, folds) }

    /** Waves currently held by the gate; always 0 when ungated. Diagnostic only. */
    val bufferedWaves: Int get() = gate?.bufferedWaves ?: 0

    init {
        // late-join catch-up (G-22): advertised pairs folded under combine
        outlet.catchUpOnLinked {
            if (ledger.isEmpty) null
            else {
                val adds = mutableMapOf<C, MutableSet<Timestamp>>()
                ledger.entries.forEach { (pair, tags) ->
                    adds.getOrPut(combine(pair.first, pair.second)) { mutableSetOf() } += tags
                }
                SetDelta(adds = adds)
            }
        }
    }

    override fun onLeft(value: SetDelta<A>) {
        if (gate?.offerLeft(GatedFold { applyLeft(value) }) == true) return
        // The ungated path below is the shipped handler verbatim — including its
        // per-row interleaving of index-then-reconcile — so `emitOnFrontier =
        // false` stays byte-identical. A gated cell also lands here for a delta
        // the gate admits to no completeness set (catch-up, straggler, unmatched
        // edge): applying and reconciling it immediately is exactly right.
        val effective = join.leftState.apply(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            val k = leftKey(a)
            join.index(join.leftIndex, k, a, live = a in join.leftState)
            join.rightIndex[k]?.forEach { b -> reconcile(a, b, adds, dels) }
        }
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // a row entering an empty opposite side — ack the swallowed wave (CP-A3)
        )
    }

    override fun onRight(value: SetDelta<B>) {
        if (gate?.offerRight(GatedFold { applyRight(value) }) == true) return
        // ungated (or gate-exempt) — the shipped handler verbatim; see [onLeft].
        val effective = join.rightState.apply(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            join.leftIndex[k]?.forEach { a -> reconcile(a, b, adds, dels) }
        }
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // a row entering an empty opposite side — ack the swallowed wave (CP-A3)
        )
    }

    // ---- the emitOnFrontier path: apply now, reconcile at completeness ----

    /**
     * Fold a left delta into membership and the key index **without**
     * reconciling; returns the pairs the completed wave must reconcile — each
     * touched row against every right row currently indexed under its key.
     */
    private fun applyLeft(value: SetDelta<A>): Set<Pair<A, B>> {
        val effective = join.leftState.apply(value)
        val pairs = LinkedHashSet<Pair<A, B>>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            val k = leftKey(a)
            join.index(join.leftIndex, k, a, live = a in join.leftState)
            join.rightIndex[k]?.forEach { b -> pairs += a to b }
        }
        return pairs
    }

    /**
     * Fold a right delta into membership and the key index **without**
     * reconciling; the mirror image of [applyLeft].
     *
     * Either fold reads the opposite index as it stands *now*, so the other
     * application order would collect a different set — but only pairs whose
     * other row that other fold itself touched, which that fold collects. Both
     * rows entering in one wave: the second fold sees the first's index entry.
     * One row entering while its partner leaves: the pair was never in the
     * ledger and is not wanted, so not collecting it is harmless (the
     * [SemiJoinCell.applyRight] argument, [GatedFold]).
     */
    private fun applyRight(value: SetDelta<B>): Set<Pair<A, B>> {
        val effective = join.rightState.apply(value)
        val pairs = LinkedHashSet<Pair<A, B>>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            join.leftIndex[k]?.forEach { a -> pairs += a to b }
        }
        return pairs
    }

    /**
     * One completed wave (gated only): apply both sides' buffered deltas, then
     * reconcile the union of their touched pairs once, against settled
     * membership — so a transient enter-then-exit cancels before a tag is
     * minted. Emitted inside the buffered context (or, for a wave known only
     * from acks, one minted from the wave position), as [SemiJoinCell]'s flush.
     */
    private fun flush(timestamp: Timestamp, context: MessageContext?, folds: List<GatedFold<Pair<A, B>>>) {
        val pairs = LinkedHashSet<Pair<A, B>>()
        folds.forEach { pairs += it.applyAndTouch() }
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        pairs.forEach { (a, b) -> reconcile(a, b, adds, dels) }
        CurrentContext.with(context ?: MessageContext(timestamp, outlet.ref)) {
            join.emitOrAbsorb(
                adds,
                dels,
                propagate = { outlet.call.propagate(it) },
                absorbAck = { outlet.absorbAck() },
            )
        }
    }

    /**
     * RESTART re-enters by catch-up, not restore (93 I-18): the gate's transient
     * wave buffer is dropped — its deltas were never applied to either side's
     * membership and never observed downstream.
     */
    override fun onDeactivate(ctx: CellContext) {
        gate?.clear()
    }

    private fun reconcile(
        a: A,
        b: B,
        adds: MutableMap<C, MutableSet<Timestamp>>,
        dels: MutableMap<C, MutableSet<Timestamp>>,
    ) {
        val wanted = a in join.leftState && b in join.rightState // keys match by index construction
        if (wanted) {
            ledger.enter(a to b) { emptySet() }?.let { adds.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        } else {
            ledger.exit(a to b)?.let { dels.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        }
    }

    override fun snapshot(): Serializable =
        arrayListOf(join.leftState.snapshot(), join.rightState.snapshot(), ledger.snapshot())

    override fun restore(state: Serializable) {
        val (l, r, m) = state as ArrayList<Serializable>
        join.leftState.restore(l)
        join.rightState.restore(r)
        ledger.restore(m)
        join.rebuildIndexes(leftKey, rightKey)
    }

    /**
     * One page of this equi-join's three sub-states (V1C-OPS).
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `A` | [TaggedEntry] — the left rows' live tags |
     * | 1 | `"right"` | `B` | [TaggedEntry] — the right rows' live tags |
     * | 2 | `"ledger"` | `Pair<A, B>` | [TaggedEntry] — the pair's one minted tag |
     *
     * Same order as [snapshot]'s
     * `arrayListOf(leftState, rightState, ledger)`. Three *different* key
     * spaces, the third a pair of the other two, so the cursor is lexicographic
     * `(subStateOrdinal, key)` over three frozen key sequences and a resume that
     * exhausts one sub-state continues at the head of the next
     * ([OperatorPaging], Decision B). Note the ledger is keyed by the **pair**,
     * not by the combined output `C`: `combine` is many-to-one, so `C` would
     * collapse distinct pairs, and `snapshot()` stores the pairs.
     *
     * **Decision D — the mint counter rides every page.** [StatePage.attributes]
     * carries [OperatorPaging.MINT_COUNTER], the counter behind
     * `MintedTags`' fresh-tag-per-entry discipline (tag hygiene, 21): it is
     * genuinely state — a restored instance must not re-mint a spent tag — and
     * it is `MintedLedger.snapshot()`'s second element, so a walk whose union is
     * to equal [snapshot]'s content has to carry it. It is an attribute rather
     * than an entry because it is cell-level, which also means it does not count
     * against [StateRead.limit], and it rides *every* page so a caller who joins
     * a walk at page 4 or abandons it after page 1 still sees it. Like the
     * frontier it is exact on the first and last page and carries the opening
     * value in between; a stale one is impossible without the frontier also
     * advancing, since minting a tag is what puts it in the ledger.
     *
     * The **key indexes** are not paged: `KeyedBinarySetJoin.rebuildIndexes`
     * derives them from the two tag states on [restore] and they are not in
     * [snapshot] (Decision E).
     *
     * [StatePage.frontier] covers all three sub-states' tags, exact on the first
     * and last page. Its equality across a walk is **necessary but not
     * sufficient** for "the union is a snapshot": neither `TagState` retains
     * tombstones, and `MintedLedger.exit` *removes* the minted tag rather than
     * tombstoning it, so a pair leaving the join mid-walk mints nothing.
     * [supportsSince] stays `false` accordingly.
     *
     * `[24-OP-JOINSET-01]`/`-02` are untouched: this method only reads.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(
            tagSubState("left", join.leftState),
            tagSubState("right", join.rightState),
            ledgerSubState("ledger", ledger),
        ),
        frontier = {
            val builder = FrontierBuilder()
            join.leftState.contributeTo(builder)
            join.rightState.contributeTo(builder)
            ledger.contributeTo(builder)
            builder.build()
        },
        attributes = { ledger.readerAttributes() },
    )
}

/** Equi-join to pairs — the default combine. */
fun <A, B, K> joinSet(
    leftKey: (A) -> K,
    rightKey: (B) -> K,
    ref: CellRef = CellRef(UUID.randomUUID()),
): JoinSetCell<A, B, K, Pair<A, B>> = JoinSetCell(ref, leftKey, rightKey, combine = { a, b -> a to b })

/** Cross product: the equi-join on the unit key. */
fun <A, B> crossProduct(ref: CellRef = CellRef(UUID.randomUUID())): JoinSetCell<A, B, Unit, Pair<A, B>> =
    JoinSetCell(ref, leftKey = { }, rightKey = { }, combine = { a, b -> a to b })
