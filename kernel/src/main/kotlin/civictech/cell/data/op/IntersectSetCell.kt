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
class IntersectSetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) : IntersectSetCellBase<E>(ref), Stateful {
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

        if (adds.isNotEmpty() || dels.isNotEmpty()) {
            outlet.call.propagate(SetDelta(adds, dels))
        }
        // TODO(restructure): ack divergence, owner decision pending
    }

    override fun snapshot(): Serializable =
        arrayListOf(leftState.snapshot(), rightState.snapshot(), ledger.snapshot())

    override fun restore(state: Serializable) {
        val (l, r, adv) = state as ArrayList<Serializable>
        leftState.restore(l)
        rightState.restore(r)
        ledger.restore(adv)
    }
}
