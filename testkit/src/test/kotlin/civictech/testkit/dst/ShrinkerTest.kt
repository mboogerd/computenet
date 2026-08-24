package civictech.testkit.dst

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CHA1-35], [CHA1-36], [CHA1-37], BS-3 and epic §9 risk 7 — the fault-plan shrinker.
 *
 * ## What each test is guarding against
 *
 * A shrinker is easy to fake in two opposite directions, and the tests here are chosen to
 * catch both:
 *
 *  - **Accepting everything.** A "shrinker" that drops faults without re-running would reduce
 *    this fixture's plan to the empty plan and report a beautiful result, because two of its
 *    three faults are genuinely irrelevant and the third is not. So
 *    [shrinkerReducesThePlanAndReVerifiesEveryReduction_CHA1_36] asserts the *surviving* fault
 *    by id, and [aReductionThatStopsReproducingIsDiscarded_CHA1_36] hands the shrinker a
 *    strategy whose only proposal is the essential fault and requires it to be refused.
 *  - **Varying the seed.** [CHA1-35] is already structural — [PlanRecord] has no seed field —
 *    so the interesting half of BS-3 is behavioural: a *strategy* that varies the seed must be
 *    rejected rather than quietly run.
 *    [aSeedVaryingReductionStrategyIsRejected_BS3] injects exactly that deliberately-wrong
 *    strategy. It is a test-supplied instrument, not a mutation of production code, and
 *    without the guard in [PlanShrinker] it would run and be accepted.
 *
 * Every accepted reduction here costs a full simulation, which is why the fixture graph is
 * small and the attempt caps are low: the suite must stay a unit test.
 */
class ShrinkerTest {

    // ------------------------------------------------------------------ fixtures

    /**
     * Configuration for the fixture faults, standing in for the fields a real fault class
     * carries. `Fault` is sealed to `:testkit` main, so a test source set cannot implement it
     * and the fixtures are [ScriptedFault]s, which hold lambdas and cannot encode themselves.
     *
     * Only `encode` reads this table; `decode` rebuilds purely from the JSON, which is what
     * makes [ReductionStrategies.numericParamToward] meaningful — it edits the record, and only
     * a genuine decode-from-parameters can turn that into a different experiment.
     */
    private val configs = IdentityHashMap<Fault, JsonObject>()

    /**
     * Drops up to [count] frames on [edge], at or after step [fromStep].
     *
     * [count] exists so the fixture has a parameter whose *less adversarial* direction is
     * unambiguous — fewer frames destroyed — which is what a semantics-aware
     * [ReductionStrategies.numericParamToward] strategy needs. The counter is created in
     * `install`, so two installs of one configuration never share it.
     */
    private fun dropFrom(id: String, edge: String, fromStep: Int, count: Int = Int.MAX_VALUE): Fault {
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

    private val root = File("build/dst-selftest/shrink")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        GraphRegistry.register(GRAPH)
        CheckRegistry.register(CHECK_ID, CHECK)
    }

    @AfterTest
    fun tearDown() {
        FaultCodecs.unregister(codec.kind)
        GraphRegistry.unregister(GRAPH.id)
        CheckRegistry.unregister(CHECK_ID)
    }

    /**
     * The failing run every test shrinks: one fault that actually breaks the property and two
     * that never fire (their window starts long after the run ends).
     */
    private fun noisyFailingPlan(seed: Long = SEED): FaultPlan = FaultPlan.of(
        seed,
        dropFrom(ESSENTIAL, "a->b", fromStep = 2),
        dropFrom("noise-ab-late", "a->b", fromStep = 9_999),
        dropFrom("noise-ba-late", "b->a", fromStep = 9_999),
    )

    private fun artifactOf(plan: FaultPlan): DstArtifact {
        val run = DstRun(GRAPH, plan, BUDGET, CHECK)
        val report = run.execute()
        assertEquals(
            DstOutcome.FAILED,
            report.outcome,
            "the fixture must fail, or every shrink assertion here is vacuous",
        )
        return DstArtifact.of(run, report, suite = SUITE, checkId = CHECK_ID)
    }

    private fun faultIds(plan: FaultPlan): List<String> = plan.faults.map { it.id }

    private fun paramOf(plan: FaultPlan, faultId: String, param: String): Int =
        FaultCodecs.encode(plan.faults.single { it.id == faultId })
            .params.getValue(param).jsonPrimitive.int

    // ------------------------------------------------------------------ CHA1-36

