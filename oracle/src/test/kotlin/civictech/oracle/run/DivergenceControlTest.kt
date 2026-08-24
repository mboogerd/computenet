package civictech.oracle.run

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
import civictech.oracle.model.ModelNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ReferenceModel
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * BS-12 / `[ORA1-DIFF-09]`, **as measured rather than as originally specified** — the
 * divergence control that was meant to prove the sweep *can* fail, and the finding that it
 * cannot be built against this kernel with this wrong oracle.
 *
 * ## Read this before changing anything here
 *
 * BS-12 asked for a naive arrival-order reference — adds and removes applied in delivery
 * order by value, observed-remove tag coverage ignored — that **reddens on at least one seed**
 * of the fixed range `0..59` while the catalog-resolved real reference stays green on all
 * sixty. Those two bullets are **mutually exclusive against the current kernel**, and not for
 * want of a better config:
 *
 * `SetCell.inletHandler.remove` (kernel/src/main/kotlin/civictech/cell/data/SetCell.kt)
 * retracts `liveTags(element)` **unconditionally** — it never consults the removing writer's
 * causal history, because `SetOps.remove(element)` carries no writer. A single `SetCell` is
 * one serialization point, so its quiescent membership over a source's slice is exactly
 * "present iff the last add/remove event for that element is an add" — which is precisely
 * [NaiveArrivalOrderSetModel] below. The naive fold is therefore not a *wrong* description of
 * this kernel; it is a **more faithful** one than the spec-faithful
 * [civictech.oracle.model.Membership] fold is. So:
 *
 * - naive red on >= 1 seed requires `naive != kernel` on that seed;
 * - real green on all 60 requires `real == kernel` on every seed;
 * - but `naive == kernel` identically, so `naive != kernel` never happens.
 *
 * The disagreement that *does* exist is between the real model and the kernel, and that is
 * **computenet-eeys**, not this control's wrongness. Firing on it would be detecting eeys,
 * which BS-12's third acceptance bullet explicitly excludes.
 *
 * ## What this file therefore pins
 *
 * The human decision of 2026-08-20 (option (a) on this task's comment thread) was to keep the
 * **measurement** rather than weaken the control or build a second wrongness instrument:
 * `[ORA1-DIFF-09]` / BS-12 is **blocked on computenet-eeys**, and the honesty ledger entry
 * lives in `concord/corpus/DISPUTES.md` (computenet-4ru.10.4). The two tests below are that
 * measurement, and neither is a weakened BS-12:
 *
 * 1. `a single writer makes the naive fold indistinguishable from the real reference` —
 *    across the single-writer configs, zero seeds differ between the two references and both
 *    columns are green. This is why the feature design's "raise the unobserved-remove ratio"
 *    escalation is spent: at one writer, `Membership`'s coverage condition (`add.writer == by`)
 *    holds for every prior add, so the causal fold provably collapses to the value fold and
 *    no knob can create a difference. (computenet-qcm1 makes it doubly spent — a generated
 *    unobserved remove never names a live element.)
 * 2. `the naive fold and the real reference agree across the whole generated population` —
 *    across the multi-writer configs the two references now produce the *same* terminal states
 *    on all sixty seeds, and both are green against the kernel. See the next section: this is
 *    the restatement of what was, until 2026-08-21, the sharper measurement "the naive fold
 *    agrees with the kernel on exactly the seeds the real reference fails".
 * 3. `the naive fold agrees with the kernel where the real reference does not` — the finding
 *    itself, carried on a hand-built writer-concurrent script rather than on generated cases.
 *    This is the non-vacuity witness for (2): it proves the comparison in [probe] can still
 *    tell the two references apart, so (2)'s zeros are a measurement rather than a comparison
 *    that stopped working.
 *
 * ## What changed on 2026-08-21, and which repair route was taken (computenet-4ru.20)
 *
 * `computenet-i3vo` gave [civictech.oracle.gen.ScriptGenerator] a post-condition — **no emitted
 * remove may leave its element live in [civictech.oracle.model.Membership]** — which is exactly
 * the step class the naive fold and the real model can disagree about. Their disagreement needs
 * a remove whose coverage differs by writer, and such a remove is precisely one the model
 * leaves live. So from that commit on, **no generated case of any config can produce a
 * differing seed**: measured 0 differing seeds on all eight probes below (P1-P8, seeds 0..59,
 * Darwin arm64, 2026-08-21), against 42/34/43/32 on the four multi-writer probes the day before.
 *
 * The bead offered three routes for this test. **Route (a) was taken — drive the finding from a
 * hand-built writer-concurrent script — and extended so that no evidence is deleted:**
 *
 * - **(b), an explicit generator opt-out used only here, was rejected on two grounds.** It is a
 *   change to the post-condition computenet-i3vo just established, which computenet-4ru.20's
 *   own acceptance criteria forbid; and a knob that manufactures a divergence is one grep away
 *   from being switched on in a suite that would then be measuring the generator rather than
 *   the kernel.
 * - **(c), retiring the control, was rejected because the finding is still true and still
 *   load-bearing.** What computenet-i3vo removed is the *generated population* that exhibited
 *   it, not the asymmetry: `SetCell.inletHandler.remove` still retracts `liveTags(element)`
 *   unconditionally, [civictech.oracle.model.Membership] still covers only the removing writer's
 *   observed adds, and the naive fold is still the more faithful description of this kernel.
 *   Test 3 below demonstrates that on three events. Deleting the measurement would leave BS-12's
 *   "why not" resting on prose alone.
 * - **(a) extended.** The eight-probe generated measurement is *kept and still asserted*, with
 *   its assertion inverted: it now pins that the population contains **no** such step. That
 *   makes it a second, independent instrument on computenet-i3vo's post-condition — a
 *   differential between two references, where `WavePrefixTest`'s
 *   `kernelEffectiveModelInertRemoves` is a predicate over scripts — and a generator regression
 *   that reintroduces the step class reddens both.
 *
 * The tripwire direction inverts with it. Before, test 2 went red when a `SetCell` remove became
 * writer-scoped. Now a red on test 2 means **the generator emitted the step class again**, and
 * the response is to fix the generator, not this file. The original tripwire moved to test 3,
 * which goes red if the kernel and the model stop disagreeing on the hand-built case — at which
 * point BS-12 becomes implementable and this measurement should be replaced by the real control
 * rather than repaired.
 *
 * ## Limits of the numbers below
 *
 * Everything tests 1 and 2 assert is a measurement over **eight probed configs x the fixed range
 * `0..59`** on this generator. It is evidence about what this generator *reaches*, not a proof
 * over the whole config space; the proof-shaped half of the argument is the structural one about
 * `SetCell.inletHandler.remove` above, and the demonstrated half is test 3. Historical divergence
 * **counts** (42/60, 34/60, 43/60, 32/60 as measured 2026-08-20 on Darwin arm64, before
 * computenet-i3vo) are deliberately *not* pinned — see [multiWriterConfigs].
 *
 * Seeds are the fixed checked-in range `0..59` throughout and are **never rotated**: rotating
 * to a friendlier range is the one response to a red here that is never correct.
 */
class DivergenceControlTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /** The fixed, checked-in, never-rotated seed range BS-12 names. */
    private val seeds = 0L..59L

    /**
     * BS-1's config shape at `scriptLength = 200` — the same knobs `OracleSweepTest`'s
     * `baselineConfig` uses, which is what makes the probes below comparable to the sweep the
     * control was supposed to measure. Each probe varies exactly the knobs its name says.
     */
    private fun baseConfig() = GeneratorConfig(
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
    )

    /** The set vocabulary widened with the counting terminals — the feature design's second escalation rung. */
    private fun countingVocabulary() = baseConfig().vocabulary +
        listOf(CoreOperators.Ids.COUNT, CoreOperators.Ids.PRESENCE_COUNT, CoreOperators.Ids.QUORUM_SET)

    /**
     * The single-writer probes: P1 (BS-1 itself), P2 (the unobserved-remove escalation), P5
     * (the counting-vocabulary escalation) and P7 (deeper graphs over a tighter element
     * domain). Every rung of the feature design's escalation ladder that keeps one writer.
     */
    private fun singleWriterConfigs(): List<Pair<String, GeneratorConfig>> = listOf(
        "P1 writerCount=1, unobservedRemoveRatio=0.0" to baseConfig(),
        "P2 writerCount=1, unobservedRemoveRatio=0.4" to baseConfig().copy(unobservedRemoveRatio = 0.4),
        "P5 writerCount=1, +count/presenceCount/quorumSet" to baseConfig().copy(vocabulary = countingVocabulary()),
        "P7 writerCount=1, depthRange=2..6, elementDomainSize=4" to
            baseConfig().copy(depthRange = 2..6, elementDomainSize = 4),
    ).map { (name, config) -> name to config.validated() }

    /**
     * The multi-writer probes: P3/P4 (two writers, with and without unobserved removes), P6
     * (two writers over the counting vocabulary) and P8 (four writers, tight domain, half the
     * removes unobserved).
     *
     * Measured on 2026-08-21 (Darwin arm64), under computenet-i3vo: **0 differing seeds and 0
     * failures in either column on all four**, and the same on the four single-writer probes.
     * That is what test 2 asserts, and it is a change of kind rather than of degree — see the
     * class KDoc's route section.
     *
     * The pre-i3vo figures are kept here as the before/after pair the finding rests on. Measured
     * 2026-08-20 (Darwin arm64), differing seeds / real-reference `Success` seeds of 60: P3
     * 42/18, P4 34/26, P6 43/17, P8 32/28 — in each row the differing count and the real
     * reference's `Mismatch` count were the same number. (The probe comment on
     * computenet-4ru.10.1 reports P8 as "Mismatch 32, Success 32"; 32 + 32 exceeds 60, and that
     * re-measurement read 32 Mismatch / 28 Success. The differing count, the only figure the
     * argument rested on, was 32 either way.) Those historical figures are recorded and **never
     * asserted**: they were a property of that generator's draw, and pinning them would make an
     * unrelated generator change look like a kernel regression.
     */
    private fun multiWriterConfigs(): List<Pair<String, GeneratorConfig>> = listOf(
        "P3 writerCount=2, unobservedRemoveRatio=0.0" to baseConfig().copy(writerCount = 2),
        "P4 writerCount=2, unobservedRemoveRatio=0.4" to
            baseConfig().copy(writerCount = 2, unobservedRemoveRatio = 0.4),
        "P6 writerCount=2, +count/presenceCount/quorumSet" to
            baseConfig().copy(writerCount = 2, vocabulary = countingVocabulary()),
        "P8 writerCount=4, elementDomainSize=4, unobservedRemoveRatio=0.5" to
            baseConfig().copy(writerCount = 4, elementDomainSize = 4, unobservedRemoveRatio = 0.5),
    ).map { (name, config) -> name to config.validated() }

    /**
     * Under a **single writer** the naive arrival-order fold and the real observed-remove model
     * are indistinguishable: they produce the same terminal states on every seed of `0..59`,
     * and both agree with the kernel on every seed.
     *
     * This is the half of the finding that closes the escalation ladder. It is not that the
     * probed knobs happened to miss a divergence — at one writer there is nothing for a knob to
     * find, because [civictech.oracle.model.Membership]'s coverage condition is satisfied by
     * `add.writer == by` for every add, collapsing the causal fold onto the value fold.
     */
    @Test
    fun `a single writer makes the naive fold indistinguishable from the real reference`() {
        singleWriterConfigs().forEach { (name, config) ->
            val probe = probe(name, config)

            withClue("$name: the two references must agree on every terminal of every seed") {
                probe.differingSeeds shouldBe emptyList()
            }
            withClue("$name: the naive arrival-order fold must agree with the kernel on every seed") {
                probe.naiveFailures shouldBe emptyList()
            }
            withClue("$name: the catalog-resolved real reference must agree with the kernel on every seed") {
                probe.realFailures shouldBe emptyList()
            }
        }
    }

    /**
     * Under **multiple writers**, since computenet-i3vo, the two references no longer differ on
     * any generated case: 0 differing seeds and 0 failures in either column on every
     * multi-writer probe, over the fixed range `0..59`.
     *
     * ## What this asserts, now that the numbers are zeros
     *
     * The two references can only disagree about a remove whose *coverage* differs by writer —
     * one that [civictech.oracle.model.Membership] leaves its element live through while the
     * naive value fold deletes it. `ScriptGenerator`'s post-condition (computenet-i3vo) is
     * exactly the statement that no emitted remove leaves its element live. So this measurement
     * is a **second, independent instrument on that post-condition**: it observes the step class
     * through the disagreement it causes between two references, where
     * `WavePrefixTest.kernelEffectiveModelInertRemoves` observes it as a predicate over the
     * script. Both must stay empty, and a regression that reintroduces the step class reddens
     * both — from opposite directions.
     *
     * **A red here is a generator regression, not a kernel one.** The response is to repair
     * `ScriptGenerator`, never to widen this assertion. (The *kernel*-side tripwire moved to
     * [`the naive fold agrees with the kernel where the real reference does not`].)
     *
     * ## Why the zeros are not vacuity
     *
     * Three separate guards, because "everything agrees" is the shape a broken comparison also
     * takes:
     *
     * - [naiveArrivalOrder] fails loudly if it substitutes nothing, so both columns are provably
     *   not the same reference.
     * - Test 3 below runs the *same* [probe] comparison over a hand-built script and gets a
     *   differing case, a green naive column and a red real one. The instrument discriminates;
     *   it is this population that no longer contains anything to discriminate.
     * - The real column is asserted to fail with nothing at all — not merely with no
     *   [RunOutcome.Mismatch]. A [RunOutcome.NonQuiescence], [RunOutcome.DeadLetterFailure],
     *   [RunOutcome.ModelEvaluationFailure] or [RunOutcome.WavePrefixViolation] would mean a
     *   broken probe config, and would be caught here rather than silently counted as agreement.
     */
    @Test
    fun `the naive fold and the real reference agree across the whole generated population`() {
        multiWriterConfigs().forEach { (name, config) ->
            val probe = probe(name, config)

            withClue("$name: the naive arrival-order fold must agree with the kernel on every seed") {
                probe.naiveFailures shouldBe emptyList()
            }
            withClue(
                "$name: no generated case may carry a remove whose coverage differs by writer — " +
                    "computenet-i3vo's post-condition, seen from the reference side. A seed here " +
                    "is the generator emitting the step class again; repair ScriptGenerator, not " +
                    "this list.",
            ) {
                probe.differingSeeds shouldBe emptyList()
            }
            withClue("$name: the catalog-resolved real reference must agree with the kernel on every seed") {
                probe.realFailures shouldBe emptyList()
            }
        }
    }

    /**
     * **The finding itself, and the non-vacuity witness for the test above**: on a
     * writer-concurrent script the naive arrival-order fold agrees with the kernel and the
     * spec-faithful reference does not.
     *
     * Three events, no generator and no seed — `WavePrefixTest`'s
     * `a remove of an element another writer added is applied by the kernel and ignored by the
     * model` (computenet-eeys), run here through [probe]'s own two-column comparison instead of
     * against a hand-written expectation:
     *
     * - the two references **differ** on this script, which no generated case can do any more;
     * - the naive fold is `Success` against the kernel;
     * - the real reference is a [RunOutcome.Mismatch].
     *
     * That is BS-12's equation with its two sides swapped, which is the whole finding this file
     * records: the wrong oracle BS-12 asked for is the one that matches this kernel. The
     * single-writer control below fixes that it is writer *concurrency* and not repetition — the
     * same control `WavePrefixTest` uses, kept here so a change that makes both cases agree
     * cannot be mistaken for this instrument going quiet.
     *
     * **This is the tripwire test 2 used to be.** It reddens the moment a `SetCell` remove
     * becomes writer-scoped and the naive fold becomes genuinely wrong — at which point BS-12 is
     * implementable and this file should be replaced by the real control rather than repaired.
     *
     * The diamond topology is reproduced rather than imported: `WavePrefixTest.diamondCase` is
     * `private` to a file this item does not claim, the same reason [baseConfig] reproduces
     * `OracleSweepTest.baselineConfig`'s shape.
     */
    @Test
    fun `the naive fold agrees with the kernel where the real reference does not`() {
        val w0 = WriterId("w0")
        val w1 = WriterId("w1")

        // --- the divergence, at its minimum: w0 adds `ab`, w1 adds `ab`, w0 removes `ab`.
        val diverging = handBuiltProbe(
            "writer-concurrent remove",
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Add(w1, "ab"),
            ScriptEvent.Remove(w0, "ab"),
        )

        withClue("the two references must actually differ here — this is what makes [probe] an instrument") {
            diverging.differingSeeds shouldBe listOf(HAND_BUILT_SEED)
        }
        withClue("the naive arrival-order fold is the one that agrees with this kernel") {
            diverging.naiveFailures shouldBe emptyList()
        }
        withClue("the spec-faithful reference keeps `ab` live — w0's remove covers only w0's own add") {
            diverging.realFailures.map { (seed, outcome) -> seed to outcome::class.simpleName } shouldContainExactly
                listOf(HAND_BUILT_SEED to "Mismatch")
        }

        // --- the control: the same shape under ONE writer. Both references agree and both are green,
        // --- so what test 2 measures is writer concurrency and not the mere repetition of an add.
        val singleWriter = handBuiltProbe(
            "single-writer control",
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Remove(w0, "ab"),
        )

        withClue("one writer: its remove covers both of its own adds, so the two references coincide") {
            singleWriter.differingSeeds shouldBe emptyList()
        }
        withClue("one writer: and both agree with the kernel") {
            singleWriter.naiveFailures shouldBe emptyList()
            singleWriter.realFailures shouldBe emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // The probe
    // -----------------------------------------------------------------------

    /** One config's measurement over [seeds]: which seeds differ, and each column's failures. */
    private data class Probe(
        val differingSeeds: List<Long>,
        val naiveFailures: List<Pair<Long, RunOutcome>>,
        val realFailures: List<Pair<Long, RunOutcome>>,
    )

    /**
     * Runs [config] over [seeds] twice per seed — once with the naive arrival-order fold
     * substituted for every set source, once with the catalog-resolved real reference passed
     * **explicitly**.
     *
     * Passing the real reference explicitly rather than letting [DifferentialRunner.run]
     * resolve it (`reference = null`) is deliberate: a substituted reference defaults
     * [WavePrefixOption] to `OFF` while a resolved one defaults it to `DEFAULT`, so leaving it
     * null would make wave-prefix checking the *second* difference between the columns and the
     * comparison would no longer be attributable to the reference alone.
     *
     * The differing-seed set is computed from the two models' terminal states directly, not
     * from the two outcomes: it states that the substitution changes the *expected* answer,
     * independently of what the kernel produced.
     */
    private fun probe(name: String, config: GeneratorConfig): Probe {
        val generator = CaseGenerator(config)
        return measure(name, seeds.map(generator::generate))
    }

    /**
     * [cases]' measurement — the two-column comparison itself, shared by the generated probes
     * and by the hand-built one so that both are literally the same instrument. A hand-built
     * control that re-implemented the comparison would not witness anything about the generated
     * measurement's non-vacuity.
     */
    private fun measure(name: String, cases: List<GeneratedCase>): Probe {
        val differing = mutableListOf<Long>()
        val naiveFailures = mutableListOf<Pair<Long, RunOutcome>>()
        val realFailures = mutableListOf<Pair<Long, RunOutcome>>()

        val startedAt = System.nanoTime()
        cases.forEach { case ->
            val seed = case.seed
            val real = CaseExecution.referenceModelFor(case.topology)
            val naive = naiveArrivalOrder(real, name)
            val script = case.script.toScript()

            if (naive.eval(script) != real.eval(script)) differing += seed

            val naiveOutcome = DifferentialRunner.run(case, reference = Reference(naive::eval))
            if (naiveOutcome != RunOutcome.Success) naiveFailures += seed to naiveOutcome

            val realOutcome = DifferentialRunner.run(case, reference = Reference(real::eval))
            if (realOutcome != RunOutcome.Success) realFailures += seed to realOutcome
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println(
            "[divergence-control] $name over ${cases.size} case(s): ${differing.size} differing seeds, " +
                "naive ${cases.size - naiveFailures.size}/${cases.size} Success, " +
                "real ${cases.size - realFailures.size}/${cases.size} Success " +
                "($elapsedMs ms) [ORA1-DIFF-09]",
        )
        return Probe(differing, naiveFailures, realFailures)
    }

    /**
     * The same [measure] comparison over one hand-built script on the BS-8 diamond — the shape
     * `WavePrefixTest` uses for computenet-eeys, reproduced because its `diamondCase` is
     * `private` to a file this item does not claim.
     *
     * `filter` + `flatMapSet` reconverging at a `union` is not decoration: two identical arms
     * carry identical element sets, so `add("ab")` contributing `{ab}` through one arm and
     * `{a, b}` through the other is what makes the terminal's state name the element's fate
     * unambiguously.
     */
    private fun handBuiltProbe(name: String, vararg events: ScriptEvent): Probe {
        fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel
        val topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), HAND_BUILT_SOURCE),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
                TopologyNode("exp", CoreOperators.Ids.FLAT_MAP_SET, listOf("src"), null),
                TopologyNode("u", CoreOperators.Ids.UNION, listOf("flt", "exp"), null),
            ),
            terminals = listOf(TerminalSpec("united", "u")),
            placement = mapOf("src" to 0, "flt" to 0, "exp" to 0, "u" to 0),
        )
        val case = GeneratedCase(
            seed = HAND_BUILT_SEED,
            topology = topology,
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
            script = CaseScript(events.map { CaseStep.Op(HAND_BUILT_SOURCE, it) }),
            removeAudit = emptyList(),
        )
        return measure(name, listOf(case))
    }

    /**
     * [model] with every [SetSourceModel] node replaced by [NaiveArrivalOrderSetModel], and
     * nothing else touched — downstream operator models are reused verbatim, so the only
     * difference between the two references is the source membership rule.
     *
     * Substituting on the already-resolved model rather than re-walking the topology is
     * equivalent to (and shorter than) mirroring [CaseExecution.referenceModelFor]'s mapping:
     * that mapping resolves catalog ids, and the id whose model this replaces is the same one
     * either way.
     *
     * Fails loudly if the topology contains no set source. A substitution that replaced
     * nothing would make both columns the same reference and every assertion here vacuously
     * true — the exact failure mode a control test exists to avoid.
     */
    private fun naiveArrivalOrder(model: ReferenceModel, name: String): ReferenceModel {
        var replaced = 0
        val nodes = model.nodes.map { node ->
            if (node is ModelNode.Source && node.model === SetSourceModel) {
                replaced++
                node.copy(model = NaiveArrivalOrderSetModel)
            } else {
                node
            }
        }
        check(replaced > 0) {
            "$name generated a case with no `set` source node, so substituting the naive " +
                "arrival-order fold replaced nothing and both reference columns would be the " +
                "same model. A control that substitutes nothing measures nothing."
        }
        return model.copy(nodes = nodes)
    }

    private companion object {
        /**
         * The single source the hand-built cases drive. Named, not generated: `SourceId` is what
         * [CaseStep.Op] routes on, and the diamond has exactly one source by construction (a
         * scope choice of these cases, not a gate: since computenet-2hur
         * `WavePrefixOracle.appliesTo` admits multi-source too — and nothing here prefix-checks
         * anyway).
         */
        val HAND_BUILT_SOURCE: SourceId = SourceId("s")

        /**
         * The seed label a hand-built case carries. It is not drawn from [seeds] and no case is
         * generated from it — [GeneratedCase.seed] is only an identifier here, reported back in
         * [Probe]'s lists — but it is deliberately OUTSIDE `0..59` so a reader cannot mistake a
         * hand-built row in the output for a generated one.
         */
        const val HAND_BUILT_SEED: Long = -1L
    }
}

