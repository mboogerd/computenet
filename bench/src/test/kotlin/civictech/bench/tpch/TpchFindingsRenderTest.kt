package civictech.bench.tpch

import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.micro.FootprintReport
import civictech.bench.micro.HeapProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** BB-TPCH findings render; its tagged path uses the BS-6 heap-path instrument. */
class TpchFindingsRenderTest {

    @Test
    @Tag("bench")
    fun `renders a five-seed SIM sweep with coverage and BS-6 evidence`() {
        val harnessSha = requireNotNull(System.getProperty("civictech.bench.harnessSha")) {
            "set -Dcivictech.bench.harnessSha=<git rev-parse HEAD>"
        }.takeIf { it.isNotBlank() }
            ?: error("civictech.bench.harnessSha must not be blank")
        val env = RunEnvironment.forRun(
            measuringJvm = FootprintReport.inProcessMeasuringJvm(),
            jmhMode =
                "TPC-H SIM collector-applied delta elements per RF1/RF2 batch " +
                    "(JUnit @Tag(bench) probe)",
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = SEEDS.count(),
            harnessCommitSha = harnessSha,
        )
        val records = TpchQuery.entries
            .filter { it.tier == TpchTier.HAND_1 }
            .flatMap { query ->
                SEEDS.map { seed ->
                    TpchRun.run(
                        query = query,
                        seed = seed,
                        sf = 0.001,
                        plan = RefreshPlan.alternating(seed = seed, sf = 0.001, batches = 2),
                        drive = Drive.SIM,
                        env = env,
                    )
                }
            }

        val heapFinding = bs6Finding()
        val rendered = TpchFindings.render(
            records = records,
            env = env,
            date = System.getProperty("civictech.bench.date")
                ?.takeIf { it.isNotBlank() }
                ?: "2026-10-16",
            bs6Verdict = heapFinding.verdict,
            bs6Series =
                "balanced Q3 SF 0.001 stream, live rows flat; least-squares slope " +
                    "${heapFinding.slopeBytesPerRefresh} B/refresh projected over the " +
                    "1000-20000-refresh fit window vs 2x the first-window spread of " +
                    "${heapFinding.firstWindowSpreadBytes} B (StateAccounting.heapGrowthFinding rule)",
        )
        println(rendered)

        assertEquals(
            22,
            rendered.lineSequence().count {
                it.contains("| HAND_1 |") || it.contains("| BLOCKED_2 |")
            },
        )
        assertTrue(rendered.contains("Trigger: G-42 — ${heapFinding.verdict.name}"))
        assertTrue(rendered.contains("Candidate G-item"))
        assertTrue(rendered.contains("[BEN1-03]"))
        assertTrue(rendered.contains("[BB-TPCH-30]"))
        assertEquals(1, listOf("FIRES", "RETIRES", "INCONCLUSIVE").count(rendered::contains))
    }

    @Test
    fun `default lane renders a small fabricated record set`() {
        val env = tpchTestEnvironment()
        val records = listOf(TpchQuery.Q01, TpchQuery.Q03).map { query ->
            TpchRecord.Verified(
                header = RunHeader(
                    seed = 7L,
                    sf = 0.001,
                    query = query,
                    refreshPlan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 2),
                    drive = Drive.SIM,
                    scheduler = "SimulationController",
                    loadMode = LoadModeLabel("volatile", "per-message"),
                    env = env,
                    retainedState = RetainedState(emptyMap(), emptyMap(), GrowthNote.Flat),
                ),
                load = Figure.Work(1),
                rf1 = listOf(BatchFigure(RefreshKind.RF1, 1, Figure.Work(10))),
                rf2 = listOf(BatchFigure(RefreshKind.RF2, 2, Figure.Work(12))),
                finalDrift = Comparison.Equal,
            )
        }

        val rendered = TpchFindings.render(
            records = records,
            env = env,
            date = "2026-10-16",
            bs6Verdict = GrowthVerdict.INCONCLUSIVE,
            bs6Series = "insufficient heap-path samples",
        )

        assertTrue(rendered.contains("| Q01 RF1 | 10.0 ± n/a applied delta elements/batch | |"))
        assertTrue(rendered.contains("| Q03 RF2 | 12.0 ± n/a applied delta elements/batch | |"))
        assertTrue(
            rendered.contains(
                "SIM maintenance-work caveat (all RF1/RF2 rows above): " +
                    "`applied delta elements/batch` sums adds+dels or puts+removals applied " +
                    "by every materialized query-outlet collector between quiescence points; " +
                    "the same logical change counts once at each observed outlet it reaches. " +
                    "It is query-specific propagation volume, not elapsed time, internal " +
                    "operator operations, or deliveries on unmaterialized edges. RF1/RF2 " +
                    "host-scheduler task counts are query-independent ingress instrumentation " +
                    "and are not retained in these rows.",
            ),
        )
        assertTrue(rendered.contains("| Q02 | BLOCKED_2 | hand | blocked |"))
        assertTrue(rendered.contains("aggregate output as a relation"))
    }

    private fun bs6Finding(): HeapGrowthFinding {
        HeapProbe.requireCollectableHeap()
        val samples = (500..20_000 step 500).map { target ->
            var flat = false
            val bytes = HeapProbe.retainedBytes {
                val session = TpchRun.openRefreshSession(
                    query = TpchQuery.Q03,
                    seed = 20261016L,
                    sf = 0.001,
                    drive = Drive.SIM,
                    ordersPerRefresh = 50,
                )
                val baseline = session.retainedState().liveRows
                repeat(target / 50) { batch ->
                    val kind = if (batch % 2 == 0) RefreshKind.RF1 else RefreshKind.RF2
                    session.prepare(kind, restorePrevious = false)
                    session.applyPrepared(kind)
                }
                flat = session.retainedState().liveRows == baseline
                session
            }
            HeapGrowthSample(target, bytes, flat)
        }
        val finding = heapGrowthFinding(samples)
        println(
            "BS-6 ${finding.verdict}: growth=${finding.growth}, " +
                "slope=${finding.slopeBytesPerRefresh}, " +
                "firstWindowSpread=${finding.firstWindowSpreadBytes}",
        )
        assertTrue(samples.all(HeapGrowthSample::liveRowsFlat), "balanced stream changed live rows")
        return finding
    }

    private companion object {
        val SEEDS = 1L..5L
    }
}
