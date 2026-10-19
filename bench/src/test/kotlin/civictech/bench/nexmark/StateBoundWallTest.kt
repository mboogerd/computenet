package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateBoundWallTest {
    @Test
    fun `state ceiling is a recorded Q7 result before comparison`() {
        val comparisons = mutableListOf<Int>()
        val record = NexmarkRun.run(
            query = NexmarkQuery.Q7,
            seed = WINDOWED_TEST_SEED,
            scale = WINDOWED_TEST_SCALE,
            drive = Drive.SIM,
            batchSize = WINDOWED_BATCH_SIZE,
            retraction = RetractionPlan(0.0),
            stateCeiling = 2L,
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(
                comparisonObserved = { batch, _ -> comparisons += batch },
            ),
        )

        val bound = assertInstanceOf(NexmarkRecord.StateBound::class.java, record)
        // Current NexmarkRun applies the ceiling to every retained outlet; the
        // ingress collector is the first Q7 outlet to cross a low ceiling.
        assertEquals("q7-bids", bound.outlet)
        assertEquals(2L, bound.ceiling)
        assertTrue(bound.batchIndex > 0)
        assertTrue(
            bound.header.stateSeries[bound.batchIndex].liveRows.getValue(bound.outlet) > bound.ceiling,
        )
        assertTrue(bound.batchIndex !in comparisons, "the breaching batch must not be compared")

        val coverage = coverageFor(listOf(bound))
            .rows
            .single { it.query == NexmarkQuery.Q7 }
        assertEquals(Status.state_bound, coverage.status)
        assertTrue(coverage.cause.orEmpty().contains(bound.outlet))
        assertTrue(coverage.cause.orEmpty().contains("2"))
    }

    @Test
    fun `a ceiling above the test run verifies normally`() {
        val record = NexmarkRun.run(
            query = NexmarkQuery.Q7,
            seed = WINDOWED_TEST_SEED,
            scale = WINDOWED_TEST_SCALE,
            drive = Drive.SIM,
            batchSize = WINDOWED_BATCH_SIZE,
            retraction = RetractionPlan(0.0),
            // q7-bids retains the whole generated driving relation (1,840 bids
            // at this scale), so this is the current-code Verified control for
            // the ingress-wide ceiling rather than the fold-only 1,000 prose.
            stateCeiling = 10_000L,
            env = nexmarkTestEnvironment(),
        )

        assertInstanceOf(NexmarkRecord.Verified::class.java, record)
    }
}
