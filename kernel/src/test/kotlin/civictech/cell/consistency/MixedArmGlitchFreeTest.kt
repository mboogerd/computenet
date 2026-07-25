package civictech.cell.consistency

import civictech.cell.*
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.*
import civictech.cell.proxy.Invocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CP-B3 regression (spec 20/22 §Completeness — cross-replica extension, E3.4):
 * the replica-frontier settlement composes **per edge**, it does NOT globally
 * replace the whole-cell predicate.
 *
 * A single glitch-free consumer carries two kinds of arm at once:
 *  - a **local fan-in diamond** arm: one origin source `a` fans through two
 *    derived cells `B` and `C` into the consumer (edges B, C are *local*);
 *  - a **replica-fed** arm: a second source `r` feeds edge `D`, declared
 *    replica-fed and gated on a merged watermark ([ReplicaFrontier]).
 *
 * The local diamond must stay glitch-free — every released `a`-wave carries both
 * `B` and `C` under one timestamp, never a lone arm — even though a replica
 * frontier is installed on the same cell. Under the old *whole-cell* settlement
 * (a gate globally replacing `expectedEdges().all { isSettled }` with
 * `replicaReady`) the local arm lost its cross-inlink glitch-freedom: the wave
 * released the moment the invocation already present was complete, before the
 * sibling edge arrived. The [control] test pins that failure: the whole-cell
 * gate glitches the very same diamond.
 *
 * Liveness: every replica-fed `D` wave still surfaces once the merged watermark
 * catches up (the frontier holds them, then releases them on recheck).
 */
class MixedArmGlitchFreeTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    data class Observation(val label: String, val n: Int, val timestamp: Timestamp)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface InletProxy {
        val inlet: Use<Consumer<Pair<String, Int>>>
    }

    private fun <Api : Any> reroute(outlet: FanOutlet<Api>, inletRef: PortRef, routedApi: Api) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routedApi, inletRef))
    }

    /** Origin tag = the wave's own position; the replica-fed edge gates on it via the merged watermark. */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        inv.context?.timestamp?.let { listOf(it) } ?: emptyList()
    }

    /** A glitch = a local diamond wave whose {B, C} did not release together under one timestamp. */
    private fun localGlitched(obs: List<Observation>, waves: Int): Boolean {
        val local = obs.filter { it.label == "B" || it.label == "C" }
        if (local.size != waves * 2) return true
        return local.chunked(2).any { g ->
            g.map { it.timestamp }.toSet().size != 1 || g.map { it.label }.toSet() != setOf("B", "C")
        }
    }

    private class Wiring(
        val controller: SimulationController,
        val a: SourceCell,
        val r: SourceCell,
        val d: MapperCell<Int, Pair<String, Int>>,
        val gf: GlitchFreeCell<Consumer<Pair<String, Int>>>,
        val observations: List<Observation>,
    )

    private fun wire(seed: Long): Wiring {
        val controller = SimulationController(seed)
        val hostB = ManagedHost(scheduler = controller.scheduler())
        val hostC = ManagedHost(scheduler = controller.scheduler())
        val hostD = ManagedHost(scheduler = controller.scheduler())
        val hostGf = ManagedHost(scheduler = controller.scheduler())

        val a = SourceCell(consumerInt)
        val r = SourceCell(consumerInt)
        val b = MapperCell<Int, Pair<String, Int>>(f = { "B" to it })
        val c = MapperCell<Int, Pair<String, Int>>(f = { "C" to it })
        val d = MapperCell<Int, Pair<String, Int>>(f = { "D" to it })
        val observations = mutableListOf<Observation>()
        val gf = GlitchFreeCell(consumerPair)

        hostB.managementInlet.call.spawn(b)
        hostC.managementInlet.call.spawn(c)
        hostD.managementInlet.call.spawn(d)
        hostGf.managementInlet.call.spawn(gf)

        // local diamond: a fans out to B and C
        a.outlet.subscribe(Use.fixed(hostB.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed(hostC.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))
        // replica arm: r feeds D
        r.outlet.subscribe(Use.fixed(hostD.lookup<MapperProxy>(d.ref)!!.inlet.call, PortRef.generate()))

        val routedGf = hostGf.lookup<InletProxy>(gf.ref)!!.inlet.call
        val gfInletFrom = gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>
        (b.outlet.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        (c.outlet.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        (d.outlet.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        reroute(b.outlet, gf.inlet.ref, routedGf)
        reroute(c.outlet, gf.inlet.ref, routedGf)
        reroute(d.outlet, gf.inlet.ref, routedGf)

        gf.outlet.subscribe(Use.fixed(object : Consumer<Pair<String, Int>> {
            override fun provide(input: Pair<String, Int>) {
                observations += Observation(input.first, input.second, CurrentContext.get()!!.timestamp)
            }
        }, PortRef.generate()))

        return Wiring(controller, a, r, d, gf, observations)
    }

    private fun emitLoop(w: Wiring, seed: Long, waves: Int) {
        val rnd = Random(seed)
        for (n in 1..waves) {
            w.a.emit(n)
            w.r.emit(n)
            repeat(rnd.nextInt(4)) { w.controller.step() }
        }
    }

    /**
     * Per-edge fix: only edge D is replica-fed; the local diamond keeps the
     * ordinary cross-inlink frontier. The merged watermark lags during the run
     * (D waves are held), then catches up and releases them (liveness).
     */
    private fun runPerEdge(seed: Long, waves: Int): List<Observation> {
        val w = wire(seed)
        var mergedWatermark = Long.MIN_VALUE
        val frontier = ReplicaFrontier { _, counter -> counter <= mergedWatermark }
        w.gf.markReplicaFed(w.d.outlet.ref, frontier, originTags)

        emitLoop(w, seed, waves)
        w.controller.runToIdle()

        // merged watermark catches up: every held replica wave now surfaces.
        mergedWatermark = Long.MAX_VALUE
        w.gf.recheck()
        w.controller.runToIdle()
        return w.observations
    }

    /**
     * The old whole-cell gate: it globally replaces the settlement predicate for
     * *every* buffered wave, so the local diamond glitches. The merged watermark
     * is always caught up here, isolating the whole-cell defect from any lag.
     */
    private fun runWholeCell(seed: Long, waves: Int): List<Observation> {
        val w = wire(seed)
        val frontier = ReplicaFrontier { _, _ -> true }
        w.gf.useReplicaFrontier(frontier, originTags)

        emitLoop(w, seed, waves)
        w.controller.runToIdle()
        return w.observations
    }

    @Test
    fun `mixed local-diamond and replica-fed arms stay glitch-free - 120 seeds`() {
        val waves = 30
        for (seed in 0L until 120L) {
            val obs = runPerEdge(seed, waves)
            // local diamond glitch-free: every a-wave releases {B, C} together under one timestamp.
            localGlitched(obs, waves).shouldBe(false)
            // liveness: every local wave and every replica-fed wave surfaced.
            obs.filter { it.label == "B" }.map { it.n }.toSet() shouldBe (1..waves).toSet()
            obs.filter { it.label == "C" }.map { it.n }.toSet() shouldBe (1..waves).toSet()
            obs.filter { it.label == "D" }.map { it.n }.toSet() shouldBe (1..waves).toSet()
        }
    }

    @Test
    fun `control - the whole-cell gate glitches the local diamond on some seed`() {
        var glitched = 0
        for (seed in 0L until 120L) {
            if (localGlitched(runWholeCell(seed, waves = 30), 30)) glitched++
        }
        // if this fails the harness is too weak — the whole-cell gate must be seen to break the local arm.
        (glitched > 0).shouldBeTrue()
    }

    @Test
    fun `pure local diamond with a replica frontier present but no local edge marked stays glitch-free`() {
        val waves = 30
        for (seed in 0L until 40L) {
            val controller = SimulationController(seed)
            val hostB = ManagedHost(scheduler = controller.scheduler())
            val hostC = ManagedHost(scheduler = controller.scheduler())
            val hostGf = ManagedHost(scheduler = controller.scheduler())

            val a = SourceCell(consumerInt)
            val b = MapperCell<Int, Pair<String, Int>>(f = { "B" to it })
            val c = MapperCell<Int, Pair<String, Int>>(f = { "C" to it })
            val observations = mutableListOf<Observation>()
            val gf = GlitchFreeCell(consumerPair)

            hostB.managementInlet.call.spawn(b)
            hostC.managementInlet.call.spawn(c)
            hostGf.managementInlet.call.spawn(gf)

            a.outlet.subscribe(Use.fixed(hostB.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
            a.outlet.subscribe(Use.fixed(hostC.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))

            val routedGf = hostGf.lookup<InletProxy>(gf.ref)!!.inlet.call
            val gfInletFrom = gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>
            (b.outlet.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
            (c.outlet.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
            reroute(b.outlet, gf.inlet.ref, routedGf)
            reroute(c.outlet, gf.inlet.ref, routedGf)
            gf.outlet.subscribe(Use.fixed(object : Consumer<Pair<String, Int>> {
                override fun provide(input: Pair<String, Int>) {
                    observations += Observation(input.first, input.second, CurrentContext.get()!!.timestamp)
                }
            }, PortRef.generate()))

            // A replica frontier is present on the cell, but it governs an outlet that never
            // links here: marking is per-edge, so the actual local diamond edges keep the
            // ordinary cross-inlink predicate and stay glitch-free.
            gf.markReplicaFed(PortRef.generate(), ReplicaFrontier { _, _ -> false }, originTags)

            val rnd = Random(seed)
            for (n in 1..waves) {
                a.emit(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
            localGlitched(observations, waves).shouldBe(false)
        }
    }
}
