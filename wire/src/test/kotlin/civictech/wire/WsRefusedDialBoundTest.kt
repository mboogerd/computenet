package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * computenet-4gzr: a dialler refused at the listener's allowlist must stop
 * re-dialling, because nothing on the wire ever tells it that it was refused.
 *
 * ## The defect this pins
 *
 * A refusal is not a failed dial. The TCP connect succeeds, the WebSocket
 * upgrade succeeds, `onOpen` fires and the hello goes out — and only *then*
 * does the listener refuse it and close. `WsConnection.onClose` cannot see the
 * difference, so it called `scheduleReconnect()` unconditionally, and because
 * the re-dial loop terminates the moment `reconnectBlocking()` returns true,
 * every refusal cycle started a **fresh** loop at `attempt = 0`. The backoff
 * therefore never escalated: a refused peer re-dialled at a fixed
 * `backoff(0)` — one second on [WsTransport.DEFAULT_RECONNECT_BACKOFF] —
 * forever, charging the refusing listener one accept plus one hello parse
 * every second, from a peer it had already decided it would not talk to.
 *
 * ## What is deliberately NOT this test
 *
 * - [WsReconnectLoopBoundTest] (computenet-8ru) bounds the number of
 *   concurrent retry *threads* during an outage. One thread re-dialling
 *   forever satisfies it completely.
 * - [WsReconnectRefusedTest] (computenet-dqy.27) reconnects into an **unbound
 *   port** — `ECONNREFUSED` at the socket, before any link exists. That case
 *   still retries forever on purpose (a listener that is down is expected
 *   back), and this fix does not touch it: the bound counts only opens that
 *   came *up* and were closed without ever being admitted.
 *
 * ## What is asserted
 *
 * The acceptance criterion is the *listener's* cost, so that is what is
 * measured: `admissionDenialCount` is one accept-plus-hello-parse charged to
 * the refusing side. It must stay bounded, and it must stop growing.
 *
 * How the dialler concludes it — an open that did not outlive
 * [WsTransport.REFUSAL_WINDOW_MS], rather than `Session.peered`, which on this
 * transport answers a different question — is argued on
 * [WsTransport.REFUSED_DIAL_LIMIT], together with what `:iroh` does instead
 * and why the two may differ.
 */
class WsRefusedDialBoundTest {

