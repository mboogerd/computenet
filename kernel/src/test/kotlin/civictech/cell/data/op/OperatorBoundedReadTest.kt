package civictech.cell.data.op

import civictech.cell.StateRead
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * V1C-OPS: **the union of a walk equals `snapshot()`'s content**, cell by cell —
 * every sub-state, nothing extra, nothing missing (Decision E) — plus the
 * per-page bound, the cursor's termination, and the scalar riders that must ride
 * every page (Decision D).
 *
 * The cross-sub-state *ordering* proofs live in [OperatorCursorOrderTest]; the
 * ownership, oversized-accumulator and wave-neutrality edges in
 * [OperatorBoundedReadEdgesTest]. This class is the "it is all there, and only
 * it" half.
 */
class OperatorBoundedReadTest {

    private val rig = OpRig()

    // ------------------------------------------------- the map-shaped family

    @Test
    fun `JoinCell pages both input sides, labelled, and nothing of the derived join`() {
        val left = rig.spawn(MapSource())
        val right = rig.spawn(MapSource())
        val cell = rig.spawn(JoinCell<String, String, String>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()

        left.put("a" to "L-a", "b" to "L-b", "c" to "L-c")
        right.put("b" to "R-b", "c" to "R-c", "d" to "R-d")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        pages.keyedMap("left") shouldBe snapshot.slot(0).asMap()
        pages.keyedMap("right") shouldBe snapshot.slot(1).asMap()
        // exactly the two sub-states, and nothing else — the joined pairs are
        // derived and are not in snapshot(), so they are not in the walk
        pages.allEntries().map { it.subState }.toSet() shouldBe setOf("left", "right")
        pages.allEntries().size shouldBe 6
        // untagged family: no frontier, so no stability check and no caveat
        pages.forEach { it.frontier.shouldBeNull() }
        pages.forEach { it.caveats.shouldBeEmpty() }
        cell.supportsSince shouldBe false
        cell.supportsScope shouldBe false
    }

    @Test
    fun `LookupJoinCell pages facts and dimensions, not the enriched output`() {
        val facts = rig.spawn(MapSource())
        val dims = rig.spawn(MapSource())
        val cell = rig.spawn(
            LookupJoinCell<String, String, String, String, String>(
                fk = { it.substring(0, 1) },
                combine = { _, v, d -> "$v@$d" },
            ),
        )
        facts.feed(cell.fact)
        dims.feed(cell.dimension)
        rig.settle()

        dims.put("x" to "DIM-x", "y" to "DIM-y")
        facts.put("x1" to "F1", "x2" to "F2", "y1" to "F3")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        pages.keyedMap("facts") shouldBe snapshot.slot(0).asMap()
        pages.keyedMap("dims") shouldBe snapshot.slot(1).asMap()
        // the enriched map is rebuilt by restore() from these two and is not in
        // snapshot(); a bounded read shows the inputs, not `combine`'s results
        pages.allEntries().map { it.subState }.toSet() shouldBe setOf("facts", "dims")
        pages.keyed("facts").none { it.value.toString().contains("@") } shouldBe true
    }

    @Test
    fun `CombineLatestCell pages both input sides, not the combined map`() {
        val left = rig.spawn(MapSource())
        val right = rig.spawn(MapSource())
        val cell = rig.spawn(CombineLatestCell<String, String, String, String>(combine = { _, v, w -> "$v|$w" }))
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()

        left.put("a" to "L-a", "b" to "L-b")
        right.put("b" to "R-b", "c" to "R-c")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)
        val snapshot = cell.snapshot()

        pages.keyedMap("left") shouldBe snapshot.slot(0).asMap()
        pages.keyedMap("right") shouldBe snapshot.slot(1).asMap()
        pages.allEntries().none { it.toString().contains("|") } shouldBe true
    }

    @Test
    fun `MergeableGroupByCell pages its single aggregate sub-state`() {
        val source = rig.spawn(SetSource())
        val cell = rig.spawn(
            MergeableGroupByCell<String, String, String>(
                keyOf = { it.substring(0, 1) },
                accumulate = { it },
                merge = { a, b -> if (a < b) "$a,$b" else "$b,$a" },
            ),
        )
        source.feedDirect(cell.inlet)
        rig.settle()

        source.add("a1", "a2", "b1", "c1")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)

