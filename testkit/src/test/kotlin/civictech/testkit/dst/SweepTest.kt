package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.IdentityHashMap
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

    /** Configuration for the [dropFrom] fixture faults; only `encode` reads it (as [ShrinkerTest]). */
    private val configs = IdentityHashMap<Fault, JsonObject>()

    private val codec = FaultCodecs.register(
        kind = DROP_KIND,
        owns = { it in configs.keys },
        encode = { configs.getValue(it) },
        decode = { id, params ->
            dropFrom(
                id = id,
                edge = params.getValue("edge").jsonPrimitive.content,
                fromStep = params.getValue("fromStep").jsonPrimitive.int,
                count = params.getValue("count").jsonPrimitive.int,
            )
        },
    )

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        GraphRegistry.register(GRAPH)
        CheckRegistry.register(CHECK_ID, CHECK)
        CheckRegistry.register(SWEEP_BACKED_CHECK_ID, SWEEP_BACKED_CHECK)
    }

    @AfterTest
    fun tearDown() {
        FaultCodecs.unregister(codec.kind)
        GraphRegistry.unregister(GRAPH.id)
        CheckRegistry.unregister(CHECK_ID)
        CheckRegistry.unregister(SWEEP_BACKED_CHECK_ID)
    }

    /** Drops up to [count] frames on [edge] at or after step [fromStep] — [ShrinkerTest]'s fixture. */
    private fun dropFrom(id: String, edge: String, fromStep: Int, count: Int): Fault {
        val fault = ScriptedFault(
            id = id,
            targets = listOf(FaultTarget.Edge(edge)),
            description = "drop up to $count frames on $edge from step $fromStep",
            onInstall = { world ->
                var dropped = 0
                world.edges.intercept(edge) { frame, step ->
                    if (step >= fromStep && dropped < count) {
                        dropped++
                        world.trace.fault(id, port = edge)
                        emptyList()
                    } else {
                        listOf(frame)
                    }
                }
            },
        )
        configs[fault] = buildJsonObject {
            put("edge", edge)
            put("fromStep", fromStep)
            put("count", count)
        }
        return fault
    }

    private fun lossPlan(count: Int): FaultPlan =
        FaultPlan.of(IDENTITY_SEED, dropFrom(ESSENTIAL, "a->b", fromStep = 2, count = count))

    private fun paramOf(plan: FaultPlan, faultId: String, param: String): Int =
        FaultCodecs.encode(plan.faults.single { it.id == faultId })
            .params.getValue(param).jsonPrimitive.int

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

        val thrown = assertFailsWith<SweepFailure> { report.assertAllPassed() }
        assertEquals(
            "DST sweep suite=$SUITE graph=${GRAPH.id} failed: synthetic failure on seed 3",
            thrown.message,
            "the thrown message is the failure mode alone — see SweepFailure",
        )
        assertTrue(
            thrown.detail.startsWith("failed on 7 of 100 seeds; first: seed=3 — "),
            "forEachSeed's summary shape is preserved, in the detail: ${thrown.detail}",
        )
        assertEquals("synthetic failure on seed 3", thrown.cause!!.message, "first failure is the cause")
        assertTrue(FAILING_SEEDS.all { "$SUITE/$it.json" in thrown.detail }, thrown.detail)
        assertEquals(
            thrown.detail,
            thrown.suppressed.single().message,
            "[CHA1-39]'s density stays visible to a human, as a suppressed throwable",
        )
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

        val thrown = assertFailsWith<SweepFailure> { report.assertAllPassed() }
        assertEquals(
            "DST sweep suite=$SUITE graph=${SelfTestGraphs.livelock().id} failed: BUDGET_EXHAUSTED",
            thrown.message,
        )
        assertTrue(thrown.detail.startsWith("failed on 3 of 3 seeds; first: seed=0 — BUDGET_EXHAUSTED"), thrown.detail)
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

    // ------------------------------------- the check-identity property (computenet-umx.4)

    /**
     * The check message a sweep raises is the **identity** of the failure, and it is stable
     * across two different fault plans that lose different amounts of traffic.
     *
     * This is the half of computenet-umx.4 that can be asserted without a shrinker: [CHA1-36]'s
     * `FailurePredicate.sameFailingCheck` compares `report.failingCheck.message` and nothing
     * else, so byte-identity of that string across two genuinely different runs is exactly what
     * the shrinker needs and exactly what the old `failed on N of M seeds; first: seed=K` shape
     * denied it.
     *
     * The delivered-frame assertion is what keeps this from being vacuous: the two plans must
     * genuinely lose different amounts, or "the messages match" would be trivially true.
     */
    @Test
    fun aSweepBackedCheckMessageIsIdenticalAcrossPlansThatLoseDifferentAmounts() {
        val heavyRun = DstRun(GRAPH, lossPlan(count = 6), BUDGET, SWEEP_BACKED_CHECK)
        val heavy = heavyRun.execute()
        val light = DstRun(GRAPH, lossPlan(count = 1), BUDGET, SWEEP_BACKED_CHECK).execute()

        assertEquals(DstOutcome.FAILED, heavy.outcome, heavy.summary())
        assertEquals(DstOutcome.FAILED, light.outcome, light.summary())

        val deliveredHeavy = heavy.trace.count { it.port == "recv" }
        val deliveredLight = light.trace.count { it.port == "recv" }
        assertTrue(
            deliveredHeavy != deliveredLight,
            "the two plans must lose different amounts or this test is vacuous: " +
                "$deliveredHeavy vs $deliveredLight of $EXPECTED_DELIVERIES",
        )

        assertEquals(
            heavy.failingCheck!!.message,
            light.failingCheck!!.message,
            "a sweep-backed check must name the failure mode, not the run's density",
        )
        val recorded = DstArtifact.of(heavyRun, heavy, suite = SUITE, checkId = SWEEP_BACKED_CHECK_ID).observed
        assertTrue(
            FailurePredicate.sameFailingCheck.reproduces(recorded, light),
            "[CHA1-36]'s predicate must read the lighter plan as the same failure: " +
                "${heavy.failingCheck!!.message} / ${light.failingCheck!!.message}",
        )
    }

    /**
     * The shrinker half: a legitimate reduction of a sweep-backed failing plan is **accepted**.
     *
     * The strategy lowers the number of frames the fault destroys, which is unambiguously less
     * adversarial and still fails the property. Before computenet-umx.4 every such candidate
     * failed with a different density line, `sameFailingCheck` read it as a different failure,
     * and the shrink returned the original plan with zero reductions accepted — silently.
     */
    @Test
    fun aReductionOverASweepBackedCheckIsAcceptedByTheShrinker() {
        val run = DstRun(GRAPH, lossPlan(count = 6), BUDGET, SWEEP_BACKED_CHECK)
        val report = run.execute()
        assertEquals(DstOutcome.FAILED, report.outcome, report.summary())
        val artifact = DstArtifact.of(run, report, suite = SUITE, checkId = SWEEP_BACKED_CHECK_ID)

        val result = PlanShrinker.shrink(
            artifact,
            strategy = ReductionStrategies.numericParamToward(DROP_KIND, "count", target = 1.0),
        )

        assertTrue(
            result.record.reductionsAccepted >= 1,
            "a smaller loss still fails the same property and must be accepted: " +
                result.trail.joinToString("\n"),
        )
        assertEquals(
            1,
            paramOf(result.plan, ESSENTIAL, "count"),
            "the shrink reaches the strategy's extreme — the plan that destroys one frame still " +
                "fails the same property: ${result.trail.joinToString("\n")}",
        )
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

        // --------------------------------- the sweep-backed check fixture (computenet-umx.4)

        private const val DROP_KIND = "dst-selftest-sweep-drop-n"
        private const val ESSENTIAL = "drop-ab"
        private const val IDENTITY_SEED = 41L
        private const val SWEEP_BACKED_CHECK_ID = "dst-selftest-sweep-backed"
        private const val INNER_SUITE = "dst-selftest-sweep-inner"

        /** `chains * (rounds + 1)` hops in each direction on [GRAPH], with no frame lost. */
        private const val EXPECTED_DELIVERIES = 2 * 4 * 2

        /**
         * How many sub-cases the inner sweep report covers.
         *
         * Wider than [EXPECTED_DELIVERIES] on purpose: if the range were narrower the derived
         * failure count would saturate, two genuinely different plans would produce the same
         * density line by accident, and the tests below would pass against the unfixed code.
         */
        private val INNER_SEEDS = 0L..EXPECTED_DELIVERIES.toLong()

        /**
         * A check whose failure path is [DstSweepReport.assertAllPassed] — the reachability the
         * whole ticket is about.
         *
         * The inner entries are **synthesized** rather than produced by a nested [dstSweep], and
         * that is the honest instrument: what is under test is the message
         * `assertAllPassed` raises, which is a pure function of the entries, and a nested sweep
         * would have cost ten full simulations per candidate to exercise the same one line. How
         * many entries fail is derived from the outer run's own traffic loss, so a fault plan
         * that destroys more frames produces a denser inner sweep — which is precisely the
         * run-varying number that used to leak into the check's identity.
         */
        private val SWEEP_BACKED_CHECK = DstCheck { world ->
            val delivered = world.traceEvents().count { it.port == "recv" }
            val missing = (EXPECTED_DELIVERIES - delivered).coerceIn(0, INNER_SEEDS.count())
            if (missing > 0) {
                val firstBad = INNER_SEEDS.last - missing + 1
                val entries = INNER_SEEDS.map { seed ->
                    SweepEntry(
                        seed = seed,
                        report = null,
                        error = if (seed >= firstBad) AssertionError("chain deliveries were lost") else null,
                        artifact = null,
                    )
                }
                DstSweepReport(INNER_SUITE, INNER_SEEDS, GRAPH.id, DstDriver.IN_PROCESS, entries).assertAllPassed()
            }
        }
    }
}
