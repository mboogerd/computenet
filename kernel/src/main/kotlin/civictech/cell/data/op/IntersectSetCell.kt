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
 * element is advertised downstream with its live tags from both sides; on
 * exit every advertised tag is deleted, so downstream membership tracks
 * exactly. Tag churn while membership is unchanged is absorbed (effective-only,
 * 21) — the advertised tag set may lag the inputs, which is sound because
 * downstream only ever sees tags this cell later deletes itself.
 *
 * RS-5.3 note: unlike [JoinSetCell]/[SemiJoinCell], this is an identity join
 * (both sides share element type `E`, matching is direct membership — no key
 * projection), so it holds its own [TagState] pair directly rather than
 * through [KeyedBinarySetJoin]'s per-side key index, which this operator has
 * no use for. It DOES share [JoinLedger] (via [AdvertisedLedger] — this
 * operator advertises the union of both sides' observed input tags, never
 * mints, unlike [MintedLedger]).
 */
class IntersectSetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
    IntersectSetCellBase<E>(ref), Stateful, BoundedStateful {
    private val leftState = TagState<E>()
    private val rightState = TagState<E>()
    private val ledger: JoinLedger<E> = AdvertisedLedger()

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
                ledger.enter(element) { leftState.tags(element) + rightState.tags(element) }
                    ?.let { adds[element] = it }
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
     * | 2 | `"ledger"` | `E` | [TaggedEntry] — the tags advertised downstream |
     *
     * Same order as [snapshot]'s
     * `arrayListOf(leftState, rightState, ledger)`. **All three are keyed by the
     * same `E`, and an element of the intersection is live in all three at
     * once**, each with a *different* tag set — the left side's observed tags,
     * the right side's, and the union advertised on entry. A cursor naming only
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
     * snapshot": neither `TagState` retains tombstones and `AdvertisedLedger`
     * advertises tags it never minted, so a mid-walk exit *removes* tags rather
     * than minting one and can even lower the stamp. [supportsSince] stays
     * `false` accordingly.
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
