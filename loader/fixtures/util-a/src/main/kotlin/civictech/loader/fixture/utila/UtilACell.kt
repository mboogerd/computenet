package civictech.loader.fixture.utila

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.gen.wire.Contract
import com.example.Util
import java.util.UUID

/**
 * Fixture (d), half A: a valid one-contract-one-cell module — the same shape as
 * `:loader:fixtures:valid-basic` — that additionally bundles its own build of the
 * non-shared class [com.example.Util].
 *
 * The contract and cell FQNs are distinct from half B's on purpose. `ContractProcessor`
 * names its generated `ContractTable_<hash>` from the module's contract and cell FQNs, so
 * two fixtures with identical FQNs would generate identically-named tables and a test
 * loading both would be asserting about the *tables* colliding rather than about
 * `com.example.Util` resolving per-module. A colliding-id fixture is a later feature of
 * epic computenet-051 and belongs there, deliberately, not here.
 */
@Contract
fun interface UtilATagApi {
    fun report(tag: String)
}

/** Reads the [Util] build bundled in THIS module's jar; half B's cell reads its own. */
class UtilACell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    /** "A" when resolved against this module's own jar; "B" would mean isolation failed. */
    fun bundledTag(): String = Util().tag()
}
