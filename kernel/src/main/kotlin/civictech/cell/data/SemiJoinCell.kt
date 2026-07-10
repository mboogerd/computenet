package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

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
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val leftKey: (A) -> K,
    private val rightKey: (B) -> K,
    private val negated: Boolean = false,
) : SemiJoinApi<A, B>, Cell, Stateful {
    override val left = registerPort("left", FanInlet.create<Propagate<SetDelta<A>>>())
    override val right = registerPort("right", FanInlet.create<Propagate<SetDelta<B>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<A>>>())

    private val leftState = TagState<A>()
    private val rightState = TagState<B>()
    private val minted = MintedTags<A>(ref, "semijoin")

    // derived indexes (rebuilt on restore): live left rows per key, live right rows per key
    private val leftIndex = mutableMapOf<K, MutableSet<A>>()
    private val rightRows = mutableMapOf<K, MutableSet<B>>()

    init {
        left.serve(object : Propagate<SetDelta<A>> {
            override fun propagate(value: SetDelta<A>) {
                val effective = leftState.apply(value)
                val adds = mutableMapOf<A, Set<Timestamp>>()
                val dels = mutableMapOf<A, Set<Timestamp>>()
                (effective.adds.keys + effective.dels.keys).forEach { a ->
                    index(leftIndex, leftKey(a), a, live = a in leftState)
                    reconcile(a, adds, dels)
                }
                emit(adds, dels)
            }
        })
        right.serve(object : Propagate<SetDelta<B>> {
            override fun propagate(value: SetDelta<B>) {
                val effective = rightState.apply(value)
                val adds = mutableMapOf<A, Set<Timestamp>>()
                val dels = mutableMapOf<A, Set<Timestamp>>()
                (effective.adds.keys + effective.dels.keys).forEach { b ->
                    val k = rightKey(b)
                    index(rightRows, k, b, live = b in rightState)
                    // key presence may have flipped: reconcile is idempotent,
                    // so visiting unflipped keys' rows is just a no-op
                    leftIndex[k]?.forEach { a -> reconcile(a, adds, dels) }
                }
                emit(adds, dels)
            }
        })
        // late-join catch-up (G-22): the advertised output as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (!minted.isEmpty) outlet.at(link.to).propagate(minted.asDelta())
        }
    }

    private fun <R> index(into: MutableMap<K, MutableSet<R>>, key: K, row: R, live: Boolean) {
        if (live) {
            into.getOrPut(key) { mutableSetOf() } += row
        } else {
            into[key]?.let { it -= row; if (it.isEmpty()) into -= key }
        }
    }

    private fun reconcile(a: A, adds: MutableMap<A, Set<Timestamp>>, dels: MutableMap<A, Set<Timestamp>>) {
        val wanted = a in leftState && ((leftKey(a) in rightRows) xor negated)
        if (wanted) {
            minted.enter(a)?.let { adds[a] = setOf(it) }
        } else {
            minted.exit(a)?.let { dels[a] = setOf(it) }
        }
    }

    private fun emit(adds: Map<A, Set<Timestamp>>, dels: Map<A, Set<Timestamp>>) {
        if (adds.isNotEmpty() || dels.isNotEmpty()) {
            outlet.call.propagate(SetDelta(adds, dels))
        }
    }

    override fun snapshot(): Serializable =
        arrayListOf(leftState.snapshot(), rightState.snapshot(), minted.snapshot())

    override fun restore(state: Serializable) {
        val (l, r, m) = state as ArrayList<Serializable>
        leftState.restore(l)
        rightState.restore(r)
        minted.restore(m)
        leftIndex.clear()
        rightRows.clear()
        leftState.elements.forEach { a -> leftIndex.getOrPut(leftKey(a)) { mutableSetOf() } += a }
        rightState.elements.forEach { b -> rightRows.getOrPut(rightKey(b)) { mutableSetOf() } += b }
    }
}

/** Set difference `A ⊖ B` (SQL EXCEPT DISTINCT): antijoin on identity keys. */
fun <E> differenceSet(ref: CellRef = CellRef(UUID.randomUUID())): SemiJoinCell<E, E, E> =
    SemiJoinCell(ref, leftKey = { it }, rightKey = { it }, negated = true)
