package civictech.cell.data.op

import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-OPS: the edges of a composite cell's bounded read — ownership
 * (V1C-KERNEL Decision 3), the unbounded accumulator (Decision G), the
 * three-level nested cursor (Decision F), wave neutrality, and the
 * `snapshot()`/`restore()` seam this ticket is forbidden to disturb.
 */
class OperatorBoundedReadEdgesTest {

    private val rig = OpRig()

    // ----------------------------------------------------------- ownership

    @Test
    fun `an exclusive element is described in every sub-state that holds it, never paged, never consumed`() {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(IntersectSetCell<Any>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()

        val owned = Owned("secret")
        left.add("plain", owned)
        right.add("plain", owned) // the SAME instance, so it is live in all three sub-states
        rig.settle()

        val pages = rig.walk(cell, limit = 2)

        // one elided entry per sub-state that holds it: left, right and ledger
        pages.sumOf { it.exclusivesElided } shouldBe 3
        val elided = pages.tagged("left").single { it.element is ExclusiveEntry }
        (elided.element as ExclusiveEntry).typeName shouldBe Owned::class.java.name
        elided.element.identity shouldBe System.identityHashCode(owned)
        elided.element.disposition shouldBe ExclusiveEntry.Disposition.HELD
        // the descriptor rides INSIDE the labelled entry, so the sub-state that
        // held the exclusive is still knowable (V1C-OPS's documented deviation
        // from SetCell's bare ExclusiveEntry)
        pages.tagged("ledger").single { it.element is ExclusiveEntry }.subState shouldBe "ledger"
        // no exclusive value, and no copy of one, anywhere in any page
        pages.allEntries().none { it is TaggedEntry && it.element is Owned<*> }.shouldBeTrue()
        // the non-exclusive element came through untouched
        pages.tagged("left").single { it.element == "plain" }.tags.isNotEmpty().shouldBeTrue()

        // `take` was never called: it still succeeds, which it could not have
        // done had the read consumed the payload
        owned.take() shouldBe "secret"
        host().supervisionAccounting().deadLetters shouldBe 0L
        host().supervisionAccounting().parkedDrainedOnTeardown shouldBe 0L
    }

    @Test
    fun `an exclusive map VALUE is described and its key still names the entry`() {
        val left = rig.spawn(MapSource())
        val right = rig.spawn(MapSource())
        val cell = rig.spawn(JoinCell<String, Any, Any>())
        left.feed(cell.left)
        right.feed(cell.right)
        rig.settle()

        val owned = Owned("payload")
        left.put("k" to owned, "plain" to "value")
        rig.settle()

        val pages = rig.walk(cell, limit = 5)

        pages.sumOf { it.exclusivesElided } shouldBe 1
        val entry = pages.keyed("left").single { it.key == "k" }
        val descriptor = entry.value as ExclusiveEntry
        descriptor.key shouldBe "k" // the key is not the exclusive, so it names the entry
        descriptor.typeName shouldBe Owned::class.java.name
        pages.allEntries().none { it is KeyedEntry && it.value is Owned<*> }.shouldBeTrue()
        pages.keyed("left").single { it.key == "plain" }.value shouldBe "value"

        owned.take() shouldBe "payload"
        host().supervisionAccounting().deadLetters shouldBe 0L
    }

    // --------------------------------------- Decision G: the whole accumulator

    @Test
    fun `a non-invertible accumulator larger than the byte budget rides whole, on a valid page`() {
        val source = rig.spawn(SetSource())
        val cell = rig.spawn(
            GroupByCell(
                keyFn = { _: String -> "one" }, // a single group, so the accumulator is the whole support
                aggregator = Aggregators.collectToSet<String>(),
            ),
        )
        source.feed(cell.inlet)
        rig.settle()

        val members = (0 until 200).map { "member-${"%03d".format(it)}" }
        source.add(*members.toTypedArray())
        rig.settle()

        // byteBudget far below one accumulator's worth; limit still binds
        val pages = rig.walk(cell, limit = 3, byteBudget = 1)

        pages.forEach { it.entries.size shouldBeLessThanOrEqual 3 }
        pages.last().next.shouldBeNull()
        val group = pages.groups("groups").single()
        group.count shouldBe 200
        // whole, not split, not elided, not summarized: the advisory budget is
        // simply exceeded, which is what "advisory" means
        @Suppress("UNCHECKED_CAST")
        (group.accumulator as Set<String>) shouldBe members.toSet()
        // and it still equals snapshot()'s content for that group
        val fromSnapshot = cell.snapshot().slot(1).asMap().getValue("one") as List<*>
        listOf(group.count, group.accumulator) shouldBe fromSnapshot.toList()
    }

    // --------------------------------------- Decision F: the nested cursor

    @Test
    fun `a quorum walk visits every lane-element pair once, resumes inside a lane, then reaches the ledger`() {
        val a = rig.spawn(SetSource())
        val b = rig.spawn(SetSource())
        val cell = rig.spawn(QuorumSetCell.union<String>())
        a.feed(cell.inlet)
        b.feed(cell.inlet)
        rig.settle()

        a.add("x", "shared")
        b.add("shared", "z")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)
        // lane order comes from the rider, which carries the live lane sequence.
        // `PresenceLanes.snapshot()` is a `HashMap`, so the snapshot's own key
        // order is hash order and says nothing about enumeration — one more
        // reason the walk imposes an order rather than inheriting one.
        val lanes = pages.first().attributes[OperatorPaging.LANES] as List<*>
        lanes.size shouldBe 2

        // four (lane, element) pairs then three ledger entries — every pair
        // exactly once, and `shared` twice because two lanes assert it
        pages.identities() shouldContainExactly listOf(
            Triple("lanes", lanes[0], "x"),
            Triple("lanes", lanes[0], "shared"),
            Triple("lanes", lanes[1], "shared"),
            Triple("lanes", lanes[1], "z"),
            Triple("ledger", null, "x"),
            Triple("ledger", null, "shared"),
            Triple("ledger", null, "z"),
        )
        // the resume after page 1 landed back INSIDE lane 0, not at the head of
        // lane 1 and not at the head of the sub-state
        (pages[1].entries.single() as TaggedEntry).lane shouldBe lanes[0]
        // each lane reports its OWN tags for `shared`, not the cross-lane union
        val sharedPerLane = pages.tagged("lanes").filter { it.element == "shared" }
        sharedPerLane.map { it.tags }.distinct().size shouldBe 2
        // ... while the ledger holds the ONE tag minted when the element first
        // met the threshold (advertise-once-then-idempotent, RS-5.3), which is
        // exactly the divergence a walk must show rather than paper over by
        // recomputing.
        //
        // computenet-s6l2 inverted this assertion. It used to require the
        // ledger's tags to be a SUBSET of the per-lane union
        // (`sharedPerLane.flatMap { it.tags }.toSet() shouldContainAll
        // ledgerTags`) — i.e. it encoded the very borrowing this bead removed,
        // where `AdvertisedLedger` re-advertised the lanes' own input tags. A
        // borrowed tag is not this cell's to delete, and deleting it on exit
        // retracted a reconvergent `UnionSetCell`'s still-live direct
        // contribution (QuorumDiamondTagTest). The flip to disjointness is
        // therefore a STRENGTHENING, not a weakening: subset-of-the-lanes was
        // satisfiable only by the defective policy, while disjointness is
        // falsifiable — restore `AdvertisedLedger` in `QuorumSetCell` and this
        // line fails.
        val ledgerTags = pages.tagged("ledger").single { it.element == "shared" }.tags
        ledgerTags.size shouldBe 1
        (sharedPerLane.flatMap { it.tags }.toSet() intersect ledgerTags) shouldBe emptySet<Timestamp>()
    }

