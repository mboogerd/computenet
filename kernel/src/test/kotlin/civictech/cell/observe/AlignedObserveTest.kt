package civictech.cell.observe

import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.testkit.awaitUntil
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random

/**
 * The aligned multi-view sink's exit criterion (spec 20/22 §The observation
 * frontier, `[22-OBS-01]`/`[22-OBS-02]`; 96 §E2.3), in
 * [civictech.cell.consistency.GlitchFreeDiamondTest]'s register: a fork from one
 * source into two named views is provably assembled from single settled waves
 * under seeded schedules, with a control — the point-consistent
 * [CompositeSink] — proving the harness actually catches mixed-wave assembly.
 *
 * **The graph** (shared by the invariant run and its control): one `SetCell`
 * source fans to two views — `items` straight off the source, and `filtered`
 * through a `FilterCell` keeping the even elements. Both derive from the *same*
 * source wave, so `filtered == items.filter(even)` holds for every per-source
 * frontier of the inputs and fails for every composite assembled from two
 * different waves. That is the invariant.
 *
 * **The interleaving.** The `items` arm's delivery is rerouted through the
 * host's queue (the `GlitchFreeDiamondTest` reroute: the handshaken link stays,
 * only delivery is queued), so it lags the synchronously-fused `filtered` arm by
 * a seed-varied number of waves — the sink must hold, order, and release a deep
 * buffer rather than publishing whatever arrived last. The `filtered` arm is
 * deliberately left fused: its absorb-acks travel the protocol lane, which is
 * synchronous on the sender's thread, so queueing *its* data while its acks stay
 * direct would reorder metadata ahead of data on one edge — an artifact of the
 * reroute harness (a real in-process edge is fused, a bridged one carries both
 * in frame order), not a property of the sink under test.
 */
class AlignedObserveTest {

    /** Minimal lookup proxy exposing a hosted [SetCell]'s write inlet. */
    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private fun evens(upTo: Int) = (1..upTo).filter { it % 2 == 0 }.toSet()

    // ---- the shared graph + harness -----------------------------------------