    /**
     * [CHA1-36]: the shrinker drops the two inert faults and keeps the one whose removal stops
     * the failure reproducing — every reduction re-run in full, the non-reproducing one
     * discarded.
     *
     * The surviving fault is asserted **by id**. That is the discriminating assertion: a
     * shrinker that skipped re-verification would drop all three and pass a test that only
     * checked the plan got smaller.
     */
    @Test
    fun shrinkerReducesThePlanAndReVerifiesEveryReduction_CHA1_36() {
        val artifact = artifactOf(noisyFailingPlan())

        val result = PlanShrinker.shrink(artifact)

        assertEquals(listOf(ESSENTIAL), faultIds(result.plan), result.trail.joinToString("\n"))
        assertEquals(2, result.record.reductionsAccepted, "both inert faults are droppable")
        assertTrue(result.record.attempts >= 3, "the essential fault was proposed and re-run too: ${result.record}")
        assertTrue(!result.record.stoppedEarly, "the search exhausted its candidates: ${result.record}")
        assertEquals("no further reduction reproduced the failure", result.record.stopReason)

        // The discarded attempts are named, and they are the essential fault's.
        val discarded = result.trail.filter { it.startsWith("discarded:") }
        assertTrue(discarded.isNotEmpty(), result.trail.joinToString("\n"))
        assertTrue(discarded.all { ESSENTIAL in it }, discarded.joinToString("\n"))
        assertTrue(discarded.any { "ran to PASSED" in it }, discarded.joinToString("\n"))

        // And the shrunk plan really does still fail the same way.
        val reRun = DstRun(GRAPH, result.plan, BUDGET, CHECK).execute()
        assertEquals(DstOutcome.FAILED, reRun.outcome)
        assertEquals(artifact.observed.failingCheck, reRun.failingCheck!!.message)

        // The summary claims local, strategy-relative minimality — never "the minimum".
        assertTrue("locally minimal under the strategy" in result.summary(), result.summary())
    }

    /**
     * [CHA1-36]'s other half: a reduction that no longer fails with the same failing check is
     * **discarded**, and the plan is left as it was.
     *
     * The strategy here proposes only the one reduction that cannot be accepted. A shrinker
     * that trusted its strategy would return a two-fault plan that passes.
     */
    @Test
    fun aReductionThatStopsReproducingIsDiscarded_CHA1_36() {
        val artifact = artifactOf(noisyFailingPlan())
        val dropTheEssentialFault = ReductionStrategy { plan, _ ->
            listOf(Reduction("drop the essential fault", plan.without(ESSENTIAL)))
        }

        val result = PlanShrinker.shrink(artifact, strategy = dropTheEssentialFault)

        assertEquals(1, result.record.attempts, "the candidate was re-run, not rejected on inspection")
        assertEquals(0, result.record.reductionsAccepted)
        assertEquals(faultIds(artifact.plan()), faultIds(result.plan), "nothing was reduced")
        assertTrue(!result.record.stoppedEarly, "candidates ran out, no bound was hit: ${result.record}")
        assertTrue(result.trail.any { it.startsWith("discarded:") && "ran to PASSED" in it }, "${result.trail}")
    }

    /**
     * A different failing check is not the same failure ([CHA1-36]): the predicate compares the
     * failing-check message, so a reduction whose run fails for another reason is discarded.
     *
     * Graded through [FailurePredicate] directly, because manufacturing a second, differently
     * failing plan for this graph would test the fixture rather than the predicate.
     */
    @Test
    fun theSameFailurePredicateComparesTheFailingCheckNotTheDigest_CHA1_36() {
        val artifact = artifactOf(noisyFailingPlan())
        val recorded = artifact.observed
        val same = DstRun(GRAPH, artifact.plan(), BUDGET, CHECK).execute()
        assertTrue(FailurePredicate.sameFailingCheck.reproduces(recorded, same), "control")

        // A shrunk plan normally moves the step count, the trace length and the digest. Those
        // are exactly what DstReplay.grade compares, which is why shrinking cannot reuse it.
        val moved = same.copy(
            steps = same.steps + 7,
            traceDigest = TraceDigest("00".repeat(32)),
            trace = same.trace.drop(1),
            failingCheck = same.failingCheck!!.copy(step = same.failingCheck!!.step + 7),
        )
        assertTrue(
            FailurePredicate.sameFailingCheck.reproduces(recorded, moved),
            "a moved step count and a different digest are the expected shape of a real reduction",
        )
        assertEquals(
            ReplayVerdict.DIVERGED,
            DstReplay.grade(artifact, moved).verdict,
            "the replay predicate would have rejected it — the two predicates answer different questions",
        )

        val otherCheck = same.copy(failingCheck = same.failingCheck!!.copy(message = "some other property broke"))
        assertTrue(!FailurePredicate.sameFailingCheck.reproduces(recorded, otherCheck))

        val passed = same.copy(outcome = DstOutcome.PASSED, failingCheck = null)
        assertTrue(!FailurePredicate.sameFailingCheck.reproduces(recorded, passed))
    }

