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
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.CounterDelta

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
 *
 * RS-5.2 note: this operator only reuses [TaggedSetOperator] for its
 * [TagState][civictech.cell.data.delta.TagState] ledger and snapshot/restore
 * — it does NOT go through [TaggedSetOperator.emitOrAbsorb]. Its emission
 * condition (`diff != 0L`) and output type (`CounterDelta`, not `SetDelta`)
 * genuinely differ from Filter/Union/FlatMap, and — unlike those three — it
 * does not call `absorbAck` when a delta produces no size change; that gap
 * predates this move and is preserved verbatim (no behavior change).
 */
class CountCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) : CountSetCellBase<E>(ref), Stateful {
    private val op = TaggedSetOperator<E>()

    init {
        // late-join catch-up (G-22): current count as a delta-from-zero
        outlet.catchUpOnLinked { if (op.state.size > 0) CounterDelta(op.state.size.toLong()) else null }
    }

    override fun onInlet(value: SetDelta<E>) {
        val before = op.state.size
        op.state.apply(value)
        val diff = (op.state.size - before).toLong()
        if (diff != 0L) outlet.call.propagate(CounterDelta(diff))
    }

    override fun snapshot(): Serializable = op.snapshot()

    override fun restore(state: Serializable) = op.restore(state)

    companion object {
        fun <E> create(): CountSetApi<E> = CountCell()
    }
}
