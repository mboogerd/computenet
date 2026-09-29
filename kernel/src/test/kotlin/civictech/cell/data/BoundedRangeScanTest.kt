package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.KeyBound
import civictech.cell.KeyBoundMistypedException
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.link.Interest
import civictech.cell.partition.RoutedCommand
import civictech.cell.partition.ShardCell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * KAGG-R: a bounded read's [KeyBound] — a half-open `[from, to)` range over a
 * family's walk order — on the three families that declare it: `MapCell` and
 * `KeyedSetCell` (bound over `K`) and `ShardCell` (bound over the element `E`,
 * not `keyFn(e)`). The bound is applied once, when the walk opens, inside
 * [EntryOrder]'s frozen order and composed with `scope` by conjunction.
 *
 * Rules pinned here: BS-30 `[KAGG-R-20]` (containment, no duplicate, exact
 * union), BS-31 `[KAGG-R-21]` (ascending across pages), BS-33 `[KAGG-R-23]`
 * (empty and inverted bounds), BS-34 `[KAGG-R-25]` (scope ∩ bound), BS-36 (a
 * mid-walk mutation inside the bound smears as documented), D9 (a bound whose
 * end is another runtime class than the keys is refused, never answered), the
 * opt-in half of `[KAGG-R-27]`, and `[KAGG-R-28]`'s additivity (a null bound walks everything).
 */
class BoundedRangeScanTest {

    private val letters: List<String> = ('a'..'z').map { it.toString() }
    private val fToP: List<String> = ('f'..'p').map { it.toString() }

    private val origin: UUID = UUID.fromString("00000000-0000-0000-0000-0000000b0a4d")
    private var tagCounter = 0L
    private val minted = HashMap<Any?, Timestamp>()

    // -- fixtures ------------------------------------------------------------

    private fun mapCellOf(keys: Iterable<String>): MapCell<String, String> =
        MapCell<String, String>().also { cell -> keys.forEach { cell.inlet.call.put(it, "v$it") } }

    private fun keyedSetOf(keys: Iterable<String>): KeyedSetCell<String, String> =
        KeyedSetCell<String, String>().also { cell -> keys.forEach { cell.inlet.call.put(it, "e$it") } }

    private fun <E> shardOf(elements: Iterable<E>, keyFn: (E) -> Any?, interest: Interest = Interest.Total): ShardCell<E> =
        ShardCell(CellRef(UUID.randomUUID()), keyFn = keyFn, initialInterest = interest).also { cell ->
            elements.forEach { route(cell, it) }
        }

    private fun <E> route(cell: ShardCell<E>, element: E) {
        val tag = Timestamp(origin, ++tagCounter)
        minted[element] = tag
        cell.routeInlet.call.propagate(RoutedCommand(0L, SetDelta(adds = mapOf(element to setOf(tag)))))
    }

