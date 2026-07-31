package civictech.cell.data.op

import civictech.cell.ReadCaveat
import civictech.cell.StateRead
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * V1C-OPS: **the cross-sub-state ordering, asserted rather than assumed** — the
 * question `20-wave-neutral-read-design.md` §7 left open for the composite
 * operator cells, closed with tests.
 *
 * Three defects follow from getting it wrong, all silent, and each has a test
 * here:
 *
 * 1. an entry returned **twice** — a cursor naming only "the last key `e`"
 *    cannot say which of `IntersectSetCell`'s three `E`-keyed sub-states it had
 *    reached;
 * 2. a resume **skipping a whole sub-state** — a cursor resolved against
 *    whichever sub-state it consults first walks off the end and terminates
 *    early, having returned a strict subset of `snapshot()`;
 * 3. insertion order mistaken for enumeration order — the backing maps are
 *    `LinkedHashMap`s, so a mid-walk remove-then-re-add moves a key to the tail
 *    and a live-iterating cursor hands it back a second time.
 *
 * The fixture throughout is [IntersectSetCell] with **all three sub-states
 * keyed by the same `E`** and overlapping in content, which is the hardest
 * shape in the package for this property, plus [JoinCell] for the two-sub-state
 * map-shaped case.
 */
class OperatorCursorOrderTest {

    private val rig = OpRig()

