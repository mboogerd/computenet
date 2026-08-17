package civictech.demo.beadsmirror

import civictech.cell.wire.Peering
import civictech.wire.WsTransport
import java.net.URI

/**
 * The rig's transport wiring, as a parameter (task computenet-7em.2.1).
 *
 * [MirrorPeering] no longer names a socket type: it asks a [MirrorTransport]
 * to establish whichever end of the peering its [MirrorWire] says it is, and
 * the binding decides what a "socket" is. The production binding is
 * [WsMirrorTransport] — the only one that exists, and the only one a running
 * `BeadsMirrorApp` ever constructs.
 *
 * **Why the seam exists at all.** Feature computenet-7em.2's convergence
 * suite is the CONTROL for the iroh transport work (epic computenet-7em §3,
 * DSC0): the same seeded schedules, the same equal-fold assertions, re-run
 * over a different transport. That is only an experiment if the transport is
 * the *only* thing that changes, so the suite receives its wiring instead of
 * naming it — a new transport is a new binding of this interface plus zero
 * test edits, and after this task no e2e test source imports `civictech.wire`.
 *
 * **It is rig-scoped, not node-scoped, and that is what [partition]/[heal]
 * need.** A partition is a property of the *peering*, not of one node, so the
 * object that severs it must be the object that established both ends. In the
 * in-test rig both nodes live in one JVM and share one instance
 * (`TwoNodeRig`); in the two-JVM launch path each process constructs its own,
 * establishes exactly one end, and never partitions anything — see
 * [WsMirrorTransport.partition] for what that asymmetry costs there.
 *
 * **What the seam does NOT abstract: the address model.** The endpoint
 * vocabulary is still `MirrorWire`'s, which is WebSocket-shaped — [listen]
 * takes a port number, [MirrorLink.boundWsPort] hands one back, and [dial]
 * takes a `ws://host:port` string, because `TwoNodeRig` builds the dialer's
 * `MirrorWire.Dial` out of the listener's bound port and `BeadsMirrorApp`
 * parses `--listen`/`--peer` into the same two shapes. A binding whose
 * addresses are not ports and URIs — an iroh node id, say — therefore has to
 * satisfy that vocabulary rather than replace it: return *some* non-null
 * integer from [MirrorLink.boundWsPort] on its listening end (`TwoNodeRig`
 * `checkNotNull`s it) and treat [dial]'s [String] as an opaque token it may
 * ignore, which is sound precisely because a rig shares ONE transport instance
 * between its two nodes, so the dialing end already knows where the listening
 * end is. That is a wart in this interface, not a test edit: "zero test edits"
 * survives it, and re-typing the address model is DSC0's call to make once a
 * second binding exists to generalize from.
 */
interface MirrorTransport {

    /**
     * Establish the listening end on [requestedWsPort] (`0` = any free port,
     * in which case [MirrorLink.boundWsPort] is the only place the granted
     * number exists — computenet-dqy.25).
     */
    fun listen(requestedWsPort: Int, side: Peering.Side): MirrorLink

    /** Establish the dialing end against [uri], a `ws://host:port` endpoint a listener announced. */
    fun dial(uri: String, side: Peering.Side): MirrorLink

    /**
     * Sever the peering this transport established, so nothing crosses between
     * the two nodes until [heal]. Deltas minted while severed are held by each
     * node's own replica and are the catch-up's job afterwards.
     */
    fun partition()

    /**
     * Re-establish what [partition] severed, returning once the peering is
     * carrying again — the *link*, not convergence: the folds catch up
     * afterwards through the ordinary re-announcement path, which is what the
     * caller's bounded wait is for.
     */
    fun heal()
}

/**
 * One established end of a peering: the handle [MirrorPeering] holds for its
 * own node, and closes when the app stops.
 */
interface MirrorLink : AutoCloseable {

    /**
     * The port this end actually bound, or `null` on a dialing end. Distinct
     * from what was *asked for*: `--listen 0` means "any free port", so this
     * is what a dialer (or a supervising test) must be told.
     */
    val boundWsPort: Int?
}

