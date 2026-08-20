package civictech.oracle.run

import civictech.cell.data.Aggregators
import civictech.cell.data.SetCell
import civictech.cell.data.op.GroupByCell
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.Aggregates
import civictech.oracle.model.GroupByModel
import civictech.oracle.model.ModelNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.NodeId
import civictech.oracle.model.ReferenceModel
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BS-13 / `[ORA1-DIFF-10]`: **the mutation check** — a deliberately wrong operator model is
 * caught by the differential machinery and *attributed* to the right terminal.
 *
 * This measures the sweep's own discriminating power, not the kernel's correctness. A green
 * sweep is evidence only if a wrong model would have made it red; every assertion below is
 * about that implication, and the kernel is expected to be right throughout. A failure here is
 * a statement about the harness.
 *
 * ## Two prongs, and why the literal BS-13 test needed one
 *
 * BS-13's prose asks for a mutant group-by model run "under the standard sweep". That premise
 * is **stale**, and the correction is recorded on bead computenet-4ru.10.2 rather than papered
 * over here: computenet-4ru.16 established that shape-typed generation structurally cannot emit
 * a `groupBy*` node, because `SetOf(Tuple(2))` is unreachable from every arity-0 source. A
 * generated sweep therefore has no group-by terminal to mutate today, and the literal test
 * would have passed vacuously — a mutant nobody exercises.
 *
 * So the claim is split into the two halves the prose conflated, and each half is checked where
 * it is checkable:
 *
 * - **Prong 1** — [`the mutant group-by model is caught and named at the group-by terminal`] and
 *   its control [`the identical case with the real GroupByModel agrees`]: BS-13's literal
 *   mutant, on a hand-built graph with a real `GroupByCell` terminal, through
 *   [DifferentialRunner.check]'s bring-your-own seam (`[ORA1-DIFF-11]`).
 * - **Prong 2** — [`a mutant model on a generator-reachable operator fails the generated sweep`]
 *   and its control [`the same seed range with the unmutated catalog is green on every seed`]:
 *   the same claim about the **actual generated sweep**, which prong 1 cannot show, using a
 *   catalog id the generator really can reach (`union`).
 *
 * **computenet-4ru.16 is PARKED for a human decision and nothing here resolves it.** Prong 1
 * exists *because* that question is open; it is not an answer to it, and no exclusion of the
 * pair-shaped operator family is written or implied anywhere in this file. If that bead is
 * later decided in favour of making group-by reachable from generation, then — and only then —
 * prong 2 can be widened to run the group-by mutant under the generated sweep, at which point
 * prongs 1 and 2 collapse back into the single test BS-13's prose describes. Until then the
 * split is the honest shape.
 *
 * ## Mutant scope and catalog hygiene
 *
 * Every mutant lives in [MutantModels] under `oracle/src/test/`, never in the shipped catalog.
 * Prong 2 substitutes one id inside one test method; `OperatorCatalog` is a process-wide
 * singleton, so [resetCatalog] restores it in `@AfterEach` exactly as `OracleSweepTest` does — a
 * leaked mutant registration would corrupt every later test in the same JVM.
 */
class MutationCheckTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    // -----------------------------------------------------------------------
    // Prong 1 — BS-13's literal mutant, through the bring-your-own seam
    // -----------------------------------------------------------------------

    private val source = SourceId("s")
    private val writer = WriterId("w")

    /**
     * `add(a1)`, `add(a2)`, `add(b3)`, then an observed `remove(b3)` — a script whose removes
     * empty **exactly one** group.
     *
     * Elements are `<key><amount>`: [FirstCharacterKey] groups them, [TrailingDigitsAsLong] is
     * what the sum reads. Group `a` keeps two live members summing to `3`; group `b`'s only
     * member is retracted by a remove its own writer issued, so the remove is observed
     * (`Membership`'s rule) and `b` dies. `{"a" -> 3}` is the whole correct answer, and `b` is
     * the emptied group every assertion below names.
     */
    private val groupByScript = Script(
        listOf(
            SourceScript(
                source,
                listOf(
                    ScriptEvent.Add(writer, "a1"),
                    ScriptEvent.Add(writer, "a2"),
                    ScriptEvent.Add(writer, "b3"),
                    ScriptEvent.Remove(writer, "b3"),
                ),
            ),
        ),
    )

    /**
     * `SetCell -> GroupByCell(firstCharacter, sumOf(trailingDigits)) -> MapTerminalFold`.
     *
     * The **real** kernel cell, unmutated — only the reference model is ever mutated in this
     * file, which is what makes a mismatch evidence about the oracle machinery rather than
     * about the kernel. The kernel's `keyFn` and selector are the same [FirstCharacterKey] and
     * [TrailingDigitsAsLong] objects the reference models below are configured with, following
     * `CoreOperators`' own discipline: one projection object per registration, so the two halves
     * cannot drift apart on the very function they are supposed to share.
     */
    private fun buildGroupByGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val groupBy = GroupByCell<String, String, Long, Long>(
            keyFn = { element -> FirstCharacterKey.keyOf(element) as String },
            aggregator = Aggregators.sumOf { element: String -> TrailingDigitsAsLong.selectLong(element) },
        )
        val fold = MapTerminalFold<String, Long>()

        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(groupBy)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", groupBy.ref, "inlet")
        world.host.managementInlet.call.connect(groupBy.ref, "outlet", fold.ref, "inlet")

        return CaseGraph(
            terminals = mapOf(GROUP_BY_TERMINAL to fold),
            sources = mapOf(source to setCell.inlet.call.asScriptSource()),
        )
    }

    /** The live-membership source node both reference models below read. */
    private val liveSource = ModelNode.Source(NodeId("live"), source, SetSourceModel)

    /** The retraction-blind source node only the MUTANT reads — see [RetentiveGroupByModel]. */
    private val everAddedSource = ModelNode.Source(NodeId("ever-added"), source, EverAddedSourceModel)

    private val groupByNode = NodeId("group-by")

    /** The honest reference: `GroupByModel` itself, over the live membership fold. */
    private val honestReference = Reference(
        ReferenceModel(
            nodes = listOf(
                liveSource,
                ModelNode.Operator(
                    groupByNode,
                    GroupByModel(FirstCharacterKey, Aggregates.sumOf(TrailingDigitsAsLong)),
                    listOf(liveSource.id),
                ),
            ),
            terminals = mapOf(GROUP_BY_TERMINAL to groupByNode),
        )::eval,
    )

    /**
     * The mutant reference: the SAME model shape with the group-by node's [OperatorModel]
     * swapped for [RetentiveGroupByModel] (plus the extra input that mutant needs, which is
     * the only structural difference and is explained on that class).
     */
    private val mutantReference = Reference(
        ReferenceModel(
            nodes = listOf(
                liveSource,
                everAddedSource,
                ModelNode.Operator(
                    groupByNode,
                    RetentiveGroupByModel(FirstCharacterKey, Aggregates.sumOf(TrailingDigitsAsLong)),
                    listOf(liveSource.id, everAddedSource.id),
                ),
            ),
            terminals = mapOf(GROUP_BY_TERMINAL to groupByNode),
        )::eval,
    )

    /**
     * BS-13 `[ORA1-DIFF-10]`, prong 1: the mutant group-by model is caught, the report names the
     * group-by terminal, and the difference names the **emptied group's key**.
     *
     * The key is the load-bearing part. "A mismatch occurred" would also be satisfied by a
     * mutant that got group `a`'s sum wrong, which is not the bug BS-13 names; asserting that
     * `b` is present under the mutant expectation and absent from the kernel's actual is what
     * pins the failure to group death (`[24-OP-GROUPBY-02]`, `[ORA1-MODEL-06]`).
     *
     * ## The outcome KIND is asserted, not just "not Success"
     *
     * `[RunOutcome]`'s precedence is `NonQuiescence > DeadLetterFailure >
     * ModelEvaluationFailure > WavePrefixViolation > Mismatch` (`FailureTaxonomyTest`), and
     * `Mismatch` is the LOWEST. If any other condition were simultaneously in force this run
     * would report that kind instead, and a mutant that fired would be indistinguishable from
     * one that did not. Asserting `Mismatch` by kind is therefore also the assertion that
     * nothing else went wrong. (`check` never prefix-checks — it passes `checker = null` — so
     * `WavePrefixViolation` is unreachable on this path by construction.)
     */
    @Test
    fun `the mutant group-by model is caught and named at the group-by terminal`() {
        val outcome = DifferentialRunner.check(
            seed = 13L,
            caseMarker = GROUP_BY_MARKER,
            script = groupByScript,
            reference = mutantReference,
            buildGraph = ::buildGroupByGraph,
        )

        val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe 13L
        mismatch.terminal shouldBe GROUP_BY_TERMINAL
        mismatch.renderedGraphSpec shouldBe GROUP_BY_MARKER
        mismatch.script shouldBe groupByScript

        // The mutant keeps the dead group with the sum's identity; the kernel removed it.
        mismatch.expected shouldBe ModelState.MapState(mapOf("a" to 3L, "b" to 0L))
        mismatch.actual shouldBe ModelState.MapState(mapOf("a" to 3L))

        val difference = mismatch.difference.shouldBeInstanceOf<StateDifference.MapDifference>()
        // Named, not merely non-empty: `b` is the group the script emptied.
        difference.onlyInExpected shouldBe mapOf("b" to 0L)
        difference.onlyInActual shouldBe emptyMap()
        // The surviving group agrees, so the mismatch is group death alone.
        difference.changed shouldBe emptyMap()
    }

    /**
     * Prong 1's control, and the reason prong 1 measures anything: the **identical** case — same
     * `buildGraph`, same script, same seed — with the real `GroupByModel` reaches
     * [RunOutcome.Success].
     *
     * Without this, the mismatch above could belong to the case rather than to the mutation: a
     * mis-wired graph, a width mismatch (`ScalarState(2)` vs `ScalarState(2L)` is the trap
     * `Aggregates`' KDoc records), or a script the honest model and the kernel already disagree
     * about would all produce a `Mismatch` naming this terminal while proving nothing about the
     * mutant.
     */
    @Test
    fun `the identical case with the real GroupByModel agrees`() {
        val outcome = DifferentialRunner.check(
            seed = 13L,
            caseMarker = GROUP_BY_MARKER,
            script = groupByScript,
            reference = honestReference,
            buildGraph = ::buildGroupByGraph,
        )

        outcome shouldBe RunOutcome.Success
    }

    // -----------------------------------------------------------------------
    // Prong 2 — sweep power on a generator-reachable operator
    // -----------------------------------------------------------------------

    /**
     * Prong 2 `[ORA1-DIFF-10]`: a mutant model substituted for a generator-reachable catalog id
     * makes the **generated** sweep fail, and the failure is *attributable* — the mismatching
     * terminal's upstream closure contains a node of the mutant id.
     *
     * ## Why this prong exists beside prong 1
     *
     * Prong 1 proves the comparison/report machinery catches a mutant on a hand-built case. It
     * says nothing about the generated path, which resolves its reference from the catalog,
     * renders its own graph, and drives a `CaseScript` in total order. "The standard sweep
     * catches a mutant model" has to be true of *that* path, and only a substitution on an id
     * the generator can actually reach can show it. `union` is such an id; `groupBy*` is not
     * (see this class's KDoc).
     *
     * ## Substitution route
     *
     * `OperatorCatalog.register` refuses silent replacement, so the route is
     * unregister-then-register. The re-registration reuses the entry's **own** `shape` and
     * `kernel` values read back before the unregister, rather than re-typing them: the mutation
     * is then the model and nothing but the model, by construction rather than by inspection.
     *
     * ## Attribution, not just failure
     *
     * A sweep that went red would be satisfied by any failure anywhere. What BS-13 claims is
     * that the machinery points at the mutated operator, so the closure walk over
     * `case.topology.nodes` is the actual assertion, and the control below is what rules out
     * "red for some other reason".
     *
     * ## Why wave-prefix checking is explicitly OFF
     *
     * `DifferentialRunner.run` defaults `wavePrefix` to a nonzero fraction **for the
     * catalog-resolved reference** — and here the catalog-resolved reference IS the mutant, so
     * every prefix answer is wrong and the first intermediate observation would be reported as
     * a `WavePrefixViolation`. That kind outranks `Mismatch`, so the run would come back
     * attributed to a glitch rather than to a value disagreement, and the assertions below would
     * fail while the mutant had in fact fired. `WavePrefixOption.OFF` is the same reasoning
     * `run`'s own KDoc applies when a reference is substituted through its `reference`
     * parameter; substituting through the catalog instead bypasses that default, so it is
     * restored by hand.
     *
     * **The paragraph above is a hazard this argument forecloses, not one that was observed —
     * the argument is inert on today's config.** Measured at review (2026-08-20, Darwin arm64):
     * [sweepConfig] draws `sourceCount = 4`, every seed of [MUTATION_SEEDS] generates a
     * four-source case, and `WavePrefixOracle.appliesTo` admits only a SINGLE-source case — so
     * `onWavePrefixChecker` reports `null` on all thirty seeds and the per-seed outcomes are
     * identical with this argument and with the default. Nothing is being silenced here today;
     * the `OFF` is what keeps the hazard foreclosed if this config's source count, or that
     * eligibility rule, later changes. Do not read it as evidence a violation was seen.
     */
    @Test
    fun `a mutant model on a generator-reachable operator fails the generated sweep`() {
        val mutated = substituteForgetfulUnion()
        val config = sweepConfig()

        val outcomes = MUTATION_SEEDS.map { seed ->
            val case = CaseGenerator.generate(seed, config)
            case to DifferentialRunner.run(case = case, wavePrefix = WavePrefixOption.OFF)
        }

        // A non-Mismatch failure kind means some OTHER condition was in force and outranked the
        // comparison; naming it here keeps that from reading as "the mutant did not fire".
        val unexpected = outcomes.filterNot { (_, outcome) ->
            outcome is RunOutcome.Success || outcome is RunOutcome.Mismatch
        }
        withClue(
            "Seeds whose outcome was neither Success nor Mismatch — a higher-precedence " +
                "condition fired and this test cannot see the mutation through it: " +
                unexpected.map { (case, outcome) -> "seed=${case.seed} ${outcome::class.simpleName}" },
        ) {
            unexpected shouldBe emptyList()
        }

        val attributed = outcomes.mapNotNull { (case, outcome) ->
            val mismatch = outcome as? RunOutcome.Mismatch ?: return@mapNotNull null
            val closure = upstreamCatalogIds(case.topology, mismatch.terminal)
            if (mutated in closure) case.seed to closure else null
        }

        withClue(
            "The '$mutated' mutant fired on no seed of the fixed range $MUTATION_SEEDS, so this " +
                "test measured nothing. Per the bead: widen the range or bias removals " +
                "(addRemoveRatio < 1) so removals flow through the mutant node before " +
                "concluding — and if it still cannot fire, park it rather than weakening the " +
                "property. Per-seed outcomes: " +
                outcomes.map { (case, outcome) ->
                    "seed=${case.seed} ${outcome::class.simpleName} " +
                        "ids=${case.topology.nodes.map { it.catalogId }.distinct()}"
                },
        ) {
            attributed.shouldNotBeEmpty()
        }

        // How much of the fixed range the mutant reached, printed rather than asserted: the
        // property is "at least one, attributed", and pinning the exact count would make this
        // test fail whenever the generator's draws legitimately shift.
        println(
            "[mutation-check] '$mutated' mutant: ${attributed.size} of ${MUTATION_SEEDS.count()} " +
                "seeds mismatched with the mutant node upstream of the reported terminal " +
                "(seeds ${attributed.map { it.first }}) [ORA1-DIFF-10]",
        )
    }

    /**
     * Prong 2's control: the **same** fixed seed range, the same config, the unmutated catalog —
     * every seed [RunOutcome.Success].
     *
     * This is what makes the attribution above meaningful rather than circumstantial. If the
     * range were red already, a mismatch under the mutant would be evidence of nothing, and the
     * closure walk would be reporting whichever operator happened to sit upstream of a terminal
     * that was going to disagree anyway.
     *
     * The config is `OracleSweepTest`'s BS-1 baseline verbatim, and [MUTATION_SEEDS] is a
     * prefix of the range BS-1 sweeps — so this control is a restatement of an already-green
     * claim on purpose. A red result here is a BS-1 regression, not a fault of this test; read
     * `OracleSweepTest`'s history note before narrowing anything.
     */
    @Test
    fun `the same seed range with the unmutated catalog is green on every seed`() {
        val config = sweepConfig()

        val outcomes = MUTATION_SEEDS.associate { seed ->
            val case = CaseGenerator.generate(seed, config)
            seed to DifferentialRunner.run(case = case, wavePrefix = WavePrefixOption.OFF)
        }

        withClue("Failing seeds: ${outcomes.filterValues { it != RunOutcome.Success }.mapValues { OracleSweep.describe(it.value) }}") {
            outcomes.values.all { it == RunOutcome.Success } shouldBe true
        }
    }

    /**
     * Replaces `union`'s registered model with [ForgetfulUnionModel], keeping its own shape and
     * kernel factory, and returns the substituted id.
     *
     * Undone by [resetCatalog] — nothing in this file leaves a mutant registered past the
     * method that installed it.
     */
    private fun substituteForgetfulUnion(): String {
        val id = CoreOperators.Ids.UNION
        val entry = OperatorCatalog.entry(id) ?: error("'$id' is not registered; CoreOperators.registerAll() did not run")
        OperatorCatalog.unregister(id) shouldBe true
        OperatorCatalog.register(id = id, shape = entry.shape, kernel = entry.kernel, model = ForgetfulUnionModel)
        return id
    }

    /**
     * The catalog ids in [terminalName]'s **upstream closure**, the terminal's own node
     * included: walk `TopologyNode.inputs` from the handle that terminal reads.
     *
     * Computed from `case.topology` rather than from the rendered `GraphSpec`, because the
     * topology is the catalog-id-level description and a catalog id is what was mutated.
     */
    private fun upstreamCatalogIds(topology: CaseTopology, terminalName: String): Set<String> {
        val terminal = topology.terminals.firstOrNull { it.name == terminalName }
            ?: error("Topology declares no terminal named '$terminalName'; it has ${topology.terminals.map { it.name }}")
        val byHandle = topology.nodes.associateBy { it.handle }

        val visited = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(terminal.handle))
        while (pending.isNotEmpty()) {
            val handle = pending.removeFirst()
            if (!visited.add(handle)) continue
            val node = byHandle[handle] ?: error("Topology names handle '$handle', which it does not declare")
            pending += node.inputs
        }
        return visited.mapTo(LinkedHashSet()) { byHandle.getValue(it).catalogId }
    }

    /**
     * `OracleSweepTest.baselineConfig()` verbatim — BS-1's own configuration, reused rather than
     * re-tuned so prong 2's control inherits a range already proven green (see that test's
     * KDoc for every knob's reasoning, including why `writerCount = 1` and
     * `unobservedRemoveRatio = 0.0`).
     *
     * `addRemoveRatio = 0.7` is what the bead asks for: below 1, so generated removes really do
     * flow through the mutant node rather than the sweep only ever growing sets.
     */
    private fun sweepConfig() = GeneratorConfig(
        depthRange = 1..4,
        sourceCount = 4,
        vocabulary = listOf(
            CoreOperators.Ids.SET,
            CoreOperators.Ids.FILTER,
            CoreOperators.Ids.FLAT_MAP_SET,
            CoreOperators.Ids.MAP_SET,
            CoreOperators.Ids.UNION,
            CoreOperators.Ids.INTERSECT,
        ),
        elementDomainSize = 8,
        scriptLength = 200,
        addRemoveRatio = 0.7,
        unobservedRemoveRatio = 0.0,
        terminalCount = 2,
        writerCount = 1,
    ).validated()

    private companion object {
        const val GROUP_BY_TERMINAL = "byKey"
        const val GROUP_BY_MARKER = "BS-13 SetCell -> GroupByCell(firstCharacter, sumOf) -> terminal"

        /**
         * Prong 2's **fixed, checked-in** seed range. Never rotated to make a run green: a
         * range chosen after seeing its result measures nothing, which is the same discipline
         * the divergence control's 60-seed range is held to.
         *
         * ## Sizing, and what the measurement does and does not bound
         *
         * Measured 2026-08-20 (Darwin arm64, base commit f1fae916): the [ForgetfulUnionModel]
         * mutant mismatched on **13 of these 30 seeds** — 1, 10, 11, 12, 13, 15, 18, 19, 23,
         * 26, 27, 28, 29 — every one with a `union` node in the reported terminal's upstream
         * closure, at a total cost of ~0.27 s for the mutated run and ~0.10 s for the control.
         *
         * The first ten seeds alone yield exactly **one** firing seed (seed 1), which is why
         * the range is thirty rather than ten: one is enough for the property but leaves no
         * margin, and this test's whole purpose is to keep measuring something after the
         * generator's draws legitimately shift. The figure is a property of *this* mutant, this
         * config and this generator, not a general firing rate — a different mutant on a
         * different id would have to be re-sized the same way.
         */
        val MUTATION_SEEDS: LongRange = 0L..29L
    }
}
