package civictech.cell.verify

import civictech.cell.Propagate
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The kotest adapter (G-31): run [block], drive the simulation to idle, then
 * assert no linked invariant emitted a violation. Failure output carries the
 * violation payloads; pass the seed in [clue] for reproducibility.
 */
fun checkInvariants(
    controller: SimulationController,
    invariants: List<InvariantCell<*, *>>,
    clue: String = "",
    block: () -> Unit,
) {
    val observed = invariants.map { invariant ->
        mutableListOf<Violation>().also { buffer ->
            invariant.violations.subscribe(Use.fixed(object : Propagate<Violation> {
                override fun propagate(value: Violation) {
                    buffer += value
                }
            }, PortRef.generate()))
        }
    }
    block()
    controller.runToIdle()
    invariants.zip(observed).forEach { (invariant, violations) ->
        withClue("$clue violations of '${invariant.name}'") {
            violations.shouldBeEmpty()
        }
    }
}
