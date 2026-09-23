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
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BS-30/BS-31 (`computenet-t6b.2.5`, `[KBLK-22]`, `[KBLK-23]`, `[KBLK-24]`): proves the
 * deterministic drive seam [SimulationController] documents in its own KDoc as supported for
 * a `:kernel`-only, non-test consumer — no `civictech.testkit` import anywhere in this file.
 * The whole graph here is built the same way `civictech.testkit.SimWorld` builds one
 * internally (`SimulationController` + `LocationRegistry` + `ManagedHost(scheduler = ...)`),
 * just without that convenience wrapper, to prove the seam works without it.
 *
 * [KBLK-23]'s scenario is a two-host fan-**in** (two sources feeding one sink), not a
 * fan-**out** into per-key `Set` state: a first attempt at this test fanned one source out to
 * two `Set`-folded sinks, which review correctly rejected as vacuous for the seeded-pick
 * property — a `Set` merge is commutative, so every delivery order folds to the same state and
 * the same step count (500 adds × 3 hops = 1500 steps, independent of pick order, for every
 * seed 1..30 checked). The fan-in's sink instead records raw arrival order
 * ([CollectorCell.arrivals]), which *is* a genuine interleaving of the two upstream hosts'
 * items and therefore does depend on which busy host [SimulationController.step] picks next.
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
     * A two-host fan-IN: two independent [SetCell] sources, one on host A and one on host B,
     * both stream into the *same* [CollectorCell] sink. Unlike a fan-out into set-folded state
     * (which a commutative `Set` merge makes order-insensitive — see the class KDoc below),
     * the sink's raw [CollectorCell.arrivals] log records deltas in actual delivery order, and
     * that order is a genuine interleaving of A's and B's items: whichever of the two busy
     * hosts [SimulationController.step] picks next delivers its pending item next. The final
     * *set* of delivered items is still order-insensitive (both sources fully drain either
     * way), but the raw arrival sequence is exactly [KBLK-23]'s observable: it is pinned to
     * the seed's pick order, not merely to the graph shape.
     */
    private data class Run(val order: List<String>, val delivered: Set<String>, val steps: Int)

    private fun runFanIn(seed: Long, opsPerSource: Int = 60): Run {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val sourceA = SetCell<String>()
        val sourceB = SetCell<String>()
        val sink = CollectorCell()
        hostA.managementInlet.call.spawn(sourceA)
        hostB.managementInlet.call.spawn(sourceB)
        hostA.managementInlet.call.spawn(sink)

        streamInto(sourceA, sink, registry)
        streamInto(sourceB, sink, registry)

        val apiA = (HostedCellProxy.create(sourceA.ref, registry, SetInletProxy::class.java)
                as SetInletProxy).inlet.call
        val apiB = (HostedCellProxy.create(sourceB.ref, registry, SetInletProxy::class.java)
                as SetInletProxy).inlet.call
        // queue both hosts' work before driving, so A and B are simultaneously busy throughout
        // the drain and every step genuinely exercises the controller's cross-host pick.
        repeat(opsPerSource) { i -> apiA.add("A-$i") }
        repeat(opsPerSource) { i -> apiB.add("B-$i") }

        val steps = controller.runToIdle()
        val order = sink.arrivals.flatMap { it.adds.keys }
        return Run(order = order, delivered = tagFold(sink.arrivals), steps = steps)
    }

    @Test
    fun `same seed reaches the same quiescent state and step count, every time`() {
        val expectedItems = ((0 until 60).map { "A-$it" } + (0 until 60).map { "B-$it" }).toSet()

        val run1 = runFanIn(seed = 42)
        val run2 = runFanIn(seed = 42)
        val run3 = runFanIn(seed = 42)

        // sanity: the fan-in actually delivered every item from both sources
        run1.delivered shouldBe expectedItems
        run1.order.toSet() shouldBe expectedItems

        // [KBLK-23]: identical quiescent state, identical raw arrival order, and identical step
        // count, every repeat of seed 42 — (a) same seed, same observable, every time.
        run2.delivered shouldBe run1.delivered
        run2.order shouldBe run1.order
        run2.steps shouldBe run1.steps
        run3.delivered shouldBe run1.delivered
        run3.order shouldBe run1.order
        run3.steps shouldBe run1.steps

        // (b) a different seed disagrees on the raw interleaving — proving the observable is
        // actually pinned to the seed's cross-host pick order, not merely to the graph shape
        // (a fan-out into commutative Set state cannot show this; see the class KDoc). Per
        // AGENTS.md this seed pair is kept once chosen, never swapped to make the test agree.
        val differently = runFanIn(seed = 7)
        differently.order shouldNotBe run1.order
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
