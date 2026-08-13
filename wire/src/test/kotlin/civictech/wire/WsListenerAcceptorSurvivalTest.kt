package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.java_websocket.WebSocketAdapter
import org.java_websocket.WebSocketImpl
import org.java_websocket.WebSocketServerFactory
import org.java_websocket.drafts.Draft
import org.java_websocket.server.DefaultWebSocketServerFactory
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.channels.ByteChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * computenet-dqy.37.1: **the vendored acceptor must not be able to die
 * quietly.**
 *
 * computenet-dqy.37's repair moved this transport's accept off java-websocket's
 * selector onto a daemon thread of its own (`WsListener.takeOverAccepting` /
 * `acceptLoop` / `admit`; see `WsListenerAcceptRstTest` for why). That relocates
 * a hazard as well as repairing one. The acceptor thread is now the *only* thing
 * accepting on the listening channel, so if it ends, the listener is deaf while
 * its listening socket is still **open** — and computenet-dqy.39's
 * `watchListeningSocket` cannot see that, because it polls only for a channel
 * that CLOSED. The observable consequence is strictly worse than the defect
 * dqy.37 repairs: dialers complete a TCP connect into an unattended backlog and
 * **hang** instead of being refused fast, and `listeningSocketLoss` stays null
 * the whole time.
 *
 * `admit` is the realistic way for that to happen. Its own `catch`es are narrow
 * (`IOException`, `InterruptedException`), while `CancelledKeyException` and
 * `ClosedSelectorException` are unchecked, and the `WebSocketServerFactory` it
 * calls is foreign code. Before b23f7a8 an unchecked throw from there unwound
 * straight out of `acceptLoop`: the thread died, the JVM's default handler
 * printed a stack trace nobody reads, and the listener never accepted again.
 *
 * ## What this test injects, and why that seam
 *
 * `WebSocketServer.setWebSocketFactory` is public and `admit` reads
 * `getWebSocketFactory()` on every accept, so a factory whose `createWebSocket`
 * throws an unchecked exception drives exactly that path, on every platform,
 * with no race — unlike `WsListenerAcceptRstTest`'s reset storm, which needs
 * BSD `setsockopt` semantics and is therefore macOS-only. No test hook was added
 * to production code for this.
 *
 * ## What it discriminates
 *
 * Against `WsTransport.kt` as of fc13802 (the pre-review-repair revision) the
 * *first* injected failure kills the acceptor, so:
 *
 * - the second and third connections are never admitted and produce no report —
 *   this test requires one report **per** failed admission, so a single fatal
 *   one does not pass; and
 * - the closing handshake, taken with the factory restored to health, never
 *   completes — `WsTransport.connect` fails its `connectBlocking` check.
 *
 * Both assertions were confirmed to fail against a locally reverted
 * `acceptLoop`/`admit` before this file was committed.
 *
 * ## What it does NOT cover, stated so the gap is not mistaken for coverage
 *
 * The other half of b23f7a8 — `acceptLoop`'s `finally` reporting through
 * `reportAcceptorStopped` when the loop itself exits with the listening channel
 * still open — has no seam reachable from outside the class: the accept
 * selector is a local, and `server.accept()` cannot be made to throw on demand
 * without a test hook in production code. That path is reviewed by reading, not
 * by this test. It is also the one place where the diagnosis is stderr-only:
 * unlike computenet-dqy.39's loss, an acceptor that stopped has no programmatic
 * seam an operator's health check could poll.
 */
class WsListenerAcceptorSurvivalTest {

    private fun side() = Peering.Side(LocationRegistry(), ManagedHost())

    /**
     * A real `DefaultWebSocketServerFactory` until [failing] is set, and then an
     * unchecked throw out of `createWebSocket` — the foreign-code failure
     * `admit`'s own `catch`es do not cover.
     */
    private class ThrowingFactory(
        private val delegate: WebSocketServerFactory = DefaultWebSocketServerFactory(),
    ) : WebSocketServerFactory {
        val failing = AtomicBoolean(false)

        private fun guard() {
            if (failing.get()) throw IllegalStateException(INJECTED)
        }

        override fun createWebSocket(a: WebSocketAdapter, d: Draft): WebSocketImpl {
            guard()
            return delegate.createWebSocket(a, d)
        }

        override fun createWebSocket(a: WebSocketAdapter, d: List<Draft>): WebSocketImpl {
            guard()
            return delegate.createWebSocket(a, d)
        }

        override fun wrapChannel(channel: SocketChannel, key: SelectionKey): ByteChannel =
            delegate.wrapChannel(channel, key)

        override fun close() = delegate.close()
    }

    /** Connect, then hang up: enough to drive one accept through `admit`. */
    private fun knockOn(port: Int) {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
    }

    private fun awaitOccurrences(sink: ByteArrayOutputStream, needle: String, atLeast: Int): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = sink.toString().windowed(needle.length).count { it == needle }
            if (seen >= atLeast) return seen
            Thread.sleep(25)
        }
        return seen
    }

    @Test
    fun `a connection whose admission throws costs that connection only, and is reported`() {
        val listener = WsTransport.listen(0, side())
        val port = listener.port
        val factory = ThrowingFactory()
        listener.setWebSocketFactory(factory)

        val reported = ByteArrayOutputStream()
        val realErr = System.err
        var connection: WsTransport.WsConnection? = null
        try {
            // `WsListener.onError` writes to stderr, exactly as
            // `WsListenerAcceptRstTest` relies on: capturing it is how "the
            // acceptor said so" becomes an assertion rather than a claim.
            System.setErr(PrintStream(reported, true))

            factory.failing.set(true)
            repeat(3) { knockOn(port) }

            // One report per failed admission. A single report would also be
            // satisfied by an acceptor that died on the first one, which is the
            // defect this test exists to exclude.
            awaitOccurrences(reported, ADMIT_FAILED, atLeast = 3) shouldBeGreaterThanOrEqual 3
            reported.toString() shouldContain INJECTED

            factory.failing.set(false)
            // The proof that matters to a dialer, and the one a TCP connect
            // cannot give: a full WebSocket handshake still completes, so
            // something is still accepting rather than leaving arrivals in an
            // unattended backlog.
            connection = WsTransport.connect(URI("ws://localhost:$port"), side()) { 20L }

            System.setErr(realErr)

            connection.isOpen shouldBe true
            // Nothing closed the listening channel, so computenet-dqy.39's
            // watchdog has nothing to say — and that is precisely why the
            // acceptor has to speak for itself.
            listener.listeningSocketLoss shouldBe null
        } finally {
            System.setErr(realErr)
            runCatching { connection?.closeBlocking() }
            runCatching { listener.stop(1_000) }
        }
    }

    private companion object {
        const val INJECTED = "computenet-dqy.37.1: injected factory failure"
        const val ADMIT_FAILED = "admitting an accepted connection failed"
    }
}
