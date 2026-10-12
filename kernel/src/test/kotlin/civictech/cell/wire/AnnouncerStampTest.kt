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
import civictech.cell.link.PeerStamp
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
import civictech.cell.proxy.InvocationSink
import java.util.concurrent.CopyOnWriteArrayList
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** The admission stamp retained by every remote location is the ingress stamp. */
class AnnouncerStampTest {

    private class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    private class Credentials(
        override val keyId: KeyId,
        override val peerId: PeerId,
        override val statements: List<IdentityStatement> = emptyList(),
    ) : PeerCredentials {
        override val publicKey: ByteArray = "public-key-of-${keyId.name}".toByteArray()
        override fun sign(message: ByteArray): ByteArray = message
    }

    private class Rig(
        aPeer: PeerId?,
        bPeer: PeerId?,
        aCredentials: PeerCredentials? = null,
        bCredentials: PeerCredentials? = null,
        aBinding: PeerIdentityBinding = PeerIdentityBinding.Interim,
        bBinding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ) {
        val controller = SimulationController(0)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        private val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        private val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        private val sideA = Peering.Side(
            registryA,
            bridgeA,
            peer = aPeer,
            credentials = aCredentials,
            identityBinding = aBinding,
        )
        private val sideB = Peering.Side(
            registryB,
            bridgeB,
            peer = bPeer,
            credentials = bCredentials,
            identityBinding = bBinding,
        )
        val link = Peering.loopback(sideA, sideB)

        fun publishOnA(ref: CellRef): LocationRegistry.Remote {
            registryA.publish(ref, hostA)
            controller.runToIdle()
            return registryB.location(ref) as LocationRegistry.Remote
        }

        fun principalFromA(): Principal? {
            val probe = PrincipalProbeCell()
            bridgeB.managementInlet.call.spawn(probe)
            controller.runToIdle()
            link.aToB.deliver(protocolFrame(probe.ref))
            controller.runToIdle()
            return probe.principals.lastOrNull()
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
    }

    @Test
    fun `named uncredentialed announcement retains its TransportVouched stamp`() {
        val rig = Rig(PeerId("a"), PeerId("b"))

        rig.publishOnA(CellRef(UUID.randomUUID())).announcer shouldBe
            PeerStamp(PeerId("a"), AuthLevel.TransportVouched, issuer = null)
    }

    @Test
    fun `credentialed announcement retains the authenticated issuer stamp`() {
        val alice = PeerId("alice")
        val aliceKey = KeyId("alice-key-1")
        val anchor = IssuerId("anchor-A")
        val aliceStatement = IdentityStatement(alice, aliceKey, anchor, 1, 0, Long.MAX_VALUE, ByteArray(0))
        val statementReading = PeerIdentityBinding { key, presented ->
            presented.firstOrNull { it.keyId == key }
                ?.let { IdentityResolution.Bound(it.name, it.issuer, it) }
                ?: IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        }
        val rig = Rig(
            aPeer = alice,
            bPeer = PeerId("bob"),
            aCredentials = Credentials(aliceKey, alice, listOf(aliceStatement)),
            bCredentials = Credentials(KeyId("bob-key-1"), PeerId("bob")),
            bBinding = statementReading,
        )
        val remote = rig.publishOnA(CellRef(UUID.randomUUID()))

        remote.announcer shouldBe PeerStamp(alice, AuthLevel.Authenticated, anchor)
        rig.principalFromA() shouldBe Principal.Peer(alice, AuthLevel.Authenticated, anchor)
    }

    @Test
    fun `anonymous announcement has no announcer or peer`() {
        val remote = Rig(aPeer = null, bPeer = PeerId("b")).publishOnA(CellRef(UUID.randomUUID()))

        remote.announcer.shouldBeNull()
        remote.peer.shouldBeNull()
    }

    @Test
    fun `partition and heal rebind the same admission stamp`() {
        val rig = Rig(PeerId("a"), PeerId("b"))
        val ref = CellRef(UUID.randomUUID())
        val original = rig.publishOnA(ref).announcer

        rig.link.partition()
        rig.controller.runToIdle()
        rig.registryB.location(ref).shouldBeNull()

        rig.link.heal()
        rig.controller.runToIdle()
        val healed = (rig.registryB.location(ref) as LocationRegistry.Remote).announcer
        healed shouldBe original
        // Not only equal to the original: two lost stamps are equal too.
        healed shouldBe PeerStamp(PeerId("a"), AuthLevel.TransportVouched, issuer = null)
    }

    @Test
    fun `low level and deferred anonymous publications are unattributed`() {
        val registry = LocationRegistry()
        val lowLevelRef = CellRef(UUID.randomUUID())
        registry.publish(lowLevelRef, InvocationSink { }, peer = PeerId("a"))
        val lowLevel = registry.location(lowLevelRef) as LocationRegistry.Remote
        lowLevel.announcer.shouldBeNull()
        lowLevel.peer shouldBe PeerId("a")

        val deferredRef = CellRef(UUID.randomUUID())
        val incumbent = InvocationSink { }
        val claimant = InvocationSink { }
        registry.publishFromPeer(
            deferredRef,
            incumbent,
            peer = null,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        registry.publishFromPeer(
            deferredRef,
            claimant,
            peer = null,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        )?.deferred shouldBe true
        registry.unpublishFromPeer(
            deferredRef,
            peer = null,
            sink = incumbent,
            anonymousOwnership = AnonymousOwnership.PerConnection,
        ) shouldBe null
        val deferred = registry.location(deferredRef) as LocationRegistry.Remote
        deferred.announcer.shouldBeNull()
        deferred.peer.shouldBeNull()
    }

    @Test
    fun `remote rejects an admission stamp for another peer`() {
        shouldThrow<IllegalArgumentException> {
            LocationRegistry.Remote(
                InvocationSink { },
                peer = PeerId("a"),
                announcer = PeerStamp(PeerId("b")),
            )
        }
    }
}
