package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import java.io.Serializable

/**
 * The waterline floor's emitted delta (`[24-WL-03]`, `[KE4-06]`; 24 §Lateness
 * and waterlines). [floor] is a **value, not a wave** (`[24-WL-04]`): it is
 * never a wave position and never a member of any completeness set or
 * glitch-free frontier, even though the emission that carries it rides the
 * wave plane like any other delta (22 §Interaction with other parts states
 * how). Merge is **pointwise maximum**, so redelivery and reordering of
 * `WaterlineDelta`s are fixpoints — a floor that does not rise evicts nothing
 * and emits nothing downstream.
 *
 * Not [WatermarkDelta]: that type is the E3.2 delivered-watermark lattice over
 * `(replica slot, sourceId) -> delivered counter`, a wave-delivery completeness
 * mechanism. This type is the lateness/eviction floor over event time — an
 * ordinary data value computed by `WaterlineCell` with no wall clock and no
 * cross-host coordination (`[24-WL-02]`, `[24-WL-03]`). The two share a merge
 * shape (max) and nothing else; see that class's KDoc for its own concern.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("WaterlineDelta")
data class WaterlineDelta(val floor: Long) : Serializable, MergeablePayload {

    fun merge(other: WaterlineDelta): WaterlineDelta = WaterlineDelta(maxOf(floor, other.floor))

    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as WaterlineDelta)

    /** Lattice order: `this ⊒ other` iff this floor is at least `other`'s. */
    fun dominates(other: WaterlineDelta): Boolean = floor >= other.floor
}
