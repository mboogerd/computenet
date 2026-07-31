package civictech.cell.data

import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-CELLS: `WatermarkCell`'s bounded read — **four independent lattices, one
 * walk**, and the concrete answer to the design note's open question about
 * ordering *across* sub-states as well as within them.
 *
 * The cursor names `(lane, slot, source?)`: a cursor that did not name its lane
 * could not resume across a lane boundary at all. Lanes are walked in
 * `snapshot()`'s own order — `rows`, `closed`, `suspended`, `members` — and
 * within a lane by the natural order of the `UUID` keys, which is what makes a
 * restored instance walk identically to the one that checkpointed it.
 *
 * This cell also sits inside the replication mesh, so the second half of this
 * class is neutrality: a walk must advance no lattice row and fire no delivery
 * tap.
 */
class WatermarkCellBoundedReadTest {

    private val sourceA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val sourceB = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    private fun replica(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    /** A watermark holding entries in all four lanes, seeded through the gossip inlet. */
    private fun populated(replicas: Int = 6): WatermarkCell {
        val cell = WatermarkCell()
        cell.deltaInlet.call.propagate(
            WatermarkDelta(
                rows = (1..replicas).associate { replica(it) to mapOf(sourceA to it.toLong(), sourceB to it * 10L) },
                closed = setOf(replica(1), replica(2)),
                suspended = mapOf(replica(3) to 1L, replica(4) to 2L),
                members = (1..replicas).map { replica(it) }.toSet(),
            )
        )
        return cell
    }

    private fun drive(
        cell: WatermarkCell,
        limit: Int,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = limit, byteBudget = byteBudget))
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    private fun rowsOf(pages: List<StatePage>) =
        pages.flatMap { it.entries }.filterIsInstance<WatermarkCell.WatermarkRowEntry>()

    private fun slotsOf(pages: List<StatePage>, lane: WatermarkCell.WatermarkLane) =
        pages.flatMap { it.entries }.filterIsInstance<WatermarkCell.WatermarkSlotEntry>().filter { it.lane == lane }

    @Test
    fun `a walk unions to exactly the snapshot's four lanes, one entry per lattice cell`() {
        val cell = populated()

        val pages = drive(cell, limit = 5)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 6 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null }

        // rows: one entry per (replica, source) cell, never a whole replica row —
        // a row is itself unbounded in the number of sources
        val rows = rowsOf(pages)
        rows.associate { (it.replica to it.source) to it.thru } shouldBe
            cell.rows().flatMap { (r, cols) -> cols.map { (s, v) -> (r to s) to v } }.toMap()

        slotsOf(pages, WatermarkCell.WatermarkLane.CLOSED).map { it.slot }.toSet() shouldBe cell.closed()
        slotsOf(pages, WatermarkCell.WatermarkLane.SUSPENDED).associate { it.slot to it.epoch!! } shouldBe
            mapOf(replica(3) to 1L, replica(4) to 2L)
        slotsOf(pages, WatermarkCell.WatermarkLane.MEMBERS).map { it.slot }.toSet() shouldBe cell.members()