    /**
     * left `[s1, s2, l1, l2]`, right `[s1, s2, r1]`, ledger `[s1, s2]` — nine
     * entries over three same-typed, overlapping sub-states, with a frozen
     * enumeration order this suite can name position by position.
     */
    private fun fixture(): Triple<SetSource, SetSource, IntersectSetCell<String>> {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(IntersectSetCell<String>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()
        left.add("s1", "s2", "l1", "l2")
        right.add("s1", "s2", "r1")
        rig.settle()
        return Triple(left, right, cell)
    }

    private val expectedOrder = listOf(
        Triple("left", null, "s1"), Triple("left", null, "s2"),
        Triple("left", null, "l1"), Triple("left", null, "l2"),
        Triple("right", null, "s1"), Triple("right", null, "s2"), Triple("right", null, "r1"),
        Triple("ledger", null, "s1"), Triple("ledger", null, "s2"),
    )

    // -------------------------------------------- (a) no entry twice, ever

    @Test
    fun `an element live in all three sub-states is three labelled entries, never one collapsed or repeated`() {
        val (_, _, cell) = fixture()

        val pages = rig.walk(cell, limit = 1)

        // one entry per (subState, key) pair — the Decision A identity
        pages.identities().distinct().size shouldBe 9
        pages.identities() shouldContainExactly expectedOrder
        // `s1` is in left, right AND ledger: three entries, not one, and not one
        // entry three times — their tag sets differ, so collapsing would lose state
        val s1 = pages.tagged("left").single { it.element == "s1" }
        val s1right = pages.tagged("right").single { it.element == "s1" }
        val s1ledger = pages.tagged("ledger").single { it.element == "s1" }
        setOf(s1.tags, s1right.tags, s1ledger.tags).size shouldBe 3
        s1ledger.tags shouldBe (s1.tags + s1right.tags)
    }

    @Test
    fun `a JoinCell key held on both sides is two labelled entries, one per side`() {
        val left = rig.spawn(MapSource())
        val right = rig.spawn(MapSource())
        val cell = rig.spawn(JoinCell<String, String, String>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()
        left.put("both" to "L", "leftOnly" to "L2")
        right.put("both" to "R", "rightOnly" to "R2")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)

        pages.identities().distinct().size shouldBe 4
        pages.identities() shouldContainExactly listOf(
            Triple("left", null, "both"), Triple("left", null, "leftOnly"),
            Triple("right", null, "both"), Triple("right", null, "rightOnly"),
        )
        // the same key, two different values, told apart only by the label
        pages.keyed("left").single { it.key == "both" }.value shouldBe "L"
        pages.keyed("right").single { it.key == "both" }.value shouldBe "R"
    }

    @Test
    fun `the enumeration order is deterministic, so two walks of unchanged state agree exactly`() {
        val (_, _, cell) = fixture()

        rig.walk(cell, limit = 2).identities() shouldContainExactly expectedOrder
        rig.walk(cell, limit = 5).identities() shouldContainExactly expectedOrder
        rig.walk(cell, limit = 200).identities() shouldContainExactly expectedOrder
    }

    // ---------------------------------- (b) a resume lands in the right place

    @Test
    fun `a cursor that lands inside a sub-state finishes it before entering the next`() {
        val (_, _, cell) = fixture()

        // limit 3 cuts the page inside "left" (4 entries), so the cursor names
        // left[3] — a position inside sub-state 0, not at its boundary
        val first = rig.page(cell, StateRead(limit = 3))
        first.entries.size shouldBe 3
        first.next shouldNotBe null
        (first.entries as List<*>).map { (it as OperatorEntry).identity() } shouldContainExactly
            expectedOrder.take(3)

        val second = rig.page(cell, StateRead(cursor = first.next, limit = 3))

        // it finished "left" first, and only then crossed into "right"
        second.entries.map { (it as OperatorEntry).identity() } shouldContainExactly expectedOrder.slice(3..5)
        (second.entries.first() as OperatorEntry).subState shouldBe "left"
        (second.entries.drop(1).first() as OperatorEntry).subState shouldBe "right"
    }

    @Test
    fun `a cursor at the exact end of a sub-state enters the next one rather than terminating`() {
        val (_, _, cell) = fixture()

        // limit 4 consumes "left" exactly — the boundary case where a
        // per-sub-state cursor would report the walk complete
        val first = rig.page(cell, StateRead(limit = 4))
        first.entries.map { (it as OperatorEntry).subState }.toSet() shouldBe setOf("left")
        first.next shouldNotBe null // NOT the end of the walk

        val second = rig.page(cell, StateRead(cursor = first.next, limit = 4))
        (second.entries.first() as OperatorEntry).subState shouldBe "right"

        // ... and the same at the last boundary: after "right" comes "ledger"
        val third = rig.page(cell, StateRead(cursor = second.next, limit = 4))
        third.entries.map { (it as OperatorEntry).subState }.toSet() shouldBe setOf("ledger")
        third.next.shouldBeNull()
    }

    @Test
    fun `a walk at limit 1 crosses both boundaries and still returns every sub-state exactly once`() {
        val (_, _, cell) = fixture()

        val pages = rig.walk(cell, limit = 1)

        pages.size shouldBe 9
        pages.dropLast(1).forEach { it.next shouldNotBe null }
        pages.last().next.shouldBeNull()
        pages.identities() shouldContainExactly expectedOrder
    }

    // ------------------------------- (c) insertion order is not walk order

    @Test
    fun `a key removed and re-added mid-walk is not returned twice`() {
        val (left, _, cell) = fixture()

        val pages = rig.walk(cell, limit = 2, between = { pageNumber ->
            // "s1" was returned by page 1. A remove-then-re-add moves it to the
            // TAIL of the backing LinkedHashMap, which is exactly the mutation a
            // live-iterating cursor would hand back a second time.
            if (pageNumber == 1) {
                left.remove("s1")
                left.add("s1")
                rig.settle()
            }
        })

        pages.identities().distinct().size shouldBe pages.identities().size
        pages.tagged("left").count { it.element == "s1" } shouldBe 1
        // and the walk did not lose its place: everything after it still arrived
        pages.identities() shouldContainAll expectedOrder.drop(1)
    }

    @Test
    fun `a cursor whose key vanished resumes at the next key in the same sub-state`() {
        val (left, _, cell) = fixture()

        // page 1 returns left[s1, s2]; the cursor now names left[2] = "l1"
        val first = rig.page(cell, StateRead(limit = 2))
        first.entries.map { (it as OperatorEntry).identity() } shouldContainExactly expectedOrder.take(2)

        left.remove("l1") // the very key the cursor names
        rig.settle()

        val second = rig.page(cell, StateRead(cursor = first.next, limit = 2))

        // continued from the next key in the SAME sub-state: no restart, no
        // throw, no skipped sub-state — and a short page rather than a wrong one
        second.entries.map { (it as OperatorEntry).identity() } shouldContainExactly
            listOf(Triple("left", null, "l2"))
        second.next shouldNotBe null

        val rest = mutableListOf(second)
        var cursor = second.next
        while (cursor != null) {
            val page = rig.page(cell, StateRead(cursor = cursor, limit = 2))
            rest += page
            cursor = page.next
        }
        val walked = (listOf(first) + rest).identities()
        walked shouldNotContain Triple("left", null, "l1")
        walked.distinct().size shouldBe walked.size
        walked shouldContainAll listOf(Triple("right", null, "r1"), Triple("ledger", null, "s2"))
    }

    @Test
    fun `a cursor at a sub-state's last key, removed, still enters the next sub-state`() {
        val (left, _, cell) = fixture()

        // page 1 returns left[s1, s2, l1]; the cursor names left[3] = "l2", the
        // LAST key of sub-state 0
        val first = rig.page(cell, StateRead(limit = 3))
        left.remove("l2")
        rig.settle()

        val second = rig.page(cell, StateRead(cursor = first.next, limit = 3))

        // the vanished key is skipped and the walk crosses the boundary rather
        // than reporting itself complete
        second.entries.map { (it as OperatorEntry).identity() } shouldContainExactly
            listOf(Triple("right", null, "s1"), Triple("right", null, "s2"))
        second.next shouldNotBe null
    }

    // -------------------------------------------- (d) the documented smear

    @Test
    fun `a mid-walk mutation smears the union rather than tearing or duplicating it`() {
        val (left, _, cell) = fixture()
        val survivors = expectedOrder.toSet()

        val pages = rig.walk(cell, limit = 2, between = { pageNumber ->
            if (pageNumber == 1) {
                left.add("late1", "late2")
                rig.settle()
            }
        })

        // never duplicated, never torn at entry granularity
        pages.identities().distinct().size shouldBe pages.identities().size
        // every entry present for the whole walk is in the union
        pages.identities() shouldContainAll survivors
        // entries added mid-walk MAY be missing — this implementation freezes the
        // enumeration order at walk start, so they are. The documented contract,
        // not a stricter promise.
        pages.identities() shouldNotContain Triple("left", null, "late1")
        // the stamp told the caller the fold gained tags, so the union is a smear
        pages.size shouldBeGreaterThan 2
        pages.last().frontier shouldNotBe pages.first().frontier
        // ... and the intermediate pages said so rather than pretending to be current
        pages.drop(1).dropLast(1).forEach {
            it.caveats shouldContain ReadCaveat.STALE_FRONTIER
            it.frontier shouldBe pages.first().frontier
        }
        pages.first().caveats.shouldBeEmpty()
        pages.last().caveats.shouldBeEmpty()
    }

    @Test
    fun `a removal-only mid-walk mutation can leave the endpoint frontiers equal, so the check is necessary not sufficient`() {
        val (left, _, cell) = fixture()

        // remove the element carrying the HIGHEST tag counter on neither side,
        // so the max per source is untouched: "s1" was tagged first on both
        // sides, and later tags on both sides survive
        val pages = rig.walk(cell, limit = 2, between = { pageNumber ->
            if (pageNumber == 1) {
                left.remove("s1")
                rig.settle()
            }
        })

        // the removal really happened: `s1` is gone from the left tag state and
        // was retracted from the ledger
        cell.snapshot().slot(0).asTagMap().keys shouldNotContain "s1"
        cell.snapshot().slot(2).asTagMap().keys shouldNotContain "s1"
        // ... it minted no tag, so both endpoint stamps are equal
        pages.first().frontier shouldBe pages.last().frontier
        // ... while the union still names `s1` present on the left, because page
        // 1 had already returned it. Asserted as the documented limit of the
        // check for this family, not as a promise a caller may rely on.
        pages.tagged("left").map { it.element } shouldContain "s1"
        // the right side never moved, so nothing about it explains the equality
        cell.snapshot().slot(1).asTagMap().keys shouldContainAll listOf("s1", "s2", "r1")
    }
}
