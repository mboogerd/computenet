package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MapperCell
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.BridgeIngressCell
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CP-A4: the wave-completeness frontier as an opt-in per-inlet policy
 * ([civictech.cell.port.FanInlet.frontierPolicy] wired to a [WaveFrontier]).
 * A plain cell that installs the policy is glitch-free without being a
 * [GlitchFreeCell]; the sugar cell is now just the common packaging of the same
 * policy (its tests — GlitchFreeDiamondTest/SuspensionTest/StallTest — stay
 * green unchanged, verifying the extraction preserved behavior).
 */
class InletFrontierPolicyTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    data class Obs(val label: String, val n: Int, val ts: Timestamp)

    class SourceCell(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    /** A plain observer whose own inlet carries a WAIT frontier policy — no GlitchFreeCell involved. */
    class PolicyObserverCell(
        clazz: Class<Consumer<Pair<String, Int>>>,
        private val out: MutableList<Obs>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.serve(object : Consumer<Pair<String, Int>> {
                override fun provide(input: Pair<String, Int>) {
                    out += Obs(input.first, input.second, CurrentContext.get()!!.timestamp)
                }
            })
            inlet.frontierPolicy = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        }
    }

    interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface PairInlet {
        val inlet: Use<Consumer<Pair<String, Int>>>
    }

    private fun runDiamond(seed: Long, waves: Int): List<Obs> {
        val controller = SimulationController(seed)
        val hostB = ManagedHost(scheduler = controller.scheduler())
        val hostC = ManagedHost(scheduler = controller.scheduler())
        val hostD = ManagedHost(scheduler = controller.scheduler())

        val a = SourceCell(consumerInt)
        val b = MapperCell<Int, Pair<String, Int>>(f = { "B" to it })
        val c = MapperCell<Int, Pair<String, Int>>(f = { "C" to it })
        val obs = mutableListOf<Obs>()
        val observer = PolicyObserverCell(consumerPair, obs)

        hostB.managementInlet.call.spawn(b)
        hostC.managementInlet.call.spawn(c)
        hostD.managementInlet.call.spawn(observer)

        a.outlet.subscribe(Use.fixed(hostB.lookup<MapperProxy>(b.ref)!!.inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed(hostC.lookup<MapperProxy>(c.ref)!!.inlet.call, PortRef.generate()))

        // both arms link into the policy-gated inlet (fires EdgeOpen); delivery routed over hostD
        val routed = hostD.lookup<PairInlet>(observer.ref)!!.inlet.call
        (b.outlet.linkTo(observer.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
        (c.outlet.linkTo(observer.inlet as LinkFrom<Consumer<Pair<String, Int>>>) is LinkResult.Connected).shouldBeTrue()
        b.outlet.unsubscribe(observer.inlet.ref)
        c.outlet.unsubscribe(observer.inlet.ref)
        b.outlet.subscribe(Use.fixed(routed, observer.inlet.ref))
        c.outlet.subscribe(Use.fixed(routed, observer.inlet.ref))

        val rnd = Random(seed)
        for (n in 1..waves) {
            a.emit(n)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return obs
    }

    @Test
    fun `a plain cell with a WAIT frontier policy is glitch-free for every seed`() {
        val waves = 50
        for (seed in 0L until 200L) {
            val obs = runDiamond(seed, waves)
            obs.size shouldBe waves * 2
            obs.chunked(2).forEachIndexed { i, wave ->
                wave.map { it.ts }.toSet().size shouldBe 1
                wave.map { it.label }.toSet() shouldBe setOf("B", "C")
                wave.map { it.n }.toSet() shouldBe setOf(i + 1)
            }
            obs.chunked(2).map { it[0].ts.counter } shouldBe (1L..waves).toList()
        }
    }

    // ---------------------------------------------------------------------
    // The bridged CP-A2 scenario, re-run with a plain policy cell as the far
    // consumer: an absorbed remote wave settles via a bridged Progress ack.
    // ---------------------------------------------------------------------

    private val propString = @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>)
    private val propInt = @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<Int>>)

    /** A plain string consumer whose inlet carries a WAIT frontier policy (the policy form of a bridged consumer). */
    private class PolicyStringCell(
        clazz: Class<Propagate<String>>,
        val out: MutableList<Pair<String, Timestamp>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.serve(object : Propagate<String> {
                override fun propagate(value: String) {
                    out += value to CurrentContext.get()!!.timestamp
                }
            })
            inlet.frontierPolicy = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        }
    }

    class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    class Mapper(val label: String, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<Int> {
                override fun propagate(value: Int) {
                    outlet.call.propagate("$label:$value")
                }
            })
        }
    }

    /** Absorbing bridged arm: even waves emit; odd waves are acked with a downstream Progress. */
    class Absorber(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<Int> {
                override fun propagate(value: Int) {
                    val ctx = CurrentContext.get()!!
                    if (value % 2 == 0) outlet.call.propagate("C:$value")
                    else outlet.linking.links.forEach {
                        Protocols.sendDownstream(it, Protocols.Progress, Progress(ctx.timestamp.sourceId, ctx.timestamp.counter))
                    }
                }
            })
        }
    }

    interface IntInlet {
        val inlet: Use<Propagate<Int>>
    }

    interface StringInlet {
        val inlet: Use<Propagate<String>>
    }

    interface FrameInlet {
        val inlet: Use<Propagate<ByteArray>>
    }

    @Test
    fun `the bridged CP-A2 diamond through the policy form settles an absorbed remote wave`() {
        val controller = SimulationController(3)
        val regNear = civictech.cell.host.LocationRegistry()
        val regFar = civictech.cell.host.LocationRegistry()
        val hostNear = ManagedHost(scheduler = controller.scheduler(), registry = regNear)
        val hostC = ManagedHost(scheduler = controller.scheduler(), registry = regNear)
        val hostFar = ManagedHost(scheduler = controller.scheduler(), registry = regFar)

        val egressNF = BridgeEgressCell()
        val egressFN = BridgeEgressCell()
        val ingressNF = BridgeIngressCell(deliverTo = InvocationSink(regFar::deliver), replySink = InvocationSink { egressFN.deliver(it) })
        val ingressFN = BridgeIngressCell(deliverTo = InvocationSink(regNear::deliver), replySink = InvocationSink { egressNF.deliver(it) })
        hostFar.managementInlet.call.spawn(ingressNF)
        hostNear.managementInlet.call.spawn(ingressFN)
        egressNF.outlet.subscribe(Use.fixed((HostedCellProxy.create(ingressNF.ref, regFar, FrameInlet::class.java) as FrameInlet).inlet.call, PortRef.generate()))
        egressFN.outlet.subscribe(Use.fixed((HostedCellProxy.create(ingressFN.ref, regNear, FrameInlet::class.java) as FrameInlet).inlet.call, PortRef.generate()))

        val a = Source()
        val c = Absorber()
        val b = Mapper("B")
        val out = mutableListOf<Pair<String, Timestamp>>()
        val consumer = PolicyStringCell(propString, out)

        hostNear.managementInlet.call.spawn(a)
        hostC.managementInlet.call.spawn(c)
        hostFar.managementInlet.call.spawn(b)
        hostFar.managementInlet.call.spawn(consumer)
        controller.runToIdle()

        a.outlet.subscribe(Use.fixed((HostedCellProxy.create(c.ref, regNear, IntInlet::class.java) as IntInlet).inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed((HostedCellProxy.create(b.ref, InvocationSink(egressNF::deliver), IntInlet::class.java) as IntInlet).inlet.call, PortRef.generate()))

        // B local to the far consumer; C bridged in
        b.outlet.linkTo(consumer.inlet as LinkFrom<Propagate<String>>)
        c.outlet.subscribe(Use.fixed((HostedCellProxy.create(consumer.ref, InvocationSink(egressNF::deliver), StringInlet::class.java) as StringInlet).inlet.call, PortRef.generate()))
        c.outlet.bridgeTo(PortAddress(c.ref, "outlet"), PortAddress(consumer.ref, "inlet"), InvocationSink(egressNF::deliver))
        consumer.inlet.bridgeFrom(PortAddress(consumer.ref, "inlet"), PortAddress(c.ref, "outlet"), InvocationSink(egressFN::deliver))
        controller.runToIdle()

        val rnd = Random(3)
        for (n in 1..9) {
            a.emit(n)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        // the plain policy consumer completes every wave, including the absorbed final one (9)
        out.filter { it.first.startsWith("B") }.map { it.second.counter }.toSet() shouldBe (1L..9L).toSet()
        out.filter { it.first.startsWith("C") }.map { it.second.counter }.toSet() shouldBe setOf(2L, 4L, 6L, 8L)
    }
}
