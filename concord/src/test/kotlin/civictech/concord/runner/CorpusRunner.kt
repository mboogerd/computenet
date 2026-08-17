package civictech.concord.runner

import civictech.concord.check.CheckContext
import civictech.concord.check.CheckResult
import civictech.concord.check.Checks
import civictech.concord.check.ReadWalk
import civictech.concord.driver.Driver
import civictech.concord.driver.ReadCursor
import civictech.concord.driver.ReadPage
import civictech.concord.driver.kernel.KernelDriver
import civictech.concord.generator.ScenarioGenerator
import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.Check
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DespawnStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.EffectCount
import civictech.concord.schema.Expect
import civictech.concord.schema.ExpectFailure
import civictech.concord.schema.FinalView
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.Kind
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.ObservationsAllSatisfy
import civictech.concord.schema.ObservationsMonotone
import civictech.concord.schema.ObservationsWholeWaves
import civictech.concord.schema.PagesEqualView
import civictech.concord.schema.Profile
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.ReadStateStep
import civictech.concord.schema.ReplicasConverge
import civictech.concord.schema.RestartStep
import civictech.concord.schema.RestoreStep
import civictech.concord.schema.RetransmitStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.SnapshotStep
import civictech.concord.schema.ViewsConverge
import civictech.concord.schema.WavePlaneUnchanged
import civictech.concord.schema.WindowKind
import civictech.concord.schema.WindowSpec
import civictech.concord.value.Value
import civictech.concord.yaml.ConcordYaml
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The Concord runner: a JUnit dynamic-test harness that discovers every scenario
 * YAML under `concord/corpus`, parses it through the reused [ConcordYaml] factory,
 * filters by the active profile set (`-Pconcord.profiles`), and runs each surviving
 * scenario across the schedule sweep against the [KernelDriver].
 *
 * **W2 — full check dispatch.** Every declared `checks:` entry is routed through
 * W1-B's [Checks] evaluator vocabulary (`civictech.concord.check`) against the
 * driver's observed outputs (views, observation logs, dead letters, effect logs).
 * A scenario passes a run only if *all* its declared checks pass; the scenario
 * passes only if all runs of the sweep pass. `kind: control` scenarios (P7) are
 * asserted to *fail* — and to fail *as declared*: its `expect-failure:` block
 * names the check that must fail and a substring of the reason it must fail for
 * (see [assertControlFailedAsDeclared]). "Something failed" is not proof the
 * right thing failed.
 *
 * **Profile filter (P9).** `-Pconcord.profiles=core,dist,dur` (default `core`)
 * selects which scenarios execute by their `profile:` field; the build passes
 * `concord.profiles` through as a system property. Filtering the corpus to the
 * empty set (e.g. `-Pconcord.profiles=dist` with only `core` pilots) yields zero
 * dynamic tests, not an error.
 */
class CorpusRunner {

    private companion object {
        const val QUIESCE_BUDGET = 5_000_000
        const val DEFAULT_RUNS = 20
        /** Default generative instance count when a `generator:` block omits `instances:`. */
        const val DEFAULT_GEN_INSTANCES = 40
        /** Wedge guard: pages one `read-state` walk may return before it is declared non-terminating. */
        const val MAX_READ_PAGES = 100_000
        /** Where the runner's working directory sees the corpus (Gradle sets cwd = module dir). */
        val CORPUS = File("corpus")

        /** `CTL-GF-01`'s declared failure, reused by the control-assertion tests below. */
        val DECLARED_GF_FAILURE = ExpectFailure(
            check = "observations-all-satisfy",
            messageContains = "fails the predicate",
        )
    }

    /**
     * A [CheckContext] pairing one run's already-quiesced [driver] with its
     * [scenario] and the bounded-read walks that run's script performed
     * (V1C-CONCORD — a read is an event with a before and an after, and by
     * check time the "before" only survives if the runner recorded it).
     */
    private class RunContext(
        override val driver: Driver,
        override val scenario: Scenario,
        override val reads: List<ReadWalk>,
    ) : CheckContext