        // an untagged family: the lattice is not this fold's tag frontier
        pages.forEach { it.frontier.shouldBeNull() }
        pages.forEach { it.caveats.shouldBeEmpty() }
        cell.supportsSince.shouldBeFalse()
        cell.supportsScope.shouldBeFalse()
    }

    @Test
    fun `lanes are walked in snapshot order and sorted within, and the cursor resumes across a lane boundary`() {
        val cell = populated()

        val laneSequence = drive(cell, limit = 3).flatMap { page ->
            page.entries.map {
                when (it) {
                    is WatermarkCell.WatermarkRowEntry -> WatermarkCell.WatermarkLane.ROWS
                    is WatermarkCell.WatermarkSlotEntry -> it.lane
                    else -> error("unexpected entry $it")
                }
            }
        }

        // rows, then closed, then suspended, then members — never interleaved,
        // which is only possible because the cursor names its lane
        laneSequence.distinct() shouldContainExactly listOf(
            WatermarkCell.WatermarkLane.ROWS,
            WatermarkCell.WatermarkLane.CLOSED,
            WatermarkCell.WatermarkLane.SUSPENDED,
            WatermarkCell.WatermarkLane.MEMBERS,
        )
        // and within the rows lane, sorted by (replica, source)
        val rows = rowsOf(drive(cell, limit = 3))
        rows.map { it.replica to it.source } shouldContainExactly
            rows.map { it.replica to it.source }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    @Test
    fun `the walk order is stable across walks and across a snapshot-restore round trip`() {
        val cell = populated()

        val original = drive(cell, limit = 4).flatMap { it.entries }.map { it.toString() }
        drive(cell, limit = 4).flatMap { it.entries }.map { it.toString() } shouldContainExactly original

        // restore rebuilds all four lanes from HashMap/HashSet — sorting is what
        // makes the restored instance walk identically
        val restored = WatermarkCell()
        restored.restore(cell.snapshot())
        drive(restored, limit = 4).flatMap { it.entries }.map { it.toString() } shouldContainExactly original
    }

    @Test
    fun `a limit larger than the state produces a single terminal page`() {
        val cell = populated(2)

        val pages = drive(cell, limit = 500)

        pages.size shouldBe 1
        pages.single().next.shouldBeNull()
        pages.single().entries.isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `a lattice cell that vanished between pages is skipped rather than throwing`() {
        // rows never shrink under the lattice's own operations, so the honest way
        // to make a key disappear mid-walk is a restore — the same case that
        // reorders every lane wholesale.
        val cell = populated()
        val first = cell.readBounded(StateRead(limit = 4))
        first.next shouldNotBe null

        // shrink the state under the in-flight cursor
        cell.restore(populated(2).snapshot())

        var cursor = first.next
        var pages = 0
        while (cursor != null) {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = 4))
            cursor = page.next
            check(pages++ < 100) { "walk did not terminate after the state shrank" }
        }
        pages shouldBeGreaterThan 0
    }

    @Test
    fun `a mid-walk gossip merge smears the union but never duplicates or tears an entry`() {
        val cell = populated()

        val pages = drive(cell, limit = 4, between = { page ->
            if (page == 1) {
                cell.deltaInlet.call.propagate(
                    WatermarkDelta(rows = mapOf(replica(9) to mapOf(sourceA to 99L)), members = setOf(replica(9)))
                )
            }
        })

        val seen = pages.flatMap { it.entries }.map { it.toString() }
        seen.distinct().size shouldBe seen.size // never duplicated
        pages.last().next.shouldBeNull()
    }

    @Test
    fun `the byte budget shortens pages without stalling the walk`() {
        val cell = populated()

        val pages = drive(cell, limit = 500, byteBudget = 100)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty().shouldBeTrue() }
    }

    // ------------------------------------------------------------- neutrality

    @Test
    fun `a full walk advances no lattice row, fires no delivery tap, emits nothing and dead-letters nothing`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        // the real replication wiring: a data source, a watermark companion
        // tracking its deliveries, plus an independent counting tap on the same
        // outlet so "the tap fired zero times" is asserted directly
        val source = SetCell<String>()
        val watermark = populated()
        watermark.trackDeliveriesOf(source.outlet)
        val tapFired = AtomicInteger()
        source.outlet.tap(Use.fixed(Propagate<civictech.cell.data.delta.SetDelta<String>> { tapFired.incrementAndGet() }, PortRef.generate()))
        val emitted = AtomicInteger()
        watermark.outlet.subscribe(Use.fixed(Propagate<WatermarkDelta> { emitted.incrementAndGet() }, PortRef.generate()))

        host.managementInlet.call.spawn(watermark)
        controller.runToIdle()

        val rowsBefore = watermark.rows()
        val closedBefore = watermark.closed()
        val suspendedBefore = watermark.suspended()
        val membersBefore = watermark.members()
        val deadLettersBefore = host.supervisionAccounting().deadLetters
        val emittedBefore = emitted.get()
        tapFired.set(0)

        var cursor: Cursor? = null
        var pages = 0
        do {
            val pending = host.readState(watermark.ref, StateRead(cursor = cursor, limit = 3))
            controller.runToIdle()
            val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.shouldBeInstanceOf<StateReadResult.Page>()
            cursor = result.page.next
            pages++
            // asserted per page, not only at the end: a walk is many tasks
            watermark.rows() shouldBe rowsBefore
            tapFired.get() shouldBe 0
        } while (cursor != null)

        pages shouldBeGreaterThan 1
        watermark.rows() shouldBe rowsBefore
        watermark.closed() shouldBe closedBefore
        watermark.suspended() shouldBe suspendedBefore
        watermark.members() shouldBe membersBefore
        tapFired.get() shouldBe 0
        emitted.get() shouldBe emittedBefore
        host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
        source.outlet.targetMisses shouldBe 0L
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