    // ------------------------------------------------------------------ CHA1-35 / BS-3

    /**
     * BS-3, the assertion half: the artifact's [DstArtifact.seed] is **byte-identical** before
     * and after shrinking, read out of the JSON text rather than from the object, and no plan —
     * original or shrunk — carries a seed of its own.
     */
    @Test
    fun theSeedIsByteIdenticalBeforeAndAfterShrinking_BS3() {
        val artifact = artifactOf(noisyFailingPlan())
        val before = DstArtifacts.write(artifact, root)
        val seedTextBefore = seedTextOf(before)

        val result = PlanShrinker.shrink(artifact)
        val after = DstArtifacts.write(result.artifact, root)

        assertEquals(seedTextBefore, seedTextOf(after), "[CHA1-35] the seed field is untouched by shrinking")
        assertEquals(SEED, Json.parseToJsonElement(after.readText()).jsonObject.getValue("seed").jsonPrimitive.long)

        val obj = Json.parseToJsonElement(after.readText()).jsonObject
        assertTrue("seed" !in obj.getValue("plan").jsonObject.keys, "a stored plan carries no seed of its own")
        assertTrue("seed" !in obj.getValue("shrunkPlan").jsonObject.keys, "nor does a stored shrunk plan")

        val reRead = DstArtifacts.read(after)
        assertEquals(SEED, reRead.seed)
        assertEquals(SEED, reRead.plan().seed)
        assertEquals(SEED, reRead.shrunkPlan()!!.seed)
    }

    /**
     * BS-3, the self-test half: a **deliberately seed-varying reduction strategy** — supplied
     * by this test, never present in production code — is rejected, loudly, naming [CHA1-35]
     * and both seeds, before the candidate is ever run.
     *
     * This is the mutation that must not survive. Delete the seed guard from [PlanShrinker] and
     * the strategy below runs, its candidate reproduces the failure on the *other* seed (it
     * still drops every frame on `a->b`), and the shrinker reports a clean one-fault reduction
     * of a run it never shrank. Nothing else in this suite notices, because every other test
     * uses a strategy that derives its plans from the original.
     */
    @Test
    fun aSeedVaryingReductionStrategyIsRejected_BS3() {
        val artifact = artifactOf(noisyFailingPlan())
        val varyTheSeed = ReductionStrategy { plan, _ ->
            listOf(Reduction("drop noise, and re-roll the seed", plan.without("noise-ab-late").copy(seed = SEED + 1)))
        }

        val rejected = assertFailsWith<IllegalArgumentException> {
            PlanShrinker.shrink(artifact, strategy = varyTheSeed)
        }

        val message = assertNotNull(rejected.message)
        assertTrue("CHA1-35" in message, message)
        assertTrue("seed=${SEED + 1}" in message, message)
        assertTrue("artifact seed=$SEED" in message, message)
        assertTrue("Rejected before it was run" in message, message)

        // The candidate the strategy proposed WOULD have reproduced the failure on its own seed,
        // so the guard is what stops it — not a lucky non-reproduction.
        val onTheOtherSeed = DstRun(GRAPH, FaultPlan.of(SEED + 1, dropFrom(ESSENTIAL, "a->b", 2)), BUDGET, CHECK)
            .execute()
        assertEquals(DstOutcome.FAILED, onTheOtherSeed.outcome, "the rejected candidate was not merely harmless")

        // And the artifact's own guard is the second line of defence ([CHA1-35]).
        assertFailsWith<IllegalArgumentException> {
            artifact.withShrunkPlan(FaultPlan.of(SEED + 1, dropFrom(ESSENTIAL, "a->b", 2)))
        }
    }

    // ------------------------------------------------------------------ CHA1-37

    /** [CHA1-37]: the original plan is recorded alongside the shrunk one and never overwritten. */
    @Test
    fun theArtifactRecordsBothPlansAndNeverOverwritesTheOriginal_CHA1_37() {
        val artifact = artifactOf(noisyFailingPlan())
        val originalRecord = artifact.plan

        val result = PlanShrinker.shrink(artifact)
        val file = DstArtifacts.write(result.artifact, root)
        val reRead = DstArtifacts.read(file)

        assertEquals(originalRecord, reRead.plan, "[CHA1-37] the original plan survives the shrink verbatim")
        assertEquals(
            listOf(ESSENTIAL, "noise-ab-late", "noise-ba-late"),
            reRead.plan.faults.map { it.id },
            "all three original faults are still on disk",
        )
        assertEquals(listOf(ESSENTIAL), reRead.shrunkPlan!!.faults.map { it.id })
        assertEquals(result.record, reRead.shrink, "the shrink bookkeeping round-trips")

        // Shrinking again from the re-read artifact starts from the ORIGINAL plan, not the
        // shrunk one — the artifact's record of what was tried is what a replay reproduces.
        assertEquals(originalRecord.faults.size, reRead.plan().faults.size)
    }

