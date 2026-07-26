package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.absorbAck
import civictech.cell.data.delta.SetDelta

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
    private val op = TaggedSetOperator<E>()

    init {
        outlet.catchUpOnLinked { if (op.state.size > 0) op.state.asDelta() else null }
    }

    override fun onInlet(value: SetDelta<E>) {
        val passed = SetDelta(
            adds = value.adds.filterKeys(predicate),
            dels = value.dels.filterKeys(predicate),
        )
        val effective = op.state.apply(passed)
        op.emitOrAbsorb(
            effective,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // filtered/deduped away — ack the swallowed wave (CP-A3)
        )
    }

    override fun snapshot(): Serializable = op.snapshot()

    override fun restore(state: Serializable) = op.restore(state)
}
