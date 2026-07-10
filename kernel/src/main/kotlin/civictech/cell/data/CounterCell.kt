package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface CounterOps {
    fun increment(amount: Long)
    fun decrement(amount: Long)
}

/** Commutative by construction: merging is addition, any arrival order converges (G-23). */
data class CounterDelta(val amount: Long) : Serializable {
    fun merge(other: CounterDelta): CounterDelta = CounterDelta(amount + other.amount)
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
        outlet.linking.onLinked = { link ->
            if (total != 0L) outlet.at(link.to).propagate(CounterDelta(total))
        }
    }

    override fun snapshot(): Serializable = total

    override fun restore(state: Serializable) {
        total = state as Long
    }

    companion object {
        fun create(): CounterApi = CounterCell()
    }
}
