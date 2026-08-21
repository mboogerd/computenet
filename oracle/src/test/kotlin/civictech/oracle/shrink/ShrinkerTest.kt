package civictech.oracle.shrink

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseDelivery
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

    // ------------------------------------------- computenet-r38y: gossip survives a shrink

    /**
     * The bug this pins: every candidate assembly rebuilt the script with `CaseScript(steps)`,
     * the single-argument constructor, so a shrink DROPPED `CaseScript.deliveries` — and a
     * replicated counterexample reduced to a non-replicated one that no longer reproduces. It
     * failed silently, which is the failure shape this epic exists to remove, so a test that only
     * asserted "the shrink returned something" would have gone green throughout.
     *
     * Asserted twice over, deliberately: the field survives, AND the reduced script still
     * *projects* to a replicated model script. The second is the one that cannot be satisfied by
     * a field that is merely carried around — [CaseScript.toScript] only derives an `absorbed`
     * lane if a delivery genuinely names a position in the surviving drive order.
     */
    @Test
    fun `computenet-r38y a shrunk replicated case still carries its gossip deliveries`() {
        val case = gossipingCase(seed = 1L)
        val terminal = case.topology.terminals.first().name

        val result = Shrinker.run(case, reference = alwaysFails(case, terminal))

        withClue("a shrink that drops the gossip silently reduces a replicated case to a non-replicated one") {
            result.case.script.deliveries.isNotEmpty() shouldBe true
        }
        withClue("deliveries=${result.case.script.deliveries} steps=${result.case.script.steps.size}") {
            result.case.script.toScript().slices.any { it.deliveries.isNotEmpty() } shouldBe true
        }
    }

    /**
     * The `atStep` shift, stated as the property that makes it correct rather than as an index
     * arithmetic golden value: a delivery is a POSITION, so after any reduction it must still be
     * a position this script has. `CaseScript`'s own `init` rejects an out-of-range `atStep`, so
     * an unshifted or over-shifted delivery surfacing here throws rather than returning a wrong
     * answer — and pass 1 deletes aggressively under [alwaysFails], so the shift is exercised
     * hard (most scripts reduce to a handful of steps from 60).
     */
    @Test
    fun `computenet-r38y a shrunk case's deliveries stay positions in the script that survived`() {
        (1L..3L).forEach { seed ->
            val case = gossipingCase(seed)
            val terminal = case.topology.terminals.first().name

            val result = Shrinker.run(case, reference = alwaysFails(case, terminal))

            withClue("seed=$seed steps=${result.case.script.steps.size} deliveries=${result.case.script.deliveries}") {
                // Non-vacuity first: a shrink that dropped the gossip would satisfy every
                // statement below by having nothing to check.
                result.case.script.deliveries.isNotEmpty() shouldBe true
                result.case.script.deliveries.forEach { delivery ->
                    (delivery.atStep in 0..result.case.script.steps.size) shouldBe true
                }
                // Re-constructing from the same parts must not throw: the `init` contract above,
                // asserted directly so this test names the invariant rather than relying on the
                // shrinker having happened to run it.
                CaseScript(result.case.script.steps, result.case.script.deliveries)
            }
        }
    }

    /**
     * Pass 3's rule, and the one worth a test of its own: [Shrinker]'s `without` removes a source
     * node and every step that drove it, and a delivery naming that source must be **dropped**,
     * never merely shifted. A shift-only fix leaves a script asserting "replica X absorbed
     * replica Y" for a Y this case no longer contains — `CaseScript` cannot catch it (its `init`
     * checks the step index, not the source names) and `toScript` would mint a slice for the
     * absent source, so the case shrinks into a DIFFERENT replication shape while still looking
     * like a faithful reduction. That is the same silent wrongness as the dropped-gossip bug,
     * relocated one pass along.
     *
     * The fixture is chosen so pass 3 genuinely has a source to drop: [retiringConfig] puts three
     * sources behind two terminals, and the injected failure names only the first — so pass 3
     * drops the second terminal, then the branch nothing reads any more, and with it a source
     * that two of the three attached deliveries name.
     */
    @Test
    fun `computenet-r38y no delivery survives that names a source the shrink removed`() {
        (1L..3L).forEach { seed ->
            val case = branchedGossipingCase(seed)
            val terminal = case.topology.terminals.first().name

            val result = Shrinker.run(case, reference = alwaysFails(case, terminal))

            val before = case.topology.nodes.mapNotNull { it.source }.toSet()
            val after = result.case.topology.nodes.mapNotNull { it.source }.toSet()
            val retired = before - after
            withClue("seed=$seed: pass 3 retired no source, so this test would witness nothing") {
                retired.isNotEmpty() shouldBe true
            }
            withClue("seed=$seed: the fixture's gossip must name a retired source, or nothing is dropped") {
                case.script.deliveries.any { it.into in retired || it.from in retired } shouldBe true
            }
            withClue("seed=$seed retired=$retired deliveries=${result.case.script.deliveries}") {
                // The gossip BETWEEN survivors must still be there — otherwise "no delivery names
                // a retired source" is satisfied by having dropped every delivery, which is the
                // very bug this bead removes.
                result.case.script.deliveries.isNotEmpty() shouldBe true
                result.case.script.deliveries.forEach { delivery ->
                    after.contains(delivery.into) shouldBe true
                    after.contains(delivery.from) shouldBe true
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ fixtures

    /**
     * A **gossiping** case: a two-branch ORA1 topology whose script carries [CaseDelivery]s
     * between its two sources.
     *
     * ## Why the deliveries are attached rather than generated
     *
     * `GeneratorConfig.replicatedSweep()` produces the real thing, but its sources are `orMap`
     * cells and `CaseExecution.scriptSourceFor` refuses those: *"Source node 'source-0'
     * instantiates catalog id 'orMap', for which the runner has no script binding"*. The
     * shrinker EXECUTES every candidate, so a generated replicated case cannot be shrunk at all
     * on this branch — the ORA2 runner half that drives a mesh is not landed here yet
     * (measured, not assumed: see this item's report).
     *
     * What is landed, and what this bead is actually about, is the *data*: `CaseScript` carries
     * `deliveries`, and the three candidate-assembly sites plus the renderer have to preserve
     * them. A `CaseDelivery` is inert to the kernel drive loop — `DifferentialRunner` walks
     * `case.script.steps` and nothing else — so attaching gossip to a drivable topology exercises
     * exactly the bookkeeping under test, with a case the runner can still execute. When the mesh
     * runner lands, the same assertions should be re-pointed at `replicatedSweep()` and will then
     * cover the generated shape too.
     *
     * [attachGossip] spreads the positions across the drive order on purpose, but this fixture has
     * only TWO sources and therefore only two deliveries — at the front and in the middle. The
     * `steps.size` tail position is reached by [branchedGossipingCase]'s third delivery (and by
     * `CounterexampleRenderTest`'s render fixture), not here.
     */
    private fun gossipingCase(seed: Long): GeneratedCase = attachGossip(generated(fanInConfig(), seed))

    /**
     * The same, on [retiringConfig] — the shape in which pass 3 genuinely retires a source, so
     * `without`'s drop rule is reachable. Separate from [gossipingCase] on purpose: there the
     * deliveries must SURVIVE, here some of them must not, and one fixture cannot witness both.
     */
    private fun branchedGossipingCase(seed: Long): GeneratedCase = attachGossip(generated(retiringConfig(), seed))

    /**
     * Three set sources and two terminals over a `union` vocabulary: enough that a failure naming
     * one terminal lets pass 3 retire a source while at least two survive. Two survivors is the
     * point — with only two sources every delivery names the retired one (a replica does not
     * gossip with itself), so *every* delivery would be legitimately dropped and the surviving-set
     * assertion would hold vacuously. Here some gossip must survive AND none of it may name what
     * was retired, which is the pair of statements `without`'s drop rule actually has to satisfy.
     */
    private fun retiringConfig() = chainConfig().copy(
        sourceCount = 3,
        terminalCount = 2,
        vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.UNION),
    ).validated()

    /**
     * Two set sources fanning in to one `union` and one terminal. Both sources are read by the
     * union, so pass 3 retires neither and a delivery between them has to survive the whole
     * shrink on its own merits rather than because nothing challenged it.
     */
    private fun fanInConfig() = chainConfig().copy(
        sourceCount = 2,
        terminalCount = 1,
        vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.UNION),
    ).validated()

    /**
     * [case] with [CaseDelivery]s attached between its two sources.
     *
     * ## Why the deliveries are attached rather than generated
     *
     * `GeneratorConfig.replicatedSweep()` produces the real thing, but its sources are `orMap`
     * cells and `CaseExecution.scriptSourceFor` refuses those: *"Source node 'source-0'
     * instantiates catalog id 'orMap', for which the runner has no script binding"*. The shrinker
     * EXECUTES every candidate, so a generated replicated case cannot be shrunk at all on this
     * branch — the ORA2 runner half that drives a mesh is not landed here yet (measured, not
     * assumed).
     *
     * What IS landed, and what this bead is about, is the *data*: `CaseScript` carries
     * `deliveries`, and the three candidate-assembly sites plus the renderer have to preserve
     * them. A `CaseDelivery` is inert to the kernel drive loop — `DifferentialRunner` walks
     * `case.script.steps` and nothing else — so attaching gossip to a drivable topology exercises
     * exactly the bookkeeping under test with a case the runner can still execute. When the mesh
     * runner lands, these assertions should be re-pointed at `replicatedSweep()`.
     *
     * One delivery per source, cycled over three positions spread across the drive order — the
     * very front, the middle, and `steps.size` (the "after the last step" position `CaseDelivery`
     * admits) — because a shift bug invisible at index 0 is not invisible at the tail. Cycled, so
     * a two-source case reaches only the first two of those; the tail needs three sources.
     */
    private fun attachGossip(case: GeneratedCase): GeneratedCase {
        val sources = case.topology.nodes.mapNotNull { it.source }.distinct()
        withClue("the fixture needs at least two sources to gossip between") {
            (sources.size >= 2) shouldBe true
        }
        val steps = case.script.steps.size
        // One delivery per adjacent pair, cycled, at spread positions: front, middle, and the
        // `steps.size` tail position `CaseDelivery` admits.
        val positions = listOf(0, steps / 2, steps)
        val deliveries = sources.indices.map { index ->
            CaseDelivery(
                atStep = positions[index % positions.size],
                into = sources[index],
                from = sources[(index + 1) % sources.size],
            )
        }
        return case.copy(script = CaseScript(case.script.steps, deliveries))
    }

    /**
     * A reference that reports [terminal] as [SENTINEL] unconditionally — so every reduction is
     * retained and the passes run to their limit. That is what makes these tests about the
     * shrinker's *bookkeeping* rather than about which reductions a particular failure survives:
     * the maximal reduction is the one most likely to mis-shift or orphan a delivery.
     */
    private fun alwaysFails(case: GeneratedCase, terminal: String): Reference =
        failWhenever(case, terminal) { true }

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
