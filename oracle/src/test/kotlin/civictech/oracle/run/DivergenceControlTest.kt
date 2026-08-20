package civictech.oracle.run

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ReferenceModel
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
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
 * 1. [`a single writer makes the naive fold indistinguishable from the real reference`] —
 *    across the single-writer configs, zero seeds differ between the two references and both
 *    columns are green. This is why the feature design's "raise the unobserved-remove ratio"
 *    escalation is spent: at one writer, `Membership`'s coverage condition (`add.writer == by`)
 *    holds for every prior add, so the causal fold provably collapses to the value fold and
 *    no knob can create a difference. (computenet-qcm1 makes it doubly spent — a generated
 *    unobserved remove never names a live element.)
 * 2. [`the naive fold agrees with the kernel on exactly the seeds the real reference fails`] —
 *    across the multi-writer configs, the naive fold is `Success` on all sixty seeds while the
 *    real reference mismatches on some, and the **mismatching seed set equals the differing
 *    seed set**. That is BS-12's equation from the other side: the naive fold agrees with the
 *    kernel on exactly the seeds the real model does not.
 *
 * **Test 2 is a tripwire.** It goes RED the moment a `SetCell` remove becomes writer-scoped —
 * i.e. the moment the naive fold becomes genuinely wrong and BS-12 becomes implementable.
 * When that happens, do not repair the assertion: implement BS-12 proper as the follow-up the
 * decision names.
 *
 * ## Limits of the numbers below
 *
 * Everything asserted here is a measurement over **eight probed configs x the fixed range
 * `0..59`** on this generator. It is evidence that no *reachable* config satisfies BS-12, not
 * a proof over the whole config space; the proof-shaped half of the argument is the
 * structural one about `SetCell.inletHandler.remove` above. Divergence **counts** (42/60,
 * 34/60, 43/60, 32/60 as measured 2026-08-20 on Darwin arm64) are deliberately *not* pinned —
 * only their qualitative shape is — because they move with any generator change; see
 * [multiWriterConfigs].
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
     * Their measured differing-seed counts on 2026-08-20 (Darwin arm64) were 42, 34, 43 and 32
     * of 60 respectively, each equal to that config's real-reference `Mismatch` count. Those
     * figures are recorded here and **not asserted**: they are a property of this generator's
     * draw, and pinning them would make an unrelated generator change look like a kernel
     * regression. What is asserted is the shape that carries the finding — nonzero, and equal
     * to the real reference's mismatch set.
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
     * Under **multiple writers** the two references do differ — and the naive fold is the one
     * that agrees with the kernel. On every multi-writer probe the naive column is green on all
     * sixty seeds while the real reference mismatches, and the mismatching seeds are *exactly*
     * the seeds on which the two references disagree.
     *
     * Four assertions, each load-bearing:
     *
     * - **naive green on all 60** — the wrong oracle BS-12 asked for cannot fail here at all.
     * - **at least one differing seed** — the substitution is provably not vacuous. An ignored
     *   reference could not produce two different verdicts from one case.
     * - **every real-reference failure is [RunOutcome.Mismatch] specifically** — a
     *   [RunOutcome.NonQuiescence], [RunOutcome.DeadLetterFailure],
     *   [RunOutcome.ModelEvaluationFailure] or [RunOutcome.WavePrefixViolation] would mean a
     *   broken probe config rather than a model disagreement, and must not be counted as one.
     * - **the mismatching seed set equals the differing seed set** — the disagreement is
     *   attributable to the reference and to nothing else.
     *
     * **This test is the tripwire for computenet-eeys.** It reddens as soon as a `SetCell`
     * remove becomes writer-scoped — at which point the naive fold stops agreeing with the
     * kernel, BS-12 becomes implementable, and this measurement should be replaced by the real
     * control rather than repaired.
     */
    @Test
    fun `the naive fold agrees with the kernel on exactly the seeds the real reference fails`() {
        multiWriterConfigs().forEach { (name, config) ->
            val probe = probe(name, config)

            withClue("$name: the naive arrival-order fold must agree with the kernel on every seed") {
                probe.naiveFailures shouldBe emptyList()
            }
            withClue("$name: the substituted reference must actually reach the comparison") {
                probe.differingSeeds.size shouldBeGreaterThan 0
            }
            withClue("$name: every real-reference failure must be a Mismatch, not an earlier kind") {
                probe.realFailures.map { (seed, outcome) -> seed to outcome::class.simpleName }
                    .filterNot { (_, kind) -> kind == "Mismatch" } shouldBe emptyList()
            }
            withClue("$name: the real reference fails on exactly the seeds the two references differ on") {
                probe.realFailures.map { it.first } shouldContainExactly probe.differingSeeds
            }
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
        val differing = mutableListOf<Long>()
        val naiveFailures = mutableListOf<Pair<Long, RunOutcome>>()
        val realFailures = mutableListOf<Pair<Long, RunOutcome>>()

        val startedAt = System.nanoTime()
        seeds.forEach { seed ->
            val case = generator.generate(seed)
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
            "[divergence-control] $name over $seeds: ${differing.size} differing seeds, " +
                "naive ${seeds.count() - naiveFailures.size}/${seeds.count()} Success, " +
                "real ${seeds.count() - realFailures.size}/${seeds.count()} Success " +
                "($elapsedMs ms) [ORA1-DIFF-09]",
        )
        return Probe(differing, naiveFailures, realFailures)
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
