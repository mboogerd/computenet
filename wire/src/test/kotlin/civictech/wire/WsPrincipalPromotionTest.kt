package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.identity.FilePeerKeyStore
import civictech.identity.PeerIdentity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-ssa.3.2` — BS-01's **delivery** clause, the half
 * `WsAuthenticatedHelloTest` deliberately stops short of
 * (`[DSC1-HELLO-05]`, `[DSC1-WIRE-05]` principal half).
 *
 * That test proves the handshake *reports* `AuthLevel.Authenticated` at the
 * transport. This one proves the level reaches the place a policy can act on
 * it: a cell on the receiving side, reading `currentPrincipal()` inside a
 * delivery that came off the socket, observes
 * `Principal.Peer(derivedId, Authenticated)` — and observes `TransportVouched`
 * when the same crossing was not authenticated.
 *
 * The third test is the [DSC1-WIRE-05] parity claim: a `Peering.loopback` and a
 * socket peering built from the *same two `Peering.Side` configurations* yield
 * the same `Principal` — same id, same level — even though only one of them
 * ever exchanged a hello. That is the property that keeps the in-process
 * composition an honest model of the distributed one.
 *
 * The probe is a `PORT_PROTOCOL` frame for the reason
 * `civictech.cell.wire.LoopbackPrincipalTest` records: it is the only
 * wire-encodable delivery type whose dispatch `ManagedHost` runs under the
 * stamped peer.
 */
class WsPrincipalPromotionTest {

    @TempDir
    lateinit var keyDirs: Path

    /** Records the ambient [Principal] of every attention assertion it is handed. */
    class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()

        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    /**
     * A peering side, optionally key-holding. When [keyed], the side's own
     * [Peering.Side.peer] is set to its key fingerprint — the configuration the
     * loopback parity case needs, and a no-op on the socket path, where the
     * bound id is derived from the presented key regardless.
     */
    private inner class Stack(name: String, val keyed: Boolean) {
        val identity: PeerIdentity? =
            if (keyed) FilePeerKeyStore(keyDirs.resolve(name)).loadOrGenerate() else null
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val peerId: PeerId = identity?.peerId ?: PeerId(name)
        val side = Peering.Side(
            registry,
            bridgeHost,
            peer = peerId,
            auth = if (keyed) PeerAuthPolicy.RequireAuthenticated() else PeerAuthPolicy.Open,
            credentials = identity?.asPeerCredentials(),
            announcementSigning = if (keyed) socketAnnouncementSigning() else null,
            announcementVerification = if (keyed) socketAnnouncementVerification() else null,
        )
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    private fun protocolFrame(target: CellRef) = HostedPortInvocation(
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
     * Connect [client] to a listener on [server], let the probe's location
     * reach the client, then send one attention assertion over the socket and
     * return the principal the probe observed.
     */
    private fun principalOverSocket(server: Stack, client: Stack): Principal {
        val probe = PrincipalProbeCell()
        server.host.managementInlet.call.spawn(probe)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("the dialer learned the listener's probe") {
                client.registry.location(probe.ref) is LocationRegistry.Remote
            }
            // Routed by the client's registry, so it takes the connection's own
            // egress: encoded here, decoded and stamped by the listener's
            // `BridgeIngressCell`, dispatched by the listener's host.
            client.registry.deliver(protocolFrame(probe.ref))
            await("the assertion crossed and was dispatched") { probe.principals.isNotEmpty() }
            return probe.principals.last()
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    /** The same assertion over an in-process [Peering.loopback] of two sides. */
    private fun principalOverLoopback(server: Stack, client: Stack): Principal {
        val probe = PrincipalProbeCell()
        server.host.managementInlet.call.spawn(probe)
        val loopback = Peering.loopback(server.side, client.side)
        await("the loopback peering settled") { client.registry.location(probe.ref) is LocationRegistry.Remote }
        loopback.bToA.deliver(protocolFrame(probe.ref))
        await("the assertion was dispatched") { probe.principals.isNotEmpty() }
        loopback.partition()
        return probe.principals.last()
    }

    @Test
    fun `a delivery over a socket crossing admitted at Authenticated reads Peer(derivedId, Authenticated)`() {
        val server = Stack("promotion-server", keyed = true)
        val client = Stack("promotion-client", keyed = true)

        principalOverSocket(server, client) shouldBe
            Principal.Peer(client.identity!!.peerId, AuthLevel.Authenticated)
    }

    @Test
    fun `a delivery over an unauthenticated socket crossing does NOT read Authenticated`() {
        // The negative the acceptance criteria require, over the real transport:
        // two `Open` sides with no keypairs at all take the legacy HELLO row and
        // are admitted at TransportVouched, byte for byte as before DSC1
        // (`[DSC1-WIRE-06]`).
        val server = Stack("vouched-server", keyed = false)
        val client = Stack("vouched-client", keyed = false)

        principalOverSocket(server, client) shouldBe
            Principal.Peer(PeerId("vouched-client"), AuthLevel.TransportVouched)
    }

    @Test
    fun `DSC1-WIRE-05 - loopback and socket with the same configuration yield the same Principal`() {
        // Same key store paths on both runs, so the two peerings are built from
        // materially identical `Peering.Side` configurations — same names, same
        // keys, same policy — differing only in whether a socket is involved.
        val overSocket = principalOverSocket(
            Stack("parity-server", keyed = true),
            Stack("parity-client", keyed = true),
        )
        val overLoopback = principalOverLoopback(
            Stack("parity-server", keyed = true),
            Stack("parity-client", keyed = true),
        )

        overLoopback shouldBe overSocket
        overSocket shouldBe Principal.Peer(
            FilePeerKeyStore(keyDirs.resolve("parity-client")).loadOrGenerate().peerId,
            AuthLevel.Authenticated,
        )
    }
}
