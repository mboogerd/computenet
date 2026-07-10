package civictech.cell

import civictech.cell.wire.UuidSerializer
import kotlinx.serialization.SerialName
import java.io.Serializable
import java.util.UUID

/**
 * Cell identity (G-8, M7.1): [id] is the **logical** identity — what links,
 * views, and users mean by "the cell" — and [instanceId] distinguishes live
 * instances of it: replicas (42, one instance per replica) and candidate
 * versions (53). Two refs with equal [id] denote the same logical cell.
 * Instance ids must be minted collision-free without coordination (random,
 * or caller-chosen in deterministic tests).
 */
@kotlinx.serialization.Serializable
@SerialName("CellRef")
data class CellRef(
    @kotlinx.serialization.Serializable(with = UuidSerializer::class) val id: UUID,
    val instanceId: Long = 0,
) : Serializable {
    fun sameLogical(other: CellRef): Boolean = id == other.id
}