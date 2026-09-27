package civictech.cell.host

import civictech.cell.Cursor
import civictech.cell.KeyBound
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
 *
 * **BS-35 (feature `computenet-83vd6`, `[KAGG-R-26]`).** A key-bounded walk on
 * `MapCell` is checked beside the unbounded control above, against the same
 * two constants. `[KAGG-R-29]` forbids a sublinear-seek claim: no `MapCell`
 * here is `NavigableMap`-backed, so a bound reduces *answered* entries, not
 * *examined* keys, and the bar below is a no-regression bar against the
 * unbounded walk on the same cell — never a claim that the bound is faster.
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

    /**
     * A `Comparable` counting key (BS-35): counts `hashCode`, `equals` **and
     * `compareTo`** into [touches]. `EntryOrder`'s natural-order branch
     * (`BoundedWalk.kt`, same-runtime-class `Comparable` keys) returns as soon
     * as `compareTo` is non-zero and never falls through to `hashCode` — so an
     * uncounted `compareTo` would make the walk's O(n log n) open-time sort
     * pass on `MapCell` invisible to this file's counter, hiding exactly the
     * pass this test exists to keep honest. `hashCode`/`equals` are still
     * counted for the per-page map lookups (`MapCell.readBounded`'s
     * `state.containsKey`/`state[key]`), the same as [CountingKey] above.
     */
    private data class CountingComparableKey(val id: Int) : Comparable<CountingComparableKey>, Serializable {
        override fun hashCode(): Int {
            touches.incrementAndGet()
            return id
        }

        override fun equals(other: Any?): Boolean {
            touches.incrementAndGet()
            return other is CountingComparableKey && other.id == id
        }

        override fun compareTo(other: CountingComparableKey): Int {
            touches.incrementAndGet()
            return id.compareTo(other.id)
        }
    }

    /** Walk [cell] to completion through [host], recording per-page key touches exactly as the C7 test does. */
    private fun walkMap(
        cell: MapCell<CountingComparableKey, String>,
        keyBound: KeyBound?,
    ): Pair<List<Int>, List<CountingComparableKey>> {
        touches.set(0)
        val perPage = mutableListOf<Int>()
        val keys = mutableListOf<CountingComparableKey>()
        var cursor: Cursor? = null
        do {
            val before = touches.get()
            val pending = host.readState(cell.ref, StateRead(cursor = cursor, limit = LIMIT, keyBound = keyBound))
            controller.runToIdle()
            val page = (pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) as StateReadResult.Page).page
            perPage += touches.get() - before
            keys += page.entries.filterIsInstance<MapCell.MapStateEntry<*, *>>()
                .map { it.key as CountingComparableKey }
            cursor = page.next
        } while (cursor != null)
        return perPage to keys
    }

    @Test
    fun `a key-bounded walk costs O(limit) per page and no extra full pass — the KAGG-R-26 bar`() {
        val cell = MapCell<CountingComparableKey, String>()
        repeat(N) { cell.inlet.call.put(CountingComparableKey(it), "v$it") }
        host.managementInlet.call.spawn(cell)

        // the control: an unbounded walk of the same cell, same rig.
        val (controlPerPage, _) = walkMap(cell, keyBound = null)

        // the middle half — both halves of the bound's admit work are exercised.
        val bound = KeyBound(CountingComparableKey(N / 4), CountingComparableKey(3 * N / 4))
        val (boundedPerPage, boundedKeys) = walkMap(cell, keyBound = bound)

        // correctness first, so the cost numbers are about a real walk.
        boundedKeys.size shouldBe N / 2
        boundedKeys.forEach {
            it.id shouldBeGreaterThanOrEqual N / 4
            it.id shouldBeLessThan 3 * N / 4
        }
        boundedKeys.map { it.id }.distinct().size shouldBe boundedKeys.size // no entry twice
        boundedPerPage.size shouldBe (N / 2) / LIMIT
        // the loop above only exits once `next == null`, so the cursor is terminated.

        withClue("bounded per-page key touches: $boundedPerPage") {
            // [KAGG-R-26]: every page after the open page is O(limit), bound or not —
            // the same ceiling the unbounded C7 test uses.
            boundedPerPage.drop(1).forEach { it shouldBeLessThan PER_PAGE_CEILING }
            boundedPerPage.last() shouldBeLessThan PER_PAGE_CEILING
        }

        withClue("bounded open page ${boundedPerPage.first()} vs control open page ${controlPerPage.first()}") {
            // [KAGG-R-26]/BS-35: the bound composes into the *same* filter-then-sort
            // pass `freeze` already pays at open (D4) — it must not add a second one.
            // An extra O(n) pass at open only (e.g. a second `freeze`) would roughly
            // double this ratio; OPEN_FACTOR sits between the honest ratio measured
            // here and that doubled one (see OPEN_FACTOR's KDoc).
            boundedPerPage.first() shouldBeLessThan controlPerPage.first() * OPEN_FACTOR
        }

        withClue("bounded total ${boundedPerPage.sum()} vs control total ${controlPerPage.sum()}") {
            // [KAGG-R-26]: no more full key passes than the unbounded walk — never
            // asserted as sublinear ([KAGG-R-29]), only as no worse than a generous
            // multiple of the control's total.
            boundedPerPage.sum() shouldBeLessThan controlPerPage.sum() * TOTAL_FACTOR
        }
    }

    @Test
    fun `the unbounded walk on MapCell is the control — its cost is unchanged by the bound machinery`() {
        val cell = MapCell<CountingComparableKey, String>()
        repeat(N) { cell.inlet.call.put(CountingComparableKey(it), "v$it") }
        host.managementInlet.call.spawn(cell)

        val (perPage, keys) = walkMap(cell, keyBound = null)

        keys.size shouldBe N
        perPage.size shouldBe N / LIMIT

        // [KAGG-R-28]-flavoured: adding `admits` to the predicate composition (D4)
        // must not have made the *unbounded* walk pay — it is pinned at the same
        // bar `SetCell`'s C7 test uses, for every *steady-state* page.
        //
        // The open page is excluded from PER_PAGE_CEILING here, unlike the C7
        // test: `MapCell.openWalk` always imposes `EntryOrder` with one
        // `sortWith` pass over all N keys (D4, `freeze`) whether or not a bound
        // is in force — `SetCell`'s walk has no such pass, so its own page 1 is
        // as cheap as any other and fits under PER_PAGE_CEILING without help. A
        // `sortWith` over N = 4,000 already-ascending keys costs TimSort's
        // best-case N − 1 ≈ 3,999 `compareTo` calls alone (measured: this
        // walk's page 1 costs 4,199 — 3,999 sort + 200 for the page's own 100
        // entries) — an order of magnitude above PER_PAGE_CEILING = 1,200 by
        // construction, not by regression: no page-1 ceiling at this N could
        // both hold and still let the sort run. WALK_CEILING (12·N = 48,000)
        // already prices in that one-time pass — this walk's total measures
        // ~12,000, comfortably inside it — so it, not a per-page ceiling, is
        // what pins page 1's cost.
        withClue("MapCell unbounded per-page key touches: $perPage") {
            perPage.drop(1).forEach { it shouldBeLessThan PER_PAGE_CEILING }
            perPage.sum() shouldBeLessThan WALK_CEILING
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

        /**
         * BS-35: the bounded walk's open page vs. the control's open page. Both
         * pay the same `freeze` (filter + `sortWith`) shape (D4) over N =
         * 4,000 keys; the bound adds ≤ 2 `compare` calls per key to the filter
         * (checking `from` and `to`) and shrinks the sorted list the `sortWith`
         * pass then sorts (N/2 admitted keys vs. N). Measured on the honest
         * code: control page 1 costs 4,199 touches (≈ 3,999 for the sort of
         * N = 4,000 already-ascending keys + 200 for its own 100 entries);
         * bounded page 1 costs 9,203 (≈ 7,000 for the bound's filter pass over
         * all 4,000 keys + ≈ 2,000 for sorting the 2,000 admitted keys + 200 for
         * its own 100 entries) — a ratio of ≈ 2.19. An extra O(n) pass at open
         * only (e.g. a second `freeze`) would roughly double the bounded side
         * alone, pushing the ratio to ≈ 4.4; OPEN_FACTOR sits strictly between
         * the two, well above the honest ratio's measurement noise and well
         * below the doubled one.
         */
        const val OPEN_FACTOR = 3

        /**
         * BS-35: the bounded walk's total vs. the control's total. The bounded
         * walk pays more at open (its filter examines all N keys, where the
         * control's trivial `scope == null` filter touches none) but answers
         * half the pages (N/2/LIMIT = 20 vs. N/LIMIT = 40), each at the same
         * ≈ 200-touch steady-state cost — the open premium and the fewer pages
         * roughly offset, and the honest total ratio measures ≈ 1.08. A
         * per-page O(n) rescan (the mutation this file's cost tests exist to
         * catch) instead multiplies every later page by ~N/LIMIT, which
         * WALK_CEILING alone would already fail to admit; TOTAL_FACTOR is a
         * generous multiple of the honest ratio, not a tight one, because the
         * per-page and open-page checks above already carry the precision.
         */
        const val TOTAL_FACTOR = 2

        const val TIMEOUT_MS = 30_000L

        val touches = AtomicInteger()
    }
}
