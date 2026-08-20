package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
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

    @Test
    fun `link requests carry the delivering peer's identity into policies`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val deadLetters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                deadLetters += value
            }
        }, PortRef.generate()))

        val target = CollectingCell()
        host.managementInlet.call.spawn(target)
        controller.runToIdle()
        target.inlet.linking.policies += allowPeers(PeerId("good"))

        val linkFrom = LinkFrom::class.java.methods.first { it.name == "linkFrom" }
        fun requestFrom(peer: PeerId): FanOutlet<Consumer<String>> {
            val outlet = FanOutlet.create<Consumer<String>>()
            host.enqueueHostedInvocation(
                HostedPortInvocation(
                    target.ref, "inlet", HostedPortInvocation.Type.PORT_MANAGEMENT,
                    Invocation.of(linkFrom, arrayOf(outlet)), peer = peer,
                )
            )
            controller.runToIdle()
            return outlet
        }

        val refused = requestFrom(PeerId("evil"))
        refused.linking.links.shouldBeEmpty()
        deadLetters.any { it.description.contains("allowlist") }.shouldBeTrue()

        val admitted = requestFrom(PeerId("good"))
        admitted.linking.links.size shouldBe 1
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
     * flipping `q` to hold credentials and re-running the principal
     * assertion turns the observed principal into
     * `Peer(PeerId("q"), Authenticated)` — see the task's final report for
     * the exact assertion watched failing.
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
}
