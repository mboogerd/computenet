package civictech.cell.data

import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.link.Interest
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * V1C-CELLS: `MapCell`'s bounded read — the simplest of the six, and the one
 * that shows the enumeration-order trap most plainly.
 *
 * `state` is a `LinkedHashMap`, so an inherited walk order would be insertion
 * order: a `remove` then `put` of the same key moves it to the tail, and
 * `restore()` refills the map from the `HashMap` that `snapshot()` produced and
 * reorders it wholesale. Both are asserted here against the *imposed* order the
 * implementation uses instead.
 */
class MapCellBoundedReadTest {

    private fun populated(n: Int, prefix: String = "k"): MapCell<String, String> {
        val cell = MapCell<String, String>()
        repeat(n) { cell.inlet.call.put("$prefix${"%03d".format(it)}", "v$it") }
        return cell
    }

    /** Drive a walk to completion, applying [between] after each page so mutation lands mid-walk. */
    private fun drive(
        cell: MapCell<String, String>,
        limit: Int,
        scope: Interest? = null,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(
                StateRead(cursor = cursor, limit = limit, scope = scope, byteBudget = byteBudget)
            )
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    private fun entries(pages: List<StatePage>): List<MapCell.MapStateEntry<String, String>> =
        pages.flatMap { it.entries }.filterIsInstance<MapCell.MapStateEntry<*, *>>()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as MapCell.MapStateEntry<String, String>
            }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotOf(cell: MapCell<String, String>): Map<String, String> =
        cell.snapshot() as Map<String, String>

    @Test
    fun `a walk unions to exactly the snapshot, respects limit, resumes, and terminates`() {
        val cell = populated(250)

        val pages = drive(cell, limit = 60)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 61 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null } // only the final page ends the walk

        val walked = entries(pages)
        walked.map { it.key }.distinct().size shouldBe walked.size // no key twice
        walked.associate { it.key to it.value } shouldBe snapshotOf(cell)
        // an untagged family: no stamp, and the KDoc says what that costs
        pages.forEach { it.frontier.shouldBeNull() }
        pages.forEach { it.caveats.shouldBeEmpty() }
    }

    @Test
    fun `a limit larger than the state produces a single terminal page`() {
        val cell = populated(12)

        val pages = drive(cell, limit = 500)

        pages.size shouldBe 1
        pages.single().next.shouldBeNull()
        entries(pages).size shouldBe 12
    }

    @Test
    fun `the enumeration order is imposed - stable across walks, across a restore, and under remove-then-re-add`() {
        val cell = populated(40)

        val first = entries(drive(cell, limit = 7)).map { it.key }
        val second = entries(drive(cell, limit = 7)).map { it.key }
        // (a) two walks of an unchanged cell agree
        first shouldContainExactly second
        // and it is a genuine total order, not an accident of insertion
        first shouldContainExactly first.sorted()

        // (b) a snapshot/restore round trip into a fresh instance walks identically,
        // even though `restore` refills the map from a HashMap
        val restored = MapCell<String, String>()
        restored.restore(cell.snapshot())
        val restoredOrder = mutableListOf<String>()
        var cursor: Cursor? = null
        do {
            val page = restored.readBounded(StateRead(cursor = cursor, limit = 7))
            restoredOrder += page.entries.filterIsInstance<MapCell.MapStateEntry<*, *>>().map { it.key as String }
            cursor = page.next
        } while (cursor != null)
        restoredOrder shouldContainExactly first

        // (c) removing a key and re-adding it does not move it across an in-flight
        // cursor: under insertion order it would jump to the tail and be paged twice
        val paged = mutableListOf<String>()
        var walkCursor: Cursor? = null
        var pageNumber = 0
        do {
            val page = cell.readBounded(StateRead(cursor = walkCursor, limit = 7))
            paged += page.entries.filterIsInstance<MapCell.MapStateEntry<*, *>>().map { it.key as String }
            walkCursor = page.next
            if (++pageNumber == 1) {
                val alreadyPaged = paged.first()
                cell.inlet.call.remove(alreadyPaged)
                cell.inlet.call.put(alreadyPaged, "re-added")
            }
        } while (walkCursor != null)
        paged.distinct().size shouldBe paged.size
    }

    @Test
    fun `a mid-walk mutation yields the documented smear and never throws`() {
        val cell = populated(80)
        val survivors = snapshotOf(cell).keys.toSet()

        val pages = drive(cell, limit = 15, between = { pageNumber ->
            if (pageNumber == 1) {
                repeat(10) { cell.inlet.call.put("zz-late$it", "v") } // sorts after every existing key
                cell.inlet.call.remove("k000") // already paged
            }
        })

        val walked = entries(pages)
        walked.map { it.key }.distinct().size shouldBe walked.size // never duplicated
        // every entry present for the whole walk is in the union...
        walked.map { it.key } shouldContainAll (survivors - "k000")
        // ...and mid-walk additions are outside the frozen order, so they are absent.
        // Asserted as the documented contract, not as a stricter promise.
        walked.map { it.key } shouldNotContain "zz-late0"
    }

    @Test
    fun `a cursor whose key was removed between pages resumes at the next key`() {
        val cell = populated(10)

        val first = cell.readBounded(StateRead(limit = 3))
        first.entries.size shouldBe 3
        // remove the key the cursor is about to hand back
        cell.inlet.call.remove("k003")

        val rest = mutableListOf<String>()
        var cursor = first.next
        while (cursor != null) {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = 3))
            rest += page.entries.filterIsInstance<MapCell.MapStateEntry<*, *>>().map { it.key as String }
            cursor = page.next
        }

        rest shouldNotContain "k003"
        rest shouldContainAll listOf("k004", "k009")
        rest.distinct().size shouldBe rest.size
    }

    @Test
    fun `scope narrows a walk to the admitted keys - the interest is over this cell's own key domain`() {
        val cell = populated(10, prefix = "a")
        repeat(4) { cell.inlet.call.put("b$it", "v") }

        cell.supportsScope.shouldBeTrue()
        val scoped = entries(drive(cell, limit = 3, scope = Prefix("b")))

        scoped.map { it.key }.toSet() shouldBe (0 until 4).map { "b$it" }.toSet()
    }

    @Test
    fun `the byte budget shortens pages without stalling the walk`() {
        val cell = populated(30)

        val pages = drive(cell, limit = 200, byteBudget = 200)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty().shouldBeTrue() }
        entries(pages).size shouldBe 30
    }

    @Test
    fun `an exclusive value is described, never paged, and never consumed`() {
        val cell = MapCell<String, Any>()
        val owned = Owned("secret")
        cell.inlet.call.put("plain", "value")
        cell.inlet.call.put("held", owned)

        val page = cell.readBounded(StateRead(limit = 100))

        page.exclusivesElided shouldBe 1
        val descriptor = page.entries.filterIsInstance<ExclusiveEntry>().single()
        descriptor.key shouldBe "held"
        descriptor.typeName shouldBe Owned::class.java.name
        descriptor.identity shouldBe System.identityHashCode(owned)
        descriptor.disposition shouldBe ExclusiveEntry.Disposition.HELD
        page.entries.none { it is Owned<*> }.shouldBeTrue()
        // `take` was never called: it still succeeds
        owned.take() shouldBe "secret"
    }

    // --------------------------------------------------------------- helpers

    /** A tiny, honest [Interest] over string keys — the shipped arms are hash-slotted. */
    private data class Prefix(val prefix: String) : Interest {
        override fun overlaps(other: Interest) = true
        override fun admits(key: Any?) = (key as? String)?.startsWith(prefix) == true
    }
}
