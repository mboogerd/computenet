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
 * Wires two registries into one graph: a full-duplex bridge (two
 * egress/ingress pairs) plus registry mirroring in both directions. The
 * loopback form links the bridges in-process — the deterministic P1 shape of
 * a peer connection; M5.5's transport replaces only the frame link with a
 * socket.
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
        val aToB = frames(from = a, to = b)
        val bToA = frames(from = b, to = a)
        mirror(announcer = a, announcerEgress = aToB, listener = b, returnEgress = bToA)
        mirror(announcer = b, announcerEgress = bToA, listener = a, returnEgress = aToB)
    }

    /** One direction of the bridge: egress on [from], hosted ingress on [to], linked in-process. */
    private fun frames(from: Side, to: Side): BridgeEgressCell {
        val egress = BridgeEgressCell()
        val ingress = BridgeIngressCell(InvocationSink(to.registry::deliver))
        to.bridgeHost.managementInlet.call.spawn(ingress)
        val ingressApi = (HostedCellProxy.create(ingress.ref, to.registry, FrameInletProxy::class.java)
                as FrameInletProxy).inlet.call
        egress.outlet.subscribe(Use.fixed(ingressApi, PortRef.generate()))
        return egress
    }

    /** [announcer]'s local publishes become Remote locations on [listener], routed via [returnEgress]. */
    private fun mirror(
        announcer: Side,
        announcerEgress: BridgeEgressCell,
        listener: Side,
        returnEgress: BridgeEgressCell,
    ) {
        val mirror = RegistryMirrorCell(listener.registry, toPeer = returnEgress)
        listener.bridgeHost.managementInlet.call.spawn(mirror)
        val announce = (HostedCellProxy.create(mirror.ref, announcerEgress, AnnounceInletProxy::class.java)
                as AnnounceInletProxy).inlet.call
        announcer.registry.onLocalPublish = { announce.published(it) }
        announcer.registry.localRefs().forEach(announce::published) // catch-up for pre-peering spawns
    }
}
