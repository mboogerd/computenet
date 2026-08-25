package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * computenet-dqy.69: the repair for the listener's lost write demand.
 *
 * The defect is java-websocket 1.6.0's server-side interest-set race, named by
 * computenet-dqy.68 and set out in full at
 * [WsTransport.WsListener.sweepWriteDemand]. What matters for testing it is the
 * **state it leaves behind**, which the library's own `write` makes exact:
 *
 * ```java
 * WebSocketImpl.write(ByteBuffer buf) { outQueue.add(buf); wsl.onWriteDemand(this); }
 * ```
 *
 * The race destroys the effect of the second statement — `doWrite` clears the
 * key back to `OP_READ` after the caller armed `OP_WRITE`, so no thread will
 * ever register write interest for those bytes again. The residue is therefore
 * precisely: **bytes in `outQueue`, a valid key, and no `OP_WRITE`** — the first
 * statement having happened and the second having had no effect. That is what
 * [`a frame stranded by a lost write demand is re-armed and delivered`] produces,
 * by performing `outQueue.add` and withholding the demand. It touches no
 * interest set and races nothing, so it is deterministic, and it is the actual
 * post-race state rather than a stand-in for it.
 *
 * ## Provoking the race itself is not a local instrument (measured)
 *
 * Reproducing the *race* (as opposed to its residue) on darwin/aarch64 was tried
 * and does not work: **0 occurrences in ~52 million timed listener sends** across
 * twelve strategies — tight loops, 1-200us busy-spin pacing, 2/3/4/8 concurrent
 * senders at independent phases, CPU oversubscription at 2-3x cores, and sends
 * timed to the `hasBufferedData()` transition. The losing interleaving needs the
 * sender's `interestOps` field write to land strictly between `batch`'s final
 * `outQueue.poll()` and `doWrite`'s own field write, a window of order 100ns
 * that is phase-locked to the previous send, and a 10-core BSD host does not
 * schedule into it. computenet-dqy.68 measured the rate where it does fire —
 * ubuntu-latest, 9 in 12,500 peerings — so the genuine occurrence is a
 * *platform* observation, and the instrument for it is
 * [WsTransport.WsListener.WRITE_REARM_MARKER]: one stderr line per confirmed
 * re-arm, greppable in the `wire-suite-sample` run that this bead's acceptance
 * is measured with. This class covers the mechanism; that marker covers the
 * occurrence.
 *
 * ## The reproduction fails without the repair
 *
 * Mutation check (2026-08-14, darwin/aarch64): with the `watchWriteDemand(port)`
 * call removed from `WsListener.onStart`, `a frame stranded by a lost write
 * demand is re-armed and delivered` fails —
 * `the stranded out-queue never drained: the listener re-armed 0 time(s) ... ==>
 * expected: <true> but was: <false>` — and the peer receives nothing, on a
 * connection that stays open. With the call restored it passes. So the drain is
 * the sweep's doing and not something the selector was going to get to anyway.
 */
class WsListenerWriteDemandReArmTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    /** A dialer that keeps what the listener's socket actually delivered. */
    private class CollectingClient(uri: URI) : WebSocketClient(uri) {
        val binaryFrames = ConcurrentLinkedQueue<ByteArray>()
        override fun onOpen(handshakedata: ServerHandshake) = Unit
        override fun onMessage(message: String) = Unit
        override fun onMessage(bytes: ByteBuffer) {
            binaryFrames.add(ByteArray(bytes.remaining()).also(bytes::get))
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit
        override fun onError(ex: Exception) = Unit
    }

    /**
     * The repair, against the exact state a lost demand leaves.
     *
     * `outQueue.add` without the `onWriteDemand` that follows it in
     * `WebSocketImpl.write` is not an approximation of the defect — it *is* the
     * defect's residue, produced without racing anything. Nothing else in the
     * process can move those bytes: `OP_WRITE` is what makes the selector call
     * `doWrite` at all, and it is not set.
     *
     * Three things are then asserted, and only the sweep makes them true: the
     * queue drains, the re-arm is counted (so the drain is attributable to the
     * repair rather than to luck), and the peer receives the exact payload — the
     * frame reached the wire, not just the queue.
     *
     * The listener is LABELLED ([WsTransport.WsListener.reArmMarkerLabel]), because
     * this test's re-arm is deliberate and the `wire-suite-sample` workflow runs
     * this whole suite once per iteration: without the label every iteration's
     * marker count starts at one and a sample has to be read by subtracting a
     * baseline by hand. computenet-0r4i did that subtraction on two 500-iteration
     * ubuntu samples after computenet-dqy.70 had been raised to P1 on the
     * unsubtracted count; the label is so the next sample needs no arithmetic.
     */
    @Test
    fun `a frame stranded by a lost write demand is re-armed and delivered`() {
        val server = Stack()
        val listener = WsTransport.listen(0, server.side)
        listener.reArmMarkerLabel = DELIBERATE_STRAND_LABEL
        val client = CollectingClient(URI("ws://localhost:${listener.port}"))
        try {
            assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "the probe dialer never connected")
            val conn = awaitIdleConnection(listener)
            val before = listener.writeDemandReArms
            val payload = ByteArray(PAYLOAD_BYTES) { (it * 7 + 3).toByte() }

            // `WebSocketImpl.write`'s first statement, and only its first.
            conn.outQueue.add(ByteBuffer.wrap(unmaskedBinaryFrame(payload)))

            assertTrue(conn.hasBufferedData(), "the injected frame is not in the out-queue, so nothing is stranded")
            assertEquals(
                0,
                conn.selectionKey.interestOps() and SelectionKey.OP_WRITE,
                "this connection already has write interest registered, so it is not in the lost-demand state " +
                    "and this run would prove nothing",
            )

            val settle = System.currentTimeMillis() + DRAIN_BUDGET_MS
            while (client.binaryFrames.isEmpty() && System.currentTimeMillis() < settle) Thread.sleep(1)

            assertTrue(
                !conn.hasBufferedData(),
                "the stranded out-queue never drained: the listener re-armed " +
                    "${listener.writeDemandReArms - before} time(s) in ${DRAIN_BUDGET_MS}ms",
            )
            assertTrue(
                listener.writeDemandReArms > before,
                "the queue drained without a re-arm being counted, so writeDemandReArms is not measuring the repair",
            )
            assertArrayEquals(
                payload,
                client.binaryFrames.poll(),
                "the re-armed frame reached the wire and the dialer decoded it unchanged",
            )
        } finally {
            runCatching { client.closeBlocking() }
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * A frame given the demand the library gives it goes out on its own, and the
     * sweep neither counts it nor has to move it.
     *
     * The stranded-frame test above withholds `WebSocketImpl.write`'s second
     * statement; this one performs both, so the connection is in the ordinary
     * post-`send` state and the sweep must keep its hands off it. What is
     * asserted is that pair: `writeDemandReArms` is unchanged, and the frame
     * reaches the dialer without the sweep having re-armed anything.
     *
     * ## What this does NOT pin (measured, computenet-dqy.70)
     *
     * It does not pin the detector's `(interestOps and OP_WRITE) == 0` term. An
     * earlier version of this KDoc claimed "the queue is non-empty for a whole
     * sequence of sweeps"; that sentence is false. On loopback a 64-byte frame
     * with a live demand is written well inside one [WsTransport.WsListener.WRITE_REARM_POLL_MS]
     * sweep, so at most **one** sweep ever sees the queue non-empty and the
     * confirmation streak never reaches
     * [WsTransport.WsListener.LOST_DEMAND_CONFIRMATIONS]. Deleting the `OP_WRITE`
     * term from [WsTransport.WsListener.sweepWriteDemand] therefore leaves this
     * test green — what keeps it green is the two-sweep streak, not the term.
     *
     * The term is pinned by
     * [`a deep out-queue draining to a peer that never reads is not counted as a lost demand`],
     * which holds the queue non-empty across many sweeps by backpressure. This
     * test remains the cheap check that a live demand is left alone end to end.
     */
    @Test
    fun `a non-empty out-queue that still carries OP_WRITE is not counted as a lost demand`() {
        val server = Stack()
        val listener = WsTransport.listen(0, server.side)
        val client = CollectingClient(URI("ws://localhost:${listener.port}"))
        try {
            assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "the probe dialer never connected")
            val conn = awaitIdleConnection(listener)
            val before = listener.writeDemandReArms
            val payload = ByteArray(PAYLOAD_BYTES) { 9 }

            val key = conn.selectionKey
            conn.outQueue.add(ByteBuffer.wrap(unmaskedBinaryFrame(payload)))
            // the second statement of `WebSocketImpl.write`, in full: this demand
            // is NOT lost, so the sweep must keep its hands off it.
            key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
            key.selector().wakeup()

            // Several sweeps' worth of wall clock, driven directly as well so the
            // assertion does not depend on the poll thread's cadence.
            repeat(SWEEPS_PER_CHECK) {
                listener.sweepWriteDemand()
                Thread.sleep(WsTransport.WsListener.WRITE_REARM_POLL_MS)
            }

            assertEquals(
                before,
                listener.writeDemandReArms,
                "ordinary backpressure — queued bytes with OP_WRITE armed — was miscounted as a lost write demand",
            )
            val settle = System.currentTimeMillis() + DRAIN_BUDGET_MS
            while (client.binaryFrames.isEmpty() && System.currentTimeMillis() < settle) Thread.sleep(1)
            assertArrayEquals(
                payload,
                client.binaryFrames.poll(),
                "the frame with a live write demand should have gone out on its own",
            )
        } finally {
            runCatching { client.closeBlocking() }
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * **The detector's `OP_WRITE` term, pinned by a peer that never reads.**
     *
     * A non-empty out-queue is the *normal* state of a listener writing to a
     * slow peer: `batch` returns false on a partial write and `doWrite` then
     * leaves `OP_WRITE` armed, so the queue can be arbitrarily deep, for
     * arbitrarily long, with nothing wrong. If
     * [WsTransport.WsListener.sweepWriteDemand] keyed on depth instead of on the
     * missing write interest, every backpressured connection would be counted as
     * a lost demand — and [WsTransport.WsListener.writeDemandReArms] is the
     * counter computenet-dqy.69's acceptance is read with, so inflating it costs
     * the instrument rather than the connection.
     *
     * The dialer here is a raw socket that completes the upgrade and then never
     * reads a byte, with a deliberately small receive buffer. [STALL_FRAMES]
     * frames of [STALL_FRAME_BYTES] — several times what the socket pair can
     * absorb, see [STALL_FRAMES] — are queued and the demand is armed the way
     * `WebSocketServer` arms it, so the write stalls part-way with `OP_WRITE`
     * still set and stays there for the whole check. Each sweep therefore sees queued bytes on a
     * valid key, which is every clause of the predicate except the one under
     * test, for far longer than
     * [WsTransport.WsListener.LOST_DEMAND_CONFIRMATIONS] sweeps.
     *
     * Both halves of that state are asserted before every sweep, so a run where
     * the buffers happened to swallow the whole queue fails loudly instead of
     * passing vacuously.
     *
     * Mutation check (2026-08-15, darwin/aarch64): deleting
     * `&& (key.interestOps() and SelectionKey.OP_WRITE) == 0` from
     * `sweepWriteDemand`'s `lost` expression, with `LOST_DEMAND_CONFIRMATIONS`
     * left at its shipped 2, fails this test — `a backpressured out-queue —
     * queued bytes with OP_WRITE armed against a peer that never reads — was
     * miscounted as a lost write demand ==> expected: <0> but was: <6>`. The
     * count is however many sweeps confirmed the streak and is not a fixed
     * number (the poll thread sweeps alongside the driven ones); what is fixed
     * is that it is not zero. The other two tests in this class stay green under
     * that mutation, which is the coverage gap computenet-dqy.70 was filed for.
     */
    @Test
    fun `a deep out-queue draining to a peer that never reads is not counted as a lost demand`() {
        val server = Stack()
        val listener = WsTransport.listen(0, server.side)
        val deaf = Socket()
        try {
            // Before connect: this is what the peer advertises as its window, so
            // the stall needs kilobytes of unread data rather than megabytes.
            deaf.receiveBufferSize = STALLED_PEER_RCVBUF
            deaf.connect(InetSocketAddress("localhost", listener.port), 10_000)
            deaf.getOutputStream().apply {
                write(upgradeRequest(listener.port).toByteArray())
                flush()
            }
            // Nothing is ever read from `deaf` after this point.

            val conn = awaitIdleConnection(listener)
            val before = listener.writeDemandReArms
            val key = conn.selectionKey
            val frame = ByteBuffer.wrap(unmaskedBinaryFrame(ByteArray(STALL_FRAME_BYTES) { (it * 31 + 5).toByte() }))

            // Queue first, then arm — the order `WebSocketImpl.write` uses. The
            // frames share one backing array; each buffer carries its own
            // position, and the selector thread only ever reads from it.
            conn.outQueue.addAll(List(STALL_FRAMES) { frame.duplicate() })
            key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
            key.selector().wakeup()

            repeat(SWEEPS_PER_CHECK) { sweep ->
                Thread.sleep(WsTransport.WsListener.WRITE_REARM_POLL_MS)
                assertTrue(
                    conn.hasBufferedData(),
                    "sweep $sweep found the out-queue drained: ${STALL_FRAMES}x$STALL_FRAME_BYTES bytes reached a " +
                        "peer that never read, so this connection was never backpressured and this run proves nothing",
                )
                assertNotEquals(
                    0,
                    key.interestOps() and SelectionKey.OP_WRITE,
                    "sweep $sweep found write interest cleared under a non-empty out-queue, which is the " +
                        "lost-demand state rather than the backpressure this test builds",
                )
                listener.sweepWriteDemand()
            }

            assertEquals(
                before,
                listener.writeDemandReArms,
                "a backpressured out-queue — queued bytes with OP_WRITE armed against a peer that never reads — " +
                    "was miscounted as a lost write demand",
            )
        } finally {
            runCatching { deaf.close() }
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * The minimal RFC 6455 §4.1 client opening handshake, so a plain socket can
     * become one of the listener's connections without a library on this end
     * that would read the bytes back off it. The key is the RFC's own example.
     */
    private fun upgradeRequest(port: Int): String =
        "GET / HTTP/1.1\r\nHost: localhost:$port\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"

    /**
     * A server-to-client binary frame as `Draft_6455` encodes it: FIN + opcode 2,
     * unmasked (RFC 6455 §5.1 — a server must not mask), in the single-byte
     * length form under 126 bytes and the two-byte extended form up to 65535.
     * Hand-encoded rather than obtained from `send`, because `send` is exactly
     * the path whose second half these tests have to withhold or outpace.
     */
    private fun unmaskedBinaryFrame(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "this encoder covers the single-byte and two-byte length forms only" }
        val header = if (payload.size < 126) {
            byteArrayOf(0x82.toByte(), payload.size.toByte())
        } else {
            byteArrayOf(0x82.toByte(), 126, (payload.size ushr 8).toByte(), payload.size.toByte())
        }
        return header + payload
    }

    /**
     * The listener's connection once its own hello has been written **and the
     * selector has finished with it**, so the out-queue each test reasons about
     * starts empty and every byte in it afterwards is the one the test put there.
     *
     * Both conditions are load-bearing, and the second is the non-obvious one. A
     * `SelectionKey`'s interest set is a field plus a queued selector update: the
     * queue goes empty inside `batch`, and `doWrite`'s `interestOps(OP_READ)`
     * lands after that, and the *registered* kqueue/epoll interest changes later
     * still, when the selector next drains its update queue. Between those points
     * the selector is blocked with write interest registered on an always-writable
     * loopback socket, so anything added to `outQueue` there goes out with no
     * demand at all — which would silently turn the stranded-frame test into a
     * test of nothing. Waiting for the field to lose `OP_WRITE` and then giving
     * the selector [SETTLE_MS] to run one full cycle puts the connection in the
     * state the defect leaves it in.
     */
    private fun awaitIdleConnection(listener: WsTransport.WsListener): WebSocketImpl {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val conn = listener.connections.firstOrNull() as? WebSocketImpl
            val key = conn?.selectionKey
            if (conn != null && conn.isOpen && !conn.hasBufferedData() &&
                key != null && key.isValid && (key.interestOps() and SelectionKey.OP_WRITE) == 0
            ) {
                Thread.sleep(SETTLE_MS)
                if (!conn.hasBufferedData()) return conn
                continue
            }
            Thread.sleep(1)
        }
        throw AssertionError("the listener never reported an open connection with a drained, unarmed out-queue")
    }

    private companion object {
        const val PAYLOAD_BYTES = 64

        /** How long a stranded queue is given to be re-armed and reach the dialer. */
        const val DRAIN_BUDGET_MS = 15_000L

        /** Sweeps the backpressure checks give the detector to get it wrong. */
        const val SWEEPS_PER_CHECK = 6

        /**
         * The stalled peer's receive buffer, set before connect so it is the
         * window it advertises. Small on purpose: the smaller the window, the
         * less the kernel can swallow before the listener's writes stall.
         */
        const val STALLED_PEER_RCVBUF = 8 * 1024

        /** Payload of one backpressure frame — the two-byte length form. */
        const val STALL_FRAME_BYTES = 16 * 1024

        /**
         * How many of them are queued: 16MB. What a peer that never reads can
         * swallow is bounded by its advertised window ([STALLED_PEER_RCVBUF])
         * plus the listener's own send buffer, which autotunes to a few MB at
         * most on the platforms this suite runs on — so the margin is several
         * times over rather than merely comfortable, and a host generous enough
         * to break that bound fails the in-loop `hasBufferedData` assertion
         * loudly instead of passing this test vacuously.
         */
        const val STALL_FRAMES = 1024

        /** How long the selector is given to complete a cycle — see [awaitIdleConnection]. */
        const val SETTLE_MS = 250L

        /**
         * What the deliberately stranded listener stamps on its marker lines, so
         * a whole-suite sample can drop them with `grep -v 'label='` instead of
         * subtracting one per iteration.
         *
         * NOT asserted by any test in this class, and deliberately so: the
         * marker is written by the listener's own sweep thread, and capturing it
         * would mean replacing the process-wide `System.err` from a suite whose
         * other classes run around it. What checks the label is the sample it
         * exists for — the next `wire-suite-sample` artifact should show every
         * iteration's baseline line carrying `label=`, and any line without one
         * is an occurrence to explain.
         */
        const val DELIBERATE_STRAND_LABEL = "WsListenerWriteDemandReArmTest-deliberate-strand"
    }
}
