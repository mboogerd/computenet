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
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    private fun adds(script: Script): List<ScriptEvent.Add> =
        script.slices.flatMap { it.events }.filterIsInstance<ScriptEvent.Add>()

    private companion object {
        /** A state no kernel fold can produce: outside `ElementDomains`' alphabet. */
        val SENTINEL: ModelState = ModelState.SetState(setOf("bs14-sentinel"))
    }
}
