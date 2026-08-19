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
 * *liveness* ([detach]): a closed connection's announcements are no longer
 * authoritative, and the retraction of what it installed has to exclude them
 * rather than race them. That gate costs one uncontended monitor per
 * announcement — again on the announcement path only, never on the data path.
 *
 * "Per connection" means per connection *instance*, on every path: a socket
 * transport mints a mirror per socket open rather than per session object
 * (computenet-dqy.14), and [Peering.Loopback.heal] mints a fresh pair rather
 * than re-opening the pair its [Peering.Loopback.partition] shut
 * (computenet-dqy.20). So [ref] — the address the hello hands the peer, and
 * therefore the address every announcement frame carries — identifies the
 * instance that will be held to it, and [detach] is a permanent fence rather
 * than a window. There is no way to re-open a mirror; that is the whole
 * property.
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

    /**
     * How many announcements this mirror's shut gate has refused
     * (computenet-dqy.40). Diagnostics only — nothing reads it to decide
     * anything, and the drop itself is unchanged.
     *
     * The gate is a *correct* drop (see [detach]) but it was, until this
     * counter, an entirely **silent** one, and that is what made the
     * 2026-08-12 Linux announcement loss undiagnosable after the fact. Measured
     * by execution in `WsAnnouncementSilenceInventoryTest`: of every way an
     * announcement can fail on this path, only two swallow it without a word —
     * this gate and `WsTransport.Session`'s pre-hello frame drop. A throw out
     * of the transport's `onText` (which truncates
     * [Peering.announceTo]'s catch-up sweep), a failing publish hook, an
     * unknown cell or port, and the scheduler's backstop all reach
     * `System.err`. So "never arrived, and stderr was silent" could not
     * previously separate a refusal here from a delivery that never ran; with
     * this counter and the pre-hello count it can.
     */
    val refusedAnnouncements: Long get() = refused.get()

    private val refused = java.util.concurrent.atomic.AtomicLong()

    private fun refuse() {
        refused.incrementAndGet()
    }

    val inlet = registerPort("inlet", FanInlet.create<RegistryAnnounce>())

    init {
        inlet.serve(object : RegistryAnnounce {
            override fun published(ref: CellRef) = synchronized(gate) {
                if (attached) registry.publish(ref, toPeer, peer) else refuse()
            }

            override fun linked(link: civictech.cell.host.TopologyLink) = synchronized(gate) {
                if (attached) registry.mirrorLink(link) else refuse()
            }

            override fun unlinked(id: UUID) = synchronized(gate) {
                if (attached) registry.mirrorUnlink(id) else refuse()
            }

            override fun unpublished(ref: CellRef) = synchronized(gate) {
                if (attached) registry.mirrorUnpublish(ref) else refuse()
            }
        })
    }

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
     *
     * **The gate never re-opens, and that is what makes the fence total.** Every
     * announcement is addressed to the mirror ref its connection instance
     * offered, so a frame that instance staged on the bridge host is dropped
     * here however late it decodes — at both scheduler hops behind the
     * connection, the ingress decode and the delivery to this cell. No parallel
     * epoch counter is needed, and none exists. What replaces a detached mirror
     * is a *fresh* one: `WsTransport.Session.hello` per socket open
     * (computenet-dqy.14), [Peering.Loopback.heal] per heal
     * (computenet-dqy.20). The returning peer loses nothing by that, because a
     * (re-)announcement is a full `localRefs` catch-up
     * ([Peering.announceTo]) — everything it still holds is re-announced, and
     * only what it no longer holds is left behind.
     *
     * What the gate can drop is bounded by what reaches this cell: [inlet] serves
     * [RegistryAnnounce] alone, whose arguments are refs, link records and ids.
     * No `Owned`/`Leased` payload can be dropped here — peer *data* is addressed
     * to the data ref it names, not to this mirror, so it parks against a missing
     * location like any other send (spec 33) rather than meeting this gate.
     */
    fun detach() = synchronized(gate) {
        attached = false
        registry.unpublishRemotes(toPeer)
    }
}