        pages.keyedMap("groups") shouldBe cell.snapshot().asMap()
        pages.allEntries().map { it.subState }.toSet() shouldBe setOf("groups")
        pages.last().next.shouldBeNull()
    }

    // ------------------------------------------------- the tagged composites

    @Test
    fun `GroupByCell pages its input tag state and its groups, in snapshot order`() {
        val source = rig.spawn(SetSource())
        val cell = rig.spawn(
            GroupByCell(keyFn = { s: String -> s.substring(0, 1) }, aggregator = Aggregators.count<String>()),
        )
        source.feed(cell.inlet)
        rig.settle()

        source.add("a1", "a2", "a3", "b1", "c1")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        pages.taggedMap("input") shouldBe snapshot.slot(0).asTagMap()
        pages.groups("groups").associate { it.key to listOf(it.count, it.accumulator) } shouldBe
            snapshot.slot(1).asMap().mapValues { (it.value as List<*>).toList() }
        // the two sub-states, in snapshot()'s order: every "input" entry precedes
        // every "groups" entry, because the cursor orders by ordinal first
        pages.allEntries().map { it.subState }.distinct() shouldContainExactly listOf("input", "groups")
        // a tag-carrying cell stamps a frontier, exact at both ends of a quiet walk
        pages.first().frontier.shouldNotBeNull()
        pages.first().frontier shouldBe pages.last().frontier
    }

    @Test
    fun `IntersectSetCell pages all three sub-states and the same element in each`() {
        val (left, right, cell) = intersection()

        left.add("both", "leftOnly")
        right.add("both", "rightOnly")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        pages.taggedMap("left") shouldBe snapshot.slot(0).asTagMap()
        pages.taggedMap("right") shouldBe snapshot.slot(1).asTagMap()
        // MintedLedger snapshot shape: `[advertised, counter]` (computenet-vvre)
        pages.taggedMap("ledger") shouldBe
            (snapshot.slot(2) as List<*>)[0].asMap().mapValues { setOf(it.value as Timestamp) }
        // "both" is live in all three, with three DIFFERENT tag sets — three
        // entries, never one collapsed one (Decision A)
        val bothEntries = pages.allEntries().filterIsInstance<TaggedEntry>().filter { it.element == "both" }
        bothEntries.map { it.subState }.toSet() shouldBe setOf("left", "right", "ledger")
        bothEntries.map { it.tags }.distinct().size shouldBe 3
        // ... and the ledger's tag is MINTED, so it borrows from neither side
        // (computenet-vvre; was asserted to be the union of the two sides')
        val ledgerTags = bothEntries.single { it.subState == "ledger" }.tags
        ledgerTags.size shouldBe 1
        ledgerTags.intersect(
            bothEntries.single { it.subState == "left" }.tags +
                bothEntries.single { it.subState == "right" }.tags,
        ).shouldBeEmpty()
    }

    @Test
    fun `JoinSetCell pages three sub-states and carries the mint counter on every page`() {
        val (left, right, cell) = equiJoin()

        left.add("k1:L", "k2:L")
        right.add("k1:R", "k2:R", "k3:R")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)
        val snapshot = cell.snapshot()

        pages.taggedMap("left") shouldBe snapshot.slot(0).asTagMap()
        pages.taggedMap("right") shouldBe snapshot.slot(1).asTagMap()
        // MintedLedger's snapshot is [advertised, counter]: one minted tag per pair
        pages.taggedMap("ledger") shouldBe
            (snapshot.slot(2) as List<*>)[0].asMap().mapValues { setOf(it.value as Timestamp) }
        // the ledger is keyed by the PAIR, not by the combined output
        pages.tagged("ledger").map { it.element }.all { it is Pair<*, *> } shouldBe true

        // Decision D: the rider is on page 1, on a mid-walk page and on the last
        val counter = (snapshot.slot(2) as List<*>)[1]
        pages.size shouldBeGreaterThan 2
        pages.forEach { it.attributes[OperatorPaging.MINT_COUNTER] shouldBe counter }
        // ... and it did not cost an entry slot: limit=1 still means one entry
        pages.forEach { it.entries.size shouldBeLessThanOrEqual 1 }
    }

    @Test
    fun `SemiJoinCell pages three sub-states, its ledger keyed by the left row`() {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(
            SemiJoinCell<String, String, String>(
                leftKey = { it.substringBefore(':') },
                rightKey = { it.substringBefore(':') },
            ),
        )
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()

        left.add("k1:L", "k2:L")
        right.add("k1:R")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        pages.taggedMap("left") shouldBe snapshot.slot(0).asTagMap()
        pages.taggedMap("right") shouldBe snapshot.slot(1).asTagMap()
        pages.taggedMap("ledger") shouldBe
            (snapshot.slot(2) as List<*>)[0].asMap().mapValues { setOf(it.value as Timestamp) }
        // only the matched left row is advertised, and it is a `String`, not a pair
        pages.tagged("ledger").map { it.element } shouldBe listOf("k1:L")
        pages.forEach {
            it.attributes[OperatorPaging.MINT_COUNTER] shouldBe (snapshot.slot(2) as List<*>)[1]
        }
    }

    @Test
    fun `QuorumSetCell pages every lane-element pair and then the ledger`() {
        val (a, b, cell) = quorum()

        a.add("shared", "onlyA")
        b.add("shared", "onlyB")
        rig.settle()

        val pages = rig.walk(cell, limit = 2)
        val snapshot = cell.snapshot()

        // lane sub-state: the snapshot is laneId -> (element -> tags); the walk
        // flattens it to (laneId, element) entries, losing nothing
        val walkedLanes = pages.tagged("lanes").associate { (it.lane to it.element) to it.tags }
        val snapshotLanes = snapshot.slot(0).asMap()
            .flatMap { (lane, laneState) -> laneState.asTagMap().map { (lane as UUID to it.key) to it.value } }
            .toMap()
        walkedLanes shouldBe snapshotLanes
        pages.taggedMap("ledger") shouldBe snapshot.slot(1).asTagMap()

        // "shared" is asserted by both lanes: two lane entries with the same
        // element, distinguished only by the lane (Decision F)
        pages.tagged("lanes").filter { it.element == "shared" }.map { it.lane }.toSet().size shouldBe 2
        // Decision D: the lane frontier rides every page
        val lanes = snapshot.slot(0).asMap().keys
        pages.forEach { (it.attributes[OperatorPaging.LANES] as List<*>).toSet() shouldBe lanes }
    }

    @Test
    fun `PresenceCountCell pages its lanes and not the derived count map`() {
        val a = rig.spawn(SetSource())
        val b = rig.spawn(SetSource())
        val cell = rig.spawn(PresenceCountCell<String>())
        a.feed(cell.inlet)
        b.feed(cell.inlet)
        rig.settle()

        a.add("shared", "onlyA")
        b.add("shared")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)

        val walkedLanes = pages.tagged("lanes").associate { (it.lane to it.element) to it.tags }
        val snapshotLanes = cell.snapshot().asMap()
            .flatMap { (lane, laneState) -> laneState.asTagMap().map { (lane as UUID to it.key) to it.value } }
            .toMap()
        walkedLanes shouldBe snapshotLanes
        pages.allEntries().map { it.subState }.toSet() shouldBe setOf("lanes")
        // the counts map is a rebuilt cache, not snapshot state — never paged
        pages.allEntries().filterIsInstance<KeyedEntry>().shouldBeEmpty()
    }

    // ------------------------------- the TaggedSetOperator family, one shape

    @Test
    fun `FilterCell FlatMapSetCell and CountCell page their one live sub-state`() {
        val filterSource = rig.spawn(SetSource())
        val flatMapSource = rig.spawn(SetSource())
        val countSource = rig.spawn(SetSource())
        val filter = rig.spawn(FilterCell<String>(predicate = { it.startsWith("keep") }))
        val flatMap = rig.spawn(FlatMapSetCell<String, String>(f = { listOf("$it-1", "$it-2") }))
        val count = rig.spawn(CountCell<String>())
        filterSource.feed(filter.inlet)
        flatMapSource.feed(flatMap.inlet)
        countSource.feed(count.inlet)
        rig.settle()

        filterSource.add("keep1", "keep2", "drop1")
        flatMapSource.add("in1", "in2")
        countSource.add("c1", "c2", "c3")
        rig.settle()

        rig.walk(filter, limit = 1).let { pages ->
            pages.taggedMap("live") shouldBe filter.snapshot().asTagMap()
            pages.tagged("live").map { it.element }.toSet() shouldBe setOf("keep1", "keep2")
        }
        rig.walk(flatMap, limit = 1).let { pages ->
            // the PREIMAGES, not f's outputs: the mapped set is recomputed, never stored
            pages.taggedMap("live") shouldBe flatMap.snapshot().asTagMap()
            pages.tagged("live").map { it.element }.toSet() shouldBe setOf("in1", "in2")
        }
        rig.walk(count, limit = 1).let { pages ->
            // not a scalar cell: its state is the whole element -> tag map
            pages.taggedMap("live") shouldBe count.snapshot().asTagMap()
            pages.tagged("live").size shouldBe 3
        }
    }

    // ------------------------------------------------------- the size bound

    @Test
    fun `limit binds every page, the cursor resumes, and even limit 1 visits every sub-state`() {
        val (left, right, cell) = intersection()
        left.add("a", "b", "c", "d")
        right.add("a", "b", "e")
        rig.settle()

        // 4 left + 3 right + 2 ledger = 9 entries across three sub-states
        val total = rig.walk(cell, limit = 200).allEntries().size
        total shouldBe 9

        for (limit in listOf(1, 2, total - 1, total, total + 1)) {
            val pages = rig.walk(cell, limit = limit)
            pages.forEach { it.entries.size shouldBeLessThanOrEqual limit }
            pages.dropLast(1).forEach { it.next shouldNotBe null }
            pages.last().next.shouldBeNull()
            pages.allEntries().size shouldBe total
            // every sub-state is reached however small the page
            pages.allEntries().map { it.subState }.toSet() shouldBe setOf("left", "right", "ledger")
            // and no entry is handed back twice
            pages.identities().distinct().size shouldBe total
        }
    }

    @Test
    fun `the advisory byte budget shortens pages without ever stalling a walk`() {
        val (left, right, cell) = intersection()
        left.add("a", "b", "c", "d", "e", "f")
        right.add("a", "b", "c")
        rig.settle()

        val pages = rig.walk(cell, limit = 200, byteBudget = 1)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty() shouldBe true } // always progresses
        pages.allEntries().size shouldBe 12 // 6 + 3 + 3
    }

    @Test
    fun `an empty cell answers one empty page and terminates`() {
        val (_, _, cell) = intersection()
        rig.settle()

        val page = rig.page(cell, StateRead(limit = 10))

        page.entries.shouldBeEmpty()
        page.next.shouldBeNull()
        page.caveats.shouldBeEmpty()
    }

    // ------------------------------------------------------------- fixtures

    private fun intersection(): Triple<SetSource, SetSource, IntersectSetCell<String>> {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(IntersectSetCell<String>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()
        return Triple(left, right, cell)
    }

    private fun equiJoin(): Triple<SetSource, SetSource, JoinSetCell<String, String, String, String>> {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(
            JoinSetCell<String, String, String, String>(
                leftKey = { it.substringBefore(':') },
                rightKey = { it.substringBefore(':') },
                combine = { a, b -> "$a+$b" },
            ),
        )
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()
        return Triple(left, right, cell)
    }

    private fun quorum(): Triple<SetSource, SetSource, QuorumSetCell<String>> {
        val a = rig.spawn(SetSource())
        val b = rig.spawn(SetSource())
        val cell = rig.spawn(QuorumSetCell.union<String>())
        a.feed(cell.inlet)
        b.feed(cell.inlet)
        rig.settle()
        return Triple(a, b, cell)
    }
}
