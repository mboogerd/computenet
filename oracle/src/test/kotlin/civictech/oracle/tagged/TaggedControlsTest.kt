package civictech.oracle.tagged

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.Delivery
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.ConvergenceCheck
import civictech.oracle.run.MeshObservation
import civictech.oracle.run.NaiveArrivalOrderMapModel
import civictech.oracle.run.RemoveAllDotModel
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The four BLOCKING discrimination controls feature computenet-4ru.1's §4.9 names —
 * `[ORA2-CTL-01]`..`[ORA2-CTL-04]` — plus the honesty prose's own load-bearing claim: **"a
 * green sweep without these is not evidence"**. Mirrors ORA1's idiom
 * ([civictech.oracle.run.DivergenceControlTest], [civictech.oracle.run.MutationCheckTest]):
 * every test here exists to go RED for a specific, named reason, and each is read alongside the
 * mutant it substitutes ([civictech.oracle.run.NaiveArrivalOrderMapModel],
 * [civictech.oracle.run.RemoveAllDotModel] in `MutantModels.kt`, and an inverted
 * [DotOrder]/uncoordinated mesh built by hand here).
 *
 * ## What these controls drive, and what none of them drives
 *
 * `civictech.oracle.bind.TaggedOperators`'s file KDoc explains why `OrMapCell`'s catalog
 * registration (`SingleInstanceOrMapModel`) is restricted to a single, delivery-free slice: the
 * cross-instance case [DotModel]'s merge exists to check needs the WHOLE multi-instance
 * [Script], which `OperatorCatalog`'s per-node evaluation shapes cannot supply. So there is no
 * `DifferentialRunner` path for a replicated OR-map case at all — the feature's own design
 * assigns the replicated differential to "driving `DotModel.converged`/`stateOf` directly".
 *
 * - CTL-01 and CTL-03 do exactly that: **model-vs-model**, no runner in the loop.
 * - CTL-02 and CTL-04 **both** drive the real, unmocked [ConvergenceCheck] — the same seam,
 *   entered the same way, differing only in where the mutation sits. [ConvergenceCheck] is built
 *   to take a hand-supplied [MeshObservation], which is why a synthesised mesh is a legitimate
 *   input to it and not a mock.
 *
 * What **none** of the four does is observe state a kernel replica produced: CTL-02's and
 * CTL-04's folds are [DotModel]'s too. That still holds, but no longer for want of a runner path:
 * computenet-6v7y wired `CaseExecution` to resolve an `OR_MAP` script source and to fold a tagged
 * terminal through `TaggedMapTerminalFold`, so a *single-instance* generated OR-map case now does
 * reach the runner. None of these four has been written onto that path, and the replicated mesh
 * CTL-02 and CTL-04 build by hand still has no path at all (previous paragraph).
 * Kernel-driven OR-map coverage lives one file over, in
 * [civictech.oracle.tagged.ConvergenceCheckTest] — see the next section.
 *
 * ## Why CTL-02/03 build meshes by hand rather than from the generator
 *
 * [civictech.oracle.tagged.ConvergenceCheckTest] already proved CTL-02's shape once (its BS-7:
 * an inverted order applied to a real kernel-driven mesh yields [RunOutcome.ReplicasAgreeButWrong]
 * with the key named). This suite does not repeat that kernel drive — `ConvergenceCheckTest.kt`
 * is owned by the sibling task that landed it, and duplicating a live `SimWorld` mesh here would
 * be exactly the "second sweep loop" the feature design forbids. What is missing without a test
 * *here* is CTL-01's arrival-order control and CTL-03's remove-all control, for which no
 * *replicated* kernel seam exists (previous paragraph) — those two, plus a CTL-02/CTL-04
 * instance scoped to this task's own bead, are what this file adds. Since computenet-6v7y a
 * single-instance generated OR-map case could carry a substituted reference through
 * `DifferentialRunner`, which is a seam these two did not have when they were written; neither
 * has been rewritten onto it.
 *
 * **That single-instance seam serves only ONE of the two, and which one is measured, not
 * assumed.** With a single instance there are no deliveries and no concurrency for the dot order
 * to resolve, so a key's winner is simply its last write — which is exactly what
 * [NaiveArrivalOrderMapModel] computes. Over a delivery-free one-source script
 * (`put/put/re-put/removeKey/put/removeKey/put`) its fold is **equal** to
 * [civictech.oracle.bind.SingleInstanceOrMapModel]'s dot fold, both `{k1=vZ, k2=v3}` (measured
 * 2026-08-21, reviewing computenet-6v7y). CTL-01 ported onto that path would therefore hold
 * identically under the mutant and under the correct reference — the vacuous shape this file's
 * own CTL-01 KDoc records as the defect a prior review caught — so CTL-01 still has no seam it
 * could usefully move to. [RemoveAllDotModel] does discriminate there (a put *after* a remove
 * lives under the real reset-remove and stays wiped under the mutant: `{}` against
 * `{k1=vZ, k2=v3}` on the same script), so CTL-03 is the one this seam could carry.
 */
