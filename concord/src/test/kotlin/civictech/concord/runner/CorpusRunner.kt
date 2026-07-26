package civictech.concord.runner

import civictech.concord.driver.Driver
import civictech.concord.driver.kernel.KernelDriver
import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.Check
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DespawnStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.FinalView
import civictech.concord.schema.Kind
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
 * The Concord runner (W1-A): a JUnit dynamic-test harness that discovers every
 * scenario YAML under `concord/corpus`, parses it through the reused [ConcordYaml] factory,
 * and runs each scenario across the schedule sweep against the [KernelDriver].
 *
 * **W1-A scope — `final-view` only.** The full check vocabulary (batch oracle,
 * convergence, glitch-freedom, …) lands in W1-B and is wired in W2; here the
 * runner evaluates only [FinalView] inline. `kind: control` scenarios (P7) are
 * asserted to *fail* their `final-view`; `kind: example` scenarios must *pass*
 * on every run of the sweep. Scenarios with no `final-view` check still execute
 * end to end (the driver binding is exercised) but assert nothing yet.
 */
class CorpusRunner {

    private companion object {
        const val QUIESCE_BUDGET = 5_000_000
        const val DEFAULT_RUNS = 20
        /** Where the runner's working directory sees the corpus (Gradle sets cwd = module dir). */
        val CORPUS = File("corpus")
    }

    @TestFactory
    fun `every corpus scenario runs against the kernel driver`(): List<DynamicTest> {
        val files = CORPUS.walkTopDown().filter { it.isFile && it.extension == "yaml" }.sorted().toList()
        assertTrue(files.isNotEmpty()) { "no corpus scenarios discovered under ${CORPUS.absolutePath}" }
        return files.map { file ->
            val scenario = ConcordYaml.instance.decodeFromString(Scenario.serializer(), file.readText())
            DynamicTest.dynamicTest("${scenario.id} (${file.parentFile.name})") { runScenario(scenario) }
        }
    }

    private fun runScenario(scenario: Scenario) {
        val runs = scenario.runs ?: DEFAULT_RUNS
        val finalViews = scenario.checks.filterIsInstance<FinalView>()

        // Per run: build a fresh seeded driver, replay the graph + script, quiesce,
        // then evaluate every final-view. A run "passes" iff all its final-views hold.
        val runPassed = BooleanArray(runs)
        for (run in 0 until runs) {
            val driver = KernelDriver(run.toLong())
            buildGraph(driver, scenario)
            runScript(driver, scenario.script)
            driver.quiesce(QUIESCE_BUDGET)
            runPassed[run] = finalViews.all { fv ->
                canonical(driver.readView(fv.view)) == canonical(fv.expected)
            }
        }

        when (scenario.kind) {
            Kind.CONTROL ->
                // P7: a control carries a deliberately wrong expectation and MUST fail.
                // W1-A asserts this minimally over final-view; the full P7 gate is W2.
                assertTrue(finalViews.isNotEmpty() && runPassed.any { !it }) {
                    "${scenario.id}: control scenario was expected to FAIL its final-view but every run passed"
                }
            else ->
                assertTrue(runPassed.all { it }) {
                    val failed = (0 until runs).filter { !runPassed[it] }
                    "${scenario.id}: final-view failed on run(s) $failed of $runs. " +
                        "Actual vs expected on run ${failed.first()}: " +
                        describeFailure(scenario, failed.first(), finalViews)
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

    /**
     * Order-insensitive comparison of a view value against a golden: set-views
     * are unordered, so [Value.ListVal]s are sorted before comparing (W1-A
     * pilots are set/scalar views only). The order-aware / true set semantics is
     * W1-B's real evaluator.
     */
    private fun canonical(value: Value): Value = when (value) {
        is Value.ListVal -> Value.ListVal(value.items.map { canonical(it) }.sortedBy { it.toString() })
        is Value.MapVal -> Value.MapVal(value.entries.mapValues { canonical(it.value) })
        else -> value
    }

    private fun describeFailure(scenario: Scenario, run: Int, finalViews: List<FinalView>): String {
        val driver = KernelDriver(run.toLong())
        buildGraph(driver, scenario)
        runScript(driver, scenario.script)
        driver.quiesce(QUIESCE_BUDGET)
        return finalViews.joinToString("; ") { fv ->
            "view '${fv.view}' = ${driver.readView(fv.view)} (expected ${fv.expected})"
        }
    }
}