/**
 * The production [MirrorTransport]: `:wire`'s WebSocket transport
 * ([WsTransport]). This is the only file in the module that names a `:wire`
 * type, and it is instantiated only when [BeadsMirrorConfig.peering] is set,
 * so a solo-mode run still loads none of it.
 *
 * ## partition/heal sever the DIALING end, deliberately
 *
 * Two shapes could express a partition here, and they differ in how
 * deterministic the *heal* is:
 *
 * - **Kill the listener**, and the dialer's own reconnect loop finds the
 *   re-listen. Healing is then something that happens *eventually*, on the
 *   dialer's backoff schedule, and the re-listen has to reclaim the same port
 *   — which the OS may have handed to any concurrent `bind(0)` in the
 *   meantime, so it needs the held-port idiom (`WsTransport.listen(channel,
 *   side)` and `:wire`'s `HeldPort`, computenet-dqy.22) to be sound at all.
 * - **Shut the dialer's connection down** ([WsTransport.WsConnection.shutdown]
 *   — close *and* stop reconnecting, which a bare `close()` does not), leaving
 *   the listener bound and serving throughout. [heal] then dials a fresh
 *   connection on the same [Peering.Side], and `WsTransport.connect` blocks
 *   until the socket is open, so **`heal()` returns with the link already
 *   carrying**. What remains for the caller's `awaitUntil` is convergence
 *   alone, not "did the transport come back".
 *
 * This binding takes the second. The listener never unbinds, so no port is
 * ever handed back to the OS and there is no re-bind race to defend against;
 * and the peer's view of a severed dialer is exactly the view it has of a
 * dropped socket — its session's close unpublishes every ref learned through
 * it, senders park (spec 33), and the fresh hello on heal re-announces a full
 * `localRefs` catch-up in both directions. `Replication.maybeLink` re-fires
 * `fireLinked` for an already-linked pair, which is why the returning peering
 * re-serves the deltas the severed socket swallowed and why this rig needs no
 * `Peering.chainOnReannounce` (see [MirrorPeering.connect]).
 *
 * The cost is stated plainly: [partition] and [heal] are only meaningful on a
 * transport that has [dial]ed. A process that only ever [listen]s — either
 * node of the two-JVM launch path, whichever end it is — cannot sever the
 * peering from its side, and calling [partition] there fails loudly rather
 * than silently doing nothing. Nothing in production calls either.
 *
 * @param reconnectBackoff the delay schedule for the dialer's *unplanned*
 *   reconnects and for `connect`'s initial reachability probe. Defaults to the
 *   production 1s-doubling schedule; a test rig drives it near zero so a heal
 *   costs scheduling rather than wall clock (`WsTransport.connect`'s own T12
 *   seam).
 */
class WsMirrorTransport(
    private val reconnectBackoff: (attempt: Int) -> Long = WsTransport.DEFAULT_RECONNECT_BACKOFF,
) : MirrorTransport {

    private val lock = Any()

    /** The dialing end, once [dial] established one — what [partition]/[heal] act on. */
    private var dialed: DialLink? = null

    override fun listen(requestedWsPort: Int, side: Peering.Side): MirrorLink =
        ListenLink(WsTransport.listen(requestedWsPort, side))

    override fun dial(uri: String, side: Peering.Side): MirrorLink = synchronized(lock) {
        check(dialed == null) { "this transport has already dialed ${dialed?.uri}" }
        DialLink(uri, side).also { link ->
            dialed = link
            link.open()
        }
    }

    override fun partition() = synchronized(lock) { dialingEnd().sever() }

    override fun heal() = synchronized(lock) { dialingEnd().reopen() }

    private fun dialingEnd(): DialLink = checkNotNull(dialed) {
        "partition/heal sever the DIALING end of the peering, and this transport has not dialed one; " +
            "share one MirrorTransport between the two nodes of a rig"
    }

    /** A serving listener. Nothing severs this end; see the class doc. */
    private class ListenLink(private val listener: WsTransport.WsListener) : MirrorLink {

        override val boundWsPort: Int get() = listener.port

        override fun close() {
            runCatching { listener.stop() }
        }
    }

    /**
     * A dialed connection, replaceable: [sever] shuts the current one down for
     * good and [reopen] dials a new one at the same [uri] on the same [side].
     *
     * A `Peering.Side` backs many sessions by construction — a listener serves
     * one per accepted socket — so re-dialing on it is the ordinary case and
     * not a reuse trick. Each open mints a fresh registry mirror and hello, so
     * a healed peering supersedes rather than resumes the severed one, exactly
     * as a reconnect does (`RegistryMirrorCell.detach` is a permanent fence).
     */
    private inner class DialLink(val uri: String, private val side: Peering.Side) : MirrorLink {

        private var connection: WsTransport.WsConnection? = null

        override val boundWsPort: Int? get() = null

        fun open() {
            check(connection == null) { "this end is already connected to $uri" }
            connection = WsTransport.connect(URI(uri), side, reconnectBackoff)
        }

        fun sever() {
            val live = checkNotNull(connection) { "the peering to $uri is already partitioned" }
            // shutdown(), not close(): close() leaves `reconnect` true, and the
            // retry loop would heal the partition on its own within a backoff tick
            live.shutdown()
            connection = null
        }

        fun reopen() {
            check(connection == null) { "the peering to $uri is not partitioned" }
            open()
        }

        override fun close() {
            connection?.let { runCatching { it.shutdown() } }
            connection = null
        }
    }
}
