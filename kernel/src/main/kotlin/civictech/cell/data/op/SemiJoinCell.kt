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
interface SemiJoinApi<A, B> {
    val left: Serve<Propagate<SetDelta<A>>>
    val right: Serve<Propagate<SetDelta<B>>>
    val outlet: Subscribe<Propagate<SetDelta<A>>>
}

/**
 * Incremental semijoin/antijoin over tagged set streams (M11.2): a left row is
 * in the output iff it is live and its key's presence among live right rows
 * matches the polarity — `negated = false` keeps matched rows (A ⋉ B),
 * `negated = true` keeps unmatched rows (A ▷ B; with identity keys that is
 * set difference).
 *
 * Non-monotone: a row can (re-)enter when the *right* side removes, with no
 * fresh left tag to ride — so output tags are minted per entry (`MintedTags`,
 * tag hygiene, 21), never borrowed from the inputs. Output membership at idle
 * is a deterministic function of the converged input memberships (add-wins on
 * both sides); duplicates converge on membership, not on tags. Not
 * glitch-free — opposing in-flight updates may flicker transiently (22's
 * wrapper is the remedy).
 */
class SemiJoinCell<A, B, K>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    private val negated: Boolean = false,
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : SemiJoinCellBase<A, B>(ref), Stateful, BoundedStateful {
    private val join = KeyedBinarySetJoin<A, B, K>()
    private val ledger: JoinLedger<A> = MintedLedger(ref, "semijoin")

    init {
        // late-join catch-up (G-22): the advertised output as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
    }

    override fun onLeft(value: SetDelta<A>) {
        val effective = join.leftState.apply(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            join.index(join.leftIndex, leftKey(a), a, live = a in join.leftState)
            reconcile(a, adds, dels)
        }
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            // frontier-gated antijoin/semijoin emission (CP-A3): a wave that flips
            // no membership still advances the downstream frontier by an absorb-ack.
            absorbAck = { outlet.absorbAck() },
        )
    }

    override fun onRight(value: SetDelta<B>) {
        val effective = join.rightState.apply(value)
        val adds = mutableMapOf<A, Set<Timestamp>>()
        val dels = mutableMapOf<A, Set<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            join.index(join.rightIndex, k, b, live = b in join.rightState)
            // key presence may have flipped: reconcile is idempotent,
            // so visiting unflipped keys' rows is just a no-op
            join.leftIndex[k]?.forEach { a -> reconcile(a, adds, dels) }
        }
        join.emitOrAbsorb(
            adds,
            dels,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    private fun reconcile(a: A, adds: MutableMap<A, Set<Timestamp>>, dels: MutableMap<A, Set<Timestamp>>) {
        val wanted = a in join.leftState && ((leftKey(a) in join.rightIndex) xor negated)
        if (wanted) {
            ledger.enter(a) { emptySet() }?.let { adds[a] = it }
        } else {
            ledger.exit(a)?.let { dels[a] = it }
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
     * One page of this semijoin's three sub-states (V1C-OPS) — structurally
     * [JoinSetCell]'s, with an `A`-keyed ledger instead of a pair-keyed one.
     *
     * | ordinal | sub-state | key | entry |
     * |---|---|---|---|
     * | 0 | `"left"` | `A` | [TaggedEntry] — the left rows' live tags |
     * | 1 | `"right"` | `B` | [TaggedEntry] — the right rows' live tags |
     * | 2 | `"ledger"` | `A` | [TaggedEntry] — the advertised row's minted tag |
     *
     * Same order as [snapshot]'s `arrayListOf(leftState, rightState, ledger)`.
     * `"left"` and `"ledger"` share key type `A` and overlap in content — an
     * advertised row is live on the left — so the `(subState, key)` identity is
     * load-bearing here exactly as in [IntersectSetCell]: one row is two
     * entries, carrying the input tags and the minted output tag respectively.
     *
     * [StatePage.attributes] carries [OperatorPaging.MINT_COUNTER] on **every**
     * page (Decision D) — see [JoinSetCell.readBounded] for the full argument;
     * the ledger is the same `MintedLedger`.
     *
     * The key indexes are derived and not in [snapshot], so they are not paged
     * (Decision E). [StatePage.frontier] covers all three sub-states, exact at
     * both ends of a walk, and its equality is **necessary but not sufficient**
     * for stability — non-retaining tag states, and a `MintedLedger.exit` that
     * removes rather than tombstones. [supportsSince] stays `false`.
     *
     * `[24-OP-SEMIJOIN-01]` is untouched: this method only reads.
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

/** Set difference `A ⊖ B` (SQL EXCEPT DISTINCT): antijoin on identity keys. */
fun <E> differenceSet(ref: CellRef = CellRef(UUID.randomUUID())): SemiJoinCell<E, E, E> =
    SemiJoinCell(ref, leftKey = { it }, rightKey = { it }, negated = true)
