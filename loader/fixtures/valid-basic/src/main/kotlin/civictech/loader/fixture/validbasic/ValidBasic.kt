package civictech.loader.fixture.validbasic

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.gen.wire.Contract
import java.util.UUID

/**
 * Fixture (a) of epic computenet-051's fixture set: the well-formed baseline module —
 * exactly one `@Contract` and exactly one [Cell], nothing else.
 *
 * It is deliberately minimal. Every other fixture in this tree is this one plus a single
 * deviation, so a loader test that fails on a deviant fixture and passes on this one has
 * isolated the deviation rather than some incidental difference in module shape.
 */
@Contract
fun interface GreetingApi {
    fun greet(name: String)
}

/**
 * The one cell this fixture module carries. Holds no state and serves no ports: what the
 * loader tests exercise is the module's *identity and isolation*, not its dataflow, and a
 * cell with ports would drag catch-up and wave semantics into a fixture that has no
 * business asserting them.
 */
class GreetingCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, GreetingApi {
    /** Last name passed to [greet], so a loaded copy of this class is observably alive. */
    @Volatile
    var lastGreeted: String? = null
        private set

    override fun greet(name: String) {
        lastGreeted = name
    }
}
