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
     * Re-points the replica mesh from the currently attached projector's cells
     * at [next]'s — the re-baseline swap seam. A no-op before [attach], so an
     * app that swaps before it has peered (it does not, but the ordering is not
     * this class's to enforce) is not broken by it.
     *
     * Throws (out of [MirrorState.swap], and so out of the re-baseline that
     * triggered it) when [next]'s cells do not carry the incumbent's
     * `CellRef`s — `Replication.rebind`'s own precondition. That is the right
     * failure: a projector rebuilt under different refs is a *different*
     * logical cell, and continuing would leave this node silently gossiping
     * nothing while still serving a fold. Task computenet-7em.1.1 is what
     * makes it not fire, by threading [refs] through every rebuild site.
     *
     * **`carryTagState = false`, deliberately, against the parameter's own
     * default.** `Replication.rebind` defaults to restoring the incumbent's
     * [civictech.cell.Stateful] snapshot into the candidate, because its
     * original use — crash-recovery promotion — wants the incumbent's state and its
     * tag counter continued. A re-baseline wants the exact opposite: the
     * discard *is* the operation ([MirrorState]). Carrying the snapshot would
     * restore every key of the projector the rebuild just replaced, so an
     * issue absent from the fresh `bd export` would come back as a zombie,
     * and — after a history compaction, where commit heights restart *lower*
     * than the pre-gap ones — the carried dots would outrank the baseline's
     * and win last-writer-wins outright. That is precisely the hazard
     * [MirrorState]'s class doc says the swap exists to avoid.
     *
     * Turning it off is safe here for the reason it is normally unsafe
     * elsewhere: the default exists to continue a cell's *internal* tag
     * counter, and this mirror never drives one. Every delta is minted
     * outside the cell by
     * [civictech.demo.beadsmirror.projector.DotMinter] from the record's feed
     * position and injected through the `Replicable` delta seam — the cells'
     * own `MapOps`/`SetOps` inlets are deliberately never used (see
     * [MirrorProjector]) — so there is no counter to restart and no fresh-epoch
     * collision to reproduce.
     */
    fun rebind(next: MirrorProjector) {
        val incumbent = attached ?: return
        if (incumbent === next) return
        replication.rebind(incumbent.cell, next.cell, runtime.mainHost, carryTagState = false)
        replication.rebind(incumbent.edges, next.edges, runtime.mainHost, carryTagState = false)
        attached = next
    }

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
