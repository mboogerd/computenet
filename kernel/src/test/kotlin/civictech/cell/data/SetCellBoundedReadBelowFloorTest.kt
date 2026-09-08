package civictech.cell.data

import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan as longShouldBeGreaterThan
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `SetCell.readBounded`'s below-floor `since` escalation (computenet-9sm.7.2,
 * decisions 9sm.7-D3/D4/D6, `[24-TAG-04]`, [civictech.cell.BoundedRead] rule 5).
 *
 * `readBounded` shares [SetCell.belowFloor] — the same predicate the pull path
 * ([outlet.pullServe]) already uses — so a `StateRead.since` naming, for any
 * source, a counter below that source's compaction floor
 * ([ReclaimedDots.floors]) is answered with the FULL walk rather than a page
 * silently missing the tags this replica has discarded, and the escalation is
 * DECLARED (`attributes["sinceEscalated"] = true`) on every page it produces —
 * never a new [civictech.cell.ReadCaveat] constant (9sm.7-D6).
 *
 * **Discriminating fixture.** The whole point of the below-floor branch is
 * observable only when the escalated walk actually returns tags a plain
 * since-filtered walk would have dropped. [fixture] holds ten LIVE tags whose
 * counters (1..10) sit *below* the `since` under test, so the below-floor case
 * and the at/above-floor case provably differ: the escalated union names those
 * ten elements and the non-escalated filtered reply does not.
 */
class SetCellBoundedReadBelowFloorTest {

    private fun buffer(cell: SetCell<String>, into: MutableList<Invocation>) {
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<String>>>(into), PortRef.generate()))
    }

    /**
     * One source `X` (this cell's own `tagSource`):
     *  - `live0`..`live9` — ten LIVE elements, add-tag counters 1..10, never removed.
     *  - `gone0`..`gone39` — forty elements added (counters 11..50) then removed
     *    (del-dot counters 51..90), then reclaimed whole by `compactBelow` at a
     *    frontier of 90 — every tag of every one of those 40 tombstone entries,
     *    del-dot included, is `<= 90`, so the discard rule
     *    ([SetCellCompactBelowTest]) reclaims all of them and [ReclaimedDots]
     *    records a floor of 90 for `X`.
     *  - `new0`..`new2` — three more LIVE elements minted after compaction
     *    (counters 91..93), so the floor and the live tail are provably
     *    disjoint.
     */
    private fun fixture(): Pair<SetCell<String>, UUID> {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)

        repeat(10) { cell.inlet.call.add("live$it") } // counters 1..10
        repeat(40) { cell.inlet.call.add("gone$it") } // counters 11..50
        repeat(40) { cell.inlet.call.remove("gone$it") } // del-dots, counters 51..90

        val source = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("live0").single().sourceId

        // 40 entries, each contributing 2 dels tags (the covered add-tag + the del-dot) plus
        // the 1 add-tag it covers = 3 per entry (SetCellCompactBelowTest's own accounting)
        val discarded = cell.compactBelow(TagFrontier(mapOf(source to 90L)))
        check(discarded == 120) {
            "fixture must reclaim exactly the 40 tombstoned 'gone*' entries (120 tags total) " +
                "or the floor of 90 this test relies on was not actually reached (reclaimed=$discarded)"
        }

        repeat(3) { cell.inlet.call.add("new$it") } // counters 91..93

        return cell to source
    }

    /** Walk [cell] to completion via `readBounded` directly — no host needed. */
    private fun walk(cell: SetCell<String>, limit: Int, since: TagFrontier? = null): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = limit, since = since))
            pages += page
            cursor = page.next
            check(guard++ < 1000) { "walk did not terminate within 1000 pages" }
        } while (cursor != null)
        return pages
    }

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(pages: List<StatePage>): List<SetCell.SetStateEntry<String>> =
        pages.flatMap { it.entries }.filterIsInstance<SetCell.SetStateEntry<*>>()
            .map { it as SetCell.SetStateEntry<String> }

    // ------------------------------------------------------- below floor

    @Test
    fun `a since below the compaction floor escalates to the full walk and declares it on every page`() {
        val (cell, source) = fixture()

        val fullPages = walk(cell, limit = 4, since = null)
        val fullUnion = entriesOf(fullPages).associate { it.element to (it.addTags to it.delTags) }

        val escalatedPages = walk(cell, limit = 4, since = TagFrontier(mapOf(source to 30L)))
        escalatedPages.size shouldBeGreaterThan 2 // the fixture is sized to force multiple pages

        val escalatedUnion = entriesOf(escalatedPages).associate { it.element to (it.addTags to it.delTags) }
        // byte-equal to the since=null union — never a partial page
        escalatedUnion shouldBe fullUnion
        // the elements a plain since-filter would have dropped (counters 1..10,
        // all <= 30) are provably present
        (0 until 10).forEach { escalatedUnion.keys shouldContain "live$it" }

        // declared on EVERY page — first, intermediate and last
        escalatedPages.forEach { it.attributes["sinceEscalated"] shouldBe true }
    }

    // ---------------------------------------------------- at/above floor

    @Test
    fun `a since at or above the floor is not escalated, and carries no escalation key`() {
        val (cell, source) = fixture()

        listOf(90L, 95L).forEach { asked ->
            val pages = walk(cell, limit = 4, since = TagFrontier(mapOf(source to asked)))
            pages.forEach { it.attributes shouldNotContainKey "sinceEscalated" }

            val entries = entriesOf(pages)
            // only tags beyond `since` ride the reply — the below-floor union's
            // live0..live9 (counters 1..10) are absent at both these thresholds
            entries.none { it.element.startsWith("live") }.shouldBeTrue()
            entries.forEach { entry ->
                entry.addTags.forEach { it.counter longShouldBeGreaterThan asked }
                entry.delTags.forEach { it.counter longShouldBeGreaterThan asked }
            }
        }
    }

    // -------------------------------------------------------- no compaction

    @Test
    fun `without compaction a since is filtered partially as today, and carries no escalation key`() {
        val cell = SetCell<String>()
        val invocationBuffer = mutableListOf<Invocation>()
        buffer(cell, invocationBuffer)
        repeat(15) { cell.inlet.call.add("live$it") } // counters 1..15, none reclaimed
        val source = (invocationBuffer[0].args[0] as SetDelta<String>).adds.getValue("live0").single().sourceId

        val pages = walk(cell, limit = 4, since = TagFrontier(mapOf(source to 8L)))
        val entries = entriesOf(pages)
        // counters 1..8 filtered out, 9..15 survive: a genuinely PARTIAL page,
        // not an escalated (full) one and not an empty one either
        entries.map { it.element }.toSet() shouldBe (8..14).map { "live$it" }.toSet()
        pages.forEach { it.attributes shouldNotContainKey "sinceEscalated" }
    }

    // ------------------------------------------------------------- host

    @Test
    fun `through the host, a below-floor since on a compacted cell answers a Page, never SINCE_UNSUPPORTED`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val (cell, source) = fixture()
        host.managementInlet.call.spawn(cell)

        val pending = host.readState(cell.ref, StateRead(limit = 4, since = TagFrontier(mapOf(source to 30L))))
        // routed to the cell's own execution context, not decided inline
        pending.isDone.shouldBeFalse()
        controller.runToIdle()
        val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        result.shouldBeInstanceOf<StateReadResult.Page>()
        val page = (result as StateReadResult.Page).page
        page.attributes["sinceEscalated"] shouldBe true
        // and the cell still declares support — no refusal path was taken
        cell.supportsSince.shouldBeTrue()
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
