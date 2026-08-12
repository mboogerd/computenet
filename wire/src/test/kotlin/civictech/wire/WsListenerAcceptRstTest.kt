package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * computenet-dqy.34: **the named mechanism behind "timed out awaiting: collector
 * announced"** — a `:wire` flake measured at ~1% per suite run *on macOS*, on
 * both sides of the computenet-dqy.28/.32 bind work, and seen on four different
 * tests (`WsReconnectLoopBoundTest`, `WsTransportSmokeTest`, `WsPeerIdentityTest`,
 * `WsThreadEntryConformanceTest`), always on an *announcement* await that the
 * socket had already connected for.
 *
 * **This test is disabled off macOS, and it therefore guards nothing on the
 * Linux CI runners.** That is not a convenience: the mechanism provably does not
 * exist on Linux, and the reason is measured below under "Platform scope". Read
 * that section before trusting this file as a gate — in the fast lane on
 * `ubuntu-latest` it is a skip, and `build-test-fast` going green says nothing
 * about this defect.
 *
 * ## The mechanism
 *
 * **A TCP reset that races java-websocket's accept closes the listener's whole
 * listening socket, silently.**
 *
 * `WebSocketServer.doAccept` (1.6.0) configures the freshly accepted socket
 * before it has anything to attribute a failure to:
 *
 * ```
 * SocketChannel channel = server.accept();
 * channel.configureBlocking(false);
 * Socket socket = channel.socket();
 * socket.setTcpNoDelay(isTcpNoDelay());   // <- throws SocketException on a socket
 * socket.setKeepAlive(true);              //    whose peer has already sent RST
 * ```
 *
 * `doAccept`'s own `try` starts three statements later, at
 * `w.setChannel(...)` — read off the 1.6.0 bytecode, whose only entry in
 * `doAccept`'s exception table covers offsets 85..117, while `setTcpNoDelay`
 * is at 44. It declares `throws IOException`, so the exception lands in the
 * selector loop's last resort instead:
 *
 * ```
 * } catch (IOException ex) {
 *     handleIOException(key, null, ex);   // and handleIOException starts with
 * }                                       // `if (key != null) key.cancel();`
 * ```
 *
 * `key` there is the **server's** acceptable key, not the doomed connection's.
 * So the listening channel is deregistered *and* — `handleIOException` closes
 * `key.channel()` whenever it has no `WebSocket` to blame — closed. The
 * `WebSocketServer` object stays alive and its existing connections keep
 * working; it is simply deaf from that moment on, and the port is unbound.
 *
 * Which call throws is measured, not inferred: a JDK-only probe (bind a
 * `ServerSocketChannel`, connect with `SO_LINGER 0`, close, then accept) throws
 * `java.net.SocketException: Invalid argument` out of `setTcpNoDelay` — never
 * out of `accept()` or `configureBlocking` — 10/10 on Temurin 21.0.11 and 10/10
 * on JDK 26, macOS 26.6/aarch64. `accept()` raising `IOException` instead would
 * reach the same catch and unbind the same listening channel, so the diagnosis
 * does not rest on the exact call; the measurement just names it.
 *
 * ## Platform scope: this is a BSD/macOS mechanism, and Linux does not have it
 *
 * The first version of this test failed **deterministically** on `ubuntu-latest`
 * (`-1 should be >= 1`: the listener was still bound after 200 deliberate resets;
 * CI run 31587771011). The chain has three links, and exactly one of them breaks
 * on Linux. The same JDK-only probe, run on both platforms, 10 trials each:
 *
 * | link | macOS 26.6 / JDK 26 | Linux 6.12 / Temurin 21.0.11 (container, aarch64) |
 * |---|---|---|
 * | `close()` with `SO_LINGER 0` delivers RST to the listener | yes | **yes** — a `read()` on the accepted channel throws `SocketException: Connection reset`, 10/10 |
 * | `accept()` throws | no, 0/10 | no, 0/10 |
 * | `setTcpNoDelay` / `setKeepAlive` on the reset victim throws | **yes** — `SocketException: Invalid argument`, 10/10 | **no** — both succeed, 10/10 |
 *
 * So **the third link is the one that breaks.** The RST arrives on Linux just as
 * it does on macOS; the difference is entirely in `setsockopt`. BSD's
 * `setsockopt(TCP_NODELAY)` on a socket whose connection is already torn down
 * returns `EINVAL`, which the JDK surfaces as `SocketException`; Linux accepts
 * the option on the dead socket and reports the reset only on the first read.
 *
 * That places the RST *after* `doAccept` has finished, inside the selector loop's
 * per-connection read — where `handleIOException` is called with the doomed
 * connection's own `WebSocket`, cancels the right key, and closes the right
 * channel. The listening socket survives. On Linux java-websocket's error
 * handling is, for this stimulus, correct; the defect is specifically that BSD
 * reports a dead peer from inside `doAccept`'s unguarded prologue, where the only
 * thing available to blame is the server's own key.
 *
 * **Consequence for the flake this bead investigated:** every rate measurement on
 * computenet-dqy.34 (3/1140 suite runs, 2/240 fresh-JVM, and the 1/100
 * `origin/main` baseline) was taken on macOS. If those failures were this
 * mechanism, they cannot occur on the Linux CI runners at all, and this flake
 * family does not threaten `build-test-fast`. See the bead comment for what that
 * evidence does and does not establish.
 *
 * Nothing reports it. `handleIOException` only `log.trace()`s, `onError` is
 * never called (that is `handleFatal`'s path, which this is not), and this
 * repository has `slf4j-api` with **no provider**, so java-websocket's own log
 * is a no-op. That is the "a rare failure loses its own evidence" wall
 * computenet-dqy.28 and this bead's review both hit.
 *
 * ## Why that reads as a lost announcement
 *
 * Every `:wire` test that waits for an announcement waits for a peer's
 * `LocationRegistry.Remote` to appear, which needs a hello exchange. When the
 * listening socket dies:
 *
 * 1. the connection that was being accepted never completes;
 * 2. the dialer's `WsConnection` reconnect loop retries forever and gets
 *    `ECONNREFUSED` forever — nothing is bound;
 * 3. so no re-hello happens, so `Peering.announceTo` never runs again, so the
 *    peer's refs are never (re-)announced;
 * 4. and the test sits on a bounded await until it expires.
 *
 * Observed exactly that way in the reproduction runs: the first stderr line of a
 * failing run is one `[WsConnection] IntakeClosedException` (the dialer's own
 * catch-up send failing as its socket dies), then a single `Connection reset by
 * peer`, then `Connection refused` for the rest of the run.
 *
 * The resets come from the suite's own dialers: java-websocket's
 * `WebSocketClient.reset()` — which the zero-backoff reconnect loops call
 * constantly — closes a socket whose receive buffer may still hold the
 * listener's unread `HELLO`, and closing a TCP socket with unread data sends RST
 * rather than FIN. So the reconnect tests spray resets at live listeners, and
 * roughly 1% of suite runs land one inside `doAccept`'s window.
 *
 * ## What this test asserts, and why it asserts the defect
 *
 * Deliberately a **characterization** test: it pins the mechanism as an
 * executable fact rather than prose (this bead's acceptance criterion), because
 * the repair is not available from outside java-websocket — see the
 * `concord/corpus/DISPUTES.md` entry and the follow-up bead. A decorator around
 * `ServerSocketChannel` cannot work either: a channel handed to `Selector`
 * registration must be a `SelChImpl` of the same provider, so `accept()` cannot
 * be wrapped.
 *
 * **When the transport is fixed, invert this test** — assert that the listener
 * still accepts after the reset storm — rather than deleting it. Note that on
 * Linux the inverted assertion *already* passes, for the reason above, so the
 * inverted test would need to stay macOS-scoped to mean anything either.
 *
 * Measured on macOS 26.6 / JDK 21 (the toolchain), 15 trials: the listening
 * socket is gone after **1-3** resets, 15/15. It is not a rare race when the
 * reset is deliberate; the 1% is how often the suite's incidental resets happen
 * to land in the window.
 *
 * The mechanism also reproduces with **no code from this repository at all** —
 * a bare `WebSocketServer` on the 1.6.0 jar, bound to `localhost:0`, killed by
 * the same `SO_LINGER 0` connect: dead after 1-21 resets, 25/25 trials, and
 * `onError` invoked 0 times across all 25 (review of this bead). That matters
 * for what this test proves: a bare server writes nothing to its clients, so
 * the probe socket's own `close()` sends FIN, and the deliberate RST is the
 * only possible cause. Here the listener does send a `HELLO`, so [accepts]'s
 * close can send a reset too — which does not change the mechanism, only the
 * bookkeeping of which of the two sockets landed the fatal one.
 */
class WsListenerAcceptRstTest {

    private fun side() = Peering.Side(LocationRegistry(), ManagedHost())

    /** Connect and reset immediately: `SO_LINGER 0` makes `close()` send RST, not FIN. */
    private fun resetAgainst(port: Int) {
        try {
            Socket().use { socket ->
                socket.setSoLinger(true, 0)
                socket.connect(InetSocketAddress("localhost", port), 1_000)
            }
        } catch (_: IOException) {
            // the listener may already be gone; `accepts` below is the observation
        }
    }

    private fun accepts(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
            true
        } catch (_: IOException) {
            false
        }

    @Test
    @EnabledOnOs(
        value = [OS.MAC],
        disabledReason = "SKIPPED, AND THEREFORE GUARDING NOTHING HERE: this characterizes a " +
            "BSD/macOS-specific mechanism. Measured on Linux 6.12/JDK 21, setTcpNoDelay on a " +
            "reset-victim socket succeeds 10/10 instead of throwing SocketException, so the RST " +
            "surfaces on a later per-connection read, java-websocket blames the right connection, " +
            "and the listening socket survives — the defect does not exist on this platform. On " +
            "the Linux CI runners this test is a skip and build-test-fast going green says " +
            "nothing about computenet-dqy.34. See the class KDoc, section 'Platform scope'.",
    )
    fun `a reset that races the accept closes the whole listening socket, and nothing reports it`() {
        val listener = WsTransport.listen(0, side())
        val port = listener.port
        val reported = ByteArrayOutputStream()
        val realErr = System.err
        try {
            accepts(port) shouldBe true // the stimulus needs a live listener to kill

            // `WsListener.onError` is the transport's only reporting seam and it
            // writes to stderr, so capturing stderr is how "nothing reported it"
            // becomes an assertion rather than a claim.
            System.setErr(PrintStream(reported, true))
            var deadAfter = -1
            // Generously bounded: measured at 1-3, so 200 cannot be a false
            // positive — and a listener that survived all 200 would mean the
            // mechanism no longer holds, which is what should flip this test.
            for (attempt in 1..200) {
                resetAgainst(port)
                if (!accepts(port)) {
                    deadAfter = attempt
                    break
                }
            }
            System.setErr(realErr)

            deadAfter shouldBeGreaterThanOrEqual 1
            deadAfter shouldBeLessThan 200

            // the silence is the other half of the defect: the listener loses its
            // listening socket without a word, so the only symptom left anywhere
            // is a distant test's bounded await expiring
            reported.toString() shouldNotContain "[WsListener]"
        } finally {
            System.setErr(realErr)
            runCatching { listener.stop(1_000) }
        }
    }
}
