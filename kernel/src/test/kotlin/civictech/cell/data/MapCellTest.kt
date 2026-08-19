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
import civictech.cell.data.delta.MapDelta

class MapCellTest {

    @Test
    fun `MapCell propagates puts`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.put("a", 1)

        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("a" to 1), delta.puts)
        assertEquals(emptySet<String>(), delta.removals)
    }

    @Test
    fun `MapCell propagates removals`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.remove("a")

        assertEquals(1, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val delta = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(emptyMap<String, Int>(), delta.puts)
        assertEquals(setOf("a"), delta.removals)
    }

    @Test
    fun `MapCell handles multiple operations`() {
        val cell = MapCell<String, Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<MapDelta<String, Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.put("a", 1)
        cell.inlet.call.put("b", 2)
        cell.inlet.call.remove("a")

        assertEquals(3, invocationBuffer.size)

        @Suppress("UNCHECKED_CAST")
        val d1 = invocationBuffer[0].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("a" to 1), d1.puts)

        @Suppress("UNCHECKED_CAST")
        val d2 = invocationBuffer[1].args[0] as MapDelta<String, Int>
        assertEquals(mapOf("b" to 2), d2.puts)

        @Suppress("UNCHECKED_CAST")
        val d3 = invocationBuffer[2].args[0] as MapDelta<String, Int>
        assertEquals(setOf("a"), d3.removals)
    }

    // -----------------------------------------------------------------
    // read accessors under a concurrent writer (computenet-ndf6)
    // -----------------------------------------------------------------

    /**
     * A host reads the cell (`snapshot`/`readBounded`) from its own thread while
     * the cell's writer mutates `state`. Before the guard those accessors
     * iterated the shared map — `HashMap(state)` in `snapshot`,
     * `EntryOrder.freeze(state.keys, …)` in `readBounded`'s walk — unsynchronised,
     * and escaped a `ConcurrentModificationException` into the caller. The
     * last-write-wins member of the family whose OR-set twins were fixed as
     * computenet-yk5r (`OrMapCell`) and computenet-bdth (`SetCell`).
     *
     * **This reproduction is statistical, not deterministic**, for the reason
     * `SetCellTest`'s twin gives: forcing the interleaving deterministically
     * needs the reader suspended *inside* its iteration while the writer is
     * released, and under the monitor the fix takes, that writer blocks on the
     * reader — so such a test deadlocks against the fixed code instead of
     * passing. A null result here bounds the defect well below the rate it had
     * but does not prove its absence.
     *
     * **The round's work is bounded in absolute terms, not by wall clock**
     * (computenet-jw58, the shape this copies): the reader makes a fixed
     * [READS_PER_ROUND] passes and the writer stops at [WRITE_BUDGET] writes *or*
     * as soon as the reader has finished its passes, whichever comes first. The
     * population the accessors walk therefore never exceeds
     * `SEEDED_ENTRIES + WRITE_BUDGET`, and the round costs the same number of map
     * operations on 4 vCPUs as on 16. Bounding the writer by the reader's
     * progress as well as by the budget is what keeps every round discriminating:
     * a writer that could exhaust a fixed budget early would leave rounds in
     * which no read pass overlaps a write, and those detect nothing.
     */
    @Test
    fun `read accessors do not throw ConcurrentModificationException under a concurrent writer`() {
        val failures = mutableListOf<Throwable>()
        repeat(CONCURRENT_ROUNDS) { round ->
            val cell = MapCell<String, Int>()
            repeat(SEEDED_ENTRIES) { cell.inlet.call.put("k$it", it) }

            val readsDone = AtomicInteger(0)
            val writerFailure = AtomicReference<Throwable?>(null)
            val writer = thread(name = "map-cell-writer-$round") {
                var n = SEEDED_ENTRIES
                var written = 0
                try {
                    while (written < WRITE_BUDGET && readsDone.get() < READS_PER_ROUND) {
                        cell.inlet.call.put("k${n++}", n)
                        if (n % 3 == 0) cell.inlet.call.remove("k${n % SEEDED_ENTRIES}")
                        written++
                    }
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            }
            try {
                repeat(READS_PER_ROUND) {
                    cell.snapshot()
                    cell.readBounded(StateRead())
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

        /** Entries seeded before the writer starts — enough that an iteration spans a write. */
        const val SEEDED_ENTRIES = 400

        /** Read passes per round, each touching both host-callable accessors. */
        const val READS_PER_ROUND = 40

        /**
         * Hard cap on the writes one round may perform. It is a *cap*, not a
         * target: the writer normally stops earlier, when the reader finishes its
         * [READS_PER_ROUND] passes. The cap is what makes the round's cost
         * machine-independent — it bounds the entry population the reader walks.
         */
        const val WRITE_BUDGET = 20_000
    }
}
