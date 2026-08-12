package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.UUID

/**
 * computenet-dqy.27: the retry-loop bound ([WsReconnectLoopBoundTest]) and the
 * reconnect smoke tests ([WsReconnectSmokeTest]) both drive their outage through
 * [HeldPort], which — deliberately, per its own KDoc — resets a dialer's failed
 * attempt (`ECONNRESET`) rather than refusing it (`ECONNREFUSED`). That is the
 * right fixture for the shape those tests need (the peer's listener comes back
 * on the SAME port), but it means no `:wire` test drives a *reconnect* attempt
 * into a port nothing is bound to at all. [WsConnectRaceTest] covers
 * `ECONNREFUSED` only on the INITIAL connect, before any socket has ever opened.
 *
 * This test closes that gap without reintroducing the close-and-rebind race
 * `HeldPort` exists to avoid (computenet-dqy.22, computenet-dqy.25,
 * computenet-dqy.26): the listener here binds an ephemeral port once, is used
 * for a real connection, and is then stopped **for good** — the outage is
 * permanent for that endpoint. Nothing afterwards asks the OS for that port
 * number again, so there is no window in which this test races another process
 * (in this JVM's own parallel test fork, or elsewhere on the runner) for it.
 * That is different from the old pre-dqy.22 fixture: that one also freed a port
 * and never rebuilt on it, but only after first winning a 20-attempt scramble to
 * get the exact same number back — the thing `HeldPort`'s KDoc documents as
 * racy. Here the port is simply abandoned; this test does not care what happens
 * to it afterwards because it never looks at it again.
 *
 * Consequently this test only asserts the retry-loop bound, never recovery: a
 * closed listener that is never coming back has nothing to reconnect *to*. What
 * stands in for "the dialer still works" here is the lower half of the bound
 * below — at least one loop is still alive, i.e. the dialer has not given up —
 * since a transport that quietly stopped retrying would otherwise satisfy an
 * upper bound alone for the wrong reason (the same argument
 * [WsReconnectLoopBoundTest] makes for asserting its recovery half).
 */
class WsReconnectRefusedTest {

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(100)
        }
    }

    /**
     * The retry loops for *this* dialer only, same shape as
     * [WsReconnectLoopBoundTest.retryLoops] — `WsConnection` names its retry
     * thread after its URI, so a connection left over from another test in this
     * fork cannot inflate the count.
     */
    private fun retryLoops(port: Int): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "ws-reconnect-ws://localhost:$port" }

    @Test
    fun `a dialer retries a permanently refused peer with one loop, not one per failed attempt`() {
        val client = Stack()
        val server = Stack()
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        // one genuine bind on an ephemeral port, used for a real connection —
        // never re-bound, and never asked for by number again
        val listener = WsTransport.listen(0, server.side)
        val port = listener.port
        // zero backoff: the worst case for a per-close retry thread, matching
        // the HeldPort-based reconnect tests
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            // the peer's listener is stopped for good: no guard, no re-serve,
            // no further bind on this port from this test ever again. Once this
            // returns, the port is genuinely unbound and every subsequent connect
            // attempt gets ECONNREFUSED from the kernel rather than ECONNRESET
            // from a HeldPort guard.
            listener.stop(1000)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }

            // hold the outage long enough for many attempts to fail against the
            // now-genuinely-unbound port
            Thread.sleep(500)

            // one loop, plus room for a hand-over between a loop that is winding
            // down and its replacement (same bound as WsReconnectLoopBoundTest);
            // and at least one loop still alive — this dialer has not given up,
            // even though (unlike the HeldPort tests) nothing will ever answer
            // this port again for it to recover onto
            val loops = retryLoops(port)
            loops shouldBeLessThanOrEqual 4
            loops shouldBeGreaterThanOrEqual 1
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
