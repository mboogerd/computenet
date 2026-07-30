package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.port.Use
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * V4-PEERID — a peer's chosen name reaches the registry.
 *
 * The open item this closes (`97-inspector-plan/90-progress-log.md`): `PeerId`
 * used to dead-end at the transport ingress, so anything identifying a peer had
 * to key on the bridge egress its refs route through — and a reconnect mints a
 * new egress, relabelling the peer's whole hull. What was missing is only that
 * the cell serving a peer's announcements could not see whose they are.
 *
 * `RegistryMirrorCell` is per *connection*, so it can hold the name and stamp
 * every [LocationRegistry.Remote] it installs, on the announcement path
 * (publish/unpublish/link/unlink) and nowhere near the data path. No wire frame
 * changes: the name already crossed in the transport hello.
 *
 * The socket half of this — a real disconnect/reconnect over a re-bound
 * listener — is `:wire`'s `WsPeerIdentityTest`; the loopback here is the
 * deterministic P1 shape, and its `partition`/`heal` is the honest in-process
 * analogue.
 */
class PeerIdentityTest {

    interface StringInletProxy {
        val inlet: Use<Consumer<String>>
    }

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    arrivals += input
                }
            })
        }
    }

    /**
     * A hand-built remote sink, named rather than a bare SAM lambda for the
     * same reason `LocationRegistryHooksTest` names its own: a non-capturing
     * lambda is a JVM singleton and `unpublishRemotes` matches by identity.
     */
    private class PeerSink(private val name: String) : InvocationSink {
        override fun deliver(invocation: HostedPortInvocation) = Unit
        override fun toString() = "peer($name)"
    }

    private class Peers(seed: Long, aName: PeerId?, bName: PeerId?) {
        val controller = SimulationController(seed)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val sideA = Peering.Side(registryA, bridgeA, peer = aName)
        val sideB = Peering.Side(registryB, bridgeB, peer = bName)
        val loopback: Peering.Loopback = Peering.loopback(sideA, sideB)
    }

    private fun LocationRegistry.remote(ref: CellRef): LocationRegistry.Remote =
        location(ref) as LocationRegistry.Remote

    // ------------------------------------------------------- the announced name

    @Test
    fun `each side records the OTHER side's configured name on every mirrored location`() {
        val peers = Peers(seed = 1, aName = PeerId("jvm-a"), bName = PeerId("jvm-b"))

        val theirs = SetCell<String>()
        peers.hostB.managementInlet.call.spawn(theirs)
        val mine = SetCell<String>()
        peers.hostA.managementInlet.call.spawn(mine)
        peers.controller.runToIdle()

        // A learned B's refs from B's announcements, so they carry B's name —
        // the mirror on A is the one B announces into, and its peer is b.peer
        peers.registryA.remote(theirs.ref).peer shouldBe PeerId("jvm-b")
        // ... and symmetrically, which is the property that makes two
        // side-by-side inspectors legible: neither side invents a label
        peers.registryB.remote(mine.ref).peer shouldBe PeerId("jvm-a")
    }

    @Test
    fun `a ref published before the peering is announced during catch-up, already named`() {
        // announceTo's `localRefs().forEach(announce::published)` is the
        // earliest announcement a connection can carry; if the name were bound
        // late it would be the one to miss it
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val controller = SimulationController(7)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val bridgeA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val bridgeB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)

        val theirs = SetCell<String>()
        hostB.managementInlet.call.spawn(theirs)
        controller.runToIdle()

        Peering.loopback(
            Peering.Side(registryA, bridgeA, peer = PeerId("jvm-a")),
            Peering.Side(registryB, bridgeB, peer = PeerId("jvm-b")),
        )
        controller.runToIdle()

        registryA.remote(theirs.ref).peer shouldBe PeerId("jvm-b")
    }

    @Test
    fun `an anonymous peering records no peer, and everything else is unchanged`() {
        val peers = Peers(seed = 2, aName = null, bName = null)

        val theirs = SetCell<String>()
        peers.hostB.managementInlet.call.spawn(theirs)
        peers.controller.runToIdle()

        val location = peers.registryA.remote(theirs.ref)
        // the pre-V4-PEERID shape verbatim: a Remote that names only its sink,
        // which is what keeps the inspector's derived `peer-<id>` label honest
        location.peer.shouldBeNull()
        location.sink shouldBe peers.loopback.aToB
        location shouldBe LocationRegistry.Remote(peers.loopback.aToB)
    }

    @Test
    fun `a named remote still routes, and still unpublishes by sink identity`() {
        val peers = Peers(seed = 3, aName = PeerId("jvm-a"), bName = PeerId("jvm-b"))
        val theirs = CollectingCell()
        peers.hostB.managementInlet.call.spawn(theirs)
        peers.controller.runToIdle()

        // the name is recorded, never consulted by routing: an A-side proxy
        // reaches B exactly as it did before
        val remote = (HostedCellProxy.create(theirs.ref, peers.registryA, StringInletProxy::class.java)
                as StringInletProxy).inlet.call
        remote.provide("milk")
        peers.controller.runToIdle()
        theirs.arrivals shouldBe listOf("milk")

        // `unpublishRemotes` matches `sink === via`; the extra field neither
        // widens nor narrows that match
        peers.registryA.unpublishRemotes(via = peers.loopback.aToB)
        peers.registryA.location(theirs.ref).shouldBeNull()
    }

    // ------------------------------------------------ stability across a re-peer

    @Test
    fun `partition and heal bring the peer back under the same identity`() {
        val peers = Peers(seed = 4, aName = PeerId("jvm-a"), bName = PeerId("jvm-b"))
        val theirs = SetCell<String>()
        peers.hostB.managementInlet.call.spawn(theirs)
        peers.controller.runToIdle()
        peers.registryA.remote(theirs.ref).peer shouldBe PeerId("jvm-b")

        peers.loopback.partition()
        peers.controller.runToIdle()
        peers.registryA.location(theirs.ref).shouldBeNull()

        peers.loopback.heal()
        peers.controller.runToIdle()
        peers.registryA.remote(theirs.ref).peer shouldBe PeerId("jvm-b")
    }

    // --------------------------------------------------------------- the late bind

    @Test
    fun `a late-bound mirror names announcements served after the bind, and re-binds on a repeated hello`() {
        // exactly what WsTransport.Session does: spawn the mirror before any
        // peer name exists (its ref has to go out in our hello), then assign
        // the name in onText, before Peering.announceTo
        val registry = LocationRegistry()
        val bridge = ManagedHost(registry = registry)
        val egress = BridgeEgressCell()
        val mirror = Peering.spawnMirror(Peering.Side(registry, bridge), toPeer = egress)
        mirror.peer.shouldBeNull()

        mirror.peer = PeerId("jvm-b")
        val first = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(first)
        registry.remote(first).peer shouldBe PeerId("jvm-b")

        // a re-hello carrying the same name (the client keeps ONE session, and
        // hence one mirror, across reconnects): a no-op in effect
        mirror.peer = PeerId("jvm-b")
        val second = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(second)
        registry.remote(second).peer shouldBe PeerId("jvm-b")
        registry.remote(first).peer shouldBe PeerId("jvm-b")

        // and no stale name leaks if the far end comes back as someone else
        mirror.peer = PeerId("jvm-c")
        val third = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(third)
        registry.remote(third).peer shouldBe PeerId("jvm-c")
        // the already-installed locations are records of what was announced
        // then, not live views of the current binding
        registry.remote(first).peer shouldBe PeerId("jvm-b")
    }

    @Test
    fun `an unbound mirror publishes anonymously — the state the bind must precede`() {
        // the negative half of the happens-before argument, pinned rather than
        // argued: if a transport announced before binding, this is what the
        // registry would hold. WsTransport orders the bind first (see
        // RegistryMirrorCell.peer), and `WsPeerIdentityTest` asserts the
        // earliest real announcement is already named.
        val registry = LocationRegistry()
        val bridge = ManagedHost(registry = registry)
        val mirror = Peering.spawnMirror(Peering.Side(registry, bridge), toPeer = BridgeEgressCell())

        val early = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(early)
        registry.remote(early).peer.shouldBeNull()

        mirror.peer = PeerId("jvm-b")
        val late = CellRef(UUID.randomUUID())
        mirror.inlet.call.published(late)
        registry.remote(late).peer shouldBe PeerId("jvm-b")
        registry.remote(late) shouldNotBe registry.remote(early)
    }

    // ------------------------------------------------------------ the default arg

    @Test
    fun `the remote publish overload defaults to an anonymous peer`() {
        val registry = LocationRegistry()
        val ref = CellRef(UUID.randomUUID())
        val sink = PeerSink("hand-built")

        // the call shape every pre-V4-PEERID caller uses, unchanged
        registry.publish(ref, sink)
        registry.remote(ref) shouldBe LocationRegistry.Remote(sink)
        registry.remote(ref).peer.shouldBeNull()

        registry.publish(ref, sink, PeerId("jvm-b"))
        registry.remote(ref).peer shouldBe PeerId("jvm-b")
    }
}
