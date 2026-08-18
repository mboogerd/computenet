package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.MessageContext
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import civictech.cell.StateRead
import civictech.cell.data.delta.SetDelta

class SetCellTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    @Test
    fun `SetDelta merge is a commutative tag-set union`() {
        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        val d1 = SetDelta(adds = mapOf("x" to setOf(t1)), dels = mapOf("y" to setOf(t3)))
        val d2 = SetDelta(adds = mapOf("x" to setOf(t2)), dels = emptyMap())

        val merged = d1.merge(d2)
        assertEquals(mapOf("x" to setOf(t1, t2)), merged.adds)
        assertEquals(mapOf("y" to setOf(t3)), merged.dels)
        assertEquals(merged, d2.merge(d1))
    }

    @Test
    fun `SetCell propagates additions with a fresh tag per add`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add(1)
        cell.inlet.call.add(1)

        assertEquals(2, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val deltas = invocationBuffer.map { it.args[0] as SetDelta<Int> }
        val tags = deltas.map { it.adds.getValue(1).single() }
        assertEquals(2, tags.toSet().size) // re-adding mints a new tag
    }

    @Test
    fun `SetCell remove emits exactly the observed tags`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.add(1)
        cell.inlet.call.add(1)
        cell.inlet.call.remove(1)

        assertEquals(3, invocationBuffer.size)
        @Suppress("UNCHECKED_CAST")
        val deltas = invocationBuffer.map { it.args[0] as SetDelta<Int> }
        val minted = deltas.take(2).flatMap { it.adds.getValue(1) }.toSet()
        assertEquals(minted, deltas[2].dels.getValue(1))
    }

    @Test
    fun `SetCell remove of an unobserved element is a no-op`() {
        val cell = SetCell<Int>()

        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Propagate<SetDelta<Int>>>(invocationBuffer)
        cell.outlet.subscribe(Use.fixed(buffer, PortRef.generate()))

        cell.inlet.call.remove(42)
        assertTrue(invocationBuffer.isEmpty())
    }

    @Test
    fun `SetCell remote merge re-originates its wave and preserves tags verbatim`() {
        val cell = SetCell<Int>()
        val emissions = mutableListOf<Invocation>()
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<SetDelta<Int>>>(emissions), PortRef.generate()))
        val incomingTag = tag(7)
        val incoming = SetDelta(adds = mapOf(1 to setOf(incomingTag)))
        val incomingContext = MessageContext(Timestamp(UUID.randomUUID(), 41), PortRef.generate())
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

        Invocation.of(propagate, arrayOf(incoming), incomingContext).invoke(cell.deltaInlet.call)

        val emission = emissions.single()
        // spec 20/22 §Source identity: sourceId is the outlet's emission
        // epoch, minted fresh at construction — never the port identity
        assertEquals(cell.outlet.waveState().sourceId, emission.context!!.timestamp.sourceId)
        assertEquals(cell.outlet.ref, emission.context!!.sourcePort)
        assertEquals(incoming, emission.args.single())
        @Suppress("UNCHECKED_CAST")
        val emitted = emission.args.single() as SetDelta<Int>
        assertSame(incomingTag, emitted.adds.getValue(1).single())
    }

    // -----------------------------------------------------------------
    // read accessors under a concurrent writer (computenet-bdth)
    // -----------------------------------------------------------------

    /**
     * A host reads the cell (`membership`/`snapshot`/`readBounded`) from its own
     * thread while the cell's writer mutates `adds`/`dels`. Before the guard
     * those accessors iterated the shared maps — and, in `readBounded` and
     * `snapshot`, the per-element tag sets — unsynchronised, and escaped a
     * `ConcurrentModificationException` into the caller. The element-shaped twin
     * of the `OrMapCell` failure observed on CI as
     * `HeadlineLivenessTest > initializationError`, reachable here through
     * `MirrorProjector.edgeView`, which reads a `SetCell`'s `membership` on an
     * `awaitUntil` thread while the beads mirror's poller writes
     * (computenet-bdth, twin of computenet-yk5r).
     *
     * **This reproduction is statistical, not deterministic**, and deliberately
     * so — the same reason `OrMapCellTest`'s twin gives. Forcing the interleaving
     * deterministically needs the reader suspended *inside* its iteration while
     * the writer is released, but under the monitor the fix takes that writer
     * blocks on the reader, so such a test deadlocks against the fixed code
     * instead of passing. Measured failure rate against the unfixed accessors:
     * 20/20 rounds threw `ConcurrentModificationException`. A null result here
     * therefore bounds the defect well below the rate it had, but does not prove
     * its absence.
     *
     * **The round's work is bounded in absolute terms, not by wall clock**
     * (computenet-jw58). The reader makes a fixed [READS_PER_ROUND] passes and
     * the writer is bounded *twice over* — it stops at [WRITE_BUDGET] writes, or
     * as soon as the reader has finished its passes, whichever comes first. So
     * the population the accessors walk never exceeds
     * `SEEDED_ELEMENTS + WRITE_BUDGET`, and the round costs the same number of
     * map operations on 4 vCPUs as on 16. The earlier shape ran the writer
     * `while (!stop)` against a fixed count of read passes: `adds` grew for the
     * whole round, each read pass cost O(n) with n still rising, and the round's
     * total cost was set by how fast the writer ran *relative* to the reader — a
     * property of the machine. That version passed in seconds here and exceeded
     * the 5-minute default `@Timeout` on a 4-vCPU CI runner (PR #311).
     *
     * Bounding the writer by the reader's progress (not only by the budget) is
     * what keeps the reproduction 20/20 rather than ~19/20: a writer that can
     * exhaust a fixed budget before the reader's passes leaves rounds in which
     * no read pass ever overlaps a write, and those rounds silently detect
     * nothing.
     *
     * **What the numbers actually are, measured rather than inferred**
     * (computenet-jw58 review, darwin/arm64, guarded code, one 20-round run):
     * the writer completes **1–3 writes in 19 of the 20 rounds** and ~180 in
     * the warm-up round. It never approaches [WRITE_BUDGET]. One
     * `inlet.call` costs orders of magnitude more than one read pass over
     * [SEEDED_ELEMENTS] entries, so the reader's [READS_PER_ROUND] passes finish
     * first in every round; [WRITE_BUDGET] is a ceiling that stops a *slow*
     * reader from meeting an unbounded population, not the term that sets the
     * observed cost. The round's real cost is `READS_PER_ROUND` passes over a
     * population that stays near [SEEDED_ELEMENTS] — a few hundred thousand element
     * visits, absolutely bounded either way.
     *
     * That matters to anyone retuning these constants: the discriminating
     * power comes from that *handful* of writes landing inside a read pass,
     * and the reader's window is what gives them somewhere to land. Lowering
     * [READS_PER_ROUND] shortens the window toward rounds in which the writer
     * thread has not issued its first write before the reader is done — the
     * same detect-nothing round as above, reached from the other side. The
     * observed floor of one write per round is the margin here, and it has
     * not been measured on a 4-vCPU CI runner.
     */
    @Test
    fun `read accessors do not throw ConcurrentModificationException under a concurrent writer`() {
        val failures = mutableListOf<Throwable>()
        repeat(CONCURRENT_ROUNDS) { round ->
            val cell = SetCell<String>()
            repeat(SEEDED_ELEMENTS) { cell.inlet.call.add("e$it") }

            val readsDone = AtomicInteger(0)
            val writerFailure = AtomicReference<Throwable?>(null)
            val writer = thread(name = "set-cell-writer-$round") {
                var n = SEEDED_ELEMENTS
                var written = 0
                try {
                    while (written < WRITE_BUDGET && readsDone.get() < READS_PER_ROUND) {
                        cell.inlet.call.add("e${n++}")
                        if (n % 3 == 0) cell.inlet.call.remove("e${n % SEEDED_ELEMENTS}")
                        written++
                    }
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            }
            try {
                repeat(READS_PER_ROUND) {
                    cell.membership()
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

        /** Elements seeded before the writer starts — enough that an iteration spans a write. */
        const val SEEDED_ELEMENTS = 400

        /** Read passes per round, each touching all three host-callable accessors. */
        const val READS_PER_ROUND = 40

        /**
         * Hard cap on the writes one round may perform. It is a *cap*, not a
         * target: the writer normally stops earlier, when the reader finishes
         * its [READS_PER_ROUND] passes. The cap is what makes the round's cost
         * machine-independent — it bounds the element population the reader
         * walks, where the previous unbounded `while (!stop)` writer let that
         * population grow for the whole round.
         */
        const val WRITE_BUDGET = 20_000
    }
}
