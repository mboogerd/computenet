package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.UuidSerializer
import kotlinx.serialization.SerialName
import java.io.Serializable
import java.util.*

/**
 * Immutable identifier of a [Port]. [cell] is the owning cell where known;
 * free-standing endpoints (e.g. `Use.fixed`) have none.
 */
@kotlinx.serialization.Serializable
@SerialName("PortRef")
data class PortRef(
    @kotlinx.serialization.Serializable(with = UuidSerializer::class) val id: UUID,
    val cell: CellRef? = null,
) : Serializable {
    companion object {
        fun generate(cell: CellRef? = null) = PortRef(UUID.randomUUID(), cell)

        /**
         * Replay-stable port identity (PN-1, plan §2 F1 root): DERIVED from the
         * owning cell's ref and the registered port name, mirroring the M10.1
         * derivation of `SetCell.tagSource` / [civictech.cell.data.delta.MintedTags] /
         * the replication watermark ref. A hosted cell rebuilt with the same
         * `(cellRef, name)` re-mints the exact `PortRef` the network observed, so
         * `MessageContext.sourcePort` keys the same durable identity the wave
         * plane keys on and a pre-restart wave still matches its edge.
         */
        fun of(cell: CellRef, name: String) = PortRef(
            UUID.nameUUIDFromBytes("port:$name:${cell.id}:${cell.instanceId}".toByteArray()),
            cell,
        )
    }
}

/**
 * A port whose [Port.ref] is re-derivable from its owner identity at stamp time
 * (PN-1). Implemented by hosted-cell ports (FanInlet/FanOutlet); the stamp seam
 * ([PortIdentities.stamp]) calls [deriveRef] once, when the owner is a
 * [civictech.cell.Cell]. Anonymous/test ports do not implement it and keep the
 * fresh random ref minted at construction.
 */
internal interface DerivedPortRef {
    fun deriveRef(owner: CellRef, name: String)
}
