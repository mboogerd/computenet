package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.PnCounterCell
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * computenet-s5p4d, carried forward from feature computenet-9sm.9's acceptance
 * clause [KE3-06]/[KE3-07]/[KE3-10]: "every replicated logical id [has] exactly
 * one companion [WatermarkCell]". `DeliveredWatermarkTest` asserts watermark
 * VALUES (`watermarkOf(logicalId)!!.watermark(src)`) and never asserts
 * uniqueness or self-tracking, so neither half of the clause had an executable
 * pin. This test supplies both, directly against the public seam
 * (`Replication.watermarkOf`, `Replication.replicate`) rather than against the
 * private `watermarks` map or `watermarkRef` derivation.
 *
 * What actually holds the property (`Replication.kt`, verified against this
 * branch's base):
 *  - `watermarks` is a `mutableMapOf<UUID, WatermarkCell>()` keyed by logical
 *    id — uniqueness is structural, from the key type, not from any check.
 *  - `trackDeliveries(cell, host, ...)` opens with `if (cell is WatermarkCell)
 *    return` (KDoc: "A [WatermarkCell] is not itself tracked — that would
 *    recurse") — a WatermarkCell handed to `replicate` never reaches the
 *    `watermarks.getOrPut` that would spin up a companion for ITS OWN logical
 *    id.
 */
class WatermarkCompanionUniquenessTest {

    private fun freshReplication(): Pair<Replication, ManagedHost> {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        return Replication(registry) to host
    }

    @Test
    fun `a logical id replicated twice yields one and the same companion WatermarkCell`() {
        val (replication, host) = freshReplication()
        val logicalId = UUID.randomUUID()

        val first = PnCounterCell(CellRef(logicalId, 0))
        replication.replicate(first, host)
        val companionAfterFirst = replication.watermarkOf(logicalId)
        companionAfterFirst.shouldNotBeNull()

        val second = PnCounterCell(CellRef(logicalId, 1))
        replication.replicate(second, host)
        val companionAfterSecond = replication.watermarkOf(logicalId)
        companionAfterSecond.shouldNotBeNull()

        // Identity, not equality: a second replica of the SAME logical id must
        // find the SAME companion object `watermarkOf` handed back after the
        // first — not an equal-looking second one.
        (companionAfterSecond === companionAfterFirst) shouldBe true
    }

    @Test
    fun `a WatermarkCell handed to replicate is not itself tracked - no companion is minted for its own logical id`() {
        val (replication, host) = freshReplication()

        // Shaped exactly like an ordinary companion ref (a fresh CellRef the
        // real watermarkRef() derivation would also produce for some data
        // ref), but never produced through the ordinary replicate(dataCell,
        // host) path — so nothing before this call has touched `watermarks`
        // for this ref's logical id.
        val standaloneRef = CellRef(UUID.randomUUID(), 0)
        val standaloneWatermark = WatermarkCell(standaloneRef)

        replication.replicate(standaloneWatermark, host)

        // "Not itself tracked": handing a WatermarkCell to replicate must not
        // recurse into minting a companion FOR the WatermarkCell's own
        // logical id.
        replication.watermarkOf(standaloneRef.id).shouldBeNull()
    }
}
