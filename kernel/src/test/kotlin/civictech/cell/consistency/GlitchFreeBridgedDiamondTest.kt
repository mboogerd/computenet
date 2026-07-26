package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.attention.Progress
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.CurrentPeer
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Protocols
import civictech.cell.port.Use
import civictech.cell.port.allowPeers
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.BridgeIngressCell
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.cell.wire.defaultProtocolCapabilities
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * CP-A2 (spec 20/22 §Bridged frontier, 40/41 point 4, closes C-13): the M2
 * fork-join diamond with one arm crossing a wire bridge into a glitch-free
 * consumer on the far host. The bridged edge now runs the real `handshake()`
 * — its `EdgeOpen`/`EdgeClose` cross the wire and its peer allowlist fires —
 * and `Progress` absorb-acks ride the same frame path, so a silently-absorbed
 * remote wave settles the far-host frontier exactly as a local one would.
 *
 * Two `ManagedHost`s under one `SimulationController` model two processes; a
 * full-duplex bridge (an egress/ingress pair per direction, mirroring
 * `Peering`) carries data and protocol frames. The Near→Far channel duplicates
 * protocol frames on a seed to prove the frontier machinery is idempotent
 * under metadata redelivery; cross-host scheduling supplies the reorder that
 * would glitch an unprotected consumer.
 */
class GlitchFreeBridgedDiamondTest {

