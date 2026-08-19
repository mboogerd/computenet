package civictech.cell.data

import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import civictech.cell.data.delta.PnCounterDelta

class PnCounterCellTest {
    @Test
    fun `remote merge re-originates its wave and preserves source totals verbatim`() {
        val cell = PnCounterCell()
        val emissions = mutableListOf<Invocation>()
        cell.outlet.subscribe(Use.fixed(buffering<Propagate<PnCounterDelta>>(emissions), PortRef.generate()))
        val contributionSource = UUID.randomUUID()
        val incoming = PnCounterDelta(incs = mapOf(contributionSource to 7L))
        val incomingContext = MessageContext(Timestamp(UUID.randomUUID(), 41), PortRef.generate())
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

        Invocation.of(propagate, arrayOf(incoming), incomingContext).invoke(cell.deltaInlet.call)

        val emission = emissions.single()
        // spec 20/22 §Source identity: sourceId is the outlet's emission
        // epoch, minted fresh at construction — never the port identity
        assertEquals(cell.outlet.waveState().sourceId, emission.context!!.timestamp.sourceId)
        assertEquals(cell.outlet.ref, emission.context!!.sourcePort)
        assertEquals(incoming, emission.args.single())
        val emitted = emission.args.single() as PnCounterDelta
        assertSame(contributionSource, emitted.incs.keys.single())
    }

    // -----------------------------------------------------------------
    // read accessors under a concurrent writer (computenet-ndf6)
    // -----------------------------------------------------------------

    /**
     * A host reads the cell (`total`/`snapshot`) from its own thread while the
     * cell's writer mutates `incs`/`decs`. Before the guard those accessors
     * iterated the shared maps — `incs.values.sum()` in `total`, `HashMap(incs)`
     * in `snapshot` — unsynchronised, and escaped a
     * `ConcurrentModificationException` into the caller. The counter-shaped
     * member of the family whose OR-set twins were fixed as computenet-yk5r
     * (`OrMapCell`) and computenet-bdth (`SetCell`).
     *
     * **The writer here is the REMOTE one, and it has to be.** A local
     * `increment` only rewrites this instance's own slot, which is not a
     * structural modification of the map and can never raise a CME however hard
     * it is driven; only `applyRemote` adds slots, one per peer source it has not
     * seen. So the writer thread gossips deltas from fresh source ids into
     * `deltaInlet` — the shape a real replica mesh produces — and drives the
     * local `inlet` alongside so the value-rewrite path is under the guard too.
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
     * as soon as the reader has finished its passes, whichever comes first, so
     * the source population never exceeds `SEEDED_SOURCES + WRITE_BUDGET` and the
     * round costs the same number of map operations on 4 vCPUs as on 16.
     */
    @Test
    fun `read accessors do not throw ConcurrentModificationException under a concurrent writer`() {
        val failures = mutableListOf<Throwable>()
        repeat(CONCURRENT_ROUNDS) { round ->
            val cell = PnCounterCell()
            repeat(SEEDED_SOURCES) {
                cell.deltaInlet.call.propagate(PnCounterDelta(incs = mapOf(UUID.randomUUID() to 1L)))
            }

            val readsDone = AtomicInteger(0)
            val writerFailure = AtomicReference<Throwable?>(null)
            val writer = thread(name = "pn-counter-cell-writer-$round") {
                var written = 0
                try {
                    while (written < WRITE_BUDGET && readsDone.get() < READS_PER_ROUND) {
                        // a fresh peer source: this is the only path that adds a
                        // map slot, and therefore the only one that can raise a CME
                        cell.deltaInlet.call.propagate(
                            PnCounterDelta(incs = mapOf(UUID.randomUUID() to 1L))
                        )
                        cell.inlet.call.increment(1)
                        written++
                    }
                } catch (t: Throwable) {
                    writerFailure.set(t)
                }
            }
            try {
                repeat(READS_PER_ROUND) {
                    cell.total()
                    cell.snapshot()
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

        /** Peer source slots seeded before the writer starts. */
        const val SEEDED_SOURCES = 400

        /** Read passes per round, each touching both host-callable accessors. */
        const val READS_PER_ROUND = 40

        /**
         * Hard cap on the writes one round may perform. It is a *cap*, not a
         * target: the writer normally stops earlier, when the reader finishes its
         * [READS_PER_ROUND] passes. The cap is what makes the round's cost
         * machine-independent — it bounds the source population the reader sums.
         */
        const val WRITE_BUDGET = 20_000
    }
}
