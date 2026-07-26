package civictech.concord.runner

import civictech.concord.check.CheckContext
import civictech.concord.check.CheckResult
import civictech.concord.check.Checks
import civictech.concord.driver.Driver
import civictech.concord.driver.kernel.KernelDriver
import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DespawnStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.Kind
import civictech.concord.schema.Profile
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.RestoreStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.SnapshotStep
import civictech.concord.schema.Step
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
        /** Where the runner's working directory sees the corpus (Gradle sets cwd = module dir). */
        val CORPUS = File("corpus")
    }

    /** A [CheckContext] pairing one run's already-quiesced [driver] with its [scenario]. */
    private class RunContext(override val driver: Driver, override val scenario: Scenario) : CheckContext

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
            runScript(driver, scenario.script)
            driver.quiesce(QUIESCE_BUDGET)
            val ctx = RunContext(driver, scenario)
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

    private fun runScript(driver: Driver, script: List<Step>) {
        script.forEach { step ->
            when (step) {
                is ApplyStep -> repeat(step.times ?: 1) { driver.apply(step.on, step.op, step.value) }
                is QuiesceStep -> driver.quiesce(step.budget ?: QUIESCE_BUDGET)
                is ConnectStep -> {
                    val result = driver.connect(step.from, step.to, step.inlet, step.outlet, step.role)
                    assertExpect(step.expect, result, "connect ${step.from}->${step.to}")
                }
                is DisconnectStep -> {
                    // The schema names a disconnect by endpoints; W1-A does not track the
                    // endpoint→linkRef map (no pilot disconnects), so this is a no-op stub
                    // beyond honouring an explicit expect. Endpoint-keyed disconnect is W3.
                    if (step.expect == Expect.REJECTED) error("disconnect expect:rejected not modelled in W1-A")
                }
                is SnapshotStep -> snapshots[step.alias] = driver.snapshot(step.on)
                is RestoreStep -> driver.restore(step.host ?: "", step.on, snapshots.getValue(step.from))
                is DespawnStep -> driver.despawn(step.on)
            }
        }
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
        cell.glitchFree?.let { put("glitch-free", Value.BoolVal(it)) }
        cell.inletMode?.let { put("inlet-mode", Value.StrVal(it)) }
        cell.replicaOf?.let { put("replica-of", Value.StrVal(it)) }
    }
}
