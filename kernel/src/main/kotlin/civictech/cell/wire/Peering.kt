package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import civictech.gen.wire.Contract
import java.util.UUID

/**
 * Peer location announcements (spec 41 point 3): "ref X lives here". Ordinary
 * wire traffic — an announcement is a port invocation on the peer's
 * [RegistryMirrorCell], crossing the same bridge as data.
 */
@Contract(management = true)
interface RegistryAnnounce {
    fun published(ref: CellRef)
}

/**
 * Receives a peer's announcements and mirrors them into the local registry as
 * [LocationRegistry.Remote] locations routed through [toPeer] — after which
 * local senders reach the remote ref transparently (parked traffic replays).
 */
class RegistryMirrorCell(
    private val registry: LocationRegistry,
    private val toPeer: InvocationSink,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<RegistryAnnounce>())

    init {
        inlet.serve(object : RegistryAnnounce {
            override fun published(ref: CellRef) = registry.publish(ref, toPeer)
        })
    }
}

/**
 * The building blocks of a peer connection — a full-duplex bridge (an
 * egress/ingress pair per direction) plus registry mirroring — and their
 * in-process [loopback] composition: the deterministic P1 shape of a peer
 * connection. A transport (`:wire`, M5.5) composes the same blocks, replacing
 * only the frame link with a socket.
 */
object Peering {

    class Side(
        val registry: LocationRegistry,
        val bridgeHost: ManagedHost,
        /** This side's transport identity (M8.2); null = anonymous. */
        val peer: PeerId? = null,
        /** Deny-by-default admission (M8.3): peers accepted at this boundary; null = open. */
        val allow: Set<PeerId>? = null,
    ) {
        fun admits(peer: PeerId?): Boolean = allow == null || peer in allow
    }

    interface FrameInletProxy {
        val inlet: Use<Propagate<ByteArray>>
    }

    interface AnnounceInletProxy {
        val inlet: Use<RegistryAnnounce>
    }

    /**
     * Handle on an established loopback peering — enough to sever it
     * ([partition]) and re-establish it ([heal]): the partition/anti-entropy
     * seam of M7.4. Disconnect drops Remote locations (senders park, spec 33);
     * heal re-announces, replaying parked traffic and re-syncing state via
     * the ordinary catch-up path.
     */
    class Loopback(private val a: Side, private val b: Side, val aToB: InvocationSink, val bToA: InvocationSink,
                   private val mirrorOnA: CellRef, private val mirrorOnB: CellRef) {
        fun partition() {
            a.registry.unpublishRemotes(aToB)
            b.registry.unpublishRemotes(bToA)
        }

        fun heal() {
            announceTo(a, peerMirror = mirrorOnB, via = aToB)
            announceTo(b, peerMirror = mirrorOnA, via = bToA)
        }
    }

    fun loopback(a: Side, b: Side): Loopback {
        val aToB = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(b, fromPeer = a.peer), PortRef.generate())) }
        val bToA = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(a, fromPeer = b.peer), PortRef.generate())) }
        val mirrorOnB = spawnMirror(b, toPeer = bToA)
        val mirrorOnA = spawnMirror(a, toPeer = aToB)
        announceTo(a, peerMirror = mirrorOnB, via = aToB)
        announceTo(b, peerMirror = mirrorOnA, via = bToA)
        return Loopback(a, b, aToB, bToA, mirrorOnA, mirrorOnB)
    }

    /** Spawn a [BridgeIngressCell] on [side]'s bridge host; returned api is safe to call from any thread. */
    fun hostIngress(side: Side, fromPeer: PeerId? = null): Propagate<ByteArray> {
        val ingress = BridgeIngressCell(InvocationSink(side.registry::deliver), peer = fromPeer, admit = side::admits)
        side.bridgeHost.managementInlet.call.spawn(ingress)
        return (HostedCellProxy.create(ingress.ref, side.registry, FrameInletProxy::class.java)
                as FrameInletProxy).inlet.call
    }

    /** Spawn the mirror that turns the peer's announcements into Remote locations routed via [toPeer]. */
    fun spawnMirror(side: Side, toPeer: InvocationSink): CellRef {
        val mirror = RegistryMirrorCell(side.registry, toPeer)
        side.bridgeHost.managementInlet.call.spawn(mirror)
        return mirror.ref
    }

    /**
     * Announce [side]'s local publishes — current and future — to [peerMirror]
     * through [via]. Announcement hooks are multicast (M7.2): a registry may
     * peer with several remotes at once; each peer only ever hears about
     * *local* refs, so nothing loops or forwards second-hand locations.
     */
    fun announceTo(side: Side, peerMirror: CellRef, via: InvocationSink): AutoCloseable {
        val announce = (HostedCellProxy.create(peerMirror, via, AnnounceInletProxy::class.java)
                as AnnounceInletProxy).inlet.call
        val registration = side.registry.onLocalPublish { announce.published(it) }
        side.registry.localRefs().forEach(announce::published) // catch-up for pre-peering spawns
        return registration
    }
}
