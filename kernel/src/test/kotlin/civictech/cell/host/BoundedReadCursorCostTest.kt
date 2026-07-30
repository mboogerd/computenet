package civictech.cell.host

import civictech.cell.Cursor
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1C-KERNEL: **the cursor resumes in O(page), not O(n)** — the hard bar the C7
 * measurement gate attached to this ticket.
 *
 * `V1C-BENCH`'s paging counterfactual used a `List<Int>` stand-in with an O(1)
 * seek, and on that basis the gate accepted a 1.7–2.4× total-work premium in
 * exchange for removing ~85–99% of the live-traffic stall a whole-state copy
 * imposes. A cursor that rescanned the cell's tag map from the start on each
 * page would turn that premium into O(n²) and invalidate the trade — so it is
 * not enough for the resume to be *correct*, it has to be *cheap*, and that has
 * to be checkable rather than asserted in a KDoc.
 *
 * The observable: a key that counts every `hashCode`/`equals` the fold performs
 * on it. A page's key work is then directly measurable, and the two shapes are
 * an order of magnitude apart at the sizes used here — the last page of a
 * 40-page walk costs O(limit) under a frozen key order and O(n) under a rescan.
 *
 * This is a correctness test with a cost assertion, not a benchmark: the bounds
 * are generous multiples of the structural cost, so they are insensitive to JIT,
 * GC and machine load, and only a change of *asymptotics* can break them.
 */
class BoundedReadCursorCostTest {

    private val controller = SimulationController()
    private val host = ManagedHost(scheduler = controller.scheduler())

    /**
     * A set element that counts the fold's key work. Both `hashCode` and
     * `equals` are counted, because a map lookup does one of each: what is being
     * measured is "how many keys did this page touch", and a rescanning cursor
     * touches every key before the cursor's own.
     */
    private data class CountingKey(val id: Int) : Serializable {
        override fun hashCode(): Int {
            touches.incrementAndGet()
            return id
        }

        override fun equals(other: Any?): Boolean {
            touches.incrementAndGet()
            return other is CountingKey && other.id == id
        }
    }

    @Test
    fun `resuming a walk costs O(page) per page, not O(n) — the C7 bar`() {
        val cell = SetCell<CountingKey>()
        repeat(N) { cell.inlet.call.add(CountingKey(it)) }
        host.managementInlet.call.spawn(cell)

        touches.set(0)

        val perPage = mutableListOf<Int>()
        var entries = 0
        var cursor: Cursor? = null
        do {
            val before = touches.get()
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = LIMIT))
            controller.runToIdle()
            val page = (pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page
            perPage += touches.get() - before
            entries += page.entries.size
            cursor = page.next
        } while (cursor != null)

        // the walk really covered the whole set, in the expected number of pages
        entries shouldBe N
        perPage.size shouldBe N / LIMIT
        cursor.shouldBeNull()

        // the counter is live — a page genuinely touches one key per entry
        // (a `dels` probe on an empty map hashes nothing, and a `HashMap` hit on
        // the identical key instance short-circuits before `equals`)
        perPage.first() shouldBeGreaterThan (LIMIT - 1)

        // *the* assertion: the last page of a 40-page walk is no more expensive
        // than the first. A rescan-from-the-start cursor would make it ~N/LIMIT
        // times worse.
        withClue("per-page key touches: $perPage") {
            perPage.last() shouldBeLessThan PER_PAGE_CEILING
            perPage.max() shouldBeLessThan PER_PAGE_CEILING
            // and therefore the whole walk is O(n), not O(n²)
            perPage.sum() shouldBeLessThan WALK_CEILING
        }
    }

    @Test
    fun `a resumed page is as cheap as a fresh one at the same offset`() {
        // The same claim from the other side: cost tracks the page's own size,
        // not how far into the walk it is. Stated separately because it is the
        // property `V1C-CELLS`/`V1C-OPS` must preserve when they copy the
        // pattern onto their own state layouts.
        val cell = SetCell<CountingKey>()
        repeat(N) { cell.inlet.call.add(CountingKey(it)) }
        host.managementInlet.call.spawn(cell)

        fun pageCost(cursor: Cursor?): Pair<Int, Cursor?> {
            val before = touches.get()
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = LIMIT))
            controller.runToIdle()
            val page = (pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page
            return (touches.get() - before) to page.next
        }

        touches.set(0)
        var cursor: Cursor? = null
        var firstCost = 0
        var lateCost = 0
        for (index in 0 until N / LIMIT) {
            val (cost, next) = pageCost(cursor)
            if (index == 1) firstCost = cost
            if (index == N / LIMIT - 1) lateCost = cost
            cursor = next
        }

        withClue("second page cost $firstCost, last page cost $lateCost") {
            lateCost shouldBeLessThan firstCost * 4
        }
    }

    private companion object {
        const val N = 4_000
        const val LIMIT = 100

        /**
         * Structural cost of one page is one to four key touches per entry (a
         * lookup in `adds` and one in `dels`, each a `hashCode` and possibly an
         * `equals`), so at most ~4·LIMIT. The ceiling is a generous multiple of
         * that, and no O(n) rescan can fit under it at N = 4,000: such a cursor's
         * last page alone touches ~N keys.
         */
        const val PER_PAGE_CEILING = 12 * LIMIT

        /** ≤ ~4·N structurally; a rescan costs ~N·(N/LIMIT)/2 ≈ 20·N here. */
        const val WALK_CEILING = 12 * N

        const val TIMEOUT_MS = 30_000L

        val touches = AtomicInteger()
    }
}
