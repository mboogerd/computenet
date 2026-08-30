package civictech.iroh

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The M5.5 socket-test shape over iroh (feature `computenet-egl.2`, example 1):
 * two full stacks in one JVM, two real sidecar child processes, one real QUIC
 * link — a ref published on the listening side is invoked from the dialling side
 * and the effect comes back.
 *
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar) every
 * test here reports SKIPPED, never failed — see [SidecarBinary].
 */
class IrohPeeringTest {

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

    private class Stack(name: String? = null, allow: Set<PeerId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    /** Nothing ever became true within [millis] — used to pin an absence. */
    private fun neverWithin(millis: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return false
            Thread.sleep(50)
        }
        return !condition()
    }

    @Test
    fun `a ref published on the listening side is invoked from the dialling side and the effect comes back`() {
        val binary = SidecarBinary.orSkip()
        val a = Stack(name = "alice")
        val b = Stack(name = "bob")

        IrohTransport.listen(a.side, binary).use { listener ->
            assertTrue(listener.addresses.isNotEmpty(), "LISTENING carried no addresses")
            IrohTransport.connect(b.side, listener.nodeId, listener.addresses, binary).use { connection ->
                // ---- A publishes the @Contract cell the invocation targets ----
                val writer = SetCell<String>()
                a.host.managementInlet.call.spawn(writer)

                // ---- B publishes the collector the effect comes back to -------
                val collector = CollectorCell()
                b.host.managementInlet.call.spawn(collector)

                // Announcements travel in BOTH directions once the two hellos
                // have crossed: each side learns the other's published refs.
                await("A's writer announced to B") {
                    b.registry.location(writer.ref) is LocationRegistry.Remote
                }
                await("B's collector announced to A") {
                    a.registry.location(collector.ref) is LocationRegistry.Remote
                }
                assertTrue(connection.peered, "the dialling side admitted A's hello")

                // A subscribes its writer's outlet to B's collector, through the
                // proxy the announcement installed — so the effect of anything
                // written on A is observable on B.
                val remoteCollector = (
                    HostedCellProxy.create(collector.ref, a.registry, DeltaInletProxy::class.java)
                        as DeltaInletProxy
                    ).inlet.call
                writer.outlet.subscribe(Use.fixed(remoteCollector, PortRef.generate()))

                // ---- B invokes a port on ITS proxy of A's writer --------------
                val remoteWriter = (
                    HostedCellProxy.create(writer.ref, b.registry, SetInletProxy::class.java)
                        as SetInletProxy
                    ).inlet.call
                listOf("milk", "eggs", "beans").forEach(remoteWriter::add)
                remoteWriter.remove("eggs")

                // ---- the invocation was delivered on A, and B can see it ------
                await("the effect of B's invocation is observable on B") {
                    membership(collector.arrivals.toList()) == setOf("milk", "beans")
                }
                assertEquals(setOf("milk", "beans"), writer.membership(), "the invocation landed on A's own cell")
                assertEquals(0L, listener.preHelloDrops, "no frame arrived before an admitted hello")
                assertEquals(0L, connection.preHelloDrops)
                assertEquals(0L, listener.admissionDenialCount)
                assertTrue(listener.linkErrors.isEmpty(), "sidecar reported link errors: ${listener.linkErrors}")
                assertTrue(connection.linkErrors.isEmpty(), "sidecar reported link errors: ${connection.linkErrors}")
            }
        }
    }

    @Test
    fun `a peer off the listening side's allowlist is refused and accounted, an admitted one peers`() {
        val binary = SidecarBinary.orSkip()
        val server = Stack(name = "server", allow = setOf(PeerId("good")))

        IrohTransport.listen(server.side, binary).use { listener ->
            val published = SetCell<String>()
            server.host.managementInlet.call.spawn(published)

            // ---- mallory: not on the allowlist ---------------------------
            val mallory = Stack(name = "mallory")
            val malloryPublished = SetCell<String>()
            mallory.host.managementInlet.call.spawn(malloryPublished)

            // connect() returns normally — a denial is not a fault (BS-14).
            IrohTransport.connect(mallory.side, listener.nodeId, listener.addresses, binary).use { refused ->
                await("the refused hello is accounted on the listener's admission sink") {
                    listener.admissionDenialCount >= 1L
                }
                assertFalse(refused.peered, "a refused peer installs no ingress on its own side either")
                assertTrue(
                    neverWithin { mallory.registry.location(published.ref) is LocationRegistry.Remote },
                    "a refused peering must apply no announcement",
                )
                assertTrue(
                    server.registry.location(malloryPublished.ref) == null,
                    "the refusing side installed no ingress, so nothing of mallory's could arrive",
                )
            }

            // A refused dialler is not told it was refused — it sees a plain
            // LINK_DOWN — so since computenet-egl.2.3 it re-dials on its backoff
            // and is refused again, exactly as a refused `:wire` client
            // reconnects forever. The listener's denial count therefore grows
            // while mallory is up, and only its DELTA across the admitted
            // peering below is stable. (mallory's connection is closed by the
            // `use` above, which stops its loop.)
            val afterMallory = listener.admissionDenialCount

            // ---- good: on the allowlist, same listener --------------------
            val good = Stack(name = "good")
            IrohTransport.connect(good.side, listener.nodeId, listener.addresses, binary).use { admitted ->
                await("the admitted peer learns the listening side's published ref") {
                    good.registry.location(published.ref) is LocationRegistry.Remote
                }
                assertTrue(admitted.peered)
                assertEquals(afterMallory, listener.admissionDenialCount, "admitting one peer adds no denial")
            }
        }
    }
}