    @TestFactory
    fun `every corpus scenario runs against the kernel driver`(): List<DynamicTest> {
        val files = CORPUS.walkTopDown().filter { it.isFile && it.extension == "yaml" }.sorted().toList()
        assertTrue(files.isNotEmpty()) { "no corpus scenarios discovered under ${CORPUS.absolutePath}" }
        val active = activeProfiles()
        return files.mapNotNull { file ->
            val scenario = ConcordYaml.instance.decodeFromString(Scenario.serializer(), file.readText())
            // The `expect-failure:`/`kind:` pairing is a property of the corpus, not of
            // one run, so it is asserted for EVERY discovered file — before the profile
            // filter. Asserting it inside the run would make the schema contract hold
            // only for the selected profiles, so a `dist`/`dur` control missing its
            // declaration (or an example carrying a meaningless one) would be invisible
            // to the documented fast loop `-Pconcord.profiles=core`.
            assertExpectFailureMatchesKind(scenario)
            if (scenario.profile.slug() !in active) return@mapNotNull null
            DynamicTest.dynamicTest("${scenario.id} (${file.parentFile.name})") { runScenario(scenario) }
        }
    }

    /**
     * `expect-failure:` is the control contract and nothing else's: **required** on
     * every `kind: control` (or "something failed" stands in as proof the right
     * thing failed — see [assertControlFailedAsDeclared]) and **rejected** on every
     * other kind, where the runner asserts every check PASSES and the block would
     * therefore declare a failure nothing ever asserts — coverage that does not
     * exist.
     */
    internal fun assertExpectFailureMatchesKind(scenario: Scenario) {
        if (scenario.kind == Kind.CONTROL) {
            assertTrue(scenario.expectFailure != null) {
                "${scenario.id}: `kind: control` carries no `expect-failure:` block. A control must declare " +
                    "WHICH check must fail and WHY (see concord/schema/scenario.md)"
            }
        } else {
            assertTrue(scenario.expectFailure == null) {
                "${scenario.id}: only `kind: control` may declare `expect-failure:` (kind is ${scenario.kind}); " +
                    "on any other kind the runner asserts every check PASSES, so the block would assert nothing"
            }
        }
    }

    /** The profile set the build activated via `-Pconcord.profiles` (default `core`). */
    private fun activeProfiles(): Set<String> =
        (System.getProperty("concord.profiles") ?: "core")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun Profile.slug(): String = when (this) {
        Profile.CORE -> "core"
        Profile.DIST -> "dist"
        Profile.DUR -> "dur"
    }

    /**
     * One declared check's failure on one run: the check's declared `type:` id
     * (its `@SerialName` in [Check]) and the evaluator's message. A control
     * asserts on **both** — see [assertControlFailedAsDeclared].
     */
    internal data class CheckFailure(val checkId: String, val message: String)

    private fun runScenario(scenario: Scenario) {
        // The `expect-failure:`/`kind:` pairing is asserted by the test factory, for
        // every corpus file rather than only the profile-selected ones.

        // Generative scenarios (§0, §1.2 exemplar (f)) stand in a `generator:` block
        // for a fixed graph: the harness synthesizes one concrete graph per instance
        // (seeded by instance index) and drives it exactly like a hand-authored
        // example. The generation lives in `civictech.concord.generator`; the driver,
        // checks, and batch oracle are the same shared machinery used below.
        if (scenario.kind == Kind.GENERATIVE) {
            runGenerative(scenario)
            return
        }
        val runs = scenario.runs ?: DEFAULT_RUNS

        // Per run: build a fresh seeded driver, replay the graph + script, quiesce,
        // then route every declared check through the W1-B evaluator vocabulary.
        // A run passes iff every declared check passes; failures are collected per
        // run for the report. incremental-equals-batch stays wired to the batch
        // oracle inside Checks.incrementalEqualsBatch.
        val failuresByRun = LinkedHashMap<Int, List<CheckFailure>>()
        for (run in 0 until runs) {
            val driver = KernelDriver(run.toLong())
            buildGraph(driver, scenario)
            val reads = runScript(driver, scenario)
            driver.quiesce(QUIESCE_BUDGET)
            val failures = evaluateChecks(RunContext(driver, scenario, reads), scenario.checks)
            if (failures.isNotEmpty()) failuresByRun[run] = failures
        }

        when (scenario.kind) {
            Kind.CONTROL -> assertControlFailedAsDeclared(scenario, failuresByRun, runs)
            else ->
                assertTrue(failuresByRun.isEmpty()) {
                    val (run, msgs) = failuresByRun.entries.first().let { it.key to it.value }
                    "${scenario.id}: check(s) failed on ${failuresByRun.size} of $runs run(s). " +
                        "First failing run ($run): ${msgs.joinToString("; ") { it.message }}"
                }
        }
    }

