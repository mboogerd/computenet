package civictech.iroh

import civictech.cell.BoundaryDenial
import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.host.IntakeClosedException
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryMirrorCell
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The iroh transport driver (DSC0, epic `computenet-egl`, feature
 * `computenet-egl.2`): the same peering `:wire`'s `WsTransport` establishes,
 * carried over an iroh QUIC stream instead of a WebSocket.
 *
 * Nothing about the *model* changes here, and that is the point. Frames from a
 * [BridgeEgressCell] go out as `DATA` on one sidecar link; inbound `DATA` is
 * handed — still encoded — to a bridge-hosted ingress
 * ([Peering.hostIngress]), so the sidecar's reader thread never runs framework
 * logic. The first `DATA` each way is a hello carrying the local mirror's
 * [CellRef]; receiving it wires announcements ([Peering.announceTo]) and the two
 * peers become one graph. The kernel is untouched: `:iroh -> :kernel`, never the
 * reverse (feature rule 2).
 *
 * ## The hello is a link-local grammar, and the identity it asserts is interim
 *
 * The sidecar protocol has no text/binary split the way a WebSocket does
 * (`iroh/sidecar/PROTOCOL.md` §3: `DATA` is `DATA`), so the hello cannot be told
 * from a wire frame by its *kind*. It is told apart by its **position**: the
 * first `DATA` frame on a link is the hello and every later one is an opaque
 * `WireCodec` frame. `PROTOCOL.md` §3 guarantees per-link delivery order, which
 * is exactly the premise `WsTransport` takes from WebSocket message order, so
 * hello-before-announcements holds here for the same reason it holds there
 * (egl.2-D1). The bytes are new — `IROH-HELLO1 <mirrorRef>[ <peerName>]` in
 * UTF-8, see [IrohTransport.HELLO_PREFIX] — and are deliberately *not*
 * `WsTransport`'s: `[DSC1-HELLO-10]` freezes that line's bytes for that
 * transport, and nothing here may claim to speak it.
 *
 * **egl.2-D4, stated so nobody reads the interim as the design: the [PeerId] a
 * peering over this transport carries is the one the peer ASSERTS in its
 * hello.** It is not derived from, or checked against, the iroh NodeId that
 * actually authenticated the QUIC connection — this feature keeps today's
 * `:wire` identity semantics unchanged so that the transport substitution is the
 * only variable. Deriving the `PeerId` from the NodeId, and demonstrating
 * admission as a public-key allowlist, is feature `computenet-egl.3`; until it
 * lands, a hello-asserted name over iroh proves no more than a hello-asserted
 * name over a WebSocket does.
 *
 * ## Scope
 *
 * One sidecar child process per transport instance (egl.2-D2): [listen] and
 * [connect] each spawn their own and own its lifetime.
 *
 * A peering **survives its link**. A dialled [IrohConnection] holds a
 * *succession* of [Session]s, one per link instance: a link that goes down
 * unplanned is re-dialled on an injectable backoff schedule
 * ([DEFAULT_RECONNECT_BACKOFF]), and the link that replaces it gets a fresh
 * Session with a fresh mirror. The Session that went down stays down — its
 * mirror is detached for good, and nothing re-opens it (computenet-dqy.14; see
 * [Session]). A close this side *asked* for ([IrohConnection.sever],
 * [IrohConnection.close]) is never re-dialled, which is `WsTransport`'s
 * planned-close discipline.
 */
object IrohTransport {

    /**
     * The hello line's prefix, trailing space included. A hello is
     * `IROH-HELLO1 <mirrorRef>` optionally followed by ` <peerName>`; the ref is
     * a [UUID] in its canonical form, which contains no space, so the split is
     * unambiguous however the name is spelt.
     *
     * The version digit is part of the token rather than a separate field
     * because the whole grammar is one line: a future `IROH-HELLO2` is a
     * different prefix and cannot be misread as this one with an extra token.
     */
    const val HELLO_PREFIX: String = "IROH-HELLO1 "

