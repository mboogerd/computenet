package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.AuthLevel
import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Feature `computenet-5y8t.4` (epic `computenet-5y8t`, DSC4, decisions
 * F4-D4/F4-D6): an allowlist that names an IDENTITY keeps admitting a peer
 * across a key rotation, because admission is judged key proven -> identity
 * resolved through [Peering.Side.identityBinding] -> name on the allowlist,
 * never the key itself.
 *
 * The rig is [LoopbackIssuerAttributionTest]'s (positive cases) and
 * [TrustBoundaryTest]'s (refusal-side accounting): one receiver `R`, built
 * once per test method and reused across peerings, with an injected test
 * binding that maps a presented key to the name of a statement it carries —
 * no signature check, no crypto, `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED
 * in every case here. Nothing in this file is evidence of stolen-key
 * resistance or of revocation, and no case re-peers a K1 sender after K2 has
 * been admitted — that supersession question is left to feature 5.
 */
class AllowlistNamesIdentityTest {

    /** A [PeerCredentials] as plain data, with an explicit key id and statements. */
    private class Credentials(
        override val keyId: KeyId,
        override val peerId: PeerId,
        override val statements: List<IdentityStatement> = emptyList(),
    ) : PeerCredentials {
        override val publicKey: ByteArray = "public-key-of-${keyId.name}".toByteArray()
        override fun sign(message: ByteArray): ByteArray = message
    }

    /** Records the ambient [Principal] of every attention assertion it is handed. */
    private class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()

        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    private companion object {
        val ALICE = PeerId("alice")
        val BOB = PeerId("bob")
        val K1 = KeyId("alice-key-1")
        val K2 = KeyId("alice-key-2")
        val ANCHOR_A = IssuerId("anchor-A")

        fun stmt(name: PeerId, key: KeyId, issuance: Long) =
            IdentityStatement(name, key, ANCHOR_A, issuance, 0, Long.MAX_VALUE, ByteArray(0))

        /** Maps a key to the name of a presented statement for it — no signature check. */
        val statementReading = PeerIdentityBinding { k, presented ->
            presented.firstOrNull { it.keyId == k }
                ?.let { IdentityResolution.Bound(it.name, it.issuer, it) }
                ?: IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        }

        fun protocolFrame(target: CellRef): HostedPortInvocation = HostedPortInvocation(
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
    }

    /**
     * A receiver `Side` plus the plumbing to peer against it repeatedly and
     * observe both what a delivered frame stamps and what a refused one
     * accounts. Built once per test method (`identityBinding` fixed at
     * construction) and reused across [peerAndDeliver] calls — the "no
     * reconfiguration" claim in every positive case rests on `receiver` being
     * the same `Side` object throughout.
     */
    private class Receiver(allow: Set<PeerId>?, identityBinding: PeerIdentityBinding = statementReading) {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val deadLetters = mutableListOf<DeadLetter>()

        val side = Peering.Side(
            registry,
            bridgeHost,
            peer = PeerId("r"),
            credentials = Credentials(KeyId("r-key"), PeerId("r")),
            allow = allow,
            identityBinding = identityBinding,
        )

        val probe = PrincipalProbeCell()

        init {
            bridgeHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            bridgeHost.managementInlet.call.spawn(probe)
            controller.runToIdle()
        }

        /** Peers [sender] against this receiver and delivers one sender-to-receiver frame. */
        fun peerAndDeliver(sender: Peering.Side): Peering.Loopback {
            val loopback = Peering.loopback(sender, side)
            controller.runToIdle()
            loopback.aToB.deliver(protocolFrame(probe.ref))
            controller.runToIdle()
            return loopback
        }

        fun sender(peer: PeerId, credentials: PeerCredentials): Peering.Side {
            val registryS = LocationRegistry()
            return Peering.Side(
                registryS,
                ManagedHost(scheduler = controller.scheduler(), registry = registryS),
                peer = peer,
                credentials = credentials,
            )
        }
    }

    @Test
    fun `an allowlist naming alice admits alice on K1 and stamps the vouching issuer`() {
        val r = Receiver(allow = setOf(ALICE))
        val s1 = r.sender(ALICE, Credentials(K1, ALICE, listOf(stmt(ALICE, K1, issuance = 1))))

        r.peerAndDeliver(s1)

        r.probe.principals.lastOrNull() shouldBe Principal.Peer(ALICE, AuthLevel.Authenticated, ANCHOR_A)
    }

    @Test
    fun `the same allowlist entry admits alice again on K2 with no reconfiguration`() {
        val r = Receiver(allow = setOf(ALICE))
        val s1 = r.sender(ALICE, Credentials(K1, ALICE, listOf(stmt(ALICE, K1, issuance = 1))))
        r.peerAndDeliver(s1)

        (K1 == K2) shouldBe false // the rotation is real: two distinct keys are in play

        r.probe.principals.size shouldBe 1 // K1 admitted
        val s2 = r.sender(ALICE, Credentials(K2, ALICE, listOf(stmt(ALICE, K2, issuance = 2))))
        r.peerAndDeliver(s2)

        // The K2 frame itself must land: `lastOrNull` alone would still read
        // K1's principal if K2 were refused.
        r.probe.principals.size shouldBe 2
        r.probe.principals.last() shouldBe Principal.Peer(ALICE, AuthLevel.Authenticated, ANCHOR_A)
        // documents the "no reconfiguration" claim; allow is a val and was never touched
        r.side.allow shouldBe setOf(ALICE)
    }

    @Test
    fun `a statement naming bob on K1 is refused NOT_ADMITTED even though K1 was admitted under alice's name`() {
        val r = Receiver(allow = setOf(ALICE))
        val s1 = r.sender(ALICE, Credentials(K1, ALICE, listOf(stmt(ALICE, K1, issuance = 1))))
        r.peerAndDeliver(s1)

        val t = r.sender(BOB, Credentials(K1, BOB, listOf(stmt(BOB, K1, issuance = 1))))
        val loopback = Peering.loopback(t, r.side)
        r.controller.runToIdle()
        val ingress = loopback.ingressOnB!!
        r.bridgeHost.managementInlet.call.supervise(ingress.ref, SupervisionPolicy.RESTART)

        // Baselines taken AFTER the peering is established: opening a loopback
        // spawns fresh bridge cells on r's own host (a new ingress and mirror),
        // which change r.registry.localRefs() before any frame is delivered —
        // the same reason TrustBoundaryTest takes its denial baseline post-peering.
        val refsBefore = r.registry.localRefs()
        val sink = ingress.boundaryDenials["bridge-ingress"]!!
        val denialCountBefore = sink.denialCount
        val lettersBefore = r.deadLetters.size
        val principalsBefore = r.probe.principals.size

        loopback.aToB.deliver(protocolFrame(r.probe.ref))
        r.controller.runToIdle()

        r.probe.principals.size shouldBe principalsBefore // no new principal recorded
        (sink.denialCount - denialCountBefore) shouldBe 1L

        val denialLetters = r.deadLetters.drop(lettersBefore)
            .filter { it.description.contains("seam=ADMISSION") }
        denialLetters.size shouldBe 1
        val letter = denialLetters.single()
        letter.cause shouldBe null
        letter.description shouldContain "NOT_ADMITTED"
        letter.description shouldContain "bob"

        r.bridgeHost.supervisionAccounting().restarts shouldBe 0L
        r.registry.localRefs() shouldBe refsBefore
    }

    @Test
    fun `a key's own name on the allowlist admits nobody whose name is alice`() {
        // The live discriminator: at pre-feature (key-judged) semantics the
        // equivalent configuration (`allow = setOf(KeyId(K1.name))`) admits
        // S1. Naming the key's own string as a PeerId here still refuses —
        // the name is what is authorised, never the key.
        val r = Receiver(allow = setOf(PeerId(K1.name)))
        val s1 = r.sender(ALICE, Credentials(K1, ALICE, listOf(stmt(ALICE, K1, issuance = 1))))

        val loopback = Peering.loopback(s1, r.side)
        r.controller.runToIdle()
        val ingress = loopback.ingressOnB!!
        r.bridgeHost.managementInlet.call.supervise(ingress.ref, SupervisionPolicy.RESTART)

        val sink = ingress.boundaryDenials["bridge-ingress"]!!
        val denialCountBefore = sink.denialCount
        val lettersBefore = r.deadLetters.size
        val principalsBefore = r.probe.principals.size

        loopback.aToB.deliver(protocolFrame(r.probe.ref))
        r.controller.runToIdle()

        r.probe.principals.size shouldBe principalsBefore
        (sink.denialCount - denialCountBefore) shouldBe 1L
        val denialLetters = r.deadLetters.drop(lettersBefore)
            .filter { it.description.contains("seam=ADMISSION") }
        denialLetters.size shouldBe 1
        val letter = denialLetters.single()
        letter.description shouldContain "NOT_ADMITTED"
        letter.description shouldContain "alice"
        r.bridgeHost.supervisionAccounting().restarts shouldBe 0L
    }

    @Test
    fun `under Interim the same entry admits alice only as TransportVouched with no issuer`() {
        val r = Receiver(allow = setOf(ALICE), identityBinding = PeerIdentityBinding.Interim)
        val s1 = r.sender(ALICE, Credentials(K1, ALICE, listOf(stmt(ALICE, K1, issuance = 1))))

        r.peerAndDeliver(s1)

        r.probe.principals.lastOrNull() shouldBe Principal.Peer(ALICE, AuthLevel.TransportVouched, null)
    }
}
