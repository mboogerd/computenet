package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A two-process startup is an ordinary race: whichever peer's `main()` calls
 * `connect()` may run before the other peer has bound its listener yet. Found
 * via CI's demo:exchange peer log — a refused first attempt used to crash the
 * whole process instead of retrying like a post-connect drop does.
 *
 * ## Why this test cannot use [HeldPort] (computenet-dqy.26)
 *
 * The three `relisten` tests removed their close-then-rebind race by never
 * unbinding the port ([HeldPort]). This test cannot borrow that shape, because
 * its stimulus is the opposite one: `connect()` must meet a port that **nothing
 * answers**, and every socket that reserves a TCP port also changes what the
 * port answers. Measured here rather than assumed (macOS 15, JDK 21):
 *
 * - A [HeldPort] guard is a listening socket, so it completes the TCP handshake
 *   `WsTransport.connect`'s reachability probe waits on — the probe returns at
 *   once and the real handshake then fails against the guard. `HeldPort`'s own
 *   KDoc records this: `serve` before `connect`, which is precisely what this
 *   test must not do.
 * - The obvious escape — reserve the port with a socket that is *bound but not
 *   listening* — does hold the port (an ordinary bind and an `SO_REUSEADDR`
 *   bind are both rejected, measured). What it cannot hold is a port that
 *   *refuses*, and refusal is the stimulus: on macOS such a socket **drops** the
 *   SYN, so `awaitReachable`'s untimed `Socket(host, port)` fails `ETIMEDOUT`
 *   after ~7.8s on loopback (measured, three runs) where an unbound port refuses
 *   in under a millisecond. That is the disqualifying part — the test would stop
 *   reproducing the production failure it exists for, the demo:exchange startup
 *   `ECONNREFUSED`, and would pay a SYN timeout per probe to do it. The handover
 *   is not clean either: in a mixed `SO_REUSEPORT` group (silent holder + real
 *   listener) every connect was dropped while the holder was in the group (6/6)
 *   and succeeded the instant it closed, so `HeldPort.serve`'s bind-before-release
 *   overlap would have to be absorbed by the dialer's retry loop instead of being
 *   invisible. All of this is macOS 15 / JDK 21; Linux — where the required checks
 *   run — was not measured, and a holder there may well reset rather than drop.
 *   That would change the cost, not the stimulus, so the rejection stands either
 *   way: no socket can hold a TCP port *and* let it refuse.
 *
 * So the port is genuinely free for an instant, and the two remaining defences
 * are: make that instant as short as it can be, and make losing it *fail* rather
 * than hang.
 *
 * - **Shortest instant.** Both stacks are built before the port is chosen, and
 *   the listener's channel is created before its bind, so nothing but a thread
 *   hand-off sits between choosing the port and taking it back. The 300ms sleep
 *   this test used to wait out is gone: the listener binds the moment the dialer
 *   *reports* a failed probe, which also upgrades the stimulus from assumed to
 *   observed — a sleep that lost its ordering used to leave the test passing
 *   while proving nothing. `connect` reaches [WsTransport.DEFAULT_RECONNECT_BACKOFF]'s
 *   seat from exactly two places: `awaitReachable`'s catch block, so the first
 *   call happens-after a refused probe, and the post-open reconnect loop, which
 *   `connect` can only reach once a probe *succeeded* — i.e. once someone else
 *   holds the port, which fails this test loudly rather than passing it (measured:
 *   a listening thief seeded before the dial fails in ~10s on `connect`'s own
 *   "could not connect to ws://…"). So a countdown never manufactures the
 *   stimulus for a green run.
 * - **Fails rather than hangs.** `awaitReachable`'s retry loop is unbounded by
 *   design, so a listener that never binds used to leave the dialer spinning
 *   until the repo's 5-minute JUnit timeout, which then reported the timeout and
 *   not the bind failure. The listener's bind is now the test's own (a
 *   `BindException` thrown in place, rather than java-websocket's 10-second
 *   `onError`-to-stderr path), it is handed to the dialer's future, and the main
 *   thread's wait is bounded.
 *
 * ## Why a failure here reports more than the port (computenet-dqy.35)
 *
 * This test failed once in 190 back-to-back `:wire:test` runs with
 * `connect() to ws://localhost:56212 never reached a listener` — in 23ms, with
 * no client-side error on stderr — and that message named the port and nothing
 * else, so the occurrence could not be diagnosed from the report. It cost a
 * whole investigation to establish from the JUnit XML alone what the run had
 * *not* been. [Evidence] is the answer to that: every failure now carries the
 * listener's bound address, whether the listener had started, whether the
 * listening channel is still open, whether the dialled socket was closed at
 * all, and a timeline of the race, so the next occurrence is read rather than
 * re-run. What it deliberately does **not** do is wait longer, retry, or soften
 * anything the test asserts — the evidence is gathered after the assertion has
 * already failed.
 *
 * The one fact it cannot reach is named in [Evidence.HOW_TO_READ]: the close
 * *code* the client saw. `WsTransport.connect` throws its own
 * `IllegalStateException` and never lets the `WsConnection` out, so only
 * `connect` itself can report what `connectBlocking` saw.
 */
class WsConnectRaceTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `connect retries past a not-yet-bound listener instead of throwing`() {
        val client = Stack()
        val server = Stack()
        // chosen last: the port is unheld from here until the starter binds it
        val port = ServerSocket(0).use { it.localPort }
        // opened before the starter waits, so only the bind is on the clock — and
        // opened *here*, on the main thread, so a failure can still ask the channel
        // whether it is open (the dqy.34 discriminator, see [Evidence.HOW_TO_READ])
        val channel = ServerSocketChannel.open()
        val evidence = Evidence(port, channel)
        evidence.note("chose port $port and released it — nothing holds it from here")
        val probeFailed = CountDownLatch(1)
        val bound = CompletableFuture<WsTransport.WsListener>()
        val dialed = CompletableFuture<WsTransport.WsConnection>()

        val starter = start("connect-race-listener", { bound.completeExceptionally(it) }) {
            check(probeFailed.await(STIMULUS_SECONDS, TimeUnit.SECONDS)) {
                "connect() never reported a failed probe against the unbound port $port"
            }
            // deliberately no SO_REUSEADDR: this bind must fail loudly if another
            // process took the port, rather than succeed over a binding on a more
            // specific address and hand the dialer a stranger (computenet-8ru).
            // And loopback rather than the wildcard, the choice `listen(0, …)` and
            // `HeldPort` now make (computenet-dqy.28/.32): this is the address the
            // dialer below resolves `localhost` to, so a stranger's specific bind
            // can neither precede us silently nor overlap the port afterwards.
            try {
                channel.bind(WsTransport.loopback(port), BACKLOG)
            } catch (e: Exception) {
                channel.close()
                throw IllegalStateException("the test lost port $port between choosing it and binding it", e)
            }
            evidence.boundAt(channel.localAddress)
            val listener = WsTransport.listen(channel, server.side)
            evidence.startedAt(listener)
            bound.complete(listener)
        }
        val dialer = start("connect-race-dialer", { dialed.completeExceptionally(it) }) {
            // `connect` seats this callback in exactly two places, and the running
            // thread tells them apart without depending on a thread name:
            // `awaitReachable`'s catch block runs on *this* thread, while the
            // post-open reconnect loop runs on a thread `scheduleReconnect` spawns.
            // So the same callback reports both the stimulus and, on failure, the
            // fact that the dialled socket was closed rather than merely silent.
            val self = Thread.currentThread()
            // near-zero backoff: bounded by retry scheduling, not sleep. The
            // callback runs only after a probe has failed, which is the
            // "listener is not up yet" stimulus this test is about.
            dialed.complete(
                WsTransport.connect(URI("ws://localhost:$port"), client.side) {
                    if (Thread.currentThread() === self) {
                        evidence.probeRefused()
                        probeFailed.countDown()
                    } else {
                        evidence.dialledSocketClosed()
                    }
                    10L
                },
            )
        }
        // no listener means no reachable port ever, and `connect` would retry for
        // that forever: report the bind failure instead of timing out on its effect
        bound.whenComplete { _, failure ->
            failure?.let {
                evidence.note("the listener failed before it could serve: $it")
                dialed.completeExceptionally(it)
                dialer.interrupt()
            }
        }

