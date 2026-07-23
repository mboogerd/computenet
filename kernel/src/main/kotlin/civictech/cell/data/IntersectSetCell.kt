package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

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
 */
class IntersectSetCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : IntersectSetApi<E>, Cell, Stateful {
    override val left = registerPort("left", FanInlet.create<Propagate<SetDelta<E>>>())
    override val right = registerPort("right", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val leftState = TagState<E>()
    private val rightState = TagState<E>()
    private val advertised = mutableMapOf<E, Set<Timestamp>>()

    init {
        left.serve(handler(leftState))
        right.serve(handler(rightState))
        // late-join catch-up (G-22): the advertised intersection as a delta-from-empty
        outlet.catchUpOnLinked { if (advertised.isEmpty()) null else SetDelta(adds = advertised.toMap()) }
    }

    private fun handler(side: TagState<E>) = object : Propagate<SetDelta<E>> {
        override fun propagate(value: SetDelta<E>) {
            val effective = side.apply(value)
            val adds = mutableMapOf<E, Set<Timestamp>>()
            val dels = mutableMapOf<E, Set<Timestamp>>()

            (effective.adds.keys + effective.dels.keys).forEach { element ->
                val isIn = element in leftState && element in rightState
                val wasIn = element in advertised
                if (isIn && !wasIn) {
                    val tags = leftState.tags(element) + rightState.tags(element)
                    advertised[element] = tags
                    adds[element] = tags
                } else if (!isIn && wasIn) {
                    dels[element] = advertised.remove(element)!!
                }
            }

            if (adds.isNotEmpty() || dels.isNotEmpty()) {
                outlet.call.propagate(SetDelta(adds, dels))
            }
        }
    }

    override fun snapshot(): Serializable =
        arrayListOf(leftState.snapshot(), rightState.snapshot(), HashMap(advertised))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (l, r, adv) = state as ArrayList<Serializable>
        leftState.restore(l)
        rightState.restore(r)
        advertised.clear()
        advertised.putAll(adv as Map<E, Set<Timestamp>>)
    }
}
