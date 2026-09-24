package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import civictech.cell.data.op.CountCell
import java.util.UUID

/**
 * computenet-4jdw0.1 (WKB2 F4 task 1): [GraphSpec.applyRemote]'s two-arg
 * overload reports one [StepEvent] per [GraphSpec.lowered] step, in order,
 * such that the returned [ApplyReport] equals the fold of those events
 * (rule 1); the one-arg overload delegates and keeps its existing observable
 * result (rule 3). [civictech.cell.graph.ApplyProgressCell] (rule 2) is
 * task 2's addition to this file.
 */
class ApplyProgressTest {

    private fun specWithFourSteps(liveRef: CellRef): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("a", CellFactory { ref -> SetCell<String>(ref = ref) }),
            SpawnStep("dup", CellFactory { ref -> SetCell<String>(ref = ref) }, IdentityBinding.Exact(liveRef)),
            SpawnStep("b", CellFactory { ref -> CountCell<String>(ref = ref) }),
            ConnectStep("dup", "outlet", "b", "inlet"),
        ),
    )

    @Test
    fun `one event per lowered step, in order, fold equals report`() {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())

        val liveRef = CellRef(UUID.randomUUID())
        host.managementInlet.call.spawn(SetCell<String>(ref = liveRef))

        val spec = specWithFourSteps(liveRef)

        val events = mutableListOf<StepEvent>()
        val report = spec.applyRemote(host.managementInlet) { events += it }
        controller.runToIdle()

        events.map { it.index to it.handle } shouldBe listOf(
            0 to "a",
            1 to "dup",
            2 to "b",
            3 to "dup.outlet->b.inlet",
        )
        (events[0].result is StepResult.Applied) shouldBe true
        (events[1].result is StepResult.Rejected) shouldBe true
        (events[2].result is StepResult.Applied) shouldBe true
        (events[3].result is StepResult.Rejected) shouldBe true
        ((events[0].result as StepResult.Applied).ref != null) shouldBe true
        ((events[2].result as StepResult.Applied).ref != null) shouldBe true

        report shouldBe ApplyReport(events.associate { it.handle to it.result })
    }

    /**
     * Every spawn step uses [IdentityBinding.Exact] with a fixed [CellRef] so the
     * apply is fully deterministic (no [IdentityBinding.FreshLogical] randomness
     * to defeat a literal [ApplyReport] equality check across two independent
     * hosts) — the shape rule 3 actually needs: "identically prepared".
     */
    private fun deterministicSpec(liveRef: CellRef, refA: CellRef, refB: CellRef): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("a", CellFactory { ref -> SetCell<String>(ref = ref) }, IdentityBinding.Exact(refA)),
            SpawnStep("dup", CellFactory { ref -> SetCell<String>(ref = ref) }, IdentityBinding.Exact(liveRef)),
            SpawnStep("b", CellFactory { ref -> CountCell<String>(ref = ref) }, IdentityBinding.Exact(refB)),
            ConnectStep("dup", "outlet", "b", "inlet"),
        ),
    )

    @Test
    fun `the one-arg overload delegates to a no-op progress and returns the same report`() {
        val liveRef = CellRef(UUID.randomUUID())
        val refA = CellRef(UUID.randomUUID())
        val refB = CellRef(UUID.randomUUID())

        val controllerTwoArg = SimulationController(seed = 9)
        val hostTwoArg = ManagedHost(scheduler = controllerTwoArg.scheduler())
        hostTwoArg.managementInlet.call.spawn(SetCell<String>(ref = liveRef))
        val twoArgReport = deterministicSpec(liveRef, refA, refB).applyRemote(hostTwoArg.managementInlet) { }
        controllerTwoArg.runToIdle()

        val controllerOneArg = SimulationController(seed = 9)
        val hostOneArg = ManagedHost(scheduler = controllerOneArg.scheduler())
        hostOneArg.managementInlet.call.spawn(SetCell<String>(ref = liveRef))
        val oneArgReport = deterministicSpec(liveRef, refA, refB).applyRemote(hostOneArg.managementInlet)
        controllerOneArg.runToIdle()

        oneArgReport shouldBe twoArgReport
        oneArgReport.allApplied shouldBe false
        (oneArgReport.results.getValue("dup") is StepResult.Rejected) shouldBe true
        oneArgReport.results.getValue("a") shouldBe StepResult.Applied(refA)
        oneArgReport.results.getValue("b") shouldBe StepResult.Applied(refB)
    }
}
