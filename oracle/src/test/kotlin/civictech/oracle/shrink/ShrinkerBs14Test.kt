package civictech.oracle.shrink

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
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
     * `WavePrefixTest`'s `generatedSweepConfig()` shape, reproduced here rather than imported
     * because that file is `private` and not this item's to change — same pattern as
     * [baselineShapedConfig] above. Its `SEAM_SEEDS` (`[30L, 40L, 50L, 58L]`) each shrink under
     * `wavePrefix = WavePrefixOption.ALWAYS` to a genuine [RunOutcome.WavePrefixViolation]
     * counterexample — the population computenet-p5qy's defect 2 was measured against.
     */
    private fun wavePrefixSweepConfig() = GeneratorConfig(
        depthRange = 3..5,
        sourceCount = 1,
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
        scriptLength = 30,
        addRemoveRatio = 0.6,
        unobservedRemoveRatio = 0.25,
        terminalCount = 1,
    ).validated()

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
     * `WavePrefixTest`'s four `SEAM_SEEDS` (`[30L, 40L, 50L, 58L]`) under [wavePrefixSweepConfig]
     * are the exact population the defect was measured against: each shrinks under
     * `wavePrefix = WavePrefixOption.ALWAYS` to a [RunOutcome.WavePrefixViolation], and
     * `WavePrefixOption.DEFAULT.selects(seed)` is `false` for 30 and 40, `true` for 50 and 58 — so
     * rendering the replay with no `wavePrefix` argument (the pre-fix behavior, reproduced
     * directly below rather than through a compiled snippet) reports `Mismatch` for 30/40 and
     * only coincidentally gets 50/58 right.
     */
    @Test
    fun `Ex-BS-14b a WavePrefixViolation counterexample replays as WavePrefixViolation, not Mismatch`() {
        val config = wavePrefixSweepConfig()
        SEAM_SEEDS.forEach { seed ->
            val case = CaseGenerator(config).generate(seed)

            val result = Shrinker.run(case, wavePrefix = WavePrefixOption.ALWAYS)
            withClue("seed=$seed must shrink to a WavePrefixViolation — the population this defect was measured on") {
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

    private fun adds(script: Script): List<ScriptEvent.Add> =
        script.slices.flatMap { it.events }.filterIsInstance<ScriptEvent.Add>()

    private companion object {
        /** A state no kernel fold can produce: outside `ElementDomains`' alphabet. */
        val SENTINEL: ModelState = ModelState.SetState(setOf("bs14-sentinel"))

        /**
         * `WavePrefixTest`'s pinned seam seeds under [wavePrefixSweepConfig] — see that file's own
         * `SEAM_SEEDS` KDoc for provenance. Reproduced as a literal list here (not imported: the
         * constant is `private` there) because this file needs the exact population defect 2 was
         * measured against, not merely A population that produces `WavePrefixViolation`.
         */
        val SEAM_SEEDS: List<Long> = listOf(30L, 40L, 50L, 58L)
    }
}
