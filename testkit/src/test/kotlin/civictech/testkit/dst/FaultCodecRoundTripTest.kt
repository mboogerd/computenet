package civictech.testkit.dst

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CHA1-31] and [CHA1-35]: every landed fault class carries a [FaultCodec], so a plan built
 * from real faults round-trips into a replay artifact — and so
 * [ReductionStrategies.numericParamToward] has a production fault it can actually reduce.
 *
 * ## Why this suite exists, and what it caught
 *
 * The registry, the interface and the refusal path all landed with `DstArtifact`, and
 * `DstArtifact.kt`'s own KDoc stated the obligation — the fault classes "arrive in sibling
 * files, and each brings a [FaultCodec]". None of the four did. That had two consequences, and
 * the second is the reason this is a test suite rather than a note:
 *
 *  1. `FaultCodecs.encode` refuses a fault no codec claims, so `DstArtifact.of` and
 *     `artifact.plan()` could not handle *any* plan built from a landed fault class. The
 *     replay artifact was unreachable for every fault the rig actually had.
 *  2. Quietly, [ReductionStrategies.numericParamToward] was **inert**. It reads the parameter
 *     off the fault's encoded record and wraps that encode in `runCatching` with an early
 *     return, so with no codec registered the early return was taken for every fault, every
 *     time — the strategy proposed nothing and reported no error.
 *     [aNumericParameterReductionIsAcceptedForAProductionFault_CHA1_35] is the executable pin
 *     on that: it fails, with zero reductions accepted, against a tree whose fault classes
 *     register no codec.
 *
 * ## What is *not* asserted here
 *
 * That a fresh JVM can decode an artifact it has never loaded the fault classes of. It cannot,
 * by design: registration happens in each fault class's companion object, which runs when the
 * class loads. That is the same load requirement [GraphRegistry] and [CheckRegistry] already
 * carry — a replay harness has to load the code that registers the artifact's graph and check
 * anyway — and [FaultCodecs.decode] names the registered kinds when it is not met. Asserting
 * otherwise would need an eager registration point in `DstArtifact.kt`, which this suite's
 * task does not own.
 */
class FaultCodecRoundTripTest {

    private companion object {

        /**
         * The four fault classes landed in `civictech.testkit.dst` and their published kinds.
         *
         * Read off each class's `CODEC`, deliberately, and **not** off its `KIND`. `KIND` is a
         * `const val`: the compiler inlines it into this file's constant pool, so naming it
         * resolves to a string literal and loads nothing. `CODEC` is an ordinary companion
         * property, so reading it is a real static field access that forces the owning class's
         * initialiser — which is the registration this suite is about.
         *
         * Written with `KIND` here, [everyLandedFaultClassRegistersACodec_CHA1_31] passes only
         * because some *other* test method in this class constructed a fault first, and fails
         * when it is run on its own (measured: registry empty, all four kinds reported missing).
         */
        val LANDED_KINDS: List<String> = listOf(
            CrashFault.CODEC.kind,
            PartitionFault.CODEC.kind,
            JournalFault.CODEC.kind,
            RestartAtFrontierFault.CODEC.kind,
        )

        // -------------------------------------------------------------- the shrink fixture

        val SHRINK_GRAPH: GraphSpec = SelfTestGraphs.crossTalk()

        const val SHRINK_CHECK_ID = "dst-selftest-codec-every-frame-delivered"

        const val SHRINK_BUDGET = 300

        /** The edge the drop partition targets, and the direction whose frames it destroys. */
        const val EDGE = "a->b"

        /** The window's opening step, held constant while `until` is walked down toward it. */
        const val FROM = 2

        /** The window's initial healing step: far past the run, so the drop never heals. */
        const val UNTIL = 1_000

        /**
         * How many `recv` events a fault-free drive of [SHRINK_GRAPH] produces: 8 chains
         * (4 opened on each edge) × 7 rounds each. Asserted against a real fault-free run in
         * [theShrinkFixtureIsNotVacuous], so the constant cannot rot into a vacuous check.
         */
        const val FULL_DELIVERY = 56

        /**
         * The property the drop partition breaks: every frame in the workload arrives.
         *
         * The message carries **no run-varying number** on purpose. `FailurePredicate`'s
         * default compares the failing-check message across two different plans, so a message
         * reading "only 12 of 56 arrived" would make every reduction a *different* failure and
         * the shrinker would accept nothing — which would make this suite pass for the wrong
         * reason.
         */
        val SHRINK_CHECK: DstCheck = DstCheck { world ->
            val delivered = world.traceEvents().count { it.port == "recv" }
            require(delivered >= FULL_DELIVERY) { "not every frame in the workload was delivered" }
        }

        fun deliveries(report: DstReport): Int = report.trace.count { it.port == "recv" }
    }

