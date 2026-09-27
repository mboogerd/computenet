package civictech.cell.data.delta

import civictech.cell.EmbeddedMergeClass
import civictech.cell.MergeablePayload
import civictech.nature.MergeClass
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `[24-WL-03]`/`[KE4-06]` merge laws for [WaterlineDelta] (task computenet-sjqat.1),
 * mirroring [civictech.cell.data.WatermarkCellTest]'s house style: four named laws
 * under 100 seeded merge orders, kotest matchers. Floors are drawn from a range
 * that overlaps and includes negatives so max is actually exercised rather than
 * degenerating to plain replacement.
 */
class WaterlineDeltaTest {

    private fun randomDelta(rnd: Random): WaterlineDelta = WaterlineDelta(rnd.nextLong(-50, 50))

    @Test
    fun `merge is commutative under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            a.merge(b) shouldBe b.merge(a)
        }
    }

    @Test
    fun `merge is idempotent under 100 seeds`() {
        for (seed in 0L until 100L) {
            val a = randomDelta(Random(seed))
            a.merge(a) shouldBe a
        }
    }

    @Test
    fun `merge is associative under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val c = randomDelta(rnd)
            a.merge(b).merge(c) shouldBe a.merge(b.merge(c))
        }
    }

    @Test
    fun `merge is monotone - the result dominates both operands under 100 seeds`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val a = randomDelta(rnd)
            val b = randomDelta(rnd)
            val m = a.merge(b)
            m.dominates(a).shouldBeTrue()
            m.dominates(b).shouldBeTrue()
        }
    }

    @Test
    fun `classify names WaterlineDelta IDEMPOTENT per the KE1-04 table`() {
        EmbeddedMergeClass.classify(WaterlineDelta(0)) shouldBe MergeClass.IDEMPOTENT
    }

    /**
     * `[KE4-34]` fold half: duplicated, reordered and replayed deltas fold via
     * `mergeWith` to the same result as a single in-order delivery — and both
     * equal `WaterlineDelta(floors.max())`, since the fold is a max-semilattice.
     */
    @Test
    fun `fold over duplicated, reordered, replayed deltas equals in-order delivery and the maximum floor`() {
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            val n = rnd.nextInt(20, 41)
            val floors = (0 until n).map { rnd.nextLong(-50, 50) }
            val deltas = floors.map { WaterlineDelta(it) }

            val inOrder = deltas.fold(WaterlineDelta(Long.MIN_VALUE) as MergeablePayload) { acc, d -> acc.mergeWith(d) }

            val shuffled = (deltas + deltas).shuffled(rnd)
            val replayedTwice = shuffled + shuffled
            val folded = replayedTwice.fold(WaterlineDelta(Long.MIN_VALUE) as MergeablePayload) { acc, d -> acc.mergeWith(d) }

            folded shouldBe inOrder
            folded shouldBe WaterlineDelta(floors.max())
        }
    }
}
