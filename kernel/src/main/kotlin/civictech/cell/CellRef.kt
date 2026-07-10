package civictech.cell

import civictech.cell.wire.UuidSerializer
import kotlinx.serialization.SerialName
import java.io.Serializable
import java.util.UUID

@kotlinx.serialization.Serializable
@SerialName("CellRef")
data class CellRef(
    @kotlinx.serialization.Serializable(with = UuidSerializer::class) val id: UUID,
) : Serializable