/**
 * The wrong oracle BS-12 names: a **tag-blind arrival-order fold**. An
 * [ScriptEvent.Add] inserts, an [ScriptEvent.Remove] deletes by value, an
 * [ScriptEvent.Observe] is ignored entirely — no observed-remove coverage, no causality, no
 * writer identity.
 *
 * It is a *plausible* wrong implementation of `[24-SET-01]`/`[24-SET-03]`, which is what makes
 * it the control BS-12 asked for: somebody implementing an OR-set from the observable API
 * alone, without reading the spec's coverage rule, writes exactly this.
 *
 * That it turns out to agree with the current kernel on every probed seed is the finding
 * [DivergenceControlTest] records; see that class's KDoc.
 *
 * **Test scope only** — like everything in [MutantModels], this must never be registered into
 * the shipped catalog. [DivergenceControlTest] substitutes it into a per-case
 * [ReferenceModel] and never touches [OperatorCatalog], so no registration can leak into a
 * later test in the same JVM.
 */
object NaiveArrivalOrderSetModel : SourceModel, Serializable {

    override fun evaluate(slice: SourceScript): ModelState {
        val live = LinkedHashSet<Any?>()
        slice.events.forEach { event ->
            when (event) {
                is ScriptEvent.Add -> live += event.element
                is ScriptEvent.Remove -> live -= event.element
                else -> Unit
            }
        }
        return ModelState.SetState(live)
    }

    override fun toString(): String = "NaiveArrivalOrderSetModel"
}
