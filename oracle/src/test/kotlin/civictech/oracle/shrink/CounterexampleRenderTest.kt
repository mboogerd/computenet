package civictech.oracle.shrink

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `[ORA1-SHRINK-04]`, unit-level: [Counterexample.renderKotlin] renders from
 * [civictech.oracle.gen.CaseTopology] + [civictech.oracle.gen.CaseScript] via catalog ids —
 * never [GeneratedCase.spec] — and carries the seed, topology and script as literals. The
 * required end-to-end shape (a mutant reference over a 200-op script, shrunk, replayed,
 * rendered) is `[ShrinkerBs14Test]`; this file is the renderer's own unit coverage, including
 * its two guard rails: an unrenderable literal type and a `Success` outcome.
 *
 * Failure injection follows `ShrinkerTest`'s own pattern: a deliberately wrong [Reference]
 * substituted through [DifferentialRunner.run]'s seam, never a mutated kernel cell.
 */
class CounterexampleRenderTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /** A single-source chain, small enough that shrinking it is fast and its render is readable. */
    private fun chainConfig() = GeneratorConfig(
        depthRange = 1..1,
        sourceCount = 1,
        vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.FILTER),
        elementDomainSize = 4,
        scriptLength = 12,
        addRemoveRatio = 0.8,
        unobservedRemoveRatio = 0.0,
        terminalCount = 1,
        writerCount = 1,
    ).validated()

    @Test
    fun `renderKotlin contains the seed literal, the topology construction, and the script construction`() {
        val counterexample = shrunkCounterexample(seed = 101L)
        val rendered = counterexample.renderKotlin()

        withClue(rendered) {
            rendered shouldContain "val seed = ${counterexample.case.seed}L"
            rendered shouldContain "civictech.oracle.gen.CaseTopology("
            rendered shouldContain "civictech.oracle.gen.TopologyNode("
            rendered shouldContain "civictech.oracle.gen.CaseScript("
            rendered shouldContain "civictech.oracle.gen.CaseStep.Op("
            val outcome = counterexample.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
            rendered shouldContain "RunOutcome.Mismatch"
            rendered shouldContain "\"${outcome.terminal}\""
        }
    }

    @Test
    fun `renderKotlin re-lowers the topology and never prints the GeneratedCase's own spec`() {
        val counterexample = shrunkCounterexample(seed = 102L)
        val rendered = counterexample.renderKotlin()

        withClue(rendered) {
            // The re-lowering call this renderer relies on, so a pasted snippet builds its own
            // spec from the rendered topology rather than depending on a spec value.
            rendered shouldContain "civictech.oracle.gen.GraphGenerator.lower(topology)"
            // Nothing here ever touches the case's own spec, or a GraphSpec's step vocabulary.
            rendered shouldNotContain "SpawnStep"
            rendered shouldNotContain "ConnectStep"
            rendered shouldNotContain "CellFactory"
        }
    }

    @Test
    fun `renderKotlin's replay assertion is self-contained - uses check, not a test framework`() {
        val counterexample = shrunkCounterexample(seed = 103L)
        val rendered = counterexample.renderKotlin()

        withClue(rendered) {
            rendered shouldContain "civictech.oracle.run.DifferentialRunner.run(case)"
            rendered shouldContain "check(outcome is"
            rendered shouldNotContain "io.kotest"
            rendered shouldNotContain "org.junit"
        }
    }

    @Test
    fun `literal renders String, Long, Boolean and null, and escapes quotes and backslashes`() {
        literal("e00") shouldBe "\"e00\""
        literal(7L) shouldBe "7L"
        literal(3) shouldBe "3"
        literal(true) shouldBe "true"
        literal(null) shouldBe "null"
        literal("a\"b\\c") shouldBe "\"a\\\"b\\\\c\""
    }

    @Test
    fun `literal refuses an unrecognized payload type rather than emitting non-compiling text`() {
        val refusal = assertThrows<IllegalStateException> { literal(StringBuilder("nope")) }
        refusal.message!! shouldContain "StringBuilder"
    }

    @Test
    fun `renderKotlin refuses a counterexample whose outcome is Success`() {
        val case = CaseGenerator(chainConfig()).generate(200L)
        val passing = Counterexample(
            case = case,
            outcome = RunOutcome.Success,
            originalSize = CaseSize.of(case),
            truncated = false,
        )

        val refusal = assertThrows<IllegalStateException> { passing.renderKotlin() }
        refusal.message!! shouldContain "Success"
    }

    // ------------------------------------------------------------------------------ fixtures

    /**
     * Generates [seed]'s case under [chainConfig], asserts its baseline is clean, injects a
     * deliberately wrong [Reference] that fails whenever the script adds anything, and shrinks —
     * exactly `ShrinkerTest`'s own pattern, reproduced locally because that file is not this
     * item's to import from.
     */
    private fun shrunkCounterexample(seed: Long): Counterexample {
        val case = CaseGenerator(chainConfig()).generate(seed)
        withClue("seed=$seed must agree with the catalog reference before a failure is injected") {
            DifferentialRunner.run(case) shouldBe RunOutcome.Success
        }
        val terminal = case.topology.terminals.single().name
        val model = CaseExecution.referenceModelFor(case.topology)
        val reference = Reference { script ->
            val states = model.eval(script)
            if (adds(script).isNotEmpty()) states + (terminal to SENTINEL) else states
        }
        return Shrinker.run(case, reference = reference)
    }

    private fun adds(script: Script): List<ScriptEvent.Add> =
        script.slices.flatMap { it.events }.filterIsInstance<ScriptEvent.Add>()

    private companion object {
        val SENTINEL: ModelState = ModelState.SetState(setOf("render-test-sentinel"))
    }
}
