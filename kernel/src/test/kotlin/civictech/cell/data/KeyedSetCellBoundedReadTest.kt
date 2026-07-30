package civictech.cell.data

import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * V1C-CELLS: `KeyedSetCell`'s bounded read — a map plus a scalar that is
 * genuinely state, and the one family in these six whose `frontier` is **exact
 * on every page** rather than only at a walk's two ends.
 *
 * Every tag this cell mints comes from one derived source with a counter of
 * `1..tagCounter`, so its frontier is an O(1) read rather than the O(n) rescan
 * the OR-set family needs — which is why no page here carries
 * `STALE_FRONTIER`. The check that frontier supports is still only a tag-*gain*
 * detector: a `remove` mints nothing, and that limit is asserted below rather
 * than assumed away.
 */
class KeyedSetCellBoundedReadTest {

    private fun populated(n: Int, prefix: String = "k"): KeyedSetCell<String, String> {
        val cell = KeyedSetCell<String, String>()
        repeat(n) { cell.inlet.call.put("$prefix${"%03d".format(it)}", "e$it") }
        return cell
    }

    private fun drive(
        cell: KeyedSetCell<String, String>,
        limit: Int,
        since: TagFrontier? = null,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(
                StateRead(cursor = cursor, limit = limit, since = since, byteBudget = byteBudget)
            )
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    private fun entries(pages: List<StatePage>): List<KeyedSetCell.KeyedSetStateEntry<String, String>> =
        pages.flatMap { it.entries }.filterIsInstance<KeyedSetCell.KeyedSetStateEntry<*, *>>()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as KeyedSetCell.KeyedSetStateEntry<String, String>
            }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotCurrent(cell: KeyedSetCell<String, String>): Map<String, List<Any>> =
        (cell.snapshot() as Map<String, Any>)["current"] as Map<String, List<Any>>

    @Test
    fun `a walk unions to exactly the snapshot's current table, tag for tag`() {
        val cell = populated(180)

        val pages = drive(cell, limit = 50)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 51 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null }

        val walked = entries(pages)
        walked.map { it.key }.distinct().size shouldBe walked.size
        val expected = snapshotCurrent(cell)
        walked.associate { it.key to listOf(it.element, it.tag) } shouldBe expected

