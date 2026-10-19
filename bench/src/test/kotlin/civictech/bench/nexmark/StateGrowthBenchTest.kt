package civictech.bench.nexmark

import civictech.bench.micro.HeapProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The expensive Q7 state-growth probe at the two Nexmark measurement scales. */
@Tag("bench")
class StateGrowthBenchTest {
    @Test
    fun `Q7 state growth prints unbounded and evicting series at both scales`() {
        HeapProbe.requireCollectableHeap()
        val policy = LatenessPolicy(5_000L)
        val findings = mutableListOf<GrowthFinding>()

        listOf(10_000, 100_000).forEach { events ->
            val scale = NexmarkScale(events = events, eventsPerSecond = 1_000)
            val runs = listOf(null, policy).map { lateness ->
                var record: NexmarkRecord? = null
                val retainedBytes = HeapProbe.retainedBytes {
                    record = NexmarkRun.run(
                        query = NexmarkQuery.Q7,
                        seed = 20261017L,
                        scale = scale,
                        policy = lateness,
                        drive = Drive.SIM,
                        batchSize = 100,
                        compareEvery = 10,
                        retraction = RetractionPlan(0.0),
                        env = nexmarkTestEnvironment(),
                    )
                    record
                }
                val verified = record as? NexmarkRecord.Verified
                    ?: error("Q7 growth probe was not verified: $record")
                println(
                    "NEXMARK_RUN events=$events policy=${lateness?.lateness ?: "none"} " +
                        "series=${seriesFrom(verified.header)} retainedBytes=$retainedBytes",
                )
                verified to retainedBytes
            }

            val unbounded = seriesFrom(runs[0].first.header)
            val evicting = seriesFrom(runs[1].first.header)
            val finding = windowGrowthFinding(unbounded, evicting, policy)
            findings += finding
            println("NEXMARK_GROWTH=events=$events $finding")
        }

        assertEquals(2, findings.size)
        assertEquals(listOf(GrowthVerdict.RETIRES, GrowthVerdict.RETIRES), findings.map { it.verdict })
    }
}