/**
 * Default hello-nonce retention window: ten minutes of epoch milliseconds
 * (DSC1 epic §9.8, `[DSC1-HELLO-11]`).
 *
 * The number is a *policy* choice, not a derived bound, and it is stated here
 * with its limits so the next reader does not have to reconstruct them:
 *
 * - **Why bounded at all.** `[DSC1-HELLO-11]` requires a side to remember the
 *   hellos it accepted, which is unbounded state unless windowed. §9.8 decides
 *   the window and pairs the eviction rule with `[DSC1-ANN-13]`'s bounded-state
 *   discipline: retained state is proportional to *admitted peers*, never to
 *   hellos received.
 * - **Why ten minutes.** Long enough to cover any plausible reconnect storm on
 *   one socket (a hello is a per-connection event, not a per-message one), and
 *   short enough that a peer whose entries have all expired costs nothing.
 * - **What it does NOT claim.** It is not a proof of replay resistance beyond
 *   the window: a hello replayed after the window elapses is refused by the
 *   *nonce freshness* of the peer's own challenge, not by this memory. This
 *   constant bounds only how long a within-window duplicate stays detectable.
 */
const val DEFAULT_NONCE_RETENTION_MILLIS: Long = 600_000

/**
 * Whether a [Peering.Side] demands cryptographic proof of a peer's identity at
 * hello time (DSC1, `[DSC1-HELLO-08..10]`, `[DSC1-WIRE-06]`).
 *
 * **Pure vocabulary.** This is a kernel type and deliberately carries no
 * crypto: no key, no signature, no digest, no `java.security` import. The
 * primitives live in `:identity` and their use lives in `:wire`, so `:kernel`
 * stays free of any cryptographic dependency (`[DSC1-WIRE-03]`). What the
 * kernel owns is the *decision surface* — a side's policy is part of its
 * configuration, beside [Peering.Side.allow], and readable without a
 * transport.
 *
 * **Where it is enforced, and where it is not.** This policy is read at *hello*
 * time, and the only peering in this repository that exchanges a hello is the
 * socket transport (`civictech.wire.WsTransport.Session.onText` — the single
 * admission point). [Peering.loopback] has **no hello at all**: it wires each
 * side's ingress directly and takes both peer names from [Peering.Side.peer],
 * i.e. from configuration, so there is nothing for a challenge/response to
 * verify and this policy is neither consulted nor enforceable there. A loopback
 * side configured [RequireAuthenticated] is therefore not authenticated — its
 * peer names are as vouched-for as they were before this type existed. That is
 * not a hole in the socket path's guarantee; it is the absence of a wire to
 * make a claim over. What [Peering.Side.credentials] *is* for on a loopback
 * side is signing — announcements carry their own signature over the frame,
 * which is the sibling feature's scope (DSC1 §4.3, `[DSC1-WIRE-05]`) and the
 * reason the credentials field is independent of this policy.
 */
sealed interface PeerAuthPolicy {
    /**
     * Today's behaviour, byte for byte (`[DSC1-HELLO-10]`, `[DSC1-WIRE-06]`):
     * a peer asserts a name and the transport vouches for it
     * (`civictech.cell.membrane.AuthLevel.TransportVouched`). The legacy
     * `HELLO` line is unchanged and unauthenticated peers are admitted subject
     * to [Peering.Side.allow] alone.
     *
     * This is the **default** for every [Peering.Side], which is what makes the
     * epic's additive claim structural rather than tested: a caller that never
     * mentions authentication cannot get any.
     */
    data object Open : PeerAuthPolicy

    /**
     * The side admits a connection only on a hello whose signature verifies
     * under a presented public key whose fingerprint equals the claimed id
     * (`[DSC1-HELLO-03..04]`), and refuses anything less —
     * `civictech.cell.DenialReason.AUTH_REQUIRED` for a hello that omits key
     * material or a proof (`[DSC1-HELLO-08..09]`).
     *
     * @property nonceRetentionMillis how long an accepted hello's nonce and
     *   signature stay detectable as a replay on this side
     *   (`[DSC1-HELLO-11]`); see [DEFAULT_NONCE_RETENTION_MILLIS] for the
     *   window's rationale and its limits.
     */
    data class RequireAuthenticated(
        val nonceRetentionMillis: Long = DEFAULT_NONCE_RETENTION_MILLIS,
    ) : PeerAuthPolicy
}

