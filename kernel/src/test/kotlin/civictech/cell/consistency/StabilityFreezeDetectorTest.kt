package civictech.cell.consistency

import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * E3.5 (`computenet-9sm.5.1`): [StabilityFreezeDetector]'s predicate, latch
 * and retraction ([KE3-27] notice half, [KE3-28] mechanism half; decisions
 * 9sm.5-D2, D3, D6).
 *
 * **Pure — no host, no cells, no ports.** The detector takes snapshots in and
 * returns events out, so every case below is three maps and a threshold. That
 * is itself the [KE3-28] assertion: there is no lattice here for the detector
 * to mutate, and its constructor takes no companion, no registry and no
 * mutator to reach one. A freeze notice cannot close, suspend or evict
 * anything because this class has nothing to call.
 */
class StabilityFreezeDetectorTest {

    private val slotA: UUID = UUID.randomUUID()
    private val slotB: UUID = UUID.randomUUID()
    private val slotC: UUID = UUID.randomUUID()
    private val s: UUID = UUID.randomUUID()

    private val open = setOf(slotA, slotB, slotC)

    /** `A→a, B→b, C→c` on the single source [s]; a null value means "no row at all". */
    private fun rows(a: Long?, b: Long?, c: Long?): Map<UUID, Map<UUID, Long>> = buildMap {
        a?.let { put(slotA, mapOf(s to it)) }
        b?.let { put(slotB, mapOf(s to it)) }
        c?.let { put(slotC, mapOf(s to it)) }
    }

    private fun frozen(slot: UUID, counter: Long?) = StallNotice.Stall(
        reason = StallReason.STABILITY_FROZEN,
        timestamp = counter?.let { Timestamp(s, it) },
        slot = slot,
    )