    private class Graph(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        val filter = FilterCell<Int> { it % 2 == 0 }

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(source)
            mgmt.spawn(filter)
            mgmt.connect(source.ref, "outlet", filter.ref, "inlet")
        }
    }

    private fun ops(graph: Graph): SetOps<Int> =
        graph.host.lookup<IntSetInlet>(graph.source.ref)!!.inlet.call

    /**
     * Routes an already-handshaken link over the target's host queue: the direct
     * subscription installed by the handshake is replaced by a routed
     * [civictech.cell.host.RoutedPropagate] keyed under the same port ref, so the
     * `Link` (and therefore `EdgeOpen`, `EdgeClose` and the `Progress` lane)
     * stays while delivery is queued. Verbatim the `GlitchFreeDiamondTest` move,
     * with `ManagedHost.inlet` standing in for a per-port proxy interface (the
     * aligned sink's port names are chosen by the app, so no static proxy
     * interface can name them).
     */
    private fun rerouteThroughHostQueue(
        host: ManagedHost,
        outlet: Subscribe<Propagate<SetDelta<Int>>>,
        inletRef: PortRef,
        target: CellRef,
        portName: String,
    ) {
        val routed: Propagate<SetDelta<Int>> = host.inlet(target, portName)
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** Seed-randomized partial draining between writes, as `GlitchFreeDiamondTest` drives its diamond. */
    private fun drive(graph: Graph, waves: Int, seed: Long) {
        val ops = ops(graph)
        val rnd = Random(seed)
        for (n in 1..waves) {
            ops.add(n)
            repeat(rnd.nextInt(4)) { graph.controller.step() }
        }
        graph.controller.runToIdle()
    }

    private class Recorded(val composites: List<Map<String, Any?>>, val unmatched: Long, val buffered: Int)

    private fun alignedRun(seed: Long, waves: Int): Recorded {
        val graph = Graph(seed)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        rerouteThroughHostQueue(
            graph.host,
            graph.source.outlet,
            sink.inlets.getValue("items").ref,
            sink.ref,
            "items",
        )

        val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { recorded += it }

        drive(graph, waves, seed)
        awaitUntil("every aligned composite delivered (seed $seed)") { recorded.size >= waves + 1 }
        val result = Recorded(recorded.toList(), sink.unmatchedDeltas, sink.bufferedWaves)
        sink.close()
        return result
    }

    /**
     * The control: the same graph, the same reroute, the same seeds — but each
     * named view folded by its own [ObserveCell] and assembled by the
     * point-consistent [CompositeSink] (what [observeAll] builds). Constructed
     * by hand rather than through `observeAll` only so the reroute can target
     * the same port refs; the wiring is what `observeAll` produces.
     */
    private fun controlRun(seed: Long, waves: Int): List<Map<String, Any?>> {
        val graph = Graph(seed)
        val mgmt = graph.host.managementInlet.call
        val items = ObserveCell(View.set<Int>())
        val filtered = ObserveCell(View.set<Int>())
        mgmt.spawn(items)
        mgmt.spawn(filtered)
        mgmt.connect(graph.source.ref, "outlet", items.ref, "inlet")
        mgmt.connect(graph.filter.ref, "outlet", filtered.ref, "inlet")
        rerouteThroughHostQueue(graph.host, graph.source.outlet, items.inlet.ref, items.ref, "inlet")

        val view = CompositeSink(
            mapOf("items" to items, "filtered" to filtered),
            mapOf("items" to "set", "filtered" to "set"),
        )
        val recorded = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        view.onChange { recorded += it }

        drive(graph, waves, seed)
        val settled = mapOf<String, Any?>("items" to (1..waves).toSet(), "filtered" to evens(waves))
        awaitUntil("control composite settles (seed $seed)") { view.current() == settled }
        val result = recorded.toList()
        view.close()
        items.close()
        filtered.close()
        return result
    }

    private fun mixesWaves(composite: Map<String, Any?>): Boolean {
        val items = composite["items"] as Set<*>
        val filtered = composite["filtered"] as Set<*>
        return filtered != items.filter { (it as Int) % 2 == 0 }.toSet()
    }

    // ---- the invariant run ---------------------------------------------------

    @Test
    fun `every published composite is one settled wave, for every seed`() {
        val waves = 30
        for (seed in 0L until 200L) {
            val run = alignedRun(seed, waves)

            // one composite per effective wave, plus the registration catch-up:
            // no torn republication, nothing swallowed.
            run.composites.size shouldBe waves + 1
            run.composites.forEachIndexed { i, composite ->
                // internal consistency (22-OBS-01): the composite is the correct
                // output for ONE per-source frontier of the inputs — both views
                // folded the same source prefix.
                mixesWaves(composite) shouldBe false
                // per-source monotone publication order: composite i is exactly
                // the fold of waves 1..i, never i-1 and never i+1.
                composite["items"] shouldBe (1..i).toSet()
                composite["filtered"] shouldBe evens(i)
            }
            // every delta was accounted for by the frontier — none installed
            // unaligned through the unmatched-edge escape hatch...
            run.unmatched shouldBe 0L
            // ...and no wave was left permanently buffered.
            run.buffered shouldBe 0
        }
    }

    @Test
    fun `control - the point-consistent composite mixes waves on at least one seed`() {
        var mixed = 0
        for (seed in 0L until 50L) {
            if (controlRun(seed, waves = 30).any { mixesWaves(it) }) mixed++
        }
        // if this fails the harness is too weak to certify the aligned sink —
        // tune the interleaving as GlitchFreeDiamondTest does.
        (mixed > 0).shouldBeTrue()
    }

    // ---- absorbed-arm liveness (the Progress absorb-ack path) ----------------

    @Test
    fun `a wave the filter arm swallows entirely still settles the composite`() {
        val graph = Graph(seed = 11)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        // the filtered arm's ONLY settlement signal for an odd wave is the
        // CP-A3 absorb-ack: the items arm is queued, so the wave cannot be
        // released by a later delta arriving first.
        rerouteThroughHostQueue(
            graph.host,
            graph.source.outlet,
            sink.inlets.getValue("items").ref,
            sink.ref,
            "items",
        )
        val seen = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { seen += it }

        val ops = ops(graph)
        ops.add(1) // odd: FilterCell absorbs it and emits nothing
        graph.controller.runToIdle()

        sink.current() shouldBe mapOf("items" to setOf(1), "filtered" to emptySet<Int>())
        sink.bufferedWaves shouldBe 0 // nothing stranded on the absorbed arm

        ops.add(2) // even: the arm produces again, and the sink keeps aligning
        ops.add(3)
        graph.controller.runToIdle()

        sink.current() shouldBe mapOf("items" to setOf(1, 2, 3), "filtered" to setOf(2))
        sink.bufferedWaves shouldBe 0
        awaitUntil("three settled waves plus the catch-up") { seen.size >= 4 }
        seen.forEach { mixesWaves(it) shouldBe false }

        sink.close()
    }

    // ---- the phantom expected edge (G-13, the WAIT shape) --------------------

    @Test
    fun `a view fed by an independent root holds waves until its edge closes`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        mgmt.spawn(a)
        mgmt.spawn(b)

        val sink = host.observeAligned {
            set("a", a.ref)
            set("b", b.ref)
        }
        host.lookup<IntSetInlet>(a.ref)!!.inlet.call.add(1)
        controller.runToIdle()

        // b's edge is open and is an expected sibling for a's wave, but b's root
        // never mints a's source: the documented static-link-set frontier
        // (G-13) has no upstream traversal to know that. WAIT shape — the wave
        // is held, not degraded away, and not silently published half-applied.
        sink.bufferedWaves shouldBe 1
        sink.current() shouldBe mapOf("a" to emptySet<Int>(), "b" to emptySet<Int>())

        // EdgeClose shrinks the condition; the held wave releases at once.
        sink.inlets.getValue("b").linking.links.single().unlink()

        sink.bufferedWaves shouldBe 0
        sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())

        sink.close()
    }

    // ---- late join: baselines install as arm state ---------------------------

    @Test
    fun `a sink attached after deltas have flowed catches up without admitting a wave`() {
        val graph = Graph(seed = 3)
        val ops = ops(graph)
        ops.add(1); ops.add(2); ops.add(3)
        graph.controller.runToIdle()

        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        graph.controller.runToIdle()

        // the producers' onLinked catch-up seeded each arm from materialized
        // state; it is arm state, never a wave position, so no completeness set
        // admitted it.
        sink.current() shouldBe mapOf("items" to setOf(1, 2, 3), "filtered" to setOf(2))
        sink.bufferedWaves shouldBe 0
        sink.unmatchedDeltas shouldBe 0L

        // and a listener registered now is caught up in ONE call with that state
        val seen = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { seen += it }
        awaitUntil("late-join catch-up") { seen.isNotEmpty() }
        seen.single() shouldBe sink.current()

        // live waves keep aligning afterwards
        ops.add(4)
        graph.controller.runToIdle()
        awaitUntil("the post-attach wave is published") { seen.size >= 2 }
        sink.current() shouldBe mapOf("items" to setOf(1, 2, 3, 4), "filtered" to setOf(2, 4))
        seen.forEach { mixesWaves(it) shouldBe false }

        sink.close()
    }

    // ---- per-name inlets: two views over ONE outlet stay distinguishable -----

    @Test
    fun `two views over the same outlet keep their own inlets, edges and folds`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        host.managementInlet.call.spawn(source)

        // Same source outlet, two named views: the two edges share a source
        // PORT, so view identity can only come from which inlet the delta
        // arrived on ([22-OBS-02]'s per-name clause).
        val sink = host.observeAligned {
            set("left", source.ref)
            set("right", source.ref)
        }
        val ops = host.lookup<IntSetInlet>(source.ref)!!.inlet.call
        ops.add(1); ops.add(2)
        controller.runToIdle()

        sink.current() shouldBe mapOf("left" to setOf(1, 2), "right" to setOf(1, 2))
        sink.unmatchedDeltas shouldBe 0L // neither edge was mistaken for the other
        sink.bufferedWaves shouldBe 0
        sink.get<Set<Int>>("left") shouldBe setOf(1, 2)

        sink.close()
    }

    // ---- lifecycle: Stateful folds, and the RESTART deaf-sink regression -----

    @Test
    fun `snapshot and restore carry every named view's fold`() {
        val graph = Graph(seed = 5)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        val ops = ops(graph)
        ops.add(1); ops.add(2)
        graph.controller.runToIdle()

        val checkpoint = sink.snapshot()

        val restored = AlignedCompositeCell(mapOf("items" to View.set<Int>(), "filtered" to View.set<Int>()))
        restored.current() shouldBe mapOf("items" to emptySet<Int>(), "filtered" to emptySet<Int>())
        restored.restore(checkpoint)
        restored.current() shouldBe mapOf("items" to setOf(1, 2), "filtered" to setOf(2))

        restored.close()
        sink.close()
    }

    @Test
    fun `a sink still notifies after the deactivate-reactivate cycle a restart performs`() {
        val graph = Graph(seed = 5)
        val sink = graph.host.observeAligned {
            set("items", graph.source.ref)
            set("filtered", graph.filter.ref)
        }
        val fires = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { fires += it }
        awaitUntil("registration catch-up") { fires.isNotEmpty() }

        val ctx = object : CellContext {}
        sink.onDeactivate(ctx)
        sink.onActivate(ctx)

        ops(graph).add(2)
        graph.controller.runToIdle()
        awaitUntil("post-reactivation change still reaches the listener") { fires.size >= 2 }
        fires.last() shouldBe mapOf("items" to setOf(2), "filtered" to setOf(2))

        sink.close()
    }
}
