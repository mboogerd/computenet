package civictech.cell.host

import civictech.cell.Propagate
import civictech.cell.data.CollectorCell
import civictech.cell.data.DeltaInletProxy
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.tagFold
import civictech.cell.port.LinkFrom
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BS-30/BS-31 (`computenet-t6b.2.5`, `[KBLK-22]`, `[KBLK-23]`, `[KBLK-24]`): proves the
 * deterministic drive seam [SimulationController] documents in its own KDoc as supported for
 * a `:kernel`-only, non-test consumer — no `civictech.testkit` import anywhere in this file.
 * The whole graph here is built the same way `civictech.testkit.SimWorld` builds one
 * internally (`SimulationController` + `LocationRegistry` + `ManagedHost(scheduler = ...)`),
 * just without that convenience wrapper, to prove the seam works without it.
 */
class DeterministicDriveTest {

    /** Minimal lookup proxy exposing a hosted [SetCell]'s write inlet (mirrors CollaborativeAppTest). */
    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** Cross-host wiring identical in shape to `CollaborativeAppTest.streamInto`. */
    private fun streamInto(writer: SetCell<String>, sink: CollectorCell, registry: LocationRegistry) {
        @Suppress("UNCHECKED_CAST")
        writer.outlet.linkTo(sink.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        val routed = (HostedCellProxy.create(sink.ref, registry, DeltaInletProxy::class.java)
                as DeltaInletProxy).inlet.call
        writer.outlet.unsubscribe(sink.inlet.ref)
        writer.outlet.subscribe(Use.fixed(routed, sink.inlet.ref))
    }

    /**
     * A two-host fan-out: a [SetCell] source on host A streams into two independent
     * [CollectorCell] sinks on host B — so the seed's across-host pick actually has two busy
     * hosts to choose between while the bulk load drains, exercising [KBLK-23] rather than a
     * single fixed order.
     */
    private data class Run(val stateA: Set<String>, val stateB: Set<String>, val steps: Int)

    private fun runBulkLoad(seed: Long, opsCount: Int = 500): Run {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val source = SetCell<String>()
        val sinkA = CollectorCell()
        val sinkB = CollectorCell()
        hostA.managementInlet.call.spawn(source)
        hostB.managementInlet.call.spawn(sinkA)
        hostB.managementInlet.call.spawn(sinkB)

        streamInto(source, sinkA, registry)
        streamInto(source, sinkB, registry)

        val sourceApi = (HostedCellProxy.create(source.ref, registry, SetInletProxy::class.java)
                as SetInletProxy).inlet.call
        repeat(opsCount) { i -> sourceApi.add("item-$i") }

        val steps = controller.runToIdle()
        return Run(stateA = tagFold(sinkA.arrivals), stateB = tagFold(sinkB.arrivals), steps = steps)
    }

    @Test
    fun `same seed reaches the same quiescent state and step count, every time`() {
        val expectedItems = (0 until 500).map { "item-$it" }.toSet()

        val run1 = runBulkLoad(seed = 42)
        val run2 = runBulkLoad(seed = 42)
        val run3 = runBulkLoad(seed = 42)

        // sanity: the bulk load actually delivered, on both sinks
        run1.stateA shouldBe expectedItems
        run1.stateB shouldBe expectedItems

        // [KBLK-23]: identical quiescent state AND identical step count, every repeat of seed 42
        run2.stateA shouldBe run1.stateA
        run2.stateB shouldBe run1.stateB
        run2.steps shouldBe run1.steps
        run3.stateA shouldBe run1.stateA
        run3.stateB shouldBe run1.stateB
        run3.steps shouldBe run1.steps

        // control (not asserted to differ — AGENTS.md: never swap a seed to make a test agree):
        // seed 43 need not match seed 42's step count or interleaving, only its own repeats would.
    }

    @Test
    fun `runToIdle exhausts its budget on a livelocked graph, bounded and diagnosable`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()

        // a task that resubmits itself on every delivery: this host is never quiescent.
        lateinit var resubmit: () -> Unit
        resubmit = { scheduler.submit(10) { resubmit() } }
        resubmit()

        val budget = 200
        val error = assertThrows<IllegalStateException> { controller.runToIdle(budget) }
        error.message shouldBe "simulation did not quiesce within $budget steps — likely livelock"

        // the world is live-locked, not corrupted: it is still busy, and stepping still works.
        controller.step() shouldBe true
    }
}