        try {
            val connection = try {
                dialed.get(DIAL_SECONDS, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                evidence.note("the dial failed: ${e.cause}")
                throw AssertionError(
                    evidence.report("connect() to ws://localhost:$port never reached a listener"),
                    e.cause,
                )
            } catch (e: TimeoutException) {
                evidence.note("the dial had still not returned")
                throw AssertionError(
                    evidence.report("connect() to ws://localhost:$port had not returned after ${DIAL_SECONDS}s"),
                    e,
                )
            }
            try {
                connection.isOpen shouldBe true
            } finally {
                connection.shutdown()
            }
        } finally {
            // A failure must not leave residue for the rest of :wire's tests, which
            // share this JVM: an un-interrupted dialer would keep probing a dead port
            // every 10ms forever, and an unbound `starter` listener would keep a port.
            // The interrupt lands in `awaitReachable`'s `Thread.sleep(backoff)`.
            dialer.interrupt()
            starter.join(JOIN_MILLIS)
            runCatching { bound.getNow(null)?.stop(1000) }
            runCatching { channel.close() } // idempotent; covers a channel no listener ever took
            // A *failed* dial leaves a third piece of residue, and it is the loudest:
            // `connect` throws before returning the `WsConnection`, but that
            // connection's `onClose` has already armed a reconnect loop which redials
            // this port every 10ms forever, on a daemon thread nothing else holds a
            // reference to. Interrupt it by the name `scheduleReconnect` gives it —
            // it announces the interrupt on stderr and stops.
            Thread.getAllStackTraces().keys
                .filter { it.name == "ws-reconnect-ws://localhost:$port" }
                .forEach(Thread::interrupt)
        }
    }

    /** A daemon worker whose failure lands in a future instead of a dead thread's stack trace. */
    private fun start(name: String, fail: (Throwable) -> Unit, body: () -> Unit): Thread =
        Thread({ runCatching(body).onFailure(fail) }, name).apply { isDaemon = true; start() }

    /**
     * What this run can still say about itself once it has failed (computenet-dqy.35).
     *
     * Every fact here is one the 2026-08-12 occurrence needed and did not have.
     * They are recorded as they happen, from whichever thread reaches them, and
     * rendered once by [report]; nothing here is consulted on a green run.
     */
    private class Evidence(private val port: Int, private val channel: ServerSocketChannel) {

        private val origin = System.nanoTime()
        private val timeline = ConcurrentLinkedQueue<String>()
        private val refusals = AtomicInteger()

        /** Counted down by the reconnect loop the dialled socket's close arms. */
        private val closed = CountDownLatch(1)

        @Volatile
        private var boundAddress: SocketAddress? = null

        @Volatile
        private var listener: WsTransport.WsListener? = null

        fun note(what: String) {
            timeline += "%8.1fms  %s".format((System.nanoTime() - origin) / 1_000_000.0, what)
        }

        fun probeRefused() {
            // one line for the stimulus, a count for the rest: a slow bind can refuse
            // hundreds of probes and the timeline must stay readable
            if (refusals.incrementAndGet() == 1) note("probe refused — the port answers nothing (the stimulus)")
        }

        fun boundAt(address: SocketAddress?) {
            boundAddress = address
            note("bound $address with backlog $BACKLOG — the OS listens from here, java-websocket does not yet")
        }

        fun startedAt(started: WsTransport.WsListener) {
            listener = started
            note("listener started on ${started.address} — selector registered, onStart reached")
        }

        fun dialledSocketClosed() {
            if (closed.count > 0L) {
                note("the dialled socket closed — WsConnection.onClose fired and armed a reconnect")
                closed.countDown()
            }
        }

        fun report(headline: String): String {
            // Only ever reached once the assertion has already failed, so this waits
            // on evidence rather than on success: `connectBlocking` releases its latch
            // in `onWebsocketClose` *before* `onClose` runs, so the close that failed
            // the dial can still be in flight. It widens no timeout the test asserts on.
            val sawClose = closed.await(CLOSE_EVIDENCE_MILLIS, TimeUnit.MILLISECONDS)
            val serving = listener
            return buildString {
                appendLine(headline)
                appendLine(
                    "  listener:  " + (boundAddress?.let { "bound $it" } ?: "NEVER BOUND") + ", " +
                        (serving?.let { "started and serving ${it.address}" } ?: "NOT started") + ", " +
                        "listening channel " + (if (channel.isOpen) "still open" else "CLOSED"),
                )
                appendLine("  dialer:    ws://localhost:$port, and localhost resolves to ${resolutions()}")
                appendLine("  stimulus:  ${refusals.get()} refused probe(s) before the port answered")
                appendLine(
                    "  close:     " + if (sawClose) {
                        "the dialled socket WAS closed (onClose fired)"
                    } else {
                        "no close reached the dialer within ${CLOSE_EVIDENCE_MILLIS}ms"
                    },
                )
                appendLine("  timeline:")
                timeline.forEach { appendLine("    $it") }
                append(HOW_TO_READ)
            }
        }

        /**
         * Every address the name `localhost` carries here, in resolution order.
         *
         * The listener binds the *first* one (`WsTransport.loopback`), and so does
         * java-websocket's dial (`Socket.connect` on one resolved address), but
         * `awaitReachable`'s `Socket(host, port)` walks the whole list — so on a
         * dual-stack host a probe can succeed on an address the real handshake
         * never tries. One line here is enough to rule that family in or out.
         */
        private fun resolutions(): String =
            runCatching { InetAddress.getAllByName("localhost").joinToString { it.hostAddress } }
                .getOrElse { "unresolvable: $it" }