    @BeforeTest
    fun setUp() {
        GraphRegistry.register(SHRINK_GRAPH)
        CheckRegistry.register(SHRINK_CHECK_ID, SHRINK_CHECK)
    }

    @AfterTest
    fun tearDown() {
        GraphRegistry.unregister(SHRINK_GRAPH.id)
        CheckRegistry.unregister(SHRINK_CHECK_ID)
    }

    // ------------------------------------------------------------------ registration

    /**
     * Every landed fault class has registered a codec by the time its class is loaded.
     *
     * Reading the four [LANDED_KINDS] entries is what loads the four classes — see that
     * property for why it must go through `CODEC` and not through the inlined `KIND` constant.
     * The load is the mechanism under test as much as the assertion is: a codec registered
     * anywhere a consumer has to *ask* for would be a codec a consumer forgets.
     *
     * This test must therefore be able to pass **alone**, with no sibling method having
     * constructed a fault first. That is the property it is really pinning.
     */
    @Test
    fun everyLandedFaultClassRegistersACodec_CHA1_31() {
        val registered = FaultCodecs.kinds()
        val missing = LANDED_KINDS.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "these landed fault classes registered no FaultCodec: $missing (registered: ${registered.sorted()})",
        )
    }

    /** Each codec claims its own class and no other's — [FaultCodecs.encode]'s single-claimant rule. */
    @Test
    fun eachCodecClaimsExactlyItsOwnFaultClass_CHA1_31() {
        assertEquals(CrashFault.KIND, FaultCodecs.encode(crash()).kind)
        assertEquals(PartitionFault.KIND, FaultCodecs.encode(partition()).kind)
        assertEquals(JournalFault.KIND, FaultCodecs.encode(journal()).kind)
        assertEquals(RestartAtFrontierFault.KIND, FaultCodecs.encode(restart()).kind)
    }

    // ------------------------------------------------------------------ round trips

    @Test
    fun crashFaultRoundTrips_CHA1_31() {
        assertRoundTrips(crash())
        assertRoundTrips(CrashFault.midDrain("crash-drain", host = "h", atStep = 7, journal = "j"))
        // The [CHA1-63] no-recovery control: `journal = null` must survive as null, not vanish.
        assertRoundTrips(CrashFault.atQuiescence("crash-control", host = "h", atStep = 7, journal = null))
        assertRoundTrips(CrashFault("crash-wave", host = "h", atStep = 0, mode = CrashMode.MID_WAVE))
    }

    @Test
    fun partitionFaultRoundTrips_CHA1_31() {
        assertRoundTrips(partition())
        assertRoundTrips(PartitionFault.park("part-park", edge = "e", from = 3, until = 9))
        // The default open-ended window: Int.MAX_VALUE must survive as a number, not saturate.
        assertRoundTrips(PartitionFault.drop("part-open", edge = "e", from = 0))
    }

    /** Every [JournalMutation] the rig has, including the one carrying a `ByteArray`. */
    @Test
    fun journalFaultRoundTripsForEveryMutation_CHA1_31() {
        val mutations = listOf(
            JournalMutation.TruncateTail(2),
            JournalMutation.TruncatePrefix(1),
            JournalMutation.CorruptAt(3),
            JournalMutation.CorruptAt(4, byteArrayOf(0x7f, 0x00, 0x2a)),
            JournalMutation.DuplicateAt(5),
            JournalMutation.ReorderAt(0, 6),
            JournalMutation.FailAppendAfter(4),
        )
        mutations.forEachIndexed { i, mutation ->
            assertRoundTrips(JournalFault("journal-$i", journal = "j", mutation = mutation))
        }
        assertRoundTrips(JournalFault("journal-windowed", "j", JournalMutation.TruncateTail(1), StepWindow(4, 11)))
    }

    @Test
    fun restartAtFrontierFaultRoundTrips_CHA1_31() {
        assertRoundTrips(restart())
        assertRoundTrips(RestartAtFrontierFault.atPrefix("restart-0", "h", "j", atStep = 5, k = 0))
        assertRoundTrips(RestartAtFrontierFault.withFrontierRolledBack("restart-fr", "h", "j", 5, 2))
        // Both knobs absent — the ordinary whole-log restart — must round-trip as absent.
        assertRoundTrips(RestartAtFrontierFault("restart-plain", "h", "j", atStep = 5))
    }

    /**
     * [CHA1-31] end to end: a report whose plan holds **one of every landed fault class**
     * becomes an artifact, survives the JSON on disk, and rebuilds into the identical plan.
     *
     * The graph is [SelfTestGraphs.inert], which declares one target of every kind and then
     * does nothing, and every fault activates far past the run — so this test is about the
     * artifact, not about what the faults do. Each fault is nonetheless *installed* and target-
     * validated by `DstRun.execute`, so a codec whose decoded fault named a different seam
     * would fail here rather than in a consumer suite.
     */
    @Test
    fun anArtifactRoundTripsAPlanHoldingEveryLandedFaultClass_CHA1_31() {
        val graph = SelfTestGraphs.inert()
        val plan = FaultPlan.of(
            0xC0DECL,
            CrashFault.atQuiescence("crash", host = "h", atStep = 9_000, journal = "j"),
            PartitionFault.drop("partition", edge = "e", from = 9_000),
            JournalFault("journal", journal = "j", mutation = JournalMutation.TruncateTail(1), window = StepWindow(9_000)),
            RestartAtFrontierFault("restart", host = "h", journal = "j", atStep = 9_000, prefix = 2),
        )
        val run = DstRun(graph, plan, budget = 8)
        val report = run.execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "the inert fixture must quiesce, or the artifact is a red run")

        val artifact = DstArtifact.of(run, report, suite = "codec-roundtrip")
        assertEquals(
            LANDED_KINDS.sorted(),
            artifact.plan.faults.map { it.kind }.sorted(),
            "the artifact must record one record per landed fault class, each under its own kind",
        )

        val reread = DstArtifacts.parse(artifact.toJson())
        assertEquals(plan, reread.plan(), "a plan of production faults must survive the artifact on disk")
    }

    // ------------------------------------------------------------------ CHA1-35

    /**
     * The fixture is a real failure, and a fault-free drive of the same graph is not — without
     * both, every shrink assertion below would be vacuous. Also pins [FULL_DELIVERY].
     */
    @Test
    fun theShrinkFixtureIsNotVacuous() {
        val clean = DstRun(SHRINK_GRAPH, FaultPlan.empty(SEED), SHRINK_BUDGET, SHRINK_CHECK).execute()
        assertEquals(DstOutcome.PASSED, clean.outcome, "the fault-free control must pass: ${clean.failingCheck}")
        assertEquals(FULL_DELIVERY, deliveries(clean), "FULL_DELIVERY no longer matches the fault-free drive")

        val failing = failingRun()
        assertEquals(DstOutcome.FAILED, failing.second.outcome)
        assertTrue(
            deliveries(failing.second) < FULL_DELIVERY,
            "the drop partition must actually destroy frames, or the failure is not about the fault",
        )
    }

    /**
     * [CHA1-35]/[CHA1-36]: [ReductionStrategies.numericParamToward] reaches a **production**
     * fault and its reduction is accepted — the partition window shortened, `until` walked down
     * toward `from`, exactly the epic's own example.
     *
     * ## Why this is the test that fails without codecs
     *
     * The strategy is the *only* one supplied: [ReductionStrategies.dropFaults] is deliberately
     * absent, and the plan holds one fault, so there is nothing else an acceptance could come
     * from. `numericParamToward` reads its parameter off `FaultCodecs.encode(fault)` behind a
     * `runCatching { }.getOrNull() ?: return@forEachIndexed`, so before [PartitionFault.CODEC]
     * existed this produced an empty candidate list and `reductionsAccepted == 0` — silently,
     * with no error anywhere.
     *
     * The assertion is on the direction and on the *encoded* parameter, not on a specific final
     * value: the ladder's stopping point depends on which step the last surviving frame crosses
     * the edge at, and pinning it would be pinning the fixture's scheduling rather than the
     * strategy's behaviour.
     */
    @Test
    fun aNumericParameterReductionIsAcceptedForAProductionFault_CHA1_35() {
        val (run, report) = failingRun()
        val artifact = DstArtifact.of(run, report, checkId = SHRINK_CHECK_ID)

        val result = PlanShrinker.shrink(
            artifact,
            maxAttempts = 24,
            strategy = ReductionStrategies.numericParamToward(PartitionFault.KIND, "until", target = FROM.toDouble()),
        )

        assertTrue(
            result.record.reductionsAccepted >= 1,
            "no numeric-parameter reduction was accepted, so the strategy is inert: ${result.summary()}\n" +
                result.trail.joinToString("\n"),
        )

        val shrunk = assertNotNull(result.artifact.shrunkPlan(), "an accepted reduction must be recorded")
        val reduced = shrunk.faults.single() as PartitionFault
        assertTrue(
            reduced.window.until < UNTIL,
            "the accepted reduction must have shortened the window, got ${reduced.window}",
        )
        assertEquals(FROM, reduced.window.from, "the reduction must move `until` only, never the opening step")
        assertEquals(
            reduced.window.until,
            FaultCodecs.encode(reduced).params.getValue("until").jsonPrimitive.int,
            "the shrunk fault must re-encode to the parameter the strategy set",
        )

        // [CHA1-37]: the original plan is never rewritten.
        assertEquals(
            UNTIL,
            artifact.plan.faults.single().params.getValue("until").jsonPrimitive.int,
            "shrinking must not touch the recorded plan",
        )
        assertTrue(
            result.trail.any { it.contains(".until from") },
            "the trail must name the numeric move it made: ${result.trail}",
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRoundTrips(fault: Fault) {
        val record = FaultCodecs.encode(fault)
        assertEquals(fault.id, record.id, "a codec must not rename the fault it encodes")
        // Through the artifact's own JSON, not just the in-memory JsonObject: a parameter that
        // only survives in memory is not a parameter an artifact can carry.
        val plan = FaultPlan(1L, listOf(fault))
        val artifact = artifactShellFor(plan)
        val decoded = DstArtifacts.parse(artifact.toJson()).plan().faults.single()
        assertEquals(fault, decoded, "${fault::class.simpleName} did not survive an encode/decode round trip")
        assertEquals(record, FaultCodecs.encode(decoded), "re-encoding a decoded fault must be a fixed point")
    }

    /**
     * A minimal artifact carrying [plan], built directly rather than from a run: these
     * round-trip cases name seams no single graph declares, and executing them is
     * [anArtifactRoundTripsAPlanHoldingEveryLandedFaultClass_CHA1_31]'s job, not this one's.
     */
    private fun artifactShellFor(plan: FaultPlan): DstArtifact = DstArtifact(
        rig = DstRig.stamp(),
        suite = "codec-roundtrip",
        seed = plan.seed,
        graphId = "unregistered-on-purpose",
        budget = 1,
        plan = PlanRecord(plan.faults.map(FaultCodecs::encode)),
        observed = ObservedRun(DstOutcome.PASSED, 0, null, null, "0".repeat(64), 0),
    )

    private fun failingRun(): Pair<DstRun, DstReport> {
        val plan = FaultPlan.of(SEED, PartitionFault.drop("drop-$EDGE", EDGE, from = FROM, until = UNTIL))
        val run = DstRun(SHRINK_GRAPH, plan, SHRINK_BUDGET, SHRINK_CHECK)
        return run to run.execute()
    }

    private fun crash() = CrashFault("crash", host = "h", atStep = 4, mode = CrashMode.AT_QUIESCENCE, journal = "j")

    private fun partition() = PartitionFault.drop("partition", edge = "e", from = 1, until = 5)

    private fun journal() = JournalFault("journal", journal = "j", mutation = JournalMutation.CorruptAt(2))

    private fun restart() = RestartAtFrontierFault("restart", host = "h", journal = "j", atStep = 6, prefix = 3)
}

private const val SEED = 0x5EEDL