    private class Stack(name: String?, allow: Set<KeyId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(20)
        }
    }

    /**
     * The bound the first test holds the transport to.
     *
     * Deliberately a literal rather than [WsTransport.REFUSED_DIAL_LIMIT], and
     * deliberately larger than it: an independent upper bound cannot be
     * satisfied by the production constant merely agreeing with itself. It is
     * also what the reproduction was run against before the fix existed — the
     * unfixed transport charged the listener 10 refusals inside the settle
     * window and failed here. The second test below pins the limit itself, by
     * injecting one.
     */
    private val generousBound = 8L

    /** How many dials the slammed-handshake test below takes; see its KDoc for why 100. */
    private val dials = 100

    /**
     * A peer that completes the WebSocket upgrade and closes the socket in the
     * same TCP write — the refusal of every other test in this class, stripped
     * of its round trip.
     *
     * A real [WsListener] refuses on the peer's *hello*, so its close costs a
     * round trip and lands somewhere random relative to the dialling thread.
     * Writing the 101 response and the CLOSE frame together puts the close in
     * the dialler's read buffer at the instant the handshake completes, which
     * is the earliest a peer can possibly close and so the widest this window
     * gets. It is still a race — see the test below for why it is run a hundred
     * times rather than once.
     *
     * **Two details here are load-bearing and measured, not incidental.**
     * Connections are served on the accept thread itself rather than one thread
     * each, and the dials below use a 20ms schedule. Against the unfixed
     * transport, that pair failed 22 of 100 dials; a thread per connection made
     * it 0 of 100, and a 200ms schedule 2 of 100. Tidying either away leaves a
     * test that passes against the defect it was written for.
     */
    private class SlammingPeer : AutoCloseable {
        private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        private val accepted = CopyOnWriteArrayList<Socket>()

        val port: Int get() = server.localPort

        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrElse { return@Thread }
                accepted += socket
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }, "slamming-peer").apply { isDaemon = true; start() }

        /**
         * `awaitReachable`'s bare TCP probe lands here too: it connects and
         * closes without sending a request line, so `key` stays null and this
         * returns having written nothing.
         */
        private fun serve(socket: Socket) {
            val input = socket.getInputStream().bufferedReader()
            var key: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(':').trim()
                }
            }
            val accept = key?.let {
                Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((it + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                )
            } ?: return
            val response = (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            // A server-to-client CLOSE frame, status 1000: unmasked, 2-byte payload.
            val close = byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xE8.toByte())
            socket.getOutputStream().apply {
                write(response + close)
                flush()
            }
            // Let the dialler read that buffer before the FIN arrives, so what it
            // sees is a close frame and not a bare end-of-stream.
            Thread.sleep(50)
        }

        override fun close() {
            runCatching { server.close() }
            accepted.forEach { runCatching { it.close() } }
            acceptor.join(2_000)
        }
    }

    /**
     * computenet-ulgy: **a dial the peer closes at once is a connection, not a
     * failed dial** — and until this was fixed it was what made the rest of this
     * class flake on ubuntu `build-test-fast`.
     *
     * [WsTransport.connect] required `connectBlocking` to return true, and
     * java-websocket's `connectBlocking` is `connectLatch.await(timeout) &&
     * isOpen()`, with the latch counted down from `onWebsocketOpen` *and* from
     * `onWebsocketClose`. A peer whose close was decoded before the dialling
     * thread got back to `isOpen()` therefore produced `false` from a dial that
     * *had* opened — and `connect` threw
     * `IllegalStateException: could not connect to <uri> — readyState=CLOSING`
     * for a socket that had opened, sent its hello and been refused exactly as
     * designed. That is the premise of this whole class
     * ([WsTransport.REFUSED_DIAL_LIMIT]): a refused dial opens first. So each of
     * the three tests above raced its own `connect` setup line against the
     * refusal it was written to study; on a loaded two-core runner the refusal
     * won. Observed on run 33848578396 (`a healed client re-peers…`, thrown from
     * this file's `connect` at line 259) and on run 33873071525 (`a dialler
     * refused at the listener's allowlist…`), on heads whose diffs touched no
     * `:wire` file at all.
     *
     * **Why a hundred dials and not one.** The window is between java-websocket
     * counting the latch down and this thread being rescheduled to read
     * `isOpen()`; nothing in the transport or the peer can order those two, so
     * the interference cannot be forced, only made likely. Measured against the
     * unfixed transport on a 16-core darwin host, with [SlammingPeer] configured
     * exactly as it ships (see its KDoc — both details are load-bearing):
     * **22 of 100** dials threw, and **32 of 100** on an independent re-run of
     * the same mutation during review. At the lower of those two rates a hundred
     * dials leave a false green at ~1e-11, while a single dial would have
     * reported a fix that isn't one roughly four times in five.
     * A busier host (CI's) only raises the rate. If this ever goes green against
     * an unfixed transport, the number to raise is the iteration count, not the
     * bound in [await].
     *
     * The distinction `connect` still owes its caller — opened versus *never*
     * opened — is pinned from the other side by `WsConnectRaceTest`'s "a give-up
     * names the readyState and the close the client saw": a peer that accepts
     * and closes without ever completing the upgrade must still throw.
     */
    @Test
    fun `a dial the peer closes at the handshake yields a connection, not a failed dial`() {
        val peer = SlammingPeer()
        val uri = URI("ws://localhost:${peer.port}")
        try {
            val refusals = mutableListOf<String>()
            var opened = 0
            repeat(dials) {
                val client = Stack(name = "client")
                try {
                    val connection = WsTransport.connect(uri, client.side, backoff = { 20L }, refusedDialLimit = 2)
                    if (connection.unadmittedOpens >= 1) opened++
                    connection.shutdown()
                } catch (e: IllegalStateException) {
                    refusals += e.message ?: "<no message>"
                }
            }
            if (refusals.isNotEmpty()) {
                throw AssertionFailedError(
                    "${refusals.size} of $dials dials to a peer that closes at the handshake were reported as " +
                        "failed connections, though every one of them opened. A refusal is an open followed by a " +
                        "close, and connect must hand back the connection that observed it. First: " +
                        refusals.first(),
                )
            }
            if (opened != dials) {
                throw AssertionFailedError(
                    "$opened of $dials returned connections had actually opened a socket; connect must not report " +
                        "success for a dial that never opened",
                )
            }

            // And the object handed back is the live one that carries the refusal
            // bound — unreachable by a caller that got an exception instead.
            val connection = WsTransport.connect(uri, Stack(name = "client").side, backoff = { 20L }, refusedDialLimit = 2)
            try {
                await("the slammed dialler gives up") { connection.abandonedAfterRefusals }
            } finally {
                connection.shutdown()
            }
        } finally {
            peer.close()
        }
    }

    @Test
    fun `a dialler refused at the listener's allowlist stops re-dialling instead of looping forever`() {
        val server = Stack(name = "server", allow = setOf(KeyId("good")))
        val listener = WsTransport.listen(0, server.side)
        try {
            val mallory = Stack(name = "mallory")
            // A near-zero schedule so an UNBOUNDED dialler blows past the bound
            // in milliseconds rather than in minutes: at 20ms the settle window
            // below is room for ~100 further re-dials.
            val refused = WsTransport.connect(URI("ws://localhost:${listener.port}"), mallory.side) { 20L }
            try {
                // The loop is live: the first refusal happened and at least one
                // re-dial followed it. Without this the assertion below would
                // pass vacuously against a transport that never retried at all.
                await("the listener records a refusal and at least one refused re-dial after it") {
                    listener.admissionDenialCount >= 2L
                }

                // Now watch it stop. An unbounded dialler keeps charging the
                // listener one accept + one hello parse every 20ms.
                val settleWindowMs = 2_000L
                val deadline = System.currentTimeMillis() + settleWindowMs
                while (System.currentTimeMillis() < deadline) {
                    val seen = listener.admissionDenialCount
                    if (seen > generousBound) {
                        throw AssertionFailedError(
                            "the refused dialler is re-dialling without bound: the listener has now paid " +
                                "$seen accept+hello refusals for one peer it already refused, which is more than " +
                                "the $generousBound this transport may cost it. At a 20ms schedule that is " +
                                "unbounded in practice; at the production schedule it is ~1/second forever.",
                        )
                    }
                    Thread.sleep(25)
                }

                // ...and it really has stopped, not merely slowed: no further
                // refusal at all across a second window.
                val quiescent = listener.admissionDenialCount
                Thread.sleep(500)
                val after = listener.admissionDenialCount
                if (after != quiescent) {
                    throw AssertionFailedError(
                        "the refused dialler never stopped: the listener paid $quiescent refusals, then " +
                            "${after - quiescent} more in the next 500ms. A refused dialler must give up, " +
                            "because nothing on this wire will ever tell it that it was refused.",
                    )
                }
                // The DEFAULT limit is the constant, not an independent literal
                // — a band of one, for the in-flight re-dial.
                if (refused.unadmittedOpens !in
                    WsTransport.REFUSED_DIAL_LIMIT..(WsTransport.REFUSED_DIAL_LIMIT + 1)
                ) {
                    throw AssertionFailedError(
                        "a dialler built with the default limit gave up after ${refused.unadmittedOpens} " +
                            "unadmitted opens rather than WsTransport.REFUSED_DIAL_LIMIT " +
                            "(${WsTransport.REFUSED_DIAL_LIMIT})",
                    )
                }
                if (!refused.abandonedAfterRefusals) {
                    throw AssertionFailedError(
                        "the dialler stopped charging the listener but does not report having given up; a client " +
                            "that has stopped retrying must never be invisible (WsConnection.scheduleReconnect's " +
                            "own rule for an interrupted retry loop)",
                    )
                }
            } finally {
                refused.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }

    /**
     * The limit is the thing that governs, and the bound is per-connection.
     *
     * Injecting a limit of 2 pins that [WsTransport.REFUSED_DIAL_LIMIT] is a
     * real parameter rather than a number the code happens to agree with: the
     * listener's cost tracks the limit it was given, to within the one re-dial
     * that can be in flight when the run completes.
     */
    @Test
    fun `the refused-dial limit governs exactly, and does not close the listener to anyone else`() {
        val server = Stack(name = "server", allow = setOf(KeyId("good")))
        val listener = WsTransport.listen(0, server.side)
        val uri = URI("ws://localhost:${listener.port}")
        try {
            val mallory = Stack(name = "mallory")
            val refused = WsTransport.connect(uri, mallory.side, backoff = { 20L }, refusedDialLimit = 2)
            try {
                await("the refused dialler gives up") { refused.abandonedAfterRefusals }
                // The limit governs to within the one re-dial that can be in
                // flight when the run completes — see
                // [WsTransport.REFUSED_DIAL_LIMIT]'s "plus at most one". The
                // point is that TWO governs and five does not: an off-by-one
                // window is a bound, an unbounded loop is not.
                Thread.sleep(500)
                Thread.sleep(500)
                if (refused.unadmittedOpens !in 2..3) {
                    throw AssertionFailedError(
                        "gave up after ${refused.unadmittedOpens} unadmitted opens for a limit of 2",
                    )
                }
                if (listener.admissionDenialCount !in 2L..3L) {
                    throw AssertionFailedError(
                        "the listener paid ${listener.admissionDenialCount} accept+hello refusals for a dialler " +
                            "limited to 2; only the one re-dial that can already be in flight may exceed the limit",
                    )
                }
            } finally {
                refused.shutdown()
            }

            // The give-up belongs to the refused connection, not to the
            // listener: an allowlisted peer still peers on the same socket.
            val good = Stack(name = "good")
            val connection = WsTransport.connect(uri, good.side)
            try {
                await("the admitted peer reaches its auth level") { connection.achievedAuthLevel != null }
                // One open is counted (every open is), and an admitted peering
                // never accumulates a run: it is nowhere near the limit and has
                // given up on nothing.
                if (connection.abandonedAfterRefusals ||
                    connection.unadmittedOpens >= WsTransport.REFUSED_DIAL_LIMIT
                ) {
                    throw AssertionFailedError(
                        "an admitted peering reached the refusal bound: abandoned=" +
                            "${connection.abandonedAfterRefusals}, run=${connection.unadmittedOpens}",
                    )
                }
                if (listener.admissionDenialCount !in 2L..4L) {
                    throw AssertionFailedError("admitting a peer moved the listener's denial count")
                }
            } finally {
                connection.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }

    /**
     * computenet-f6dr: an abandoned `:wire` client has a way back —
     * [WsConnection.heal] — the same shape `IrohConnection.heal` already gave
     * `:iroh`. This pins that a healed client re-peers once the listener's
     * allowlist has since admitted it, which is exactly the false-positive
     * case [WsTransport.REFUSED_DIAL_LIMIT]'s own KDoc names: a peering that
     * genuinely could not stay up gets abandoned as though it had been
     * refused, and an operator who judges that wrong needs a way to retry
     * without reconstructing the connection.
     *
     * The allowlist is a mutable set held by the test, not by [Stack]: the
     * listener's [civictech.cell.wire.Peering.Side.admits] reads `allow` at
     * call time (`allow == null || peer in allow`), so mutating the same set
     * instance after construction changes what the *next* hello sees without
     * touching kernel or reconstructing the listener's side.
     *
     * Peering is confirmed from the **listener's** [WsListener.achievedAuthLevels],
     * not from the healed client's own [WsConnection.achievedAuthLevel]:
     * `achievedAuthLevel` reports which side *this* Session has admitted, not
     * which side admitted *it* — the client's session admits the listener's
     * (open-policy) hello independently of whether the listener in turn
     * admits the client, so it can read non-null on a connection the listener
     * is about to refuse and close. The listener admitting mallory is the
     * property the allowlist actually governs.
     */
    @Test
    fun `a healed client re-peers once the listener's allowlist admits it`() {
        val allow = mutableSetOf<KeyId>()
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val serverSide = Peering.Side(registry, bridgeHost, peer = PeerId("server"), allow = allow)
        val listener = WsTransport.listen(0, serverSide)
        try {
            val mallory = Stack(name = "mallory")
            val uri = URI("ws://localhost:${listener.port}")
            val healable = WsTransport.connect(uri, mallory.side, backoff = { 20L }, refusedDialLimit = 2)
            try {
                await("the healable dialler gives up") { healable.abandonedAfterRefusals }
                if (listener.achievedAuthLevels.any { it != null }) {
                    throw AssertionFailedError("the listener admitted mallory despite an empty allowlist")
                }

                // The allowlist changes underneath the abandoned connection —
                // an operator allowlisting the peer, or judging the refusal a
                // false positive either way calls the same method.
                allow += KeyId("mallory")
                healable.heal()

                await("the listener admits the healed client") { listener.achievedAuthLevels.any { it != null } }
                if (healable.abandonedAfterRefusals) {
                    throw AssertionFailedError(
                        "heal() re-peered the client but it still reports abandonedAfterRefusals=true; a healed " +
                            "connection must clear the give-up it is healing from",
                    )
                }
            } finally {
                healable.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }
}
