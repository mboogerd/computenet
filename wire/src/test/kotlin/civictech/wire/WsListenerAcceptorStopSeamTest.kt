package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.java_websocket.WebSocketAdapter
import org.java_websocket.WebSocketImpl
import org.java_websocket.WebSocketServerFactory
import org.java_websocket.drafts.Draft
import org.java_websocket.server.DefaultWebSocketServerFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.ByteChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

/**
 * computenet-dqy.56: **an acceptor that stopped must be readable, not only
 * printable.**
 *
 * computenet-dqy.39 gave the lost-listening-socket diagnosis a programmatic
 * seam, `WsListener.listeningSocketLoss`, precisely so a health check or a test
 * could poll it instead of scraping stderr. computenet-dqy.37's repair then
 * moved accepting onto a daemon thread of its own, which added a *second* way
 * for this listener to go deaf — the acceptor stops while the listening channel
 * stays OPEN — and gave it no seam at all: `reportAcceptorStopped` spoke through
 * `onError`/stderr only. That asymmetry is the gap this closes, and it favours
 * the wrong half: of the two, the stopped acceptor is the one a dialer suffers
 * *more* from, because a TCP connect into an unattended backlog hangs instead of
 * being refused fast.
 *
 * `WsListenerAcceptorSurvivalTest` (computenet-dqy.37.1) covers the
 * per-connection half — an unchecked throw out of `admit` costs that connection
 * and nothing more — and its KDoc records that `acceptLoop`'s *own* exit through
 * `reportAcceptorStopped` had no seam reachable from outside the class. This
 * test covers that exit.
 *
 * ## What it injects, and why that seam
 *
 * Still no test hook in production code, and still nothing platform-specific
 * (unlike `WsListenerAcceptRstTest`'s BSD-only reset storm — this runs on Linux
 * CI). Two pieces of public API compose into a deterministic exit of
 * `acceptLoop` with the listening channel still open:
 *
 * - `WebSocketServer.setWebSocketFactory` is public and `admit` reads
 *   `getWebSocketFactory()` on every accept, so a factory whose `createWebSocket`
 *   throws drives `acceptLoop`'s inner `catch (t: Throwable)` — the handler that
 *   calls `onError` to report the dropped connection.
 * - The exception it throws renders hostilely: its `toString` throws. `onError`
 *   calls `ex.printStackTrace()`, which prints the cause chain, so reporting
 *   *that* connection throws out of the inner catch — past it, out of the loop,
 *   into the `finally`.
 *
 * That is not a contrived shape. It is the same class of failure dqy.37.1's
 * test guards against, one frame further out: the exit path exists exactly
 * because foreign code (a factory, an `onError` override, a rendering of a
 * foreign throwable) runs on the acceptor thread, and any of it can be unchecked.
 * It also happens to exercise the ordering `reportAcceptorStopped` guarantees —
 * the seam is published before `onError` runs, so a diagnosis survives a report
 * that cannot be printed.
 *
 * ## What it discriminates
 *
 * Measured, not asserted, against the pre-change `WsTransport.kt`:
 *
 * - with the seam removed entirely, `:wire:compileTestKotlin` fails —
 *   `unresolved reference 'acceptorStopped'`; and
 * - with the property present but `reportAcceptorStopped` left as it was
 *   (`onError` only, nothing recorded), this test fails on
 *   `listener.acceptorStopped` being null while the listener is provably deaf.
 *
 * The deafness check is what keeps the seam honest: the dial below completes its
 * TCP connect into a backlog nobody is draining and never finishes a handshake,
 * which is the state `listeningSocketLoss` — asserted null here — cannot see.
 */
class WsListenerAcceptorStopSeamTest {

    private fun side() = Peering.Side(LocationRegistry(), ManagedHost())

    /** Renders by throwing: `toString` is what `printStackTrace` calls on a cause. */
    private class HostileThrowable : RuntimeException(INJECTED) {
        override fun toString(): String = throw IllegalStateException(HOSTILE)
    }

    /**
     * Fails every admission, and fails it in a way that cannot be *reported*
     * either — which is what carries the failure out of `acceptLoop`.
     */
    private class HostileFactory(
        private val delegate: WebSocketServerFactory = DefaultWebSocketServerFactory(),
    ) : WebSocketServerFactory {
        override fun createWebSocket(a: WebSocketAdapter, d: Draft): WebSocketImpl = throw HostileThrowable()

        override fun createWebSocket(a: WebSocketAdapter, d: List<Draft>): WebSocketImpl = throw HostileThrowable()

        override fun wrapChannel(channel: SocketChannel, key: SelectionKey): ByteChannel =
            delegate.wrapChannel(channel, key)

        override fun close() = delegate.close()
    }

    /** Connect, then hang up: enough to drive one accept through `admit`. */
    private fun knockOn(port: Int) {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
    }

    private fun awaitAcceptorStopped(listener: WsTransport.WsListener): WsTransport.WsListener.AcceptorStoppedException? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            listener.acceptorStopped?.let { return it }
            Thread.sleep(25)
        }
        return listener.acceptorStopped
    }

    @Test
    @Timeout(60)
    fun `an acceptor that stops with its listening socket open is readable, not just printed`() {
        val listener = WsTransport.listen(0, side())
        val port = listener.port
        listener.setWebSocketFactory(HostileFactory())

        val reported = ByteArrayOutputStream()
        val realErr = System.err
        val stopped: WsTransport.WsListener.AcceptorStoppedException?
        try {
            // `WsListener.onError` writes to stderr — captured both to keep a
            // deliberately hostile stack trace out of the build log and to
            // assert the human-facing half still speaks.
            System.setErr(PrintStream(reported, true))
            knockOn(port)
            stopped = awaitAcceptorStopped(listener)
        } finally {
            System.setErr(realErr)
        }

        try {
            stopped.shouldNotBeNull()
            stopped.message.shouldNotBeNull() shouldContain "the vendored acceptor for port $port stopped"
            listener.acceptorStopped shouldBe stopped // one-shot, and the object handed to onError
            reported.toString() shouldContain "the vendored acceptor for port $port stopped"

            // The asymmetry, made an assertion: nothing closed the listening
            // channel, so computenet-dqy.39's seam has nothing to say about a
            // listener that is nonetheless deaf.
            listener.listeningSocketLoss shouldBe null

            // And it really is deaf, in the way the diagnosis claims. Raw
            // sockets rather than a WebSocket client, so the evidence is exactly
            // what a dialer sees and nothing waits on a library latch: the TCP
            // connect SUCCEEDS — into a backlog nobody drains, which is why this
            // is worse than a closed listening socket — and the upgrade request
            // is then answered by nothing at all.
            Socket().use { deaf ->
                deaf.connect(InetSocketAddress("localhost", port), 2_000)
                deaf.soTimeout = 2_000
                deaf.getOutputStream().apply {
                    write(
                        (
                            "GET / HTTP/1.1\r\nHost: localhost:$port\r\nUpgrade: websocket\r\n" +
                                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).toByteArray()
                    )
                    flush()
                }
                shouldThrow<SocketTimeoutException> { deaf.getInputStream().read() }
            }
        } finally {
            runCatching { listener.stop(1_000) }
        }
    }

    private companion object {
        const val INJECTED = "computenet-dqy.56: injected factory failure"
        const val HOSTILE = "computenet-dqy.56: injected failure that cannot be rendered"
    }
}
