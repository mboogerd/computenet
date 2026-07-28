package civictech.cell.consistency

import civictech.cell.*
import civictech.cell.control.AttentionSupport
import civictech.cell.control.AttentionPolicy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.*
import civictech.cell.port.*
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M6.4 (spec 34 decision 3): a glitch-free diamond with one attention-parked
 * branch holds waves under WAIT (and completes them on resume — park never
 * drops), and completes degraded waves under DEGRADE with post-resume
 * replays passing through as late catch-up.
 */
class GlitchFreeSuspensionTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    data class Observation(val label: String, val n: Int, val counter: Long)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    class ObserverCell(
        clazz: Class<Consumer<Pair<String, Int>>>,
        private val observations: MutableList<Observation>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.serve(object : Consumer<Pair<String, Int>> {
                override fun provide(input: Pair<String, Int>) {
                    observations +=
                        Observation(input.first, input.second, CurrentContext.get()!!.timestamp.counter)
                }
            })
        }
    }

    interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface InletProxy {
        val inlet: Use<Consumer<Pair<String, Int>>>
    }

    private inner class Fixture(mode: GlitchFreeCell.WaveMode) {
        val controller = SimulationController()

        // only B's host maps attention; window of 3 dispatches before parking
        val hostB = ManagedHost(
            scheduler = controller.scheduler(),
            attention = AttentionPolicy(suspendAfter = 3),
        )
        val hostC = ManagedHost(scheduler = controller.scheduler())
        val hostD = ManagedHost(scheduler = controller.scheduler())

        val a = SourceCell(consumerInt)
        val b = MapperCell<Int, Pair<String, Int>>(f = { "B" to it })
        val c = MapperCell<Int, Pair<String, Int>>(f = { "C" to it })
        val observations = mutableListOf<Observation>()
        val observer = ObserverCell(consumerPair, observations)
        val gf = GlitchFreeCell(consumerPair, mode = mode)

        init {
            hostB.managementInlet.call.spawn(b)
            hostC.managementInlet.call.spawn(c)
            hostD.managementInlet.call.spawn(observer)
            hostD.managementInlet.call.spawn(gf)

            a.outlet.subscribe(Use.fixed(hostB.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
            a.outlet.subscribe(Use.fixed(hostC.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))

            // handshake registers the links (the notice path); delivery routed over hostD's queue
            val routedGf = hostD.lookup<InletProxy>(gf.ref)!!.inlet.call
            (b.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            (c.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            b.outlet.unsubscribe(gf.inlet.ref)
            c.outlet.unsubscribe(gf.inlet.ref)
            b.outlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
            c.outlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
            gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))

            controller.runToIdle()
            AttentionSupport.of(b).attend(0f) // B: explicit zero interest from the start
        }
    }

    @Test
    fun `WAIT holds incomplete waves across a parked branch and completes them on resume`() {
        val fixture = Fixture(GlitchFreeCell.WaveMode.WAIT)
        (1..10).forEach { fixture.a.emit(it) }
        fixture.controller.runToIdle()

        // 3 dispatches fit the window; wave 4+ parked at B, held at the join
        fixture.observations.size shouldBe 3 * 2
        fixture.observations.chunked(2).forEach { it.map { o -> o.label }.toSet() shouldBe setOf("B", "C") }

        AttentionSupport.of(fixture.b).attend(1f) // renewed interest: replay
        fixture.controller.runToIdle()

        // every wave complete, grouped, in per-source counter order — zero loss
        fixture.observations.size shouldBe 10 * 2
        fixture.observations.chunked(2).forEachIndexed { i, wave ->
            wave.map { it.label }.toSet() shouldBe setOf("B", "C")
            wave.map { it.n }.toSet() shouldBe setOf(i + 1)
            wave.map { it.counter }.toSet().size shouldBe 1
        }
    }

    @Test
    fun `DEGRADE shrinks the frontier for a parked branch and passes replays through as catch-up`() {
        val fixture = Fixture(GlitchFreeCell.WaveMode.DEGRADE)
        (1..10).forEach { fixture.a.emit(it) }
        fixture.controller.runToIdle()

        // waves 1-3 complete; waves 4-10 flushed degraded (C only) — no stall
        fixture.observations.size shouldBe 3 * 2 + 7
        fixture.observations.take(6).chunked(2).forEach { it.map { o -> o.label }.toSet() shouldBe setOf("B", "C") }
        fixture.observations.drop(6).forEach { it.label shouldBe "C" }
        fixture.observations.drop(6).map { it.n } shouldBe (4..10).toList()

        AttentionSupport.of(fixture.b).attend(1f)
        fixture.controller.runToIdle()

        // B's replays arrive late, solo, in order — nothing re-buffers, nothing is lost
        fixture.observations.size shouldBe 10 * 2
        val stragglers = fixture.observations.drop(13)
        stragglers.forEach { it.label shouldBe "B" }
        stragglers.map { it.n } shouldBe (4..10).toList()
    }
}