    /** Drive a walk to completion, applying [between] after each page so mutation lands mid-walk. */
    private fun drive(
        cell: BoundedStateful,
        limit: Int,
        keyBound: KeyBound? = null,
        scope: Interest? = null,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(
                StateRead(cursor = cursor, limit = limit, scope = scope, byteBudget = 50_000, keyBound = keyBound)
            )
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    /** The walk-order key of each entry on a page: `K` for the two keyed families, the element for a shard. */
    private fun keysOf(page: StatePage): List<Any?> = page.entries.map {
        when (it) {
            is MapCell.MapStateEntry<*, *> -> it.key
            is KeyedSetCell.KeyedSetStateEntry<*, *> -> it.key
            is SetCell.SetStateEntry<*> -> it.element
            else -> error("unexpected entry $it")
        }
    }

    private fun keysOf(pages: List<StatePage>): List<Any?> = pages.flatMap { keysOf(it) }

    /** The three families over the same key space `a..z`, freshly built per call. */
    private fun families(keys: List<String> = letters): List<Pair<String, BoundedStateful>> = listOf(
        "MapCell" to mapCellOf(keys),
        "KeyedSetCell" to keyedSetOf(keys),
        "ShardCell" to shardOf(keys, keyFn = { it }),
    )

    // -- [KAGG-R-27]: exactly these three opt in -----------------------------

    @Test
    fun `supportsKeyBound is declared by exactly MapCell, KeyedSetCell and ShardCell`() {
        families().forEach { (name, cell) -> withClue(name) { cell.supportsKeyBound.shouldBeTrue() } }
        // the families task 1 pins as refusing stay refusing
        SetCell<String>().supportsKeyBound.shouldBeFalse()
        ListCell<String>().supportsKeyBound.shouldBeFalse()
    }

    // -- BS-30 [KAGG-R-20] ---------------------------------------------------

    @Test
    fun `BS-30 a bounded walk answers exactly the in-bound keys at every limit, on every family`() {
        for (limit in listOf(1, 5, 30)) {
            families().forEach { (name, cell) ->
                withClue("$name limit=$limit") {
                    val pages = drive(cell, limit, keyBound = KeyBound("f", "q"))
                    pages.forEach { page -> fToP.containsAll(keysOf(page)).shouldBeTrue() }
                    val walked = keysOf(pages)
                    walked.distinct().size shouldBe walked.size
                    walked.toSet() shouldBe fToP.toSet()
                    walked.size shouldBe 11
                }
            }
        }
    }

    @Test
    fun `BS-30 an open-ended bound answers exactly its side of the key space, on every family`() {
        families().forEach { (name, cell) ->
            withClue("$name to-only") {
                keysOf(drive(cell, limit = 5, keyBound = KeyBound(null, "f"))) shouldContainExactly
                    listOf("a", "b", "c", "d", "e")
            }
        }
        families().forEach { (name, cell) ->
            withClue("$name from-only") {
                keysOf(drive(cell, limit = 5, keyBound = KeyBound("q", null))) shouldContainExactly
                    ('q'..'z').map { it.toString() }
            }
        }
    }

    @Test
    fun `BS-30 a ShardCell bound is over the element, not keyFn of it`() {
        // keyFn maps every letter to 1: a bound applied to keyFn(e) would admit
        // either nothing or everything, never exactly f..p.
        val cell = shardOf(letters, keyFn = { it.length })
        for (limit in listOf(1, 5, 30)) {
            withClue("limit=$limit") {
                keysOf(drive(cell, limit, keyBound = KeyBound("f", "q"))) shouldContainExactly fToP
            }
        }
    }

    // -- BS-31 [KAGG-R-21] ---------------------------------------------------

    @Test
    fun `BS-31 a multi-page bounded walk is strictly ascending under EntryOrder, within and across pages`() {
        families().forEach { (name, cell) ->
            withClue(name) {
                val pages = drive(cell, limit = 5, keyBound = KeyBound("f", "q"))
                pages.size shouldBeGreaterThan 1
                keysOf(pages).zipWithNext().forEach { (a, b) -> EntryOrder.compare(a, b) shouldBeLessThan 0 }
                pages.map { keysOf(it) }.filter { it.isNotEmpty() }.zipWithNext().forEach { (page, nextPage) ->
                    EntryOrder.compare(page.last(), nextPage.first()) shouldBeLessThan 0
                }
            }
        }
    }

    // -- BS-33 [KAGG-R-23] ---------------------------------------------------

    @Test
    fun `BS-33 an empty or inverted bound answers one empty final page and never throws`() {
        for (bound in listOf(KeyBound("m", "m"), KeyBound("q", "f"))) {
            listOf(
                "MapCell" to mapCellOf(letters),
                "KeyedSetCell" to keyedSetOf(letters),
                "ShardCell" to shardOf(letters, keyFn = { it }),
            ).forEach { (name, cell) ->
                withClue("$name $bound") {
                    val pages = drive(cell, limit = 5, keyBound = bound)
                    pages.size shouldBe 1
                    pages.single().entries.shouldBeEmpty()
                    pages.single().next.shouldBeNull()
                }
            }
        }
    }

    @Test
    fun `BS-33 EntryOrder admits is the half-open D3 arithmetic`() {
        EntryOrder.admits("anything", null).shouldBeTrue()
        EntryOrder.admits("a", KeyBound(null, "b")).shouldBeTrue()
        EntryOrder.admits("b", KeyBound(null, "b")).shouldBeFalse() // `to` is exclusive
        EntryOrder.admits("f", KeyBound("f", "q")).shouldBeTrue() // `from` is inclusive
        EntryOrder.admits("q", KeyBound("f", "q")).shouldBeFalse()
        shouldThrow<IllegalArgumentException> { KeyBound(null, null) }
    }

    @Test
    fun `D9 a bound whose end differs in runtime class from the keys is refused by name, never answered`() {
        // Maintainer decision 2026-09-27, option (b) REFUSE. Under EntryOrder's
        // cross-class rule these would answer silently: KeyBound(40, 80) (Int
        // ends) over Long keys would admit nothing, KeyBound(100, null) would
        // admit every key — full state as though the bound had been applied.
        // The throw is `KeyBoundMistypedException`, a dedicated IllegalArgumentException
        // subtype: `ManagedHost.readState` (computenet-5woy9) names it
        // `StateReadResult.Reason.KEY_BOUND_MISTYPED` rather than folding it
        // into `READ_FAILED` with every other `readBounded` throw — see
        // `BoundedStateReadTest`'s host-level pin of both arms.
        val longKeys = (0L until 100L).toList()
        val longMap = MapCell<Long, String>().also { cell -> longKeys.forEach { cell.inlet.call.put(it, "v$it") } }
        val longKeyedSet = KeyedSetCell<Long, String>().also { cell -> longKeys.forEach { cell.inlet.call.put(it, "e$it") } }
        val longShard = shardOf(longKeys, keyFn = { it })
        val mistyped = listOf(KeyBound(40, 80), KeyBound(100, null), KeyBound(null, 100), KeyBound(40L, 80))
        listOf("MapCell" to longMap, "KeyedSetCell" to longKeyedSet, "ShardCell" to longShard).forEach { (name, cell) ->
            mistyped.forEach { bound ->
                withClue("$name $bound") {
                    val refusal = shouldThrow<KeyBoundMistypedException> { drive(cell, limit = 7, keyBound = bound) }
                    refusal.message!! shouldContain "java.lang.Integer"
                    refusal.message!! shouldContain "java.lang.Long"
                }
            }
            // the same bound with ends of the keys' class is answered, so the
            // refusal is about the class, not the values
            withClue("$name KeyBound(40L, 80L)") {
                keysOf(drive(cell, limit = 7, keyBound = KeyBound(40L, 80L))) shouldContainExactly (40L until 80L).toList()
            }
        }
        // the refusal is at EntryOrder.admits itself, so every family inherits it
        shouldThrow<KeyBoundMistypedException> { EntryOrder.admits(5L, KeyBound(1, 10)) }
        EntryOrder.admits(5L, KeyBound(1L, 10L)).shouldBeTrue()
        // a null key has no class to mismatch; it is ordered first, as before
        EntryOrder.admits(null, KeyBound(1L, 10L)).shouldBeFalse()
        EntryOrder.admits(null, KeyBound(null, 10L)).shouldBeTrue()
    }

    // -- BS-34 [KAGG-R-25] ---------------------------------------------------

    @Test
    fun `BS-34 a ShardCell walk with scope and bound answers their intersection`() {
        val cell = shardOf((0L until 100L).toList(), keyFn = { it })
        val scope = Interest.Ranges(listOf(Interest.Ranges.Range(20, 60)))
        val bound = KeyBound(40L, 80L)

        keysOf(drive(cell, limit = 7, keyBound = bound, scope = scope)) shouldContainExactly (40L until 60L).toList()
        // the contrasts: each constraint alone, so a dropped constraint cannot pass
        keysOf(drive(cell, limit = 7, scope = scope)) shouldContainExactly (20L until 60L).toList()
        keysOf(drive(cell, limit = 7, keyBound = bound)) shouldContainExactly (40L until 80L).toList()
    }

    // -- [KAGG-R-27]: bound at walk open only --------------------------------

    @Test
    fun `a resumed page keeps the bound it opened with and does not re-read request keyBound`() {
        families().forEach { (name, cell) ->
            withClue(name) {
                val first = cell.readBounded(StateRead(limit = 3, keyBound = KeyBound("f", "q")))
                keysOf(first) shouldContainExactly listOf("f", "g", "h")
                // resume with no bound, then with a disjoint one: the frozen order wins both times
                val second = cell.readBounded(StateRead(cursor = first.next, limit = 3, keyBound = null))
                keysOf(second) shouldContainExactly listOf("i", "j", "k")
                val third = cell.readBounded(StateRead(cursor = second.next, limit = 30, keyBound = KeyBound("x", null)))
                keysOf(third) shouldContainExactly listOf("l", "m", "n", "o", "p")
                third.next.shouldBeNull()
            }
        }
    }

    // -- BS-36 [KAGG-R-20] boundary: a mid-walk mutation inside the bound ----

    private val k80: List<String> = (0 until 80).map { "k${"%03d".format(it)}" }
    private val lateAdds: List<String> = (5..9).map { "k010$it" }
    private val bs36Bound = KeyBound("k010", "k070")
    private val inBound: List<String> = (10 until 70).map { "k${"%03d".format(it)}" }

    private fun assertBs36Smear(pages: List<StatePage>) {
        val walked = keysOf(pages)
        walked.distinct().size shouldBe walked.size // never duplicated
        walked shouldContainAll (inBound - "k010") // every in-bound survivor
        walked shouldContain "k010" // paged on page 1, before its removal
        lateAdds.forEach { walked shouldNotContain it } // outside the frozen order
        walked.forEach { k -> EntryOrder.admits(k, bs36Bound).shouldBeTrue() }
    }

    @Test
    fun `BS-36 the late adds sort inside the bound, after the paged prefix`() {
        lateAdds.forEach { k ->
            EntryOrder.admits(k, bs36Bound).shouldBeTrue()
            EntryOrder.compare(k, "k010") shouldBeGreaterThan 0
            EntryOrder.compare(k, "k024") shouldBeLessThan 0 // within page 1's span at limit 15
        }
    }

    @Test
    fun `BS-36 MapCell - a mid-walk in-bound mutation yields the documented smear, frontier null throughout`() {
        val cell = mapCellOf(k80)
        val pages = drive(cell, limit = 15, keyBound = bs36Bound, between = { page ->
            if (page == 1) {
                lateAdds.forEach { cell.inlet.call.put(it, "late") }
                cell.inlet.call.remove("k010")
            }
        })
        assertBs36Smear(pages)
        pages.forEach { it.frontier.shouldBeNull() }
    }

    @Test
    fun `BS-36 KeyedSetCell - a mid-walk in-bound mutation smears and the in-bound put moves the frontier`() {
        val cell = keyedSetOf(k80)
        val pages = drive(cell, limit = 15, keyBound = bs36Bound, between = { page ->
            if (page == 1) {
                lateAdds.forEach { cell.inlet.call.put(it, "late") }
                cell.inlet.call.remove("k010")
            }
        })
        assertBs36Smear(pages)
        pages.last().frontier shouldNotBe pages.first().frontier
    }

    @Test
    fun `BS-36 ShardCell - a mid-walk in-bound route smears and the closing frontier differs from the opening`() {
        val cell = shardOf(k80, keyFn = { it })
        val pages = drive(cell, limit = 15, keyBound = bs36Bound, between = { page ->
            if (page == 1) {
                lateAdds.forEach { route(cell, it) }
                cell.routeInlet.call.propagate(
                    RoutedCommand(0L, SetDelta(dels = mapOf("k010" to setOf(minted.getValue("k010")))))
                )
            }
        })
        assertBs36Smear(pages)
        pages.size shouldBeGreaterThan 2
        pages.last().frontier shouldNotBe pages.first().frontier
        pages.drop(1).dropLast(1).forEach { it.caveats shouldBe setOf(ReadCaveat.STALE_FRONTIER) }
    }

    // -- [KAGG-R-28] additivity: a null bound changes nothing ----------------

    @Test
    fun `a null keyBound walks the whole key set on every family`() {
        families().forEach { (name, cell) ->
            withClue(name) {
                val walked = keysOf(drive(cell, limit = 7, keyBound = null))
                walked.distinct().size shouldBe walked.size
                walked.toSet() shouldBe letters.toSet()
            }
        }
    }
}
