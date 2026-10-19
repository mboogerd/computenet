package civictech.bench.nexmark

import civictech.bench.micro.HeapProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** The expensive Q7 state-growth probe at the two Nexmark measurement scales. */
@Tag("bench")
class StateGrowthBenchTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    fun `Q7 state growth prints unbounded and evicting series at both scales`() {
        HeapProbe.requireCollectableHeap()
        val policy = LatenessPolicy(5_000L)
        val findings = mutableListOf<GrowthFinding>()
        val retainedUnboundedBytes = mutableMapOf<Int, Long>()

        listOf(10_000, 100_000).forEach { events ->
            val scale = NexmarkScale(events = events, eventsPerSecond = 1_000)
            val runs = listOf(null, policy).map { lateness ->
                var record: NexmarkRecord? = null
                var maintained: civictech.bench.nexmark.queries.MaintainedQuery? = null
                val retainedBytes = HeapProbe.retainedBytes {
                    record = NexmarkRun.run(
                        query = NexmarkQuery.Q7,
                        seed = 20261017L,
                        scale = scale,
                        policy = lateness,
                        drive = Drive.SIM,
                        // Keep the 10k/100k event scales while reducing the
                        // number of SIM phases for this retained-state probe.
                        // Keep at least three samples at the 10k scale so the
                        // growth classifier is not forced to INCONCLUSIVE.
                        batchSize = 5_000,
                        // The prescribed compareEvery=10 exceeded the ten-minute
                        // probe budget on this SIM host at 100k events; 50 keeps
                        // the same scale while remaining a bounded evidence run.
                        compareEvery = 50,
                        retraction = RetractionPlan(0.0),
                        env = nexmarkTestEnvironment(),
                        hooks = NexmarkRunHooks(
                            emissionCounters = { query ->
                                maintained = query
                                query.emissionCounters()
                            },
                        ),
                    )
                    checkNotNull(maintained) { "Q7 run did not expose its maintained query" }
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
            retainedUnboundedBytes[events] = runs[0].second
            val finding = windowGrowthFinding(unbounded, evicting, policy)
            findings += finding
            println("NEXMARK_GROWTH=events=$events $finding")
        }

        assertEquals(2, findings.size)
        // At 1,000 events/s, 10,000 events span exactly one 10 s WINDOW_MS. The
        // unbounded series therefore peaks at one window, so its RETIRES result
        // is a classifier sanity check, not growth evidence; the 100,000-event
        // pair is the evidence that retained unbounded state grows.
        assertTrue(
            retainedUnboundedBytes.getValue(100_000) > retainedUnboundedBytes.getValue(10_000),
            "retained Q7 state should grow from 10k to 100k events: $retainedUnboundedBytes",
        )
        assertEquals(listOf(GrowthVerdict.RETIRES, GrowthVerdict.RETIRES), findings.map { it.verdict })
    }
}
