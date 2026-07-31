package civictech.cell.data

import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * V1C-CELLS: `ListCell`'s bounded read — the one family with **no key**, and
 * therefore the single documented exception to the bounded read's key-based
 * cursor rule.
 *
 * A list element has no identity: duplicates are legal, `set` replaces by
 * position and `removeAt` shifts every later element. Minting an identity to
 * repair that would mean new fold-path state (P2) and a changed `snapshot()`
 * shape, both forbidden for a diagnostic capability. So the cursor is
 * positional, every page declares [ReadCaveat.POSITIONAL_CURSOR], and the
 * weaker guarantee is asserted here **as documented** rather than as the
 * stronger one the interface states for keyed families:
 *
 * > at a stable frontier the walk returns each element exactly once in list
 * > order; under mid-walk mutation a removal before the cursor can cause an
 * > element to be skipped, and an insertion before the cursor can cause one to
 * > be returned twice.
 */
class ListCellBoundedReadTest {

    private fun populated(n: Int): ListCell<String> {
        val cell = ListCell<String>()
        repeat(n) { cell.inlet.call.add("e${"%03d".format(it)}") }
        return cell
    }

    private fun drive(
        cell: ListCell<String>,
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

    private fun entries(pages: List<StatePage>): List<ListCell.ListStateEntry<String>> =
        pages.flatMap { it.entries }.filterIsInstance<ListCell.ListStateEntry<*>>()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as ListCell.ListStateEntry<String>
            }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotOf(cell: ListCell<String>): List<String> = cell.snapshot() as List<String>

    @Test
    fun `at a stable frontier a walk returns each element exactly once, in list order`() {
        val cell = populated(120)

        val pages = drive(cell, limit = 25)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 26 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null }

        val walked = entries(pages)
        walked.map { it.element } shouldContainExactly snapshotOf(cell)
        walked.map { it.index } shouldContainExactly (0 until 120).toList()
    }

    @Test
    fun `every page declares the positional-cursor caveat, on every page of every walk`() {
        val cell = populated(30)

        drive(cell, limit = 4).forEach {
            withClue("a consumer must read the weaker guarantee off the page, not out of a KDoc") {
                it.caveats shouldContain ReadCaveat.POSITIONAL_CURSOR
            }
        }
        // a single-page walk declares it too
        cell.readBounded(StateRead(limit = 1000)).caveats shouldContain ReadCaveat.POSITIONAL_CURSOR
    }

    @Test
    fun `a limit larger than the state produces a single terminal page`() {
        val cell = populated(7)

        val pages = drive(cell, limit = 500)

        pages.size shouldBe 1
        entries(pages).size shouldBe 7
        pages.single().next.shouldBeNull()
    }

    @Test
    fun `a mid-walk removal before the cursor SKIPS an element - the documented weaker guarantee`() {
        val cell = populated(12)

        val pages = drive(cell, limit = 4, between = { page ->
            if (page == 1) cell.inlet.call.removeAt(0) // shifts every later element down one
        })

        val walked = entries(pages).map { it.element }
        // e000..e003 were paged; removing e000 shifts e004 into index 3, which the
        // cursor has already passed — so e004 is skipped. Asserted as documented.
        walked shouldNotBe snapshotOf(cell)
        walked.contains("e004").shouldBeFalse()
        // and nothing is torn or duplicated, and the walk still terminates
        walked.distinct().size shouldBe walked.size
        pages.last().next.shouldBeNull()
    }

    @Test
    fun `a mid-walk insertion before the cursor RETURNS an element twice - the documented weaker guarantee`() {
        val cell = populated(12)

        val pages = drive(cell, limit = 4, between = { page ->
            if (page == 1) cell.inlet.call.add(0, "inserted") // shifts every later element up one
        })

        val walked = entries(pages).map { it.element }
        // e003 was paged at index 3; the insertion moves it to index 4, which the
        // cursor has not reached yet — so it comes back a second time.
        walked.count { it == "e003" } shouldBe 2
        // still whole entries, still terminates
        pages.last().next.shouldBeNull()
        pages.forEach { it.caveats shouldContain ReadCaveat.POSITIONAL_CURSOR }
    }

    @Test
    fun `a cursor past a shrunken list terminates instead of throwing`() {
        val cell = populated(10)

        val first = cell.readBounded(StateRead(limit = 6))
        first.next shouldNotBe null
        // the list shrinks below the cursor entirely
        repeat(8) { cell.inlet.call.removeAt(0) }

        val next = cell.readBounded(StateRead(cursor = first.next, limit = 6))

        next.entries.size shouldBe 0
        next.next.shouldBeNull()
    }

    @Test
    fun `list order survives a snapshot-restore round trip, unlike the hash-rebuilt families`() {
        val cell = populated(20)
        val original = entries(drive(cell, limit = 5)).map { it.element }

        val restored = ListCell<String>()
        restored.restore(cell.snapshot())

        entries(drive(restored, limit = 5)).map { it.element } shouldContainExactly original
    }

    @Test
    fun `the frontier is null, and no bound is declared`() {
        val cell = populated(5)

        drive(cell, limit = 2).forEach { it.frontier.shouldBeNull() }
        cell.supportsSince.shouldBeFalse()
        cell.supportsScope.shouldBeFalse()
    }

    @Test
    fun `the byte budget shortens pages without stalling the walk`() {
        val cell = populated(25)

        val pages = drive(cell, limit = 200, byteBudget = 200)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty().shouldBeTrue() }
        entries(pages).size shouldBe 25
    }

    @Test
    fun `an exclusive element is described, never paged, and never consumed`() {
        val cell = ListCell<Any>()
        val owned = Owned("secret")
        cell.inlet.call.add("plain")
        cell.inlet.call.add(owned)

        val page = cell.readBounded(StateRead(limit = 100))

        page.exclusivesElided shouldBe 1
        val descriptor = page.entries.filterIsInstance<ExclusiveEntry>().single()
        descriptor.key shouldBe 1 // the position, since a list element has no key
        descriptor.typeName shouldBe Owned::class.java.name
        page.entries.none { it is Owned<*> }.shouldBeTrue()
        owned.take() shouldBe "secret"
    }
}
