package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Q3ReferenceTest {
    @Test
    fun `Q3 sink equals the reference over the whole prefix and the record is verified`() {
        val events = NexmarkGenerator.generate(Q3_SEED, NexmarkScale(2_000, 50))
        val batches = events.chunked(100).mapIndexed { i, ops -> Batch(i + 1, ops.map(StreamOp::Add)) }
        val observed = mutableListOf<NexmarkResult>()

        val record = NexmarkRun.run(
            query = NexmarkQuery.Q3,
            seed = Q3_SEED,
            scale = NexmarkScale(2_000, 50),
            drive = Drive.SIM,
            retraction = RetractionPlan(0.0),
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(resultObserved = { _, result -> observed += result }),
        )

        assertInstanceOf(NexmarkRecord.Verified::class.java, record)
        val expected = NexmarkReference.evaluate(NexmarkQuery.Q3, batches, null).result
        assertTrue(expected.rows.isNotEmpty(), "Q3 produced no rows for seed 7")
        assertEquals(
            expected.rows,
            observed.last().rows,
            "Q3 sink differs from reference (${expected.rows.size} reference rows)",
        )
    }
}

/**
 * Seed 7 (and seeds 1..20, 50..1000) yield an empty Q3 at this scale: Beam's hot-seller rule
 * points almost every seller at person 1000, which the first 40 persons rarely include.
 * Seed 9264 is the first probed (step 13 from 5000) with a non-empty join (2 rows).
 */
internal const val Q3_SEED = 9264L
