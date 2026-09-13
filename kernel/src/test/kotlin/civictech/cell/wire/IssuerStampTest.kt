package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.IssuerId
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Feature `computenet-5y8t.1`, task `.1.2`, decision D5/D12: the issuer rides
 * the same per-connection stamp as `id` and `auth` — [PeerStamp][civictech.cell.link.PeerStamp]
 * -> `HostedPortInvocation.peerIssuer` -> [Principal.Peer] -> [currentPrincipal].
 *
 * Cases 1-2 pin [CurrentPeer.with]'s direct contract (the ambient half); case
 * 3 pins the whole chain through a wire-encoded [BridgeIngressCell] delivery
 * (the carrier half); case 4 is the non-vacuousness mutation.
 *
 * Every case here leaves the loopback and both transports at their
 * pre-existing null-issuer default (tasks `.1.3`/`.1.4`); this file asserts
 * only that the carrier CAN carry a non-null issuer when a caller supplies
 * one directly to [CurrentPeer.with] or [Peering.hostIngress].
 */
class IssuerStampTest {

    @Test
    fun `CurrentPeer with an issuer stamps currentPrincipal with that issuer`() {
        val result = CurrentPeer.with(PeerId("p"), AuthLevel.Authenticated, IssuerId("anchor-A")) {
            currentPrincipal()
        }
        result shouldBe Principal.Peer(PeerId("p"), AuthLevel.Authenticated, IssuerId("anchor-A"))
    }

    @Test
    fun `CurrentPeer with no issuer argument stamps a null issuer, the two-argument spelling stays equal`() {
        var observedStampIssuer: IssuerId? = IssuerId("never-set")
        val result = CurrentPeer.with(PeerId("p"), AuthLevel.Authenticated) {
            observedStampIssuer = CurrentPeer.stamp()?.issuer
            currentPrincipal()
        }
        result shouldBe Principal.Peer(PeerId("p"), AuthLevel.Authenticated)
        observedStampIssuer.shouldBeNull()
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
    }

    @Test
    fun `a frame arriving through a hostIngress built with an issuer stamps currentPrincipal with that issuer`() {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val side = Peering.Side(registry, ManagedHost(scheduler = controller.scheduler(), registry = registry))
        val probe = PrincipalProbeCell()
        val ingress = Peering.hostIngress(
            side,
            fromPeer = PeerId("p"),
            fromPeerAuth = AuthLevel.Authenticated,
            fromPeerIssuer = IssuerId("anchor-A"),
        )
        side.bridgeHost.managementInlet.call.spawn(probe)
        controller.runToIdle()

        ingress.propagate(WireCodec.encode(protocolFrame(probe.ref)))
        controller.runToIdle()

        probe.principals.last() shouldBe Principal.Peer(PeerId("p"), AuthLevel.Authenticated, IssuerId("anchor-A"))
    }
}