class TaggedControlsTest {

    @BeforeEach
    fun registerCatalog() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        TaggedOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    // =====================================================================
    // [ORA2-CTL-01] / BS-13 — the arrival-order fold must fail
    // =====================================================================

    /**
     * `[ORA2-CTL-01]`/BS-13: over [GeneratorConfig.REPLICATED_SWEEP_SEEDS] (the fixed, checked-in
     * range the replicated generator dimension is measured against —
     * `civictech.oracle.tagged.MultiWriterGenerationTest` uses the same range), at least one
     * replica's [NaiveArrivalOrderMapModel] fold must disagree with **that same replica's**
     * [DotModel] fold. If it never did, the arrival-order fold would be observationally
     * equivalent to the dot-order one and the control would prove nothing — precisely the
     * failure mode [civictech.oracle.run.DivergenceControlTest] found for ORA1's `SetCell` case.
     * The OR-map's concurrent-put resolution is a genuinely different mechanism from arrival
     * order (dot `(counter, sourceId)` versus "last write wins"), so unlike that ORA1 case this
     * control is expected to fire.
     *
     * **The comparison is per-replica on BOTH sides, and that is load-bearing.** As first
     * landed this test compared each replica's naive fold against [DotModel]'s *converged*
     * state, which is vacuous: these seeds do not fully gossip, so a replica's own — perfectly
     * CORRECT — dot fold differs from the converged state on all 40 seeds too (measured
     * 2026-08-21 while reviewing computenet-4ru.1.5). The assertion therefore held identically
     * under the correct implementation and under the mutant, pinning nothing while reading as
     * evidence. Like-for-like, the mutant is discriminated on 28 of the 40 seeds, and
     * substituting the correct per-replica fold for [NaiveArrivalOrderMapModel] now reddens
     * this test — which is the property that makes it a control at all.
     */
    @Test
    fun `CTL-01 an untagged arrival-order fold disagrees with the tagged dot-order reference on at least one seed`() {
        val config = GeneratorConfig.replicatedSweep()
        val generator = CaseGenerator(config)
        val differingSeeds = mutableListOf<Long>()

        GeneratorConfig.REPLICATED_SWEEP_SEEDS.forEach { seed ->
            val case = generator.generate(seed)
            val audit = case.replication ?: error("seed $seed produced no replication plan for a replicated config")
            val script = case.script.toScript()
            // `replicatedSweep()`'s vocabulary also names a SECOND, unreplicated `orMap` source
            // (the `join` arm) — every source the script actually drives needs a rank, not only
            // the replicated handle's. The unreplicated source is never concurrent with anything
            // (one instance, no peer to tie against), so its position among the rest is immaterial.
            val order = DotOrder.ranked(audit.plan.replicas + (script.sources() - audit.plan.replicas.toSet()))

            val real = DotModel(order)
            // The correct reference, per replica — NOT the converged state. See the KDoc: a
            // replica's own correct fold already differs from the converged one here, so a
            // converged comparison cannot tell the mutant from the real thing.
            val reference = real.perInstance(script).mapValues { real.entries(it.value).entries }
            val naive = NaiveArrivalOrderMapModel.perInstance(script) // every replica's untagged fold

            val disagreeing = naive.filter { (source, fold) -> reference.getValue(source) != fold }
            if (disagreeing.isNotEmpty()) differingSeeds += seed
        }

        withClue(
            "[ORA2-CTL-01]/BS-13: over ${GeneratorConfig.REPLICATED_SWEEP_SEEDS}, the untagged " +
                "arrival-order fold must disagree with the SAME replica's dot-order fold on at " +
                "least one seed. Zero differing seeds means this control cannot fail and is not evidence " +
                "(differing seeds observed: $differingSeeds).",
        ) {
            differingSeeds shouldNotBe emptyList<Long>()
        }
    }

