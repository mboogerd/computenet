package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.AuthLevel
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-ssa.3.2` ([DSC1-HELLO-05], [DSC1-WIRE-04], [DSC1-WIRE-05]
 * principal half): the `AuthLevel` a crossing was admitted at reaches
 * `currentPrincipal()`, and **only** a crossing that achieved it.
 *
 * ## Why the probe is a `PORT_PROTOCOL` frame
 *
 * `ManagedHost` installs the ambient peer on its `PORT_PROTOCOL` and
 * `PORT_MANAGEMENT` branches (`BridgeBoundaryPolicyTest` records why), and of
 * those only `PORT_PROTOCOL` is wire-encodable — a link request carries no
 * `@Contract` ids, so `WireCodec.encode` cannot take it. So a `PORT_PROTOCOL`
 * frame pushed into the peering's own egress is the one probe that travels the
 * **whole** path this task plumbs: encode, decode, the ingress's stamp, the
 * host's ambient, and back out of `currentPrincipal()`. Nothing here
 * hand-builds the stamp; every principal asserted below was put there by
 * `BridgeIngressCell`.
 *
 * ## What decides the level here
 *
 * [Peering.loopback] exchanges no hello and the kernel verifies no signature
 * ([DSC1-WIRE-04] — this file imports nothing from `:identity` and no crypto;
 * [Credentials] below is a plain data holder). The level is computed from the
 * two `Side`s' configuration by [Peering.loopbackAuthLevel], whose KDoc is the
 * rule; these tests are that rule's cases, stated as observable principals
 * rather than as a return value, so they fail if the plumbing between the
 * decision and the delivery breaks as readily as if the decision does.
 */
class LoopbackPrincipalTest {

    /**
     * A [PeerCredentials] with no cryptography in it at all — which is the
     * point ([DSC1-WIRE-04]): the kernel treats credentials as *data*, and
     * nothing on the loopback path signs, verifies or hashes anything. Real
     * keys live in `:identity`, which `:kernel` does not depend on.
     */
    private class Credentials(override val peerId: PeerId) : PeerCredentials {
        /**
         * A test double's key identifier: the same string as [peerId], which is
         * what `PeerIdentityBinding.Interim` resolves between anyway (feature
         * `computenet-376c`). Nothing here fingerprints a key — the kernel
         * treats credentials as data ([DSC1-WIRE-04]).
         */
        override val keyId: KeyId = KeyId(peerId.name)

        override val publicKey: ByteArray = "public-key-of-${peerId.name}".toByteArray()
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

        /**
         * Two sides sharing one [SimulationController]-driven scheduler, each
         * with its own registry and bridge host — the shape every loopback
         * fixture in this package uses.
         */
        fun sides(
            controller: SimulationController,
            aName: PeerId?,
            bName: PeerId?,
            aCredentials: PeerCredentials? = null,
            bCredentials: PeerCredentials? = null,
        ): Pair<Peering.Side, Peering.Side> {
            val registryA = LocationRegistry()
            val registryB = LocationRegistry()
            return Peering.Side(
                registryA,
                ManagedHost(scheduler = controller.scheduler(), registry = registryA),
                peer = aName,
                credentials = aCredentials,
            ) to Peering.Side(
                registryB,
                ManagedHost(scheduler = controller.scheduler(), registry = registryB),
                peer = bName,
                credentials = bCredentials,
            )
        }
    }

    /**
     * Builds the fixture and returns the principal a B->A crossing observes.
     * One helper for every row, so the only thing that varies between the
     * cases below is the two sides' *configuration* — which is exactly the
     * claim [Peering.loopbackAuthLevel] makes.
     */
    private fun principalOfCrossing(
        aName: PeerId? = PeerId("a"),
        bName: PeerId? = PeerId("b"),
        aCredentials: PeerCredentials? = null,
        bCredentials: PeerCredentials? = null,
    ): Principal? {
        val controller = SimulationController(0)
        val (a, b) = sides(controller, aName, bName, aCredentials, bCredentials)
        val probe = PrincipalProbeCell()
        val loopback = Peering.loopback(a, b)
        a.bridgeHost.managementInlet.call.spawn(probe)
        controller.runToIdle()
        loopback.bToA.deliver(protocolFrame(probe.ref))
        controller.runToIdle()
        return probe.principals.lastOrNull()
    }

    @Test
    fun `Promotion - an authenticated loopback observes Principal Peer(id, Authenticated)`() {
        // Both sides hold credentials and each announces itself under the id
        // its own key derives: the socket's PROOF row, expressed as
        // configuration ([DSC1-WIRE-05] principal half).
        val bKeys = Credentials(PeerId("b"))
        principalOfCrossing(
            aCredentials = Credentials(PeerId("a")),
            bCredentials = bKeys,
        ) shouldBe Principal.Peer(bKeys.peerId, AuthLevel.Authenticated)
    }

    @Test
    fun `No ambient promotion - a default Open loopback with no keypairs observes TransportVouched`() {
        // Byte for byte today's behaviour ([DSC1-WIRE-06]): the negative the
        // acceptance criteria require — an unauthenticated crossing does NOT
        // observe Authenticated.
        principalOfCrossing() shouldBe Principal.Peer(PeerId("b"), AuthLevel.TransportVouched)
    }

    @Test
    fun `a sender with a keypair the receiver cannot challenge is not promoted`() {
        // The socket's uncredentialed-HELLO2 row: a well-keyed peer arriving at
        // a side that holds no keypair is admitted at TransportVouched, because
        // that side could never have issued the challenge.
        principalOfCrossing(bCredentials = Credentials(PeerId("b"))) shouldBe
            Principal.Peer(PeerId("b"), AuthLevel.TransportVouched)
    }

    @Test
    fun `a receiver with a keypair does not promote an unkeyed sender`() {
        principalOfCrossing(aCredentials = Credentials(PeerId("a"))) shouldBe
            Principal.Peer(PeerId("b"), AuthLevel.TransportVouched)
    }

    @Test
    fun `a peer name its own key does not derive is not promoted`() {
        // The socket's ID_MISMATCH refusal, as data: the *stamped* name is
        // Side.peer, so promoting it while the key derives something else would
        // authenticate a claim no key backs.
        principalOfCrossing(
            aCredentials = Credentials(PeerId("a")),
            bName = PeerId("not-my-fingerprint"),
            bCredentials = Credentials(PeerId("b")),
        ) shouldBe Principal.Peer(PeerId("not-my-fingerprint"), AuthLevel.TransportVouched)
    }

    @Test
    fun `an anonymous sender is a local principal, never an authenticated one`() {
        // No stamp at all -> LocalTrusted, which is not a level: an unnamed
        // side cannot be promoted because there is no id to promote.
        principalOfCrossing(
            aCredentials = Credentials(PeerId("a")),
            bName = null,
            bCredentials = Credentials(PeerId("b")),
        ) shouldBe Principal.LocalTrusted
    }

    @Test
    fun `Local unchanged - an in-process assertion observes LocalTrusted`() {
        val controller = SimulationController(0)
        val (a, b) = sides(
            controller,
            PeerId("a"),
            PeerId("b"),
            aCredentials = Credentials(PeerId("a")),
            bCredentials = Credentials(PeerId("b")),
        )
        val probe = PrincipalProbeCell()
        Peering.loopback(a, b)
        a.bridgeHost.managementInlet.call.spawn(probe)
        controller.runToIdle()

        // Same frame, same port, same authenticated peering — delivered without
        // crossing it. The fast path is untouched (93 I-28 §4.2).
        a.registry.deliver(protocolFrame(probe.ref))
        controller.runToIdle()

        probe.principals shouldContainExactly listOf(Principal.LocalTrusted)
    }

    @Test
    fun `the level is fixed before the ingress exists, so every delivery reads the same one`() {
        val controller = SimulationController(0)
        val (a, b) = sides(
            controller,
            PeerId("a"),
            PeerId("b"),
            aCredentials = Credentials(PeerId("a")),
            bCredentials = Credentials(PeerId("b")),
        )
        val probe = PrincipalProbeCell()
        val loopback = Peering.loopback(a, b)
        a.bridgeHost.managementInlet.call.spawn(probe)
        controller.runToIdle()

        repeat(5) {
            loopback.bToA.deliver(protocolFrame(probe.ref))
            controller.runToIdle()
        }

        probe.principals.size shouldBe 5
        probe.principals.toSet() shouldBe setOf(Principal.Peer(PeerId("b"), AuthLevel.Authenticated))
    }
}
