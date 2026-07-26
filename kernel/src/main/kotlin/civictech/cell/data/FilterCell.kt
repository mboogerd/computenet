package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState

@CellBase
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
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val predicate: (E) -> Boolean,
) : FilterSetCellBase<E>(ref), Stateful {
    private val state = TagState<E>()

    init {
        outlet.catchUpOnLinked { if (state.size > 0) state.asDelta() else null }
    }

    override fun onInlet(value: SetDelta<E>) {
        val passed = SetDelta(
            adds = value.adds.filterKeys(predicate),
            dels = value.dels.filterKeys(predicate),
        )
        val effective = state.apply(passed)
        if (effective.adds.isNotEmpty() || effective.dels.isNotEmpty()) {
            outlet.call.propagate(effective)
        } else {
            outlet.absorbAck() // filtered/deduped away — ack the swallowed wave (CP-A3)
        }
    }

    override fun snapshot(): Serializable = state.snapshot()

    override fun restore(state: Serializable) = this.state.restore(state)
}