    @Test
    fun `an open lane asserting nothing is still reported, as a rider rather than an entry`() {
        val a = rig.spawn(SetSource())
        val silent = rig.spawn(SetSource())
        val cell = rig.spawn(QuorumSetCell.union<String>())
        a.feed(cell.inlet)
        silent.feed(cell.inlet) // opens a lane and never asserts anything
        rig.settle()
        a.add("x")
        rig.settle()

        val pages = rig.walk(cell, limit = 1)

        // the empty lane contributes no entry — it has none — but `n` still
        // counts it, so the lane frontier rides every page as an attribute
        pages.tagged("lanes").map { it.lane }.distinct().size shouldBe 1
        pages.forEach { (it.attributes[OperatorPaging.LANES] as List<*>).size shouldBe 2 }
        (pages.first().attributes[OperatorPaging.LANES] as List<*>).toSet() shouldBe
            cell.snapshot().slot(0).asMap().keys
    }

    // ------------------------------------------------------ wave neutrality

    @Test
    fun `a full walk of an operator cell moves no wave counter, fires no tap and emits no delta`() {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val cell = rig.spawn(IntersectSetCell<String>())
        left.feed(cell.left)
        right.feed(cell.right)

        val tapFired = AtomicInteger()
        cell.outlet.tap(Use.fixed(Propagate<SetDelta<String>> { tapFired.incrementAndGet() }, PortRef.generate()))
        rig.settle()

        left.add("a", "b", "c", "d")
        right.add("a", "b")
        rig.settle()

        val waveBefore = cell.outlet.waveState()
        val tapBefore = tapFired.get()
        val deadLettersBefore = host().supervisionAccounting().deadLetters

        var pages = 0
        var cursor = rig.page(cell, StateRead(limit = 2)).also { pages++ }.next
        while (cursor != null) {
            val page = rig.page(cell, StateRead(cursor = cursor, limit = 2))
            pages++
            cursor = page.next
            // checked per page, not only at the end: a walk is many scheduler
            // tasks and each one must be neutral
            cell.outlet.waveState() shouldBe waveBefore
            tapFired.get() shouldBe tapBefore
        }

        pages shouldBeGreaterThan 2
        cell.outlet.waveState() shouldBe waveBefore
        tapFired.get() shouldBe tapBefore
        host().supervisionAccounting().deadLetters shouldBe deadLettersBefore
        // no `at`-style targeted answer either: the read never touched the outlet
        cell.outlet.targetMisses shouldBe 0L
    }

