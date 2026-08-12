package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 *   bind are both rejected, measured), but on macOS such a socket **drops** the
 *   SYN instead of resetting it: a connect attempt hangs to its timeout rather
 *   than being refused. `awaitReachable` dials with an untimed `Socket(host,
 *   port)`, so that trades a lost-port race for a guaranteed multi-minute stall.
 *   Worse, in a mixed `SO_REUSEPORT` group (silent holder + real listener) the
 *   kernel routed the SYN to the silent socket, so the handover `HeldPort.serve`
 *   performs does not even work in this shape.
 *
 * So the port is genuinely free for an instant, and the two remaining defences
 * are: make that instant as short as it can be, and make losing it *fail* rather
 * than hang.
 *
 * - **Shortest instant.** Both stacks are built before the port is chosen, and
 *   the listener's channel is created before its bind, so nothing but a thread
 *   hand-off sits between choosing the port and taking it back. The 300ms sleep
 *   this test used to wait out is gone: the listener binds the moment the dialer
 *   *reports* a failed probe (the `backoff` callback fires only after one), which
 *   also upgrades the stimulus from assumed to observed — a sleep that lost its
 *   ordering used to leave the test passing while proving nothing.
 * - **Fails rather than hangs.** `awaitReachable`'s retry loop is unbounded by
 *   design, so a listener that never binds used to leave the dialer spinning
 *   until the repo's 5-minute JUnit timeout, which then reported the timeout and
 *   not the bind failure. The listener's bind is now the test's own (a
 *   `BindException` thrown in place, rather than java-websocket's 10-second
 *   `onError`-to-stderr path), it is handed to the dialer's future, and the main
 *   thread's wait is bounded.
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
        val probeFailed = CountDownLatch(1)
        val bound = CompletableFuture<WsTransport.WsListener>()
        val dialed = CompletableFuture<WsTransport.WsConnection>()

        val starter = start("connect-race-listener", { bound.completeExceptionally(it) }) {
            val channel = ServerSocketChannel.open() // opened before the wait: only the bind is on the clock
            check(probeFailed.await(STIMULUS_SECONDS, TimeUnit.SECONDS)) {
                "connect() never reported a failed probe against the unbound port $port"
            }
            // deliberately no SO_REUSEADDR: this bind must fail loudly if another
            // process took the port, rather than succeed over a binding on a more
            // specific address and hand the dialer a stranger (computenet-8ru)
            try {
                channel.bind(InetSocketAddress(port), 16)
            } catch (e: Exception) {
                channel.close()
                throw IllegalStateException("the test lost port $port between choosing it and binding it", e)
            }
            bound.complete(WsTransport.listen(channel, server.side))
        }
        val dialer = start("connect-race-dialer", { dialed.completeExceptionally(it) }) {
            // near-zero backoff: bounded by retry scheduling, not sleep. The
            // callback runs only after a probe has failed, which is the
            // "listener is not up yet" stimulus this test is about.
            dialed.complete(
                WsTransport.connect(URI("ws://localhost:$port"), client.side) {
                    probeFailed.countDown()
                    10L
                },
            )
        }
        // no listener means no reachable port ever, and `connect` would retry for
        // that forever: report the bind failure instead of timing out on its effect
        bound.whenComplete { _, failure ->
            failure?.let {
                dialed.completeExceptionally(it)
                dialer.interrupt()
            }
        }

        val connection = try {
            dialed.get(DIAL_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw AssertionError("connect() to ws://localhost:$port never reached a listener", e.cause)
        } catch (e: TimeoutException) {
            throw AssertionError("connect() to ws://localhost:$port had not returned after ${DIAL_SECONDS}s", e)
        }
        try {
            connection.isOpen shouldBe true
        } finally {
            connection.shutdown()
            starter.join(JOIN_MILLIS)
            runCatching { bound.getNow(null)?.stop(1000) }
        }
    }

    /** A daemon worker whose failure lands in a future instead of a dead thread's stack trace. */
    private fun start(name: String, fail: (Throwable) -> Unit, body: () -> Unit): Thread =
        Thread({ runCatching(body).onFailure(fail) }, name).apply { isDaemon = true; start() }

    private companion object {
        /** How long the listener waits for the dialer to prove it met a closed port. */
        const val STIMULUS_SECONDS = 20L

        /** Bound on the whole scenario: the intended path takes one probe interval. */
        const val DIAL_SECONDS = 30L

        const val JOIN_MILLIS = 5_000L
    }
}