    /** Route every declared check through the W1-B evaluator vocabulary for one run. */
    private fun evaluateChecks(ctx: CheckContext, checks: List<Check>): List<CheckFailure> =
        checks.mapNotNull { check ->
            when (val r = Checks.evaluate(check, ctx)) {
                CheckResult.Passed -> null
                is CheckResult.Failed -> CheckFailure(check.checkId(), r.message)
                is CheckResult.NotImplemented -> CheckFailure(check.checkId(), "check '${r.check}' is not implemented")
            }
        }

    /**
     * Assert a `kind: control` failed, **and failed as it declared** (P7 — the
     * harness must be able to fail).
     *
     * The weaker assertion this replaced was effectively `!passedAllRuns`: it
     * never looked at which check failed or why. A control is a negative
     * scenario asserting the harness detects one specific violation, so a
     * control that starts failing for an unrelated reason — a vacuity guard on
     * an empty observation log, a missing view, an oracle that cannot model the
     * graph — keeps failing, keeps satisfying `!passedAllRuns`, and has silently
     * stopped covering anything. From outside that is indistinguishable from the
     * control still working, which is exactly how `CTL-GF-01`'s vacuous pass
     * survived (computenet-qaz / computenet-dqy.18).
     *
     * So four things are asserted, and each is RED on its own:
     * 1. the control declares at least one real, routed check;
     * 2. it carries an `expect-failure:` block (a control must say what it
     *    detects; the block is required, never inferred);
     * 3. that block names a check the scenario actually declares — a stale id
     *    left behind by an edit is a control asserting nothing;
     * 4. at least one run failed, and **every** failure recorded on **every**
     *    run is the declared check failing for the declared reason. A single
     *    wrong-reason failure on any run of the sweep is the coverage-loss
     *    signal, so it fails here rather than being averaged away by a sibling
     *    run that happened to fail correctly.
     */
    internal fun assertControlFailedAsDeclared(
        scenario: Scenario,
        failuresByRun: Map<Int, List<CheckFailure>>,
        runs: Int,
    ) {
        assertTrue(scenario.checks.isNotEmpty()) {
            "${scenario.id}: `kind: control` declares no check at all, so there is nothing it could be shown to detect"
        }
        val expected = scenario.expectFailure
        assertTrue(expected != null) {
            "${scenario.id}: `kind: control` carries no `expect-failure:` block. A control must declare WHICH " +
                "check must fail and WHY, or 'something failed' stands in as proof the right thing failed " +
                "(see concord/schema/scenario.md)"
        }
        checkNotNull(expected)

        val declared = scenario.checks.map { it.checkId() }
        assertTrue(expected.check in declared) {
            "${scenario.id}: `expect-failure:` names check '${expected.check}', which this scenario does not " +
                "declare (declared: ${declared.joinToString(", ")}) — the expectation can never be met"
        }

        assertTrue(failuresByRun.isNotEmpty()) {
            "${scenario.id}: control scenario was expected to FAIL '${expected.check}' " +
                "(message containing \"${expected.messageContains}\") but every check passed on every one of " +
                "$runs run(s)"
        }

        val wrongReason = failuresByRun.entries
            .flatMap { (run, failures) -> failures.map { run to it } }
            .filterNot { (_, f) -> f.checkId == expected.check && expected.messageContains in f.message }
        assertTrue(wrongReason.isEmpty()) {
            val (run, failure) = wrongReason.first()
            "${scenario.id}: control failed for a reason it does not declare. Expected check " +
                "'${expected.check}' to fail with a message containing \"${expected.messageContains}\", but run " +
                "$run recorded '${failure.checkId}': ${failure.message}. " +
                "${wrongReason.size} of ${failuresByRun.values.sumOf { it.size }} recorded failure(s) across " +
                "${failuresByRun.size} of $runs run(s) do not match. A control that fails for the wrong reason " +
                "no longer tests what it was written to detect, even though it still fails"
        }
    }

