package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
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
    fun linked(link: civictech.cell.host.TopologyLink)
    fun unlinked(id: UUID)

    /**
     * A local ref despawned/migrated away (spec 42, G-45's eviction gate):
     * the peer drops its stale [LocationRegistry.Remote] mirror so a linker
     * (`Replication`) reconciles — no ack, no round trip.
     */
    fun unpublished(ref: CellRef)
}

/**
 * Receives a peer's announcements and mirrors them into the local registry as
 * [LocationRegistry.Remote] locations routed through [toPeer] — after which
 * local senders reach the remote ref transparently (parked traffic replays).
 *
 * One mirror per peer connection, so it is the right place to hold *whose*
 * announcements these are ([peer], V4-PEERID): the announcement path already
 * carries the peer's identity as a stamp on every decoded invocation
 * (`BridgeIngressCell`), but a served [RegistryAnnounce] method cannot see it,
 * and reading an ambient on the per-message path is what P2 forbids. Holding
 * it on the connection's own cell costs one volatile read per *announcement*
 * — publish/unpublish/link/unlink — and nothing at all on the data path.
 *
 * Being per-connection is also what lets this cell carry the connection's
 * *liveness* ([attach]/[detach]): a closed connection's announcements are no
 * longer authoritative, and the retraction of what it installed has to exclude
 * them rather than race them. That gate costs one uncontended monitor per
 * announcement — again on the announcement path only, never on the data path.
 *
 * On a *socket* transport, "per connection" means per connection *instance*: it
 * mints a mirror per socket open, not per session object, so [ref] — the address
 * the hello hands the peer, and therefore the address every announcement frame
 * carries — identifies the instance that will be held to it, and [detach] is a
 * permanent fence rather than a window. [Peering.loopback] is the exception: it
 * holds one mirror per *peering*, re-opened across a partition/heal. [attach]
 * carries both halves of that.
 */
