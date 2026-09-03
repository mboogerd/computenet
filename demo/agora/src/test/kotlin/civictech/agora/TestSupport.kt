package civictech.agora

import civictech.cell.CellRef
import civictech.testkit.SimWorld

/** One deterministic single-host world (the repo's SimulationController idiom, via SimWorld). */
class Harness(
    seed: Long? = null,
    quiescence: Double = 1e-3,
    magnitude: Boolean = true,
    onCredence: (CellRef, Double) -> Unit = { _, _ -> },
) {
    private val world = SimWorld(
        seed = seed,
        attention = if (magnitude) {
            civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS)
        } else null,
    )
    val controller = world.controller
    val registry = world.registry
    val host = world.host
    val service = AgoraService(host, registry, quiescence = quiescence, onCredence = onCredence)

    /** Drain to idle under a hard step budget: quiescence is asserted, not hoped for. */
    fun runToIdle(budget: Int = 200_000): Int = world.runToIdle(budget)
}

// BatchReference moved to src/testFixtures/kotlin/civictech/agora/BatchReference.kt
// (computenet-5swa) so :demo:dialogue's differential tests can import it instead
// of reproducing the solver. The `java-test-fixtures` plugin wires this module's
// `test` source set to depend on `testFixtures` automatically, so callers in this
// package (AgoraExitTest, CycleQuiescenceTest) keep resolving it unqualified.