        // the tag counter is cell-level state, not an entry: it rides EVERY page
        val counter = (cell.snapshot() as Map<*, *>)["counter"]
        pages.forEach { it.attributes["counter"] shouldBe counter }
    }

    @Test
    fun `a limit larger than the state produces a single terminal page`() {
        val cell = populated(9)

        val pages = drive(cell, limit = 500)

        pages.size shouldBe 1
        entries(pages).size shouldBe 9
        pages.single().next.shouldBeNull()
    }

    @Test
    fun `the frontier is real and exact on every page - no stale-frontier caveat is ever needed`() {
        val cell = populated(60)

        val pages = drive(cell, limit = 10)

        pages.size shouldBeGreaterThan 3
        pages.forEach {
            it.frontier.shouldNotBeNull()
            it.caveats.shouldBeEmpty() // exact per page: an O(1) frontier needs no caveat
        }
        pages.map { it.frontier }.distinct().size shouldBe 1
        // and it names exactly one source, at the counter the snapshot reports
        val counter = (cell.snapshot() as Map<*, *>)["counter"] as Long
        pages.first().frontier!!.perSource.values.single() shouldBe counter
    }

    @Test
    fun `an empty cell reports an empty frontier rather than counter zero`() {
        val cell = KeyedSetCell<String, String>()

        val page = cell.readBounded(StateRead())

        page.entries.shouldBeEmpty()
        page.next.shouldBeNull()
        page.frontier shouldBe TagFrontier(emptyMap())
    }

    @Test
    fun `the stability check sees a mid-walk put and misses a mid-walk remove - a tag-gain detector`() {
        // the documented limit of a TagFrontier, asserted rather than assumed:
        // a put mints, a remove does not.
        val puttingWalk = populated(40)
        val putPages = drive(puttingWalk, limit = 8, between = { page ->
            if (page == 1) puttingWalk.inlet.call.put("zz-new", "e")
        })
        putPages.last().frontier shouldNotBe putPages.first().frontier

        val removingWalk = populated(40)
        val removePages = drive(removingWalk, limit = 8, between = { page ->
            if (page == 1) removingWalk.inlet.call.remove("k000") // already paged
        })
        // endpoints agree even though the fold changed: necessary, not sufficient
        removePages.last().frontier shouldBe removePages.first().frontier
        entries(removePages).map { it.key } shouldContainAll listOf("k000")
    }

    @Test
    fun `the enumeration order is imposed - stable across walks, across a restore, and under remove-then-re-put`() {
        val cell = populated(30)

        val first = entries(drive(cell, limit = 6)).map { it.key }
        first shouldContainExactly entries(drive(cell, limit = 6)).map { it.key }
        first shouldContainExactly first.sorted()

        val restored = KeyedSetCell<String, String>()
        restored.restore(cell.snapshot())
        val restoredOrder = mutableListOf<String>()
        var cursor: Cursor? = null
        do {
            val page = restored.readBounded(StateRead(cursor = cursor, limit = 6))
            restoredOrder += page.entries.filterIsInstance<KeyedSetCell.KeyedSetStateEntry<*, *>>()
                .map { it.key as String }
            cursor = page.next
        } while (cursor != null)
        restoredOrder shouldContainExactly first

        // remove then re-put moves the key to the map's tail; the imposed order
        // keeps it where it was, so it is not paged a second time
        val paged = mutableListOf<String>()
        var walkCursor: Cursor? = null
        var pageNumber = 0
        do {
            val page = cell.readBounded(StateRead(cursor = walkCursor, limit = 6))
            paged += page.entries.filterIsInstance<KeyedSetCell.KeyedSetStateEntry<*, *>>().map { it.key as String }
            walkCursor = page.next
            if (++pageNumber == 1) {
                cell.inlet.call.remove(paged.first())
                cell.inlet.call.put(paged.first(), "re-added")
            }
        } while (walkCursor != null)
        paged.distinct().size shouldBe paged.size
    }

    @Test
    fun `since narrows a walk to the keys re-bound after the frontier`() {
        val cell = populated(10, prefix = "a")
        cell.supportsSince.shouldBeTrue()
        val afterFirstBatch = cell.readBounded(StateRead(limit = 1000)).frontier
        repeat(4) { cell.inlet.call.put("b$it", "later") }

        val incremental = entries(drive(cell, limit = 3, since = afterFirstBatch))

        incremental.map { it.key }.toSet() shouldBe (0 until 4).map { "b$it" }.toSet()
    }

    @Test
    fun `scope is not declared - the key domain and the emitted domain differ`() {
        // K is the table's key; the outlet emits a SetDelta over E. An Interest
        // arriving from a consumer of that stream is defined over E, so applying
        // it to K would answer a neighbouring question. ManagedHost.readState
        // refuses instead of letting this cell widen silently.
        KeyedSetCell<String, String>().supportsScope.shouldBeFalse()
    }

    @Test
    fun `a cursor whose key was removed between pages resumes at the next key`() {
        val cell = populated(10)

        val first = cell.readBounded(StateRead(limit = 3))
        cell.inlet.call.remove("k003")

        val rest = mutableListOf<String>()
        var cursor = first.next
        while (cursor != null) {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = 3))
            rest += page.entries.filterIsInstance<KeyedSetCell.KeyedSetStateEntry<*, *>>().map { it.key as String }
            cursor = page.next
        }

        rest shouldNotContain "k003"
        rest shouldContainAll listOf("k004", "k009")
        rest.distinct().size shouldBe rest.size
    }

    @Test
    fun `an exclusive element is described, never paged, and never consumed`() {
        val cell = KeyedSetCell<String, Any>()
        val owned = Owned("secret")
        cell.inlet.call.put("plain", "value")
        cell.inlet.call.put("held", owned)

        val page = cell.readBounded(StateRead(limit = 100))

        page.exclusivesElided shouldBe 1
        val descriptor = page.entries.filterIsInstance<ExclusiveEntry>().single()
        descriptor.key shouldBe "held"
        descriptor.typeName shouldBe Owned::class.java.name
        descriptor.disposition shouldBe ExclusiveEntry.Disposition.HELD
        page.entries.none { it is Owned<*> }.shouldBeTrue()
        owned.take() shouldBe "secret"
    }

    @Test
    fun `every entry carries the whole triple, so an entry is never split`() {
        val cell = populated(5)

        val walked = entries(drive(cell, limit = 2))

        walked.forEach {
            it.key.isNotEmpty().shouldBeTrue()
            it.element.isNotEmpty().shouldBeTrue()
            (it.tag is Timestamp).shouldBeTrue()
        }
    }
}
