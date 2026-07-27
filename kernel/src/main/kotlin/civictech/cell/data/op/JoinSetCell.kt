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
) : JoinSetCellBase<A, B, C>(ref), Stateful {
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
