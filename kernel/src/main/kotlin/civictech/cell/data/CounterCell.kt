package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.host.MergeablePayload

@Contract
interface CounterOps {
    fun increment(amount: Long)
    fun decrement(amount: Long)
}

/** Commutative by construction: merging is addition, any arrival order converges (G-23). */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("CounterDelta")
data class CounterDelta(val amount: Long) : Serializable, MergeablePayload {
    fun merge(other: CounterDelta): CounterDelta = CounterDelta(amount + other.amount)
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as CounterDelta)
}

interface CounterApi {
    val inlet: Use<CounterOps>
    val outlet: Subscribe<Propagate<CounterDelta>>
}

class CounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : CounterApi, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<CounterOps>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

    private var total = 0L

    private val inletApi = object : CounterOps {
        override fun increment(amount: Long) {
            total += amount
            // effective-only (21): zero-amount ops don't emit
            if (amount != 0L) outlet.call.propagate(CounterDelta(amount))
        }

        override fun decrement(amount: Long) = increment(-amount)
    }

    init {
        inlet.serve(inletApi)
        // late-join catch-up (G-22): current total as a delta-from-zero
        outlet.catchUpOnLinked { if (total != 0L) CounterDelta(total) else null }
    }

    override fun snapshot(): Serializable = total

    override fun restore(state: Serializable) {
        total = state as Long
    }

    companion object {
        fun create(): CounterApi = CounterCell()
    }
}