    /**
     * The `type:` id a scenario names this check by — the `@SerialName` carried on
     * the schema type ([Check]). Exhaustive on purpose: a new check in the closed
     * vocabulary must extend this mapping, and the compiler says so, so an
     * `expect-failure:` id can never quietly stop resolving.
     */
    private fun Check.checkId(): String = when (this) {
        is FinalView -> "final-view"
        is ViewsConverge -> "views-converge"
        is IncrementalEqualsBatch -> "incremental-equals-batch"
        is LateJoinEqualsEarly -> "late-join-equals-early"
        is ObservationsAllSatisfy -> "observations-all-satisfy"
        is ObservationsMonotone -> "observations-monotone"
        is ObservationsWholeWaves -> "observations-whole-waves"
        is ReplicasConverge -> "replicas-converge"
        NoDeadLetters -> "no-dead-letters"
        is EffectCount -> "effect-count"
        is WavePlaneUnchanged -> "wave-plane-unchanged"
        is PagesEqualView -> "pages-equal-view"
    }

    // ------------------------------------------------------------------------
    // computenet-dqy.21 — the control assertion's own tests.
    //
    // [assertControlFailedAsDeclared] is the arbiter's own arbiter: everything
    // the corpus's three controls prove about the harness rests on it. These
    // tests drive it directly with synthetic per-run failures, because the only
    // other way to see a wrong-reason control failure is to break the kernel on
    // purpose, and a synthetic failure set states the case exactly.
    //
    // The first test spells the OLD assertion out (`checks.isNotEmpty() &&
    // failures.isNotEmpty()`) and asserts it against the same input, so the
    // regression is visible in one place: the old predicate accepted a control
    // failing for a reason it was never written to detect, and the new one
    // rejects it.
    // ------------------------------------------------------------------------

    /** The synthetic stand-in for `CTL-GF-01`: one declared check, one declared reason. */
    private fun gfLikeControl(
        expectFailure: ExpectFailure? = DECLARED_GF_FAILURE,
        checks: List<Check> = listOf(ObservationsAllSatisfy(view = "v", fn = "even")),
    ) = Scenario(
        id = "CTL-SYNTHETIC-01",
        title = "synthetic control for the runner's own control assertion",
        covers = listOf("22-GF-01"),
        profile = Profile.CORE,
        kind = Kind.CONTROL,
        checks = checks,
        expectFailure = expectFailure,
    )

    /** `CTL-GF-01`'s declared failure, in the wording the reviewer observed 20/20. */
    private fun tornSumFailure() = CheckFailure(
        "observations-all-satisfy",
        "observations-all-satisfy(v, even): event #1 1 fails the predicate",
    )

    /**
     * A wrong-reason failure of the **same** declared check: the empty-log
     * vacuity guard computenet-qaz added. Same check id, different reason — the
     * exact substitution the old assertion could not see.
     */
    private fun emptyLogFailure() = CheckFailure(
        "observations-all-satisfy",
        "observations-all-satisfy(v, even): view 'v' produced no observation at all",
    )

    @Test
    fun `a control failing for a reason it does not declare is rejected, where the old assertion accepted it`() {
        val scenario = gfLikeControl()
        // The control still fails on every run — and has stopped testing
        // torn-sum detection entirely.
        val wrongReason = mapOf(0 to listOf(emptyLogFailure()))

        val oldAssertionAccepted = scenario.checks.isNotEmpty() && wrongReason.isNotEmpty()
        assertTrue(oldAssertionAccepted) {
            "the old control assertion was `checks.isNotEmpty() && !passedAllRuns`; a wrong-reason failure " +
                "satisfies it, which is the defect under test"
        }

        val rejected = assertThrows<AssertionError> {
            assertControlFailedAsDeclared(scenario, wrongReason, runs = 1)
        }
        assertTrue(rejected.message!!.contains("failed for a reason it does not declare")) {
            "expected the wrong-reason diagnosis, got: ${rejected.message}"
        }
        assertTrue(rejected.message!!.contains("produced no observation at all")) {
            "the diagnosis must quote the failure actually observed, got: ${rejected.message}"
        }
    }

