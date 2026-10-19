package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class Q1Q2ReferenceTest {
    @ParameterizedTest
    @EnumSource(Drive::class)
    fun `Q1 is reference-verified after every batch and by a fresh terminal evaluator`(drive: Drive) {
        verify(NexmarkQuery.Q1, drive)
    }

    @ParameterizedTest
    @EnumSource(Drive::class)
    fun `Q2 is reference-verified after every batch and by a fresh terminal evaluator`(drive: Drive) {
        verify(NexmarkQuery.Q2, drive)
    }

    private fun verify(query: NexmarkQuery, drive: Drive) {
        var factories = 0
        val comparisons = mutableListOf<Comparison>()
        val record = NexmarkRun.run(
            query = query,
            seed = 7L,
            scale = NexmarkScale(2_000, 50),
            drive = drive,
            env = nexmarkTestEnvironment(),
            hooks = NexmarkRunHooks(
                referenceFactory = {
                    factories++
                    NexmarkReferenceEvaluator(NexmarkReference::evaluate)
                },
                comparisonObserved = { _, comparison -> comparisons += comparison },
            ),
        )

        val verified = assertInstanceOf(NexmarkRecord.Verified::class.java, record)
        assertEquals(20, verified.insert.size)
        assertTrue(verified.retract.size >= 9)
        assertEquals(verified.insert.size + verified.retract.size + 1, comparisons.size)
        assertTrue(comparisons.all { it == Comparison.Equal })
        assertEquals(2, factories, "streaming and terminal comparisons need distinct evaluators")
        assertEquals(2, verified.header.maxFanOut, "both query graphs exercise an outlet fan-out of two")
        assertEquals(
            if (drive == Drive.SIM) SIM_SCHEDULER else "VirtualThreadScheduler",
            verified.header.scheduler,
        )
    }
}
