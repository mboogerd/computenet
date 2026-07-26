package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.absorbAck
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MintedTags

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
 * fresh left tag to ride — so output tags are minted per entry ([MintedTags],
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
) : SemiJoinCellBase<A, B>(ref), Stateful {
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
}

/** Set difference `A ⊖ B` (SQL EXCEPT DISTINCT): antijoin on identity keys. */
fun <E> differenceSet(ref: CellRef = CellRef(UUID.randomUUID())): SemiJoinCell<E, E, E> =
    SemiJoinCell(ref, leftKey = { it }, rightKey = { it }, negated = true)
