package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.durability.InMemoryJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.TopoEvent
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * computenet-f7y8: `AgoraService.createEdge` published the edge into `nodes`
 * (so `/graph` could already serve it) before `manage.spawn(edge)`, which
 * blocks and can throw — a quota refusal, or any other spawn failure
 * surfaced through the future. computenet-u7by established that
 * `AgoraApp.stop()` cannot land inside this span (the HTTP dispatcher runs
 * handlers inline and `stop()` joins it), so `AgoraStopWindowTest` cannot
 * pin this one: the trigger here is the spawn call itself throwing, not an
 * external interrupt.
 *
 * INSTRUMENT: `ManagedHost`'s public `quota` constructor parameter (G-28,
 * M8.1 — see `kernel/src/test/kotlin/civictech/cell/host/HierarchyTest.kt`'s
 * `subtree quota rejects spawns anywhere below the budgeted host`). No
 * kernel/gen change or subclass is needed or possible: `manage.spawn`'s only
 * overridable seam is the anonymous `internalApi.spawn` built inside
 * `ManagedHost`'s own `init` block, which is not `open` and not reachable
 * from outside the class. `quota` is the one caller-visible lever on that
 * path, and it throws `IllegalStateException` synchronously out of
 * `manage.spawn(edge)` — genuinely, through `enqueueAwaiting`'s
 * `scheduler.await`, not merely simulated.
 *
 * The service is spawned with a budget that exactly covers its own hub cell
 * plus the two claims this test creates, so the edge's `manage.spawn` is the
 * first call that exceeds it.
 */
class CreateEdgeSpawnFailureTest {

    @Test
    fun `a spawn that throws leaves the edge served by graph and still recorded as topology`() {
        val registry = LocationRegistry()
        val scheduler = VirtualThreadScheduler("create-edge-spawn-failure-test")
        val topology = InMemoryJournal()
        // Budget: 1 (hub, spawned in AgoraService's init) + 2 (the claims
        // below) = 3. The edge's manage.spawn is the 4th call and exceeds it.
        val host = ManagedHost(scheduler = scheduler, registry = registry, quota = 3)
        val context = ApplyContext(host, topology = topology)
        val service = AgoraService(host, registry, context = context)
        try {
            val a = service.createClaim("a")
            val b = service.createClaim("b")
            val edgeRef = CellRef(UUID.randomUUID())

            val failure = assertFailsWith<IllegalStateException> {
                service.createEdge(a, b, Polarity.SUPPORT, edgeRef)
            }
            assertTrue(
                failure.message?.contains("quota exceeded") == true,
                "expected a quota-exceeded failure, got: ${failure.message}",
            )

            // The bug this pins: publication into `nodes` happens before the
            // spawn that just threw, so /graph already serves this ref.
            val info = service.nodeInfo(edgeRef)
            assertEquals(AgoraService.Kind.EDGE, info?.kind, "edge should be published into nodes despite the failed spawn")
            assertTrue(
                service.graph().any { it.ref == edgeRef },
                "graph() should already serve the edge the failed spawn left published",
            )

            // One complete GraphSpec delta is write-ahead of the throwing
            // spawn, so recovery can finish all three links next boot.
            val edgeDeltas = topology.replay()
                .map(JournalRecords::decode)
                .filterIsInstance<DecodedJournalRecord.Topology>()
                .filter { delta -> delta.events.any { it is TopoEvent.Spawn && it.ref == edgeRef } }
            assertEquals(1, edgeDeltas.size, "edge creation must write exactly one topology delta")
            assertEquals(4, edgeDeltas.single().events.size, "edge delta must contain spawn plus three links")
        } finally {
            scheduler.shutdown()
        }
    }
}
