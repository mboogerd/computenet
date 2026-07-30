package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
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
 */
class JoinSetCell<A, B, K, C>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    private val combine: (A, B) -> C,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : JoinSetCellBase<A, B, C>(ref), Stateful, BoundedStateful {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<Pair<A, B>> = MintedLedger(ref, "join")

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
