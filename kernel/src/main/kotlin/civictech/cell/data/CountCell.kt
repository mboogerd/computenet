package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface CountSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<CounterDelta>>
}

/**
 * Distinct-element count over a tagged set stream: emits the membership-size
 * change per applied delta (effective-only — tag churn that doesn't flip
 * membership emits nothing). The output is a commutative [CounterDelta]
 * stream, so downstream merging converges (G-23).
 */
class CountCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) : CountSetCellBase<E>(ref), Stateful {
    private val state = TagState<E>()

    init {
        // late-join catch-up (G-22): current count as a delta-from-zero
        outlet.catchUpOnLinked { if (state.size > 0) CounterDelta(state.size.toLong()) else null }
    }

    override fun onInlet(value: SetDelta<E>) {
        val before = state.size
        state.apply(value)
        val diff = (state.size - before).toLong()
        if (diff != 0L) outlet.call.propagate(CounterDelta(diff))
    }

    override fun snapshot(): Serializable = state.snapshot()

    override fun restore(state: Serializable) = this.state.restore(state)

    companion object {
        fun <E> create(): CountSetApi<E> = CountCell()
    }
}