    // =====================================================================
    // [ORA2-CTL-02] — an inverted dot order must be detected and attributed
    // =====================================================================

    /**
     * `[ORA2-CTL-02]`: a mesh that genuinely converged under the KERNEL's dot order (simulated
     * here by feeding [ConvergenceCheck] every replica's fold from the CORRECT [DotModel], all
     * agreeing) must be reported wrong when [ConvergenceCheck] itself is constructed with an
     * inverted order — and the report must name the right key. This is
     * [civictech.oracle.tagged.ConvergenceCheckTest]'s BS-7 shape, run here as this bead's own
     * named control rather than assumed from a sibling file.
     */
    @Test
    fun `CTL-02 an inverted dot order is detected and attributed to the right key`() {
        val sources = listOf(SourceId("r0"), SourceId("r1"), SourceId("r2"))
        val correctOrder = DotOrder.ranked(sources)
        // Three genuinely concurrent puts to one key — no writer observes another's write.
        val script = Script(
            sources.mapIndexed { i, source ->
                SourceScript(source, listOf(ScriptEvent.Put(WriterId("w$i"), "k", "v$i")))
            },
        )

        val referenceState = DotModel(correctOrder).converged(script)
        val agreedEntries = DotModel(correctOrder).entries(referenceState)
        withClue("the setup must actually be concurrent — three live dots at one key") {
            referenceState.liveDots("k").keys shouldHaveSize 3
        }

        // [ORA2-CTL-02]'s substitution: the kernel's own order, inverted. Applied uniformly, so
        // a mesh that really did converge under the CORRECT order still agrees with itself here
        // — only the reference the check computes moves.
        val invertedOrder = DotOrder.ranked(sources.reversed())
        val mesh = MeshObservation(
            logicalId = UUID(0xC0FFEEL, 2L),
            folds = sources.associateWith { agreedEntries },
            agreed = true,
        )

        val outcome = ConvergenceCheck(invertedOrder)
            .check(seed = 2L, caseMarker = "CTL-02", script = script, mesh = mesh)

        val wrong = outcome.shouldBeInstanceOf<RunOutcome.ReplicasAgreeButWrong>()
        withClue("outcome=$outcome") {
            (outcome is RunOutcome.ReplicaDivergence) shouldBe false
            (outcome is RunOutcome.Mismatch) shouldBe false
        }
        wrong.keys shouldHaveSize 1
        wrong.keys[0].key shouldBe "k"
        // attributed to the right terminal/case: the caseMarker and logicalId this call named.
        wrong.caseMarker shouldBe "CTL-02"
        wrong.logicalId shouldBe mesh.logicalId.toString()
        val invertedWinner = sources.maxBy { invertedOrder.rankOf(it) }
        wrong.keys[0].winningDot.shouldNotBeNull().source shouldBe invertedWinner
        wrong.expected shouldNotBe agreedEntries
    }

    @Test
    fun `CTL-02's control - the same mesh under the correct order is Success, so the verdict is the order and not the mesh`() {
        val sources = listOf(SourceId("r0"), SourceId("r1"), SourceId("r2"))
        val correctOrder = DotOrder.ranked(sources)
        val script = Script(
            sources.mapIndexed { i, source ->
                SourceScript(source, listOf(ScriptEvent.Put(WriterId("w$i"), "k", "v$i")))
            },
        )
        val agreedEntries = DotModel(correctOrder).evaluate(script)
        val mesh = MeshObservation(
            logicalId = UUID(0xC0FFEEL, 2L),
            folds = sources.associateWith { agreedEntries },
            agreed = true,
        )
        ConvergenceCheck(correctOrder).check(2L, "CTL-02 control", script, mesh) shouldBe RunOutcome.Success
    }

    // =====================================================================
    // [ORA2-CTL-03] / BS-4 — remove-all must be detected, naming the key
    // =====================================================================

