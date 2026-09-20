package civictech.iroh

import civictech.cell.BoundaryDenials
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One iroh **endpoint**: a single sidecar process, a single [SidecarClient],
 * and every link this side has — accepted, discovered and configured — under
 * one key (DSC2 feature `computenet-ktn1l`, decisions F3-D2 and ktn1l-D11).
 *
 * ## Why one endpoint is the whole point
 *
 * [IrohTransport.listen] and [IrohTransport.connect] each spawn their own
 * sidecar (egl.2-D2), so a JVM that both listens and dials is two endpoints
 * with two NodeIds. That is harmless while peers are configured by hand and
 * fatal once they are *discovered*: the key a peer learns from an mDNS
 * advertisement must be the key that accepts its dial and the key it is dialled
 * from, or "one live peering per key identifier" ([DSC2-DIAL-01]) is a claim
 * about two unrelated keys. A node is that single key.
 *
 * Both older entry points are untouched and keep working exactly as they did.
 * A caller that mixes a node with [IrohTransport.connect] gets two endpoints
 * and no one-peering guarantee **across** them — a stated non-goal of the epic
 * (ktn1l-D15), not an oversight.
 *
 * ## What it is, and what it deliberately is not
 *
 * This class is transport *mechanics* only: it owns links, consults a [gate] on
 * every hello, keeps a registry of what is up and reports the lifecycle. It
 * holds no peer table, no discovery-event consumption, no dial schedule and no
 * tie-break rule — those are `civictech.iroh.discover`'s (feature
 * `computenet-ktn1l`, tasks 2 and 3), which drives this class from outside.
 * Nothing here decides *which* verdict a hello gets; it only guarantees the
 * verdict is asked for at the one point where acting on it is safe (see
 * [HelloGate]).
 *
 * ## Threads
 *
 * Every callback this class hands out — [NodeLinkListener], and the [gate]
 * itself — runs on the [SidecarClient]'s single reader thread, with the one
 * exception noted on [NodeLinkListener.onUp]. **They must only enqueue.** A
 * callback that blocks stops every link on this endpoint, and one that dials
 * deadlocks against the reply it is waiting for.
 */
