package civictech.loader.fixture.noattrs

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.gen.wire.Contract
import java.util.UUID

/**
 * Fixture (b) of epic computenet-051's fixture set: `:loader:fixtures:valid-basic`'s
 * shape (one `@Contract`, one cell) minus the manifest attributes — see this
 * module's build file. ERR-02: a jar refused as not-a-module because its
 * manifest lacks `ComputeNet-Module-Id`/`ComputeNet-Module-Version`, not
 * because it is malformed in any other way.
 */
@Contract
fun interface PingApi {
    fun ping()
}

class PingCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, PingApi {
    @Volatile
    var pinged: Boolean = false
        private set

    override fun ping() {
        pinged = true
    }
}
