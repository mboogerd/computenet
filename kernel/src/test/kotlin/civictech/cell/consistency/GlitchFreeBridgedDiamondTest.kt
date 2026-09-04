package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.Propagate
import civictech.cell.onEach
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.port.Use
import civictech.cell.link.KeyId
import civictech.cell.link.allowPeers
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.BridgeIngressCell
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.cell.wire.defaultProtocolCapabilities
import civictech.testkit.dst.FrameInterposer
import civictech.testkit.dst.FrameInterposers
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

    /**
     * Which Near→Far frames the seeded duplicator is allowed to copy.
     *
     * [PROTOCOL_ONLY] is the shipped wiring and the only value any pre-existing test uses, so
     * the default leaves this file's behaviour — and its RNG draw sequence — byte-identical to
     * what it was before this knob existed. The other two exist for the BS-16 arm below, which
     * needs to *neutralise* ([NONE]) and *ungate* ([ALL_FRAMES]) the injector in-build rather
     * than by a hand mutation a reviewer has to re-apply.
     */
    enum class DuplicationScope { PROTOCOL_ONLY, ALL_FRAMES, NONE }

    /** Two processes and a full-duplex bridge; the Near→Far leg duplicates protocol frames on the seed. */
    private class Net(seed: Long, duplication: DuplicationScope = DuplicationScope.PROTOCOL_ONLY) {
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
            // Duplicate protocol frames with probability 0.5, seeded from this net's own `rnd`:
            // idempotency of the frontier plane under redelivery. Uses the DST rig's own
            // DuplicateFault primitive, FrameInterposers.duplicating (computenet-umx.3.3), gated
            // to PORT_PROTOCOL frames only — a plain data frame duplicated here would not be
            // testing what this test is about. `step` is unused by [duplicateProtocolFrame]'s
            // window (StepWindow.ALWAYS, the default), so 0 is passed for every call.
            //
            // The PORT_PROTOCOL gate is hand-built here rather than taken from `DuplicateFault`
            // because the rig's frame plane selects a fault's target by EDGE NAME, not by frame
            // content, and this graph is wired by hand with no named `DstGraph` edges. That is a
            // reach limit of the rig, not a preference — reported to computenet-umx.3.
            //
            // Two caveats on what this injector proves, both measured under the
            // computenet-umx.3.9 review:
            //  - This is a STRESSOR, not a discriminator. Neutralising it — delivering only the
            //    original and never a copy — leaves all five tests in this file green, so no
            //    per-seed verdict here is evidence that duplication occurred. What proves the
            //    primitive fires is the opposite mutation: removing the PORT_PROTOCOL gate so
            //    data frames duplicate too turns `bridged diamond is glitch-free for every seed`
            //    red (observation count 49 against an expected 40). The discriminating control
            //    for duplication itself is BS-6 in `DuplicateFaultTest` (computenet-umx.3.3).
            //  - The seed DERIVATION is preserved (same `rnd`, same `Random(seed)`), but the
            //    per-frame draw sequence is not the pre-retrofit one: `duplicating` consults
            //    `nextDouble()` where the hand-rolled duplicator consulted `nextBoolean()`, which
            //    consumes the LCG differently. On seed 0 the two disagree on 21 of the first 40
            //    frame decisions. Each seed is therefore still a reproducible sample of the same
            //    Bernoulli(0.5) fault distribution, but it is a DIFFERENT sample than before the
            //    retrofit — do not read a per-seed outcome here as a like-for-like comparison
            //    against a pre-retrofit run.
            val duplicateProtocolFrame: FrameInterposer = FrameInterposers.duplicating(
                copies = 1,
                probability = 0.5,
                rng = rnd,
            )
            val protocolDuplicator = FrameInterposer { frame, step ->
                val duplicable = when (duplication) {
                    DuplicationScope.NONE -> false
                    DuplicationScope.ALL_FRAMES -> true
                    DuplicationScope.PROTOCOL_ONLY ->
                        WireCodec.decode(frame).type == civictech.cell.proxy.HostedPortInvocation.Type.PORT_PROTOCOL
                }
                if (duplicable) duplicateProtocolFrame.apply(frame, step) else listOf(frame)
            }
            egressNF.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) {
                    protocolDuplicator.apply(value, 0).forEach(ingressNFApi::propagate)
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
    private fun runDiamond(
        seed: Long,
        waves: Int,
        protected: Boolean,
        duplication: DuplicationScope = DuplicationScope.PROTOCOL_ONLY,
    ): List<Obs> {
        val net = Net(seed, duplication)
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

    /**
     * True iff [seed] satisfies, on its own, every assertion
     * `bridged diamond is glitch-free for every seed` makes — i.e. this seed's entry in that
     * test's per-seed outcome vector, which is what [CHA1-61] is about. A throw counts as a
     * failing seed, since that is how the outcome would present in the suite.
     */
    private fun diamondOutcome(seed: Long, waves: Int, duplication: DuplicationScope): Boolean =
        try {
            val obs = runDiamond(seed, waves, protected = true, duplication = duplication)
            obs.size == waves * 2 &&
                obs.chunked(2).withIndex().all { (i, wave) ->
                    wave.map { it.ts }.toSet().size == 1 &&
                        wave.map { it.label }.toSet() == setOf("B", "C") &&
                        wave.map { it.n }.toSet() == setOf(i + 1)
                } &&
                obs.chunked(2).map { it[0].ts.counter } == (1L..waves).toList()
        } catch (t: Throwable) {
            false
        }

    /**
     * **BS-16 — "the retrofit is behaviour-preserving" ([CHA1-61]), for this file, stated so it
     * cannot pass on a dead injector.**
     *
     * computenet-umx.3.9 replaced this file's hand-rolled protocol-frame duplicator with the
     * rig's own [FrameInterposers.duplicating]. [CHA1-61] asks that the per-seed pass/fail
     * outcome be unchanged by that swap. It is — and it is unchanged for a reason that makes the
     * bare parity assertion worthless, which is why this test has three arms rather than one:
     *
     *  - **Arm 1, the criterion.** The pinned vector is a literal recorded from the PRE-retrofit
     *    code at `67399fc23^` — where `bridged diamond is glitch-free for every seed` asserted,
     *    and passed, on every seed of `0 until 100`. So the pre-retrofit vector is all-true by
     *    record, not by recomputation from the retrofitted path. The retrofitted path must
     *    reproduce it.
     *  - **Arm 2, the vacuity, recorded as a fact rather than a comment.** Neutralising the
     *    duplicator entirely leaves that vector *identical*. Measured under the umx.3.9 review
     *    (`.take(1)` on the delivery, five of five tests green) and re-measured here at
     *    computenet-xpj5 before this test existed (`:kernel:test --tests
     *    GlitchFreeBridgedDiamondTest --rerun`: 5 tests, 0 failures). Arm 1 is therefore blind
     *    to the bug BS-16 exists to catch, and asserting the invariance out loud is what stops a
     *    later reader from mistaking arm 1 for coverage of the injector.
     *  - **Arm 3, the non-vacuity.** What *does* discriminate is the opposite mutation the
     *    file's own KDoc names: ungate the duplicator so data frames copy too, and the
     *    glitch-free invariant breaks on at least one seed. That can only happen if
     *    [FrameInterposers.duplicating] is actually firing on this edge, so arm 3 fails against
     *    a neutralised injector where arm 1 cannot.
     *
     * **What this test does NOT claim.** The per-*frame* duplication decisions are NOT
     * pre-retrofit ones — `duplicating` draws `nextDouble()` where the hand-rolled duplicator
     * drew `nextBoolean()`, so the two disagree on 21 of the first 40 frames on seed 0 (measured
     * in umx.3.9, and recorded in [Net]'s KDoc above). Parity here is coarse — the per-seed
     * outcome and nothing finer — and that limit is a property of the retrofit, not of this
     * test.
     */
    @Test
    fun `BS-16 CHA1-61 - the per-seed outcome vector survives the retrofit, is blind to a dead duplicator, and ungating it diverges`() {
        val waves = 20
        val seeds = 0L until 30L // a prefix of the existing 0..99 range; 30 seeds x 3 arms

        // Arm 1 — the criterion. Pinned from the pre-retrofit run at 67399fc23^: every seed passed.
        val preRetrofitOutcomes = seeds.map { true }
        seeds.map { diamondOutcome(it, waves, DuplicationScope.PROTOCOL_ONLY) } shouldBe preRetrofitOutcomes

        // Arm 2 — the measured vacuity: arm 1 is invariant under a fully neutralised injector.
        seeds.map { diamondOutcome(it, waves, DuplicationScope.NONE) } shouldBe preRetrofitOutcomes

        // Arm 3 — non-vacuity: ungated, the rig's duplicator is observable, so it is alive.
        val ungated = seeds.map { diamondOutcome(it, waves, DuplicationScope.ALL_FRAMES) }
        withClue("ungating the duplicator must break the invariant on some seed", ungated) {
            ungated.any { !it }.shouldBeTrue()
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
        gf.inlet.linking.policies += allowPeers(KeyId("good"))

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
