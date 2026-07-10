package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
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

    class Side(val registry: LocationRegistry, val bridgeHost: ManagedHost)

    interface FrameInletProxy {
        val inlet: Use<Propagate<ByteArray>>
    }

    interface AnnounceInletProxy {
        val inlet: Use<RegistryAnnounce>
    }

    fun loopback(a: Side, b: Side) {
        val aToB = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(b), PortRef.generate())) }
        val bToA = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(a), PortRef.generate())) }
        val mirrorOnB = spawnMirror(b, toPeer = bToA)
        val mirrorOnA = spawnMirror(a, toPeer = aToB)
        announceTo(a, peerMirror = mirrorOnB, via = aToB)
        announceTo(b, peerMirror = mirrorOnA, via = bToA)
    }

    /** Spawn a [BridgeIngressCell] on [side]'s bridge host; returned api is safe to call from any thread. */
    fun hostIngress(side: Side): Propagate<ByteArray> {
        val ingress = BridgeIngressCell(InvocationSink(side.registry::deliver))
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
     * through [via]. One announcement hook per registry: a registry peers with
     * one remote at a time (multi-peer fan-out is M6+ replication territory).
     */
    fun announceTo(side: Side, peerMirror: CellRef, via: InvocationSink) {
        val announce = (HostedCellProxy.create(peerMirror, via, AnnounceInletProxy::class.java)
                as AnnounceInletProxy).inlet.call
        side.registry.onLocalPublish = { announce.published(it) }
        side.registry.localRefs().forEach(announce::published) // catch-up for pre-peering spawns
    }
}
