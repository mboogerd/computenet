package civictech.oracle.run

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.cell.host.LocationRegistry
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The differential halves of BS-7 (`[ORA1-DIFF-05]`), BS-9 and BS-4 (computenet-4ru.8.6).
 *
 * BS-7 and BS-9 hand-CONSTRUCT their [GeneratedCase] values, per this feature's own
 * established discipline (`GeneratedCaseExecutionTest`'s KDoc): invoking the generator there
 * would make those tests track the generator's current choices instead of the runner's
 * behavior. BS-4 is the deliberate exception — see [diamondCase]'s KDoc for why it invokes
 * [CaseGenerator] with a PINNED seed instead.
 */
class LateJoinerAndPlacementTest {

    private val writer = WriterId("w")
    private val sourceA = SourceId("a")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    /**
     * `set(a) -> filter -> {early, late}` — one filter node read by two terminals: `early`
     * links at build time (as every non-late terminal does), `late` links only at the
     * script's [CaseStep.Barrier], after the graph has quiesced. Both name the SAME node
     * handle, so their expected states are identical in the model regardless of when the
     * kernel side links — the property under test is that the late-linked kernel fold
     * actually CATCHES UP to that same state rather than only seeing events after the link.
     */
    private fun lateJoinerCase(script: CaseScript, seed: Long = 4001L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
            ),
            terminals = listOf(
                TerminalSpec("early", "flt", late = false),
                TerminalSpec("late", "flt", late = true),
            ),
            placement = mapOf("src" to 0, "flt" to 0),
        ),
        spec = spec(
            SpawnStep("src", factory(CoreOperators.Ids.SET)),
            SpawnStep("flt", factory(CoreOperators.Ids.FILTER)),
            ConnectStep("src", "outlet", "flt", "inlet"),
        ),
        script = script,
        removeAudit = emptyList(),
    )

    /**
     * Ops BEFORE the barrier (so the late terminal must catch up on history it never
     * observed live), a [CaseStep.Barrier] (the late link point), more ops AFTER it (so the
     * late terminal must also keep tracking going forward), and a trailing [CaseStep.Barrier]
     * to force a final `onBarrier` snapshot — [DifferentialRunner.run]'s only observation
     * hook into live fold state.
     *
     * `CoreOperators.Ids.FILTER`'s registered predicate is `TEXT_LENGTH_IS_EVEN`
     * (`GeneratedCaseExecutionTest`), so even-length text survives: `aa`, `cc`, `dddd`.
     */
    private fun lateJoinerScript() = CaseScript(
        listOf(
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
            CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "bbb")),
            CaseStep.Barrier,
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "dddd")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "e")),
            CaseStep.Barrier,
        ),
    )

    @Test
    fun `BS-7 late terminal catches up to early terminal and the model`() {
        val observations = mutableListOf<Map<String, ModelState>>()

        val outcome = DifferentialRunner.run(lateJoinerCase(lateJoinerScript())) { observations += it }

        outcome shouldBe RunOutcome.Success
        // Not vacuous: the shared filtered set really holds the surviving even-length text.
        val expected = ModelState.SetState(setOf("aa", "cc", "dddd"))

        // At the FIRST barrier (right after linking), the late terminal is at minimum present.
        observations.size shouldBe 2
        observations[0].keys shouldBe setOf("early", "late")

        // At the trailing (second) barrier, after the post-link ops and a full drain, late ==
        // early == the model's full-script result [24-CATCHUP-01]/[21-CATCHUP-02].
        val final = observations[1]
        final["early"] shouldBe expected
        final["late"] shouldBe expected
        final["late"] shouldBe final["early"]
    }

    /**
     * The BS-7 coverage gap a prior review found (computenet-4ru.8.6): mutating the shipped
     * code to link the late terminal at case-BUILD time instead of at the Barrier left the
     * test above STILL PASSING, because both terminals name the same node handle and converge
     * to the identical model state regardless of when the late fold joins — the final-state
     * comparison cannot tell "linked early" from "linked at the Barrier, caught up correctly"
     * apart. What the property actually claims ([ORA1-DIFF-05]) is about WHEN linking happens,
     * not just that it eventually does — so this test observes that moment directly through
     * [DifferentialRunner.run]'s `onAssembled` hook, fired right after
     * [CaseExecution.assemble] builds the graph and BEFORE any script step — including the
     * first Barrier — is driven.
     *
     * [CaseExecution.assemble] never links a late terminal (it filters `!it.late`), so the
     * correct answer here is always the case's eager terminals only: `{"early"}`. A regression
     * that links "late" any earlier than the Barrier — including the exact mutation the
     * reviewer performed, moving the call into the `buildGraph` lambda — makes "late" appear
     * in this set too, failing the assertion below while the BS-7 test above stays green.
     */
    @Test
    fun `BS-7 coverage gap - late terminal is not yet linked when the graph is first assembled`() {
        val linkedAtAssembly = mutableListOf<Set<String>>()

        val outcome = DifferentialRunner.run(
            lateJoinerCase(lateJoinerScript()),
            onAssembled = { linkedAtAssembly += it },
        ) { }

        outcome shouldBe RunOutcome.Success
        linkedAtAssembly shouldBe listOf(setOf("early"))
    }

    // -- BS-9: single-host vs two-host placement --------------------------

    /**
     * `set(a) -> filter -> out`, identical topology/spec/script to [lateJoinerCase] minus the
     * late terminal — the only thing that varies between calls is [placement]. `"src" -> 0,
     * "flt" -> 0` is the single-host case; `"src" -> 0, "flt" -> 1` places the filter on a
     * SECOND host, exercising [CaseExecution.assemble]'s cross-host `ConnectStep` path (the
     * `src.outlet -> flt.inlet` connect step then crosses hosts) — the guard BS-9 replaces.
     */
    private fun placementCase(placement: Map<String, Int>, seed: Long = 5001L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
            ),
            terminals = listOf(TerminalSpec("out", "flt", late = false)),
            placement = placement,
        ),
        spec = spec(
            SpawnStep("src", factory(CoreOperators.Ids.SET)),
            SpawnStep("flt", factory(CoreOperators.Ids.FILTER)),
            ConnectStep("src", "outlet", "flt", "inlet"),
        ),
        script = CaseScript(
            listOf(
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
                CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "bbb")),
                CaseStep.Barrier,
            ),
        ),
        removeAudit = emptyList(),
    )

    /**
     * The SAME case (`[placementCase]`, same seed, same script) run once with `"flt"` on host
     * `0` (single-host) and once with `"flt"` on host `1` (two-host, one shared
     * `LocationRegistry` per run — `[CaseExecution.assemble]`'s KDoc): both must equal the
     * model's result AND each other, under `[ORA1-GEN-10]`'s runner half.
     */
    @Test
    fun `BS-9 single-host and two-host placement agree with the model and each other`() {
        // Structural guard, before anything else: "flt" really does land on a DIFFERENT
        // ManagedHost in the two-host case, checked through the registry rather than trusting
        // CaseAssembly's own bookkeeping. Without this, a regression that silently collapsed
        // every ordinal's spawn onto host 0 while still creating (but never using) the second
        // host — as the former `require(placement.values.all { it == 0 })` guard's removal
        // could regress to — would pass the rest of this test by luck: a single-host replay of
        // the same case trivially agrees with itself, and `CaseGraph.extraHosts` being
        // non-empty proves only that a second `ManagedHost` object EXISTS, not that anything
        // was placed on it. Measured: forcing `CaseExecution.assemble`'s internal `hostFor` to
        // always return host 0 leaves `extraHosts.size` unchanged (the host is still built) but
        // this location check catches it.
        val singleWorld = SimWorld(seed = 5001L)
        val singleHostAssembly = CaseExecution.assemble(placementCase(mapOf("src" to 0, "flt" to 0)), singleWorld)
        val twoHostWorld = SimWorld(seed = 5001L)
        val twoHostAssembly = CaseExecution.assemble(placementCase(mapOf("src" to 0, "flt" to 1)), twoHostWorld)

        singleHostAssembly.graph.extraHosts shouldHaveSize 0
        twoHostAssembly.graph.extraHosts shouldHaveSize 1

        val singleHostFltLocation = singleWorld.registry.location(singleHostAssembly.refs.getValue("flt"))
        singleHostFltLocation.shouldBeInstanceOf<LocationRegistry.Local>()
        (singleHostFltLocation as LocationRegistry.Local).host shouldBe singleWorld.host

        val twoHostFltLocation = twoHostWorld.registry.location(twoHostAssembly.refs.getValue("flt"))
        twoHostFltLocation.shouldBeInstanceOf<LocationRegistry.Local>()
        (twoHostFltLocation as LocationRegistry.Local).host shouldNotBe twoHostWorld.host

        val singleHostObservations = mutableListOf<Map<String, ModelState>>()
        val twoHostObservations = mutableListOf<Map<String, ModelState>>()

        val singleHostOutcome = DifferentialRunner.run(placementCase(mapOf("src" to 0, "flt" to 0))) {
            singleHostObservations += it
        }
        val twoHostOutcome = DifferentialRunner.run(placementCase(mapOf("src" to 0, "flt" to 1))) {
            twoHostObservations += it
        }

        singleHostOutcome shouldBe RunOutcome.Success
        twoHostOutcome shouldBe RunOutcome.Success

        // Not vacuous: the filter keeps even-length text, so "bbb" (added then removed) and
        // the odd-length survivors are excluded either way — {"aa", "cc"} is what remains.
        val expected = ModelState.SetState(setOf("aa", "cc"))

        singleHostObservations.size shouldBe 1
        twoHostObservations.size shouldBe 1
        singleHostObservations[0]["out"] shouldBe expected
        twoHostObservations[0]["out"] shouldBe expected
        singleHostObservations[0]["out"] shouldBe twoHostObservations[0]["out"]
    }

    // -- BS-4: a generated diamond agrees with the model --------------------

    private fun diamondConfig() = GeneratorConfig(
        depthRange = 3..5,
        sourceCount = 3,
        vocabulary = listOf(
            CoreOperators.Ids.SET,
            CoreOperators.Ids.KEYED_SET,
            CoreOperators.Ids.FILTER,
            CoreOperators.Ids.FLAT_MAP_SET,
            CoreOperators.Ids.MAP_SET,
            CoreOperators.Ids.COUNT,
            CoreOperators.Ids.UNION,
            CoreOperators.Ids.INTERSECT,
            CoreOperators.Ids.PRESENCE_COUNT,
            CoreOperators.Ids.QUORUM_SET,
        ),
        elementDomainSize = 6,
        scriptLength = 40,
        addRemoveRatio = 0.6,
        unobservedRemoveRatio = 0.25,
        terminalCount = 1,
    ).validated()

    /** Number of distinct paths `from -> to` over the spec's connect steps (the graph is a DAG). */
    private fun pathCount(spec: GraphSpec, from: String, to: String): Long {
        val out = spec.steps.filterIsInstance<ConnectStep>().groupBy({ it.from }, { it.to })
        val memo = HashMap<String, Long>()
        fun count(node: String): Long = memo.getOrPut(node) {
            if (node == to) 1L else out[node].orEmpty().sumOf { count(it) }
        }
        return if (from == to) 1L else out[from].orEmpty().sumOf { count(it) }
    }

    /** Source/terminal pairs joined by two or more distinct paths — the diamond witness. */
    private fun diamondSourceTerminalPairs(case: GeneratedCase): List<Pair<String, String>> {
        val sources = case.topology.nodes.filter { it.source != null }.map { it.handle }
        val terminals = case.topology.terminals.map { it.handle }
        return sources.flatMap { s -> terminals.map { t -> s to t } }
            .filter { (s, t) -> pathCount(case.spec, s, t) >= 2L }
    }

    /**
     * `(diamondConfig(), seed = 0)` — invoked through [CaseGenerator], unlike every other case
     * in this file. `GraphGeneratorTest`'s Ex/diamond sweep (computenet-4ru.6.2) already
     * proves diamond-yielding seeds exist within the first 100 under this exact config
     * (measured 73/100); confirmed directly here (2026-08-19) that seed `0` is one of them —
     * `diamondSourceTerminalPairs` finds `source-2 -> op-3-0` joined by >= 2 distinct paths
     * through an `intersect` fan-in.
     *
     * PINNING a seed and hand-constructing a topology are different defenses against different
     * failure modes. A hand-built diamond only ever proves the runner can execute a diamond
     * someone thought to write down. This proves the ACTUAL generator, run through the ACTUAL
     * runner, on a seed frozen at review time, still produces one — so a change to
     * `GraphGenerator`'s draws that stops seed 0 from being a diamond fails this test LOUDLY
     * (the shape assertion below, checked BEFORE agreement) instead of silently letting the
     * case degrade to an ordinary chain and the test keep passing for the wrong reason.
     */
    private fun diamondCase(): GeneratedCase = CaseGenerator(diamondConfig()).generate(0L)

    @Test
    fun `BS-4 a generated diamond case agrees with the model`() {
        val case = diamondCase()

        // Assert the shape FIRST: a pinned seed that stops producing a diamond must fail here,
        // loudly, rather than silently degrading this into a test of an ordinary chain.
        val diamonds = diamondSourceTerminalPairs(case)
        withClue("seed ${case.seed}: expected at least one source-to-terminal pair joined by >= 2 distinct paths") {
            diamonds.shouldNotBeEmpty()
        }

        val outcome = DifferentialRunner.run(case)

        outcome shouldBe RunOutcome.Success
    }
}
