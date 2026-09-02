package civictech.iroh

import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.MSG_HEADER_LEN
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A loopback socket that speaks `PROTOCOL.md` by hand: one accepted host
 * connection, a reader thread decoding host -> sidecar messages into a queue,
 * and [send] for sidecar -> host ones. It implements no behaviour — every
 * reply in a test is written by that test.
 *
 * Shared by [SidecarBackpressureTest] and [IrohReconnectTest] (`computenet-sr48`),
 * which is why it carries both of their original fixtures' capabilities:
 * [SidecarBackpressureTest]'s [answerOpenLink]/[refuse]/[dialAndAnswer]/
 * [pollHostMessage], and [IrohReconnectTest]'s `DIAL`-counting ([dials],
 * [nextDial]) and [openAndAdmit]/[admit]. Neither original fixture used the
 * other's methods, and this merge drops none of them.
 */
class FakeSidecar : AutoCloseable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val accepted = ArrayBlockingQueue<Socket>(1)
    private val received = LinkedBlockingQueue<HostMessage>()

    /** Every `DIAL` this fake has decoded. */
    val dials = AtomicLong()

    val port: Int get() = server.localPort

    private val acceptor = Thread({
        runCatching {
            val socket = server.accept()
            accepted.put(socket)
            val input = DataInputStream(socket.getInputStream().buffered())
            while (true) {
                val declared = input.readInt()
                val body = ByteArray(declared)
                input.readFully(body)
                val frame = SidecarCodec.decodeBody(body, 0, declared)
                when (val decoded = SidecarCodec.asHostMessage(frame)) {
                    is Decoded.Ok -> {
                        if (decoded.message is HostMessage.Dial) dials.incrementAndGet()
                        received.put(decoded.message)
                    }

                    is Decoded.Malformed -> fail("the host sent an undecodable message: $decoded")
                }
            }
        }
    }, "fake-sidecar").apply { isDaemon = true; start() }

    private fun connection(): Socket =
        accepted.peek() ?: accepted.poll(30, TimeUnit.SECONDS)?.also { accepted.put(it) }
            ?: fail("no host connected within 30s")

    /** One sidecar -> host message, encoded exactly as `PROTOCOL.md` §2 lays it out. */
    fun send(message: SidecarMessage) {
        val socket = connection()
        socket.getOutputStream().apply {
            write(SidecarCodec.encode(message))
            flush()
        }
    }

    fun nextHostMessage(seconds: Long = 30): HostMessage =
        received.poll(seconds, TimeUnit.SECONDS) ?: fail("no host message within ${seconds}s")

    fun pollHostMessage(millis: Long): HostMessage? = received.poll(millis, TimeUnit.MILLISECONDS)

    /**
     * The next `DIAL`, skipping any `DATA` an announcer wrote in between —
     * for fixtures that assert on dial *identity* and on [dials], never on the
     * traffic a peering happens to produce.
     */
    fun nextDial(seconds: Long = 30): HostMessage.Dial {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            val next = received.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (next is HostMessage.Dial) return next
        }
        fail("no DIAL within ${seconds}s")
    }

    /**
     * One link opened on [connection]: its `DIAL` answered with `LINK_UP`,
     * and this side's hello — the first frame on every outbound link
     * (`PROTOCOL.md` section 3) — drained. Returns the link id.
     *
     * The peer never answers that hello, so the link is never admitted:
     * `IrohTransport.Session.peered` stays false and the down that follows is
     * an unadmitted one, which is the only kind [IrohTransport.REFUSED_DIAL_LIMIT]
     * counts.
     */
    fun answerOpenLink(connection: IrohTransport.IrohConnection): Long {
        val opened = ArrayBlockingQueue<Result<Unit>>(1)
        Thread({ opened.put(runCatching { connection.openLink(30.seconds) }) }, "open-link")
            .apply { isDaemon = true }
            .start()
        val dial = assertIs<HostMessage.Dial>(nextHostMessage())
        send(SidecarMessage.LinkUp(dial.link, ByteArray(NODE_ID_LEN) { 7 }, DIRECTION_OUTBOUND))
        (opened.poll(30, TimeUnit.SECONDS) ?: fail("openLink did not settle within 30s")).getOrThrow()
        assertIs<HostMessage.Data>(nextHostMessage())
        return dial.link
    }

    /**
     * One link opened by [connection]'s own `openLink`, brought all the way to
     * *admitted*: `DIAL` answered with `LINK_UP`, this side's hello drained,
     * and a peer hello written back so the Session binds a mirror and installs
     * an ingress. Returns the link id.
     */
    fun openAndAdmit(connection: IrohTransport.IrohConnection, peerNodeId: ByteArray): Long {
        val opened = ArrayBlockingQueue<Result<Unit>>(1)
        Thread({ opened.put(runCatching { connection.openLink(30.seconds) }) }, "open-link")
            .apply { isDaemon = true }
            .start()
        val dial = nextDial()
        admit(dial.link, peerNodeId)
        (opened.poll(30, TimeUnit.SECONDS) ?: fail("openLink did not settle within 30s")).getOrThrow()
        return dial.link
    }

    /**
     * Answer an outstanding `DIAL` on [link] with `LINK_UP`, drain the hello
     * the dialler writes as its first frame, and answer it with a peer hello —
     * `IROH-HELLO1 <mirror ref>`, the whole grammar (`PROTOCOL.md` §3).
     */
    fun admit(link: Long, peerNodeId: ByteArray) {
        send(SidecarMessage.LinkUp(link, peerNodeId, DIRECTION_OUTBOUND))
        assertIs<HostMessage.Data>(nextHostMessage())
        send(
            SidecarMessage.Data(
                link,
                (IrohTransport.HELLO_PREFIX + UUID.randomUUID()).toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    /**
     * Refuse a frame on [link], `PROTOCOL.md` section 2's queue-full arm
     * verbatim, and wait for the `CLOSE_LINK` the host answers with — which
     * is what says the client has already set the refusal flag the tests are
     * about.
     */
    fun refuse(link: Long) {
        send(
            SidecarMessage.Failure(
                link,
                "link $link's send queue is full (256 frames outstanding); the frame was not sent",
            ),
        )
        assertEquals(
            HostMessage.CloseLink(link),
            nextHostMessage(),
            "PROTOCOL.md section 2: an ERROR on an established link is terminal for it and the host must CLOSE_LINK",
        )
    }

    /** `DIAL` from the client, answered with `LINK_UP` — the shortest route to an established link. */
    fun dialAndAnswer(client: SidecarClient, listener: LinkListener): SidecarLink {
        val dialed = ArrayBlockingQueue<Any>(1)
        val dialing = Thread {
            dialed.put(runCatching { client.dial(ByteArray(NODE_ID_LEN) { 7 }, listener) })
        }
        dialing.start()
        val dial = assertIs<HostMessage.Dial>(nextHostMessage())
        send(SidecarMessage.LinkUp(dial.link, ByteArray(NODE_ID_LEN) { 7 }, DIRECTION_OUTBOUND))

        @Suppress("UNCHECKED_CAST")
        val outcome = dialed.poll(30, TimeUnit.SECONDS) as Result<SidecarLink>?
            ?: fail("DIAL did not settle within 30s")
        return outcome.getOrThrow()
    }

    override fun close() {
        acceptor.interrupt()
        runCatching { accepted.peek()?.close() }
        runCatching { server.close() }
    }

    init {
        // A guard on the transcription rather than on the protocol: every
        // message this fake writes goes out with PROTOCOL.md §2's 9-byte
        // header, so a header that drifted would fail here and not as an
        // unexplained timeout in a test above.
        check(MSG_HEADER_LEN == 9) { "PROTOCOL.md §2's header is 9 bytes; SidecarProtocol says $MSG_HEADER_LEN" }
    }
}
