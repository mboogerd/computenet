package civictech.agora

import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitDrained
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * computenet-2nwxu: pins `CredenceObservationSource.publishCurrent()` at the end
 * of `AgoraService.rebuildIndex()`.
 *
 * A checkpoint written before computenet-xt0z1 holds the `agora:hub` fold (the
 * old `ObserveCell(CredenceView)` snapshot) but no state for the canonical
 * observation group, which did not exist yet. Recovery restores the source's
 * fold; the group stays empty, and no replay frame follows a compacting
 * checkpoint to refill it. Without the republish, `graph()` reports every
 * claim at the 0.5 default.
 *
 * Fixture `legacy-credence-checkpoint/host.journal` was written by the
 * pre-migration code (commit ab39be77): claims A and B, edge B -ATTACK-> A,
 * stances B:m=0.95 and A:n=0.2, drained, then `host.checkpoint(journal)`. The
 * expected credences below are that run's own `graph()` output.
 */
class LegacyCredenceCheckpointTest {

    @Test
    fun `a pre-migration checkpoint recovers its credences into the canonical observation`(@TempDir dir: File) {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/legacy-credence-checkpoint/host.journal")) {
            "missing legacy journal fixture"
        }
        val journalFile = File(dir, "host.journal")
        fixture.use { input -> journalFile.outputStream().use { input.copyTo(it) } }

        val registry = LocationRegistry()
        val scheduler = VirtualThreadScheduler("agora-legacy-checkpoint")
        val journal = FileJournal(journalFile)
        val host = ManagedHost(
            scheduler = scheduler,
            registry = registry,
            attention = civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
            journal = journal,
        )
        val context = ApplyContext(host, journals = mapOf("host" to journal), topology = journal)
        val service = AgoraService(host, registry, context = context)
        try {
            // AgoraApp's recovery sequence, in its order.
            context.recover(journal).awaitApplied(60_000)
            if (service.repairTornRemovals()) host.quiescence().await(60_000, "legacy torn-removal repair")
            host.checkpoint(journal)
            service.rebuildIndex()
            scheduler.awaitDrained("legacy recovery settles")

            val recovered = service.graph().associate { it.ref.id to it.credence }
            assertEquals(setOf(CLAIM_A, CLAIM_B, EDGE_BA), recovered.keys, "recovered topology differs")
            mapOf(CLAIM_A to 0.105, CLAIM_B to 0.95).forEach { (ref, expected) ->
                val actual = recovered.getValue(ref)
                assertTrue(
                    abs(actual - expected) <= 1e-9,
                    "claim $ref: pre-migration checkpoint held $expected, recovered graph() reports $actual",
                )
            }
        } finally {
            scheduler.shutdown()
        }
    }

    private companion object {
        val CLAIM_A: UUID = UUID.fromString("6e7eb65f-7bc3-4063-9ea5-3e8676002c94")
        val CLAIM_B: UUID = UUID.fromString("e94a966d-b9e1-41dc-9ddd-ff5cde7cf463")
        val EDGE_BA: UUID = UUID.fromString("517239c2-ab90-4c27-93f8-fb9d1fb03805")
    }
}
