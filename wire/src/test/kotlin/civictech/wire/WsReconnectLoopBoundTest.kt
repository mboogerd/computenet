package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID

/**
 * computenet-8ru: a dialer whose peer is down retries with **one** loop, however
 * long the outage lasts.
 *
 * java-websocket reports a failed connect as a close — `WebSocketClient.run`
 * catches the `ConnectException` and drives `closeConnection` — so `onClose` fires
 * once per unsuccessful attempt, not only once per lost connection. A retry thread
 * started per close therefore grew without bound while a peer stayed down, and the
 * loops corrupted each other: concurrent `reconnectBlocking` calls race `reset()`
 * and `connect()` over the client's single `connectReadThread`, throwing
 * `IllegalStateException: WebSocketClient objects are not reuseable`, and one
 * loop's `reset()` can close a connection another loop has just established.
 *
 * Measured against the code before the guard, with the zero backoff these tests
 * inject: ~950 live `ws-reconnect-*` threads after 250ms of downtime, ~2700 after
 * 1s, and by 3s the JVM was starved badly enough that `WsTransport.listen`'s own
 * 10s start latch expired — the transport could not re-bind at all. That is how
 * `WsReconnectSmokeTest` and `WsPeerIdentityTest` were failing on CI's 2-core
 * runner, where the window between `listener.stop` and the re-bind is far longer
 * than on a developer box.
 *
 * Both halves are asserted, because either alone passes for a wrong reason: the
 * retry loop is bounded, **and** the dialer still recovers when the peer returns
 * (a transport that gave up retrying would trivially satisfy the bound).
 *
 * The 500ms downtime here is the stimulus, not a wait for a condition — the
 * assertion is about what the transport does *during* an outage, and any outage
 * long enough to admit several failed attempts exposes the defect. Every actual
 * wait below is bounded polling.
 */
class WsReconnectLoopBoundTest {

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

    /** As `WsReconnectSmokeTest.relisten` — re-binding a just-freed port races the OS. */
    private fun relisten(port: Int, side: Peering.Side, attempts: Int = 20): WsTransport.WsListener {
        var lastFailure: BindException? = null
        repeat(attempts) { attempt ->
            try {
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress(port))
                }
                return WsTransport.listen(port, side)
            } catch (e: BindException) {
                lastFailure = e
                if (attempt < attempts - 1) Thread.sleep(50)
            }
        }
        throw IllegalStateException("could not re-bind port $port after $attempts attempts", lastFailure)
    }

    /**
     * The retry loops for *this* dialer only: `WsConnection` names them after its
     * URI, so a connection left over from another test in this fork cannot inflate
     * the count.
     */
    private fun retryLoops(port: Int): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "ws-reconnect-ws://localhost:$port" }

    @Test
    fun `a dialer retries a down peer with one loop, not one per failed attempt`() {
        val client = Stack()
        var server = Stack()
        var collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        var listener = WsTransport.listen(0, server.side)
        val port = listener.port
        // zero backoff: the worst case for a per-close retry thread, and what the
        // two reconnect tests inject to keep reconnect timing scheduling-bound
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            listener.stop(1000)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }

            // hold the peer down long enough for many attempts to fail
            Thread.sleep(500)
            // one loop, plus room for a hand-over between a loop that is winding
            // down and its replacement; the defect produced three orders more
            retryLoops(port) shouldBeLessThanOrEqual 4

            server = Stack()
            collector = CollectingCell(collector.ref)
            server.host.managementInlet.call.spawn(collector)
            listener = relisten(port, server.side)

            // the bound is not "gave up": the surviving loop still reconnects
            await("re-announced after the peer returned") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
