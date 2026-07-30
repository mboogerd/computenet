package civictech.cell.partition

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-CELLS: `ShardCell`'s bounded read — the composite case.
 *
 * A shard's recoverable state is the triple `(TagState, interest,
 * assignedEpoch)` (`[24-SHARD-01]`), and two thirds of it is not enumerable at
 * all: it is context every page needs. `interest` and `assignedEpoch` therefore
 * ride `StatePage.attributes` on **every** page, because `assign` can run
 * between two pages of a walk — a consumer holding page 1's interest and page
 * 7's entries would attribute entries to the wrong key range at the wrong
 * routing epoch, a partition-membership claim that was never true.
 *
 * The rest of this class is inertness: `[24-SHARD-03]`'s `rebuildFrom` reads the
 * same two fields the bounded read reads, and must see them unchanged; a read
 * must shed nothing (`[24-SHARD-04]`), emit nothing, and never take the
 * `baselineTo` pull path this cell also serves.
 */
class ShardCellBoundedReadTest {

    private val origin: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f00d")
    private var tagCounter = 0L

    private fun shard(interest: Interest = Interest.Total): ShardCell<String> =
        ShardCell(CellRef(UUID.randomUUID()), keyFn = { it }, initialInterest = interest)

    private fun route(cell: ShardCell<String>, vararg elements: String) {
        elements.forEach {
            cell.routeInlet.call.propagate(
                RoutedCommand(0L, SetDelta(adds = mapOf(it to setOf(Timestamp(origin, ++tagCounter)))))
            )
        }
    }

    private fun populated(n: Int, prefix: String = "k"): ShardCell<String> =
        shard().also { cell -> route(cell, *(0 until n).map { "$prefix${"%03d".format(it)}" }.toTypedArray()) }

    private fun drive(
        cell: ShardCell<String>,
        limit: Int,
        since: TagFrontier? = null,
        scope: Interest? = null,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val page = cell.readBounded(
                StateRead(cursor = cursor, limit = limit, since = since, scope = scope, byteBudget = byteBudget)
            )
            pages += page
            cursor = page.next
            between(pages.size)
            check(guard++ < 10_000) { "walk at limit=$limit did not terminate" }
        } while (cursor != null)
        return pages
    }

    private fun entries(pages: List<StatePage>): List<SetCell.SetStateEntry<String>> =
        pages.flatMap { it.entries }.filterIsInstance<SetCell.SetStateEntry<*>>()
            .map {
                @Suppress("UNCHECKED_CAST")
                it as SetCell.SetStateEntry<String>
            }

    @Test
    fun `a walk unions to exactly the tag-state half of the triple, tag for tag`() {
        val cell = populated(150)

        val pages = drive(cell, limit = 40)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.entries.size shouldBeLessThan 41 }
        pages.last().next.shouldBeNull()
        pages.dropLast(1).forEach { it.next shouldNotBe null }

        val walked = entries(pages)
        walked.map { it.element }.distinct().size shouldBe walked.size
        walked.associate { it.element to it.addTags } shouldBe cell.contents().adds
        // this shard's ledger retains no tombstones, so del tags are always empty
        walked.forEach { it.delTags.shouldBeEmpty() }
        walked.forEach { it.present.shouldBeTrue() }
    }

    @Test
    fun `a limit larger than the shard produces a single terminal page`() {
        val cell = populated(8)

        val pages = drive(cell, limit = 500)

        pages.size shouldBe 1
        entries(pages).size shouldBe 8
        pages.single().next.shouldBeNull()
    }

    @Test
    fun `interest and assignedEpoch ride every page, identically, over an unchanging shard`() {
        val cell = shard(Prefix("k"))
        route(cell, *(0 until 60).map { "k${"%03d".format(it)}" }.toTypedArray())

        val pages = drive(cell, limit = 9)

        pages.size shouldBeGreaterThan 3
        pages.forEach {
            it.attributes["interest"] shouldBe cell.interest
            it.attributes["assignedEpoch"] shouldBe cell.assignedEpoch
        }
        pages.map { it.attributes["interest"] }.distinct().size shouldBe 1
        pages.map { it.attributes["assignedEpoch"] }.distinct().size shouldBe 1
    }

    @Test
    fun `a walk that straddles an assign shows the change on the pages after it, never mixing key ranges silently`() {
        val cell = shard(Interest.Total)
        route(cell, *(0 until 40).map { "k${"%03d".format(it)}" }.toTypedArray())
        route(cell, *(0 until 10).map { "z${"%03d".format(it)}" }.toTypedArray())

        val interestBefore = cell.interest
        val pages = drive(cell, limit = 8, between = { page ->
            // a repartition lands between two pages: the shard sheds every `z`
            if (page == 1) cell.assign(Prefix("k"), epoch = 7L)
        })

        val early = pages.first()
        val late = pages.last()
        early.attributes["interest"] shouldBe interestBefore
        early.attributes["assignedEpoch"] shouldBe 0L
        late.attributes["interest"] shouldBe Prefix("k")
        late.attributes["assignedEpoch"] shouldBe 7L
        withClue("a consumer must be able to see that the walk straddled a repartition") {
            pages.map { it.attributes["interest"] }.distinct().size shouldBe 2
        }
        // the shed elements are skipped rather than reported under the new interest
        entries(pages).map { it.element }.filter { it.startsWith("z") }.size shouldBeLessThan 10
    }

    @Test
    fun `reads disturb no repartition state - rebuildFrom sees exactly the routing table it would have seen`() {
        val cell = shard(Prefix("k"))
        route(cell, *(0 until 30).map { "k${"%03d".format(it)}" }.toTypedArray())
        cell.assign(Prefix("k"), epoch = 3L)

        val interestBefore = cell.interest
        val epochBefore = cell.assignedEpoch
        val membershipBefore = cell.membership().toSet() // membership() is a live view of the ledger
        val contentsBefore = cell.contents()

        val emitted = AtomicInteger()
        cell.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { emitted.incrementAndGet() }, PortRef.generate()))

        repeat(4) { drive(cell, limit = 7) }

        cell.interest shouldBe interestBefore
        cell.assignedEpoch shouldBe epochBefore
        cell.membership() shouldBe membershipBefore
        cell.contents() shouldBe contentsBefore
        emitted.get() shouldBe 0
        cell.outlet.targetMisses shouldBe 0L

        // and the rebuildFrom-style read `[24-SHARD-03]` performs — "asking each
        // restored shard what interest and epoch it holds" — yields the same
        // routing table it would have without the walks
        val router = PartitionedShardSet<String>(totalSlots = 4, keyFn = { it }, registry = LocationRegistry())
        router.rebuildFrom(listOf(cell))
        router.routingEpoch shouldBe epochBefore
    }

    @Test
    fun `the frontier is real, exact at both ends of a quiet walk, and stale-flagged in between`() {
        val cell = populated(90)

        val pages = drive(cell, limit = 15)

        pages.size shouldBeGreaterThan 3
        pages.forEach { it.frontier.shouldNotBeNull() }
        pages.first().frontier shouldBe pages.last().frontier // equal endpoints: a quiet walk
        pages.first().caveats.shouldBeEmpty()
        pages.last().caveats.shouldBeEmpty()
        pages.drop(1).dropLast(1).forEach {
            it.caveats shouldContain ReadCaveat.STALE_FRONTIER
            it.frontier shouldBe pages.first().frontier
        }
    }

    @Test
    fun `a mid-walk merge advances the closing frontier and smears the union`() {
        val cell = populated(60)
        val survivors = cell.membership().toSet() // membership() is a live view of the ledger

        val pages = drive(cell, limit = 12, between = { page ->
            if (page == 1) route(cell, "zz-late-0", "zz-late-1")
        })

        pages.last().frontier shouldNotBe pages.first().frontier
        val walked = entries(pages)
        walked.map { it.element }.distinct().size shouldBe walked.size
        walked.map { it.element } shouldContainAll survivors
        walked.map { it.element } shouldNotContain "zz-late-0"
    }

    @Test
    fun `the enumeration order is imposed - stable across walks and across a snapshot-restore round trip`() {
        val cell = populated(35)

        val order = entries(drive(cell, limit = 6)).map { it.element }
        order shouldContainExactly order.sorted()
        entries(drive(cell, limit = 6)).map { it.element } shouldContainExactly order

        // restore rebuilds the ledger from a HashMap; sorting is what keeps the
        // restored shard walking identically to the one that checkpointed
        val restored = shard()
        restored.restore(cell.snapshot())
        entries(drive(restored, limit = 6)).map { it.element } shouldContainExactly order
        restored.interest shouldBe cell.interest
        restored.assignedEpoch shouldBe cell.assignedEpoch
    }

    @Test
    fun `an element shed between pages is skipped, and the walk never restarts or throws`() {
        val cell = shard(Interest.Total)
        route(cell, *(0 until 12).map { "k${"%03d".format(it)}" }.toTypedArray())

        val first = cell.readBounded(StateRead(limit = 4))
        val alreadyPaged = entries(listOf(first)).map { it.element }
        // shed everything from k006 up — a real repartition, mid-walk
        cell.assign(BelowPrefix("k006"), epoch = 2L)

        val rest = mutableListOf<String>()
        var cursor = first.next
        while (cursor != null) {
            val page = cell.readBounded(StateRead(cursor = cursor, limit = 4))
            rest += entries(listOf(page)).map { it.element }
            cursor = page.next
        }

        rest shouldNotContain "k006"
        rest.intersect(alreadyPaged.toSet()).shouldBeEmpty() // never restarted
        rest.distinct().size shouldBe rest.size
    }

    @Test
    fun `since and scope are honoured, using the same predicates the pull reply uses`() {
        val cell = shard(Interest.Total)
        cell.supportsSince.shouldBeTrue()
        cell.supportsScope.shouldBeTrue()
        route(cell, *(0 until 10).map { "a$it" }.toTypedArray())
        val afterFirstBatch = cell.readBounded(StateRead(limit = 1000)).frontier
        route(cell, *(0 until 4).map { "b$it" }.toTypedArray())

        entries(drive(cell, limit = 3, since = afterFirstBatch)).map { it.element }.toSet() shouldBe
            (0 until 4).map { "b$it" }.toSet()
        entries(drive(cell, limit = 3, scope = Prefix("b"))).map { it.element }.toSet() shouldBe
            (0 until 4).map { "b$it" }.toSet()
    }

    @Test
    fun `an exclusive element is described, never paged, and never consumed`() {
        val cell: ShardCell<Any> = ShardCell(CellRef(UUID.randomUUID()), keyFn = { it }, initialInterest = Interest.Total)
        val owned = Owned("secret")
        cell.routeInlet.call.propagate(
            RoutedCommand(0L, SetDelta(adds = mapOf<Any, Set<Timestamp>>("plain" to setOf(Timestamp(origin, 1)))))
        )
        cell.routeInlet.call.propagate(
            RoutedCommand(0L, SetDelta(adds = mapOf<Any, Set<Timestamp>>(owned to setOf(Timestamp(origin, 2)))))
        )

        val page = cell.readBounded(StateRead(limit = 100))

        page.exclusivesElided shouldBe 1
        val descriptor = page.entries.filterIsInstance<ExclusiveEntry>().single()
        descriptor.key.shouldBeNull() // the element IS the exclusive value
        descriptor.typeName shouldBe Owned::class.java.name
        descriptor.identity shouldBe System.identityHashCode(owned)
        page.entries.none { it is Owned<*> }.shouldBeTrue()
        owned.take() shouldBe "secret"
    }

    @Test
    fun `a host-routed walk emits nothing and dead-letters nothing`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = populated(24)
        val emitted = AtomicInteger()
        cell.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { emitted.incrementAndGet() }, PortRef.generate()))
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val deadLettersBefore = host.supervisionAccounting().deadLetters
        val membershipBefore = cell.membership().toSet() // membership() is a live view of the ledger

        var cursor: Cursor? = null
        var pages = 0
        do {
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = 5))
            controller.runToIdle()
            val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.shouldBeInstanceOf<StateReadResult.Page>()
            cursor = result.page.next
            pages++
            cell.membership() shouldBe membershipBefore
        } while (cursor != null)

        pages shouldBeGreaterThan 1
        emitted.get() shouldBe 0
        host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
    }

    /** A tiny, honest [Interest] over string keys — the shipped arms are hash-slotted. */
    private data class Prefix(val prefix: String) : Interest {
        override fun overlaps(other: Interest) = true
        override fun admits(key: Any?) = (key as? String)?.startsWith(prefix) == true
    }

    /** Admits keys strictly below [bound] — a shed that leaves the walk's early pages alone. */
    private data class BelowPrefix(val bound: String) : Interest {
        override fun overlaps(other: Interest) = true
        override fun admits(key: Any?) = (key as? String)?.let { it < bound } == true
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
