package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.booleans.shouldBeFalse
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-CELLS: `InstanceSet`'s bounded read — the cleanest of the six, and the
 * one whose key type already has a genuine total order.
 *
 * `CellRef` is `(id, instanceId)`, so the walk sorts by that pair rather than
 * inheriting the `LinkedHashMap`'s insertion order — which `restore()` discards
 * anyway when it refills the table from the `HashMap` `snapshot()` produced.
 *
 * The other half of this class is inertness: this is replication-lattice state,
 * so a read must merge nothing, emit nothing, and — because they are
 * O(instances²) and are not state — run neither `overlapCount()` nor
 * `isDisjoint()`.
 */
class InstanceSetBoundedReadTest {

    private fun instanceRef(n: Int, instance: Long = 0L): CellRef =
        CellRef(UUID.fromString("00000000-0000-0000-0000-%012d".format(n)), instance)

    private fun populated(n: Int = 20): InstanceSet {
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        repeat(n) { set.assign(instanceRef(it + 1), Interest.Total, epoch = (it + 1).toLong()) }
        return set
    }

    private fun drive(
        set: InstanceSet,
        limit: Int,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = set.readBounded(StateRead(cursor = cursor, limit = limit, byteBudget = byteBudget))
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    private fun entries(pages: List<StatePage>) =
        pages.flatMap { it.entries }.filterIsInstance<InstanceSet.InstanceAssignmentEntry>()

    @Test
    fun `a walk unions to exactly the snapshot's table, respects limit, resumes and terminates`() {
        val set = populated(45)

        val pages = drive(set, limit = 10)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 11 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null }

        val walked = entries(pages)
        walked.map { it.ref }.distinct().size shouldBe walked.size
        walked.associate { it.ref to it.assignment } shouldBe set.entries()

        // no tag lane here: the Assignment epoch is a ROUTING epoch, not a tag
        // counter, so reporting it as a TagFrontier would be a category error
        pages.forEach { it.frontier.shouldBeNull() }
        pages.forEach { it.caveats.shouldBeEmpty() }
        pages.forEach { it.exclusivesElided shouldBe 0 }
        set.supportsSince.shouldBeFalse()
        set.supportsScope.shouldBeFalse()
    }

    @Test
    fun `a limit larger than the table produces a single terminal page`() {
        val set = populated(6)

        val pages = drive(set, limit = 500)

        pages.size shouldBe 1
        entries(pages).size shouldBe 6
        pages.single().next.shouldBeNull()
    }

    @Test
    fun `the walk order is by (id, instanceId), stable across walks and across a restore`() {
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        // deliberately assigned out of order, and with two instances of one id
        listOf(5 to 1L, 2 to 0L, 5 to 0L, 9 to 0L, 1 to 3L).forEach { (n, instance) ->
            set.assign(instanceRef(n, instance), Interest.Total, epoch = 1L)
        }

        val order = entries(drive(set, limit = 2)).map { it.ref }
        order shouldContainExactly order.sortedWith(compareBy({ it.id }, { it.instanceId }))
        entries(drive(set, limit = 2)).map { it.ref } shouldContainExactly order

        val restored = InstanceSet(CellRef(UUID.randomUUID()))
        restored.restore(set.snapshot())
        entries(drive(restored, limit = 2)).map { it.ref } shouldContainExactly order
    }

    @Test
    fun `a mid-walk gossip merge smears the union without duplicating an entry`() {
        val set = populated(30)
        val before = set.entries().keys.toSet()

        val pages = drive(set, limit = 6, between = { page ->
            if (page == 1) {
                // a peer's delta lands between two pages, through the real inlet
                set.deltaInlet.call.propagate(
                    AssignmentDelta(mapOf(instanceRef(99) to Assignment(Interest.Total, 5L)))
                )
            }
        })

        val walked = entries(pages)
        walked.map { it.ref }.distinct().size shouldBe walked.size
        walked.map { it.ref } shouldContainAll before
        // the late arrival is outside the frozen order, so it is absent —
        // the documented smear, not a stricter promise
        walked.map { it.ref } shouldNotContain instanceRef(99)
    }

    @Test
    fun `an entry removed from the table between pages is skipped, not restarted`() {
        val set = populated(12)
        val first = set.readBounded(StateRead(limit = 4))
        val dropped = entries(listOf(first)).map { it.ref }

        // the table shrinks under the in-flight cursor (only a restore can do
        // that here — the lattice itself is grow-only)
        val trimmed = InstanceSet(CellRef(UUID.randomUUID()))
        trimmed.restore(set.snapshot())
        val reduced = HashMap(set.entries()).also { it.remove(instanceRef(6)) }
        set.restore(reduced)

        val rest = mutableListOf<CellRef>()
        var cursor = first.next
        while (cursor != null) {
            val page = set.readBounded(StateRead(cursor = cursor, limit = 4))
            rest += entries(listOf(page)).map { it.ref }
            cursor = page.next
        }

        rest shouldNotContain instanceRef(6)
        rest.intersect(dropped.toSet()).shouldBeEmpty() // never restarted
        rest.distinct().size shouldBe rest.size
    }

    @Test
    fun `the byte budget shortens pages without stalling the walk`() {
        val set = populated(20)

        val pages = drive(set, limit = 500, byteBudget = 100)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty().shouldBeTrue() }
        entries(pages).size shouldBe 20
    }

    @Test
    fun `a full walk merges nothing, emits nothing and dead-letters nothing`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val set = populated(24)
        val emitted = AtomicInteger()
        set.outlet.subscribe(Use.fixed(Propagate<AssignmentDelta> { emitted.incrementAndGet() }, PortRef.generate()))

        host.managementInlet.call.spawn(set)
        controller.runToIdle()

        val tableBefore = set.entries()
        val overlapBefore = set.overlapCount()
        val deadLettersBefore = host.supervisionAccounting().deadLetters
        val emittedBefore = emitted.get()

        var cursor: Cursor? = null
        var pages = 0
        do {
            val pending = host.readState(set.ref, StateRead(cursor = cursor, limit = 5))
            controller.runToIdle()
            val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.shouldBeInstanceOf<StateReadResult.Page>()
            cursor = result.page.next
            pages++
            set.entries() shouldBe tableBefore
        } while (cursor != null)

        pages shouldBeGreaterThan 1
        set.entries() shouldBe tableBefore
        set.overlapCount() shouldBe overlapBefore
        emitted.get() shouldBe emittedBefore
        host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
        set.outlet.targetMisses shouldBe 0L
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