    // ------------------------------------------------------------------ risk 7

    /**
     * Epic §9 risk 7: the search is bounded by attempts, and a bounded stop is **recorded as
     * an early stop** rather than presented as a minimum.
     */
    @Test
    fun theSearchIsBoundedByAttemptsAndRecordsTheEarlyStop_risk7() {
        val artifact = artifactOf(noisyFailingPlan())

        val result = PlanShrinker.shrink(artifact, maxAttempts = 1)

        assertEquals(1, result.record.attempts, "the cap is on candidate runs")
        assertTrue(result.record.stoppedEarly, "a capped search stopped early: ${result.record}")
        val reason = assertNotNull(result.record.stopReason)
        assertTrue("attempt cap reached (1 of 1)" in reason, reason)
        assertTrue("not a proven minimum" in reason, reason)
        assertTrue("STOPPED EARLY" in result.summary(), result.summary())
        assertTrue("this is not a minimum" in result.summary(), result.summary())

        // The claim reaches the artifact, not just the return value.
        val onDisk = DstArtifacts.read(DstArtifacts.write(result.artifact, root))
        assertTrue(onDisk.shrink!!.stoppedEarly)
        assertEquals(1, onDisk.shrink!!.attempts)
    }

    /** The optional wall-clock bound, checked before each candidate run, recorded the same way. */
    @Test
    fun theSearchIsAlsoBoundedByWallClock_risk7() {
        val artifact = artifactOf(noisyFailingPlan())

        val result = PlanShrinker.shrink(artifact, maxAttempts = 1_000, wallClockMillis = 0)

        assertEquals(0, result.record.attempts, "the budget was spent before the first candidate ran")
        assertTrue(result.record.stoppedEarly)
        val reason = assertNotNull(result.record.stopReason)
        assertTrue("wall-clock budget of 0ms" in reason, reason)
        assertEquals(faultIds(artifact.plan()), faultIds(result.plan), "an unshrunk plan, honestly labelled")
    }

    // ------------------------------------------------------------------ parameter reductions

    /**
     * The semantics-aware half of the strategy seam: a caller who knows which direction of a
     * parameter is *less* adversarial gets a binary search over it, with every step re-verified.
     *
     * Here the parameter is the number of frames destroyed and the harmless direction is
     * `0`. The reduction to zero is proposed first and **discarded** — nothing dropped means
     * nothing fails — and the search then converges on the smallest count that still fails,
     * which for this check is one frame.
     */
    @Test
    fun aNumericParameterIsReducedByBinarySearchAndEachStepIsReVerified() {
        val artifact = artifactOf(FaultPlan.of(SEED, dropFrom(ESSENTIAL, "a->b", fromStep = 2, count = 6)))
        assertEquals(6, paramOf(artifact.plan(), ESSENTIAL, "count"))

        val result = PlanShrinker.shrink(
            artifact,
            maxAttempts = 12,
            strategy = ReductionStrategies.numericParamToward(DROP_KIND, "count", target = 0.0),
        )

        assertEquals(1, paramOf(result.plan, ESSENTIAL, "count"), result.trail.joinToString("\n"))
        assertEquals(listOf(ESSENTIAL), faultIds(result.plan), "a parameter reduction drops no fault")
        assertTrue(!result.record.stoppedEarly, "the search converged within the cap: ${result.record}")
        assertTrue(
            result.trail.any { it.startsWith("discarded:") && "to 0" in it },
            "reducing to zero was tried and rejected: ${result.trail}",
        )
        assertTrue(result.record.reductionsAccepted >= 1, "${result.record}")

        // Integral parameters stay integral on disk: `1`, not `1.0`.
        val file = DstArtifacts.write(result.artifact, root)
        val shrunkParams = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("shrunkPlan").jsonObject
            .getValue("faults").jsonArray
            .single().jsonObject.getValue("params").jsonObject
        assertEquals("1", shrunkParams.getValue("count").jsonPrimitive.content)

        // Still the same failure, on the same seed.
        val reRun = DstRun(GRAPH, result.plan, BUDGET, CHECK).execute()
        assertEquals(DstOutcome.FAILED, reRun.outcome)
        assertEquals(SEED, result.plan.seed)
    }