    /**
     * The production re-dial backoff: fixed doubling from 1s, capped at 30s,
     * retrying forever. `attempt` is 0-based — the delay *before* the
     * (attempt+1)-th re-dial.
     *
     * The values are `WsTransport.DEFAULT_RECONNECT_BACKOFF`'s (M10.3), and the
     * seam is the same T12 seam: a test injects a near-zero schedule instead of
     * paying wall-clock delays. The *type* is declared here rather than imported
     * from `:wire` deliberately — this module's whole dependency claim is
     * `:iroh -> :kernel` (feature rule 2), and taking a `:wire` dependency for
     * one lambda type would couple two transports that have no business on each
     * other's classpath. The cost is one duplicated four-line lambda; the price
     * of the alternative is a module edge.
     */
    val DEFAULT_RECONNECT_BACKOFF: (attempt: Int) -> Long = { attempt ->
        // guard overflow on a long-lived failing connection: cap the shift itself
        (1_000L shl attempt.coerceAtMost(20)).coerceAtMost(30_000L)
    }

    /**
     * Serve peerings on a fresh sidecar. Returns once the sidecar is listening;
     * [IrohListener.nodeId] and [IrohListener.addresses] are what a dialler
     * needs ([connect]'s two first arguments).
     *
     * [binary] is the sidecar executable — in tests, `SidecarBinary.orSkip()`.
     * [sidecarArgs] are passed to the child verbatim (see [SidecarProcess.spawn]):
     * pinning `--secret-key` and `--bind-addr` is what lets a listener come back
     * at the same endpoint after its process died, which is how an *unplanned*
     * drop is staged.
     */
    fun listen(
        side: Peering.Side,
        binary: Path,
        timeout: Duration = 30.seconds,
        stderrSink: (String) -> Unit = {},
        sidecarArgs: List<String> = emptyList(),
    ): IrohListener {
        val process = SidecarProcess.spawn(binary, stderrSink = stderrSink, args = sidecarArgs)
        val listener = try {
            IrohListener(process, process.connect(timeout), side)
        } catch (e: Throwable) {
            process.close()
            throw e
        }
        try {
            listener.start(timeout)
        } catch (e: Throwable) {
            listener.close()
            throw e
        }
        return listener
    }

    /**
     * Dial [peerNodeId] on a fresh sidecar and open one peering over the
     * resulting link. [peerAddresses] are the peer's `LISTENING` addresses
     * (`ADD_PEER`, `PROTOCOL.md` §3) — offline dialling, no discovery service.
     *
     * Returns once the link is up and this side's hello has been sent; the
     * peer's hello, admission and announcements follow asynchronously on the
     * sidecar's reader thread.
     *
     * The returned connection **outlives its link**: an unplanned `LINK_DOWN`
     * re-dials [peerNodeId] on [backoff], each re-dial bounded by
     * [redialTimeout]. Both are seams for tests — a near-zero schedule and a
     * short dial timeout make reconnect observable without wall-clock waits.
     */
    fun connect(
        side: Peering.Side,
        peerNodeId: ByteArray,
        peerAddresses: List<String>,
        binary: Path,
        timeout: Duration = 30.seconds,
        stderrSink: (String) -> Unit = {},
        backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF,
        redialTimeout: Duration = timeout,
        sidecarArgs: List<String> = emptyList(),
    ): IrohConnection {
        val process = SidecarProcess.spawn(binary, stderrSink = stderrSink, args = sidecarArgs)
        return try {
            val client = process.connect(timeout)
            client.addPeer(peerNodeId, peerAddresses, timeout)
            IrohConnection(process, client, side, peerNodeId, backoff, redialTimeout)
                .also { it.openLink(timeout) }
        } catch (e: Throwable) {
            process.close()
            throw e
        }
    }