    @Test
    fun `another cell's cursor is refused as a failed read, never indexed off the end`() {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val intersect = rig.spawn(IntersectSetCell<String>()) // three sub-states
        val join = rig.spawn(JoinCell<String, String, String>()) // two sub-states
        left.feed(intersect.left)
        right.feed(intersect.right)
        rig.settle()
        left.add("a", "b", "c")
        right.add("a")
        rig.settle()

        val foreign = rig.page(intersect, StateRead(limit = 1)).next
        val result = rig.host.readState(join.ref, StateRead(cursor = foreign, limit = 1))
        rig.settle()

        // a diagnostic read never turns a broken caller into a broken cell: it is
        // a named refusal, not an exception and not a wrong page
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.READ_FAILED)
    }

    // ----------------------------------------- the seam this ticket must not move

    @Test
    fun `snapshot and restore still round-trip after a walk, on every three-sub-state cell`() {
        val left = rig.spawn(SetSource())
        val right = rig.spawn(SetSource())
        val intersect = rig.spawn(IntersectSetCell<String>())
        val joinLeft = rig.spawn(SetSource())
        val joinRight = rig.spawn(SetSource())
        val join = rig.spawn(
            JoinSetCell<String, String, String, String>(
                leftKey = { it.substringBefore(':') },
                rightKey = { it.substringBefore(':') },
                combine = { a, b -> "$a+$b" },
            ),
        )
        left.feed(intersect.left)
        right.feed(intersect.right)
        joinLeft.feed(join.left)
        joinRight.feed(join.right)
        rig.settle()
        left.add("a", "b")
        right.add("b", "c")
        joinLeft.add("k:L")
        joinRight.add("k:R")
        rig.settle()

        val intersectBefore = intersect.snapshot()
        val joinBefore = join.snapshot()
        rig.walk(intersect, limit = 1)
        rig.walk(join, limit = 1)

        // a read changed nothing: snapshot() is byte-for-byte what it was
        intersect.snapshot() shouldBe intersectBefore
        join.snapshot() shouldBe joinBefore

        // ... and the restore seam drain/migration/promotion depends on still works
        val restoredIntersect = IntersectSetCell<String>().also { it.restore(intersectBefore) }
        restoredIntersect.snapshot() shouldBe intersectBefore
        val restoredJoin = JoinSetCell<String, String, String, String>(
            leftKey = { it.substringBefore(':') },
            rightKey = { it.substringBefore(':') },
            combine = { a, b -> "$a+$b" },
        ).also { it.restore(joinBefore) }
        restoredJoin.snapshot() shouldBe joinBefore
    }

    private fun host() = rig.host
}
