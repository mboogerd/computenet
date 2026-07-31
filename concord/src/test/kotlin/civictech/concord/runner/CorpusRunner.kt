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
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DespawnStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.Kind
import civictech.concord.schema.Profile
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.ReadStateStep
import civictech.concord.schema.RestoreStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.SnapshotStep
import civictech.concord.schema.WindowKind
import civictech.concord.schema.WindowSpec
import civictech.concord.value.Value
import civictech.concord.yaml.ConcordYaml
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
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
 * asserted to *fail* — at least one declared check must fail on some run.
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
            if (scenario.profile.slug() !in active) return@mapNotNull null
            DynamicTest.dynamicTest("${scenario.id} (${file.parentFile.name})") { runScenario(scenario) }
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

    private fun runScenario(scenario: Scenario) {
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
        val failuresByRun = LinkedHashMap<Int, List<String>>()
        for (run in 0 until runs) {
            val driver = KernelDriver(run.toLong())
            buildGraph(driver, scenario)
            val reads = runScript(driver, scenario)
            driver.quiesce(QUIESCE_BUDGET)
            val ctx = RunContext(driver, scenario, reads)
            val failures = scenario.checks.mapNotNull { check ->
                when (val r = Checks.evaluate(check, ctx)) {
                    CheckResult.Passed -> null
                    is CheckResult.Failed -> r.message
                    is CheckResult.NotImplemented -> "check '${r.check}' is not implemented"
                }
            }
            if (failures.isNotEmpty()) failuresByRun[run] = failures
        }

        val passedAllRuns = failuresByRun.isEmpty()
        when (scenario.kind) {
            Kind.CONTROL ->
                // P7 (the harness must be able to fail): a control carries a
                // deliberately wrong expectation and MUST fail at least one of its
                // real, routed checks on some run.
                assertTrue(scenario.checks.isNotEmpty() && !passedAllRuns) {
                    "${scenario.id}: control scenario was expected to FAIL a declared check " +
                        "but every check passed on every one of $runs run(s)"
                }
            else ->
                assertTrue(passedAllRuns) {
                    val (run, msgs) = failuresByRun.entries.first().let { it.key to it.value }
                    "${scenario.id}: check(s) failed on ${failuresByRun.size} of $runs run(s). " +
                        "First failing run ($run): ${msgs.joinToString("; ")}"
                }
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

        val failuresByInstance = LinkedHashMap<Int, List<String>>()
        for (i in 0 until instances) {
            val concrete = ScenarioGenerator.generate(scenario, i)
            val driver = KernelDriver(i.toLong())
            buildGraph(driver, concrete)
            val reads = runScript(driver, concrete)
            driver.quiesce(QUIESCE_BUDGET)
            val ctx = RunContext(driver, concrete, reads)
            val failures = concrete.checks.mapNotNull { check ->
                when (val r = Checks.evaluate(check, ctx)) {
                    CheckResult.Passed -> null
                    is CheckResult.Failed -> r.message
                    is CheckResult.NotImplemented -> "check '${r.check}' is not implemented"
                }
            }
            if (failures.isNotEmpty()) failuresByInstance[i] = failures
        }

        assertTrue(failuresByInstance.isEmpty()) {
            val (instance, msgs) = failuresByInstance.entries.first().let { it.key to it.value }
            "${scenario.id}: generative check(s) failed on ${failuresByInstance.size} of $instances instance(s). " +
                "First failing instance ($instance): ${msgs.joinToString("; ")}"
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