    private val propagateInt = @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<Int>>)
    private val propagateString = @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>)

    data class Obs(val label: String, val n: Int, val ts: Timestamp)

    /** Source A: mints a fresh wave per emission. */
    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    /** Reactive mapper Int -> "label:n": preserves the incoming wave timestamp. */
    class LabelMapper(private val label: String, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("$label:$n") }
        }
    }

    /**
     * The far bridged arm as an *absorbing* operator: even waves emit a real
     * delta ("C:n"); odd waves are swallowed and acknowledged with a
     * downstream `Progress(sourceId, thru)` minted against the incoming wave —
     * the exact absorb-ack CP-A3 wires into the operator suite, exercised here
     * over the bridge. Progress fans over the outlet's real links only (the
     * bridged edge), never the data-proxy subscription.
     */
    class AbsorbGate(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n ->
                val ctx = CurrentContext.get()!!
                if (n % 2 == 0) {
                    outlet.call.propagate("C:$n")
                } else {
                    val ack = Progress(ctx.timestamp.sourceId, ctx.timestamp.counter)
                    outlet.linking.links.forEach { Protocols.sendDownstream(it, Protocols.Progress, ack) }
                }
            }
        }
    }

    class ObserverCell(
        private val out: MutableList<Obs>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        init {
            inlet.onEach { s ->
                val (label, n) = s.split(":")
                out += Obs(label, n.toInt(), CurrentContext.get()!!.timestamp)
            }
        }
    }

    interface IntInlet {
        val inlet: Use<Propagate<Int>>
    }

    interface StringInlet {
        val inlet: Use<Propagate<String>>
    }

    /** Two processes and a full-duplex bridge; the Near→Far leg duplicates protocol frames on the seed. */
    private class Net(seed: Long) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val registryNear = LocationRegistry()
        val registryFar = LocationRegistry()
        val hostNear = ManagedHost(scheduler = controller.scheduler(), registry = registryNear)
        // C runs on its own near-side host so its arm is scheduled independently
        // of the B arm — the seed-driven cross-host reorder the join must absorb.
        val hostC = ManagedHost(scheduler = controller.scheduler(), registry = registryNear)
        val hostFar = ManagedHost(scheduler = controller.scheduler(), registry = registryFar)

        val egressNF = BridgeEgressCell() // Near -> Far (data + EdgeOpen/Progress)
        val egressFN = BridgeEgressCell() // Far -> Near (upstream replies)

        init {
            val ingressNF = BridgeIngressCell(
                deliverTo = InvocationSink(registryFar::deliver),
                replySink = InvocationSink { egressFN.deliver(it) },
            )
            val ingressFN = BridgeIngressCell(
                deliverTo = InvocationSink(registryNear::deliver),
                replySink = InvocationSink { egressNF.deliver(it) },
            )
            hostFar.managementInlet.call.spawn(ingressNF)
            hostNear.managementInlet.call.spawn(ingressFN)

            val ingressNFApi = (HostedCellProxy.create(ingressNF.ref, registryFar, FrameInlet::class.java)
                    as FrameInlet).inlet.call
            // duplicate protocol frames on a coin flip: idempotency of the frontier plane
            egressNF.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) {
                    ingressNFApi.propagate(value)
                    if (WireCodec.decode(value).type == civictech.cell.proxy.HostedPortInvocation.Type.PORT_PROTOCOL &&
                        rnd.nextBoolean()
                    ) {
                        ingressNFApi.propagate(value)
                    }
                }
            }, PortRef.generate()))

            val ingressFNApi = (HostedCellProxy.create(ingressFN.ref, registryNear, FrameInlet::class.java)
                    as FrameInlet).inlet.call
            egressFN.outlet.subscribe(Use.fixed(ingressFNApi, PortRef.generate()))
        }

        fun proxyFor(ref: CellRef): StringInlet =
            HostedCellProxy.create(ref, InvocationSink(egressNF::deliver), StringInlet::class.java) as StringInlet

        fun intProxyFor(ref: CellRef): IntInlet =
            HostedCellProxy.create(ref, InvocationSink(egressNF::deliver), IntInlet::class.java) as IntInlet

        /** Near-side, host-queued proxy (no bridge): delivery lands on the target's own host. */
        fun nearIntProxy(ref: CellRef): IntInlet =
            HostedCellProxy.create(ref, registryNear, IntInlet::class.java) as IntInlet
    }

    interface FrameInlet {
        val inlet: Use<Propagate<ByteArray>>
    }

    /**
     * Runs the diamond. A (Near) fans to C (Near) and B (Far); B is local to
     * the far consumer, C crosses the bridge into it. Returns the observer's
     * arrival log.
     */
    private fun runDiamond(seed: Long, waves: Int, protected: Boolean): List<Obs> {
        val net = Net(seed)
        val obs = mutableListOf<Obs>()

        val a = SourceCell()
        val c = LabelMapper("C")
        val b = LabelMapper("B")
        val observer = ObserverCell(obs)
        val gf = GlitchFreeCell(propagateString)

        net.hostNear.managementInlet.call.spawn(a)
        net.hostC.managementInlet.call.spawn(c)
        net.hostFar.managementInlet.call.spawn(b)
        net.hostFar.managementInlet.call.spawn(observer)
        if (protected) net.hostFar.managementInlet.call.spawn(gf)
        net.controller.runToIdle()

        // A -> C on its own near host (reactive); A -> B across the bridge to Far.
        a.outlet.subscribe(Use.fixed(net.nearIntProxy(c.ref).inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed(net.intProxyFor(b.ref).inlet.call, PortRef.generate()))

        if (protected) {
            // B is local to gf; establish the link (fires EdgeOpen locally).
            (b.outlet.linkTo(gf.inlet as LinkFrom<Propagate<String>>) is LinkResult.Connected).shouldBeTrue()
            // C crosses the bridge: data over a proxy, edge over a bridged handshake.
            c.outlet.subscribe(Use.fixed(net.proxyFor(gf.ref).inlet.call, PortRef.generate()))
            (c.outlet.bridgeTo(
                selfAddr = PortAddress(c.ref, "outlet"),
                toAddr = PortAddress(gf.ref, "inlet"),
                sink = InvocationSink(net.egressNF::deliver),
            ) is LinkResult.Connected).shouldBeTrue()
            gf.inlet.bridgeFrom(
                selfAddr = PortAddress(gf.ref, "inlet"),
                fromAddr = PortAddress(c.ref, "outlet"),
                sink = InvocationSink(net.egressFN::deliver),
            )
            gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        } else {
            // control: both arms hit the observer directly (no wave grouping)
            b.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
            c.outlet.subscribe(Use.fixed(net.proxyFor(observer.ref).inlet.call, PortRef.generate()))
        }
        net.controller.runToIdle()

        val rnd = Random(seed xor 0x5eed)
        for (n in 1..waves) {
            a.emit(n)
            repeat(rnd.nextInt(4)) { net.controller.step() } // partial, seed-randomized draining
        }
        net.controller.runToIdle()
        return obs
    }

    @Test
    fun `bridged diamond is glitch-free for every seed`() {
        val waves = 20
        for (seed in 0L until 100L) {
            val obs = runDiamond(seed, waves, protected = true)
            obs.size shouldBe waves * 2
            obs.chunked(2).forEachIndexed { i, wave ->
                withClue(seed, wave) {
                    // one wave group: both arms, one timestamp, never mixed
                    wave.map { it.ts }.toSet().size shouldBe 1
                    wave.map { it.label }.toSet() shouldBe setOf("B", "C")
                    wave.map { it.n }.toSet() shouldBe setOf(i + 1)
                }
            }
            obs.chunked(2).map { it[0].ts.counter } shouldBe (1L..waves).toList()
        }
    }

    @Test
    fun `control - the unprotected bridged diamond glitches on at least one seed`() {
        var glitched = 0
        for (seed in 0L until 50L) {
            val obs = runDiamond(seed, waves = 20, protected = false)
            val mixed = obs.chunked(2).any { wave ->
                wave.size < 2 || wave.map { it.ts }.toSet().size != 1 ||
                    wave.map { it.label }.toSet() != setOf("B", "C")
            }
            if (mixed) glitched++
        }
        (glitched > 0).shouldBeTrue()
    }

    /**
     * Runs the absorbing-arm variant: C is an [AbsorbGate] swallowing odd waves.
     * With [progressCapable] the bridged edge negotiates the `progress`
     * capability so absorb-acks cross; without it they are dropped at the
     * frame boundary (the control). Returns the observer log.
     */
    private fun runAbsorbing(seed: Long, waves: Int, progressCapable: Boolean): List<Obs> {
        val net = Net(seed)
        val obs = mutableListOf<Obs>()

        val a = SourceCell()
        val c = AbsorbGate()
        val b = LabelMapper("B")
        val observer = ObserverCell(obs)
        val gf = GlitchFreeCell(propagateString) // WAIT mode

        net.hostNear.managementInlet.call.spawn(a)
        net.hostC.managementInlet.call.spawn(c)
        net.hostFar.managementInlet.call.spawn(b)
        net.hostFar.managementInlet.call.spawn(observer)
        net.hostFar.managementInlet.call.spawn(gf)
        net.controller.runToIdle()

        a.outlet.subscribe(Use.fixed(net.nearIntProxy(c.ref).inlet.call, PortRef.generate()))
        a.outlet.subscribe(Use.fixed(net.intProxyFor(b.ref).inlet.call, PortRef.generate()))

        b.outlet.linkTo(gf.inlet as LinkFrom<Propagate<String>>)
        c.outlet.subscribe(Use.fixed(net.proxyFor(gf.ref).inlet.call, PortRef.generate()))
        val caps = if (progressCapable) defaultProtocolCapabilities()
        else defaultProtocolCapabilities() - Protocols.Progress
        c.outlet.bridgeTo(
            selfAddr = PortAddress(c.ref, "outlet"),
            toAddr = PortAddress(gf.ref, "inlet"),
            sink = InvocationSink(net.egressNF::deliver),
            capabilities = caps,
        )
        gf.inlet.bridgeFrom(
            selfAddr = PortAddress(gf.ref, "inlet"),
            fromAddr = PortAddress(c.ref, "outlet"),
            sink = InvocationSink(net.egressFN::deliver),
        )
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        net.controller.runToIdle()

        val rnd = Random(seed xor 0x5eed)
        for (n in 1..waves) {
            a.emit(n)
            repeat(rnd.nextInt(4)) { net.controller.step() }
        }
        net.controller.runToIdle()
        return obs
    }

    @Test
    fun `a silently-absorbed remote wave settles via a bridged Progress ack`() {
        // waves = 9: the final (odd) wave is absorbed with no later delta, so it
        // can only settle if the Progress ack crossed the bridge.
        for (seed in 0L until 30L) {
            val obs = runAbsorbing(seed, waves = 9, progressCapable = true)
            val bWaves = obs.filter { it.label == "B" }.map { it.n }.toSet()
            withClue(seed, obs) {
                bWaves shouldBe (1..9).toSet() // every wave completes, including the absorbed final one
                obs.filter { it.label == "C" }.map { it.n }.toSet() shouldBe setOf(2, 4, 6, 8)
            }
        }
    }

    @Test
    fun `control - without a bridged Progress ack the remote frontier stalls forever`() {
        // Same pipeline, but the bridged edge lacks the progress capability: the
        // final absorbed wave never settles — WAIT holds it forever.
        val obs = runAbsorbing(seed = 0, waves = 9, progressCapable = false)
        val bWaves = obs.filter { it.label == "B" }.map { it.n }.toSet()
        bWaves shouldNotContain 9 // wave 9's B contribution is held at the join
        bWaves shouldBe (1..8).toSet() // earlier absorbed waves still settle via later even deltas
    }

    @Test
    fun `a denied peer's bridged link is rejected, an allowed peer's is admitted`() {
        val net = Net(seed = 1)
        val gf = GlitchFreeCell(propagateString)
        net.hostFar.managementInlet.call.spawn(gf)
        net.controller.runToIdle()
        gf.inlet.linking.policies += allowPeers(PeerId("good"))

        // a bridged link request from an unlisted peer is refused at the handshake
        val refused = CurrentPeer.with(PeerId("evil")) {
            gf.inlet.bridgeFrom(
                selfAddr = PortAddress(gf.ref, "inlet"),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "outlet"),
                sink = InvocationSink(net.egressFN::deliver),
            )
        }
        (refused is LinkResult.Rejected).shouldBeTrue()
        gf.inlet.linking.links.shouldBeEmpty() // nothing registered on a refused bridged edge

        // an allowed peer is admitted and registered
        val admitted = CurrentPeer.with(PeerId("good")) {
            gf.inlet.bridgeFrom(
                selfAddr = PortAddress(gf.ref, "inlet"),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "outlet"),
                sink = InvocationSink(net.egressFN::deliver),
            )
        }
        (admitted is LinkResult.Connected).shouldBeTrue()
        gf.inlet.linking.links.shouldContain((admitted as LinkResult.Connected).link)
    }

    private inline fun <T> withClue(vararg clue: Any?, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("clue=${clue.toList()} :: ${e.message}", e)
        }
}
