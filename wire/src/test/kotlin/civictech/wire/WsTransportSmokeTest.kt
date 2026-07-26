package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.Collections
import java.util.UUID

/**
 * M5.5 smoke: real sockets, real threads — two full stacks in one JVM over
 * localhost WebSocket. Correctness under scheduling chaos is the loopback
 * harness's job (M5.3/M5.4, seeded); this only proves the socket glue:
 * convergence end-to-end and park-on-disconnect.
 */
class WsTransportSmokeTest {

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

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(20)
        }
    }

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `two JVM-shaped stacks converge over localhost websocket and park on disconnect`() {
        val server = Stack()
        val client = Stack()
        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            // collector lives on the server stack, writer on the client stack
            val collector = CollectorCell()
            server.host.managementInlet.call.spawn(collector)
            await("collector announced to client") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))

            val api = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            listOf("milk", "eggs", "beans").forEach(api::add)
            api.remove("eggs")

            await("membership convergence across the socket") {
                membership(collector.arrivals.toList()) == setOf("milk", "beans")
            }

            // kill the server side: client senders must park, not crash or drop
            listener.stop(1000)
            await("remote refs unpublished on disconnect") {
                client.registry.location(collector.ref) == null
            }
            api.add("cheese")
            await("post-disconnect send parked") {
                client.registry.parkedFor(collector.ref).isNotEmpty()
            }
            client.registry.parkedFor(collector.ref).shouldNotBeEmpty()
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
