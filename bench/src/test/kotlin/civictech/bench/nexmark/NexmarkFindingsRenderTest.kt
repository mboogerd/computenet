package civictech.bench.nexmark

import civictech.bench.FindingsRefusalException
import civictech.bench.RunEnvironment
import civictech.bench.micro.FootprintReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** BB-NEX findings rendering; the tagged path is the durable five-seed measurement. */
class NexmarkFindingsRenderTest {

    @Test
    fun `default lane renders coverage deviations growth and comparison caveat`() {
        val env = nexmarkTestEnvironment()
        val records = listOf(
            verifiedNexmark(NexmarkQuery.Q1, seed = 1L),
            verifiedNexmark(NexmarkQuery.Q3, seed = 2L),
            verifiedNexmark(NexmarkQuery.Q7, seed = 3L),
        )
        val growth = GrowthFinding(
            verdict = GrowthVerdict.RETIRES,
            unboundedSeries = listOf(
                WindowGrowthSample(0, 0, null),
                WindowGrowthSample(1, 1, null),
                WindowGrowthSample(2, 2, null),
            ),
            evictingSeries = listOf(
                WindowGrowthSample(0, 0, 0L),
                WindowGrowthSample(1, 1, 0L),
                WindowGrowthSample(2, 1, 0L),
            ),
            bound = 2L,
            note = "RETIRES: unbounded series grows while evicting series stays within bound 2",
        )

        val rendered = NexmarkFindings.render(records, env, "2026-10-19", growth)

        assertTrue(rendered.contains("| Q1 insert | 100.0 ± n/a applied delta elements/batch | |"))
        assertTrue(rendered.contains("| Q1 retract | 50.0 ± n/a applied delta elements/batch | |"))
        assertTrue(rendered.contains("| Q4 | NOT_EXPRESSIBLE | not_expressible |"))
        assertTrue(rendered.contains("Q7 unbounded-state series"))
        assertTrue(rendered.contains("Q7 evicting series (lateness=5_000 ms; live-window bound=2"))
        assertTrue(rendered.contains("floor=0"))
        assertTrue(rendered.contains("Q7 emits one bid per window"))
        assertTrue(rendered.contains("Candidate G-item"))
        assertTrue(rendered.contains("BB-TPCH 2026-10-16"))
        assertTrue(rendered.contains(NO_PUBLISHED_COMPARISON))
        assertEquals(1, listOf("FIRES", "RETIRES", "INCONCLUSIVE").count(rendered::contains))
    }

    @Test
    fun `unverified and failed records are refused before anything is rendered`() {
        val env = nexmarkTestEnvironment()
        val growth = minimalGrowth()
        val unverified = NexmarkRecord.Unverified(
            header = nexmarkTestHeader(query = NexmarkQuery.Q1),
            reason = "comparison skipped for speed",
        )
        val unverifiedFailure = assertThrows(FindingsRefusalException::class.java) {
            NexmarkFindings.render(listOf(verifiedNexmark(), unverified), env, "2026-10-19", growth)
        }
        assertTrue(unverifiedFailure.message.orEmpty().contains("unverified"))
        assertTrue(unverifiedFailure.message.orEmpty().contains("Q1"))

        val failed = NexmarkRecord.Failed(
            header = nexmarkTestHeader(query = NexmarkQuery.Q1),
            batchIndex = 4,
            differingRows = emptyList(),
        )
        val failedFailure = assertThrows(FindingsRefusalException::class.java) {
            NexmarkFindings.render(listOf(verifiedNexmark(), failed), env, "2026-10-19", growth)
        }
        assertTrue(failedFailure.message.orEmpty().contains("failed"))
    }

