package civictech.iroh

import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.MSG_HEADER_LEN
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The host's recovery contract for a refused `DATA` (`computenet-ey4v`,
 * residual of `computenet-3gij`): **an `ERROR` on an established link is
 * terminal for that link, and the host closes it.**
 *
 * `PROTOCOL.md` §2's Backpressure section is the normative statement and
 * carries the reasoning; the short of it is that the wire names a *link* and
 * never a *frame*. An `ERROR` carries a kind byte, a link id and a UTF-8
 * reason and no sequence number, while an accepted `DATA` is answered with
 * nothing at all — so a host with several `DATA` outstanding on a link cannot
 * tell which one was refused, and with a concurrent consumer the refusals are
 * not even a contiguous suffix. Nothing addressed to a frame is implementable
 * over that; discarding the link is, and it discards the hole with it.
 *
 * ## Why these run against a fake sidecar rather than a real one
 *
 * The rule under test is entirely the **host's**, and the sidecar's part in it
 * is one `ERROR` frame. Driving the client over a plain loopback socket that
 * speaks `PROTOCOL.md` by hand makes the refusal deterministic — no flood, no
 * 256-frame race, no timing — and, because it needs no `-Piroh.enabled=true`
 * build, it executes on the default lanes rather than reporting SKIPPED there
 * ([SidecarBinary]). The sidecar's own half of the contract — that a `DATA`
 * past the bound is answered with `ERROR` on its link and not sent, while the
 * host connection keeps answering — is pinned in Rust, by
 * `sidecar/tests/protocol.rs`'s
 * `a_flood_on_an_unadopted_inbound_link_leaves_the_host_connection_answering`.
 */
class SidecarBackpressureTest {

    private val peerId = ByteArray(NODE_ID_LEN) { (it + 1).toByte() }

    @Test
    fun `an ERROR on an established link makes the host close that link`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingLinkListener("refused")
                val link = fake.dialAndAnswer(client, listener)

                link.send(byteArrayOf(1, 2, 3))
                val sent = assertIs<HostMessage.Data>(fake.nextHostMessage())
                assertEquals(link.id, sent.link)

                // The sidecar refuses that frame: PROTOCOL.md §2, the queue-full
                // arm of server.rs's kind::DATA handler, verbatim.
                val reason = "link ${link.id}'s send queue is full (256 frames outstanding); the frame was not sent"
                fake.send(SidecarMessage.Failure(link.id, reason))

                // THE PIN. The host answers a refusal by taking the link down —
                // not by resending, not by carrying on, and without having had to
                // work out which frame was refused.
                val answer = fake.nextHostMessage()
                assertEquals(
                    HostMessage.CloseLink(link.id),
                    answer,
                    "PROTOCOL.md §2: an ERROR on an established link is terminal for it and the host must " +
                        "CLOSE_LINK; the host answered with $answer instead",
                )

                assertEquals(listOf(reason), listener.errors.toList(), "the refusal is reported verbatim, not absorbed")
                assertTrue(link.refusedAndClosed, "the link records that it ended in a refusal")

                // And nothing more goes onto it: recovery is a NEW link.
                val refused = assertFailsWith<SidecarException> { link.send(byteArrayOf(4)) }
                assertTrue(
                    "resending" in (refused.message ?: ""),
                    "the refusal to send should say why resending is wrong, got: ${refused.message}",
                )

                // The LINK_DOWN that close produces arrives as an ordinary down —
                // which is what an IrohConnection recovers from, with a new link.
                fake.send(SidecarMessage.LinkDown(link.id, "link closed"))
                assertEquals("link closed", listener.nextDown())
                assertEquals(1, listener.downCount.get())
            }
        }
    }

    /**
     * The rule is scoped to *established* links. An `ERROR` answering a `DIAL`
     * settles that dial and establishes nothing, so there is no link to close —
     * a client that closed on it would name a link the sidecar never had.
     */
    @Test
    fun `an ERROR answering a DIAL closes nothing`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val dialing = Thread {
                    assertFailsWith<SidecarException> { client.dial(peerId, RecordingLinkListener("dial")) }
                }
                dialing.start()

                val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                fake.send(SidecarMessage.Failure(dial.link, "dial failed: no route to peer"))
                dialing.join(TimeUnit.SECONDS.toMillis(30))

                assertEquals(
                    null,
                    fake.pollHostMessage(500),
                    "an ERROR that answers a DIAL establishes no link, so the host must send no CLOSE_LINK for it",
                )
            }
        }
    }

    // ------------------------------------------------------------------ fake

    /**
     * A loopback socket that speaks `PROTOCOL.md` by hand: one accepted host
     * connection, a reader thread decoding host → sidecar messages into a queue,
     * and [send] for sidecar → host ones. It implements no behaviour — every
     * reply in a test is written by that test.
     */
    private class FakeSidecar : AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val accepted = ArrayBlockingQueue<Socket>(1)
        private val received = LinkedBlockingQueue<HostMessage>()

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
                        is Decoded.Ok -> received.put(decoded.message)
                        is Decoded.Malformed -> fail("the host sent an undecodable message: $decoded")
                    }
                }
            }
        }, "fake-sidecar").apply { isDaemon = true; start() }

        private fun connection(): Socket =
            accepted.peek() ?: accepted.poll(30, TimeUnit.SECONDS)?.also { accepted.put(it) }
                ?: fail("no host connected within 30s")

        /** One sidecar → host message, encoded exactly as `PROTOCOL.md` §2 lays it out. */
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
}
