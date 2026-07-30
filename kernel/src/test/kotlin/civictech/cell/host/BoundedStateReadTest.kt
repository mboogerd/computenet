package civictech.cell.host

import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Owned
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.link.Interest
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * V1C-KERNEL: the bounded state read's paging contract, on its reference
 * implementation — `SetCell` behind [ManagedHost.readState].
 *
 * Everything asserted here is a sentence of [civictech.cell.StatePage]'s
 * contract made falsifiable: a walk's pages union to the fold's state at a
 * stable frontier, `limit` binds on every page, the cursor resumes, the final
 * page terminates the walk, and — the part a snapshot cannot promise — a walk
 * interleaved with real mutation degrades to the *documented smear* rather than
 * to a torn or duplicated read.
 *
 * The host is a [SimulationController] throughout so a "page" is an observable
 * event: the read is queued from the test thread and lands on a later
 * `runToIdle()`, never before, which is also the assertion that one page is one
 * scheduler task.
 */
class BoundedStateReadTest {

    // ---------------------------------------------------------------- rig

    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler())

    private fun spawn(cell: SetCell<String>): SetCell<String> =
        cell.also { host.managementInlet.call.spawn(it) }

    private fun populated(n: Int, prefix: String = "k"): SetCell<String> {
        val cell = SetCell<String>()
        repeat(n) { cell.inlet.call.add("$prefix${"%04d".format(it)}") }
        return spawn(cell)
    }

    /** One page, driven the way a real caller drives it: submit, let the host run, read. */
    private fun page(cell: SetCell<*>, request: StateRead): StatePage {
        val pending = host.readState(cell.ref, request)
        // routed, not executed inline — the copy happens on the cell's own
        // execution context, which has not run yet
        pending.isDone.shouldBeFalse()
        controller.runToIdle()
        val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        result.shouldBeInstanceOf<StateReadResult.Page>()
        return result.page
    }

    /** Walk to completion, one page per `runToIdle()`, with [between] applied after each page. */
    private fun walk(
        cell: SetCell<*>,
        limit: Int = 200,
        since: TagFrontier? = null,
        scope: Interest? = null,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val produced = page(
                cell,
                StateRead(cursor = cursor, limit = limit, since = since, scope = scope, byteBudget = byteBudget),
            )
            pages += produced
            cursor = produced.next
            between(pages.size)
            check(guard++ < WALK_GUARD) { "walk did not terminate within $WALK_GUARD pages" }
        } while (cursor != null)
        return pages
    }

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(pages: List<StatePage>): List<SetCell.SetStateEntry<String>> =
        pages.flatMap { it.entries }.filterIsInstance<SetCell.SetStateEntry<*>>()
            .map { it as SetCell.SetStateEntry<String> }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotAdds(cell: SetCell<String>): Map<String, Set<Timestamp>> =
        (cell.snapshot() as Map<String, Any>)["adds"] as Map<String, Set<Timestamp>>

    @Suppress("UNCHECKED_CAST")
    private fun snapshotDels(cell: SetCell<String>): Map<String, Set<Timestamp>> =
        (cell.snapshot() as Map<String, Any>)["dels"] as Map<String, Set<Timestamp>>

    // ------------------------------------------------------------- paging

    @Test
    fun `a walk at a stable frontier unions to exactly the snapshot's content`() {
        val cell = populated(450)
        cell.inlet.call.remove("k0007") // a tombstone, so `dels` is non-empty too

        val pages = walk(cell, limit = 100)

        // limit binds on every page, and only the final page ends the walk
        pages.forEach { it.entries.size shouldBeLessThan 101 }
        pages.dropLast(1).forEach { it.next shouldNotBe null }
        pages.last().next.shouldBeNull()

        val entries = entriesOf(pages)
        // no entry twice in one walk
        entries.map { it.element }.distinct().size shouldBe entries.size
        // union == snapshot content, tag for tag
        entries.associate { it.element to it.addTags } shouldBe snapshotAdds(cell)
        entries.filter { it.delTags.isNotEmpty() }
            .associate { it.element to it.delTags } shouldBe snapshotDels(cell)
        // the tombstoned element is still enumerable, and honestly not present
        entries.single { it.element == "k0007" }.present.shouldBeFalse()
        entries.single { it.element == "k0100" }.present.shouldBeTrue()
    }

    @Test
    fun `the frontier is exact at both ends of a quiet walk, and equal, so the union is a snapshot`() {
        val cell = populated(450)

        val pages = walk(cell, limit = 100)

        pages.size shouldBeGreaterThan 2
        // the verifiable-stability check StatePage documents: opening == closing
        pages.first().frontier shouldBe pages.last().frontier
        // nothing was elided and nothing was weakened on the endpoints
        pages.first().caveats.shouldBeEmpty()
        pages.last().caveats.shouldBeEmpty()
        pages.forEach { it.exclusivesElided shouldBe 0 }
        // cell-level state rides every page, not only the first
        val counter = (cell.snapshot() as Map<*, *>)["counter"]
        pages.forEach { it.attributes["counter"] shouldBe counter }
    }

    @Test
    fun `a mid-walk mutation smears the union rather than tearing or duplicating it`() {
        val cell = populated(400)
        val survivors = snapshotAdds(cell).keys.toSet()

        val pages = walk(cell, limit = 50, between = { pageNumber ->
            // real work on the fold between pages: new tags on new keys, and a
            // tombstone on a key the walk has already passed
            if (pageNumber == 1) {
                repeat(20) { cell.inlet.call.add("late$it") }
                cell.inlet.call.remove("k0000")
            }
        })

        val entries = entriesOf(pages)
        // never duplicated
        entries.map { it.element }.distinct().size shouldBe entries.size
        // every entry present for the whole walk is in the union
        entries.map { it.element } shouldContainAll survivors
        // entries added mid-walk MAY be missing — this implementation freezes the
        // enumeration order at walk start, so they are. Asserted as the documented
        // contract, not as a stricter promise.
        entries.map { it.element } shouldNotContain "late0"
        // the stamp told the caller: the frontier advanced, so this union is a
        // smear and not a snapshot
        pages.last().frontier shouldNotBe pages.first().frontier
        // and the intermediate pages said so rather than pretending to be current
        pages.drop(1).dropLast(1).forEach {
            it.caveats shouldContain ReadCaveat.STALE_FRONTIER
            it.frontier shouldBe pages.first().frontier
        }
        pages.first().caveats.shouldBeEmpty()
        pages.last().caveats.shouldBeEmpty()
    }

    @Test
    fun `a cursor whose key disappeared mid-walk resumes at the next key instead of restarting or throwing`() {
        val cell = populated(10)
        val first = page(cell, StateRead(limit = 3))
        first.entries.size shouldBe 3

        // the OR-set's own `remove` tombstones rather than deletes, so the only
        // way a key genuinely leaves this fold is a restore — which is also the
        // case that reorders the backing maps wholesale
        val adds = LinkedHashMap(snapshotAdds(cell))
        val dropped = "k0003" // the key the cursor is about to hand back
        adds.remove(dropped)
        cell.restore(HashMap(mapOf("adds" to HashMap(adds), "dels" to HashMap<String, Set<Timestamp>>(), "counter" to 10L)))

        val rest = mutableListOf<StatePage>()
        var cursor = first.next
        while (cursor != null) {
            val next = page(cell, StateRead(cursor = cursor, limit = 3))
            rest += next
            cursor = next.next
        }

        val walked = (first.entries + rest.flatMap { it.entries })
            .filterIsInstance<SetCell.SetStateEntry<*>>().map { it.element }
        walked shouldNotContain dropped
        // it continued from the next key: nothing before the cursor was replayed,
        // and every surviving key after it was still delivered
        walked.distinct().size shouldBe walked.size
        walked shouldContainAll listOf("k0004", "k0009")
    }

    // ----------------------------------------------------- the other bounds

    @Test
    fun `since narrows a walk to the tags beyond the frontier, and scope to the admitted keys`() {
        val cell = populated(20, prefix = "a")
        val afterFirstBatch = page(cell, StateRead(limit = 1000)).frontier
        afterFirstBatch shouldNotBe null
        repeat(5) { cell.inlet.call.add("b$it") }

        val incremental = entriesOf(walk(cell, limit = 4, since = afterFirstBatch))
        incremental.map { it.element }.toSet() shouldBe (0 until 5).map { "b$it" }.toSet()

        val scoped = entriesOf(walk(cell, limit = 4, scope = Prefix("b")))
        scoped.map { it.element }.toSet() shouldBe (0 until 5).map { "b$it" }.toSet()
    }

    @Test
    fun `a cell that declares neither bound is refused rather than answered wider than asked`() {
        val cell = spawn(SetCell())
        // SetCell declares both, so this pins the refusal path on a cell that does not
        val plain = PlainBoundedCell().also { host.managementInlet.call.spawn(it) }

        val refusedSince = host.readState(plain.ref, StateRead(since = TagFrontier(emptyMap())))
        val refusedScope = host.readState(plain.ref, StateRead(scope = Prefix("x")))
        // decided on the caller's thread: no scheduler round trip is spent
        refusedSince.isDone.shouldBeTrue()
        refusedScope.isDone.shouldBeTrue()
        refusedSince.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.SINCE_UNSUPPORTED)
        refusedScope.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.SCOPE_UNSUPPORTED)

        // Interest.Total is not a narrowing, so it is not a refusal
        val total = host.readState(plain.ref, StateRead(scope = Interest.Total))
        controller.runToIdle()
        total.get().shouldBeInstanceOf<StateReadResult.Page>()

        // and the declaring cell serves both
        cell.supportsSince.shouldBeTrue()
        cell.supportsScope.shouldBeTrue()
    }

    @Test
    fun `the byte budget shortens pages without ever stalling a walk`() {
        val cell = populated(60)

        // far below one page's worth of entries at limit=200
        val pages = walk(cell, limit = 200, byteBudget = 200)

        pages.size shouldBeGreaterThan 1
        pages.forEach { it.entries.isNotEmpty().shouldBeTrue() } // always progresses
        entriesOf(pages).size shouldBe 60
    }

    // -------------------------------------------------------------- ownership

    @Test
    fun `an exclusive element is described, never paged, and never consumed`() {
        val cell = SetCell<Any>()
        val owned = Owned("secret")
        cell.inlet.call.add("plain")
        cell.inlet.call.add(owned)
        host.managementInlet.call.spawn(cell)

        val pending = host.readState(cell.ref, StateRead(limit = 100))
        controller.runToIdle()
        val page = (pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page

        page.exclusivesElided shouldBe 1
        val descriptor = page.entries.filterIsInstance<ExclusiveEntry>().single()
        descriptor.key.shouldBeNull() // the element IS the exclusive value
        descriptor.typeName shouldBe Owned::class.java.name
        descriptor.identity shouldBe System.identityHashCode(owned)
        descriptor.disposition shouldBe ExclusiveEntry.Disposition.HELD
        // no exclusive value, and no copy of one, appears anywhere in the page
        page.entries.none { it is Owned<*> }.shouldBeTrue()
        page.entries.filterIsInstance<SetCell.SetStateEntry<*>>().single().element shouldBe "plain"

        // `take` was never called: it still succeeds, which it could not have
        // done had the read consumed the payload
        owned.take() shouldBe "secret"
        // and nothing was dead-lettered or discharged on the way
        host.supervisionAccounting().deadLetters shouldBe 0L
        host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 0L
    }

    // --------------------------------------------------------- one page, one task

    @Test
    fun `a walk against a simulated host advances exactly one page per runToIdle and never blocks the caller`() {
        val cell = populated(9)

        var cursor: Cursor? = null
        val sizes = mutableListOf<Int>()
        repeat(3) {
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = 3))
            // the caller is not blocked and the cell has not been entered
            pending.isDone.shouldBeFalse()
            controller.runToIdle()
            pending.isDone.shouldBeTrue()
            val produced = (pending.get() as StateReadResult.Page).page
            sizes += produced.entries.size
            cursor = produced.next
        }

        sizes shouldContainExactly listOf(3, 3, 3)
        cursor.shouldBeNull()
    }

    /** A [civictech.cell.BoundedStateful] cell that declares neither optional bound. */
    private class PlainBoundedCell(
        override val ref: civictech.cell.CellRef = civictech.cell.CellRef(java.util.UUID.randomUUID()),
    ) : civictech.cell.Cell, civictech.cell.BoundedStateful {
        override fun readBounded(request: StateRead) = StatePage(entries = emptyList())
        override fun snapshot(): Serializable = 0
        override fun restore(state: Serializable) = Unit
    }

    /** A tiny, honest [Interest] for scope tests — the algebra's arms are hash-slotted. */
    private data class Prefix(val prefix: String) : Interest {
        override fun overlaps(other: Interest) = true
        override fun admits(key: Any?) = (key as? String)?.startsWith(prefix) == true
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val WALK_GUARD = 1_000
    }
}
