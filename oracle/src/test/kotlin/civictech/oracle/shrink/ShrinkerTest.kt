package civictech.oracle.shrink

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.gen.GraphGenerator
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.nanoseconds

/**
 * `[ORA1-SHRINK-01]`, `[ORA1-SHRINK-02]`, `[ORA1-SHRINK-03]` and `[ORA1-SHRINK-05]`: one named
 * test per reduction pass, the budget's truncation field, and the final re-execution gate —
 * including the Ex/SHRINK-05 shape the feature's example mapping names (a failure that needs
 * **both** of two ops, so removing either must not be retained).
 *
 * ## Failure injection: a deliberately wrong `Reference`, never a broken kernel
 *
 * Every failure here is manufactured by substituting a [Reference] that returns a sentinel state
 * for one terminal under a stated predicate over the script, and the *honest* catalog-resolved
 * answer otherwise — the divergence seam [DifferentialRunner.run] already exposes and
 * `OracleSweepTest`'s density test already uses. Nothing mutates a kernel cell, so these tests
 * cannot go green because a kernel defect was fixed, and they cannot go red because one was
 * introduced.
 *
 * That substitution is also what makes a *predicate* the failure condition, which is the only way
 * to test a reduction pass at all: a pass is retained exactly when the failure survives it, so a
 * test has to be able to say which reductions preserve the failure and which do not.
 *
 * ## Why the injected references pin the model of the ORIGINAL topology
 *
 * A [Reference] sees a [Script], never a topology, so an injected one cannot re-resolve the
 * catalog model per candidate. Pass 3 therefore compares a reduced graph against the original
 * graph's model for the terminals it did not drop. That is sound for what these tests assert —
 * the failing terminal's state is the sentinel under the predicate either way — and it is why
 * every config below keeps the *baseline* clean (`writerCount = 1`,
 * `unobservedRemoveRatio = 0.0`, and no reconvergent fan-in operator in the vocabulary): each
 * test asserts that baseline explicitly before injecting anything, so a signature that changed
 * because the model disagreed for an unrelated reason cannot pass unnoticed.
 */
class ShrinkerTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /**
     * A single-source chain: one set source, one unary operator, one terminal. No fan-in, so no
     * reconvergence and a clean baseline; `writerCount = 1` and `unobservedRemoveRatio = 0.0`
     * keep the cross-writer remove seam (computenet-qcm1) out of these tests entirely.
     */
    private fun chainConfig() = GeneratorConfig(
        depthRange = 1..1,
        sourceCount = 1,
        vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.FILTER, CoreOperators.Ids.MAP_SET),
        elementDomainSize = 4,
        scriptLength = 12,
        addRemoveRatio = 0.8,
        unobservedRemoveRatio = 0.0,
        terminalCount = 1,
        writerCount = 1,
    ).validated()

    /**
     * Two independent branches, each source → filter → its own terminal: the shape pass 3 needs,
     * because dropping the second terminal is what makes its whole branch unreferenced and
     * therefore droppable.
     */
    private fun twoBranchConfig() = chainConfig().copy(sourceCount = 2, terminalCount = 2)

    @Test
    fun `ORA1-SHRINK-01 pass 1 deletes op-script steps down to the ones the failure needs`() {
        val case = generated(chainConfig(), seed = 1L)
        val terminal = case.topology.terminals.single().name
        val reference = failWhenever(case, terminal) { script -> adds(script).isNotEmpty() }

        val result = Shrinker.run(case, reference = reference)

        result.truncated shouldBe false
        result.originalSize.scriptSteps shouldBe case.script.steps.size
        // The failure needs one Add and nothing else, so that is exactly what is left: a script
        // reduced to the single step whose removal would make the injected predicate false.
        result.case.script.steps.size shouldBe 1
        result.case.script.steps.single().shouldBeInstanceOf<CaseStep.Op>().event
            .shouldBeInstanceOf<ScriptEvent.Add>()
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal
    }

    @Test
    fun `ORA1-SHRINK-01 pass 2 narrows the element domain onto a single element`() {
        val case = generated(chainConfig(), seed = 2L)
        val terminal = case.topology.terminals.single().name
        // Element-agnostic on purpose: the failure needs three adds, not three *distinct* adds, so
        // pass 1 cannot reduce the domain and pass 2 is the only thing that can.
        val reference = failWhenever(case, terminal) { script -> adds(script).size >= 3 }

        val result = Shrinker.run(case, reference = reference)

        result.truncated shouldBe false
        result.originalSize.elementDomain shouldBeGreaterThan 1
        result.size.elementDomain shouldBe 1
        adds(result.case.script.toScript()).size shouldBe 3
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal
    }

    @Test
    fun `ORA1-SHRINK-01 pass 3 drops the non-failing terminal and the cells only it reached`() {
        val case = generated(twoBranchConfig(), seed = 3L)
        case.topology.terminals.size shouldBe 2
        val failing = case.topology.terminals.first()
        val kept = sourcesFeeding(case.topology, failing.handle)
        withClue("the failing terminal must be fed by exactly one source for this test to be sharp") {
            kept.size shouldBe 1
        }
        // Predicate over the FAILING branch's source only, so pass 1 cannot leave the failure
        // depending on a step of the branch pass 3 is meant to drop.
        val reference = failWhenever(case, failing.name) { script ->
            script.slice(kept.single()).events.any { it is ScriptEvent.Add }
        }

        val result = Shrinker.run(case, reference = reference)

        result.truncated shouldBe false
        result.case.topology.terminals.map { it.name } shouldContainExactly listOf(failing.name)
        result.case.topology.nodes.size shouldBeGreaterThan 0
        result.originalSize.nodes shouldBeGreaterThan result.size.nodes
        // Everything left is on the failing terminal's own path, and the placement map lost the
        // handles with the nodes — a stale placement entry would be a lie a later reader trips on.
        sourcesFeeding(result.case.topology, failing.handle) shouldContainExactly kept.toList()
        result.case.topology.placement.keys.toList() shouldContainExactly
            result.case.topology.nodes.map { it.handle }
        // Re-lowered, not edited: the spec names exactly the surviving handles.
        CaseExecution.renderSpec(result.case.spec) shouldBe
            CaseExecution.renderSpec(GraphGenerator.lower(result.case.topology))
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe failing.name
    }

    /**
     * Ex/SHRINK-05, the shape the feature's example mapping names: a failure that fires only when
     * the script contains **both** op A and op B. Removing either must not be retained, and the
     * reported counterexample must still contain both — which the test proves twice over, once by
     * reading the reduced script and once by re-executing it with each of the two removed.
     */
    @Test
    fun `ORA1-SHRINK-02 a reduction that stops the failure is not retained - Ex-SHRINK-05`() {
        val case = generated(chainConfig(), seed = 4L)
        val terminal = case.topology.terminals.single().name
        val (a, b) = distinctAddedElements(case).let { it[0] to it[1] }
        val reference = failWhenever(case, terminal) { script ->
            val added = adds(script).map { it.element }.toSet()
            a in added && b in added
        }

        val result = Shrinker.run(case, reference = reference)

        result.truncated shouldBe false
        // Both survive every pass: pass 1 cannot delete either, and pass 2's domain collapse
        // (which would fuse them) is not retained.
        adds(result.case.script.toScript()).map { it.element }.toSet() shouldContainExactly setOf(a, b)
        result.case.script.steps.size shouldBe 2
        result.size.elementDomain shouldBe 2

        // The same two reductions the shrinker refused, executed directly: each one passes, which
        // is exactly why neither was retained.
        listOf(a, b).forEach { element ->
            val without = result.case.copy(
                script = CaseScript(
                    result.case.script.steps.filterNot {
                        it is CaseStep.Op && it.event.let { e -> e is ScriptEvent.Add && e.element == element }
                    },
                ),
            )
            withClue("dropping the add of '$element' must make the injected failure disappear") {
                DifferentialRunner.run(without, reference = reference) shouldBe RunOutcome.Success
            }
        }
    }

    /**
     * `[ORA1-SHRINK-05]`, and the proof that the final re-execution is not decoration: the
     * reference is honest from the shrink's *last* evaluation onwards, so the reduced case passes
     * when it is re-executed. A shrinker that reported what it had retained without re-running it
     * would return a counterexample here; this one refuses, having found nothing — not the reduced
     * case, not any case retained on the way, not the original — that still reproduces.
     *
     * The evaluation count is taken from an identical first shrink, whose last evaluation *is* the
     * final re-execution: a generated case is deterministic and the reference behaves identically
     * for every earlier evaluation, so the second shrink follows the same path candidate for
     * candidate.
     */
    @Test
    fun `ORA1-SHRINK-05 a candidate that passes on re-execution is never reported as minimal`() {
        val case = generated(chainConfig(), seed = 5L)
        val terminal = case.topology.terminals.single().name
        val model = CaseExecution.referenceModelFor(case.topology)
        val fails = { script: Script -> adds(script).isNotEmpty() }

        var evaluations = 0
        val counted = Reference { script ->
            evaluations += 1
            model.eval(script).let { if (fails(script)) it + (terminal to SENTINEL) else it }
        }
        Shrinker.run(case, reference = counted)
        val lastEvaluation = evaluations

        var index = 0
        val honestOnTheLastEvaluation = Reference { script ->
            index += 1
            val states = model.eval(script)
            if (index < lastEvaluation && fails(script)) states + (terminal to SENTINEL) else states
        }
        val refusal = assertThrows<IllegalStateException> {
            Shrinker.run(case, reference = honestOnTheLastEvaluation)
        }

        refusal.message!! shouldContain "not reproducible"
        withClue("the shrink must have re-executed past the candidate it retained") {
            index shouldBeGreaterThan lastEvaluation - 1
        }
    }

    @Test
    fun `ORA1-SHRINK-03 an exhausted candidate budget reports the smallest case so far as truncated`() {
        val case = generated(chainConfig(), seed = 6L)
        val terminal = case.topology.terminals.single().name
        val reference = failWhenever(case, terminal) { script -> adds(script).isNotEmpty() }

        val result = Shrinker.run(case, budget = ShrinkBudget(maxCandidates = 1), reference = reference)

        result.truncated shouldBe true
        result.originalSize shouldBe CaseSize.of(case)
        // One candidate bought at most one deletion, so the result is far from the size an
        // unbounded shrink reaches (asserted as 1 step above) — and it still fails.
        result.size.scriptSteps shouldBeGreaterThan 1
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal
    }

    @Test
    fun `ORA1-SHRINK-03 an exhausted wall clock reports the original case as truncated`() {
        val case = generated(chainConfig(), seed = 7L)
        val terminal = case.topology.terminals.single().name
        val reference = failWhenever(case, terminal) { script -> adds(script).isNotEmpty() }

        val result = Shrinker.run(
            case,
            budget = ShrinkBudget(maxCandidates = 500, wallClock = 1.nanoseconds),
            reference = reference,
        )

        result.truncated shouldBe true
        result.case shouldBe case
        result.size shouldBe result.originalSize
        result.outcome.shouldBeInstanceOf<RunOutcome.Mismatch>().terminal shouldBe terminal
    }

    @Test
    fun `shrinking a case that does not fail is refused, never answered with a passing case`() {
        val case = generated(chainConfig(), seed = 8L)

        val refusal = assertThrows<IllegalArgumentException> { Shrinker.run(case) }

        refusal.message!! shouldContain "does not fail"
    }

    /**
     * The re-derived remove audit ([Shrinker.auditFor]) reproduces `ScriptGenerator`'s own audit
     * for a freshly generated case — the pin on the duplication that function's KDoc declares,
     * at both writer counts, for the `unobservedRemoveRatio = 0.0` scripts these tests use and
     * the KDoc's stated limit names.
     */
    @Test
    fun `the re-derived remove audit equals the generator's own for an unobserved-remove-free script`() {
        listOf(1, 2).forEach { writers ->
            (0L..4L).forEach { seed ->
                val case = CaseGenerator(chainConfig().copy(writerCount = writers)).generate(seed)
                withClue("writerCount=$writers seed=$seed") {
                    Shrinker.auditFor(case.script) shouldContainExactly case.removeAudit
                }
            }
        }
    }

    /**
     * The lowering seam this item extracted from `GraphGenerator` is the *same* lowering the
     * generated path runs: re-lowering a generated case's own topology reproduces the spec the
     * generator emitted. `Bs16ReproducibilityTest` pins the stronger, byte-level statement across
     * two JVMs; this is the one a shrink candidate depends on.
     */
    @Test
    fun `re-lowering a generated topology reproduces the generated spec`() {
        listOf(chainConfig(), twoBranchConfig()).forEach { config ->
            (0L..4L).forEach { seed ->
                val case = CaseGenerator(config).generate(seed)
                withClue("seed=$seed") {
                    CaseExecution.renderSpec(GraphGenerator.lower(case.topology)) shouldBe
                        CaseExecution.renderSpec(case.spec)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ fixtures

    /**
     * The case for `(config, seed)`, with its **baseline asserted clean** — the honest
     * catalog-resolved reference agrees with the kernel — so every failure a test then injects is
     * the only failure in play and a signature can be read as evidence about the shrinker.
     */
    private fun generated(config: GeneratorConfig, seed: Long): GeneratedCase {
        val case = CaseGenerator(config).generate(seed)
        withClue("seed=$seed must agree with the catalog reference before a failure is injected") {
            DifferentialRunner.run(case) shouldBe RunOutcome.Success
        }
        return case
    }

    /**
     * A reference that answers honestly (the catalog-resolved model of [case]'s topology) except
     * that [terminal] reads [SENTINEL] whenever [fails] holds for the script.
     *
     * [SENTINEL] is outside `ElementDomains`' alphabet, so a terminal reading it can never equal
     * what the kernel folded — the injected mismatch cannot accidentally agree.
     */
    private fun failWhenever(case: GeneratedCase, terminal: String, fails: (Script) -> Boolean): Reference {
        val model = CaseExecution.referenceModelFor(case.topology)
        return Reference { script ->
            val states = model.eval(script)
            if (fails(script)) states + (terminal to SENTINEL) else states
        }
    }

    private fun adds(script: Script): List<ScriptEvent.Add> =
        script.slices.flatMap { it.events }.filterIsInstance<ScriptEvent.Add>()

    /** The distinct elements [case]'s script adds, in order of first appearance. */
    private fun distinctAddedElements(case: GeneratedCase): List<Any?> =
        adds(case.script.toScript()).map { it.element }.distinct()
            .also {
                withClue("the fixture needs at least two distinct added elements") {
                    it.size shouldBeGreaterThan 1
                }
            }

    /** Every source reachable upstream of [handle]. */
    private fun sourcesFeeding(topology: CaseTopology, handle: String): Set<SourceId> {
        val byHandle = topology.nodes.associateBy { it.handle }
        val seen = mutableSetOf<String>()
        val sources = LinkedHashSet<SourceId>()
        fun walk(current: String) {
            if (!seen.add(current)) return
            val node = byHandle[current] ?: return
            node.source?.let { sources += it }
            node.inputs.forEach(::walk)
        }
        walk(handle)
        return sources
    }

    private companion object {
        /** A state no kernel fold can produce: the element is outside `ElementDomains`' alphabet. */
        val SENTINEL: ModelState = ModelState.SetState(setOf("shrinker-sentinel"))
    }
}
