package civictech.loader.fixture.missingsharedtype

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.gen.wire.Contract
import civictech.nature.removed.RemovedBase
import java.util.UUID

/**
 * Fixture (g) of epic computenet-051's fixture set: a cell that extends
 * [RemovedBase], a class this module only sees through a `compileOnly`
 * dependency on `:loader:fixtures:removed-api` — see this module's build
 * file. ERR-04/B12: resolving this class inside a `ModuleClassLoader` must
 * fail at LOAD TIME with `NoClassDefFoundError` naming `RemovedBase`, not at
 * first spawn.
 */
@Contract
fun interface MissingSharedTypeApi {
    fun ping()
}

class MissingSharedTypeCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : RemovedBase(), Cell, MissingSharedTypeApi {
    override fun ping() {}
}
