package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.StateRead
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.port.PortRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import civictech.cell.data.delta.ListDelta

class ListCellTest {

    @Test
    fun `ListCell propagates additions`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.add(0, "b")

        assertEquals(2, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "a")), d1.adds)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "b")), d2.adds)
    }

    @Test
    fun `ListCell propagates updates`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.set(0, "updated")

        assertEquals(2, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as ListDelta<String>
        assertEquals(listOf(IndexedValue(0, "updated")), d2.updates)
    }

    @Test
    fun `ListCell propagates removals`() {
        val cell = ListCell<String>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<ListDelta<String>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add("a")
        cell.inlet.call.add("b")
        cell.inlet.call.removeAt(0)

        assertEquals(3, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d3 = invocationBuffer[2].args[0] as ListDelta<String>
        assertEquals(listOf(0), d3.removals)
    }

    // -----------------------------------------------------------------
    // read accessors under a concurrent writer (computenet-ndf6)
    // -----------------------------------------------------------------

    /**
     * A host reads the cell (`snapshot`/`readBounded`) from its own thread while
     * the cell's writer mutates `state`. This cell's exposure has two shapes
     * where the map families have one, and **`readBounded` is the discriminating
     * one here**: it reads `state.size` to bound the page and then indexes
     * `state[index]`, so a `removeAt` landing between the two escapes an
     * `IndexOutOfBoundsException` into the caller — a tear no copy-on-read
     * repairs, because it is between the bound and the access. (`snapshot`'s
     * `ArrayList(state)` copies through `toArray` rather than an iterator, so it
     * tears silently rather than throwing; it is exercised here anyway because it
     * is a host-callable accessor over the same shared list.)
     *
     * **The page bounds are raised deliberately.** `StateRead`'s defaults —
     * `limit = 200`, and a `byteBudget` that stops the loop after ~780 entries —
     * would bound the page below [SEEDED_ELEMENTS] and make `examineThrough` a
     * constant rather than `state.size`, which is precisely the term the tear
     * lives in. [PAGE_LIMIT]/[PAGE_BYTE_BUDGET] ask for the whole list in one
     * page — an ordinary host request — so the walk is bounded by the live size
     * and the race is reachable.
     *
     * **This reproduction is statistical, not deterministic**, for the reason
     * `SetCellTest`'s twin gives: forcing the interleaving deterministically
     * needs the reader suspended *inside* its walk while the writer is released,
     * and under the monitor the fix takes, that writer blocks on the reader.
     *
     * **The round's work is bounded in absolute terms, not by wall clock**
     * (computenet-jw58, the shape this copies): the reader makes a fixed
     * [READS_PER_ROUND] passes and the writer stops at [WRITE_BUDGET] writes *or*
     * as soon as the reader has finished, whichever comes first, so the round
     * costs the same number of list operations on 4 vCPUs as on 16. The writer
     * adds two elements for every one it removes, so the list only grows and its
     * own `removeAt` can never run off the front.
     */
    @Test
    fun `read accessors do not throw ConcurrentModificationException under a concurrent writer`() {
        val failures = mutableListOf<Throwable>()
        repeat(CONCURRENT_ROUNDS) { round ->
            val cell = ListCell<String>()
            repeat(SEEDED_ELEMENTS) { cell.inlet.call.add("e$it") }

            val readsDone = AtomicInteger(0)
            val writerFailure = AtomicReference<Throwable?>(null)
            val writer = thread(name = "list-cell-writer-$round") {
                var n = SEEDED_ELEMENTS
                var written = 0
                try {
                    while (written < WRITE_BUDGET && readsDone.get() < READS_PER_ROUND) {
                        cell.inlet.call.add("e${n++}")
                        cell.inlet.call.add("e${n++}")
                        cell.inlet.call.removeAt(0) // net growth: the list never shrinks past the seed
                        written++
                    }
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            }
            try {
                repeat(READS_PER_ROUND) {
                    cell.snapshot()
                    cell.readBounded(StateRead(limit = PAGE_LIMIT, byteBudget = PAGE_BYTE_BUDGET))
                    readsDone.incrementAndGet()
                }
            } catch (t: Throwable) {
                failures += t
            } finally {
                readsDone.set(READS_PER_ROUND)
                writer.join(60_000)
            }
            writerFailure.get()?.let { failures += it }
        }
        assertTrue(
            failures.isEmpty(),
            "read accessors threw under a concurrent writer: ${failures.map { it::class.java.name }}",
        )
    }

    private companion object {
        /** Rounds of the concurrent-read regression; each round is a fresh cell. */
        const val CONCURRENT_ROUNDS = 20

        /** Elements seeded before the writer starts — enough that a walk spans a write. */
        const val SEEDED_ELEMENTS = 400

        /** Read passes per round, each touching both host-callable accessors. */
        const val READS_PER_ROUND = 40

        /**
         * One page wide enough to hold the whole list, so the walk's bound is the
         * live `state.size` rather than the request's limit — see the test's KDoc.
         */
        const val PAGE_LIMIT = 100_000

        /** Wide enough that `StateRead.byteBudget` never ends the walk early. */
        const val PAGE_BYTE_BUDGET = 100_000_000

        /**
         * Hard cap on the writer's iterations in one round. It is a *cap*, not a
         * target: the writer normally stops earlier, when the reader finishes its
         * [READS_PER_ROUND] passes. The cap is what makes the round's cost
         * machine-independent — the list nets one element per iteration, so it
         * bounds the length the reader walks at `SEEDED_ELEMENTS + WRITE_BUDGET`.
         * It is smaller than the OR-set family's because this cell's page
         * materializes one entry per element with no early byte cut-off above.
         */
        const val WRITE_BUDGET = 2_000
    }
}