/**
 * The signing half of a side's identity, as the kernel sees it: an id, the
 * public key that derives it, and the ability to sign — nothing more.
 *
 * **Deliberately an interface with no kernel implementation.** The kernel
 * cannot construct one of these, because doing so needs a keypair and
 * therefore a cryptographic provider; `:identity` holds the material and
 * `:wire` adapts it (`civictech.wire.PeerIdentityCredentials`). That
 * asymmetry is the enforcement of `[DSC1-WIRE-03]`: the dependency direction
 * is `:wire -> :identity -> :kernel`, and this type is the seam it meets.
 *
 * [sign] rather than a private-key property is the same discipline
 * `civictech.identity.PeerIdentity` applies (`[DSC1-KEY-09]`): no accessor,
 * destructuring, copy or serializer can carry private material out through
 * this interface. An implementation must keep [toString] free of it too.
 */
interface PeerCredentials {
    /** This side's key-derived identity — `civictech.identity.fingerprint(publicKey)`. */
    val peerId: PeerId

    /** The public half, in its X.509/SubjectPublicKeyInfo encoding — what a hello presents. */
    val publicKey: ByteArray

    /** A signature over exactly [message]; no framing, prefixing or hashing is added here. */
    fun sign(message: ByteArray): ByteArray
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
        /**
         * **Test-only seam** (computenet-dqy.45), null on every production path
         * and never set by kernel, `:wire` or any demo. [announceTo] invokes it
         * once per announcer, *between* installing the `onLocalPublish` hook and
         * running the `localRefs()` catch-up sweep — i.e. with the
         * register-then-sweep window held open.
         *
         * It exists because that window is otherwise unreachable from a test.
         * A peer's `announceTo` is gated on a socket round trip (the peer's
         * hello), while a local publish is an in-memory enqueue, so any publish
         * a test can time against *connect* has already been installed by the
         * time the sweep reads `localRefs()`. Measured, before this seam
         * existed: deleting the sweep lost 40/40 refs of
         * `WsAnnouncementCatchUpBurstTest`'s burst, both halves — nothing at all
         * travelled the hook, so the ordering argument `announceTo` rests on had
         * no measured bound.
         *
         * A test releases its racing publishes from here; their installation
         * then runs concurrently with the sweep, which is the race the handover
         * claims to be safe under. It changes no behaviour when null, and
         * `announceTo`'s real ordering is untouched — the seam is a notification
         * *inside* the existing window, not a reordering of it.
         */
        val onCatchUpWindowOpen: (() -> Unit)? = null,
        /**
         * Whether this side demands a cryptographically proven peer identity at
         * hello time (DSC1, `[DSC1-HELLO-08..10]`). Defaults to
         * [PeerAuthPolicy.Open] — today's behaviour, byte for byte.
         *
         * A **trailing parameter with a default** on purpose: every existing
         * caller of this constructor compiles unchanged and behaves identically
         * (`[DSC1-HELLO-10]`, `[DSC1-WIRE-06]`), which is a structural property
         * of the signature rather than something a test has to establish.
         */
        val auth: PeerAuthPolicy = PeerAuthPolicy.Open,
        /**
         * This side's signing identity, or null when it has none. Optional even
         * under [PeerAuthPolicy.Open]: a side may hold a keypair and still
         * admit unauthenticated peers, which is the configuration the sibling
         * announcement feature needs in order to sign its own announcements
         * over an in-process [loopback] with no socket involved
         * (`[DSC1-WIRE-05]`).
         *
         * Carrying credentials here is *configuration*, not behaviour: this
         * task adds the surface, and loopback authentication semantics are the
         * announcement feature's scope, not this one's.
         */
        val credentials: PeerCredentials? = null,
    ) {
        init {
            // A side that demands proof from its peer must be able to answer the
            // peer's challenge in turn — the hello exchange is symmetric
            // (`[DSC1-HELLO-01..04]`), so RequireAuthenticated without a signing
            // identity is a configuration that can never complete a handshake.
            //
            // Refusing at CONSTRUCTION rather than at hello time is the point:
            // the failure is a misconfiguration of the process, and it should
            // surface where the process is wired up, not as an unexplained
            // connection refusal minutes later against a peer that did nothing
            // wrong. Nothing about this is a runtime trust decision, so it is a
            // `require`, not a DenialReason.
            require(auth is PeerAuthPolicy.Open || credentials != null) {
                "PeerAuthPolicy.RequireAuthenticated needs credentials: a side that demands a " +
                    "proven peer identity must hold a keypair to answer the peer's challenge " +
                    "(auth=$auth, credentials=null)"
            }
        }

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
     *
     * **A heal is a new connection instance, not a resumed one**
     * (computenet-dqy.20). The two frame links — the egresses and the ingresses
     * they feed — persist, because they are the wire; the *registry mirrors* and
     * the announcement hooks that address them do not. Each [heal] mints a fresh
     * [RegistryMirrorCell] pair and announces to those, exactly as
     * `WsTransport.Session.hello` mints one per socket open, so
     * [RegistryMirrorCell.detach] is a permanent fence on this path too.
     *
     * What that buys, and what the previous shape cost: an announcement decoded
     * on the bridge host *before* [partition] leaves a delivery queued for the
     * mirror. Re-opening the same mirror in [heal] applied it — and if the peer
     * dropped that ref while severed, [heal]'s full `localRefs` catch-up never
     * re-announces it, so nothing ever retracted the resurrected
     * [LocationRegistry.Remote]. Addressing the returning peering to a *fresh*
     * mirror ref drops that delivery at the superseded mirror's shut gate
     * instead, which leaves the catch-up as the single authority on what the
     * peer still holds. Under a [civictech.cell.host.SimulationController] that
     * window is not exotic: it is whatever is left unrun between [partition] and
     * [heal].
     *
     * The superseded mirrors stay *spawned*, deliberately, for the reason
     * `WsTransport.Session.mirror` records: despawning turns the fence's drop
     * into a park, since [LocationRegistry.deliver] parks an invocation whose
     * target ref has no location — so the stale announcement would sit in
     * [LocationRegistry.parkedFor] forever instead of being refused, which is
     * both a worse leak and a weaker fence.
     *
     * **The price of that, stated in full.** A retired mirror stays published as
     * [LocationRegistry.Local], so it also stays in [LocationRegistry.localRefs]
     * — which is what the next [heal]'s catch-up announces. Measured over ten
     * partition/heal cycles on one loopback: each side grows by exactly one cell,
     * one announced local ref, and one *mirrored* [LocationRegistry.Remote] per
     * heal (2/2 refs per side before the first heal, 12/12 after ten). So the
     * bead's "cell counts and announced localRefs stay flat across a heal" is
     * **not** met here, and an observer of either registry — an inspector, say —
     * sees the graveyard as peer cells.
     *
     * That is knowingly accepted rather than overlooked: it is the *same*
     * mechanism, with the same reason the obvious despawn is wrong, that
     * computenet-vzb already measures and owns for the socket path, and closing
     * it needs either a quiescence bound before retiring an instance's cells or
     * a "refuse, do not park" tombstone in [LocationRegistry] — a kernel
     * semantics decision (an unconditional drop path in
     * [LocationRegistry.deliver] owes an `Owned`/`Leased` accounting story),
     * not a repair inside this fix. The trade this item does make is a
     * monotonic, inert residue in place of a *silently wrong* registry, and a
     * loopback is the in-process peering shape: the unbounded-reconnect hazard
     * lives on the socket path, where `WsReconnectLoopBoundTest` and
     * computenet-vzb watch it.
     */
    class Loopback(
        private val a: Side,
        private val b: Side,
        val aToB: InvocationSink,
        val bToA: InvocationSink,
        /**
         * The [BridgeIngressCell] receiving `b`'s frames on `a`'s bridge host
         * (computenet-usd.4.1) — the seam-1 accounting test surface,
         * `ingressOnA.boundaryDenials["bridge-ingress"]`. Null for the direct
         * constructor call `MirrorCloseFenceTest` makes with its own
         * hand-wired `SeverableLink`s, which never routes through
         * [hostIngress]'s `onSpawn` hook; every path through [loopback] itself
         * supplies it.
         */
        val ingressOnA: BridgeIngressCell? = null,
        /** [ingressOnA]'s counterpart: the ingress receiving `a`'s frames on `b`'s bridge host. */
        val ingressOnB: BridgeIngressCell? = null,
    ) {
        private lateinit var mirrorOnA: RegistryMirrorCell
        private lateinit var mirrorOnB: RegistryMirrorCell
        private var announcerFromA: AutoCloseable? = null
        private var announcerFromB: AutoCloseable? = null

        init {
            open()
        }

        /**
         * The address this peering's *current* instance hands the peer on
         * [b]'s side — the ref every announcement A serves is sent to. Fresh
         * after every [heal]; the test surface for "a heal supersedes rather
         * than resumes".
         *
         * Reads under the same monitor [heal] writes it under: the mirror fields
         * became mutable here, so "which instance is current" is only a
         * well-defined question against that lock.
         */
        val mirrorRefOnA: CellRef get() = synchronized(this) { mirrorOnA.ref }

        /** [mirrorRefOnA]'s counterpart on [a]'s side. */
        val mirrorRefOnB: CellRef get() = synchronized(this) { mirrorOnB.ref }

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
         *
         * The announcement hooks go with the gate: a severed peering does not
         * speak for its peer either, and a frame encoded onto a link that is
         * down would only be dropped at the far gate anyway. A socket transport
         * gets that for free (the send fails), so closing them here is what
         * makes the two shapes answer a publish-while-severed the same way.
         */
        @Synchronized
        fun partition() = closeInstance()

        /**
         * Re-establish the peering as a *fresh* connection instance: new
         * mirrors, new announcement hooks, a full catch-up on both sides. Safe
         * without a preceding [partition] — it supersedes whatever instance is
         * current, the same way a re-hello does.
         */
        @Synchronized
        fun heal() = open()

        private fun open() {
            closeInstance()
            // V4-PEERID: the mirror on B serves A's announcements, so its peer
            // is A's name (and symmetrically). Both names are known here, so the
            // loopback path is a pure constructor value — it never uses the setter.
            mirrorOnB = spawnMirror(b, toPeer = bToA, peer = a.peer)
            mirrorOnA = spawnMirror(a, toPeer = aToB, peer = b.peer)
            announcerFromA = announceTo(a, peerMirror = mirrorOnB.ref, via = aToB)
            announcerFromB = announceTo(b, peerMirror = mirrorOnA.ref, via = bToA)
        }

        /**
         * Retire the current connection instance: stop announcing, then shut
         * both gates (each [RegistryMirrorCell.detach] also retracts what its
         * side installed). Idempotent — [partition] twice, or [heal] on a
         * severed peering, is a no-op the second time.
         */
        private fun closeInstance() {
            announcerFromA?.close()
            announcerFromA = null
            announcerFromB?.close()
            announcerFromB = null
            if (::mirrorOnA.isInitialized) {
                mirrorOnA.detach()
                mirrorOnB.detach()
            }
        }
    }

    fun loopback(a: Side, b: Side): Loopback {
        lateinit var ingressOnB: BridgeIngressCell
        lateinit var ingressOnA: BridgeIngressCell
        val aToB = BridgeEgressCell().also {
            it.outlet.subscribe(
                Use.fixed(hostIngress(b, fromPeer = a.peer, onSpawn = { ingressOnB = it }), PortRef.generate()),
            )
        }
        val bToA = BridgeEgressCell().also {
            it.outlet.subscribe(
                Use.fixed(hostIngress(a, fromPeer = b.peer, onSpawn = { ingressOnA = it }), PortRef.generate()),
            )
        }
        // the mirrors and announcers are the *connection instance*, minted by
        // Loopback itself so that a heal can mint fresh ones (computenet-dqy.20)
        return Loopback(a, b, aToB, bToA, ingressOnA, ingressOnB)
    }

    /**
     * Spawn a [BridgeIngressCell] on [side]'s bridge host; returned api is
     * safe to call from any thread. [onSpawn] hands back the spawned cell
     * itself before it is hosted — a test-only widening (computenet-usd.4.1)
     * so [loopback] can expose its ingress cells' `boundaryDenials` (seam 1
     * accounting) without changing this function's return type for its
     * production callers (`WsTransport`).
     */
    fun hostIngress(side: Side, fromPeer: PeerId? = null, onSpawn: (BridgeIngressCell) -> Unit = {}): Propagate<ByteArray> {
        val ingress = BridgeIngressCell(InvocationSink(side.registry::deliver), peer = fromPeer, admit = side::admits)
        onSpawn(ingress)
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
     *
     * **The catch-up sweep is failure-isolated per ref** (computenet-dqy.40),
     * for the reason [LocationRegistry.publish]'s own hook notification already
     * is: *hooks are notifications, not participants*. Until this item the
     * sweep was the one place on the announcement path without that isolation,
     * and the asymmetry was the defect — the same send failure was survivable
     * on the [LocationRegistry.onLocalPublish] path and fatal on the sweep.
     *
     * What that cost, measured rather than reasoned. A send on [via] that
     * fails takes `WsTransport.Session`'s egress branch, which retracts this
     * connection's remotes and rethrows `IntakeClosedException`; that throw
     * unwound `forEach` and abandoned **every remaining local ref**, and then
     * escaped `Session.onText` entirely. Three consequences, each reproduced in
     * `:wire`'s `WsAnnouncementSilenceInventoryTest`:
     *
     * 1. the refs behind the failure were never announced;
     * 2. the socket **stays open** — java-websocket reports the failed callback
     *    and carries on — so there is no close, no reconnect, no re-hello and
     *    therefore no second catch-up to repair it: the loss is permanent on a
     *    connection that looks healthy;
     * 3. `announceTo` never returned, so the three hooks it had already
     *    registered were never handed back to the caller and could not be
     *    closed — a leaked announcer per occurrence, on a `via` that had just
     *    failed.
     *
     * Observed in the ordinary `:wire` suite on Linux (groovy:4.0-jdk21,
     * aarch64), from both sides, in 3 of 3 container runs of the whole
     * package — so this is a path the code actually takes, not a hypothesis.
     *
     * A failure is *reported*, never swallowed, exactly as
     * `LocationRegistry.notify` reports a failing hook: the silence inventory
     * on this path (`WsAnnouncementSilenceInventoryTest`) is a property worth
     * keeping, and a catch-up that lost a ref must stay loud.
     *
     * This is **not** a claim about the 2026-08-12 Linux loss, which was
     * silent for its whole iteration and therefore cannot be this.
     */
    fun announceTo(side: Side, peerMirror: CellRef, via: InvocationSink): AutoCloseable {
        val announce = (HostedCellProxy.create(peerMirror, via, AnnounceInletProxy::class.java)
                as AnnounceInletProxy).inlet.call
        val registration = side.registry.onLocalPublish { announce.published(it) }
        val unpublishRegistration = side.registry.onLocalUnpublish { announce.unpublished(it) }
        val topologyRegistration = side.registry.onLocalTopology(announce::linked, announce::unlinked)
        side.onCatchUpWindowOpen?.invoke() // test-only seam; null everywhere else
        // catch-up for pre-peering spawns
        side.registry.localRefs().forEach { ref -> catchUp("published $ref") { announce.published(ref) } }
        side.registry.localLinks().forEach { link -> catchUp("linked ${link.id}") { announce.linked(link) } }
        return AutoCloseable { registration.close(); unpublishRegistration.close(); topologyRegistration.close() }
    }

    /** One catch-up announcement, isolated from its siblings — see [announceTo]. */
    private inline fun catchUp(what: String, send: () -> Unit) {
        try {
            send()
        } catch (e: Exception) {
            System.err.println("[Peering] catch-up announcement failed ($what): $e")
        }
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