    /**
     * `[ORA2-CTL-03]`/BS-4: BS-3's own setup — writer A puts `k`, writer B removes `k` having
     * observed only A's first dot, and A concurrently puts a second, unobserved-by-B dot — with
     * [civictech.oracle.model.DotState.resetRemove] replaced by [RemoveAllDotModel]'s
     * remove-all. The real reference (add-wins) keeps `k = v2`; the mutant wipes `k` outright the
     * moment B's remove runs, so the two disagree, and the disagreement names key `k`.
     */
    @Test
    fun `CTL-03 a remove-all mutant is detected and names the key`() {
        val writerA = SourceId("a")
        val writerB = SourceId("b")
        val order = DotOrder.ranked(listOf(writerA, writerB))

        // A: put v1, then (concurrently with B's remove) put v2 — B never observes the second put.
        val sliceA = SourceScript(
            writerA,
            events = listOf(
                ScriptEvent.Put(WriterId("wa"), "k", "v1"),
                ScriptEvent.Put(WriterId("wa"), "k", "v2"),
            ),
        )
        // B: absorbs A's first event only, then removes k.
        val sliceB = SourceScript(
            writerB,
            events = listOf(ScriptEvent.RemoveKey(WriterId("wb"), "k")),
            deliveries = listOf(Delivery(afterEvents = 0, from = writerA, throughEvents = 1)),
        )
        val script = Script(listOf(sliceA, sliceB))

        val real = DotModel(order)
        val realState = real.converged(script)
        withClue("BS-3's own precondition: add-wins keeps v2 live at k under the real model") {
            real.value(realState, "k") shouldBe "v2"
        }

        val mutant = RemoveAllDotModel(order)
        val mutantState = mutant.converged(script)
        val mutantValue = mutant.value(mutantState, "k")

        withClue(
            "[ORA2-CTL-03]/BS-4: the remove-all mutant must disagree with the real reference at " +
                "key 'k' — real=${real.value(realState, "k")} mutant=$mutantValue. Agreement here " +
                "would mean the add-wins boundary is never exercised by this control. 'k' is the " +
                "only key this script names, so the disagreement is unambiguously attributable to it.",
        ) {
            mutantValue shouldNotBe real.value(realState, "k")
            mutantValue.shouldBeNull() // remove-all: the key is wiped outright, not just reset
        }
    }

    // =====================================================================
    // [ORA2-CTL-04] / BS-8 — missing gossip must be reported as divergence
    // =====================================================================

    /**
     * `[ORA2-CTL-04]`/BS-8: two replicas each put a different value at the same key, and neither
     * ever absorbs the other's write — the model-level statement of "one replica's outbound
     * gossip is not applied at the other peer". [ConvergenceCheck] must report
     * [RunOutcome.ReplicaDivergence], naming both replicas and the differing key, rather than
     * reporting success or silently picking one replica's answer.
     *
     * [ConvergenceCheck]'s own KDoc states the mesh it is fed is "deliberately a plain value
     * rather than a live handle" precisely so a hand-built, gossip-withheld mesh like this one is
     * a legitimate input, not a special case.
     */
    @Test
    fun `CTL-04 withheld gossip is reported as replica divergence, naming both replicas and the key`() {
        val r0 = SourceId("r0")
        val r1 = SourceId("r1")
        val order = DotOrder.ranked(listOf(r0, r1))
        // No deliveries between r0 and r1 at all: r1's gossip never reaches r0, and vice versa.
        val script = Script(
            listOf(
                SourceScript(r0, listOf(ScriptEvent.Put(WriterId("w0"), "k", "v0"))),
                SourceScript(r1, listOf(ScriptEvent.Put(WriterId("w1"), "k", "v1"))),
            ),
        )

        // Each replica's own fold sees only what it itself wrote — exactly what a replica whose
        // peer's gossip never arrived would report through its own delta outlet.
        val foldR0 = DotModel(order).evaluate(Script(listOf(script.slice(r0))))
        val foldR1 = DotModel(order).evaluate(Script(listOf(script.slice(r1))))
        withClue("the two replica folds must actually differ, or this control proves nothing") {
            foldR0 shouldNotBe foldR1
        }

        val mesh = MeshObservation(
            logicalId = UUID(0xC0FFEEL, 4L),
            folds = mapOf(r0 to foldR0, r1 to foldR1),
            agreed = false, // the kernel invariant would also see two different states here
        )

        val outcome = ConvergenceCheck(order).check(seed = 4L, caseMarker = "CTL-04", script = script, mesh = mesh)

        val divergence = outcome.shouldBeInstanceOf<RunOutcome.ReplicaDivergence>()
        withClue("outcome=$outcome") {
            (outcome is RunOutcome.ReplicasAgreeButWrong) shouldBe false
            (outcome is RunOutcome.Mismatch) shouldBe false
        }
        divergence.perReplica.keys shouldBe setOf("r0", "r1")
        divergence.perReplica["r0"] shouldBe foldR0
        divergence.perReplica["r1"] shouldBe foldR1
        divergence.keys shouldHaveSize 1
        divergence.keys[0].key shouldBe "k"
    }
}
