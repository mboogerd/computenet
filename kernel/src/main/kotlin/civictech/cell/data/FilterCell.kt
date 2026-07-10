package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

interface FilterSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Incremental filter over a tagged set stream: deltas for elements passing
 * [predicate] flow through with their tags intact; everything else is
 * absorbed. Tracks passing live tags so late joiners get catch-up (G-22) and
 * duplicates dedup.
 */
class FilterCell<E>(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    private val predicate: (E) -> Boolean,
) : FilterSetApi<E>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val state = TagState<E>()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                val passed = SetDelta(
                    adds = value.adds.filterKeys(predicate),
                    dels = value.dels.filterKeys(predicate),
                )
                val effective = state.apply(passed)
                if (effective.adds.isNotEmpty() || effective.dels.isNotEmpty()) {
                    outlet.call.propagate(effective)
                }
            }
        })
        outlet.linking.onLinked = { link ->
            if (state.size > 0) outlet.at(link.to).propagate(state.asDelta())
        }
    }

    override fun snapshot(): Serializable = state.snapshot()

    override fun restore(state: Serializable) = this.state.restore(state)
}
