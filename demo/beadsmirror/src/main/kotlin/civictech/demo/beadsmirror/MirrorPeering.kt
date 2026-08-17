package civictech.demo.beadsmirror

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.wire.WsTransport
import java.net.URI

/**
 * Which end of the socket this node is (feature computenet-7em.1, task
 * computenet-7em.1.2). The role is *implied* by which endpoint the operator
 * gave — there is no separate `--role` flag to get out of sync with the
 * flags that actually decide it.
 */
sealed interface MirrorWire {

    /**
     * This node listens on [wsPort]. `0` asks for any free port, in which case
     * only [MirrorPeering.boundWsPort] knows which one was granted — that, and
     * never [wsPort], is what a supervising test or a dialer must be told
     * (computenet-dqy.25).
     */
    data class Listen(val wsPort: Int) : MirrorWire

    /** This node dials [uri] (a `ws://host:port` endpoint a listener announced). */
    data class Dial(val uri: String) : MirrorWire
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
 * The two-node mode of [BeadsMirrorApp]: the mirror's [MirrorProjector.cell]
 * and [MirrorProjector.edges] spawned as replicas of one logical cell and
 * gossiped to one peer over the real `:wire` WebSocket transport.
 *
 * **Constructed only when [BeadsMirrorConfig.peering] is set.** This class is
 * the *only* place in the module that names a `:wire` or a
 * `civictech.cell.replication` / `civictech.cell.wire` type, so a solo-mode
 * run never loads one: the registry, the hosts, the [Replication] linker and
 * the transport are all instance state of an object solo mode never creates.
 *
 * **Ordering is load-bearing, and it is why this is three calls rather than
 * one.** The order the app must drive is:
 *
 * 1. construct — [Replication]'s constructor installs the registry
 *    `onPublish`/`onUnpublish` hooks the whole mesh is driven by
 *    (`Replication.kt` `init`), so it must exist before any announcement,
 *    local or peer, can land;
 * 2. [attach] — the two mirror cells are spawned as replicas on [host], which
 *    publishes them on the registry;
 * 3. [connect] — only now does a socket exist, so the peer's first
 *    announcement is guaranteed to arrive with the hooks installed and the
 *    local replicas already published. This is demo/shopping's
 *    replicate-before-peer rule (`DemoApp`'s `replication` field comment),
 *    made explicit because here the cells do not exist until a projector does.
 *
 * **The swap seam.** A re-baseline replaces the projector — and its cell
 * objects — wholesale ([MirrorState]), on three paths (first start, restart,
 * checkpoint-gone). The replacement cells carry the *same* `CellRef`s (task
 * computenet-7em.1.1 threads [MirrorCellRefs] through the rebuild), so
 * [rebind] can hand them to [Replication.rebind], which drops the incumbent's
 * outbound gossip and republishes the candidate under the same ref — keeping
 * this node a member throughout, and parking inbound gossip that arrives
 * during the object swap at the registry rather than dropping it. Without
 * that call the mesh would keep gossiping into the discarded projector and the
 * served fold would silently stop converging.
 *
 * **On `Peering.chainOnReannounce`.** This rig deliberately does not call it,
 * and the omission is not an oversight — see [connect].
 */
class MirrorPeering(val settings: MirrorPeeringSettings) : AutoCloseable {

    /** The shared logical refs this node's projector cells are built under. */
    val refs: MirrorCellRefs = settings.refs

    private val registry = LocationRegistry()

    /**
     * The replica mesh linker. Constructed here, in a property initializer, for
     * the reason demo/shopping states: its constructor installs the registry
     * hooks, so building it first means every announcement this process will
     * ever see arrives after the hooks are in place.
     */
    private val replication = Replication(registry)

    /** The host the two mirror replicas live on. */
    private val host = ManagedHost(registry = registry)

    /** The peering bridge's own host — its egress/ingress cells publish here too. */
    private val bridgeHost = ManagedHost(registry = registry)

    private var listener: WsTransport.WsListener? = null
    private var connection: WsTransport.WsConnection? = null

    /**
     * The projector whose cells are currently replicated, or `null` before
     * [attach]. Held so [rebind] knows the incumbent without the caller having
     * to remember it — the caller is [MirrorState]'s swap hook, which sees only
     * the new projector on some paths.
     */
    private var attached: MirrorProjector? = null

    /** **Test seam.** Which projector's cells the mesh currently gossips. */
    internal val attachedProjector: MirrorProjector? get() = attached

    /**
     * The port the listener actually bound, or `null` in dial mode / before
     * [connect]. Distinct from [MirrorWire.Listen.wsPort], which is what was
     * *asked for*: `--listen 0` means "any free port", and announcing the
     * requested value there would announce `0` (computenet-dqy.25).
     */
    val boundWsPort: Int? get() = listener?.port

    /** Spawns [projector]'s two cells as replicas on this node's host. */
    fun attach(projector: MirrorProjector) {
        check(attached == null) { "MirrorPeering.attach is a one-shot; use rebind for a re-baseline swap" }
        replication.replicate(projector.cell, host)
        replication.replicate(projector.edges, host)
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
        replication.rebind(incumbent.cell, next.cell, host, carryTagState = false)
        replication.rebind(incumbent.edges, next.edges, host, carryTagState = false)
        attached = next
    }

    /**
     * Opens the socket for this node's role, carrying one [Peering.Side].
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
    fun connect() {
        val side = Peering.Side(registry, bridgeHost, peer = PeerId("${settings.rigName}-${settings.role}"))
        when (val wire = settings.wire) {
            is MirrorWire.Listen -> listener = WsTransport.listen(wire.wsPort, side)
            is MirrorWire.Dial -> connection = WsTransport.connect(URI(wire.uri), side)
        }
    }

    /** Best-effort socket teardown; the hosts and the registry are plain objects. */
    override fun close() {
        listener?.let { runCatching { it.stop() } }
        connection?.let { runCatching { it.close() } }
        listener = null
        connection = null
    }
}
