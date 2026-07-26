package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface JoinSetApi<A, B, C> {
    val left: Serve<Propagate<SetDelta<A>>>
    val right: Serve<Propagate<SetDelta<B>>>
    val outlet: Subscribe<Propagate<SetDelta<C>>>
}

/**
 * Incremental relational equi-join over tagged set streams (M11.5): a pair
 * `(a, b)` is live iff both rows are live and their keys match; each live
 * pair carries one minted tag ([MintedTags] — pairs re-enter when a removed
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
) : JoinSetCellBase<A, B, C>(ref), Stateful {
    private val leftState = TagState<A>()
    private val rightState = TagState<B>()
    private val minted = MintedTags<Pair<A, B>>(ref, "join")

    // derived indexes (rebuilt on restore): live rows per key, per side
    private val leftIndex = mutableMapOf<K, MutableSet<A>>()
    private val rightIndex = mutableMapOf<K, MutableSet<B>>()

    init {
        // late-join catch-up (G-22): advertised pairs folded under combine
        outlet.catchUpOnLinked {
            if (minted.isEmpty) null
            else {
                val adds = mutableMapOf<C, MutableSet<Timestamp>>()
                minted.entries.forEach { (pair, tag) ->
                    adds.getOrPut(combine(pair.first, pair.second)) { mutableSetOf() } += tag
                }
                SetDelta(adds = adds)
            }
        }
    }

    override fun onLeft(value: SetDelta<A>) {
        val effective = leftState.apply(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { a ->
            val k = leftKey(a)
            index(leftIndex, k, a, live = a in leftState)
            rightIndex[k]?.forEach { b -> reconcile(a, b, adds, dels) }
        }
        emit(adds, dels)
    }

    override fun onRight(value: SetDelta<B>) {
        val effective = rightState.apply(value)
        val adds = mutableMapOf<C, MutableSet<Timestamp>>()
        val dels = mutableMapOf<C, MutableSet<Timestamp>>()
        (effective.adds.keys + effective.dels.keys).forEach { b ->
            val k = rightKey(b)
            index(rightIndex, k, b, live = b in rightState)
            leftIndex[k]?.forEach { a -> reconcile(a, b, adds, dels) }
        }
        emit(adds, dels)
    }

    private fun <R> index(into: MutableMap<K, MutableSet<R>>, key: K, row: R, live: Boolean) {
        if (live) {
            into.getOrPut(key) { mutableSetOf() } += row
        } else {
            into[key]?.let { it -= row; if (it.isEmpty()) into -= key }
        }
    }

    private fun reconcile(
        a: A,
        b: B,
        adds: MutableMap<C, MutableSet<Timestamp>>,
        dels: MutableMap<C, MutableSet<Timestamp>>,
    ) {
        val wanted = a in leftState && b in rightState // keys match by index construction
        if (wanted) {
            minted.enter(a to b)?.let { adds.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        } else {
            minted.exit(a to b)?.let { dels.getOrPut(combine(a, b)) { mutableSetOf() } += it }
        }
    }

    private fun emit(adds: Map<C, Set<Timestamp>>, dels: Map<C, Set<Timestamp>>) {
        if (adds.isNotEmpty() || dels.isNotEmpty()) {
            outlet.call.propagate(SetDelta(adds, dels))
        } else {
            outlet.absorbAck() // a row entering an empty opposite side — ack the swallowed wave (CP-A3)
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
        rightIndex.clear()
        leftState.elements.forEach { a -> leftIndex.getOrPut(leftKey(a)) { mutableSetOf() } += a }
        rightState.elements.forEach { b -> rightIndex.getOrPut(rightKey(b)) { mutableSetOf() } += b }
    }
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
