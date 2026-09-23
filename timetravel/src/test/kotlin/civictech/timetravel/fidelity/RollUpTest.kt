package civictech.timetravel.fidelity

import civictech.cell.CellRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

/**
 * TTD1 F3 (computenet-kxex2.1) D3: the run-level roll-up [TTD1-37]. Pins the four
 * consequences named in the design plus a seeded property loop over random verdict sets.
 */
class RollUpTest {

    private fun ref(name: String): CellRef = CellRef(UUID.nameUUIDFromBytes(name.toByteArray()))

    @Test
    fun emptyMapAndEmptyRunIsFaithful() {
        rollUp(emptyMap(), emptySet()) shouldBe Fidelity.Faithful
    }

    @Test
    fun runReasonsAloneMakeTheResultAtLeastDegraded() {
        val result = rollUp(emptyMap(), setOf(Reason.JOURNAL_TORN))
        result shouldBe Fidelity.Degraded(setOf(Reason.JOURNAL_TORN))
    }

    @Test
    fun neverBetterThanAnyInput() {
        val cells = mapOf(
            ref("a") to Fidelity.Faithful,
            ref("b") to Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL)),
        )
        val result = rollUp(cells, emptySet())
        (result.rank >= Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL)).rank) shouldBe true
    }

    @Test
    fun everyInputReasonAppearsInTheResult() {
        val cells = mapOf(
            ref("a") to Fidelity.Degraded(setOf(Reason.EFFECTFUL_CELL)),
            ref("b") to Fidelity.Unreconstructible(setOf(Reason.VOLATILE_CELL)),
        )
        val result = rollUp(cells, setOf(Reason.JOURNAL_TORN))
        result shouldBe Fidelity.Unreconstructible(setOf(Reason.EFFECTFUL_CELL, Reason.VOLATILE_CELL, Reason.JOURNAL_TORN))
    }

    /**
     * Property loop, seeded per the design (`Random(42)`, 200 iterations): a failing seed
     * stays failing — this seed is not to be swapped for a friendlier one.
     */
    @Test
    fun propertyLoop_neverBetterThanAnyInputAndReasonsAreTheUnion() {
        val random = Random(42)
        val allReasons = Reason.entries.toList()

        repeat(200) { iteration ->
            val cellCount = random.nextInt(7) // 0..6
            val cells = (0 until cellCount).associate { i ->
                ref("cell-$iteration-$i") to randomVerdict(random, allReasons)
            }
            val runReasons = randomReasons(random, allReasons, 0..3)

            val result = rollUp(cells, runReasons)

            val maxCellRank = cells.values.maxOfOrNull { it.rank } ?: 0
            (result.rank >= maxCellRank) shouldBe true
            if (runReasons.isNotEmpty()) {
                (result.rank >= Fidelity.Degraded(setOf(Reason.JOURNAL_TORN)).rank) shouldBe true
            }

            val expectedReasons = cells.values.flatMap { it.reasons }.toSet() + runReasons
            result.reasons shouldBe expectedReasons
        }
    }

    private fun randomVerdict(random: Random, allReasons: List<Reason>): Fidelity = when (random.nextInt(3)) {
        0 -> Fidelity.Faithful
        1 -> Fidelity.Degraded(randomReasons(random, allReasons, 1..3))
        else -> Fidelity.Unreconstructible(randomReasons(random, allReasons, 1..3))
    }

    /** A non-empty (since [range]'s lower bound is >= 1 for cell reasons, 0 for run reasons) set of reasons. */
    private fun randomReasons(random: Random, allReasons: List<Reason>, range: IntRange): Set<Reason> {
        val size = range.first + random.nextInt(range.last - range.first + 1)
        return (0 until size).map { allReasons[random.nextInt(allReasons.size)] }.toSet()
    }
}
