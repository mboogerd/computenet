package civictech.oracle.shrink

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
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
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.WavePrefixOption
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Ex/BS-14 (feature computenet-4ru.12's design block), the required end-to-end test named by
 * this item's bead: a mutant [Reference] makes a fixed seed fail with [RunOutcome.Mismatch] on
 * terminal T over a 200-op script (an `OracleSweepTest.baselineConfig`-shaped
 * [GeneratorConfig]); [Shrinker.run] reduces it to a script strictly smaller than 200 steps;
 * re-running the reduced case still yields [RunOutcome.Mismatch] on T; and the rendered Kotlin
 * ([Counterexample.renderKotlin]) contains at minimum the seed literal, the topology/graph
 * steps, and the script steps.
 *
 * `OracleSweepTest.baselineConfig()` is `private` to `OracleSweepTest.kt`, which this item does
 * not claim, so its shape (depth 1..4, 4 sources, the set-algebra vocabulary, an 8-element
 * domain, 200 script ops, a 0.7 add/remove ratio, no unobserved removes, a single writer, 2
 * terminals) is reproduced here rather than imported or made non-private.
 *
 * The mutant reference is a deliberately wrong [Reference] substituted through
 * [DifferentialRunner.run]'s seam — `ShrinkerTest`'s own established pattern (see its "Failure
 * injection" KDoc) — never a mutated kernel cell: the honest catalog-resolved model answers
 * every terminal except that terminal T reads a sentinel state once the script has added
 * anything at all, which is a predicate the honest model would otherwise apply correctly —
 * "one that drops a predicate", per the bead's own phrasing for this seam.
 *
 * ## The two replay tests use a hand-built case, not a mutant and not a generated one
 *
 * Ex-BS-14b (computenet-p5qy defect 2) and Ex-BS-14c (computenet-kgsd) are about the
 * `wavePrefix` argument the renderer emits, so each needs a case that fails **differently with
 * and without it**. A mutant [Reference] cannot supply that: the rendered replay carries no
 * `reference` argument at all (`RenderKotlin.kt` says so in the snippet's own NOTE), so a
 * mutant-manufactured counterexample replays as `Success` whatever the `wavePrefix` argument
 * says, and the discriminator disappears.
 *
 * Both therefore drive [divergingCase] — computenet-eeys in three events, failing against the
 * **catalog-resolved** reference exactly as the generated seam seeds used to. See [SEEDS] for
 * why the generated population they previously used no longer contains a failing case
 * (computenet-4ru.20).
 */
class ShrinkerBs14Test {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun baselineShapedConfig() = GeneratorConfig(
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

    /**
     * The BS-8 diamond over a single `set` source: `filter` and `flatMapSet` arms reconverging
     * at a `union`, observed at one terminal.
     *
     * Reproduced rather than imported — `WavePrefixTest.diamondCase` is `private` and lives in
     * `civictech.oracle.run`, the same reason [baselineShapedConfig] reproduces
     * `OracleSweepTest.baselineConfig`'s shape. The two arms are deliberately *different*
     * operators: identical arms carry identical element sets, so `add("ab")` contributing `{ab}`
     * through one and `{a, b}` through the other is what makes a half-published wave a state
     * that is no prefix at all.
     *
     * [seed] is a label, not a draw — the case's content does not depend on it. What it does
     * decide is [civictech.oracle.gen.GeneratedCase.controllerSeed] (hence the drive schedule)
     * and, load-bearingly here, `WavePrefixOption.DEFAULT.selects(seed)`.
     */
    private fun diamondCase(seed: Long, vararg events: ScriptEvent): GeneratedCase {
        fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel
        return GeneratedCase(
            seed = seed,
            topology = CaseTopology(
                nodes = listOf(
                    TopologyNode("src", CoreOperators.Ids.SET, emptyList(), SOURCE),
                    TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
                    TopologyNode("exp", CoreOperators.Ids.FLAT_MAP_SET, listOf("src"), null),
                    TopologyNode("u", CoreOperators.Ids.UNION, listOf("flt", "exp"), null),
                ),
                terminals = listOf(TerminalSpec("united", "u")),
                placement = mapOf("src" to 0, "flt" to 0, "exp" to 0, "u" to 0),
            ),
            spec = GraphSpec(
                listOf(
                    SpawnStep("src", factory(CoreOperators.Ids.SET)),
                    SpawnStep("flt", factory(CoreOperators.Ids.FILTER)),
                    SpawnStep("exp", factory(CoreOperators.Ids.FLAT_MAP_SET)),
                    SpawnStep("u", factory(CoreOperators.Ids.UNION)),
                    ConnectStep("src", "outlet", "flt", "inlet"),
                    ConnectStep("src", "outlet", "exp", "inlet"),
                    ConnectStep("flt", "outlet", "u", "inlet"),
                    ConnectStep("exp", "outlet", "u", "inlet"),
                ),
            ),
            script = CaseScript(events.map { CaseStep.Op(SOURCE, it) }),
            removeAudit = emptyList(),
        )
    }

    /**
     * The naturally-failing case both replay tests are driven from, at [seed]: `w0` adds `ab`,
     * `w1` adds `ab`, `w0` removes `ab` — computenet-eeys at its minimum, three events, no
     * generator and no substituted reference.
     *
     * Against the catalog-resolved reference this case is **both** dirty at intermediate waves
     * and wrong at quiescence, which is exactly what these two tests need and what no *generated*
     * case can supply any more (see [SEEDS]):
     *
     * - `Membership` covers only the adds the removing writer observed, so `w0`'s remove leaves
     *   `w1`'s add uncovered and the model answers `{ab, a, b}` at the terminal;
     * - `SetCell.inletHandler.remove` retracts `liveTags("ab")` — both tags, one cell, one
     *   serialization point — so the kernel answers the empty set;
     * - under `WavePrefixOption.OFF` that surfaces at quiescence as [RunOutcome.Mismatch];
     * - under `WavePrefixOption.ALWAYS` the empty set observed after the remove matches only
     *   `prefixes[0]`, *below* the terminal's floor, so it surfaces first as a
     *   [RunOutcome.WavePrefixViolation] of kind `REGRESSED` and the run never reaches the
     *   final comparison.
     *
     * One case, two outcome kinds selected purely by the `wavePrefix` argument, is precisely the
     * discriminator computenet-p5qy defect 2 and computenet-kgsd are about.
     *
     * **The kernel is the right side of this divergence** (`[24-SET-03]`'s observer is the cell;
     * a generated drive path builds one replica) — see `WavePrefixTest`'s
     * `a remove of an element another writer added is applied by the kernel and ignored by the
     * model`. Nothing here is a pinned kernel defect; the case is used only as a reliable source
     * of a failing run.
     */
    private fun divergingCase(seed: Long): GeneratedCase = diamondCase(
        seed,
        ScriptEvent.Add(W0, "ab"),
        ScriptEvent.Add(W1, "ab"),
        ScriptEvent.Remove(W0, "ab"),
    )

    @Test
    fun `Ex-BS-14 a mutant reference over a 200-op script shrinks, replays, and renders`() {
        val seed = 4200L
        val case = CaseGenerator(baselineShapedConfig()).generate(seed)
        case.script.steps.size shouldBe 200
        withClue("seed=$seed must agree with the catalog reference before a failure is injected") {
            DifferentialRunner.run(case) shouldBe RunOutcome.Success
        }

        val terminal = case.topology.terminals.first().name
        val model = CaseExecution.referenceModelFor(case.topology)
        val reference = Reference { script ->
            val states = model.eval(script)
            if (adds(script).isNotEmpty()) states + (terminal to SENTINEL) else states
        }

        val outcomeBeforeShrink = DifferentialRunner.run(case, reference = reference)
        outcomeBeforeShrink.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal

        val result = Shrinker.run(case, reference = reference)

        withClue("the shrink must actually reduce the 200-op script") {
            result.case.script.steps.size shouldBeLessThan 200
        }

        // Re-running the shrunk result still yields Mismatch on the same terminal — checked both
        // directly and via the Counterexample's own recorded outcome (Shrinker's confirming
        // re-execution, ORA1-SHRINK-05).
        val replay = DifferentialRunner.run(result.case, reference = reference)
        replay.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal

        val rendered = result.renderKotlin()
        withClue(rendered) {
            rendered shouldContain "val seed = ${result.case.seed}L"
            rendered shouldContain "civictech.oracle.gen.CaseTopology("
            rendered shouldContain "civictech.oracle.gen.TopologyNode("
            rendered shouldContain "civictech.oracle.gen.CaseScript("
            rendered shouldContain "civictech.oracle.gen.CaseStep.Op("
            rendered shouldContain "RunOutcome.Mismatch"
            rendered shouldContain "\"$terminal\""
        }
    }

    /**
     * computenet-p5qy defect 2: a shrunk [RunOutcome.WavePrefixViolation] counterexample must
     * replay as the SAME outcome kind and terminal it was shrunk to — not as [RunOutcome.Mismatch]
     * because the emitted replay dropped the `wavePrefix` option the shrink was given.
     *
     * The population is [divergingCase] at each of [SEEDS], and the seeds are what carry the
     * defect: `WavePrefixOption.DEFAULT.selects(seed)` is `false` for 30 and 40 and `true` for 50
     * and 58, so the pre-fix rendering — a bare `DifferentialRunner.run(case)`, which resolves
     * [WavePrefixOption.DEFAULT] — reports `Mismatch` for 30/40 and only *coincidentally* gets
     * 50/58 right. A population on one side of that split could not tell a fixed renderer from a
     * broken one.
     */
    @Test
    fun `Ex-BS-14b a WavePrefixViolation counterexample replays as WavePrefixViolation, not Mismatch`() {
        SEEDS.forEach { seed ->
            val case = divergingCase(seed)

            val result = Shrinker.run(case, wavePrefix = WavePrefixOption.ALWAYS)
            withClue("seed=$seed must shrink to a WavePrefixViolation — the outcome kind this defect is about") {
                result.outcome.shouldBeInstanceOf<RunOutcome.WavePrefixViolation>()
            }
            val violation = result.outcome as RunOutcome.WavePrefixViolation

            // The pre-fix rendering emitted `DifferentialRunner.run(case)` with no `wavePrefix`
            // argument at all — reproduced directly (not through a compiled snippet, which no
            // test here can invoke) to show it does NOT reliably reproduce the violation.
            val unfixedReplay = DifferentialRunner.run(result.case)
            withClue(
                "seed=$seed, DEFAULT.selects=${WavePrefixOption.DEFAULT.selects(seed)}: the pre-fix " +
                    "replay (no wavePrefix argument) got $unfixedReplay",
            ) {
                if (WavePrefixOption.DEFAULT.selects(seed)) {
                    unfixedReplay.shouldBeInstanceOf<RunOutcome.WavePrefixViolation>()
                } else {
                    // The bug this defect fixes: half the population replays as the WRONG kind.
                    unfixedReplay.shouldBeInstanceOf<RunOutcome.Mismatch>()
                }
            }

            // The FIXED rendering: exactly the call renderCounterexample emits for a
            // WavePrefixViolation outcome (RenderKotlin.kt), executed here inline since no test
            // in this module can invoke the Kotlin compiler on the rendered string itself.
            val fixedReplay = DifferentialRunner.run(result.case, wavePrefix = WavePrefixOption.ALWAYS)
            withClue("seed=$seed: the fixed replay must reproduce the same kind and terminal") {
                fixedReplay.shouldBeInstanceOf<RunOutcome.WavePrefixViolation>().terminal shouldBe violation.terminal
            }

            val rendered = result.renderKotlin()
            withClue(rendered) {
                rendered shouldContain "civictech.oracle.run.DifferentialRunner.run(case, " +
                    "wavePrefix = civictech.oracle.run.WavePrefixOption.ALWAYS)"
                rendered shouldContain "RunOutcome.WavePrefixViolation"
                rendered shouldContain "\"${violation.terminal}\""
                rendered shouldNotContain "Shrinker"
            }
        }
    }

    /**
     * computenet-kgsd: the mirror of Ex-BS-14b above, with the two outcome kinds swapped. A
     * counterexample shrunk with prefix checking OFF is a [RunOutcome.Mismatch] — the check
     * never ran — but the pre-fix renderer emitted the bare `DifferentialRunner.run(case)` for
     * every non-[RunOutcome.WavePrefixViolation] outcome, which resolves to
     * [WavePrefixOption.DEFAULT] (a nonzero selection fraction), not [WavePrefixOption.OFF]. So
     * a case whose seed [WavePrefixOption.DEFAULT] happens to select replays with checking
     * turned back on, and a prefix-dirty case then reports [RunOutcome.WavePrefixViolation]
     * instead of the [RunOutcome.Mismatch] the counterexample names.
     *
     * Same population as Ex-BS-14b — [divergingCase] at each of [SEEDS] — shrunk under
     * `WavePrefixOption.OFF` instead. It is `Mismatch` for all four (the check never ran), and
     * the bare replay reports the WRONG kind for exactly the seeds it got right in Ex-BS-14b's
     * mirror direction: 50 and 58, the two `DEFAULT.selects(seed) == true` members.
     */
    @Test
    fun `Ex-BS-14c a Mismatch counterexample shrunk with wave-prefix checking off replays as Mismatch, not WavePrefixViolation`() {
        SEEDS.forEach { seed ->
            val case = divergingCase(seed)

            val result = Shrinker.run(case, wavePrefix = WavePrefixOption.OFF)
            withClue("seed=$seed must shrink to a Mismatch under wavePrefix = OFF") {
                result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
            }
            val mismatch = result.outcome as RunOutcome.Mismatch

            // The pre-fix rendering emitted `DifferentialRunner.run(case)` with no `wavePrefix`
            // argument at all — reproduced directly (not through a compiled snippet, which no
            // test here can invoke) to show it does NOT reliably reproduce the Mismatch.
            val unfixedReplay = DifferentialRunner.run(result.case)
            withClue(
                "seed=$seed, DEFAULT.selects=${WavePrefixOption.DEFAULT.selects(seed)}: the pre-fix " +
                    "replay (no wavePrefix argument) got $unfixedReplay",
            ) {
                if (WavePrefixOption.DEFAULT.selects(seed)) {
                    // The bug this defect fixes: half the population replays as the WRONG kind.
                    unfixedReplay.shouldBeInstanceOf<RunOutcome.WavePrefixViolation>()
                } else {
                    unfixedReplay.shouldBeInstanceOf<RunOutcome.Mismatch>()
                }
            }

            // The FIXED rendering: DifferentialRunner.run(case, wavePrefix =
            // civictech.oracle.run.WavePrefixOption.OFF), the call renderCounterexample must
            // emit for a non-WavePrefixViolation outcome.
            val fixedReplay = DifferentialRunner.run(result.case, wavePrefix = WavePrefixOption.OFF)
            withClue("seed=$seed: the fixed replay must reproduce the same kind and terminal") {
                fixedReplay.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe mismatch.terminal
            }

            val rendered = result.renderKotlin()
            withClue(rendered) {
                rendered shouldContain "civictech.oracle.run.DifferentialRunner.run(case, " +
                    "wavePrefix = civictech.oracle.run.WavePrefixOption.OFF)"
                rendered shouldContain "RunOutcome.Mismatch"
                rendered shouldContain "\"${mismatch.terminal}\""
                rendered shouldNotContain "Shrinker"
            }
        }
    }

    private fun adds(script: Script): List<ScriptEvent.Add> =
        script.slices.flatMap { it.events }.filterIsInstance<ScriptEvent.Add>()

    private companion object {
        /** A state no kernel fold can produce: outside `ElementDomains`' alphabet. */
        val SENTINEL: ModelState = ModelState.SetState(setOf("bs14-sentinel"))

        /** The two writers whose concurrency [divergingCase] turns on. */
        val W0 = WriterId("w0")
        val W1 = WriterId("w1")

        /** The single source [diamondCase] drives. */
        val SOURCE = SourceId("s")

        /**
         * The four seeds Ex-BS-14b and Ex-BS-14c run [divergingCase] at.
         *
         * The list is `WavePrefixTest`'s former `SEAM_SEEDS` literal, kept **for its
         * `WavePrefixOption.DEFAULT.selects` split and for continuity with the measurement
         * computenet-p5qy defect 2 was found on** — `false` for 30 and 40, `true` for 50 and 58.
         * That split is what both tests discriminate on, and it is a pure function of the seed
         * (`WavePrefixOption.selects` hashes it), so it is stable against any generator change.
         *
         * ## Why these are no longer generated cases (computenet-4ru.20)
         *
         * They used to be: `CaseGenerator(wavePrefixSweepConfig()).generate(seed)`, over a copy
         * of `WavePrefixTest.generatedSweepConfig()`, where each seed shrank to a genuine
         * counterexample. `computenet-i3vo` gave `ScriptGenerator` the post-condition that no
         * emitted remove may leave its element live in `Membership`, which is the whole of what
         * made those cases fail — that population is now 60/60 clean, `WavePrefixTest`'s four
         * pinned lists are empty, and `Shrinker.run` refused every one of these seeds with
         * "given a case that does not fail".
         *
         * The repair is [divergingCase]: the *same* mechanism (computenet-eeys), constructed by
         * hand in three events instead of drawn from a generator that can no longer draw it, at
         * the same four seeds. What is lost is that the cases came from the sweep; what is kept —
         * and is the whole of what these two tests need — is a case that fails one way with the
         * `wavePrefix` argument and another way without it, over a population the DEFAULT
         * fraction splits. **Neither test was weakened to accommodate the empty population, and
         * no seed was rotated.**
         */
        val SEEDS: List<Long> = listOf(30L, 40L, 50L, 58L)
    }
}