class RegistryMirrorCell(
    private val registry: LocationRegistry,
    private val toPeer: InvocationSink,
    initialPeer: PeerId? = null,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {

    /**
     * The peer whose announcements this mirror serves; null = anonymous
     * (V4-PEERID). Recorded on every [LocationRegistry.Remote] this mirror
     * installs, so the peer's hull keeps one identity across a reconnect that
     * mints a new bridge egress.
     *
     * **Assignable after construction, and why that is safe.** [Peering.loopback]
     * knows both names up front and passes `initialPeer`. A socket transport
     * cannot: `WsTransport.Session` spawns its mirror in its own constructor,
     * because the hello it must send carries [ref] — and the *remote* name only
     * arrives in the peer's hello, later. The late bind is nonetheless ordered
     * before every announcement this mirror will ever serve:
     *
     * 1. our hello (carrying [ref]) is sent from `onOpen`;
     * 2. the peer cannot address this mirror before it receives that hello, so
     *    its `announceTo` cannot run earlier;
     * 3. the peer's own hello is sent from *its* `onOpen`, i.e. before it
     *    processes ours, and a WebSocket preserves per-connection message
     *    order — so our `onText` (which does the bind) runs before any
     *    announcement frame from that peer;
     * 4. independently, `WsTransport.Session.onFrame` drops every binary frame
     *    that arrives before the hello installed an ingress.
     *
     * So assigning this before the transport's `Peering.announceTo` call
     * happens-before every announcement served here.
     *
     * `@Volatile` because the writer is the transport's IO thread and the
     * reader is the bridge host's scheduler thread. A `var` rather than a
     * constructor-only value because of the late bind above, not because it is
     * re-written: since computenet-dqy.14 every socket connection instance mints
     * its own mirror, so a transport mirror's name is written once, by the hello
     * of the instance that owns it. (The setter stays capable of a re-bind — a
     * peer that re-hellos on one socket is served, `PeerIdentityTest` covers it
     * — but no transport path in this repository reaches that any more.)
     */
    @Volatile
    var peer: PeerId? = initialPeer

    /**
     * The connection gate: whether this mirror is currently authorized to speak
     * for its peer. Held under a monitor rather than as a `@Volatile` flag
     * because [detach] must shut it and retract this connection's locations as
     * one indivisible step — see [detach].
     */
    private val gate = Any()
    private var attached = true

    val inlet = registerPort("inlet", FanInlet.create<RegistryAnnounce>())

    init {
        inlet.serve(object : RegistryAnnounce {
            override fun published(ref: CellRef) = synchronized(gate) {
                if (attached) registry.publish(ref, toPeer, peer)
            }

            override fun linked(link: civictech.cell.host.TopologyLink) = synchronized(gate) {
                if (attached) registry.mirrorLink(link)
            }

            override fun unlinked(id: UUID) = synchronized(gate) {
                if (attached) registry.mirrorUnlink(id)
            }

            override fun unpublished(ref: CellRef) = synchronized(gate) {
                if (attached) registry.mirrorUnpublish(ref)
            }
        })
    }

    /**
     * Re-open the gate — this peering is live again and the mirror may install
     * locations once more. A mirror starts attached, so a peering that never
     * detaches (the pre-existing shape) behaves exactly as before.
     *
     * A re-attached mirror needs no replay of what it lost while detached: the
     * peer's (re-)announcement is a full `localRefs` catch-up
     * ([Peering.announceTo]), so everything it still holds is re-announced.
     *
     * **[Peering.Loopback.heal] is the only production caller, and that is the
     * point** (`MirrorCloseFenceTest` also drives it directly, to pin the gate's
     * own semantics). The disconnect fence is total across *connection
     * instances*: no transport
     * re-opens a mirror it once detached. Both `WsTransport` paths now mint a
     * mirror per connection instance — a listener a whole fresh `Session` per
     * socket, a client a fresh mirror in `Session.hello` per (re)connect
     * (computenet-dqy.14) — so a superseded instance's mirror is shut for good
     * and a frame that instance staged on the bridge host is dropped at the
     * gate, however late it decodes. That works because the mirror's [ref] *is*
     * the connection instance's identity on the wire: an announcement is
     * addressed to the ref the hello carried, so it is attributable to the
     * instance that offered it, at both scheduler hops behind the socket (the
     * ingress decode and the delivery to this cell). No parallel epoch counter
     * is needed, and none exists.
     *
     * [Peering.Loopback] is the one caller because a partition/heal is the
     * *same* peering resuming, not a new instance: mirrors, ingresses and
     * egresses all persist across it. The window that follows from that — an
     * announcement decoded before [Peering.Loopback.partition] and applied
     * after [Peering.Loopback.heal], which can install a ref the peer dropped
     * while severed — is the one place the fence still does not reach, and it
     * is tracked separately (computenet-dqy.20). It is not reachable from either
     * socket path.
     */
    fun attach() = synchronized(gate) { attached = true }

    /**
     * Shut the gate and drop every location this connection installed, as one
     * step — the disconnect fence.
     *
     * **The race this closes.** A peer's announcements are applied
     * *asynchronously*, two scheduler hops behind the socket: `WsTransport`'s
     * IO thread only enqueues a frame on the bridge host
     * ([Peering.hostIngress] returns a hosted proxy), the ingress cell decodes
     * it there and hands the invocation back to `LocationRegistry.deliver`,
     * which queues it again for this mirror. The close, by contrast, used to
     * call [LocationRegistry.unpublishRemotes] straight from that same IO
     * thread. So an announcement decoded *before* the close could be *applied*
     * after it, re-installing [LocationRegistry.Remote] locations routed
     * through an egress whose socket is gone — and nothing would ever retract
     * them: the close has already happened, and the dead peer will not announce
     * again on that connection. The peer's cells then linger in every observer
     * of this registry (an inspector reports a departed peer's cells forever)
     * and every send to them fails into the park queue.
     *
     * Holding the monitor across the retraction is what makes the fence total:
     * a late announcement either lands before it (and is dropped with the rest
     * of the batch) or finds the gate shut. It also serializes the
     * publish-notification chain against the unpublish-notification chain for
     * one connection, so a hook that reads the registry back — as the inspector
     * does to resolve a mirrored node's network host — can no longer observe
     * the two interleaved and record a node the removal event has already been
     * emitted for.
     *
     * The transport's *send-failure* path (a dead socket noticed before the
     * close event) deliberately keeps calling [LocationRegistry.unpublishRemotes]
     * directly: it is an early park optimization, not the fence, and the close
     * that follows is what makes the end state authoritative.
     */
    fun detach() = synchronized(gate) {
        attached = false
        registry.unpublishRemotes(toPeer)
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
                   private val mirrorOnA: RegistryMirrorCell, private val mirrorOnB: RegistryMirrorCell) {
        /**
         * Sever the peering. Routed through each mirror's
         * [RegistryMirrorCell.detach] rather than calling
         * [LocationRegistry.unpublishRemotes] on the two registries directly:
         * that is the same pair of retractions, plus the disconnect fence, so
         * the in-process shape and the socket shape answer a mid-flight
         * announcement identically. It matters here too — a loopback
         * announcement crosses a hosted ingress and is applied on the bridge
         * host's scheduler, so under a [civictech.cell.host.SimulationController]
         * an announcement queued before the partition would otherwise be
         * applied after it.
         */
        fun partition() {
            mirrorOnA.detach()
            mirrorOnB.detach()
        }

        fun heal() {
            mirrorOnA.attach()
            mirrorOnB.attach()
            announceTo(a, peerMirror = mirrorOnB.ref, via = aToB)
            announceTo(b, peerMirror = mirrorOnA.ref, via = bToA)
        }
    }

    fun loopback(a: Side, b: Side): Loopback {
        val aToB = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(b, fromPeer = a.peer), PortRef.generate())) }
        val bToA = BridgeEgressCell().also { it.outlet.subscribe(Use.fixed(hostIngress(a, fromPeer = b.peer), PortRef.generate())) }
        // V4-PEERID: the mirror on B serves A's announcements, so its peer is
        // A's name (and symmetrically). Both names are known here, so the
        // loopback path is a pure constructor value — it never uses the setter.
        val mirrorOnB = spawnMirror(b, toPeer = bToA, peer = a.peer)
        val mirrorOnA = spawnMirror(a, toPeer = aToB, peer = b.peer)
        announceTo(a, peerMirror = mirrorOnB.ref, via = aToB)
        announceTo(b, peerMirror = mirrorOnA.ref, via = bToA)
        return Loopback(a, b, aToB, bToA, mirrorOnA, mirrorOnB)
    }

    /** Spawn a [BridgeIngressCell] on [side]'s bridge host; returned api is safe to call from any thread. */
    fun hostIngress(side: Side, fromPeer: PeerId? = null): Propagate<ByteArray> {
        val ingress = BridgeIngressCell(InvocationSink(side.registry::deliver), peer = fromPeer, admit = side::admits)
        side.bridgeHost.managementInlet.call.spawn(ingress)
        return (HostedCellProxy.create(ingress.ref, side.registry, FrameInletProxy::class.java)
                as FrameInletProxy).inlet.call
    }

    /**
     * Spawn the mirror that turns the peer's announcements into Remote
     * locations routed via [toPeer]. [peer] names the peer whose announcements
     * it will serve, when the caller already knows it (V4-PEERID); omitting it
     * spawns an anonymous mirror, the pre-V4-PEERID shape.
     *
     * Returns the cell rather than its [CellRef] — the richer handle a
     * transport needs, because a socket session must spawn its mirror before
     * the peer's hello has named it and then late-bind
     * [RegistryMirrorCell.peer] (see that property's happens-before argument).
     * Callers that only wanted the ref read `.ref`.
     */
    fun spawnMirror(side: Side, toPeer: InvocationSink, peer: PeerId? = null): RegistryMirrorCell {
        val mirror = RegistryMirrorCell(side.registry, toPeer, peer)
        side.bridgeHost.managementInlet.call.spawn(mirror)
        return mirror
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
        val unpublishRegistration = side.registry.onLocalUnpublish { announce.unpublished(it) }
        val topologyRegistration = side.registry.onLocalTopology(announce::linked, announce::unlinked)
        side.registry.localRefs().forEach(announce::published) // catch-up for pre-peering spawns
        side.registry.localLinks().forEach(announce::linked)
        return AutoCloseable { registration.close(); unpublishRegistration.close(); topologyRegistration.close() }
    }

    /**
     * Kernel-level re-announce chaining rule (T07 finding 2, DRY audit):
     * promotes the application-side idiom every symmetric-view-chaining demo
     * had hand-rolled —
     * `registry.onPublish { ref -> chained[ref]?.let { (cell, link) -> cell.outlet.linking.fireLinked(link) } }`
     * — into the kernel, so the ONE fix this pattern has already needed three
     * times (the PN-9 full-multicast fix: re-fire [Link.unlink]-adjacent
     * [civictech.cell.link.LinkSupport.fireLinked], not just the single
     * `onLinked` slot) lives where the semantics live, not re-expressed at
     * every call site.
     *
     * [chained] maps a peer's announced ref to the local `(outlet, link)`
     * pair that ref feeds into — e.g. `myUnion.outlet to myUnion.outlet
     * .streamTo(routedDelta(peerUnionRef))`. Every announcement (initial join
     * OR a returning/reconnecting peer, M10.1 anti-entropy) that matches a
     * chained ref re-fires that link's FULL on-link catch-up — the same
     * "state-as-delta unicast is idempotent, so a redundant re-fire costs one
     * wasted delta at worst" reasoning [Replication.maybeLink] and
     * [SingleWriterReplication.shipTo] apply to the kernel's own gossip/
     * shipping meshes.
     */
    fun chainOnReannounce(registry: LocationRegistry, chained: Map<CellRef, Pair<Linked, Link>>) {
        registry.onPublish { ref -> chained[ref]?.let { (linked, link) -> linked.linking.fireLinked(link) } }
    }
}