        private companion object {

            /**
             * How to turn the block above into a mechanism — written down because the
             * evidence is only worth what the next reader can do with it.
             *
             * The fact that does the work is the **listening channel's state**. Stderr
             * does not: java-websocket 1.6.0's client reaches `onClose` without
             * `onError` from every non-SSL `IOException` in its read loop as well as
             * from a clean EOF — `WebSocketClient.handleIOException` calls `onError`
             * only for an `SSLException` and otherwise goes straight to `eot()` — so a
             * *reset* is exactly as silent as a graceful close and carries the same
             * close code, **-1**. The only other silent client code is **1002**, from
             * `WebSocketImpl.decode`'s client-role handshake rejections.
             *
             * Read from the shipped bytecode and measured during this item's review
             * (2026-08-12). It corrects the claim this block used to carry — that a
             * reset always prints — which was an inference from an OS-level
             * measurement and is false through java-websocket.
             */
            const val HOW_TO_READ =
                "  how to read this:\n" +
                    "    * the listening channel's state is the one DIRECT discriminator here, so read it\n" +
                    "      first. CLOSED is computenet-dqy.34's family: java-websocket's doAccept prologue\n" +
                    "      (setTcpNoDelay on a reset victim, EINVAL on BSD) cancels the SERVER key and\n" +
                    "      closes the listening socket. Still open means the listening socket survived,\n" +
                    "      which that family does not.\n" +
                    "    * stderr does NOT discriminate: an absent '[WsConnection] …' line rules nothing\n" +
                    "      out. java-websocket 1.6.0's client swallows the reset a dying listening socket\n" +
                    "      sends — WebSocketClient.handleIOException calls onError only for an SSLException\n" +
                    "      and otherwise goes straight to eot(). Measured macOS 26.6, 8 trials each: a dial\n" +
                    "      left queued on a listening socket that then closes IS reset at the OS level (a\n" +
                    "      raw socket reads 'Connection reset' 8/8) and still reaches WsConnection with NO\n" +
                    "      onError 8/8, close code -1 — indistinguishable from a graceful EOF. Only a port\n" +
                    "      already dead before the dial connects prints, and it prints ConnectException:\n" +
                    "      Connection refused, which is a different failure entirely.\n" +
                    "    * so close code -1 means 'the socket ended before any handshake reply', NOT 'it\n" +
                    "      ended gracefully'. The client's whole silent set (read of the shipped 1.6.0\n" +
                    "      bytecode) is: eot() -> code -1, reached both from read() == -1 and from any\n" +
                    "      non-SSL IOException in the read loop; and WebSocketImpl.decode's client-role\n" +
                    "      handshake rejections -> 1002, i.e. an HTTP reply that was not an upgrade, or a\n" +
                    "      draft that refused one. (closeConnectionDueToWrongHandshake is the SERVER-role\n" +
                    "      path — it writes a 404 — and a client never reaches it.)\n" +
                    "    * the close CODE itself is not observable from here: WsTransport.connect throws its\n" +
                    "      own IllegalStateException and never lets the WsConnection out, so only connect()\n" +
                    "      can say what connectBlocking saw (computenet-dqy.35 — production change, not made).\n" +
                    "      The payoff is smaller than it looks: -1 does not separate an EOF from a reset.\n" +
                    "    * 'NEVER BOUND' or 'NOT started' is a snapshot taken when this report rendered, not\n" +
                    "      a verdict: a listener that started microseconds later still reads 'NOT started'\n" +
                    "      (observed in a forced run, 0.3ms behind). Check the timeline before concluding\n" +
                    "      the dialer is innocent; the cause is then the AssertionError's own cause."
        }
    }

    private companion object {
        /** How long the listener waits for the dialer to prove it met a closed port. */
        const val STIMULUS_SECONDS = 20L

        /** Bound on the whole scenario: the intended path takes one probe interval. */
        const val DIAL_SECONDS = 30L

        const val JOIN_MILLIS = 5_000L

        /**
         * Small but not 1: the dialer offers the listener a reachability probe and a
         * real handshake, and both can be queued before java-websocket accepts either.
         */
        const val BACKLOG = 16

        /**
         * How long a *failed* run waits for the close that failed it to be observable.
         * Post-assertion only — see [Evidence.report].
         */
        const val CLOSE_EVIDENCE_MILLIS = 500L
    }
}