    @Test
    fun `a control failing for the reason it declares is accepted`() {
        // Only some runs of the sweep need fail: CTL-GF-01's torn sum is a
        // schedule-dependent observation, not a certainty on every seed.
        val declaredReason = mapOf(3 to listOf(tornSumFailure()))

        assertControlFailedAsDeclared(gfLikeControl(), declaredReason, runs = 20)
    }

    @Test
    fun `a wrong-reason failure on one run of the sweep is rejected even when another run failed correctly`() {
        // Averaging a wrong-reason run away against a correct one would hide
        // exactly the drift this assertion exists to surface.
        val failures = mapOf(2 to listOf(tornSumFailure()), 7 to listOf(emptyLogFailure()))

        assertThrows<AssertionError> { assertControlFailedAsDeclared(gfLikeControl(), failures, runs = 20) }
    }

    @Test
    fun `a control declaring no expect-failure is rejected`() {
        val failures = mapOf(0 to listOf(tornSumFailure()))

        val rejected = assertThrows<AssertionError> {
            assertControlFailedAsDeclared(gfLikeControl(expectFailure = null), failures, runs = 1)
        }
        assertTrue(rejected.message!!.contains("no `expect-failure:` block")) {
            "expected the missing-declaration diagnosis, got: ${rejected.message}"
        }
    }

    @Test
    fun `an expect-failure naming a check the scenario never declares is rejected`() {
        val scenario = gfLikeControl(expectFailure = ExpectFailure(check = "final-view", messageContains = "but read"))
        val failures = mapOf(0 to listOf(tornSumFailure()))

        val rejected = assertThrows<AssertionError> { assertControlFailedAsDeclared(scenario, failures, runs = 1) }
        // The substring has to be one only the STALE-ID diagnosis produces. The
        // wrong-reason diagnosis further down also says "does not declare", so
        // matching that alone would let this test pass with the stale-id check
        // deleted — the same wrong-reason-looks-like-the-right-reason confusion
        // this whole item is about, one level up.
        assertTrue(rejected.message!!.contains("the expectation can never be met")) {
            "expected the undeclared-check diagnosis, got: ${rejected.message}"
        }
        assertTrue(rejected.message!!.contains("names check 'final-view'")) {
            "the diagnosis must quote the stale id, got: ${rejected.message}"
        }
    }

    @Test
    fun `a control declaring no check at all is rejected`() {
        val rejected = assertThrows<AssertionError> {
            assertControlFailedAsDeclared(gfLikeControl(checks = emptyList()), emptyMap(), runs = 20)
        }
        assertTrue(rejected.message!!.contains("declares no check at all")) {
            "expected the no-check diagnosis, got: ${rejected.message}"
        }
    }

    @Test
    fun `a control whose every check passes on every run is still rejected`() {
        val rejected = assertThrows<AssertionError> {
            assertControlFailedAsDeclared(gfLikeControl(), emptyMap(), runs = 20)
        }
        assertTrue(rejected.message!!.contains("every check passed on every one of 20 run(s)")) {
            "expected the never-failed diagnosis (P7), got: ${rejected.message}"
        }
    }

