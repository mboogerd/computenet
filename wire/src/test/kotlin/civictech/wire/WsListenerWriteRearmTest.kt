package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * computenet-dqy.69's repair, pinned: the listener re-arms a write demand that
 * java-websocket 1.6.0 lost, and the frame that was stranded is written.
 *
 * ## Why this reproduction is forced rather than natural
 *
 * The defect is a genuine race inside the library — see
 * [WsTransport.Session.framesSent] for the full mechanism and
 * [WsTransport.WsListener.watchStalledWrites] for the repair — measured at 9 in
 * 12,500 ubuntu peerings (0.07%), i.e. 1.8% per fresh-JVM `:wire` suite run. A
 * test that peers two stacks and hopes to lose the race says nothing: it passes
 * against the unfixed code 99.93% of the time. So this test *installs* the
 * post-clobber state instead of waiting for it, and it installs it exactly:
 *
 * - a buffer is added straight to the connection's `outQueue` **without** the
 *   `wsl.onWriteDemand(this)` that `WebSocketImpl.write` would follow it with —
 *   which is precisely what a clobbered sender leaves behind, and
 * - `interestOps(OP_READ)` is then asserted, the losing branch of
 *   `doWrite`'s `if (batch(conn, ch) && key.isValid()) key.interestOps(OP_READ)`.
 *
 * That state is *stable*, not racy: with no `OP_WRITE` interest the selector
 * never reports the key writable, so `doWrite` cannot run and the frame sits
 * there for the life of the process. Which is the defect, and is why the
 * reproduction is deterministic in both directions — it hangs with the repair
 * absent and passes with it present, on every run rather than 1 in 1400.
 *
 * ## Watched to fail with the watchdog inert
 *
 * Per the bead's `untested-hypothesis` flag, this was run against the
 * unrepaired path before being trusted: with the single
 * `watchStalledWrites(port)` call removed from
 * [WsTransport.WsListener.onStart] and everything else in place, on
 * darwin/arm64, `./gradlew :wire:test --tests
 * 'civictech.wire.WsListenerWriteRearmTest' --rerun` reported, verbatim:
 *
 * ```
 * WsListenerWriteRearmTest > a write demand lost to doWrite's clobber is re-armed and the frame delivered() FAILED
 *     org.opentest4j.AssertionFailedError: the stranded frame never reached the peer: the listener's
 *     out-queue still holds it (hasBufferedData=true) and its key still has no OP_WRITE, which is the
 *     unrepaired defect ==> expected: <true> but was: <false>
 * ```
 *
 * The frame sat in the queue for the full 10s bound, so the delivery assertion
 * is load-bearing on the re-arm and on nothing else.
 *
 * **The flag earned its keep.** The FIRST version of this test passed the
 * delivery assertion against that same inert listener, and would have landed as
 * permanent false assurance: it waited only for `!conn.hasBufferedData()` before
 * injecting, and `WebSocketServer.onWebsocketOpen` adds a connection to
 * `connections` *before* calling `onOpen`, where [WsTransport.WsListener] sends
 * its hello. So the out-queue is briefly and legitimately empty before the hello
 * enters it, and a frame injected in that window is written by the HELLO's write
 * demand moments later — delivery with no re-arm at all. It failed only on the
 * counter (`rearmedWriteDemands never incremented`), which is precisely the
 * shape of a reproduction that does not reach the defect. Waiting for the peer
 * to have *received* the hello is what closes it.
 *
 * ## The peer is a raw client, deliberately
 *
 * A [WsConnection] would route the injected bytes into a registry ingress that
 * would then fail to decode them, and would also be announcing on the same
 * connection while the test tries to hold its out-queue in a known state. A
 * bare [WebSocketClient] observes the one thing under test — did the bytes reach
 * the wire — and adds no traffic of its own (it never sends a hello, so the
 * listener's Session never opens an announcer).
 */
class WsListenerWriteRearmTest {

    @Test
    fun `a write demand lost to doWrite's clobber is re-armed and the frame delivered`() {
        val side = Peering.Side(LocationRegistry(), ManagedHost())
        // A process-global capture, unlike `stderrWrittenByThisThread`: the
        // marker is written by the listener's own watchdog thread, so a
        // thread-scoped capture would miss it by construction. Safe here because
        // this asserts the PRESENCE of a unique token — computenet-dqy.67's
        // hazard is the opposite assertion, "nothing was written", which a
        // neighbour thread can falsify. Everything still reaches the console.
        val captured = ByteArrayOutputStream()
        val realErr = System.err
        val tee = object : OutputStream() {
            override fun write(b: Int) {
                captured.write(b)
                realErr.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                captured.write(b, off, len)
                realErr.write(b, off, len)
            }

            override fun flush() = realErr.flush()
        }
        System.setErr(PrintStream(tee, true))

        val listener = WsTransport.listen(0, side)
        val received = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)
        val helloSeen = CountDownLatch(1)
        val client = object : WebSocketClient(URI("ws://localhost:${listener.port}")) {
            override fun onOpen(handshakedata: ServerHandshake?) = opened.countDown()
            override fun onMessage(message: String?) = helloSeen.countDown()
            override fun onMessage(bytes: ByteBuffer) {
                received.add(ByteArray(bytes.remaining()).also(bytes::get))
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
            override fun onError(ex: Exception?) = Unit
        }

        try {
            client.connect()
            assertTrue(opened.await(10, TimeUnit.SECONDS), "the peer never completed the handshake")

            val conn = awaitOneConnection(listener)
            // The listener's own hello must be GONE before anything is injected,
            // and the peer having received it is the only proof of that which
            // does not race. Measured, not defensive: waiting on
            // `!conn.hasBufferedData()` alone made this test pass against the
            // UNREPAIRED listener. `WebSocketServer.onWebsocketOpen` adds the
            // connection to `connections` and only then calls `onOpen`, where
            // `WsListener` sends the hello — so the queue is legitimately empty
            // for a moment before the hello is enqueued, and a frame injected in
            // that window is written by the HELLO's write demand a moment later.
            // That would have been a green test proving nothing.
            assertTrue(helloSeen.await(10, TimeUnit.SECONDS), "the listener's hello never reached the peer")
            awaitTrue("the listener's hello never drained") { !conn.hasBufferedData() }

            val payload = "computenet-dqy.69 stranded frame".toByteArray()
            forceLostWriteDemand(conn, payload)

            // The state now installed is exactly the post-clobber state, and is
            // asserted rather than assumed — if either half were wrong the test
            // below would be measuring an ordinary send.
            assertTrue(conn.hasBufferedData(), "the forced reproduction did not leave anything buffered")
            assertEquals(
                0,
                conn.selectionKey.interestOps() and SelectionKey.OP_WRITE,
                "the forced reproduction left OP_WRITE armed, so nothing was clobbered",
            )
            assertEquals(0L, listener.rearmedWriteDemands, "nothing should have been re-armed before the stall")

            val delivered = received.poll(10, TimeUnit.SECONDS)
            assertTrue(
                delivered != null,
                "the stranded frame never reached the peer: the listener's out-queue " +
                    "still holds it (hasBufferedData=${conn.hasBufferedData()}) and its key still has " +
                    "no OP_WRITE, which is the unrepaired defect",
            )
            assertEquals(
                payload.toList(),
                delivered!!.toList(),
                "the peer received something other than the stranded frame",
            )

            // The two halves of the fire instrument the acceptance measurement
            // reads. Bounded waits: the frame reaching the peer and the counter
            // being incremented are on different threads.
            awaitTrue("rearmedWriteDemands never incremented") { listener.rearmedWriteDemands >= 1L }
            awaitTrue("the stderr marker was never emitted") {
                captured.toString().contains("computenet-dqy.69 re-armed")
            }
            assertTrue(
                captured.toString().contains("computenet-dqy.69 re-armed a stalled write on port ${listener.port}"),
                "the marker line does not name the stalled listener's port: ${captured.toString().trim()}",
            )
        } finally {
            System.setErr(realErr)
            runCatching { client.closeBlocking() }
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * Leave [conn] in the state a clobbered sender leaves: the framed [payload]
     * in `outQueue`, and no `OP_WRITE` interest on its key.
     *
     * `WebSocketImpl.write` is `outQueue.add(buf); wsl.onWriteDemand(this)`, and
     * the clobber is the demand being erased by `doWrite` immediately after it
     * is issued. Enqueuing without ever issuing the demand reaches the same
     * state without depending on winning a microsecond-wide race — and the
     * `interestOps(OP_READ)` below makes `doWrite`'s losing branch explicit even
     * though the drained hello already left it there.
     */
    private fun forceLostWriteDemand(conn: WebSocketImpl, payload: ByteArray) {
        val draft = conn.draft
        // mask=false: this is the server side of the connection (RFC 6455 §5.1).
        draft.createFrames(ByteBuffer.wrap(payload), false)
            .forEach { conn.outQueue.add(draft.createBinaryFrame(it)) }
        conn.selectionKey.interestOps(SelectionKey.OP_READ)
    }

    private fun awaitOneConnection(listener: WsTransport.WsListener): WebSocketImpl {
        awaitTrue("the listener never registered the peer's connection") { listener.connections.size == 1 }
        return listener.connections.single() as WebSocketImpl
    }

    private fun awaitTrue(why: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(1)
        assertTrue(condition(), why)
    }
}
