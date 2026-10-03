package civictech.demo.beadsmirror

import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransports
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.runtime.Manifest
import civictech.runtime.NodeSpec
import civictech.runtime.Runtime
import java.nio.file.Path

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

/**
 * The optional two-node settings of a [BeadsMirrorConfig]: everything the
 * mirror needs to gossip its projector state to one peer, and nothing else.
 *
 * @param rigName the rig's fixed name. **Both nodes must be given the
 *   identical string** — it is hashed into the shared logical `CellRef`s
 *   ([MirrorCellRefs]) and is therefore the entire coordination mechanism, so
 *   a typo on one side mints an unrelated logical cell and the two nodes
 *   silently never link.
 * @param wire which end of the socket this node is; also what fixes its
 *   [MirrorCellRefs.role], and hence its replica `instanceId`.
 */
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
 * 2. apply or recover [MirrorGraph.spec] through that runtime;
 * 3. attach the application projector to the hosted cells;
 * 4. [connect], opening transport endpoints only after the replicas exist.
 *
 * A rig passes one exact [PeerTransport] instance to both nodes. This matters
 * for the discovery binding, whose listener and dialler share formation state,
 * and lets tests wrap the actual binding used by [Runtime.Node.open].
 */
class MirrorPeering(
    val settings: MirrorPeeringSettings,
    private val runDir: Path,
    private val transport: PeerTransport = PeerTransports.forScheme(WS_SCHEME),
) : AutoCloseable {

    /** The shared logical refs this node's projector cells are built under. */
    val refs: MirrorCellRefs = settings.refs

    private val runtime: Runtime.Node = Runtime.boot(
        manifest(),
        LOCAL_NODE,
        MirrorGraph.spec(refs, replicated = true),
        transport,
    )

    /** The same hosted graph seam solo mode exposes. */
    val graph: MirrorGraph = MirrorGraph.runtime(runtime, refs).also {
        if (it.recovered) it.checkpoint()
    }

    /** Test seam for the one local replica per logical id invariant after a respawn. */
    internal val registry get() = runtime.registry

    /** The listener's granted address, or `null` in dial mode / before [connect]. */
    val boundAddress: PeerAddress? get() = runtime.boundAddress

    /** The dialled endpoint, or `null` on a listener / before [connect]. */
    val connection: PeerConnection? get() = runtime.connections.singleOrNull()

    /**
     * Open this node's manifest endpoints after application wiring is installed.
     *
     * **Why `Peering.chainOnReannounce` is not called here.** Task
     * computenet-7em.1.2 prescribes it, transposed from demo/shopping, and it
     * does not transpose: `chainOnReannounce` re-fires the on-link catch-up of
     * a link the *application* created, and shopping has such links because it
     * chains its unions into the peer's counterparts by hand
     * (`itemsUnion.outlet.streamTo(routedDelta(peerRef))`). This rig has no
     * application-created link at all — every link in it is minted inside
     * [Replication.replicate]'s linker, whose `linked` map is private to the
     * kernel — so the only `chained` map expressible here is the empty one, and
     * `chainOnReannounce(registry, emptyMap())` registers a hook that can never
     * match a ref. It would be a call that reads like a guarantee and provides
     * none.
     *
     * The guarantee itself is not missing; it is already in the kernel, one
     * layer down. [Replication]'s `init` installs `registry.onPublish { ref ->
     * linkOut(ref) }`, `linkOut` calls `maybeLink` for every local replica, and
     * `maybeLink`'s first branch — for a pair that is *already* linked, which
     * is exactly the reconnect case — does
     * `cell.outlet.linking.fireLinked(link)` and returns. That is
     * `chainOnReannounce`'s body, applied to the gossip mesh's own links, with
     * the same "state-as-delta unicast is idempotent, so a redundant re-fire
     * costs one wasted delta at worst" argument in its comment. So a returning
     * peer does get the deltas its dying socket swallowed re-served; adding
     * `chainOnReannounce` on top would change nothing.
     */
    fun connect() = runtime.open()

    /** Close transport endpoints and drain the runtime-owned bridge and application host. */
    override fun close() = graph.close()

    private fun manifest(): Manifest {
        val local = NodeSpec(
            transport = transport.scheme,
            listen = (settings.wire as? MirrorWire.Listen)?.let(::listenAddress),
            dial = if (settings.wire is MirrorWire.Dial) listOf(REMOTE_NODE) else emptyList(),
            replica = refs.instanceId,
            peerName = settings.role,
            journalDir = runDir.toString(),
            journalTopology = true,
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
