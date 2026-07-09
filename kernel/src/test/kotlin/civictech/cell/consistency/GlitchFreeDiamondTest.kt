package civictech.cell.consistency

import civictech.cell.*
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.*
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * The M2 exit criterion: a fork-join diamond (A → B,C → D) is provably
 * glitch-free under seeded randomized scheduling — invariant-checked over many
 * seeds, with a control run proving the harness actually produces glitches.
 */
class GlitchFreeDiamondTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    data class Observation(val label: String, val n: Int, val timestamp: Timestamp)

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
                    observations += Observation(input.first, input.second, CurrentContext.get()!!.timestamp)
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

    /**
     * Routes an already-handshaken link over the target's host queue: the direct
     * subscription installed by the handshake is replaced by the routed proxy Api,
     * keyed under the same port ref, so the Link stays while delivery is queued.
     * (The wire layer, M5, will do this inside cross-host handshakes.)
     */
    private fun <Api : Any> reroute(outlet: FanOutlet<Api>, inletRef: PortRef, routedApi: Api) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routedApi, inletRef))
    }

    private fun runDiamond(
        seed: Long,
        waves: Int,
        protected: Boolean,
        unlinkCAfter: Int? = null,
    ): List<Observation> {
        val controller = SimulationController(seed)
        val hostB = ManagedHost(scheduler = controller.scheduler())
        val hostC = ManagedHost(scheduler = controller.scheduler())
        val hostD = ManagedHost(scheduler = controller.scheduler())

        val a = SourceCell(consumerInt)
        val b = MapperCell<Int, Pair<String, Int>>(f = { "B" to it })
        val c = MapperCell<Int, Pair<String, Int>>(f = { "C" to it })
        val observations = mutableListOf<Observation>()
        val observer = ObserverCell(consumerPair, observations)
        val gf = GlitchFreeCell(consumerPair)

        hostB.managementInlet.call.spawn(b)
        hostC.managementInlet.call.spawn(c)
        hostD.managementInlet.call.spawn(observer)
        if (protected) hostD.managementInlet.call.spawn(gf)

        // A fans out to B and C through their host queues (routed, unique refs)
        a.outlet.subscribe(Use.fixed(hostB.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed(hostC.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))

        if (protected) {
            // handshake registers the links; then route delivery over hostD's queue
            val routedGf = hostD.lookup<InletProxy>(gf.ref)!!.inlet.call
            (b.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            (c.outlet.linkTo(gf.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
            reroute(b.outlet, gf.inlet.ref, routedGf)
            reroute(c.outlet, gf.inlet.ref, routedGf)
            // the observer sits behind the wrapper (direct, runs on hostD's task)
            gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        } else {
            // control: B and C hit the observer directly through hostD's queue
            val routedObserver = hostD.lookup<InletProxy>(observer.ref)!!.inlet.call
            b.outlet.subscribe(Use.fixed(routedObserver, PortRef.generate()))
            c.outlet.subscribe(Use.fixed(routedObserver, PortRef.generate()))
        }

        val rnd = Random(seed)
        for (n in 1..waves) {
            if (unlinkCAfter != null && n == unlinkCAfter + 1) {
                controller.runToIdle()
                gf.inlet.linking.links.single { it.from == c.outlet.ref }.unlink()
            }
            a.emit(n)
            repeat(rnd.nextInt(4)) { controller.step() } // partial, seed-randomized draining
        }
        controller.runToIdle()
        return observations
    }

    @Test
    fun `diamond is glitch-free for every seed`() {
        val waves = 50
        for (seed in 0L until 200L) {
            val obs = runDiamond(seed, waves, protected = true)
            obs.size shouldBe waves * 2
            obs.chunked(2).forEachIndexed { i, wave ->
                // complete wave group: both branches, same timestamp, never mixed
                wave.map { it.timestamp }.toSet().size shouldBe 1
                wave.map { it.label }.toSet() shouldBe setOf("B", "C")
                wave.map { it.n }.toSet() shouldBe setOf(i + 1)
            }
            // per-source monotonic wave order
            obs.chunked(2).map { it[0].timestamp.counter } shouldBe (1L..waves).toList()
        }
    }

    @Test
    fun `control - the unprotected diamond glitches on at least one seed`() {
        var glitched = 0
        for (seed in 0L until 50L) {
            val obs = runDiamond(seed, waves = 50, protected = false)
            val mixed = obs.chunked(2).any { wave ->
                wave.size < 2 || wave.map { it.timestamp }.toSet().size != 1 ||
                    wave.map { it.label }.toSet() != setOf("B", "C")
            }
            if (mixed) glitched++
        }
        // if this fails the harness is too weak to detect glitches — tune interleaving
        (glitched > 0).shouldBeTrue()
    }

    @Test
    fun `unlinking a branch shrinks the completeness condition without stalling`() {
        val obs = runDiamond(seed = 7, waves = 20, protected = true, unlinkCAfter = 10)

        // 10 full waves of {B, C}, then 10 B-only waves — no permanent stall
        obs.size shouldBe 10 * 2 + 10
        obs.take(20).chunked(2).forEach { it.map { o -> o.label }.toSet() shouldBe setOf("B", "C") }
        obs.drop(20).forEach { it.label shouldBe "B" }
        obs.last().n shouldBe 20
    }
}
