package civictech.bench.nexmark

import java.util.Collections
import java.util.Random
import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetractionDirectionTest {
    @Test
    fun `Q3 retraction of half the auctions compares equal and propagates work`() {
        val seed = Q3_SEED
        val scale = NexmarkScale(2_000, 50)
        val events = NexmarkGenerator.generate(seed, scale)
        val auctions = events.filterIsInstance<Auction>().toMutableList()
        Collections.shuffle(auctions, Random(seed))
        val removed = auctions.take(floor(auctions.size * 0.5).toInt()).toSet()
        val reducedBatches = listOf(Batch(1, events.filter { it !in removed }.map(StreamOp::Add)))
        val comparisons = mutableListOf<Comparison>()
        val observed = mutableListOf<NexmarkResult>()

        val record = NexmarkRun.run(
            query = NexmarkQuery.Q3,
            seed = seed,
            scale = scale,
            drive = Drive.SIM,
            retraction = RetractionPlan(0.5),
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(
                comparisonObserved = { _, comparison -> comparisons += comparison },
                resultObserved = { _, result -> observed += result },
            ),
        )

        val verified = assertInstanceOf(NexmarkRecord.Verified::class.java, record)
        assertTrue(verified.retract.isNotEmpty())
        assertEquals(verified.insert.size + verified.retract.size + 1, comparisons.size)
        assertTrue(comparisons.all { it == Comparison.Equal })
        assertEquals(
            NexmarkReference.evaluate(NexmarkQuery.Q3, reducedBatches, null).result.rows,
            observed.last().rows,
        )
        val steps = verified.retract.map { (it.figure as Figure.Work).steps }
        assertTrue(steps.any { it > 0 }, "no retract batch propagated anything: $steps")
    }
}
