package civictech.testkit.dst

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CHA1-38], [CHA1-39], [CHA1-40] and BS-15 — sweeps report density, not the first failure.
 *
 * The failing property here is keyed off the seed directly (`seed % 14 == 3`) rather than
 * being a real emergent bug. That is deliberate and is the honest instrument: what is under
 * test is the sweep's *accounting* — that all 100 seeds run, that 7 failures are reported as
 * `failed on 7 of 100`, that all 7 artifacts exist and that the first failure is the thrown
 * cause. A property whose failure density depended on scheduling would make the expected
 * numbers a moving target and would test the graph rather than the sweep.
 */
class SweepTest {

    private val root = File("build/dst-selftest/sweep")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        GraphRegistry.register(GRAPH)
        CheckRegistry.register(CHECK_ID, CHECK)
    }

    @AfterTest
    fun tearDown() {
        GraphRegistry.unregister(GRAPH.id)
        CheckRegistry.unregister(CHECK_ID)
    }

    private fun sweep(
        seeds: LongRange = 0L..99L,
        driver: DstDriver = DstDriver.IN_PROCESS,
    ): DstSweepReport = dstSweep(
        suite = SUITE,
        seeds = seeds,
        graph = GRAPH,
        checkId = CHECK_ID,
        budget = BUDGET,
        driver = driver,
        artifactRoot = root,
    )

    /**
     * BS-15 ([CHA1-38], [CHA1-39]): 7 of 100 seeds fail; the report says `failed on 7 of 100`,
     * lists all seven artifact paths, records the executed range `0..99`, and the first failure
     * is the thrown `cause`.
     */
    @Test
    fun sweepReportsDensityNotFirstFailure_BS15() {
        val report = sweep()

        assertEquals(100, report.total)
        assertEquals(0L..99L, report.seedRange)
        assertEquals(FAILING_SEEDS, report.failures.map { it.seed })
        assertEquals("failed on 7 of 100", report.density)

        assertEquals(FAILING_SEEDS, report.artifactPaths.map { it.nameWithoutExtension.toLong() })
        assertTrue(report.artifactPaths.all { it.isFile }, "every listed artifact path exists")
        assertEquals(
            FAILING_SEEDS.map { File(root, "$SUITE/$it.json").absolutePath },
            report.artifactPaths.map { it.absolutePath },
        )

        val thrown = assertFailsWith<AssertionError> { report.assertAllPassed() }
        assertTrue(
            thrown.message!!.startsWith("failed on 7 of 100 seeds; first: seed=3 — "),
            "forEachSeed's summary shape is preserved: ${thrown.message}",
        )
        assertEquals("synthetic failure on seed 3", thrown.cause!!.message, "first failure is the cause")
        assertTrue(FAILING_SEEDS.all { "$SUITE/$it.json" in thrown.message!! }, thrown.message!!)
    }

    /**
     * [CHA1-39]: every seed runs regardless of earlier failures — asserted on the executed
     * entries, and enforced structurally: a report whose entries do not cover its own recorded
     * range cannot be constructed at all.
     */
    @Test
    fun everySeedRunsRegardlessOfEarlierFailures_CHA1_39() {
        val report = sweep()
        assertEquals((0L..99L).toList(), report.entries.map { it.seed })
        assertTrue(report.entries.all { it.report != null }, "every seed produced a report")

        val narrowed = assertFailsWith<IllegalArgumentException> {
            DstSweepReport(SUITE, 0L..99L, GRAPH.id, DstDriver.IN_PROCESS, report.entries.take(50))
        }
        assertTrue("CHA1-39" in narrowed.message!!, narrowed.message!!)
    }

    /**
     * [CHA1-38] as the epic states it (§9 risk 8): the rig **records** the executed range so a
     * narrowed range is detectable in review. It does not enforce the rule, and nothing here
     * claims it does.
     */
    @Test
    fun sweepRecordsTheExecutedSeedRange_CHA1_38() {
        val summary = sweep().summary()
        assertTrue("seeds=0..99 (executed 100)" in summary, summary)
        assertTrue("failed on 7 of 100" in summary, summary)

        val narrowedButHonest = sweep(seeds = 0L..9L)
        assertTrue("seeds=0..9 (executed 10)" in narrowedButHonest.summary(), narrowedButHonest.summary())
        assertEquals("failed on 1 of 10", narrowedButHonest.density)
    }

    /** An artifact a sweep wrote is a replay artifact like any other ([CHA1-31], [CHA1-32]). */
    @Test
    fun aSweepArtifactReplays() {
        val report = sweep(seeds = 0L..5L)
        val artifact = report.artifactPaths.single()
        assertEquals(3L, DstArtifacts.read(artifact).seed)

        val result = DstReplay.from(artifact)
        assertEquals(ReplayVerdict.REPLAYED, result.verdict, result.message)
        assertEquals("synthetic failure on seed 3", result.report!!.failingCheck!!.message)
    }

    /**
     * [CHA1-40]: a sweep driven across JVMs is marked non-deterministic, says so in its
     * summary, and its artifacts grade `INDETERMINATE` rather than claiming reproducibility.
     */
    @Test
    fun multiJvmSweepIsMarkedNonDeterministic_CHA1_40() {
        val report = sweep(seeds = 0L..5L, driver = DstDriver.MULTI_JVM)

        assertTrue(report.nonDeterministic)
        assertTrue("NON-DETERMINISTIC — no replay reproducibility claimed ([CHA1-40])" in report.summary())

        val result = DstReplay.from(report.artifactPaths.single())
        assertEquals(ReplayVerdict.INDETERMINATE, result.verdict, result.message)

        assertTrue(!sweep(seeds = 0L..5L).nonDeterministic, "the in-process control is not marked")
    }

    /**
     * A seed that never quiesced fails the sweep without claiming a verdict about the property
     * ([CHA1-03]): it is counted apart from the check failures, and it is not a pass.
     */
    @Test
    fun budgetExhaustedSeedsFailTheSweepWithoutClaimingAVerdict() {
        val report = dstSweep(
            suite = SUITE,
            seeds = 0L..2L,
            graph = SelfTestGraphs.livelock(),
            budget = 50,
            artifactRoot = root,
        )

        assertEquals("failed on 0 of 3", report.density, "budget exhaustion is not a check failure")
        assertEquals(listOf(0L, 1L, 2L), report.exhausted.map { it.seed })
        assertTrue("budget exhausted on 3 (no verdict claimed)" in report.summary(), report.summary())
        assertTrue(report.artifactPaths.isEmpty(), "no failure artifact for a run that disproved nothing")

        val thrown = assertFailsWith<AssertionError> { report.assertAllPassed() }
        assertTrue(thrown.message!!.startsWith("failed on 3 of 3 seeds; first: seed=0 — BUDGET_EXHAUSTED"), thrown.message!!)
    }

    /** [CHA1-54] holds for sweeps too: the artifact root is validated before any seed runs. */
    @Test
    fun aSweepRootOutsideTheBuildDirectoryIsRefusedUpFront_CHA1_54() {
        val e = assertFailsWith<IllegalArgumentException> {
            dstSweep(
                suite = SUITE,
                seeds = 0L..99L,
                graph = GRAPH,
                checkId = CHECK_ID,
                budget = BUDGET,
                artifactRoot = File(System.getProperty("java.io.tmpdir"), "dst-outside"),
            )
        }
        assertTrue("CHA1-54" in e.message!!, e.message!!)
    }

    companion object {
        private const val SUITE = "dst-selftest-sweep"
        private const val CHECK_ID = "dst-selftest-seed-keyed"
        private const val BUDGET = 5_000

        /** Distinct from [ReplayTest]'s graph: a `GraphSpec` id is a globally registered name. */
        private val GRAPH: GraphSpec = SelfTestGraphs.crossTalk(chains = 2, rounds = 3)

        /** See the class KDoc: a synthetic, seed-keyed property, so the density is exact. */
        private val CHECK = DstCheck { world ->
            if (world.seed % 14 == 3L) throw AssertionError("synthetic failure on seed ${world.seed}")
        }

        private val FAILING_SEEDS = (0L..99L).filter { it % 14 == 3L }
    }
}
