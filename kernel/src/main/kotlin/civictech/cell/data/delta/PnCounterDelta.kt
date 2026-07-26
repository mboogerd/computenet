package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import civictech.cell.UuidSerializer
import java.io.Serializable
import java.util.UUID

/**
 * PN-counter delta (spec 24/42, session delta 4): per-source **cumulative**
 * increment/decrement totals; merging is pointwise max — commutative,
 * associative, **idempotent** — so replica gossip echoes die out the same way
 * tagged set deltas do. This is what plain `CounterDelta` (raw addition, not
 * idempotent, double-counts on a mesh) cannot offer; `CounterCell` therefore
 * stays single-instance, valid for derived per-peer views.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PnCounterDelta")
data class PnCounterDelta(
    val incs: Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long> = emptyMap(),
    val decs: Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long> = emptyMap(),
) : Serializable, MergeablePayload {
    fun merge(other: PnCounterDelta): PnCounterDelta =
        PnCounterDelta(mergeMax(incs, other.incs), mergeMax(decs, other.decs))
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as PnCounterDelta)

    companion object {
        private fun mergeMax(a: Map<UUID, Long>, b: Map<UUID, Long>): Map<UUID, Long> =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }
    }
}
