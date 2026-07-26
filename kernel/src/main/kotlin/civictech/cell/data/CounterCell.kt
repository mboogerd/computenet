package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
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

@CellBase
interface CounterApi {
    val inlet: Use<CounterOps>
    val outlet: Subscribe<Propagate<CounterDelta>>
}

class CounterCell(ref: CellRef = CellRef(UUID.randomUUID())) : CounterCellBase(ref), Stateful {
    private var total = 0L

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): CounterOps = object : CounterOps {
        override fun increment(amount: Long) {
            total += amount
            // effective-only (21): zero-amount ops don't emit
            if (amount != 0L) outlet.call.propagate(CounterDelta(amount))
        }

        override fun decrement(amount: Long) = increment(-amount)
    }

    init {
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
