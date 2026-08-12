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
import java.net.ConnectException
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
 * The first version of this test was a **coin flip** on `ubuntu-latest`: it PASSED
 * on CI run 31585819446 (2026-08-12T10:05Z) and FAILED on run 31587771011
 * (10:32Z) with `-1 should be >= 1` — the listener still bound after 200
 * deliberate resets — with only comment changes between the two commits. Neither
 * outcome was a reproduction: the pass came from [accepts] scoring a transient
 * connect timeout as a kill, which is why that predicate now re-probes instead of
 * trusting one failed connect. The chain has three links, and exactly one of them
 * breaks on Linux. The same JDK-only probe, run on both platforms, 10 trials each:
 *
 * | link | macOS 26.6 / JDK 21 and 26 | Linux 6.12 / Temurin 21.0.11 (container, aarch64) |
 * |---|---|---|
 * | `close()` with `SO_LINGER 0` delivers RST to the listener | yes | **yes** — a `read()` on the accepted channel throws `SocketException: Connection reset`, 10/10 |
 * | `accept()` throws | no, 0/10 | no, 0/10 |
 * | `setTcpNoDelay` / `setKeepAlive` on the reset victim throws | **yes** — `SocketException: Invalid argument`, 10/10 | **no** — both succeed, 10/10 |
 *
 * Confirmed twice more in review, both platforms, same JDK 21 class file: a tight
 * 3000-cycle reset storm through a `Selector` acceptor raises
 * `SocketException: Invalid argument` from `setTcpNoDelay` on 2997/3000 accepts on
 * macOS and on **0/3000** on Linux (Linux accepted all 3000 cleanly); and at the
 * syscall level `setsockopt(TCP_NODELAY)` on a reset victim returns `EINVAL`
 * 10/10 on macOS. The end-to-end consequence, against a bare `WebSocketServer`:
 * the listening socket is truly unbound (ECONNREFUSED, still refused 500ms later)
 * in 15/15 macOS trials and in **0/15** Linux trials.
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
 * **Consequence for the flake this bead investigated, stated carefully because it
 * is easy to over-read:** every rate measurement on computenet-dqy.34 (3/1140
 * suite runs, 2/240 fresh-JVM, and the 1/100 `origin/main` baseline) was taken on
 * macOS. Those macOS failures are explained. What is *not* established is that
 * the same signature on the Linux runners has the same cause — it cannot, since
 * this mechanism does not exist there. So do not book this bead as the fix for a
 * `build-test-fast` flake: if "timed out awaiting: collector announced" has ever
 * been seen on a CI run, it is a **different** cause.
 *
 * computenet-dqy.34's own INVESTIGATE list left that question open ("whether the
 * same signature has ever been seen on the Linux runners"). Partially answered in
 * review: the eleven most recent failed CI runs (2026-08-09 to 08-11, every
 * `feature/computenet-dqy.` failure plus the two `fix/` ones) contain **zero**
 * occurrences of "collector announced" in their failed-step logs. Bounded sample,
 * and CI keeps logs for a limited window — but it is the first Linux-side
 * evidence on the question, and it points the same way as the platform
 * measurement: this flake family is a macOS-only phenomenon.
 *
 * computenet-dqy.37 (the repair) is still worth doing on its own terms: it is a
 * production defect on any BSD host — a peer that resets while a listener is
 * accepting it takes that listener off the air permanently, and nothing reports
 * it — and macOS is the development platform.
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

    /**
     * True unless the port is **unbound**, which is the claim this test makes —
     * and specifically not "unless connecting failed somehow". One failed connect
     * does not distinguish the two, and both wrong readings have been measured
     * against a bare `WebSocketServer` (120 macOS trials, 15 Linux):
     *
     * - `ConnectException` (ECONNREFUSED) is the only *definitive* answer:
     *   nothing is bound. macOS 91/120, Linux 0/15.
     * - Some other `IOException` is ambiguous, and which way it falls is
     *   platform-shaped. On macOS it is `SocketException: Connection reset by
     *   peer` — the dying listener's own RST racing this connect — and the
     *   listener really is gone, 29/29 still refused 500ms later. On Linux it is
     *   `SocketTimeoutException` from a backlog drop under the 200-cycle reset
     *   storm, and the listener is **alive**, 8/8 answering again within 500ms.
     *
     * Reading the ambiguous case as death is how the first version of this test
     * PASSED on `ubuntu-latest` with the mechanism absent; reading it as life
     * would fail ~24% of macOS runs. So it is neither guessed nor classified by
     * errno: it is re-probed, because a listening socket that is really gone
     * stays gone. Costs nothing on the common path — the definitive refusal
     * returns immediately.
     */
    private fun accepts(port: Int): Boolean {
        val failure = connectFailure(port) ?: return true
        if (failure is ConnectException) return false
        repeat(5) {
            Thread.sleep(100)
            if (connectFailure(port) == null) return true
        }
        return false
    }

    /** `null` when the connect succeeded. */
    private fun connectFailure(port: Int): IOException? =
        try {
            Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
            null
        } catch (e: IOException) {
            e
        }

    @Test
    @EnabledOnOs(
        value = [OS.MAC],
        disabledReason = "SKIPPED, AND THEREFORE GUARDING NOTHING HERE: this characterizes a " +
            "BSD/macOS-specific mechanism. Measured on Linux 6.12/JDK 21, setTcpNoDelay on a " +
            "reset-victim socket succeeds (10/10, and 3000/3000 in a tight storm) instead of " +
            "throwing SocketException, so the RST surfaces on a later per-connection read, " +
            "java-websocket blames the right connection, and the listening socket survives: " +
            "0/15 trials ever reached ECONNREFUSED on Linux against 15/15 on macOS. The defect " +
            "does not exist on this platform. So on the Linux CI runners this test is a skip and " +
            "build-test-fast going green says nothing about computenet-dqy.34 — and if that " +
            "failure signature has ever appeared on CI, it has a different cause. See the class " +
            "KDoc, section 'Platform scope'.",
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
