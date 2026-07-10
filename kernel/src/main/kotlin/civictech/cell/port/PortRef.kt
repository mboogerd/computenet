package civictech.cell.port

import civictech.cell.CellRef
import civictech.cell.wire.UuidSerializer
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
    }
}
