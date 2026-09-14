package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
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
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task `computenet-5y8t.1.3` (feature `computenet-5y8t.1`, decision D9): a
 * loopback crossing is resolved on the **relying** side — the receiver's
 * [Peering.Side.identityBinding], with the sender's presented
 * [PeerCredentials.statements] — and the issuer that vouched is stamped on the
 * delivery exactly when the crossing is promoted to [AuthLevel.Authenticated].
 *
 * The first case is the feature's "second binding without call-site change":
 * a binding other than [PeerIdentityBinding.Interim] is installed on a `Side`,
 * and nothing in `Peering.loopback`'s callers, `BridgeCells.kt` or the socket
 * transports changes for its issuer to reach `currentPrincipal()`.
 *
 * The rig is [LoopbackPrincipalTest]'s: a `PORT_PROTOCOL` frame pushed through
 * the peering's own egress, observed by a probe cell's `currentPrincipal()`, so
 * every principal asserted below was stamped by `BridgeIngressCell`.
 *
 * **What this file does NOT show.** The bindings here read presented
 * statements with **no signature check** — the kernel verifies nothing
 * ([DSC1-WIRE-04]). No case is evidence of stolen-key resistance or of
 * revocation; `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED. The statements'
 * `issuance`, `notBefore` and `notAfter` are carried and compared by nothing.
 */
class LoopbackIssuerAttributionTest {

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
        val ALICE_KEY = KeyId("alice-key-1")
        val ANCHOR_A = IssuerId("anchor-A")

        fun aliceStatement() = IdentityStatement(ALICE, ALICE_KEY, ANCHOR_A, 1, 0, Long.MAX_VALUE, ByteArray(0))

        /** Maps a key to the name of a presented statement for it — no signature check. */
        val statementReading = PeerIdentityBinding { k, presented ->
            presented.firstOrNull { it.keyId == k }
                ?.let { IdentityResolution.Bound(it.name, it.issuer, it) }
                ?: IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        }

        /** Accepts no issuer at all. */
        val acceptsNoIssuer = PeerIdentityBinding { _, _ ->
            IdentityResolution.Unbound(UnboundReason.ISSUER_NOT_ACCEPTED)
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
     * Builds a loopback a<->b and returns the principal an **a->b** crossing
     * observes on b. Only the two sides' configuration varies between cases.
     */
    private fun principalOfAToB(
        aPeer: PeerId,
        aCredentials: PeerCredentials,
        aBinding: PeerIdentityBinding,
        bBinding: PeerIdentityBinding,
    ): Principal? {
        val controller = SimulationController(0)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val a = Peering.Side(
            registryA,
            ManagedHost(scheduler = controller.scheduler(), registry = registryA),
            peer = aPeer,
            credentials = aCredentials,
            identityBinding = aBinding,
        )
        val b = Peering.Side(
            registryB,
            ManagedHost(scheduler = controller.scheduler(), registry = registryB),
            peer = PeerId("bob"),
            credentials = Credentials(KeyId("bob-key-1"), PeerId("bob")),
            identityBinding = bBinding,
        )
        val probe = PrincipalProbeCell()
        val loopback = Peering.loopback(a, b)
        b.bridgeHost.managementInlet.call.spawn(probe)
        controller.runToIdle()
        loopback.aToB.deliver(protocolFrame(probe.ref))
        controller.runToIdle()
        return probe.principals.lastOrNull()
    }

    @Test
    fun `a second binding on the receiver stamps the presented statement's issuer with no call-site change`() {
        principalOfAToB(
            aPeer = ALICE,
            aCredentials = Credentials(ALICE_KEY, ALICE, statements = listOf(aliceStatement())),
            aBinding = PeerIdentityBinding.Interim,
            bBinding = statementReading,
        ) shouldBe Principal.Peer(ALICE, AuthLevel.Authenticated, ANCHOR_A)
    }

    @Test
    fun `a sender presenting no statement is TransportVouched with no issuer and no key-derived name`() {
        val observed = principalOfAToB(
            aPeer = ALICE,
            aCredentials = Credentials(ALICE_KEY, ALICE, statements = emptyList()),
            aBinding = PeerIdentityBinding.Interim,
            bBinding = statementReading,
        )
        observed shouldBe Principal.Peer(ALICE, AuthLevel.TransportVouched, null)
        // "alice" != "alice-key-1": nothing stamps a name derived from the key.
        (observed as Principal.Peer).id shouldNotBe PeerId(ALICE_KEY.name)
    }

    @Test
    fun `control - under Interim on both sides a promoted crossing carries no issuer`() {
        val keyDerived = PeerId(ALICE_KEY.name)
        principalOfAToB(
            aPeer = keyDerived,
            aCredentials = Credentials(ALICE_KEY, keyDerived, statements = listOf(aliceStatement())),
            aBinding = PeerIdentityBinding.Interim,
            bBinding = PeerIdentityBinding.Interim,
        ) shouldBe Principal.Peer(keyDerived, AuthLevel.Authenticated, null)
    }

    @Test
    fun `the relying side decides - a sender binding that would vouch does not promote past a refusing receiver`() {
        principalOfAToB(
            aPeer = ALICE,
            aCredentials = Credentials(ALICE_KEY, ALICE, statements = listOf(aliceStatement())),
            aBinding = statementReading,
            bBinding = acceptsNoIssuer,
        ) shouldBe Principal.Peer(ALICE, AuthLevel.TransportVouched, null)
    }
}
