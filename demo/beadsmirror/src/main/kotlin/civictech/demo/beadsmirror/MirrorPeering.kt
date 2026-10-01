package civictech.demo.beadsmirror

import civictech.cell.graph.GraphSpec
import civictech.cell.replication.Replication
import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.runtime.Manifest
import civictech.runtime.NodeSpec
import civictech.runtime.Runtime

/**
 * Which end of the peering this node is. The endpoint flag implies the role;
 * there is no second role flag that can disagree with it.
 */
sealed interface MirrorWire {

    /** Listen on [wsPort]. For `ws`, `0` asks the OS for a free port. */
    data class Listen(val wsPort: Int) : MirrorWire

    /** Dial [uri], which is the listener's [PeerAddress.text]. */
    data class Dial(val uri: String) : MirrorWire {
        companion object {
            /** Discovery has no handed-over endpoint; this fixed address selects the local discovery binding. */
            const val DISCOVERED: String = "iroh+mdns://"
        }
    }
}

/** The optional two-node settings of a [BeadsMirrorConfig]. */
data class MirrorPeeringSettings(val rigName: String, val wire: MirrorWire) {

    /** [MirrorCellRefs.LISTENER] for [MirrorWire.Listen], [MirrorCellRefs.DIALER] for [MirrorWire.Dial]. */
    val role: String
        get() = when (wire) {
            is MirrorWire.Listen -> MirrorCellRefs.LISTENER
            is MirrorWire.Dial -> MirrorCellRefs.DIALER
        }

    /** The shared logical refs this node builds its projector's two cells under. */
    val refs: MirrorCellRefs get() = MirrorCellRefs(rigName, role)
}

/**
 * The two-node mode of [BeadsMirrorApp]. [Runtime.boot] owns this node's
 * registry, host and bridge lifecycle; this class adds the mirror's
 * application-owned [Replication] wiring between boot and [Runtime.Node.open].
 *
 * The order is load-bearing:
 *
 * 1. boot the runtime node;
 * 2. construct [Replication], installing its registry hooks;
 * 3. [attach] the projector replicas to [Runtime.Node.mainHost];
 * 4. [connect], opening transport endpoints only after the replicas exist.
 *
 * A rig passes one exact [PeerTransport] instance to both nodes. This matters
 * for the discovery binding, whose listener and dialler share formation state,
 * and lets tests wrap the actual binding used by [Runtime.Node.open].
 */
class MirrorPeering(
    val settings: MirrorPeeringSettings,
    private val transport: PeerTransport = PeerTransports.forScheme(WS_SCHEME),
) : AutoCloseable {

    /** The shared logical refs this node's projector cells are built under. */
    val refs: MirrorCellRefs = settings.refs

    private val runtime: Runtime.Node = Runtime.boot(
        manifest(),
        LOCAL_NODE,
        GraphSpec(emptyList()),
        transport,
    )

    /** Installs the registry hooks before [attach] publishes either replica. */
    private val replication = Replication(runtime.registry)

    /** The projector whose cells are currently replicated, or `null` before [attach]. */
    private var attached: MirrorProjector? = null

    /** Test seam: which projector's cells the mesh currently gossips. */
    internal val attachedProjector: MirrorProjector? get() = attached

    /** The listener's granted address, or `null` in dial mode / before [connect]. */
    val boundAddress: PeerAddress? get() = runtime.boundAddress

    /** The dialled endpoint, or `null` on a listener / before [connect]. */
    val connection: PeerConnection? get() = runtime.connections.singleOrNull()

    /** Spawns [projector]'s two cells as replicas on this node's runtime host. */
    fun attach(projector: MirrorProjector) {
        check(attached == null) { "MirrorPeering.attach is a one-shot; use rebind for a re-baseline swap" }
        replication.replicate(projector.cell, runtime.mainHost)
        replication.replicate(projector.edges, runtime.mainHost)
        attached = projector
    }

    /**
     * Re-points the replica mesh at a re-baselined projector. Tag state is not
     * carried: the baseline is the authoritative replacement, and this mirror
     * mints every delta's dot from its feed position rather than a cell-local
     * counter.
     */
    fun rebind(next: MirrorProjector) {
        val incumbent = attached ?: return
        if (incumbent === next) return
        replication.rebind(incumbent.cell, next.cell, runtime.mainHost, carryTagState = false)
        replication.rebind(incumbent.edges, next.edges, runtime.mainHost, carryTagState = false)
        attached = next
    }

    /** Open this node's manifest endpoints after application wiring is installed. */
    fun connect() = runtime.open()

    /** Close transport endpoints and drain the runtime-owned bridge and application host. */
    override fun close() = runtime.close()

    private fun manifest(): Manifest {
        val local = NodeSpec(
            transport = transport.scheme,
            listen = (settings.wire as? MirrorWire.Listen)?.let(::listenAddress),
            dial = if (settings.wire is MirrorWire.Dial) listOf(REMOTE_NODE) else emptyList(),
            replica = refs.instanceId,
            peerName = settings.role,
        )
        val nodes = when (val wire = settings.wire) {
            is MirrorWire.Listen -> mapOf(LOCAL_NODE to local)
            is MirrorWire.Dial -> mapOf(
                LOCAL_NODE to local,
                REMOTE_NODE to NodeSpec(transport = transport.scheme, listen = wire.uri),
            )
        }
        return Manifest(nodes)
    }

    private fun listenAddress(listen: MirrorWire.Listen): String = when (transport.scheme) {
        WS_SCHEME -> "$WS_SCHEME://localhost:${listen.wsPort}"
        IROH_SCHEME -> "$IROH_SCHEME://"
        DiscoveredIrohPeerTransport.SCHEME -> DiscoveredIrohPeerTransport.ADDRESS
        else -> throw IllegalArgumentException(
            "beadsmirror cannot derive a listen address for transport scheme '${transport.scheme}' from --listen ${listen.wsPort}",
        )
    }

    private companion object {
        const val LOCAL_NODE = "mirror"
        const val REMOTE_NODE = "peer"
        const val WS_SCHEME = "ws"
        const val IROH_SCHEME = "iroh"
    }
}