    @Test
    fun `expect-failure on a non-control kind is rejected, and on a control is required`() {
        // The corpus-wide half of the contract (schema/scenario.md: required on
        // every control, rejected on every other kind), asserted for every
        // discovered file by the test factory rather than per run — so it holds
        // under `-Pconcord.profiles=core` too, for the dist/dur files that loop
        // never executes.
        assertExpectFailureMatchesKind(gfLikeControl())

        val example = gfLikeControl().copy(kind = Kind.EXAMPLE, expectFailure = DECLARED_GF_FAILURE)
        val onExample = assertThrows<AssertionError> { assertExpectFailureMatchesKind(example) }
        assertTrue(onExample.message!!.contains("only `kind: control` may declare")) {
            "expected the wrong-kind diagnosis, got: ${onExample.message}"
        }

        assertExpectFailureMatchesKind(example.copy(expectFailure = null))

        val bareControl = gfLikeControl(expectFailure = null)
        val onControl = assertThrows<AssertionError> { assertExpectFailureMatchesKind(bareControl) }
        assertTrue(onControl.message!!.contains("no `expect-failure:` block")) {
            "expected the missing-declaration diagnosis, got: ${onControl.message}"
        }
    }

    /**
     * Run a `kind: generative` scenario: sweep [DEFAULT_GEN_INSTANCES] (or the
     * block's `instances:`, or `-Pconcord.gen.instances`) distinct pipelines, each
     * synthesized by [ScenarioGenerator] from the instance index. Instance index
     * seeds both the generation and the [KernelDriver] schedule, so a failure is
     * one reproducible (graph, schedule) pair. Every generated instance must pass
     * all four standard property checks (`incremental-equals-batch`,
     * `views-converge`, `late-join-equals-early`, `no-dead-letters`) — a generated
     * graph that only passes by being trivial is worthless (honesty rule), so the
     * generator builds real depth from the vocabulary.
     */
    private fun runGenerative(scenario: Scenario) {
        val gen = scenario.generator
            ?: error("${scenario.id}: kind is generative but no generator: block is present")
        val instances = System.getProperty("concord.gen.instances")?.toIntOrNull()
            ?: gen.instances
            ?: DEFAULT_GEN_INSTANCES

        val failuresByInstance = LinkedHashMap<Int, List<CheckFailure>>()
        for (i in 0 until instances) {
            val concrete = ScenarioGenerator.generate(scenario, i)
            val driver = KernelDriver(i.toLong())
            buildGraph(driver, concrete)
            val reads = runScript(driver, concrete)
            driver.quiesce(QUIESCE_BUDGET)
            val failures = evaluateChecks(RunContext(driver, concrete, reads), concrete.checks)
            if (failures.isNotEmpty()) failuresByInstance[i] = failures
        }

        assertTrue(failuresByInstance.isEmpty()) {
            val (instance, msgs) = failuresByInstance.entries.first().let { it.key to it.value }
            "${scenario.id}: generative check(s) failed on ${failuresByInstance.size} of $instances instance(s). " +
                "First failing instance ($instance): ${msgs.joinToString("; ") { it.message }}"
        }
    }

    private fun buildGraph(driver: Driver, scenario: Scenario) {
        val graph = scenario.graph ?: return
        graph.hosts?.forEach { driver.createHost(it) }
        graph.cells.forEach { cell ->
            driver.spawn(cell.host ?: "", cell.id, cell.type, params(cell))
        }
        graph.links.forEach { link ->
            driver.connect(link.from, link.to, link.inlet, link.outlet, link.role)
        }
    }

    /**
     * Replay one run's script, returning the bounded-read walks it performed
     * (V1C-CONCORD). Every other step is side-effecting only.
     */
    private fun runScript(driver: Driver, scenario: Scenario): List<ReadWalk> {
        val reads = mutableListOf<ReadWalk>()
        scenario.script.forEach { step ->
            when (step) {
                is ReadStateStep -> reads += walk(driver, step)
                is ApplyStep -> repeat(step.times ?: 1) { driver.apply(step.on, step.op, step.value) }
                is QuiesceStep -> driver.quiesce(step.budget ?: QUIESCE_BUDGET)
                is ConnectStep -> {
                    val result = driver.connect(step.from, step.to, step.inlet, step.outlet, step.role)
                    assertExpect(step.expect, result, "connect ${step.from}->${step.to}")
                }
                is DisconnectStep -> {
                    // W3-0: the driver holds the endpoint→linkRef map, so a disconnect
                    // named by endpoints resolves and unlinks the exact link.
                    val result = (driver as KernelDriver).disconnectEndpoint(step.from, step.to, step.inlet, step.outlet)
                    assertExpect(step.expect, result, "disconnect ${step.from}->${step.to}")
                }
                is SnapshotStep -> snapshots[step.alias] = driver.snapshot(step.on)
                is RestoreStep -> driver.restore(step.host ?: "", step.on, snapshots.getValue(step.from))
                is RestartStep -> driver.restart(step.on)
                // The duplicate-delivery verb: the step states the whole
                // position, so the runner threads it through verbatim and keeps
                // no memory of prior invocations (schema/scenario.md, `####
                // retransmit`).
                is RetransmitStep ->
                    driver.retransmit(
                        step.on, step.inlet, step.source, step.counter, step.op, step.value, step.baseline,
                    )
                is DespawnStep -> driver.despawn(step.on)
            }
        }
        return reads
    }