    @Test
    fun `a lagging unchanged slot latches on the Hth evaluation and never again`() {
        val detector = StabilityFreezeDetector(threshold = 3)

        // A and B advance each evaluation; C is stuck at 9.
        // Evaluation 1 is the first snapshot: C's row is "changed" against the
        // empty previous view, so it cannot count yet.
        detector.evaluate(rows(10, 10, 9), open, emptySet()).shouldBeEmpty()
        // 2 and 3 are the first two consecutive (lagging AND unchanged) hits.
        detector.evaluate(rows(11, 11, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(12, 12, 9), open, emptySet()).shouldBeEmpty()

        // The third consecutive hit reaches H = 3 and latches, with C's own
        // wave position on the source it is the floor for.
        detector.evaluate(rows(13, 13, 9), open, emptySet()) shouldBe listOf(frozen(slotC, 9L))

        // Latched: further evaluations say nothing, however long the lag runs.
        detector.evaluate(rows(14, 14, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(15, 15, 9), open, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `the row advancing retracts the latch with exactly one Resume and resets the counter`() {
        val detector = StabilityFreezeDetector(threshold = 2)
        detector.evaluate(rows(10, 10, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(11, 11, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(12, 12, 9), open, emptySet()) shouldBe listOf(frozen(slotC, 9L))

        // C moves at all: one Resume, and nothing else. Not repeated afterwards —
        // the latch is gone, not re-cleared on every later evaluation.
        detector.evaluate(rows(13, 13, 10), open, emptySet()) shouldBe listOf(StallNotice.Resume)

        // And the counter really did reset: it takes a fresh H consecutive hits
        // to latch again, not one.
        detector.evaluate(rows(14, 14, 10), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(15, 15, 10), open, emptySet()) shouldBe listOf(frozen(slotC, 10L))
    }

    @Test
    fun `a closed slot retracts the latch with exactly one Resume`() {
        val detector = StabilityFreezeDetector(threshold = 2)
        detector.evaluate(rows(10, 10, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(11, 11, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(12, 12, 9), open, emptySet()) shouldBe listOf(frozen(slotC, 9L))

        // A clean departure: the row is closed and leaves the open set.
        detector.evaluate(rows(13, 13, 9), open - slotC, setOf(slotC)) shouldBe listOf(StallNotice.Resume)
        detector.evaluate(rows(14, 14, 9), open - slotC, setOf(slotC)).shouldBeEmpty()
    }

    @Test
    fun `a slot leaving the open set retracts the latch with exactly one Resume`() {
        val detector = StabilityFreezeDetector(threshold = 2)
        detector.evaluate(rows(10, 10, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(11, 11, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(12, 12, 9), open, emptySet()) shouldBe listOf(frozen(slotC, 9L))

        // Membership shrank without a `closed` marker — an evict or a re-scope.
        detector.evaluate(rows(13, 13, 9), open - slotC, emptySet()) shouldBe listOf(StallNotice.Resume)
    }

    @Test
    fun `a rowless announced member freezes with a null timestamp`() {
        val detector = StabilityFreezeDetector(threshold = 2)

        // C is announced (open) and has no row at all, so it is bottom on `s`
        // from the first evaluation and is unchanged against a rowless past.
        detector.evaluate(rows(10, 10, null), open, emptySet()).shouldBeEmpty()

        val notices = detector.evaluate(rows(11, 11, null), open, emptySet())
        notices shouldBe listOf(frozen(slotC, null))
        // Explicitly: no wave position to report for a slot with no row.
        (notices.single() as StallNotice.Stall).timestamp shouldBe null
        (notices.single() as StallNotice.Stall).slot shouldBe slotC
    }

    @Test
    fun `a slot that lags but advances every evaluation never trips`() {
        val detector = StabilityFreezeDetector(threshold = 2)

        // C is always behind A and B, but it moves every time: slow, not frozen.
        detector.evaluate(rows(10, 10, 1), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(20, 20, 2), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(30, 30, 3), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(40, 40, 4), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(50, 50, 5), open, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `an unchanged slot that is not lagging never trips`() {
        val detector = StabilityFreezeDetector(threshold = 2)

        // Nothing moves at all. C is unchanged, but no other slot is strictly
        // past it, so it is not lagging and the conjunction never holds.
        repeat(5) { detector.evaluate(rows(9, 9, 9), open, emptySet()).shouldBeEmpty() }
    }

    @Test
    fun `two frozen slots yield two stalls each naming its own slot`() {
        val detector = StabilityFreezeDetector(threshold = 2)
        val t = UUID.randomUUID()

        // Two slots can only lag simultaneously on DIFFERENT sources — "lagging"
        // is "every other open slot strictly greater", so on one source at most
        // one slot can be the strict floor. B is the floor on `s`, C on `t`.
        val stuck = mapOf(
            slotA to mapOf(s to 100L, t to 100L),
            slotB to mapOf(s to 1L, t to 100L),
            slotC to mapOf(s to 100L, t to 1L),
        )
        detector.evaluate(stuck, open, emptySet()).shouldBeEmpty()
        detector.evaluate(stuck, open, emptySet()).shouldBeEmpty()

        val notices = detector.evaluate(stuck, open, emptySet())
        notices.filterIsInstance<StallNotice.Stall>().map { it.slot }.toSet() shouldBe setOf(slotB, slotC)
        notices.toSet() shouldBe setOf(
            StallNotice.Stall(StallReason.STABILITY_FROZEN, Timestamp(s, 1L), slotB),
            StallNotice.Stall(StallReason.STABILITY_FROZEN, Timestamp(t, 1L), slotC),
        )
        // A is nobody's floor and never trips.
        notices.filterIsInstance<StallNotice.Stall>().none { it.slot == slotA } shouldBe true
    }

    @Test
    fun `with a single open slot nothing is ever lagging`() {
        val detector = StabilityFreezeDetector(threshold = 1)

        // There is no "other" open slot to be strictly past it, so the lag
        // predicate is vacuously false however long the row sits still.
        repeat(5) { detector.evaluate(rows(9, null, null), setOf(slotA), emptySet()).shouldBeEmpty() }
    }

    @Test
    fun `an empty open set produces nothing`() {
        val detector = StabilityFreezeDetector(threshold = 1)
        repeat(3) { detector.evaluate(rows(9, 9, 9), emptySet(), emptySet()).shouldBeEmpty() }
    }

    @Test
    fun `the reported witness is the source the slot sits lowest on`() {
        val detector = StabilityFreezeDetector(threshold = 1)
        // Two sources; C lags on both, but sits at 1 on `s` and 7 on `t`.
        val t = UUID.randomUUID()
        val rows = mapOf(
            slotA to mapOf(s to 100L, t to 100L),
            slotB to mapOf(s to 100L, t to 100L),
            slotC to mapOf(s to 1L, t to 7L),
        )
        // First evaluation: C's row is changed against the empty past, so no count.
        detector.evaluate(rows, open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows, open, emptySet()) shouldBe listOf(frozen(slotC, 1L))
    }

    @Test
    fun `a lagging slot that goes rowless counts as changed and resets`() {
        val detector = StabilityFreezeDetector(threshold = 2)
        detector.evaluate(rows(10, 10, 9), open, emptySet()).shouldBeEmpty()
        detector.evaluate(rows(11, 11, 9), open, emptySet()).shouldBeEmpty()
        // The row disappearing is a change, not a freeze: the counter resets
        // rather than latching on this evaluation.
        detector.evaluate(rows(12, 12, null), open, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `a threshold below one is refused`() {
        val failure = runCatching { StabilityFreezeDetector(threshold = 0) }.exceptionOrNull()
        (failure is IllegalArgumentException) shouldBe true
    }
}