    /**
     * One peer link: bridge cells and mirroring on the local side, `DATA` frames
     * on the wire. The direct analogue of `WsTransport.Session`, and deliberately
     * the same shape — hello, admission, mirror, ingress, announcements, in that
     * order — because the whole claim of this feature is that only the transport
     * changed.
     *
     * A Session is built per **link**, never reused across links. That is the
     * whole difference from `WsTransport.Session`, which keeps *one* Session
     * across every reconnect and re-opens its state on each re-hello: here the
     * state a reconnect would have to reset does not survive to be reset, and
     * [IrohConnection] holds a succession of Sessions instead — one per link
     * instance, each retired for good by [onDown].
     *
     * ## The mirror is per link INSTANCE (computenet-dqy.14)
     *
     * [RegistryMirrorCell.peer]'s KDoc argues the late bind safe from four
     * premises; each has an analogue here and none is inherited by assertion.
     * All four are stated **about one link instance**, which is why they survive
     * reconnect unchanged: a re-dial creates a different Session over a different
     * link id, sharing no mirror, no egress and no ingress with the one it
     * replaces, so there is no cross-instance ordering left for them to be about
     * (see [IrohConnection.openLink] for the one cross-instance ordering there
     * is, and why it is not a premise of any of them):
     *
     * 1. **Our hello, carrying this instance's mirror ref, is written before we
     *    announce anything.** On a dialled link it is the first `DATA` we send
     *    ([openLocalHello] at the end of [IrohConnection.openLink], on the first
     *    dial and on every re-dial alike); on an accepted link it is
     *    sent from [onHello], still before [bindAndAnnounce] — see [onHello] for
     *    why the accepting side waits.
     * 2. **The peer cannot address this mirror before it has read that hello**,
     *    because the ref exists nowhere else: it is minted in [hello] and
     *    published to nobody but this link.
     * 3. **The peer announces only while handling our hello**, which is strictly
     *    earlier in our→peer order than any frame it sends afterwards.
     * 4. **Per-link delivery preserves order** (`PROTOCOL.md` §3), and
     *    [SidecarClient]'s single reader thread dispatches one link's messages in
     *    arrival order. So [onHello] runs, and returns, before [onData] can route
     *    a single frame — [ingress] does not exist until it does.
     *
     * On top of those, and independently of all of them: a frame arriving while
     * [ingress] is null is dropped and counted on [preHelloDrops], so a refused
     * hello leaves nothing routable and the admission decision cannot be raced by
     * a frame that beats it.
     *
     * A link that goes down detaches its mirror **permanently** ([onDown]); there
     * is no way to re-open one, so a frame the sidecar had already staged for a
     * dead link is refused at a shut gate rather than re-installing a
     * `LocationRegistry.Remote` for a ref the peer may since have dropped.
     *
     * ## What happens if a link's send queue is full
     *
     * The sidecar bounds a link's send queue at 256 messages and **refuses**
     * rather than waits (`PROTOCOL.md` §2/§3, computenet-3gij, merged as
     * `4679de93c`): a `DATA` arriving with 256 frames already outstanding on that
     * link is answered with `ERROR` **on that link** and is **not sent**, while
     * the host connection stays responsive throughout — `GET_ID`, `CLOSE_LINK`
     * and `SHUTDOWN` still answer, on every link including the flooded one. So
     * the failure mode is a lost frame on one link, never a wedged sidecar.
     *
     * This Session does not attempt to recover from such a refusal, and that is a
     * deliberate limit rather than an oversight: **`computenet-ey4v` is the open
     * residual.** An `ERROR` carries a kind byte, a link id and a UTF-8 reason
     * and no sequence number, while an *accepted* `DATA` is answered with nothing
     * at all — so a host with more than one `DATA` outstanding learns that *one*
     * frame on the link was refused and never *which one*, and a blind resend
     * would reorder the link. What a host is REQUIRED to do about that is
     * unsettled and is `computenet-ey4v`'s to settle; nothing here forecloses any
     * of its candidate remedies (this file encodes no frame identifier of its own
     * and alters no `DATA` header).
     *
     * What this Session does instead is take the one route `PROTOCOL.md` already
     * sanctions: **avoid the refusal.** [SidecarLink.send] on an accepted
     * (inbound) link waits for the dialler's first frame before sending (§3,
     * `LINK_UP`), which is where an unadopted stream's queue would otherwise fill
     * with no consumer at all; and this side's own traffic on a healthy peering
     * is a hello plus a catch-up burst bounded by the local registry, both of
     * which the peer is actively draining. A refusal that happens anyway is
     * reported through [IrohListener.linkErrors] / [IrohConnection.linkErrors]
     * rather than absorbed.
     */
    internal class Session(
        private val side: Peering.Side,
        private val send: (ByteArray) -> Unit,
        private val refuse: () -> Unit,
        /**
         * Seam-1 accounting for a refused hello (spec 40/43 seam 1, `[SEC1-07]`),
         * supplied by the caller for the reason `WsTransport.Session`'s is: a
         * refused hello closes its link, the listener drops the Session on the
         * resulting `LINK_DOWN`, and a sink owned by the Session would be
         * discarded together with the very refusal it recorded. [IrohListener]
         * therefore allocates one and hands it to every Session it opens.
         */
        private val admissionSink: BoundaryDenialSink = BoundaryDenials().sinkFor("hello"),
    ) {
        /**
         * This side's announcement signer is borrowed from the `Peering.Side`,
         * exactly as `WsTransport.Session` borrows it — null on a side with no
         * signing configuration, which encodes byte-identically to an unsigned
         * frame.
         */
        val egress = BridgeEgressCell(signer = side.announcementSigner)

        /** @see Session — minted by [hello], retired for good by [onDown]. */
        @Volatile
        private var mirror: RegistryMirrorCell? = null

        @Volatile
        private var ingress: Propagate<ByteArray>? = null

        /**
         * Whether the first `DATA` on this link has arrived. The grammar is
         * positional (see [IrohTransport]'s KDoc), so this is what says whether
         * the next frame is a hello or wire traffic — and, once a hello has been
         * refused, what keeps every later frame on the drop path.
         */
        @Volatile
        private var helloSeen = false

        @Volatile
        private var announcement: AutoCloseable? = null

        /**
         * Frames dropped because no admitted hello had installed an [ingress] yet
         * — the `WsTransport.Session.preHelloDrops` analogue.
         *
         * On this transport the *first* frame is the hello by construction, so
         * what this counts is the frames that follow a hello which was **not**
         * admitted: malformed, unparseable, or refused at [Peering.Side.allow].
         * The link is closed on every one of those paths, but closing is
         * asynchronous — the peer may already have written more — and those
         * frames have nowhere to route. They are dropped, exactly as before, and
         * now counted rather than silent.
         */
        private val preHelloDropCount = AtomicLong()
        val preHelloDrops: Long get() = preHelloDropCount.get()

        /** @see admissionSink */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * The last hello refusal this link recorded — its [DenialReason], the
         * peer it was attributed to and its detail — kept for the reason
         * `WsTransport.Session.lastAdmissionDenial` is: the sink is
         * reporter-less on this seam, so without this the *reason* a hello was
         * refused would be observable nowhere. One record, not a log: every
         * refusal closes the link, so there is at most one worth reading.
         */
        @Volatile
        var lastAdmissionDenial: BoundaryDenial? = null
            private set

        /** This link instance's mirror ref, once [hello] has minted it. */
        val mirrorRef: CellRef? get() = mirror?.ref

        /**
         * This link instance's mirror itself — the cell whose gate [onDown] shuts
         * for good. Exposed so that a *retired* instance's fence can be checked
         * directly (`RegistryMirrorCell.refusedAnnouncements` counts what the
         * shut gate refuses), which is the only way to tell a mirror that is
         * detached from one that is merely unused.
         */
        internal val mirrorCell: RegistryMirrorCell? get() = mirror

        /** True once an admitted hello has installed this link's ingress. */
        val peered: Boolean get() = ingress != null

        init {
            egress.outlet.subscribe(
                Use.fixed(
                    object : Propagate<ByteArray> {
                        override fun propagate(value: ByteArray) {
                            try {
                                send(value)
                            } catch (e: Exception) {
                                // A dead link noticed before LINK_DOWN reached us:
                                // unpublish now so later sends take the park fast
                                // path, and signal "destination unavailable" the way
                                // a closed intake does — the registry parks THIS
                                // invocation too. Same branch, same reasons, as
                                // WsTransport.Session's.
                                side.registry.unpublishRemotes(via = egress)
                                throw IntakeClosedException(egress.ref)
                            }
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        /**
         * Mint this link instance's mirror and send the hello that names it, once.
         *
         * Called directly by the **dialler** the instant its link is up: its
         * hello is its first frame, which is also what adopts the QUIC stream at
         * the accepting sidecar (`PROTOCOL.md` §3). The **accepting** side calls
         * it from [onHello] instead — see there.
         */
        fun openLocalHello() {
            if (mirror != null) return
            send(hello())
        }

        private fun hello(): ByteArray {
            val fresh = Peering.spawnMirror(side, toPeer = egress)
            mirror = fresh
            val name = side.peer?.let { " ${it.name}" } ?: ""
            return (HELLO_PREFIX + fresh.ref.id + name).toByteArray(StandardCharsets.UTF_8)
        }

        /**
         * One inbound `DATA`. Routed into the ingress once an admitted hello has
         * installed one; read as the hello when it is the first frame on this
         * link; dropped and counted otherwise.
         */
        fun onData(payload: ByteArray) {
            val current = ingress
            if (current != null) {
                current.propagate(payload)
                return
            }
            if (!helloSeen) {
                helloSeen = true
                onHello(payload)
                return
            }
            preHelloDropCount.incrementAndGet()
        }

        /**
         * **The single admission point.** Every trust decision this transport
         * makes about a peer is taken here, between parsing the hello and
         * [Peering.Side.admits]; no ingress, no bound mirror and no announcer
         * exists on any path that does not reach the end of this method.
         *
         * The accepting side sends **its own** hello from here rather than at
         * link-up, for two independent reasons:
         *
         * - `SidecarLink.send` on an accepted link waits for the dialler's first
         *   frame, and that frame is dispatched by the very reader thread a
         *   link-up handler runs on — so sending there would deadlock this side
         *   against itself until the wait expired. By the time [onHello] runs,
         *   the dialler has spoken by definition.
         * - Our hello must precede anything else we write on this link, because
         *   the peer reads its first `DATA` as a hello. Sending it here, before
         *   [bindAndAnnounce], is what guarantees that.
         *
         * A **refused** hello sends nothing at all: this side never mints a
         * mirror for a peer it will not talk to, and the link is closed.
         */
        private fun onHello(payload: ByteArray) {
            val text = String(payload, StandardCharsets.UTF_8)
            if (!text.startsWith(HELLO_PREFIX)) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    null,
                    "first frame on this link refused: ${payload.size} bytes that do not open with the hello prefix",
                )
                return
            }
            val parts = text.removePrefix(HELLO_PREFIX).trim().split(" ", limit = 2)
            val peer = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { PeerId(it) }
            val peerMirrorRef = runCatching { UUID.fromString(parts[0]) }.getOrNull()
            if (peerMirrorRef == null) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    peer,
                    "hello refused: its first token is not a mirror ref UUID",
                )
                return
            }
            if (!admitted(peer)) return
            // Our own hello first (see this method's KDoc), then bind + announce.
            openLocalHello()
            bindAndAnnounce(peer, peerMirrorRef)
        }

        /**
         * `Peering.Side.admits`, with the refusal accounted — the same code, the
         * same stderr line and the same denial shape `WsTransport` writes.
         *
         * @return true when the peer is admitted; false after refusing it, in
         *   which case the caller must return without binding anything.
         */
        private fun admitted(peer: PeerId?): Boolean {
            if (side.admits(peer)) return true
            System.err.println("[IrohTransport] refusing peer $peer: not on the allowlist (spec 43)")
            // Seam 1 (spec 40/43, [SEC1-07]): accounted before the link is
            // refused. Nothing throws — a denial is not a cell fault (BS-14) —
            // so this never reaches supervision.
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = DenialReason.NOT_ADMITTED,
                principal = peer,
                subject = null,
                detail = "hello from $peer refused: not on the allowlist (spec 43)",
            )
            refuse()
            return false
        }

        /**
         * Account a refused hello on the seam-1 sink, then close the link. Every
         * refusal path goes through here or through [admitted], so
         * [admissionDenialCount] counts all of them and none can close a link
         * unaccounted.
         *
         * [detail] names ids, reasons and shapes only — never the raw line, which
         * is attacker-chosen bytes.
         */
        private fun refuseHello(reason: DenialReason, principal: PeerId?, detail: String) {
            System.err.println("[IrohTransport] refusing hello: $detail")
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = reason,
                principal = principal,
                subject = null,
                detail = detail,
            )
            refuse()
        }