    /**
     * Perform one `read-state` step: walk [step]'s cell to completion (the
     * driver owns the cursor; the scenario never names one), sampling the read
     * cell's wave plane immediately before and immediately after the whole walk.
     *
     * The samples are taken here rather than inside a check because only this
     * point in the run is *before* the read. The page cap is a wedge guard: a
     * cursor that never terminates is a driver defect, and it must surface as a
     * loud failure rather than as a hung sweep.
     */
    private fun walk(driver: Driver, step: ReadStateStep): ReadWalk {
        val waveBefore = driver.wavePlane(step.on)
        val pages = mutableListOf<ReadPage>()
        var cursor: ReadCursor? = null
        do {
            val page = driver.readState(step.on, cursor, step.limit)
            pages += page
            cursor = page.next
            assertTrue(pages.size <= MAX_READ_PAGES) {
                "read-state on '${step.on}' (limit ${step.limit}) produced more than $MAX_READ_PAGES pages " +
                    "without terminating — the cursor never reported a complete walk"
            }
        } while (cursor != null)

        return ReadWalk(
            cell = step.on,
            limit = step.limit,
            pages = pages,
            waveBefore = waveBefore,
            waveAfter = driver.wavePlane(step.on),
        )
    }

    private val snapshots = LinkedHashMap<String, ByteArray>()

    private fun assertExpect(expect: Expect?, result: civictech.concord.driver.LinkResult, what: String) {
        val expected = expect ?: Expect.CONNECTED
        val connected = result is civictech.concord.driver.LinkResult.Connected
        assertTrue((expected == Expect.CONNECTED) == connected) {
            "$what: expected $expected but got $result"
        }
    }

    private fun params(cell: CellSpec): Map<String, Value> = buildMap {
        cell.of?.let { put("of", Value.StrVal(it)) }
        cell.fn?.let { put("fn", Value.StrVal(it)) }
        cell.agg?.let { put("agg", Value.StrVal(it)) }
        cell.k?.let { put("k", Value.IntVal(it.toLong())) }
        cell.glitchFree?.let { put("glitch-free", Value.BoolVal(it)) }
        cell.inletMode?.let { put("inlet-mode", Value.StrVal(it)) }
        cell.replicaOf?.let { put("replica-of", Value.StrVal(it)) }
        cell.interest?.let { put("interest", interestValue(it)) }
        cell.window?.let { put("window", windowValue(it)) }
    }

    /** Lower a scenario's `window:` descriptor to the neutral [Value] model (24-OP-WINDOW-01/02). */
    private fun windowValue(w: WindowSpec): Value = Value.MapVal(
        buildMap {
            put("kind", Value.StrVal(if (w.kind == WindowKind.TUMBLING) "tumbling" else "sliding"))
            put("size", Value.IntVal(w.size))
            w.slide?.let { put("slide", Value.IntVal(it)) }
        },
    )

    /** Lower a scenario's `interest:` descriptor to the neutral [Value] model (42-INTEREST-01). */
    private fun interestValue(spec: civictech.concord.schema.InterestSpec): Value = Value.of(
        buildMap<String, Any?> {
            spec.total?.let { put("total", it) }
            spec.empty?.let { put("empty", it) }
            spec.slots?.let { put("slots", it) }
            spec.totalSlots?.let { put("total-slots", it) }
            spec.ranges?.let { put("ranges", it) }
        },
    )
}
