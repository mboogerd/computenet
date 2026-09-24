package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import civictech.cell.data.op.CountCell
import java.util.UUID

/**
 * computenet-4jdw0.1 (WKB2 F4 task 1): [GraphSpec.applyRemote]'s two-arg
 * overload reports one [StepEvent] per [GraphSpec.lowered] step, in order,
 * such that the returned [ApplyReport] equals the fold of those events
 * (rule 1); the one-arg overload delegates and keeps its existing observable
 * result (rule 3). computenet-4jdw0.2 (task 2): [ApplyProgressCell]
 * republishes those events on its linkable `progress` outlet as fresh
 * originations, and replays nothing to a late consumer (rule 2).
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

    // --- rule 2: ApplyProgressCell (computenet-4jdw0.2, computenet-4jdw0-D3) ---

    private class Recorder {
        val received = mutableListOf<StepEvent>()
        val contexts = mutableListOf<MessageContext?>()
        val sink: Use<Propagate<StepEvent>> = Use.fixed(
            Propagate<StepEvent> { received += it; contexts += CurrentContext.get() },
            PortRef.generate(),
        )
    }

    /** The events a plain recording [ApplyProgress] sees for [deterministicSpec] on a fresh, identically prepared host. */
    private fun plainEvents(liveRef: CellRef, refA: CellRef, refB: CellRef): List<StepEvent> {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        host.managementInlet.call.spawn(SetCell<String>(ref = liveRef))
        val events = mutableListOf<StepEvent>()
        deterministicSpec(liveRef, refA, refB).applyRemote(host.managementInlet) { events += it }
        controller.runToIdle()
        return events
    }

    /**
     * Spawns an [ApplyProgressCell], subscribes a [Recorder] to its `progress`
     * outlet BEFORE the apply, then applies [deterministicSpec] passing the
     * cell as the progress; [around] wraps the apply call (a foreign frame, or
     * nothing).
     */
    private fun applyThroughCell(
        liveRef: CellRef,
        refA: CellRef,
        refB: CellRef,
        around: (() -> Unit) -> Unit,
    ): ThroughCell {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
        host.managementInlet.call.spawn(SetCell<String>(ref = liveRef))
        val cell = ApplyProgressCell()
        host.managementInlet.call.spawn(cell)
        val recorder = Recorder()
        cell.progress.subscribe(recorder.sink)
        around { deterministicSpec(liveRef, refA, refB).applyRemote(host.managementInlet, cell) }
        controller.runToIdle()
        return ThroughCell(cell, recorder, controller)
    }

    private data class ThroughCell(val cell: ApplyProgressCell, val recorder: Recorder, val controller: SimulationController)

    /**
     * Fresh origination means more than `sourcePort == progress.ref`: an
     * inherited-context `call` emission ALSO rewrites `sourcePort` to the
     * outlet, but keeps the ambient frame's [Timestamp] and bumps its hop. So
     * the discriminator against a foreign frame is the timestamp's source
     * lane and `hop == 0`.
     */
    private fun assertFreshOriginations(cell: ApplyProgressCell, contexts: List<MessageContext?>, foreign: MessageContext?) {
        contexts.forEach { ctx ->
            ctx shouldNotBe null
            ctx!!.sourcePort shouldBe cell.progress.ref
            ctx.hop shouldBe 0
            if (foreign != null) ctx.timestamp.sourceId shouldNotBe foreign.timestamp.sourceId
        }
        // One fresh wave per event, all minted from the same outlet lane.
        contexts.map { it!!.timestamp }.distinct().size shouldBe contexts.size
        contexts.map { it!!.timestamp.sourceId }.distinct().size shouldBe 1
    }

    @Test
    fun `a consumer linked to progress before the apply receives every event as a fresh origination`() {
        val liveRef = CellRef(UUID.randomUUID())
        val refA = CellRef(UUID.randomUUID())
        val refB = CellRef(UUID.randomUUID())
        val expected = plainEvents(liveRef, refA, refB)
        expected.size shouldBe 4

        val (cell, recorder) = applyThroughCell(liveRef, refA, refB) { apply -> apply() }

        recorder.received shouldBe expected
        recorder.contexts.size shouldBe expected.size
        assertFreshOriginations(cell, recorder.contexts, foreign = null)
    }

    @Test
    fun `progress emissions are detached from a foreign ambient frame around the apply`() {
        val liveRef = CellRef(UUID.randomUUID())
        val refA = CellRef(UUID.randomUUID())
        val refB = CellRef(UUID.randomUUID())
        val expected = plainEvents(liveRef, refA, refB)

        val foreign = MessageContext(Timestamp(UUID.randomUUID(), 1L), PortRef.generate())
        val (cell, recorder) = applyThroughCell(liveRef, refA, refB) { apply ->
            CurrentContext.with(foreign) { apply() }
        }

        recorder.received shouldBe expected
        recorder.contexts.size shouldBe expected.size
        assertFreshOriginations(cell, recorder.contexts, foreign)
    }

    @Test
    fun `a consumer attached after the apply receives nothing`() {
        val liveRef = CellRef(UUID.randomUUID())
        val refA = CellRef(UUID.randomUUID())
        val refB = CellRef(UUID.randomUUID())

        val (cell, early, controller) = applyThroughCell(liveRef, refA, refB) { apply -> apply() }
        early.received.size shouldBe 4 // the apply did emit — the late sink's silence is not vacuous

        val late = Recorder()
        cell.progress.subscribe(late.sink)
        controller.runToIdle()

        late.received shouldBe emptyList()
        late.contexts shouldBe emptyList()
    }
}
