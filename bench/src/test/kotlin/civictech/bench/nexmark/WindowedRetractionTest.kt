package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowedRetractionTest {
    @Test
    fun `Q7 half retractions compare equal with and without a policy`() {
        listOf(null, LatenessPolicy(5_000)).forEach { policy ->
            val comparisons = mutableListOf<Comparison>()
            val record = NexmarkRun.run(
                query = NexmarkQuery.Q7,
                seed = WINDOWED_TEST_SEED,
                scale = WINDOWED_TEST_SCALE,
                policy = policy,
                drive = Drive.SIM,
                batchSize = WINDOWED_BATCH_SIZE,
                retraction = RetractionPlan(0.5),
                env = nexmarkTestEnvironment(),
                hooks = NexmarkRunHooks(
                    comparisonObserved = { _, comparison -> comparisons += comparison },
                ),
            )

            assertInstanceOf(NexmarkRecord.Verified::class.java, record)
            assertTrue(comparisons.isNotEmpty())
            assertTrue(comparisons.all { it == Comparison.Equal })
        }
    }

    @Test
    fun `removing an already-evicted Q7 bid is a no-op on graph and reference`() {
        val template = windowedEvents().filterIsInstance<Bid>().first()
        val old = template.copy(id = Long.MAX_VALUE - 2L, dateTime = 1_000L, eventNumber = Long.MAX_VALUE - 2L)
        val live = template.copy(id = Long.MAX_VALUE - 3L, dateTime = 15_000L, eventNumber = Long.MAX_VALUE - 3L)
        val batches = listOf(
            Batch(1, listOf(StreamOp.Add(old), StreamOp.Add(live))),
            Batch(2, listOf(StreamOp.Remove(old))),
        )
        val observed = mutableListOf<NexmarkResult>()

        runWindowed(
            query = NexmarkQuery.Q7,
            policy = LatenessPolicy(5_000),
            batches = batches,
            hooks = NexmarkRunHooks(resultObserved = { _, result -> observed += result }),
        )

        assertTrue(observed.size >= 3, "two batch comparisons plus terminal drift expected")
        assertEquals(1, observed.distinct().size, "evicted remove changed the maintained sink")
        assertEquals(
            NexmarkReference.evaluate(NexmarkQuery.Q7, listOf(batches.first()), LatenessPolicy(5_000)),
            NexmarkReference.evaluate(NexmarkQuery.Q7, batches, LatenessPolicy(5_000)),
            "evicted remove changed the policy-matched reference",
        )
    }
}