    // ------------------------------------------------------------------ refusals

    /** A run that did not fail has no failure to hold constant, so shrinking it is refused. */
    @Test
    fun onlyAFailingRunCanBeShrunk() {
        val run = DstRun(GRAPH, FaultPlan.empty(SEED), BUDGET, CHECK)
        val report = run.execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "the fault-free control passes")
        val passing = DstArtifact.of(run, report, suite = SUITE)

        val e = assertFailsWith<IllegalArgumentException> { PlanShrinker.shrink(passing) }
        assertTrue("only a FAILED run can be shrunk" in e.message!!, e.message!!)
    }

    /**
     * A run the rig makes no reproducibility claim for cannot be shrunk either: re-verification
     * is a re-run, and a re-run of a multi-JVM interleaving is not the same experiment
     * ([CHA1-40]).
     */
    @Test
    fun aNonDeterministicRunCannotBeShrunk_CHA1_40() {
        val plan = noisyFailingPlan()
        val run = DstRun(GRAPH, plan, BUDGET, CHECK)
        val report = run.execute()
        val multiJvm = DstArtifact.of(
            run,
            report,
            suite = SUITE,
            checkId = CHECK_ID,
            driver = DstDriver.MULTI_JVM,
        )

        val e = assertFailsWith<IllegalArgumentException> { PlanShrinker.shrink(multiJvm) }
        assertTrue("CHA1-40" in e.message!!, e.message!!)
    }

    /**
     * The control run: an artifact whose own plan no longer reproduces the recorded failure is
     * reported as such, and nothing is shrunk.
     *
     * Without this check the shrinker would grade reductions against a failure that never
     * happens, accept all of them, and present an empty plan as the minimal reproducer. The
     * artifact here is forged by recording a failing observation against a plan that passes —
     * which is what a stale artifact from an older commit looks like.
     */
    @Test
    fun anArtifactThatNoLongerReproducesIsReportedNotShrunk() {
        val artifact = artifactOf(noisyFailingPlan())
        val stale = artifact.copy(
            plan = PlanRecord(
                listOf(FaultCodecs.encode(dropFrom("noise-ab-late", "a->b", fromStep = 9_999))),
            ),
        )

        val result = PlanShrinker.shrink(stale)

        assertEquals(0, result.record.attempts, "no candidate is graded against a failure that did not happen")
        assertEquals(0, result.record.reductionsAccepted)
        assertTrue(result.record.stoppedEarly)
        val reason = assertNotNull(result.record.stopReason)
        assertTrue("did not reproduce the recorded failure" in reason, reason)
        assertEquals(listOf("noise-ab-late"), faultIds(result.plan), "the plan is returned untouched")
    }

    private fun seedTextOf(file: File): String =
        file.readLines().single { it.trimStart().startsWith("\"seed\"") }.trim()

    private companion object {
        const val DROP_KIND = "dst-selftest-shrink-drop-n"
        const val SUITE = "dst-selftest-shrink"
        const val CHECK_ID = "dst-selftest-shrink-all-chains-complete"
        const val ESSENTIAL = "drop-ab"
        const val SEED = 41L
        const val BUDGET = 5_000

        /** Distinct from [ReplayTest]'s and [SweepTest]'s: a `GraphSpec` id is a global name. */
        val GRAPH: GraphSpec = SelfTestGraphs.crossTalk(chains = 3, rounds = 4)

        /** `chains * (rounds + 1)` hops in each direction, with no frame lost. */
        const val EXPECTED_DELIVERIES = 3 * 5 * 2

        /**
         * The property, with a **message that does not embed the observed count**.
         *
         * That is deliberate and it is the fixture's most load-bearing detail. [CHA1-36]'s
         * predicate compares the failing-check message, so a check whose message carries a
         * run-varying number reports a *different* failure for every reduction and the shrinker
         * accepts nothing. Observed while writing this suite: with the count in the message, a
         * plan reduced from 6 dropped frames to 3 failed as "only 18 of 30 arrived" against a
         * recorded "only 12 of 30", and was discarded as a different failure. The count belongs
         * in the report and the trace, not in the identity of the property.
         */
        val CHECK = DstCheck { world ->
            val delivered = world.traceEvents().count { it.port == "recv" }
            if (delivered < EXPECTED_DELIVERIES) {
                throw AssertionError("not all $EXPECTED_DELIVERIES chain deliveries arrived")
            }
        }
    }
}