    @Test
    fun `state bound remains a coverage result`() {
        val env = nexmarkTestEnvironment()
        val stateBound = NexmarkRecord.StateBound(
            header = nexmarkTestHeader(query = NexmarkQuery.Q7),
            outlet = Q7_WINDOW_OUTLET,
            ceiling = 2L,
            batchIndex = 9,
        )
        val rendered = NexmarkFindings.render(
            records = listOf(verifiedNexmark(NexmarkQuery.Q1), stateBound),
            env = env,
            date = "2026-10-19",
            growth = minimalGrowth(),
        )
        assertTrue(rendered.contains("| Q7 | EXPRESSIBLE | state_bound |"))
        assertTrue(rendered.contains("outlet $Q7_WINDOW_OUTLET exceeded ceiling 2 at batch 9"))
    }

    @Test
    @Tag("bench")
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `renders the five seed SIM sweep and recomputed 10k growth finding`() {
        val harnessSha = requireNotNull(System.getProperty("civictech.bench.harnessSha")) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse HEAD>"
        }.takeIf { it.isNotBlank() }
            ?: error("civictech.bench.harnessSha must not be blank")
        val env = RunEnvironment.forRun(
            measuringJvm = FootprintReport.inProcessMeasuringJvm(),
            jmhMode =
                "Nexmark SIM collector-applied delta elements per insert/retract batch " +
                    "(JUnit @Tag(bench) probe)",
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = SEEDS.count(),
            harnessCommitSha = harnessSha,
        )
        val scale = NexmarkScale(events = 10_000, eventsPerSecond = 1_000)
        val policy = LatenessPolicy(5_000L)
        val configurations = listOf(
            null to listOf(NexmarkQuery.Q1, NexmarkQuery.Q2, NexmarkQuery.Q3, NexmarkQuery.Q7, NexmarkQuery.Q8),
            policy to listOf(NexmarkQuery.Q7, NexmarkQuery.Q8),
        )
        val records = configurations.flatMap { (lateness, queries) ->
            queries.map { query ->
                summarise(SEEDS.map { seed ->
                    NexmarkRun.run(
                        query = query,
                        seed = seed,
                        scale = scale,
                        policy = lateness,
                        drive = Drive.SIM,
                        batchSize = 100,
                        compareEvery = 1,
                        retraction = RetractionPlan(fraction = 0.5, seed = seed),
                        env = env,
                    )
                })
            }
        }

        val unbounded = growthRun(scale, policy = null, env = env)
        val evicting = growthRun(scale, policy = policy, env = env)
        val growth = windowGrowthFinding(
            unbounded = seriesFrom(unbounded.header),
            evicting = seriesFrom(evicting.header),
            policy = policy,
        )
        val rendered = NexmarkFindings.render(
            records = records,
            env = env,
            date = System.getProperty("civictech.bench.date")
                ?.takeIf { it.isNotBlank() }
                ?: "2026-10-19",
            growth = growth,
        )
        println("NEXMARK_FINDINGS_BEGIN")
        println(rendered)
        println("NEXMARK_FINDINGS_END")

        assertEquals(10, records.size)
        assertEquals(GrowthVerdict.RETIRES, growth.verdict)
        assertTrue(rendered.contains(NO_PUBLISHED_COMPARISON))
        assertTrue(rendered.contains("| Q8 | EXPRESSIBLE | verified |"))
    }

    private fun growthRun(
        scale: NexmarkScale,
        policy: LatenessPolicy?,
        env: civictech.bench.RunEnvironment,
    ): NexmarkRecord.Verified {
        val record = NexmarkRun.run(
            query = NexmarkQuery.Q7,
            seed = 20261017L,
            scale = scale,
            policy = policy,
            drive = Drive.SIM,
            batchSize = 5_000,
            compareEvery = 50,
            retraction = RetractionPlan(0.0),
            env = env,
        )
        return record as? NexmarkRecord.Verified
            ?: error("Q7 growth probe was not verified: $record")
    }

    private fun minimalGrowth() = GrowthFinding(
        verdict = GrowthVerdict.INCONCLUSIVE,
        unboundedSeries = listOf(WindowGrowthSample(0, 0, null)),
        evictingSeries = listOf(WindowGrowthSample(0, 0, 0L)),
        bound = 2L,
        note = "INCONCLUSIVE: fixture growth is not evidence",
    )

    private companion object {
        val SEEDS = 1L..5L
    }
}
