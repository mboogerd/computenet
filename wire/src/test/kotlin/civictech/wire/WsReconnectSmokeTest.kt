package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.Collections
import java.util.UUID
import civictech.cell.data.delta.SetDelta

/**
 * M10.3 smoke: a client survives its listener dying and coming back on the
 * same port — the reconnect loop re-runs the hello, announcements re-mirror
 * both ways, parked sends replay, and traffic flows again. Correctness under
 * chaos stays with the seeded loopback harnesses; this proves the socket
 * glue only.
 */
class WsReconnectSmokeTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals: MutableList<SetDelta<String>> = Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    arrivals += value
                }
            })
        }
    }

    private fun membership(deltas: List<SetDelta<String>>): Set<String> {
        val live = mutableMapOf<String, MutableSet<Timestamp>>()
        deltas.forEach { delta ->
            delta.adds.forEach { (e, tags) -> live.getOrPut(e) { mutableSetOf() } += tags }
            delta.dels.forEach { (e, tags) ->
                live[e]?.let { it -= tags; if (it.isEmpty()) live.remove(e) }
            }
        }
        return live.keys
    }

    // T12 finding 4: the reconnect loop's own backoff (WsTransport.DEFAULT_RECONNECT_BACKOFF)
    // used to be the only thing standing between this test and a real wall-clock wait, so
    // every await here carried a 20s deadline. Driving the connection's backoff to zero (see
    // `connect` below) means reconnect is bounded only by scheduling, not sleep.
    //
    // That last step — 20s down to 2s — is what made this test flaky in CI, and the deadline
    // is back up to the 30s `testkit`'s canonical `awaitUntil` uses. "Bounded only by
    // scheduling" is not the same as "bounded by a small number": on CI's 2-core runner,
    // under a full `./gradlew build check`, scheduling is precisely the thing that gets slow.
    // This test timed out here as `parked send replayed via reconnect` on PRs #13 and #15,
    // neither of which touched `wire/` (computenet-8ru).
    //
    // Nothing about the T12 improvement is undone: the zero backoff stays, so a healthy run
    // still returns the moment the condition holds. A deadline is only ever reached when the
    // test is failing, so a generous one costs nothing and buys the runner room to breathe.
    //
    // The poll interval moves 20ms -> 100ms for the same reason, also matching `awaitUntil`.
    // Spinning every 20ms on a saturated 2-core box competes for the very CPU the reconnect
    // needs to make progress, so tight polling here makes the condition it waits for slower
    // to come true.
    //
    // computenet-8ru: the generous deadline was not enough, and the diagnosis above was
    // incomplete. The dialer was not merely slow to reconnect — it was starving itself.
    // `WsConnection.onClose` started a retry thread per close, and java-websocket reports
    // every *failed* connect attempt as a close, so retry loops multiplied without bound
    // for as long as the listener stayed down (~950 threads after 250ms, ~2700 after 1s;
    // by 3s `WsTransport.listen` could no longer win its own 10s start latch). The loops
    // also raced each other's `reset()`/`connect()` on the one client. The transport now
    // runs a single retry loop; `WsReconnectLoopBoundTest` is the regression gate, and
    // these deadlines are left generous because a healthy run never approaches them.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(100)
        }
    }

    /**
     * T12 finding 4: re-binding the exact port `listener.stop()` just freed races the OS
     * (TIME_WAIT, or a concurrent process on this host grabbing it first — a documented
     * reality in this environment) — a real flake source, not a hypothetical one. Probing
     * with a throwaway `SO_REUSEADDR` [ServerSocket] bind+close first nudges the OS past a
     * lingering TIME_WAIT; a bounded retry on [BindException] around the real listen absorbs
     * whatever race remains, with a clear message if every attempt fails.
     */
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

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `a client reconnects after the listener restarts and parked sends replay`() {
        val client = Stack()
        var server = Stack()
        var listener = WsTransport.listen(0, server.side)
        val port = listener.port
        // zero reconnect backoff (T12 finding 4): reconnect timing is then bounded by
        // scheduling, not sleep, so the awaits above can drop their 20s deadlines.
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            var collector = CollectorCell()
            server.host.managementInlet.call.spawn(collector)
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))
            val api = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            api.add("milk")
            await("pre-restart convergence") { membership(collector.arrivals.toList()) == setOf("milk") }

            // the server process "dies": listener gone, refs unpublished, sends park
            listener.stop(1000)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }
            api.add("cheese") // accepted while down: parks at the client

            // the server comes back on the SAME port with a rebuilt graph
            // (same collector ref — the restart-recovery shape)
            server = Stack()
            collector = CollectorCell(collector.ref)
            server.host.managementInlet.call.spawn(collector)
            listener = relisten(port, server.side)

            // no manual reconnect: the client's backoff loop finds the new
            // listener, re-hellos, announcements re-mirror, parked sends replay.
            // (Only the parked delta arrives — recovering "milk" is the
            // journal/replication story, kernel CrashRecoveryTest territory.)
            await("parked send replayed via reconnect") {
                membership(collector.arrivals.toList()).contains("cheese")
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
