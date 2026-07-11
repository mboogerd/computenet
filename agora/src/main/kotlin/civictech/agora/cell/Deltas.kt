package civictech.agora.cell

import civictech.cell.CellRef
import civictech.cell.data.Magnitude
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Polarity { ATTACK, SUPPORT }

/**
 * A user's subjective stance on a claim, LWW per (claim, user);
 * `value == null` clears it. Deliberately no magnitude: user writes ride the
 * neutral band — urgency belongs to the derived propagation they trigger.
 */
@Serializable
@SerialName("agora.StanceDelta")
data class StanceDelta(val user: String, val value: Double?) : java.io.Serializable

/**
 * The latest influence of one edge on its target — a complete value per
 * [edge], not an increment; `value == null` retracts it (edge removed).
 * [size] is |change vs previously sent|, the emitter-stamped urgency.
 */
@Serializable
@SerialName("agora.InfluenceDelta")
data class InfluenceDelta(
    val edge: CellRef,
    val polarity: Polarity,
    val value: Double?,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}

/** A claim's current credence; [size] is |change vs previously emitted|. */
@Serializable
@SerialName("agora.CredenceUpdate")
data class CredenceUpdate(
    val source: CellRef,
    val credence: Double,
    val size: Double,
) : java.io.Serializable, Magnitude {
    override fun size(): Double = size
}
