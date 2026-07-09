package civictech.kernel.germ.port

import civictech.kernel.germ.CellRef
import java.io.Serializable
import java.util.*

/**
 * Immutable identifier of a [Port]. [cell] is the owning cell where known;
 * free-standing endpoints (e.g. `Use.fixed`) have none.
 */
data class PortRef(val id: UUID, val cell: CellRef? = null) : Serializable {
    companion object {
        fun generate(cell: CellRef? = null) = PortRef(UUID.randomUUID(), cell)
    }
}
