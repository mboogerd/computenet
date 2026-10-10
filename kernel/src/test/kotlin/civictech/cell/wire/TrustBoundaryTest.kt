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
import civictech.cell.host.IntakeClosedException
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
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

    private class Run(seed: Long, allowlisted: Boolean, qName: String = "evil") {
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
            val q = Peering.Side(registryQ, bridgeQ, peer = PeerId(qName))
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

    /**
     * The **positive** half of the same gate, and the one that pins what
     * `Peering.hostIngress` hands its `BridgeIngressCell`'s allowlist gate: a
     * peer whose identity IS on the allowlist crosses it, on the very
     * configuration the test above refuses. Allowlists name identities (epic
     * `computenet-5y8t`); the gate judges the stamped `fromPeer`.
     *
     * Without it the allowlist half of this file is satisfied by refusing
     * *everything*. Measured 2026-09-02 against the pre-`computenet-5y8t`
     * key-judged gate by mutating `peerKey = fromKey` to `peerKey = null` in
     * `Peering.hostIngress` and running `:kernel:test`, `:wire:test` and
     * `:iroh:test -Piroh.enabled=true` in full: 1416 tests, 0 failures. That
     * mutation no longer changes any verdict — the key is judged by nothing.
     * The equivalent refuse-everything mutation on the identity-judged gate is
     * `admit = { side.admits(null) }` in `hostIngress`; measured 2026-09-13 on
     * this class alone, it fails this test and no other in the class. The
     * `control - open mode` test below cannot catch it, because its side
     * carries no allowlist at all (`allow == null` short-circuits).
     *
     * What this test does **not** discriminate: a gate that judged a
     * key-derived fallback (`admit = { side.admits(fromKey?.let { PeerId(it.name) }) }`)
     * instead of the stamped identity passes it (also measured 2026-09-13),
     * because `q` holds no credentials and so presents `KeyId(q.peer.name)` —
     * the transport-vouched assertion arm, the same string as its identity.
     * Key and identity only come apart under a non-interim binding, which is
     * outside this file.
     */
    @Test
    fun `an allowlisted peer's traffic crosses the same boundary that refuses an unlisted one`() {
        val run = Run(seed = 0, allowlisted = true, qName = "good")
        val ingress = run.loopback.ingressOnA!!
        val sink = ingress.boundaryDenials["bridge-ingress"]!!
        val denialCountBefore = sink.denialCount
        val lettersBefore = run.deadLettersP.size

        run.sendFromQ(5)

        run.collector.received shouldBe (0 until 5).map { "q-$it" }
        (sink.denialCount - denialCountBefore) shouldBe 0L
        run.deadLettersP.size shouldBe lettersBefore
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
    private class RedirectRig(
        private val requesterPeer: PeerId? = REQUESTER_Q,
        private val thirdPeer: PeerId? = THIRD_R,
        private val ownership: AnonymousOwnership = AnonymousOwnership.Shared,
    ) {
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
        private val p = Peering.Side(
            registryP,
            bridgeP,
            peer = PEER_P,
            anonymousOwnership = ownership,
        )
        private val q = Peering.Side(registryQ, bridgeQ, peer = requesterPeer)
        private val r = Peering.Side(registryR, bridgeR, peer = thirdPeer)

        init {
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

        /** P's ingress for r's frames — the link-request half of the local-shadowing arm. */
        val ingressFromR: BridgeIngressCell get() = pr.ingressOnA!!

        /** P's per-connection mirror that applies r's announcements. */
        val mirrorFromR: CellRef get() = pr.mirrorRefOnA

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

        /** The same real-frame request as [requestFromQ], sent by r. */
        fun requestFromR(consumer: PortAddress) {
            RemoteLinkRequests.requestLinkTo(
                sink = pr.bToA,
                target = PortAddress(source.ref, "outlet"),
                consumer = consumer,
                api = Consumer::class.java,
            )
            controller.runToIdle()
        }

        /** Host a cell on r under an already-used ref, causing r to announce that ref to P. */
        fun announceImpostorFromR(ref: CellRef): CollectingCell = CollectingCell(ref).also {
            hostR.managementInlet.call.spawn(it)
            controller.runToIdle()
        }

        /** Have r retract q's ref directly, without first publishing an impostor. */
        fun retractFromR(ref: CellRef) {
            val announce = (HostedCellProxy.create(
                mirrorFromR,
                pr.bToA,
                Peering.AnnounceInletProxy::class.java,
            ) as Peering.AnnounceInletProxy).inlet.call
            announce.unpublished(ref)
            controller.runToIdle()
        }

        /** Have q — the owning connection — retract its own ref over [pq]. */
        fun retractFromQ(ref: CellRef) {
            val announce = (HostedCellProxy.create(
                pq.mirrorRefOnA,
                pq.bToA,
                Peering.AnnounceInletProxy::class.java,
            ) as Peering.AnnounceInletProxy).inlet.call
            announce.unpublished(ref)
            controller.runToIdle()
        }

        /** Open q's replacement while [pq] is still live; the caller advances the scheduler. */
        fun overlappingQ(): Peering.Loopback = Peering.loopback(p, q)

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
     * The announcement-collision refusal shared by computenet-zlm2's three
     * arms: one typed ADMISSION denial names r, and the mirror's RESTART policy
     * never fires because a boundary refusal is not a cell fault.
     */
    private fun RedirectRig.assertImpostorAnnouncementRefused(
        lettersBefore: Int,
        faultLettersBefore: Long,
        expectedPrincipal: PeerId? = RedirectRig.THIRD_R,
    ) {
        val letters = deadLettersP.drop(lettersBefore)
            .filter { it.denial?.exposure == "announcement-admission" }
        letters.size shouldBe 1
        val letter = letters.single()
        val denial = letter.denial!!
        denial.seam shouldBe BoundarySeam.ADMISSION
        denial.reason shouldBe DenialReason.NOT_ADMITTED
        denial.principal shouldBe expectedPrincipal
        letter.cause shouldBe null
        letter.description shouldContain (expectedPrincipal?.name ?: "<anonymous>")

        bridgeP.supervisionAccounting().deadLetters shouldBe faultLettersBefore
        bridgeP.supervisionAccounting().restarts shouldBe 0L
    }

    /**
     * computenet-zlm2 arm 1: q first establishes a legitimate RemoteLink to
     * its own consumer. A later announcement by r for the same full ref must
     * not re-aim that already-live link at r.
     */
    @Test
    fun `computenet-zlm2 - another peer cannot re-aim an established RemoteLink`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        rig.requestFromQ(PortAddress(rig.consumerOnQ.ref, "inlet"))
        rig.emit("first")
        rig.consumerOnQ.received shouldBe listOf("first")

        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters
        val impostorOnR = rig.announceImpostorFromR(rig.consumerOnQ.ref)

        (rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote).peer shouldBe
            RedirectRig.REQUESTER_Q
        rig.emit("q-only-secret")

        rig.consumerOnQ.received shouldBe listOf("first", "q-only-secret")
        impostorOnR.received.shouldBeEmpty()
        rig.assertImpostorAnnouncementRefused(lettersBefore, faultLettersBefore)
    }

    /**
     * computenet-4f55i, council option D: Open-mode anonymous announcements
     * deliberately share the null [PeerId] owner. P has two independent
     * loopback connections to anonymous peers q and r; r's announcement of
     * q's full ref is admitted and the already-linked proxy follows the new
     * sink. This pins non-protection rather than treating null equality as an
     * accidental implementation detail.
     */
    @Test
    fun `computenet-4f55i - anonymous peers may re-aim each other's announcements`() {
        val rig = RedirectRig(requesterPeer = null, thirdPeer = null)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        rig.consumerOnQ.received shouldBe listOf("first")

        val lettersBefore = rig.deadLettersP.size
        val consumerOnR = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        rig.emit("re-aimed")

        rig.consumerOnQ.received shouldBe listOf("first")
        consumerOnR.received shouldBe listOf("re-aimed")
        val location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        location.peer shouldBe null
        (location.sink === rig.pr.aToB).shouldBeTrue()
        rig.deadLettersP.drop(lettersBefore)
            .filter { it.denial?.exposure == "announcement-admission" }
            .shouldBeEmpty()
    }

    @Test
    fun `computenet-ddurc - PerConnection another anonymous connection cannot re-aim an announcement`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters

        val impostor = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        rig.emit("secret")

        rig.consumerOnQ.received shouldBe listOf("first", "secret")
        impostor.received.shouldBeEmpty()
        val location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        (location.sink === rig.pq.aToB).shouldBeTrue()
        rig.assertImpostorAnnouncementRefused(
            lettersBefore,
            faultLettersBefore,
            expectedPrincipal = null,
        )
        rig.deadLettersP.last().description shouldContain
            "the claim is deferred until the incumbent connection retires"
    }

    @Test
    fun `computenet-ddurc - PerConnection another anonymous connection cannot retract then re-claim`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters

        rig.retractFromR(rig.consumerOnQ.ref)
        val impostor = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        rig.emit("secret")

        rig.consumerOnQ.received shouldBe listOf("first", "secret")
        impostor.received.shouldBeEmpty()
        val location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        (location.sink === rig.pq.aToB).shouldBeTrue()
        val denials = rig.deadLettersP.drop(lettersBefore)
            .filter { it.denial?.exposure == "announcement-admission" }
        denials.map { it.denial!!.subject } shouldBe listOf(
            "RegistryAnnounce.unpublished",
            "RegistryAnnounce.published",
        )
        denials.forEach {
            it.denial!!.seam shouldBe BoundarySeam.ADMISSION
            it.denial!!.reason shouldBe DenialReason.NOT_ADMITTED
            it.denial!!.principal shouldBe null
            it.cause shouldBe null
        }
        denials.last().description shouldContain
            "the claim is deferred until the incumbent connection retires"
        rig.bridgeP.supervisionAccounting().deadLetters shouldBe faultLettersBefore
        rig.bridgeP.supervisionAccounting().restarts shouldBe 0L
    }

    @Test
    fun `computenet-ddurc - PerConnection an overlapping anonymous reconnect regains its refs when the old connection retires`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters

        val replacement = rig.overlappingQ()
        rig.bridgeP.managementInlet.call.supervise(
            replacement.mirrorRefOnA,
            SupervisionPolicy.RESTART,
        )
        rig.controller.runToIdle()

        val refDenials = rig.deadLettersP.drop(lettersBefore)
            .filter { letter ->
                letter.denial?.exposure == "announcement-admission" &&
                    letter.description.contains(rig.consumerOnQ.ref.toString())
            }
        refDenials.size shouldBe 1
        refDenials.single().denial!!.reason shouldBe DenialReason.NOT_ADMITTED
        refDenials.single().denial!!.principal shouldBe null
        refDenials.single().cause shouldBe null
        refDenials.single().description shouldContain
            "the claim is deferred until the incumbent connection retires"
        var location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        (location.sink === rig.pq.aToB).shouldBeTrue()
        rig.emit("during-overlap")
        rig.consumerOnQ.received shouldBe listOf("first", "during-overlap")

        rig.pq.partition()
        rig.controller.runToIdle()

        location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        location.peer shouldBe null
        (location.sink === replacement.aToB).shouldBeTrue()
        rig.emit("after")
        rig.consumerOnQ.received shouldBe listOf("first", "during-overlap", "after")
        rig.bridgeP.supervisionAccounting().deadLetters shouldBe faultLettersBefore
        rig.bridgeP.supervisionAccounting().restarts shouldBe 0L
    }

    /**
     * ddurc-D4 rule 4 (review addition): the owner freeing its ref on purpose
     * admits the deferred claimant as an ordinary publication — `onPublish`
     * fires once, with no further announcement from the claimant.
     */
    @Test
    fun `computenet-ddurc - PerConnection the owner's own retraction admits the deferred claimant`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        val claimant = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        (rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote).sink shouldBeSameInstanceAs
            rig.pq.aToB
        val published = mutableListOf<CellRef>()
        rig.registryP.onPublish { published += it }

        rig.retractFromQ(rig.consumerOnQ.ref)

        val location = rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote
        location.peer shouldBe null
        location.sink shouldBeSameInstanceAs rig.pr.aToB
        published.filter { it == rig.consumerOnQ.ref } shouldBe listOf(rig.consumerOnQ.ref)
        rig.emit("after")
        rig.consumerOnQ.received shouldBe listOf("first")
        claimant.received shouldBe listOf("after")
    }

    /**
     * ddurc-D4 rule 2 (review addition): a refused claimant that then retracts
     * the ref no longer wants it, so the incumbent's retirement installs nothing.
     */
    @Test
    fun `computenet-ddurc - PerConnection a refused claimant's retraction withdraws its deferred claim`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        val claimant = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        rig.retractFromR(rig.consumerOnQ.ref)
        (rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote).sink shouldBeSameInstanceAs
            rig.pq.aToB

        rig.pq.partition()
        rig.controller.runToIdle()

        rig.registryP.location(rig.consumerOnQ.ref) shouldBe null
        claimant.received.shouldBeEmpty()
    }

    /**
     * ddurc-D4 rule 3(a) (review addition): a claim lives only as long as its
     * claimant's connection. A replacement that retires before the incumbent
     * must not be installed afterwards — its sink is dead.
     */
    @Test
    fun `computenet-ddurc - PerConnection a claimant that retires first is never installed`() {
        val rig = RedirectRig(null, null, AnonymousOwnership.PerConnection)
        val lettersBefore = rig.deadLettersP.size
        val replacement = rig.overlappingQ()
        rig.controller.runToIdle()
        rig.deadLettersP.drop(lettersBefore).count { letter ->
            letter.denial?.exposure == "announcement-admission" &&
                letter.description.contains(rig.consumerOnQ.ref.toString()) &&
                letter.description.contains("the claim is deferred")
        } shouldBe 1

        replacement.partition()
        rig.controller.runToIdle()
        (rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote).sink shouldBeSameInstanceAs
            rig.pq.aToB
        rig.pq.partition()
        rig.controller.runToIdle()

        rig.registryP.location(rig.consumerOnQ.ref) shouldBe null
    }

    /**
     * `unpublishRemotes` is the transport's send-failure path: a sink that
     * notices its socket is dead calls it from inside `deliver`, on a sender
     * thread that may already hold one ref's park-queue monitor (a hold
     * release replaying, a publish draining). Two such senders, each holding a
     * different ref routed through the same dead sink, must both finish — the
     * retire must not wait on a monitor the other sender holds.
     */
    @Test
    fun `computenet-ddurc - two senders that notice one dead sink mid-replay both retire it`() {
        // The losing interleaving is a race (both senders must scan the
        // registry before either removes its ref), so it is attempted many
        // times; one stuck round fails the test.
        repeat(500) { round ->
            val registry = LocationRegistry()
            val refs = listOf(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()))
            val bothMidReplay = java.util.concurrent.CyclicBarrier(2)
            val dead = object : InvocationSink {
                override fun deliver(invocation: HostedPortInvocation) {
                    bothMidReplay.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    registry.unpublishRemotes(this)
                    throw IntakeClosedException(invocation.cellRef)
                }
            }
            refs.forEach { ref ->
                registry.hold(ref)
                registry.publish(ref, dead)
                registry.deliver(
                    HostedPortInvocation(
                        ref, "inlet", HostedPortInvocation.Type.PORT_API,
                        Invocation("provide", listOf("java.lang.Object"), listOf("parked")),
                    ),
                )
            }

            val senders = refs.map { ref ->
                Thread { registry.release(ref) }.apply { isDaemon = true; start() }
            }
            senders.forEach { it.join(10_000) }

            (round to senders.map { it.isAlive }) shouldBe (round to listOf(false, false))
            refs.forEach { ref ->
                registry.location(ref) shouldBe null
                registry.parkedFor(ref).size shouldBe 1
            }
        }
    }

    /**
     * The same two-sender failure with PerConnection state active and a live
     * deferred claimant on each ref. Retirement still removes locations
     * immediately, but claim reconciliation waits until each sender has left
     * the park-queue monitor it entered through replay.
     */
    @Test
    fun `computenet-ddurc - PerConnection two senders mid-replay retire and admit deferred claims`() {
        repeat(500) { round ->
            val registry = LocationRegistry()
            val refs = listOf(CellRef(UUID.randomUUID()), CellRef(UUID.randomUUID()))
            val bothMidReplay = java.util.concurrent.CyclicBarrier(2)
            val dead = object : InvocationSink {
                override fun deliver(invocation: HostedPortInvocation) {
                    bothMidReplay.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    registry.unpublishRemotes(this)
                    throw IntakeClosedException(invocation.cellRef)
                }
            }
            val claimants = refs.associateWith { InvocationSink { } }
            refs.forEach { ref ->
                registry.hold(ref)
                registry.publishFromPeer(
                    ref,
                    dead,
                    peer = null,
                    anonymousOwnership = AnonymousOwnership.PerConnection,
                ) shouldBe null
                registry.publishFromPeer(
                    ref,
                    claimants.getValue(ref),
                    peer = null,
                    anonymousOwnership = AnonymousOwnership.PerConnection,
                )!!.deferred.shouldBeTrue()
                registry.deliver(
                    HostedPortInvocation(
                        ref, "inlet", HostedPortInvocation.Type.PORT_API,
                        Invocation("provide", listOf("java.lang.Object"), listOf("parked")),
                    ),
                )
            }

            val senders = refs.map { ref ->
                Thread { registry.release(ref) }.apply { isDaemon = true; start() }
            }
            senders.forEach { it.join(10_000) }

            (round to senders.map { it.isAlive }) shouldBe (round to listOf(false, false))
            refs.forEach { ref ->
                val location = registry.location(ref) as LocationRegistry.Remote
                location.sink shouldBeSameInstanceAs claimants.getValue(ref)
                registry.parkedFor(ref).shouldBeEmpty()
            }
        }
    }

    /**
     * A Shared retirement may have selected its monitor-free path immediately
     * before the registry's first PerConnection admission. The admission waits
     * for that already-entered scan, so it observes the ref as free and installs
     * directly instead of recording a claim the Shared scan would strand.
     */
    @Test
    fun `computenet-ddurc - first PerConnection admission cannot race behind a Shared retire scan`() {
        val sharedScanEntered = java.util.concurrent.CountDownLatch(1)
        val releaseSharedScan = java.util.concurrent.CountDownLatch(1)
        val perConnectionWaiting = java.util.concurrent.CountDownLatch(1)
        val registry = LocationRegistry(
            beforeSharedRemoteRemoval = {
                sharedScanEntered.countDown()
                releaseSharedScan.await(20, java.util.concurrent.TimeUnit.SECONDS).shouldBeTrue()
            },
            onPerConnectionWait = perConnectionWaiting::countDown,
        )
        val ref = CellRef(UUID.randomUUID())
        val owner = InvocationSink { }
        val claimant = InvocationSink { }
        registry.publish(ref, owner)

        val retirer = Thread { registry.unpublishRemotes(owner) }.apply {
            isDaemon = true
            start()
        }
        sharedScanEntered.await(20, java.util.concurrent.TimeUnit.SECONDS).shouldBeTrue()

        val refusal = java.util.concurrent.atomic.AtomicReference<LocationRegistry.RemotePublishRefusal?>()
        val publisher = Thread {
            refusal.set(
                registry.publishFromPeer(
                    ref,
                    claimant,
                    peer = null,
                    anonymousOwnership = AnonymousOwnership.PerConnection,
                ),
            )
        }.apply {
            isDaemon = true
            start()
        }
        perConnectionWaiting.await(20, java.util.concurrent.TimeUnit.SECONDS).shouldBeTrue()

        releaseSharedScan.countDown()
        retirer.join(10_000)
        publisher.join(10_000)

        listOf(retirer.isAlive, publisher.isAlive) shouldBe listOf(false, false)
        refusal.get() shouldBe null
        registry.location(ref) shouldBe LocationRegistry.Remote(claimant, peer = null)
    }

    /**
     * A claimant retirement must not miss a ref when the incumbent retracts
     * after the retirement's location scan and promotes that claimant. The
     * scan hook pins the interleaving instead of relying on thread timing.
     */
    @Test
    fun `computenet-ilcg6 - claimant retirement covers promotion between candidate scans`() {
        val locationScanFinished = java.util.concurrent.CountDownLatch(1)
        val releaseRetirement = java.util.concurrent.CountDownLatch(1)
        val registry = LocationRegistry(
            beforeSharedRemoteRemoval = null,
            onPerConnectionWait = null,
            afterPerConnectionLocationScan = {
                locationScanFinished.countDown()
                releaseRetirement.await(20, java.util.concurrent.TimeUnit.SECONDS).shouldBeTrue()
            },
        )
        val ref = CellRef(UUID.randomUUID())
        val incumbent = InvocationSink { }
        val retiringClaimant = InvocationSink { }
        registry.publishFromPeer(
            ref,
            incumbent,
            peer = null,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        registry.publishFromPeer(
            ref,
            retiringClaimant,
            peer = null,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        )!!.deferred.shouldBeTrue()

        val retirer = Thread { registry.unpublishRemotes(retiringClaimant) }.apply {
            isDaemon = true
            start()
        }
        locationScanFinished.await(20, java.util.concurrent.TimeUnit.SECONDS).shouldBeTrue()

        registry.unpublishFromPeer(
            ref,
            peer = null,
            sink = incumbent,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        registry.location(ref) shouldBe LocationRegistry.Remote(retiringClaimant, peer = null)
        releaseRetirement.countDown()
        retirer.join(10_000)

        retirer.isAlive shouldBe false
        registry.location(ref) shouldBe null

        // A stale deferred claim would be resurrected when this probe owner retracts.
        val probeOwner = InvocationSink { }
        registry.publishFromPeer(
            ref,
            probeOwner,
            peer = null,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        registry.unpublishFromPeer(
            ref,
            peer = null,
            sink = probeOwner,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        registry.location(ref) shouldBe null
    }

    /**
     * The named/anonymous boundary remains protected in both directions even
     * though two anonymous connections share ownership: concrete [PeerId] and
     * null never compare equal. Both arms use independent loopback peerings and
     * prove delivery stays with the incumbent after the refused announcement.
     */
    @Test
    fun `computenet-4f55i - named and anonymous peers cannot capture each other's announcements`() {
        val anonymousOwner = RedirectRig(requesterPeer = null, thirdPeer = RedirectRig.THIRD_R)
        anonymousOwner.bridgeP.managementInlet.call.supervise(
            anonymousOwner.mirrorFromR,
            SupervisionPolicy.RESTART,
        )
        val anonymousConsumer = HostedCellProxy.create(
            anonymousOwner.consumerOnQ.ref,
            anonymousOwner.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        anonymousOwner.source.outlet.linkTo(anonymousConsumer.inlet)
        val namedLettersBefore = anonymousOwner.deadLettersP.size
        val namedFaultLettersBefore = anonymousOwner.bridgeP.supervisionAccounting().deadLetters
        val namedImpostor = anonymousOwner.announceImpostorFromR(anonymousOwner.consumerOnQ.ref)
        anonymousOwner.emit("anonymous-owner")

        anonymousOwner.consumerOnQ.received shouldBe listOf("anonymous-owner")
        namedImpostor.received.shouldBeEmpty()
        anonymousOwner.assertImpostorAnnouncementRefused(
            namedLettersBefore,
            namedFaultLettersBefore,
        )

        val namedOwner = RedirectRig(requesterPeer = RedirectRig.REQUESTER_Q, thirdPeer = null)
        namedOwner.bridgeP.managementInlet.call.supervise(
            namedOwner.mirrorFromR,
            SupervisionPolicy.RESTART,
        )
        val namedConsumer = HostedCellProxy.create(
            namedOwner.consumerOnQ.ref,
            namedOwner.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        namedOwner.source.outlet.linkTo(namedConsumer.inlet)
        val anonymousLettersBefore = namedOwner.deadLettersP.size
        val anonymousFaultLettersBefore = namedOwner.bridgeP.supervisionAccounting().deadLetters
        val anonymousImpostor = namedOwner.announceImpostorFromR(namedOwner.consumerOnQ.ref)
        namedOwner.emit("named-owner")

        namedOwner.consumerOnQ.received shouldBe listOf("named-owner")
        anonymousImpostor.received.shouldBeEmpty()
        namedOwner.assertImpostorAnnouncementRefused(
            anonymousLettersBefore,
            anonymousFaultLettersBefore,
            expectedPrincipal = null,
        )
    }

    /**
     * computenet-zlm2 arm 2, the control: no RemoteLink request participates.
     * P links its own outlet to q through the ordinary registry-resolving
     * HostedCellProxy; r still cannot capture the proxy's later deliveries by
     * announcing q's ref.
     */
    @Test
    fun `computenet-zlm2 - another peer cannot capture an ordinary HostedCellProxy link`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        rig.consumerOnQ.received shouldBe listOf("first")

        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters
        val impostorOnR = rig.announceImpostorFromR(rig.consumerOnQ.ref)
        rig.emit("q-only-secret")

        rig.consumerOnQ.received shouldBe listOf("first", "q-only-secret")
        impostorOnR.received.shouldBeEmpty()
        rig.assertImpostorAnnouncementRefused(lettersBefore, faultLettersBefore)
    }

    /**
     * computenet-zlm2 arm 3: r cannot shadow a ref P hosts locally. Retaining
     * P's Local binding also means r's subsequent real-frame RemoteLink request
     * names an address it does not own and is refused by the existing gate.
     */
    @Test
    fun `computenet-zlm2 - another peer cannot shadow a locally hosted ref`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        rig.bridgeP.managementInlet.call.supervise(rig.ingressFromR.ref, SupervisionPolicy.RESTART)
        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters
        val impostorOnR = rig.announceImpostorFromR(rig.victimOnP.ref)

        rig.registryP.location(rig.victimOnP.ref) shouldBe LocationRegistry.Local(rig.hostP)
        rig.requestFromR(PortAddress(rig.victimOnP.ref, "inlet"))
        rig.emit("p-internal-secret")

        rig.victimOnP.received.shouldBeEmpty()
        impostorOnR.received.shouldBeEmpty()
        rig.source.outlet.linking.links.shouldBeEmpty()
        rig.assertImpostorAnnouncementRefused(lettersBefore, faultLettersBefore)
    }

    /**
     * computenet-zlm2, review arm: the publish guard alone is bypassed in two
     * announcements — r retracts the incumbent (its own despawn of the
     * impostor announces `unpublished`), then re-announces the now-fresh ref.
     * Measured against 795abe6e before the retraction half existed: P resolved
     * q's ref as `Remote(peer=third-party-r)` and r received `q-only-secret`;
     * P's own victim ref resolved the same way and r received
     * `p-internal-secret`. Each of the three refused announcements (publish,
     * unpublish, re-publish) is one typed ADMISSION denial naming r.
     */
    @Test
    fun `computenet-zlm2 - another peer cannot retract then re-claim a ref it does not own`() {
        val rig = RedirectRig()
        rig.bridgeP.managementInlet.call.supervise(rig.mirrorFromR, SupervisionPolicy.RESTART)
        val consumer = HostedCellProxy.create(
            rig.consumerOnQ.ref,
            rig.registryP,
            CollectorProxy::class.java,
        ) as CollectorProxy
        rig.source.outlet.linkTo(consumer.inlet)
        rig.emit("first")
        val lettersBefore = rig.deadLettersP.size
        val faultLettersBefore = rig.bridgeP.supervisionAccounting().deadLetters

        val impostors = listOf(rig.consumerOnQ.ref, rig.victimOnP.ref).flatMap { ref ->
            val first = rig.announceImpostorFromR(ref)
            rig.hostR.managementInlet.call.despawn(ref)
            rig.controller.runToIdle()
            listOf(first, rig.announceImpostorFromR(ref))
        }

        (rig.registryP.location(rig.consumerOnQ.ref) as LocationRegistry.Remote).peer shouldBe
            RedirectRig.REQUESTER_Q
        rig.registryP.location(rig.victimOnP.ref) shouldBe LocationRegistry.Local(rig.hostP)
        rig.requestFromR(PortAddress(rig.victimOnP.ref, "inlet"))
        rig.emit("secret")

        rig.consumerOnQ.received shouldBe listOf("first", "secret")
        rig.victimOnP.received.shouldBeEmpty()
        impostors.forEach { it.received.shouldBeEmpty() }

        val denials = rig.deadLettersP.drop(lettersBefore)
            .filter { it.denial?.exposure == "announcement-admission" }
        denials.map { it.denial!!.subject } shouldBe List(2) {
            listOf("RegistryAnnounce.published", "RegistryAnnounce.unpublished", "RegistryAnnounce.published")
        }.flatten()
        denials.forEach {
            it.denial!!.principal shouldBe RedirectRig.THIRD_R
            it.cause shouldBe null
        }
        rig.bridgeP.supervisionAccounting().deadLetters shouldBe faultLettersBefore
        rig.bridgeP.supervisionAccounting().restarts shouldBe 0L
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

    /**
     * computenet-a4ha's fourth probe, `PROBE repeated links = 5`, closed by
     * computenet-hil6 — and closed as a **repair, not a policy**: no cap was
     * chosen and no number appears anywhere in the change.
     *
     * The amplifier was an identity accident. `RemoteLinkRequests.translate`
     * built the stand-in for the requesting peer's port as `FanInlet(api)`,
     * which mints `PortRef.generate()` — a fresh anonymous ref per request
     * (`cell=null`). `FanOutlet.consumers` is keyed by [PortRef], so five
     * identical requests installed five distinct consumers on one outlet.
     * Measured against the unfixed code: `links=5 consumers=5 delivered=5`,
     * five refs with `cell=null`. Giving the stand-in the DERIVED ref of the
     * port it stands for ([PortRef.of], `standInRef`) makes the repeat replace
     * the attachment instead of adding a sibling — the same mechanism
     * `FanOutlet.streamTo` (T21) and `GossipLinkIdempotenceTest` already rely
     * on, where the link ref is likewise derived from the pair it connects.
     *
     * The aim property computenet-a4ha established is asserted unchanged: the
     * one copy that does cross lands on the requesting peer's own consumer and
     * nowhere else.
     *
     * **The bookkeeping collapses with it** (computenet-lioe). When this test
     * was written it asserted `links=5`: `LinkSupport.active` is keyed by a
     * random `Link.id`, so each admitted request left a superseded record on
     * the target outlet even though only one consumer attachment survived — the
     * orphan T21 had to evict explicitly in `streamTo`, in a code path outside
     * this seam. `handshake` now evicts it for every link (`evictSuperseded`,
     * keyed on the whole `(from, to, role)` triple), so the honest number here
     * is 1. The general-path pin is `civictech.cell.link.LinkSupersessionTest`;
     * this count is the wire-facing consequence of it.
     */
    @Test
    fun `computenet-hil6 - repeated identical link requests collapse onto one consumer and one delivery`() {
        val rig = RedirectRig()
        repeat(5) { rig.requestFromQ(PortAddress(rig.consumerOnQ.ref, "inlet")) }

        // one live attachment, keyed by the derived ref of the peer's own port
        val consumers = consumerRefs(rig.source.outlet)
        consumers shouldBe setOf(PortRef.of(rig.consumerOnQ.ref, "inlet"))

        rig.emit("p-internal-secret")

        // ...so P's single emission crosses ONCE, not five times
        rig.consumerOnQ.received shouldBe listOf("p-internal-secret")
        // ...and the aim computenet-a4ha bound is unchanged: nowhere else
        rig.victimOnP.received.shouldBeEmpty()
        rig.consumerOnR.received.shouldBeEmpty()

        // ...and the bookkeeping collapses with it (computenet-lioe): the
        // repeats supersede each other rather than accumulating records
        rig.source.outlet.linking.links.size shouldBe 1
    }

    /**
     * The discriminator of the test above: a repeat must not be able to pass by
     * simply never linking. A *distinct* endpoint on the same peer still gets
     * its own consumer, so the collapse is de-duplication of an identical
     * address and not a mute of repeated requests.
     */
    @Test
    fun `computenet-hil6 - distinct endpoints on one peer still get their own links`() {
        val rig = RedirectRig()
        rig.requestFromQ(PortAddress(rig.consumerOnQ.ref, "inlet"))
        rig.requestFromQ(PortAddress(rig.consumerOnQ.ref, "other-inlet"))

        consumerRefs(rig.source.outlet) shouldBe setOf(
            PortRef.of(rig.consumerOnQ.ref, "inlet"),
            PortRef.of(rig.consumerOnQ.ref, "other-inlet"),
        )
    }

    /**
     * Live consumer attachments on [outlet]. `FanOutlet.consumers` is private —
     * deliberately, it is the fan-out hot path — so the probe reads it
     * reflectively rather than widening the port API for one assertion, exactly
     * as `GossipLinkIdempotenceTest` does for the same field.
     */
    private fun consumerRefs(outlet: FanOutlet<*>): Set<PortRef> {
        val field = FanOutlet::class.java.getDeclaredField("consumers").apply { isAccessible = true }
        return (field.get(outlet) as Map<*, *>).keys.map { it as PortRef }.toSet()
    }
}