class IrohNode internal constructor(
    private val sidecar: IrohTransport.Sidecar,
    /** Shared: the listener, every discovered connection and every configured one speak over this one client. */
    internal val client: SidecarClient,
    private val side: Peering.Side,
) : AutoCloseable {

    /** Where a link on this node came from. */
    enum class LinkSource {
        /** Accepted: the peer dialled us. */
        ACCEPTED,

        /** Opened by [dialDiscovered] — a key a discovery event named. */
        DISCOVERED,

        /** Opened by [connectConfigured] — a key and addresses a caller named. */
        CONFIGURED,
    }

    /**
     * One live link of this node, as of the moment it was read (ktn1l-D14).
     *
     * A value, not a handle: a link that has since gone down still reads the
     * same here, which is what makes a snapshot usable off the reader thread.
     * [attributedPeer] is the identity the hello resolved to, null until the
     * link is admitted; it is the `Session`'s own attribution and never a
     * second derivation from the key.
     */
    data class LinkView(
        val linkId: Long,
        val remoteNodeId: ByteArray,
        val direction: LinkDirection,
        val source: LinkSource,
        val peered: Boolean,
        val attributedPeer: PeerId?,
    ) {
        // ByteArray in a data class: compare and hash by CONTENT, since the
        // whole point of a key identifier is that two readings of the same 32
        // bytes are the same key (F3-D3).
        override fun equals(other: Any?): Boolean =
            other is LinkView &&
                linkId == other.linkId &&
                remoteNodeId.contentEquals(other.remoteNodeId) &&
                direction == other.direction &&
                source == other.source &&
                peered == other.peered &&
                attributedPeer == other.attributedPeer

        override fun hashCode(): Int {
            var result = linkId.hashCode()
            result = 31 * result + remoteNodeId.contentHashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + peered.hashCode()
            result = 31 * result + (attributedPeer?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * The link lifecycle of this whole endpoint (ktn1l-D14).
     *
     * Delivered on the sidecar reader thread and therefore **enqueue-only**,
     * the same rule [PeerWatchListener] carries and for the same reason: this
     * thread dispatches every link's frames, so anything it waits on waits for
     * the whole endpoint.
     *
     * The one honest exception: [onUp] for an OUTBOUND link runs on whichever
     * thread called [IrohTransport.IrohConnection.openLink] — the dial is
     * synchronous, and the link exists first on that thread. Treat it as
     * enqueue-only regardless.
     */
    interface NodeLinkListener {
        /** A link exists. Not yet admitted: nothing has been said on it. */
        fun onUp(link: LinkView) {}

        /** The link's hello was admitted and the gate let it through; [LinkView.attributedPeer] is set. */
        fun onAdmitted(link: LinkView) {}

        /**
         * The link is gone. [outcome] is the dialling connection's
         * classification of an **unplanned** down, and null for an accepted
         * link or a close this side asked for.
         */
        fun onDown(link: LinkView, outcome: IrohTransport.IrohConnection.LinkOutcome?) {}
    }

    /**
     * The verdict every hello on **every** link of this node is judged by —
     * accepted, discovered and configured alike (ktn1l-D14).
     *
     * A `var` read at each consult rather than a constructor parameter, because
     * the policy that installs it is built *around* a node and therefore cannot
     * exist when the node is constructed. Every Session this node opens holds
     * [delegatingGate], which reads this field at judgement time, so a gate
     * installed after construction governs links that already exist.
     */
    @Volatile
    var gate: HelloGate = HelloGate.ADMIT_ALL

    /** @see gate — what each Session actually holds. */
    private val delegatingGate = HelloGate { key, remoteNodeId, direction, resolved ->
        gate.judge(key, remoteNodeId, direction, resolved)
    }

    /** This side's iroh endpoint id: the key it advertises, accepts on and dials from. */
    val nodeId: ByteArray get() = sidecar.nodeId

    @Volatile
    private var listeningAddresses: List<String> = emptyList()

    /** The `LISTENING` addresses, `ADD_PEER`-ready. @see IrohTransport.connect */
    val addresses: List<String> get() = listeningAddresses

    private val records = ConcurrentHashMap<Long, LinkRecord>()
    private val listeners = CopyOnWriteArrayList<NodeLinkListener>()
    private val connections = CopyOnWriteArrayList<IrohTransport.IrohConnection>()

    /** Accepted-link Sessions, by link id — this node's half of [IrohTransport.IrohListener.sessionFor]. */
    private val acceptedSessions = ConcurrentHashMap<Long, IrohTransport.Session>()

    /** @see IrohTransport.IrohListener — one sink for every accepted link, for the same reason. */
    private val acceptedDenials = BoundaryDenials().sinkFor("hello")

    private val acceptedLinkErrors: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * Hello refusals recorded anywhere on this endpoint: accepted links plus
     * every connection this node opened. A [Verdict.CloseQuietly] is **not**
     * counted here — that is what "quiet" means.
     */
    val admissionDenialCount: Long
        get() = acceptedDenials.denialCount + connections.sumOf { it.admissionDenialCount }

    /** @see IrohTransport.IrohListener.preHelloDrops — over accepted links and every connection. */
    val preHelloDrops: Long
        get() = acceptedSessions.values.sumOf { it.preHelloDrops } + connections.sumOf { it.preHelloDrops }

    /** @see IrohTransport.IrohListener.linkErrors */
    val linkErrors: List<String>
        get() = synchronized(acceptedLinkErrors) { acceptedLinkErrors.toList() } +
            connections.flatMap { connection -> synchronized(connection.linkErrors) { connection.linkErrors.toList() } }

    /** Every link this node currently holds for [remoteNodeId], compared by the 32 bytes themselves. */
    fun links(remoteNodeId: ByteArray): List<LinkView> =
        records.values.filter { it.remoteNodeId.contentEquals(remoteNodeId) }.map { it.view() }

    /** Every link this node currently holds, whatever its peer. */
    fun links(): List<LinkView> = records.values.map { it.view() }

    /**
     * The Session of one accepted link, while that link is up — the node's
     * [IrohTransport.IrohListener.sessionFor]. Internal: it reaches a link's
     * refusal record ([IrohTransport.Session.lastAdmissionDenial]), which is
     * what a gate `Refuse` has to be checked by until a node-level surface for
     * it is decided (task `computenet-ktn1l.4`).
     */
    internal fun sessionFor(linkId: Long): IrohTransport.Session? = acceptedSessions[linkId]

    /** Register a lifecycle listener. @see NodeLinkListener */
    fun onLinkEvent(listener: NodeLinkListener) {
        listeners += listener
    }

    /**
     * Install the inbound handler and `LISTEN`. Called by
     * [IrohTransport.node]; separate from the constructor so a failure to
     * listen can close what was already built.
     */
    internal fun start(timeout: Duration) {
        accept()
        listeningAddresses = client.listen(timeout)
    }

    /**
     * Install the inbound-link handler, without listening.
     *
     * Split out of [start] for the reason [IrohTransport.Sidecar] exists: a
     * `LISTEN` needs a sidecar to answer it, while accepting a link that a
     * [FakeSidecar]-style double presents needs nothing at all.
     */
    internal fun accept() {
        client.onInboundLink { link ->
            val session = IrohTransport.Session(
                side,
                // The key this link is admitted on: the endpoint the sidecar
                // authenticated when it accepted the QUIC connection.
                link.remoteNodeId,
                send = { link.send(it) },
                refuse = { link.close() },
                admissionSink = acceptedDenials,
                gate = delegatingGate,
                direction = link.direction,
                closeQuietly = { link.close() },
                onAdmitted = { peer -> admitted(link.id, peer) },
            )
            acceptedSessions[link.id] = session
            up(link, LinkSource.ACCEPTED)
            object : LinkListener {
                override fun onData(link: SidecarLink, payload: ByteArray) = session.onData(payload)

                override fun onDown(link: SidecarLink, reason: String) {
                    acceptedSessions.remove(link.id)
                    session.onDown()
                    // An accepted link has no dialling connection and therefore
                    // no re-dial accounting: nothing here classifies its down.
                    down(link.id, null)
                }

                override fun onError(link: SidecarLink, reason: String) {
                    acceptedLinkErrors += reason
                    System.err.println(
                        "[IrohTransport] link ${link.id} error: $reason — the link is closed " +
                            "(PROTOCOL.md §2: an ERROR on an established link is terminal for it)",
                    )
                }
            }
        }
    }

    /**
     * A connection to a key **discovery** named, on this node's shared client —
     * built but **not dialled** (ktn1l-D15).
     *
     * Three things distinguish it from [connectConfigured], and each is a
     * decided rule rather than a convenience:
     *
     * - **No `ADD_PEER`.** The sidecar fed its own `MemoryLookup` before it
     *   emitted `PEER_DISCOVERED`, so the addresses are already known to the
     *   endpoint; sending them back would be the host asserting what the
     *   sidecar told it ([DSC2-DIAL-02], F3-D2).
     * - **No dial here.** The caller opens the link from its own bounded
     *   executor ([IrohTransport.IrohConnection.openLink]), because a dial
     *   blocks and the discovery policy dials many keys under one in-flight
     *   bound (F3-D8).
     * - **No re-dial loop.** [onUnplannedDown] receives every unplanned drop,
     *   with this connection's own accounting already applied, and decides
     *   whether the key is dialled again and when.
     */
    fun dialDiscovered(
        peerNodeId: ByteArray,
        redialTimeout: Duration,
        refusedDialLimit: Int = IrohTransport.REFUSED_DIAL_LIMIT,
        onUnplannedDown: (IrohTransport.IrohConnection.LinkOutcome) -> Unit,
    ): IrohTransport.IrohConnection = register(
        IrohTransport.IrohConnection(
            sidecar,
            client,
            side,
            peerNodeId,
            // Never consulted: this connection schedules no re-dial of its own.
            // A schedule that IS consulted lives on the caller's timer seam.
            backoff = { 0L },
            redialTimeout = redialTimeout,
            refusedDialLimit = refusedDialLimit,
            ownsClient = false,
            onUnplannedDown = onUnplannedDown,
            gate = delegatingGate,
            observer = observerFor(LinkSource.DISCOVERED),
        ),
    )

    /**
     * A **configured** peering under this node: [IrohTransport.connect]'s
     * semantics — `ADD_PEER`, dial now, re-dial on its own backoff loop after
     * an unplanned drop — over the node's shared endpoint instead of a second
     * one (ktn1l-D15, [DSC2-DIAL-07]).
     *
     * Returns once the link is up and this side's hello has been sent.
     */
    fun connectConfigured(
        peerNodeId: ByteArray,
        addresses: List<String>,
        timeout: Duration = 30.seconds,
        backoff: (attempt: Int) -> Long = IrohTransport.DEFAULT_RECONNECT_BACKOFF,
        redialTimeout: Duration = timeout,
        refusedDialLimit: Int = IrohTransport.REFUSED_DIAL_LIMIT,
    ): IrohTransport.IrohConnection {
        client.addPeer(peerNodeId, addresses, timeout)
        val connection = register(
            IrohTransport.IrohConnection(
                sidecar,
                client,
                side,
                peerNodeId,
                backoff = backoff,
                redialTimeout = redialTimeout,
                refusedDialLimit = refusedDialLimit,
                ownsClient = false,
                // Null: this connection keeps its own re-dial loop, exactly as
                // IrohTransport.connect's does.
                onUnplannedDown = null,
                gate = delegatingGate,
                observer = observerFor(LinkSource.CONFIGURED),
            ),
        )
        connection.openLink(timeout)
        return connection
    }

    /**
     * Close the endpoint: every connection it opened, then the shared client
     * and the sidecar process.
     *
     * The connections go first and close only their links — they hold
     * `ownsClient = false` — so the shutdown below is the single place this
     * endpoint ends.
     */
    override fun close() {
        connections.forEach { runCatching { it.close() } }
        runCatching { client.shutdown() }
        runCatching { client.close() }
        sidecar.close()
    }

    // ------------------------------------------------------------- internals

    private fun register(connection: IrohTransport.IrohConnection): IrohTransport.IrohConnection {
        connections += connection
        return connection
    }

    private fun observerFor(source: LinkSource) = object : IrohTransport.IrohConnection.LinkObserver {
        override fun onUp(link: SidecarLink) = up(link, source)
        override fun onAdmitted(linkId: Long, peer: PeerId) = admitted(linkId, peer)
        override fun onDown(linkId: Long, outcome: IrohTransport.IrohConnection.LinkOutcome?) = down(linkId, outcome)
    }

    private fun up(link: SidecarLink, source: LinkSource) {
        val record = LinkRecord(link.id, link.remoteNodeId, link.direction, source)
        records[link.id] = record
        val view = record.view()
        listeners.forEach { runCatching { it.onUp(view) } }
    }

    private fun admitted(linkId: Long, peer: PeerId) {
        val record = records[linkId] ?: return
        record.peered = true
        record.attributedPeer = peer
        val view = record.view()
        listeners.forEach { runCatching { it.onAdmitted(view) } }
    }

    private fun down(linkId: Long, outcome: IrohTransport.IrohConnection.LinkOutcome?) {
        val record = records.remove(linkId) ?: return
        val view = record.view()
        listeners.forEach { runCatching { it.onDown(view, outcome) } }
    }

    /**
     * The mutable half of a [LinkView]. Two fields move over a link's life —
     * whether it is admitted, and to whom — and both are written once, on the
     * reader thread, and read from anywhere.
     */
    private class LinkRecord(
        val linkId: Long,
        val remoteNodeId: ByteArray,
        val direction: LinkDirection,
        val source: LinkSource,
    ) {
        @Volatile
        var peered: Boolean = false

        @Volatile
        var attributedPeer: PeerId? = null

        fun view(): LinkView = LinkView(linkId, remoteNodeId, direction, source, peered, attributedPeer)
    }
}
