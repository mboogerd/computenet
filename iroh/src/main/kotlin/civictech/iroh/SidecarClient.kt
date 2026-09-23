package civictech.iroh

import civictech.iroh.SidecarProtocol.CONTROL_LINK
import civictech.iroh.SidecarProtocol.DIRECTION_INBOUND
import civictech.iroh.SidecarProtocol.Kind
import civictech.iroh.SidecarProtocol.MAX_MESSAGE_LEN
import civictech.iroh.SidecarProtocol.MSG_HEADER_LEN
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Anything the sidecar protocol refuses, times out on, or cannot carry. */
class SidecarException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Which side opened a link (`PROTOCOL.md` §3, `LINK_UP`'s direction byte). */
enum class LinkDirection {
    /** This side dialled (direction byte `0x00`). */
    OUTBOUND,

    /** This side accepted (direction byte `0x01`). */
    INBOUND,
    ;

    companion object {
        fun of(directionByte: Byte): LinkDirection =
            if (directionByte == DIRECTION_INBOUND) INBOUND else OUTBOUND
    }
}

/**
 * Events for one link, delivered on the client's single reader thread and
 * therefore in arrival order. A listener must not block that thread for long.
 */
interface LinkListener {
    /** One peer frame arrived on this link. */
    fun onData(link: SidecarLink, payload: ByteArray) {}

    /**
     * The link is down. Terminal and delivered exactly once per link, whichever
     * cause came first (`PROTOCOL.md` §3, `LINK_DOWN`).
     */
    fun onDown(link: SidecarLink, reason: String) {}

    /**
     * The sidecar refused something on this link. In reply to a `DATA` this
     * always means that frame was NOT sent.
     *
     * **This is a notification of a terminal event, not an invitation to
     * retry.** `PROTOCOL.md` §2 makes an `ERROR` on an established link terminal
     * for that link, and [SidecarClient] has already sent `CLOSE_LINK` by the
     * time this runs — [SidecarLink.send] on this link now throws, and an
     * [onDown] follows. Recover by establishing a *new* link, never by resending
     * onto this one (`computenet-ey4v`).
     */
    fun onError(link: SidecarLink, reason: String) {}
}

/**
 * Discovery events for [SidecarClient.watchPeers], delivered on the client's
 * reader thread (DSC2, aas-D8). Both callbacks must only enqueue, never dial
 * or block: the consumer that acts on discovered/expired peers is feature
 * `computenet-ktn1l`, not this client.
 */
interface PeerWatchListener {
    /** A newly seen endpoint: [nodeId] and its `ADD_PEER`-shaped [addresses]. */
    fun onDiscovered(nodeId: ByteArray, addresses: List<String>)

    /** A previously discovered endpoint expired. */
    fun onExpired(nodeId: ByteArray)
}

/**
 * One established peer link: exactly one bi-directional QUIC stream at the
 * sidecar, addressed here by its link id.
 */
class SidecarLink internal constructor(
    val id: Long,
    val remoteNodeId: ByteArray,
    val direction: LinkDirection,
    private val client: SidecarClient,
) {
    internal val listenerRef = java.util.concurrent.atomic.AtomicReference<LinkListener>(null)
    internal val downDelivered = AtomicBoolean(false)
    internal val peerSpoke = CountDownLatch(1)

    /**
     * For a link that answers this host's `DIAL`: open once the dialling thread
     * has decided the link's fate — returned it to its caller, or abandoned it
     * (computenet-c45fr). The reader passes it before reading [listenerRef], so
     * no event reaches the dial's listener while that decision is pending.
     * Null for an accepted link, which has no dial to wait for.
     */
    @Volatile
    internal var dialDecided: CountDownLatch? = null

    /**
     * Set the moment the sidecar reports an `ERROR` on this link, before the
     * `CLOSE_LINK` that answers it and well before the `LINK_DOWN` that answers
     * *that*. Nothing may be sent in the window between the two.
     */
    internal val refused = AtomicBoolean(false)

    /**
     * True once the sidecar reported an `ERROR` on this link and the client
     * closed it for that reason (`PROTOCOL.md` §2, Backpressure). Distinct from
     * an ordinary [LinkListener.onDown]: it says the link ended because
     * something on it was refused, not because a peer or a caller ended it.
     */
    val refusedAndClosed: Boolean get() = refused.get()

    /** True once at least one `DATA` has arrived from the peer on this link. */
    val peerHasSpoken: Boolean get() = peerSpoke.count == 0L

    /**
     * Send one peer frame.
     *
     * On an INBOUND link this first waits, up to [awaitPeerFirstFrame], for the
     * peer's first frame. That is the avoidance path `PROTOCOL.md` §3's
     * `LINK_UP` entry advises and not an optimisation: until the dialler speaks,
     * the accepting sidecar has not adopted the link's QUIC stream, so that
     * link's send queue has no consumer at all and the 256-frame bound is an
     * absolute count. See [SidecarClient]'s KDoc.
     *
     * @throws SidecarException when the link is down, when the sidecar has
     *   already refused something on it ([refusedAndClosed]), when the wait
     *   expires, or when the payload exceeds [SidecarProtocol.MAX_FRAME_LEN].
     */
    fun send(payload: ByteArray, awaitPeerFirstFrame: Duration = 30.seconds) {
        if (payload.size > SidecarProtocol.MAX_FRAME_LEN) {
            throw SidecarException("frame of ${payload.size} bytes exceeds MAX_FRAME_LEN (${SidecarProtocol.MAX_FRAME_LEN})")
        }
        // PROTOCOL.md §2: an ERROR on an established link is terminal for it, so
        // the refusal closes the send path immediately rather than at the
        // LINK_DOWN that follows — a frame written into that window would be
        // refused in its turn, or worse, accepted behind a hole.
        if (refused.get()) {
            throw SidecarException(
                "link $id was closed after the sidecar refused something on it; " +
                    "recover on a new link, never by resending on this one (PROTOCOL.md §2, Backpressure)",
            )
        }
        if (downDelivered.get()) throw SidecarException("link $id is down")
        if (direction == LinkDirection.INBOUND && !peerHasSpoken) {
            val ok = peerSpoke.await(awaitPeerFirstFrame.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!ok) {
                throw SidecarException(
                    "link $id was accepted and the peer has not spoken within $awaitPeerFirstFrame; " +
                        "sending now would queue against an unadopted stream (PROTOCOL.md §3, LINK_UP)",
                )
            }
        }
        client.sendMessage(HostMessage.Data(id, payload))
    }

    /** Ask the sidecar to take this link down. A [LinkListener.onDown] follows. */
    fun close() {
        if (downDelivered.get()) return
        client.sendMessage(HostMessage.CloseLink(id))
    }

    override fun toString(): String = "SidecarLink(id=$id, direction=$direction, remote=${remoteNodeId.toHex()})"
}

/**
 * The JVM half of the sidecar's local-socket protocol: owns the TCP socket to
 * `127.0.0.1:<port>`, runs one reader thread that dispatches every sidecar → host
 * message, and offers request/reply for the control verbs plus per-link send.
 *
 * `iroh/sidecar/PROTOCOL.md` is the normative contract; this class does not
 * weaken it.
 *
 * ## Backpressure: a link's send queue REFUSES
 *
 * Both of the sidecar's queues are bounded at 256 messages and they answer a
 * full buffer differently (`PROTOCOL.md` §2, Backpressure). The socket writer's
 * queue **waits**. A link's send queue **refuses**: a `DATA` arriving while that
 * link already has 256 frames outstanding is answered with `ERROR` on that link
 * and is **not sent**. Refusing is what keeps the sidecar's single message loop
 * free, so the host connection stays responsive throughout — `GET_ID`,
 * `CLOSE_LINK` and `SHUTDOWN` still answer, on every link including the flooded
 * one. The price is that `DATA` is refusable rather than unconditionally
 * accepted, and an `ERROR` in reply to a `DATA` never means the frame went out.
 *
 * The sharpest case is a **freshly accepted (inbound) link whose dialler has not
 * yet spoken**: QUIC reveals a stream to the peer only when its opener first
 * writes, so the accepting sidecar has not adopted the stream and that link's
 * queue has *no consumer at all*. The bound is then an absolute count, not a
 * rate: the 257th `DATA` and every one after it is refused until the dialler
 * speaks.
 *
 * ## The recovery rule: a refusal ends the link (`computenet-ey4v`)
 *
 * **An `ERROR` on an established link is terminal for that link.** On one, this
 * client sets [SidecarLink.refusedAndClosed], sends `CLOSE_LINK` on that id, and
 * only then calls [LinkListener.onError]; [SidecarLink.send] throws from that
 * moment, and the `LINK_DOWN` the close produces arrives as an ordinary
 * [LinkListener.onDown]. A host recovers by establishing a **new** link and
 * resynchronising over it — [IrohTransport.IrohConnection] already does exactly
 * that for every `LINK_DOWN`, with a fresh mirror and a full `announceTo` sweep
 * — never by resending onto the link that was refused.
 *
 * The rule is stated over the link because the link is all the wire names, and
 * that is precisely what makes it implementable. An `ERROR` carries a kind byte,
 * an 8-byte link id and a UTF-8 reason and **no sequence number**, while an
 * *accepted* `DATA` is answered with nothing at all. A host with more than one
 * `DATA` outstanding on a link therefore learns that *one* frame on that link
 * was refused and never *which one* — and when the link's consumer drains
 * concurrently, acceptances and refusals interleave, so the refused frames are
 * not even the last ones sent. So no resend rule could be written, at any
 * header: identifying the frame needs a sequence number, and resending it *in
 * order* additionally needs an acknowledgement of acceptance on every accepted
 * frame — the per-frame reply the refusing bound exists to avoid. Discarding the
 * link discards the ordering context along with the hole in it, and needs
 * nothing from the wire that is not already there.
 *
 * Closing is the fallback, not the plan: this client still **avoids the
 * refusal**. [SidecarLink.send] on an inbound link waits for the peer's first
 * frame before sending (§3, `LINK_UP`), and callers that keep their own
 * outstanding `DATA` on a link within the bound never meet the rule above.
 *
 * ## Discovery events (DSC2, `aas-D8`)
 *
 * [watchPeers] registers a [PeerWatchListener] and sends `WATCH_PEERS`; once
 * the sidecar answers `WATCHING`, `PEER_DISCOVERED` and `PEER_EXPIRED` frames
 * are delivered to that listener on the reader thread, in arrival order, like
 * every other event this client dispatches. A malformed discovery frame is
 * counted in [malformedDiscoveryEvents] and the reader keeps going — the one
 * exception to "a bad frame ends the reader," because discovery is
 * best-effort mDNS input and must not be able to take the whole socket down
 * (`DSC2-MDNS-06`). A sidecar started without `--mdns` still answers
 * `WATCHING` and simply never emits.
 */
class SidecarClient(
    private val socket: Socket,
    private val defaultTimeout: Duration = 30.seconds,
) : AutoCloseable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = ReentrantLock()

    /** One control request at a time; the sidecar answers them in order. */
    private val controlLock = ReentrantLock()
    private val controlReplies = ArrayBlockingQueue<SidecarMessage>(1)

    private val nextLinkId = AtomicLong(1)
    private val links = ConcurrentHashMap<Long, SidecarLink>()
    private val pendingDials = ConcurrentHashMap<Long, PendingDial>()

    @Volatile
    private var inboundHandler: ((SidecarLink) -> LinkListener)? = null

    @Volatile
    private var peerWatchListener: PeerWatchListener? = null

    private val malformedDiscoveryEventCount = AtomicLong()

    /**
     * Count of `PEER_DISCOVERED`/`PEER_EXPIRED` frames the reader could not
     * decode (`DSC2-MDNS-06`). The reader continues dispatching after each one
     * — see [readLoop] — unlike every other kind's malformed frame, which ends
     * the reader as before.
     */
    val malformedDiscoveryEvents: Long get() = malformedDiscoveryEventCount.get()

    private val closed = AtomicBoolean(false)

    @Volatile
    private var readerFailure: Throwable? = null

    private val reader = Thread({ readLoop() }, "iroh-sidecar-reader").apply {
        isDaemon = true
        start()
    }

    // ---------------------------------------------------------------- control

    /** `GET_ID` → `ID`. The 32-byte endpoint id of the sidecar's own endpoint. */
    fun getId(timeout: Duration = defaultTimeout): ByteArray =
        control(HostMessage.GetId, timeout, "GET_ID") { it as? SidecarMessage.Id }.nodeId

    /** `LISTEN` → `LISTENING`. The bound UDP socket addresses, `ADD_PEER`-ready. */
    fun listen(timeout: Duration = defaultTimeout): List<String> =
        control(HostMessage.Listen, timeout, "LISTEN") { it as? SidecarMessage.Listening }.addresses

    /** `ADD_PEER` → `PEER_ADDED`. Teaches this endpoint how to reach [nodeId] offline. */
    fun addPeer(nodeId: ByteArray, addresses: List<String>, timeout: Duration = defaultTimeout): ByteArray =
        control(HostMessage.AddPeer(nodeId, addresses), timeout, "ADD_PEER") { it as? SidecarMessage.PeerAdded }.nodeId

    /**
     * `WATCH_PEERS` → `WATCHING`. [listener] is registered BEFORE the request
     * goes out, so no `PEER_DISCOVERED`/`PEER_EXPIRED` can arrive before
     * registration. Idempotent: a second call replaces the listener and
     * re-sends `WATCH_PEERS`, which the sidecar answers with `WATCHING` again
     * without starting anything new (`PROTOCOL.md` §3, DSC2 aas-D8).
     */
    fun watchPeers(listener: PeerWatchListener, timeout: Duration = defaultTimeout) {
        peerWatchListener = listener
        control(HostMessage.WatchPeers, timeout, "WATCH_PEERS") { it as? SidecarMessage.Watching }
    }

    private fun <T : SidecarMessage> control(
        request: HostMessage,
        timeout: Duration,
        name: String,
        narrow: (SidecarMessage) -> T?,
    ): T = controlLock.withLock {
        controlReplies.clear()
        sendMessage(request)
        val reply = controlReplies.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            ?: throw SidecarException("$name got no reply within $timeout${readerFailureSuffix()}")
        narrow(reply) ?: when (reply) {
            is SidecarMessage.Failure -> throw SidecarException("$name refused: ${reply.reason}")
            else -> throw SidecarException("$name answered with an unexpected $reply")
        }
    }

    // ------------------------------------------------------------------ links

    /**
     * Install the handler invoked for each INBOUND link the sidecar accepts.
     *
     * It runs on the reader thread when `LINK_UP` arrives and returns the
     * listener for that link, so no `DATA` can precede registration. Call before
     * [listen].
     */
    fun onInboundLink(handler: (SidecarLink) -> LinkListener) {
        inboundHandler = handler
    }

    /**
     * `DIAL` on a freshly allocated odd link id, then wait for the asynchronous
     * `LINK_UP` (or `ERROR`) on that id. [listener] is registered before the
     * `DIAL` goes out, so no frame on the new link can be missed.
     *
     * ## An abandoned dial leaves no link behind (computenet-r2zhu, computenet-r3301)
     *
     * A dial that throws — its wait timed out, or its thread was interrupted —
     * has already written its `DIAL`, and the `LINK_UP` answering it is the
     * host's own link that no caller will ever hold. This client closes it,
     * whenever it arrives:
     *
     * - **before** the dial gives up: the reader has settled the dial and
     *   registered the link, and the dial throws anyway (an interrupt still
     *   makes `CountDownLatch.await` throw at a zero count, because it checks
     *   the flag first). The dial unregisters the link, detaches [listener]
     *   from it and sends `CLOSE_LINK` before it throws.
     * - **while** it gives up: settlement and abandonment are one
     *   compare-and-set on [PendingDial.outcome], so exactly one of the reader
     *   and the dial owns the link's fate, and the reader closes a link whose
     *   dial was already abandoned.
     * - **after**: the pending entry is gone, and a `LINK_UP` with direction
     *   OUTBOUND and no pending dial is closed rather than handed to the
     *   inbound handler (see [onLinkUp]).
     *
     * In every case [listener] sees nothing, the link is not in [link], and
     * the exception the dial throws is the one it threw before this rule:
     * [InterruptedException] for an interrupt, [SidecarException] for a
     * timeout.
     *
     * "Sees nothing" holds for the first case too, although the reader goes on
     * dispatching after it settles the dial and may meet a `LINK_DOWN`,
     * `ERROR` or `DATA` for the link before the dial gets to abandon it
     * (computenet-c45fr). Every event for a dialled link waits, on the reader
     * thread, until the dial has decided: [listener] gets the event if the dial
     * returned the link, and nobody does if it abandoned it. The wait is only
     * on the dialling thread's remaining steps between its `DIAL` write and
     * that decision, and none of them blocks — the `CLOSE_LINK` an
     * abandonment writes comes after it — so outside tests (which hold that
     * thread in [beforeDialAwait]) it lasts as long as the thread takes to be
     * scheduled. Events are still delivered on the reader thread, in order.
     */
    fun dial(peerId: ByteArray, listener: LinkListener, timeout: Duration = defaultTimeout): SidecarLink {
        val id = nextLinkId.getAndAdd(2)
        val pending = PendingDial(listener)
        pendingDials[id] = pending
        try {
            sendMessage(HostMessage.Dial(id, peerId))
            beforeDialAwait?.invoke(id)
            val settled = try {
                pending.latch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                abandon(id, pending)
                throw e
            }
            if (!settled) {
                abandon(id, pending)
                throw SidecarException("DIAL on link $id got no LINK_UP within $timeout${readerFailureSuffix()}")
            }
            return when (val outcome = pending.outcome.get()) {
                is DialOutcome.Up -> outcome.link
                is DialOutcome.Refused -> throw SidecarException("DIAL on link $id refused: ${outcome.reason}")
                else -> throw SidecarException("DIAL on link $id settled with neither LINK_UP nor ERROR")
            }
        } finally {
            pendingDials.remove(id)
            // Whatever the outcome, the reader may deliver to (or past) this
            // dial's link from here on. Idempotent after [abandon].
            pending.decided.countDown()
        }
    }

    /**
     * The dial on [id] is giving up. If the reader has not settled it yet,
     * mark it abandoned so the reader closes whatever `LINK_UP` it later
     * finds; if the reader already settled it with a link, that link is this
     * thread's to close.
     */
    private fun abandon(id: Long, pending: PendingDial) {
        if (pending.outcome.compareAndSet(null, DialOutcome.Abandoned)) return
        val up = pending.outcome.get() as? DialOutcome.Up ?: return
        up.link.listenerRef.set(null)
        links.remove(id, up.link)
        // Before the write below, which can block: the reader may be waiting
        // on this decision, and must not wait on the socket too.
        pending.decided.countDown()
        closeAbandoned(id)
    }

    /**
     * `CLOSE_LINK` for a link no caller holds. The raw message rather than
     * [SidecarLink.close]: such a link is not registered, so its `LINK_DOWN`
     * (and the "no such link" `ERROR` a close racing it may draw) is dropped
     * by [dispatch] without reaching anyone.
     */
    private fun closeAbandoned(id: Long) {
        runCatching { sendMessage(HostMessage.CloseLink(id)) }
    }

    /** The link with this id, while it is up. */
    fun link(id: Long): SidecarLink? = links[id]

    /** Every link this client currently has registered — what a test of "no link nobody holds" reads. */
    internal val openLinks: List<SidecarLink> get() = links.values.toList()

    /**
     * Test seam (computenet-r2zhu): runs on the dialling thread with the link
     * id, after the `DIAL` is written and before the wait for its answer.
     * It is how a test puts an interrupt in the window between the reader's
     * settlement and the dial's return, which is otherwise microseconds wide.
     * Null, and never set, outside tests.
     */
    @Volatile
    internal var beforeDialAwait: ((Long) -> Unit)? = null

    // ------------------------------------------------------------------- send

    internal fun sendMessage(message: HostMessage) {
        if (closed.get()) throw SidecarException("client is closed")
        val bytes = SidecarCodec.encode(message)
        writeLock.withLock {
            try {
                output.write(bytes)
                output.flush()
            } catch (e: IOException) {
                throw SidecarException("writing $message failed", e)
            }
        }
    }

    /** `SHUTDOWN`: close every link and end the sidecar process. Best effort. */
    fun shutdown() {
        runCatching { sendMessage(HostMessage.Shutdown) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        reader.join(TimeUnit.SECONDS.toMillis(5))
    }

    // ----------------------------------------------------------------- reader

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val declared = input.readInt()
                if (declared < MSG_HEADER_LEN || declared > MAX_MESSAGE_LEN) {
                    throw SidecarException("sidecar sent a length of $declared, outside [$MSG_HEADER_LEN, $MAX_MESSAGE_LEN]")
                }
                val body = ByteArray(declared)
                input.readFully(body)
                val frame = SidecarCodec.decodeBody(body, 0, declared)
                when (val decoded = SidecarCodec.asSidecarMessage(frame)) {
                    is Decoded.Ok -> dispatch(decoded.message)
                    is Decoded.Malformed ->
                        if (frame.kind == Kind.PEER_DISCOVERED || frame.kind == Kind.PEER_EXPIRED) {
                            // DSC2-MDNS-06 (BS-10): a malformed discovery frame is
                            // counted and the reader keeps dispatching — discovery
                            // is best-effort and one bad frame from mDNS must not
                            // take down the whole socket. Every other kind's
                            // malformed frame still ends the reader below.
                            malformedDiscoveryEventCount.incrementAndGet()
                        } else {
                            throw SidecarException("undecodable message from sidecar: ${decoded.problem} — ${decoded.detail}")
                        }
                }
            }
        } catch (e: EOFException) {
            readerFailure = e
        } catch (e: IOException) {
            if (!closed.get()) readerFailure = e
        } catch (e: Throwable) {
            readerFailure = e
        } finally {
            failEverythingOutstanding()
        }
    }

    private fun dispatch(message: SidecarMessage) {
        when (message) {
            is SidecarMessage.Id, is SidecarMessage.Listening, is SidecarMessage.PeerAdded, is SidecarMessage.Watching ->
                controlReplies.offer(message)

            is SidecarMessage.PeerDiscovered -> peerWatchListener?.onDiscovered(message.nodeId, message.addresses)

            is SidecarMessage.PeerExpired -> peerWatchListener?.onExpired(message.nodeId)

            is SidecarMessage.LinkUp -> onLinkUp(message)

            is SidecarMessage.Data -> {
                val link = links[message.link] ?: return
                link.peerSpoke.countDown()
                listenerOf(link)?.onData(link, message.payload)
            }

            is SidecarMessage.LinkDown -> {
                val link = links.remove(message.link) ?: return
                link.peerSpoke.countDown()
                if (link.downDelivered.compareAndSet(false, true)) {
                    listenerOf(link)?.onDown(link, message.reason)
                }
            }

            is SidecarMessage.Failure -> {
                if (message.link == CONTROL_LINK) {
                    controlReplies.offer(message)
                    return
                }
                val pending = pendingDials[message.link]
                if (pending != null) {
                    // An abandoned dial's refusal has nothing left to tell anyone.
                    pending.outcome.compareAndSet(null, DialOutcome.Refused(message.reason))
                    pending.latch.countDown()
                    return
                }
                val link = links[message.link] ?: return
                // PROTOCOL.md §2, Backpressure (computenet-ey4v): an ERROR on an
                // ESTABLISHED link is terminal for that link, so the client takes
                // it down here rather than leaving the rule to each host of this
                // class. The CAS is what stops the loop when CLOSE_LINK itself is
                // answered with "no such link" — a race with a LINK_DOWN already
                // in flight, and the one ERROR this could otherwise ping-pong on.
                if (link.refused.compareAndSet(false, true)) {
                    runCatching { sendMessage(HostMessage.CloseLink(link.id)) }
                }
                listenerOf(link)?.onError(link, message.reason)
            }
        }
    }

    private fun onLinkUp(message: SidecarMessage.LinkUp) {
        val direction = LinkDirection.of(message.direction)
        val link = SidecarLink(message.link, message.remoteNodeId, direction, this)
        val pending = pendingDials[message.link]
        if (pending != null) {
            // Registered BEFORE the CAS: once the CAS says Up, an interrupted
            // dial may be in `abandon` at once, and must find the link to remove.
            link.listenerRef.set(pending.listener)
            link.dialDecided = pending.decided
            links[message.link] = link
            // One CAS against the dial's abandonment (computenet-r2zhu): if the
            // dial gave up first, this link is nobody's and is closed here.
            if (pending.outcome.compareAndSet(null, DialOutcome.Up(link))) {
                pending.latch.countDown()
            } else {
                link.listenerRef.set(null)
                links.remove(message.link, link)
                closeAbandoned(message.link)
            }
            return
        }
        if (direction == LinkDirection.OUTBOUND) {
            // computenet-r3301: the sidecar reports the QUIC connection's
            // direction, so an OUTBOUND link is one this host dialled — and
            // with no dial waiting on its id it is the answer to a dial that
            // already gave up. Not an accepted link: close it, register
            // nothing, and keep it from the inbound handler.
            closeAbandoned(message.link)
            return
        }
        val handler = inboundHandler
        if (handler == null) {
            // Nobody asked for inbound links; take it down rather than leaving a
            // link nobody drains.
            links[message.link] = link
            link.close()
            return
        }
        link.listenerRef.set(handler(link))
        links[message.link] = link
    }

    /**
     * The listener an event on [link] goes to, once it may go anywhere. For a
     * dialled link that is after the dial has decided (see [dial]); an
     * abandoned dial has cleared [SidecarLink.listenerRef] by then.
     */
    private fun listenerOf(link: SidecarLink): LinkListener? {
        link.dialDecided?.let { decided ->
            decided.await()
            link.dialDecided = null
        }
        return link.listenerRef.get()
    }

    private fun failEverythingOutstanding() {
        val reason = readerFailure?.let { "reader stopped: $it" } ?: "connection closed"
        pendingDials.values.forEach {
            it.outcome.compareAndSet(null, DialOutcome.Refused(reason))
            it.latch.countDown()
        }
        links.values.toList().forEach { link ->
            links.remove(link.id)
            link.peerSpoke.countDown()
            if (link.downDelivered.compareAndSet(false, true)) listenerOf(link)?.onDown(link, reason)
        }
    }

    private fun readerFailureSuffix(): String = readerFailure?.let { " (reader thread stopped: $it)" } ?: ""

    /**
     * One dial's wait. [outcome] is set exactly once, by whichever of the
     * reader (settling it) and the dialling thread (abandoning it) gets there
     * first — the atomic hand-off [dial]'s KDoc describes.
     */
    private class PendingDial(val listener: LinkListener) {
        val latch = CountDownLatch(1)

        /** Counted down once the dialling thread has decided; see [SidecarLink.dialDecided]. */
        val decided = CountDownLatch(1)
        val outcome = AtomicReference<DialOutcome?>(null)
    }

    private sealed interface DialOutcome {
        class Up(val link: SidecarLink) : DialOutcome
        class Refused(val reason: String) : DialOutcome
        data object Abandoned : DialOutcome
    }

    companion object {
        /** Connect to a sidecar listening on `127.0.0.1:[port]`. */
        fun connect(port: Int, timeout: Duration = 30.seconds): SidecarClient {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeout.inWholeMilliseconds.toInt())
            socket.tcpNoDelay = true
            return SidecarClient(socket)
        }
    }
}
