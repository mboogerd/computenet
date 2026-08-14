package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
     */
    @Test
    fun `a frame stranded by a lost write demand is re-armed and delivered`() {
        val server = Stack()
        val listener = WsTransport.listen(0, server.side)
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
     * The false positive that would make [WsTransport.WsListener.writeDemandReArms]
     * worthless, ruled out by execution.
     *
     * A non-empty out-queue is the *normal* state of a listener writing to a slow
     * peer: `batch` returns false on a partial write and `doWrite` leaves
     * `OP_WRITE` armed, so the queue can be arbitrarily deep with nothing wrong.
     * The detector must key on the missing write interest and not on the depth.
     * Here the frame is stranded exactly as above and then the demand is given
     * the way the library gives it — so the queue is non-empty for a whole
     * sequence of sweeps, and not one of them may count a re-arm.
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
     * A server-to-client binary frame as `Draft_6455` encodes it: FIN + opcode 2,
     * unmasked (RFC 6455 §5.1 — a server must not mask), single-byte length for
     * a payload under 126. Hand-encoded rather than obtained from `send`, because
     * `send` is exactly the path whose second half this test has to withhold.
     */
    private fun unmaskedBinaryFrame(payload: ByteArray): ByteArray {
        require(payload.size < 126) { "this encoder only covers the single-byte length form" }
        return byteArrayOf(0x82.toByte(), payload.size.toByte()) + payload
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

        /** Sweeps the backpressure check gives the detector to get it wrong. */
        const val SWEEPS_PER_CHECK = 6

        /** How long the selector is given to complete a cycle — see [awaitIdleConnection]. */
        const val SETTLE_MS = 250L
    }
}
