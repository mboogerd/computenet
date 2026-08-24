package civictech.cell.wire

import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.AuthLevel
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.link.allowPeers
import civictech.cell.port.input
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M8.2–M8.4 (G-29 phase 1, spec 43): identity rides deliveries into link
 * requests, and the bridge boundary refuses unlisted peers. Since
 * computenet-usd.4.1 (spec 40/43 seam 1, `[SEC1-06]`/`[SEC1-07]`) that
 * refusal is a typed `ADMISSION` denial through `BridgeIngressCell`'s own
 * [civictech.cell.BoundaryDenials] sink, never a thrown fault: nothing
 * crosses, the ingress's denial counter moves, and — the not-a-fault
 * property (BS-14) — a `SupervisionPolicy.RESTART` on the ingress never
 * fires, over 100 seeds, with an open-mode control proving the harness would
 * have linked.
 */
class TrustBoundaryTest {

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()

        @Suppress("unused")
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    interface CollectorProxy {
        val inlet: Use<Consumer<String>>
    }

    private class Run(seed: Long, allowlisted: Boolean) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)

        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)

        val deadLettersP = mutableListOf<DeadLetter>()
        val collector = CollectingCell()
        val loopback: Peering.Loopback

        init {
            val p = Peering.Side(
                registryP, bridgeP, peer = PeerId("p"),
                allow = if (allowlisted) setOf(PeerId("good")) else null,
            )
            val q = Peering.Side(registryQ, bridgeQ, peer = PeerId("evil"))
            // ingressOnA lives on bridgeP (p's bridge host) and receives q's
            // ("evil") traffic through p's allowlist — the ingress this test's
            // refusal assertions read from (computenet-usd.4.1).
            loopback = Peering.loopback(p, q)

            listOf(hostP, bridgeP).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLettersP += value
                    }
                }, PortRef.generate()))
            }
            hostP.managementInlet.call.spawn(collector)
            controller.runToIdle()
        }

        fun sendFromQ(count: Int) {
            val proxy = (HostedCellProxy.create(collector.ref, registryQ, CollectorProxy::class.java)
                    as CollectorProxy).inlet.call
            repeat(count) { i ->
                proxy.provide("q-$i")
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
        }
    }

    @Test
    fun `an unlisted peer's traffic is refused at the boundary on every seed`() {
        for (seed in 0L until 100L) {
            val run = Run(seed, allowlisted = true)
            val ingress = run.loopback.ingressOnA!!
            // BS-14, [SEC1-07]: a denial is not a fault — supervise the
            // ingress under RESTART and prove it never fires.
            run.bridgeP.managementInlet.call.supervise(ingress.ref, SupervisionPolicy.RESTART)

            // Baselines, taken *after* the peering is established (Run's init
            // already ran to idle): opening a loopback announces each side's
            // own bridge cells (the ingress's ref and its registry mirror's
            // ref) to the other, and P's allowlist refuses Q's two — genuine
            // ADMISSION denials, just not ones `sendFromQ` causes. Measuring
            // the *increment* isolates what this test is actually about.
            val sink = ingress.boundaryDenials["bridge-ingress"]!!
            val denialCountBefore = sink.denialCount
            val lettersBefore = run.deadLettersP.size

            run.sendFromQ(5)

            run.collector.received.shouldBeEmpty() // nothing crossed
            run.deadLettersP.size shouldBeGreaterThan lettersBefore // and the refusal is visible

            // [SEC1-06][SEC1-07]: a typed ADMISSION denial per refused send,
            // counted on the ingress's own sink — not a bare thrown check().
            (sink.denialCount - denialCountBefore) shouldBe 5L

            val denialLetters = run.deadLettersP.drop(lettersBefore)
                .filter { it.description.contains("seam=ADMISSION") }
            denialLetters.size shouldBe 5
            denialLetters.forEach { letter ->
                letter.cause shouldBe null // not a fault
                letter.description shouldContain "evil"
                letter.description shouldContain "NOT_ADMITTED"
            }

            // BS-14: nothing thrown, so supervision never sees a failure —
            // including from the pre-existing setup refusals above.
            run.bridgeP.supervisionAccounting().restarts shouldBe 0L
        }
    }

    @Test
    fun `control - open mode delivers the same traffic`() {
        val run = Run(seed = 0, allowlisted = false)
        run.sendFromQ(5)
        run.collector.received shouldBe (0 until 5).map { "q-$it" }
        run.deadLettersP.shouldBeEmpty()
    }

    /**
     * Records the ambient [Principal] of every `Attention` assertion it is
     * handed — the same probe [LoopbackPrincipalTest] uses to observe what
     * [Peering.loopbackAuthLevel] decided. Reused here (rather than edited
     * there) to keep BS-03 self-contained in this file.
     */
    private class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()

        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    /** A bare `PORT_PROTOCOL` `Attention` frame addressed to [target] — the one invocation type that carries the ambient peer stamp all the way to [currentPrincipal] (see [LoopbackPrincipalTest]'s KDoc). */
    private fun protocolFrame(target: CellRef): HostedPortInvocation = HostedPortInvocation(
        cellRef = target,
        portName = "outlet",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "outlet"),
        ),
        protocolMessage = Attention(1f),
    )

    /**
     * BS-03 ([DSC1-HELLO-10], [DSC1-ANN-11], [DSC1-WIRE-06]): a
     * [Peering.Side] built with no identity configuration at all — no
     * [Peering.Side.allow], no [PeerCredentials], nothing beyond the
     * [PeerAuthPolicy.Open] default — is the exact construction every
     * existing demo makes. Connecting, announcing and exchanging data over
     * such a loopback behaves indistinguishably from before this epic: a
     * genuine crossing's principal is [Principal.Peer] at
     * [AuthLevel.TransportVouched] and never [AuthLevel.Authenticated], the
     * registry converges (Q resolves P's collector remotely), and the data
     * exchange itself raises zero new dead letters.
     *
     * Non-vacuousness (test-only route, no production mutation): locally
     * giving **both** `p` and `q` [PeerCredentials] whose `peerId` matches
     * their own [Peering.Side.peer] and re-running the principal assertion
     * turns the observed principal into `Peer(PeerId("q"), Authenticated)`.
     * Both sides are load-bearing: [Peering.loopbackAuthLevel] short-circuits
     * to [AuthLevel.TransportVouched] the moment either the sender's or the
     * *receiver's* credentials are absent, so crediting `q` alone leaves this
     * assertion green and proves nothing. See the task's final report for the
     * exact assertion watched failing.
     */
    @Test
    fun `BS-03 - a default-open loopback stays TransportVouched, converges, and adds no dead letters from the exchange`() {
        val controller = SimulationController(0)
        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)

        // No allow, no credentials, no auth policy, no signing, no
        // verification: the construction every existing demo makes.
        val p = Peering.Side(registryP, bridgeP, peer = PeerId("p"))
        val q = Peering.Side(registryQ, bridgeQ, peer = PeerId("q"))

        val deadLettersP = mutableListOf<DeadLetter>()
        listOf(hostP, bridgeP).forEach { h ->
            h.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLettersP += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        val loopback = Peering.loopback(p, q)
        val collector = CollectingCell()
        hostP.managementInlet.call.spawn(collector)
        val probe = PrincipalProbeCell()
        bridgeP.managementInlet.call.spawn(probe)
        controller.runToIdle()

        // connect + announce converged: Q resolves P's collector remotely,
        // exactly as every pre-epic peering did.
        (registryQ.location(collector.ref) is LocationRegistry.Remote).shouldBeTrue()

        val lettersBefore = deadLettersP.size

        // a genuine crossing observes Peer(q, TransportVouched), never Authenticated
        loopback.bToA.deliver(protocolFrame(probe.ref))
        controller.runToIdle()
        probe.principals.lastOrNull() shouldBe Principal.Peer(PeerId("q"), AuthLevel.TransportVouched)

        // ordinary data still crosses, with nothing refused at the boundary
        val proxy = (HostedCellProxy.create(collector.ref, registryQ, CollectorProxy::class.java)
                as CollectorProxy).inlet.call
        repeat(5) { i -> proxy.provide("q-$i") }
        controller.runToIdle()

        collector.received shouldBe (0 until 5).map { "q-$it" }
        deadLettersP.size shouldBe lettersBefore // zero new dead letters from the exchange
    }

    /**
     * computenet-wb6s: a peering whose remote side announces itself as
     * [remotePeer], with a [CollectingCell] hosted on the local side — the rig
     * both real-frame link-request tests below drive.
     */
    private class LinkRig(remotePeer: String) {
        val controller = SimulationController(0)
        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val deadLettersP = mutableListOf<DeadLetter>()
        val collector = CollectingCell()

        /**
         * The remote side's own producer — the address its request names.
         * computenet-a4ha: it has to be a cell the requesting peer really
         * hosts, because P now refuses a request naming an address that does
         * not resolve to a location that peer announced.
         */
        val producerOnQ = SourceCell()
        val loopback: Peering.Loopback

        init {
            loopback = Peering.loopback(
                Peering.Side(registryP, bridgeP, peer = PeerId("p")),
                Peering.Side(registryQ, bridgeQ, peer = PeerId(remotePeer)),
            )
            listOf(hostP, bridgeP).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLettersP += value
                    }
                }, PortRef.generate()))
            }
            hostP.managementInlet.call.spawn(collector)
            hostQ.managementInlet.call.spawn(producerOnQ)
            controller.runToIdle()
        }

        /**
         * The remote side asks P's collector inlet to accept a link from its own
         * producer — as a **real frame**: encoded by the peering's Q→P
         * [BridgeEgressCell], decoded and peer-stamped by P's
         * [BridgeIngressCell]. Nothing here supplies an identity; the one the
         * handshake sees is the one that ingress applied.
         */
        fun requestLink() {
            RemoteLinkRequests.requestLinkFrom(
                sink = loopback.bToA,
                target = PortAddress(collector.ref, "inlet"),
                producer = PortAddress(producerOnQ.ref, "outlet"),
                api = Consumer::class.java,
            )
            controller.runToIdle()
        }
    }

    /**
     * computenet-wb6s: the link-request half of the identity seam, verified
     * through a real wire frame rather than up to an injection point.
     *
     * Before this bead, the invocation below could not be encoded at all —
     * `IllegalStateException: not wire-capable: 'linkFrom' was not captured from
     * a @Contract interface` — so every cross-boundary link test in the
     * repository handed the already-decoded invocation to the target side's
     * registry instead.
     */
    @Test
    fun `a link request crosses a real wire frame`() {
        val rig = LinkRig(remotePeer = "q")
        rig.requestLink()
        rig.collector.inlet.linking.links.size shouldBe 1
    }

    @Test
    fun `link requests carry the delivering peer's identity into policies`() {
        val refusedRig = LinkRig(remotePeer = "evil")
        refusedRig.collector.inlet.linking.policies += allowPeers(PeerId("good"))
        refusedRig.requestLink()
        refusedRig.collector.inlet.linking.links.shouldBeEmpty()
        refusedRig.deadLettersP.any { it.description.contains("allowlist") }.shouldBeTrue()

        val admittedRig = LinkRig(remotePeer = "good")
        admittedRig.collector.inlet.linking.policies += allowPeers(PeerId("good"))
        admittedRig.requestLink()
        admittedRig.collector.inlet.linking.links.size shouldBe 1
    }

    /**
     * computenet-a4ha: a plain source port on P. Its outlet is what a
     * [RemoteLink] request targets, and what emits once a link is established —
     * so where its emissions land is the observable that separates a link
     * established for the requesting peer from one redirected elsewhere.
     */
    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
    }

    /**
     * computenet-a4ha's rig: **three** peers, because the defect it pins is a
     * confused deputy across the trust boundary and two peers cannot express it.
     *
     * P peers with q and, separately, with r. P hosts the [source] whose outlet
     * a link request targets, and a [victimOnP] of its own; q and r each host a
     * consumer. Every request below is sent by **q**, as a real wire frame over
     * the q→P peering: nothing supplies a [PeerId] to the invocation, so the
     * only identity in play is the one P's [BridgeIngressCell] stamped.
     */
    private class RedirectRig {
        val controller = SimulationController(0)
        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val registryR = LocationRegistry()
        val hostR = ManagedHost(scheduler = controller.scheduler(), registry = registryR)
        val bridgeR = ManagedHost(scheduler = controller.scheduler(), registry = registryR)

        val deadLettersP = mutableListOf<DeadLetter>()

        /** P's own producer — the link target. */
        val source = SourceCell()

        /** P's own cell: the "consumer on the receiver itself" arm's address. */
        val victimOnP = CollectingCell()

        /** q's cell: the legitimate arm's address. */
        val consumerOnQ = CollectingCell()

        /** r's cell: the third-peer arm's address — q holds nothing on it. */
        val consumerOnR = CollectingCell()

        val pq: Peering.Loopback
        val pr: Peering.Loopback

        init {
            val p = Peering.Side(registryP, bridgeP, peer = PEER_P)
            val q = Peering.Side(registryQ, bridgeQ, peer = REQUESTER_Q)
            val r = Peering.Side(registryR, bridgeR, peer = THIRD_R)
            listOf(hostP, bridgeP).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLettersP += value
                    }
                }, PortRef.generate()))
            }
            hostP.managementInlet.call.spawn(source)
            hostP.managementInlet.call.spawn(victimOnP)
            hostQ.managementInlet.call.spawn(consumerOnQ)
            hostR.managementInlet.call.spawn(consumerOnR)
            pq = Peering.loopback(p, q)
            pr = Peering.loopback(p, r)
            controller.runToIdle()
        }

        /** P's ingress for q's frames — where a refusal of q's request is accounted. */
        val ingressFromQ: BridgeIngressCell get() = pq.ingressOnA!!

        /**
         * q asks P's [source] outlet to link to [consumer], as a real frame:
         * encoded by the peering's q→P [BridgeEgressCell], decoded and
         * peer-stamped by P's [BridgeIngressCell]. No [PeerId] is supplied here.
         */
        fun requestFromQ(consumer: PortAddress) {
            RemoteLinkRequests.requestLinkTo(
                sink = pq.bToA,
                target = PortAddress(source.ref, "outlet"),
                consumer = consumer,
                api = Consumer::class.java,
            )
            controller.runToIdle()
        }

        fun emit(value: String) {
            source.outlet.call.provide(value)
            controller.runToIdle()
        }

        companion object {
            val PEER_P = PeerId("p")
            val REQUESTER_Q = PeerId("requester-q")
            val THIRD_R = PeerId("third-party-r")
        }
    }

    /**
     * The refusal accounting all three arms below share: one typed denial on the
     * ingress's own `"link-request"` sink, naming the refused [PeerId], with a
     * null `cause` — and no supervision RESTART, which is the BS-14 not-a-fault
     * property the announcement-admission gate already holds to.
     */
    private fun RedirectRig.assertRefused(sinkCountBefore: Long, lettersBefore: Int) {
        val sink = ingressFromQ.boundaryDenials["link-request"]!!
        (sink.denialCount - sinkCountBefore) shouldBe 1L

        val letters = deadLettersP.drop(lettersBefore)
            .filter { it.denial?.reason == DenialReason.LINK_REFUSED }
        letters.size shouldBe 1
        val letter = letters.single()
        val denial = letter.denial!!
        denial.seam shouldBe BoundarySeam.ADMISSION
        denial.principal shouldBe RedirectRig.REQUESTER_Q // the refused PeerId is named
        letter.cause shouldBe null // not a fault
        letter.description shouldContain "requester-q"

        bridgeP.supervisionAccounting().restarts shouldBe 0L
    }

    /**
     * computenet-a4ha arm 1: q names one of **P's own** cells as the consumer.
     * Measured against the unfixed code as `PROBE victim.received =
     * [p-internal-secret]` — the link established and P streamed its own
     * emission into its own cell at a peer's request.
     */
    @Test
    fun `computenet-a4ha - a link request naming a cell on the receiving side is refused`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.ingressFromQ.ref, SupervisionPolicy.RESTART)
        val before = rig.ingressFromQ.boundaryDenials["link-request"]?.denialCount ?: 0L
        val lettersBefore = rig.deadLettersP.size

        rig.requestFromQ(PortAddress(rig.victimOnP.ref, "inlet"))
        rig.emit("p-internal-secret")

        rig.victimOnP.received.shouldBeEmpty() // the reviewer's PROBE victim.received
        rig.source.outlet.linking.links.shouldBeEmpty() // and no link was established at all

        rig.assertRefused(before, lettersBefore)
    }

    /**
     * computenet-a4ha arm 2, the confused deputy: q names a consumer belonging
     * to a **third** peer r. Measured against the unfixed code as
     * `PROBE third-peer received = [[p-internal-secret]]` — the authorisation
     * was taken against `Principal = Peer(q)` and the data landed at r.
     */
    @Test
    fun `computenet-a4ha - a link request naming a third peer's cell is refused`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.ingressFromQ.ref, SupervisionPolicy.RESTART)
        // precondition: P really does resolve r's cell, so the arm is about the
        // binding and not about an unresolvable address.
        (rig.registryP.location(rig.consumerOnR.ref) is LocationRegistry.Remote).shouldBeTrue()
        val before = rig.ingressFromQ.boundaryDenials["link-request"]?.denialCount ?: 0L
        val lettersBefore = rig.deadLettersP.size

        rig.requestFromQ(PortAddress(rig.consumerOnR.ref, "inlet"))
        rig.emit("p-internal-secret")

        rig.consumerOnR.received.shouldBeEmpty() // the reviewer's PROBE third-peer received
        rig.source.outlet.linking.links.shouldBeEmpty()

        rig.assertRefused(before, lettersBefore)
    }

    /**
     * computenet-a4ha arm 3, and the one that makes this a fix rather than a
     * mute: the legitimate request — q naming q's own consumer — still links
     * over a real wire frame, and P's emission reaches it.
     */
    @Test
    fun `computenet-a4ha - a link request naming the requesting peer's own cell still links`() {
        val rig = RedirectRig()
        val before = rig.ingressFromQ.boundaryDenials["link-request"]?.denialCount ?: 0L
        val lettersBefore = rig.deadLettersP.size

        rig.requestFromQ(PortAddress(rig.consumerOnQ.ref, "inlet"))

        rig.source.outlet.linking.links.size shouldBe 1
        rig.emit("p-internal-secret")
        rig.consumerOnQ.received shouldBe listOf("p-internal-secret")

        (rig.ingressFromQ.boundaryDenials["link-request"]?.denialCount ?: 0L) shouldBe before
        rig.deadLettersP.drop(lettersBefore).mapNotNull { it.denial }.shouldBeEmpty()
    }
}
