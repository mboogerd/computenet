package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * T06 §E: the WS-read-thread -> `ManagedHost.enqueueHostedInvocation` path
 * (the traced production path T04's findings hang off) under real
 * concurrency — a peer floods frames over the socket while the receiving
 * host is *simultaneously* driven by a separate, purely-local thread. Both
 * streams must converge fully; keeps the existing smoke-test style
 * (generous deadlines, a conformance check rather than a stress benchmark).
 */
class WsThreadEntryConformanceTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<civictech.cell.data.delta.SetDelta<String>>>
    }

    interface CounterApi {
        fun increment(n: Int)
    }

    private class CounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val total = AtomicInteger()
        val inlet = registerPort("inlet", FanInlet.create<CounterApi>())
        init {
            inlet.serve(object : CounterApi {
                override fun increment(n: Int) { total.addAndGet(n) }
            })
        }
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
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
    fun `a peer flooding frames converges alongside concurrent local traffic on the receiving host`() {
        val server = Stack()
        val client = Stack()
        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            // wire-delivered stream: client writes to a SetCell replica-style
            // target hosted on the server, reached only through the socket
            val remoteCollector = object : Cell {
                override val ref = CellRef(UUID.randomUUID())
                val received = Collections.synchronizedList(mutableListOf<String>())
                val inlet = registerPort("inlet", FanInlet.create<Propagate<civictech.cell.data.delta.SetDelta<String>>>())
                init {
                    inlet.serve(object : Propagate<civictech.cell.data.delta.SetDelta<String>> {
                        override fun propagate(value: civictech.cell.data.delta.SetDelta<String>) {
                            value.adds.keys.forEach { received += it }
                        }
                    })
                }
            }
            server.host.managementInlet.call.spawn(remoteCollector)
            await("collector announced to client") {
                client.registry.location(remoteCollector.ref) is LocationRegistry.Remote
            }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(remoteCollector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))
            val writerApi = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call

            // local stream: a SEPARATE cell on the SAME server host, driven
            // directly (never touching the socket) — concurrently with the
            // flood, so enqueueHostedInvocation genuinely sees both a WS
            // read thread AND a plain local thread at once.
            val localCounter = CounterCell()
            server.host.managementInlet.call.spawn(localCounter)

            val floodSize = 400
            val localSize = 400
            val floodElements = (0 until floodSize).map { "e$it" }

            val floodThread = Thread {
                floodElements.forEach { writerApi.add(it) }
            }.apply { name = "t06-e-flood" }
            val localThread = Thread {
                repeat(localSize) { server.host.enqueueHostedInvocation(incrementInvocation(localCounter.ref)) }
            }.apply { name = "t06-e-local" }

            floodThread.start()
            localThread.start()
            floodThread.join(30_000)
            localThread.join(30_000)

            await("wire-flooded elements converge on the server") {
                synchronized(remoteCollector.received) { remoteCollector.received.toSet() } == floodElements.toSet()
            }
            await("concurrently-driven local traffic is not lost") {
                localCounter.total.get() == localSize
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    private fun incrementInvocation(cellRef: CellRef) = civictech.cell.proxy.HostedPortInvocation(
        cellRef, "inlet", civictech.cell.proxy.HostedPortInvocation.Type.PORT_API,
        civictech.cell.proxy.Invocation("increment", listOf("int"), listOf(1)),
    )
}