        /**
         * Bind the mirror's peer, install the ingress, announce — in that order,
         * and only ever from a path that has already admitted [peer]. See the
         * class KDoc's four-step happens-before argument for why the late bind is
         * safe on this transport.
         */
        private fun bindAndAnnounce(peer: PeerId?, peerMirrorRef: UUID) {
            val instance = checkNotNull(mirror) { "onHello admitted a peer without opening a link instance" }
            // Bind BEFORE announcing, so every Remote location this link installs
            // — including the peer's own catch-up burst, which cannot start
            // before it has seen our hello — records the peer's name (V4-PEERID).
            instance.peer = peer
            ingress = Peering.hostIngress(side, fromPeer = peer)
            announcement?.close()
            announcement = Peering.announceTo(side, CellRef(peerMirrorRef), via = egress)
        }

        /**
         * The link is down: retire the announcer and detach the mirror, both
         * permanently.
         *
         * `detach` shuts the gate and retracts this link's Remote locations in
         * one step, which a bare `unpublishRemotes(via = egress)` could not: this
         * runs on the sidecar's reader thread while the announcements it retracts
         * are applied two scheduler hops later on the bridge host, so an
         * announcement decoded before this close can be applied after it.
         */
        fun onDown() {
            announcement?.close()
            announcement = null
            mirror?.detach()
        }
    }

    /**
     * The listening side: a sidecar that has `LISTEN`ed, plus one [Session] per
     * link it accepts.
     */
    class IrohListener internal constructor(
        private val process: SidecarProcess,
        private val client: SidecarClient,
        private val side: Peering.Side,
    ) : AutoCloseable {

        /** This side's iroh endpoint id — what a dialler passes to [connect]. */
        val nodeId: ByteArray get() = process.nodeId

        @Volatile
        private var listeningAddresses: List<String> = emptyList()

        /** The `LISTENING` addresses, `ADD_PEER`-ready — see [connect]. */
        val addresses: List<String> get() = listeningAddresses

        private val sessions = ConcurrentHashMap<Long, Session>()

        /**
         * One sink for every Session this listener opens, for the reason
         * `WsListener.admissionDenials` is allocated once: a refused hello's
         * Session is removed from [sessions] on the `LINK_DOWN` its own refusal
         * caused, so a per-Session sink would be discarded with the count it just
         * recorded. Read directly, never summed over [sessions] — the same sink
         * is shared, so summing would multiply-count one refusal.
         */
        private val admissionDenials = BoundaryDenials()
        private val admissionSink = admissionDenials.sinkFor("hello")

        /** @see admissionSink */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * Frames dropped before an admitted hello, summed over the links that are
         * still up (`WsListener.preHelloDrops`' analogue).
         */
        val preHelloDrops: Long get() = sessions.values.sumOf { it.preHelloDrops }

        /**
         * Every `ERROR` the sidecar reported on a link of this listener, in
         * arrival order — including a `DATA` refused by a full send queue, which
         * is never absorbed here (see [Session]'s KDoc and `computenet-ey4v`).
         */
        val linkErrors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        internal fun sessionFor(linkId: Long): Session? = sessions[linkId]

        internal fun start(timeout: Duration) {
            client.onInboundLink { link ->
                val session = Session(
                    side,
                    send = { link.send(it) },
                    refuse = { link.close() },
                    admissionSink = admissionSink,
                )
                sessions[link.id] = session
                object : LinkListener {
                    override fun onData(link: SidecarLink, payload: ByteArray) = session.onData(payload)

                    override fun onDown(link: SidecarLink, reason: String) {
                        sessions.remove(link.id)
                        session.onDown()
                    }

                    override fun onError(link: SidecarLink, reason: String) {
                        linkErrors += reason
                        System.err.println("[IrohTransport] link ${link.id} error: $reason")
                    }
                }
            }
            listeningAddresses = client.listen(timeout)
        }

        override fun close() {
            runCatching { client.shutdown() }
            runCatching { client.close() }
            process.close()
        }
    }

    /**
     * The dialling side: one sidecar, and a **succession** of links — each with
     * its own [Session] — under one connection handle.
     *
     * ## Requested down versus unplanned down
     *
     * Every `LINK_DOWN` arrives the same way whatever caused it
     * (`PROTOCOL.md` §3: exactly one per side, from the link's own observer), so
     * the difference has to be held here rather than read off the wire. A close
     * this side asked for — [sever], [close] — sets [closeRequested] *before* it
     * asks, and the handler consumes that flag instead of re-dialling. Anything
     * else is unplanned and starts [scheduleReconnect]. This is `WsTransport`'s
     * planned-close discipline (its `reconnect` flag), and it is what keeps a
     * severed peering severed until the test heals it.
     *
     * ## What a re-established link shares with the one it replaces: nothing
     *
     * A re-dial mints a whole new [Session] — new mirror, new egress, new
     * ingress, new hello, and a fresh `announceTo` catch-up over the local
     * registry. The retired Session's mirror is detached permanently, and there
     * is deliberately no way to re-open it (see [RegistryMirrorCell.detach]:
     * "the gate never re-opens, and that is what makes the fence total"). The
     * returning peer loses nothing by that, because a re-announcement is a full
     * `localRefs` sweep.
     *
     * What *is* connection-scoped, and survives across instances, is only the
     * accounting a per-link object could not carry honestly: the admission sink
     * (a refused hello takes its own link down, so a per-Session sink would be
     * discarded together with the count it just recorded — [IrohListener] owns
     * one for the same reason) and the pre-hello drop total.
     */
    class IrohConnection internal constructor(
        private val process: SidecarProcess,
        private val client: SidecarClient,
        private val side: Peering.Side,
        private val peerNodeId: ByteArray,
        private val backoff: (attempt: Int) -> Long,
        private val redialTimeout: Duration,
    ) : AutoCloseable {

        /** @see IrohConnection — one sink across every link instance. */
        private val admissionSink = BoundaryDenials().sinkFor("hello")

        private val currentLink = AtomicReference<SidecarLink?>(null)
        private val currentSession = AtomicReference<Session?>(null)

        /** Drops charged to links that are already gone; see [preHelloDrops]. */
        private val retiredPreHelloDrops = AtomicLong()

        /**
         * Set immediately before this side asks for a close, and consumed by the
         * `LINK_DOWN` that close produces. A one-shot rather than a level: it
         * must not suppress the reconnect for the *next* link.
         */
        private val closeRequested = java.util.concurrent.atomic.AtomicBoolean(false)

        /** False until [close]; nothing re-dials after it. */
        @Volatile
        private var shuttingDown = false

        /**
         * Single-flight guard on the re-dial loop, for the reason
         * `WsConnection.reconnecting` exists: one loop retries, and a
         * `LINK_DOWN` that arrives while a loop is running must not spawn a
         * second one racing it onto the same connection.
         */
        private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

        private val backoffCalls = AtomicLong()
        private val highestAttempt = java.util.concurrent.atomic.AtomicInteger(-1)

        /** @see IrohListener.linkErrors */
        val linkErrors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        /** @see IrohListener.admissionDenialCount */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * @see IrohListener.preHelloDrops
         *
         * Summed over every link instance this connection has had, live one
         * included — a count that reset on each re-dial would understate exactly
         * the case it exists for.
         */
        val preHelloDrops: Long get() = retiredPreHelloDrops.get() + (currentSession.get()?.preHelloDrops ?: 0L)

        /** True while a link is up whose peer hello was admitted. */
        val peered: Boolean get() = currentSession.get()?.peered ?: false

        /**
         * The live link instance's mirror ref, or null while there is no link.
         * **A different [CellRef] after every re-establishment** — the assertion
         * computenet-dqy.14's per-instance rule is checked by.
         */
        val mirrorRef: CellRef? get() = currentSession.get()?.mirrorRef

        /** @see Session.mirrorCell */
        internal val mirrorCell: RegistryMirrorCell? get() = currentSession.get()?.mirrorCell

        /**
         * How many times the re-dial schedule has been consulted, and the highest
         * `attempt` it was consulted with (-1 = never). Together they say that the
         * seam is real and which delays it was asked for — a reconnect test that
         * injects a near-zero schedule asserts on these rather than on elapsed
         * time, which no loaded machine can promise.
         */
        val backoffConsultations: Long get() = backoffCalls.get()

        /** @see backoffConsultations */
        val highestReconnectAttempt: Int get() = highestAttempt.get()

        /** This side's own iroh endpoint id. */
        val nodeId: ByteArray get() = process.nodeId

        /**
         * Take this connection's link down **without** re-dialling it: the
         * transport's programmatic partition (egl.2-D3). `CLOSE_LINK` yields
         * exactly one `LINK_DOWN` per side (`PROTOCOL.md` §3), so both peers
         * detach their mirror; [heal] is what brings the peering back, and only
         * as a new link.
         */
        fun sever() {
            val link = currentLink.get() ?: return
            closeRequested.set(true)
            link.close()
        }

        /**
         * Re-establish the peering as a NEW link, and therefore a new [Session]
         * with a fresh mirror, a fresh hello and a fresh announcement catch-up.
         * Nothing about the severed instance is resumed — there is nothing to
         * resume it to.
         */
        fun heal(timeout: Duration = redialTimeout): Unit = openLink(timeout).let {}

        /**
         * Dial one link and install a Session on it.
         *
         * The one ordering that spans link instances lives here: the re-dial runs
         * on the reconnect thread while the previous instance was retired on the
         * sidecar's reader thread. It is not a premise of [Session]'s
         * happens-before argument, and cannot become one, because the two
         * instances share no cell: the retired mirror's gate is already shut when
         * this returns, and this instance's mirror is minted below — after the
         * link exists and before any frame can be routed on it, since
         * [SidecarClient.dial] registers the listener before the `DIAL` goes out
         * and `LINK_UP` precedes every `DATA` on that id.
         */
        internal fun openLink(timeout: Duration) {
            val linkHolder = AtomicReference<SidecarLink?>(null)
            val session = Session(
                side,
                send = { payload ->
                    val link = linkHolder.get() ?: throw SidecarException("this connection has no link yet")
                    link.send(payload)
                },
                refuse = { linkHolder.get()?.close() },
                admissionSink = admissionSink,
            )
            val link = client.dial(
                peerNodeId,
                object : LinkListener {
                    override fun onData(link: SidecarLink, payload: ByteArray) = session.onData(payload)

                    override fun onDown(link: SidecarLink, reason: String) = retire(session, link, reason)

                    override fun onError(link: SidecarLink, reason: String) {
                        linkErrors += reason
                        System.err.println("[IrohTransport] link ${link.id} error: $reason")
                    }
                },
                timeout,
            )
            linkHolder.set(link)
            currentLink.set(link)
            currentSession.set(session)
            // The dialler's hello is its FIRST frame, and it is what adopts the
            // QUIC stream at the accepting sidecar (PROTOCOL.md §3). The peer
            // cannot have spoken before it: an accepting Session sends nothing
            // until it has read this hello (Session.onHello), so no inbound DATA
            // can reach the listener above before this line runs.
            session.openLocalHello()
        }

        /**
         * One link instance is over: charge its drops to the connection, shut its
         * mirror's gate for good, and decide whether a replacement is owed.
         */
        private fun retire(session: Session, link: SidecarLink, reason: String) {
            retiredPreHelloDrops.addAndGet(session.preHelloDrops)
            session.onDown()
            currentSession.compareAndSet(session, null)
            currentLink.compareAndSet(link, null)
            if (shuttingDown) return
            // A close this side asked for is not a partition to recover from.
            if (closeRequested.getAndSet(false)) return
            System.err.println("[IrohTransport] link ${link.id} went down unplanned ($reason); re-dialling")
            scheduleReconnect()
        }

        /**
         * Re-dial on [backoff] until a link comes back or [close] is called;
         * retries forever, as `WsConnection.scheduleReconnect` does.
         *
         * The schedule is consulted once per attempt, immediately before that
         * attempt — including the first, so a schedule is never bypassed by a
         * re-dial that happens to succeed at once.
         */
        private fun scheduleReconnect() {
            if (shuttingDown) return
            if (!reconnecting.compareAndSet(false, true)) return // a loop is already retrying
            Thread({
                try {
                    var attempt = 0
                    while (!shuttingDown && currentSession.get() == null) {
                        try {
                            Thread.sleep(delayFor(attempt))
                            attempt++
                            if (shuttingDown) break
                            openLink(redialTimeout)
                        } catch (_: InterruptedException) {
                            System.err.println("[IrohTransport] re-dial loop interrupted; this connection will not retry")
                            return@Thread
                        } catch (e: Exception) {
                            System.err.println("[IrohTransport] re-dial attempt $attempt failed: $e")
                        }
                    }
                } finally {
                    reconnecting.set(false)
                }
                // A LINK_DOWN that landed while this loop was winding down found
                // the guard held and returned; nothing else would retry for it.
                if (!shuttingDown && currentSession.get() == null) scheduleReconnect()
            }, "iroh-reconnect-${peerNodeId.toHex().take(8)}").apply { isDaemon = true }.start()
        }

        /** The schedule seam, with its consultation recorded. @see backoffConsultations */
        private fun delayFor(attempt: Int): Long {
            backoffCalls.incrementAndGet()
            highestAttempt.updateAndGet { maxOf(it, attempt) }
            return backoff(attempt)
        }

        override fun close() {
            shuttingDown = true
            closeRequested.set(true)
            runCatching { currentLink.get()?.close() }
            runCatching { client.shutdown() }
            runCatching { client.close() }
            process.close()
        }
    }
}
