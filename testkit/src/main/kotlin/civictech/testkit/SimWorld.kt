package civictech.testkit

import civictech.cell.control.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController

/**
 * One deterministic single-host simulation world — the repo's SimulationController
 * idiom, previously copy-built per test (kernel `Fixture`s, demo/agora's `Harness`).
 *
 * Canonical shape: [registry] + [host] + [controller] wired together, plus a
 * *budgeted* [runToIdle] (taken from demo/agora's `Harness.runToIdle`) so a stalled
 * simulation fails the test loudly instead of hanging forever. This budgeted form is
 * the offered API here; [SimulationController.runToIdle] (unbudgeted, drains until
 * quiescent with no failure signal) is untouched in the kernel and remains available
 * via [controller] directly for callers that want it.
 */
class SimWorld(
    seed: Long? = null,
    attention: AttentionPolicy? = null,
) {
    val controller = SimulationController(seed)
    val registry = LocationRegistry()
    val host = ManagedHost(
        scheduler = controller.scheduler(),
        registry = registry,
        attention = attention,
    )

    /** Drain to idle under a hard step budget: quiescence is asserted, not hoped for. */
    fun runToIdle(budget: Int = 200_000): Int {
        var steps = 0
        while (controller.step()) {
            check(++steps < budget) { "no quiescence within $budget steps" }
        }
        return steps
    }
}
