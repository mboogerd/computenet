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
import civictech.cell.control.absorbAck
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState

@CellBase
interface IntersectSetApi<E> {
    val left: Serve<Propagate<SetDelta<E>>>
    val right: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Binary intersection of two tagged set streams (n-ary by chaining): an
 * element is in the intersection iff it is live on both sides. On entry the
 * element is advertised downstream under **one freshly minted, cell-owned
 * output tag**; on exit exactly that tag is deleted, so downstream membership
 * tracks exactly. Tag churn while membership is unchanged is absorbed
 * (effective-only, 21) — the advertised tag never changes while membership
 * holds.
 *
 * ### Tag policy: minted, never borrowed (21 §Tag hygiene, computenet-vvre)
 *
 * This cell used to advertise the *union of both sides' observed input tags*
 * ([AdvertisedLedger]), justified by the premise that "downstream only ever
 * sees tags this cell later deletes itself". **That premise is false across a
 * reconvergent (diamond) path**, and it is false in two independent ways:
 *
 * - **A borrowed tag is not this cell's to delete.** In `union(A, intersect(A,
 *   B))` the element's tag from `A` reaches the [UnionSetCell] twice — once on
 *   the direct edge, once re-advertised by this cell — and the union correctly
 *   folds the two into ONE fact keyed by `(element, tag)`
 *   (`[24-OP-UNION-01]`, "duplicate deliveries of the same tag across a
 *   diamond fan-in are deduplicated"). When the element then left this
 *   intersection, the exit deleted `A`'s tag, and the union retracted the
 *   *direct* edge's still-live contribution with it: `A ∪ (A ∩ B)` lost an
 *   element that is live in `A`. The tags this cell deletes are only "its own"
 *   when no other path carries them; a diamond is exactly the shape where
 *   downstream cannot tell the two apart, and by construction should not have
 *   to.
 * - **Re-entry re-emits a deleted tag.** Intersection membership flips ON when
 *   the *other* side adds, so a flip-ON does not ride a fresh input add-tag on
 *   the flipping element: re-advertising the unchanged side's tag after an exit
 *   that deleted it violates 21's flat prohibition — "an emitter of tagged
 *   deltas never re-emits a tag it previously deleted" — and leaves the
 *   re-entry dead under a tombstone-folding consumer.
 *
 * Minting per entry ([MintedLedger]/`MintedTags`) removes both: the advertised
 * tag is unconfusable with any upstream's, so a diamond sees two independent
 * facts, and every re-entry carries a tag no consumer has tombstoned.
 * `[24-OP-INTERSECT-01]` is unchanged in substance — an entry tag is
 * advertised on entry and every advertised tag deleted on exit — only its
 * provenance moves from borrowed to minted, matching what every other join
 * operator in this family already does.
 *
 * RS-5.3 note: unlike [JoinSetCell]/[SemiJoinCell], this is an identity join
 * (both sides share element type `E`, matching is direct membership — no key
 * projection), so it holds its own [TagState] pair directly rather than
 * through [KeyedBinarySetJoin]'s per-side key index, which this operator has
 * no use for. It shares both [JoinLedger] and, since computenet-vvre, its
 * [MintedLedger] policy.
 */
class IntersectSetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
    IntersectSetCellBase<E>(ref), Stateful, BoundedStateful {
    private val leftState = TagState<E>()
    private val rightState = TagState<E>()
    // minted, not advertised — see the tag-policy section on this class's KDoc
    private val ledger: JoinLedger<E> = MintedLedger(ref, "intersect")

    init {
        // late-join catch-up (G-22): the advertised intersection as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
    }

    override fun onLeft(value: SetDelta<E>) = fold(leftState, value)

    override fun onRight(value: SetDelta<E>) = fold(rightState, value)

    private fun fold(side: TagState<E>, value: SetDelta<E>) {
        val effective = side.apply(value)
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()

        (effective.adds.keys + effective.dels.keys).forEach { element ->
            val isIn = element in leftState && element in rightState
            if (isIn) {
                // [MintedLedger] mints its own tag and ignores the supplier —
                // the input tags are deliberately NOT borrowed (see class KDoc)
                ledger.enter(element) { emptySet() }?.let { adds[element] = it }
            } else {
                ledger.exit(element)?.let { dels[element] = it }
            }
        }

        // T05 finding 2 (was a TODO(restructure) "ack divergence, owner
        // decision pending"): a membership-neutral fold (tag churn with no
        // net add/del) now absorb-acks instead of silently swallowing the
        // wave — a GlitchFreeCell downstream would otherwise stall forever
        // on such a wave. Behavior change: this operator now acks.
        emitOrAbsorb(
            adds.isEmpty() && dels.isEmpty(),
            emit = { outlet.call.propagate(SetDelta(adds, dels)) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    override fun snapshot(): Serializable =
        arrayListOf(leftState.snapshot(), rightState.snapshot(), ledger.snapshot())

    override fun restore(state: Serializable) {
        val (l, r, adv) = state as ArrayList<Serializable>
        leftState.restore(l)
        rightState.restore(r)
        ledger.restore(adv)
    }

    /**
     * One page of this intersection's three sub-states (V1C-OPS) — **the
     * Decision A case**.
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `E` | [TaggedEntry] — the left side's live tags |
     * | 1 | `"right"` | `E` | [TaggedEntry] — the right side's live tags |
     * | 2 | `"ledger"` | `E` | [TaggedEntry] — the minted tag advertised downstream |
     *
     * Same order as [snapshot]'s
     * `arrayListOf(leftState, rightState, ledger)`. **All three are keyed by the
     * same `E`, and an element of the intersection is live in all three at
     * once**, each with a *different* tag set — the left side's observed tags,
     * the right side's, and the single cell-owned tag minted on entry. A cursor naming only
     * "the last element `e`" could not say which of the three it had reached, so
     * a resume would either re-emit `e` from a sub-state already walked or skip
     * a whole sub-state. Here the same `e` is **three distinct entries**,
     * `("left", e)`, `("right", e)` and `("ledger", e)`, and the cursor is
     * lexicographic `(subStateOrdinal, element)` over three frozen key sequences
     * ([OperatorPaging], Decisions A and B). Deduplicating across the three
     * would be wrong: their tag sets differ, and a consumer that cannot tell
     * them apart cannot reconstruct this cell.
     *
     * [StatePage.frontier] is the max per-source counter over all three
     * sub-states' tags, exact on the first and last page of a walk. Its equality
     * across a walk is **necessary but not sufficient** for "the union is a
     * snapshot": neither `TagState` retains tombstones, so a mid-walk exit
     * *removes* tags rather than minting one and can even lower the stamp.
     * [supportsSince] stays `false` accordingly. (`MintedLedger`'s own
     * monotone mint counter rides the page attributes — `OperatorPaging`'s
     * `mintCounter` — as it does for every other minting operator; it does not
     * make the frontier sufficient, because the two `TagState`s can still
     * lower it.)
     *
     * `[24-OP-INTERSECT-01]` is untouched: this method only reads, emits
     * nothing, and — unlike every fold path in this cell — reaches no
     * `absorbAck`.
     */
    override fun readBounded(request: StateRead): StatePage = pageOver(
        request,
        listOf(
            tagSubState("left", leftState),
            tagSubState("right", rightState),
            ledgerSubState("ledger", ledger),
        ),
        frontier = {
            val builder = FrontierBuilder()
            leftState.contributeTo(builder)
            rightState.contributeTo(builder)
            ledger.contributeTo(builder)
            builder.build()
        },
    )
}
