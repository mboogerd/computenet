package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import java.io.Serializable

/** Commutative by construction: merging is addition, any arrival order converges (G-23). */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("CounterDelta")
data class CounterDelta(val amount: Long) : Serializable, MergeablePayload {
    fun merge(other: CounterDelta): CounterDelta = CounterDelta(amount + other.amount)
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as CounterDelta)
}
