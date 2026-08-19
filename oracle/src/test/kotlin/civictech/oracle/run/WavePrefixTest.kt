package civictech.oracle.run

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
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
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BS-8 / `[ORA1-DIFF-06]` — the wave-prefix glitch-freedom oracle, epic design **D5**: while a
 * case is driven, every intermediate observation of every terminal equals the reference model's
 * result for SOME prefix of the wave sequence, and the matched prefix index never regresses per
 * terminal.
 *
 * ## The behavioral cross-check against `InternalConsistencyTest`
 *
 * `kernel/src/test/kotlin/civictech/cell/consistency/InternalConsistencyTest.kt` holds the
 * construction this suite generalizes (its `Oracle`, `prefixesOf`, and
 * `every observed composite is a completed-transfer prefix, for every seed`). **Direct code
 * reuse is impossible** — that `Oracle` is `private`, lives in the *kernel* test source set, and
 * is written over a domain-specific `Transfer`/`Leg` vocabulary — so the correspondence is
 * behavioral, case by case:
 *
 * | `InternalConsistencyTest` | here |
 * | --- | --- |
 * | `prefixesOf(transfers)`: recompute folded forward one wave at a time | [WavePrefixOracle.prefixesOf]: the reference evaluated on each Op-prefix of the `CaseScript` |
 * | `composite shouldBe prefixes[prefix]`, matched index monotone | [WavePrefixOracle.Checker]'s floor-bounded search |
 * | seed-randomized partial drains between waves (`repeat(rnd.nextInt(4))`) | [DifferentialRunner.Driving.partialDrain], the same idiom |
 * | `control - the ungated inner join publishes composites matching no transfer prefix` | ["a torn observation - one arm of the diamond caught up, the other not - matches no prefix"] |
 *
 * The negative half is what makes both suites instruments rather than assertions: a check that
 * accepts a torn state accepts everything.
 *
 * ## Two deviations from the bead's Implement prose, both deliberate
 *
 * 1. **The diamond's two arms are `filter` + `flatMapSet`, not two `filter`s.** Two identical
 *    arms cannot discriminate: they carry identical element sets, so a per-arm publish and a
 *    wave-complete publish produce the same VALUE at the union and no observation can separate
 *    them — the shape would test nothing. With `filter` + `flatMapSet`, `add("ab")` contributes
 *    `{ab}` through one arm and `{a, b}` through the other, so a half-published wave is a state
 *    that is no prefix at all, which is exactly what the torn control uses.
 * 2. **The generated sweep is single-source.** Not a narrowing to dodge failures: it is
 *    [WavePrefixOracle.appliesTo]'s documented soundness domain (a total-order prefix denotes a
 *    real frontier only for one source; multi-source needs per-source frontiers,
 *    computenet-2hur). The sweep keeps the *ordinary* writer and unobserved-remove knobs — see
 *    [generatedSweepConfig] and the sweep test's own KDoc for how the known cross-writer seam is
 *    partitioned out by MEASUREMENT rather than by configuration.
 */
class WavePrefixTest {

    private val writer = WriterId("w")
    private val source = SourceId("s")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    // ------------------------------------------------------------ the BS-8 diamond

    /**
     * The BS-8 shape: one `set` source fanning out into a `filter` arm and a `flatMapSet` arm,
     * reconverging at a `union`, observed at one terminal.
     *
     * ```
     *            ┌── flt (filter: text length even) ──┐
     *   src (set)─┤                                    ├── u (union) ── "united"
     *            └── exp (flatMapSet: characters) ────┘
     * ```
     *
     * `intersect` is deliberately NOT the fan-in here even though computenet-vvre (`8d840f26`)
     * and computenet-88hv (`d40e66f8`) landed its tag-minting fix in this branch's base: `union`
     * keeps both arms' contributions visible in the terminal's value, which is what makes a
     * half-published wave a distinguishable state. An `intersect` would hide one arm's
     * contribution behind the other's.
     */
    private fun diamondCase(script: CaseScript, seed: Long = 8L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), source),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
                TopologyNode("exp", CoreOperators.Ids.FLAT_MAP_SET, listOf("src"), null),
                TopologyNode("u", CoreOperators.Ids.UNION, listOf("flt", "exp"), null),
            ),
            terminals = listOf(TerminalSpec("united", "u")),
            placement = mapOf("src" to 0, "flt" to 0, "exp" to 0, "u" to 0),
        ),
        spec = spec(
            SpawnStep("src", factory(CoreOperators.Ids.SET)),
            SpawnStep("flt", factory(CoreOperators.Ids.FILTER)),
            SpawnStep("exp", factory(CoreOperators.Ids.FLAT_MAP_SET)),
            SpawnStep("u", factory(CoreOperators.Ids.UNION)),
            ConnectStep("src", "outlet", "flt", "inlet"),
            ConnectStep("src", "outlet", "exp", "inlet"),
            ConnectStep("flt", "outlet", "u", "inlet"),
            ConnectStep("exp", "outlet", "u", "inlet"),
        ),
        script = script,
        removeAudit = emptyList(),
    )

    /**
     * Four waves over the diamond, chosen so consecutive prefixes differ in ways the two arms
     * disagree about, and so one wave is a genuine RETRACTION:
     *
     * | prefix | script so far | `united` |
     * | --- | --- | --- |
     * | 0 | — | `{}` |
     * | 1 | `add(ab)` | `{ab, a, b}` |
     * | 2 | `add(abc)` | `{ab, a, b, c}` (odd length: no filter contribution) |
     * | 3 | `add(cd)` | `{ab, cd, a, b, c, d}` |
     * | 4 | `remove(cd)` | `{ab, a, b, c}` |
     *
     * Prefix 4 is value-equal to prefix 2, on purpose: a legal run whose terminal returns to an
     * earlier VALUE must still be admitted, because the later index is also a match. A checker
     * that compared values without an index would be indistinguishable from one that did on this
     * script if every prefix were distinct.
     */
    private fun diamondScript() = CaseScript(
        listOf(
            CaseStep.Op(source, ScriptEvent.Add(writer, "ab")),
            CaseStep.Op(source, ScriptEvent.Add(writer, "abc")),
            CaseStep.Op(source, ScriptEvent.Add(writer, "cd")),
            CaseStep.Op(source, ScriptEvent.Remove(writer, "cd")),
        ),
    )

    /** The catalog-resolved reference for [diamondCase] — the same one [DifferentialRunner.run] uses. */
    private fun diamondReference(case: GeneratedCase): Reference =
        CaseExecution.referenceModelFor(case.topology).let { model -> Reference(model::eval) }

    @Test
    fun `every intermediate observation matches a wave prefix`() {
        val case = diamondCase(diamondScript())
        var checker: WavePrefixOracle.Checker? = null
        var settled: Map<String, ModelState>? = null

        val outcome = DifferentialRunner.run(
            case = case,
            wavePrefix = WavePrefixOption.ALWAYS,
            onWavePrefixChecker = { checker = it },
        )

        outcome shouldBe RunOutcome.Success

        // --- non-vacuity, four ways. Success above is worthless without them: a run that took
        // --- no observation, or whose prefix list had one entry, would report Success too.
        val live = checker.shouldNotBeNull()
        withClue("the case must be eligible and selected, so the check actually ran") {
            WavePrefixOracle.appliesTo(case) shouldBe true
        }
        withClue("one prefix per Op plus the empty-input prefix") {
            live.prefixes.size shouldBe 5
        }
        withClue("observations are taken per productive scheduler step; four waves cannot be zero") {
            live.observations shouldBeGreaterThanOrEqualTo 4
        }
        withClue("the terminal must have reached the LAST prefix, not stalled on an early one") {
            live.floorOf("united") shouldBe live.prefixes.lastIndex
        }

        // --- and the shape really is discriminating: the two arms contribute different elements,
        // --- so a half-published wave at the union is a state no prefix contains.
        val last = live.prefixes.last().getValue("united").shouldBeInstanceOf<ModelState.SetState>()
        withClue("the filter arm's contribution") { last.elements.contains("ab") shouldBe true }
        withClue("the expansion arm's contribution") { last.elements.contains("a") shouldBe true }

        // --- and the terminal genuinely holds it (read at a trailing Barrier, the only live read
        // --- the run API exposes).
        DifferentialRunner.run(
            case = diamondCase(CaseScript(diamondScript().steps + CaseStep.Barrier)),
            wavePrefix = WavePrefixOption.ALWAYS,
        ) { settled = it } shouldBe RunOutcome.Success
        settled shouldBe mapOf("united" to ModelState.SetState(setOf("ab", "a", "b", "c")))
    }

    // ------------------------------------------------------- discrimination controls

    /** [diamondScript]'s prefix list, for feeding the checker fabricated observation streams. */
    private fun diamondPrefixes(): Pair<WavePrefixOracle.Checker, List<ModelState>> {
        val case = diamondCase(diamondScript())
        val checker = WavePrefixOracle.checker(case, "marker", diamondReference(case))
        return checker to checker.prefixes.map { it.getValue("united") }
    }

    @Test
    fun `a legal observation stream is accepted`() {
        val (checker, prefixes) = diamondPrefixes()

        // p0, p1, p1 (a plateau — two partial drains inside one wave), p2, p3, p4.
        listOf(0, 1, 1, 2, 3, 4).forEach { index ->
            withClue("prefix $index must be admissible") {
                checker.observeTerminal("united", prefixes[index]).shouldBeNull()
            }
        }
        checker.floorOf("united") shouldBe 4
    }

    @Test
    fun `a torn observation - one arm of the diamond caught up, the other not - matches no prefix`() {
        val (checker, prefixes) = diamondPrefixes()

        // Mirrors InternalConsistencyTest's ungated-join control: a composite in which one arm
        // has applied the wave and the other has not. Here the filter arm has taken `cd` while
        // the expansion arm has not yet taken `d` — a state strictly between prefix 2 and 3.
        val torn = ModelState.SetState(setOf("ab", "cd", "a", "b", "c"))
        withClue("the torn state must not be a prefix by accident, or this control proves nothing") {
            prefixes.contains(torn) shouldBe false
        }

        checker.observeTerminal("united", prefixes[2]).shouldBeNull()
        val violation = checker.observeTerminal("united", torn).shouldNotBeNull()

        violation.kind shouldBe RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
        violation.terminal shouldBe "united"
        violation.observed shouldBe torn
        violation.regressedTo.shouldBeNull()
        withClue("the report must name the prefixes the torn state sits between") {
            violation.matchedFloor shouldBe 2
            violation.nearestPrefixes.keys.toList() shouldBe listOf(2, 3)
        }
    }

    @Test
    fun `a regressing matched-index sequence is rejected`() {
        val (checker, prefixes) = diamondPrefixes()

        // Every state here is individually a legal prefix; the sequence is what is illegal.
        // Prefix 2's value occurs only at index 2 and 4 — feeding it after the floor has reached
        // 3 is admissible via index 4, so the regression control uses prefix 1's value, which
        // occurs at index 1 alone.
        withClue("prefix 1's value must be unique to index 1, or this control is vacuous") {
            prefixes.indices.filter { prefixes[it] == prefixes[1] } shouldBe listOf(1)
        }

        checker.observeTerminal("united", prefixes[3]).shouldBeNull()
        val violation = checker.observeTerminal("united", prefixes[1]).shouldNotBeNull()

        violation.kind shouldBe RunOutcome.WavePrefixViolation.Kind.REGRESSED
        violation.matchedFloor shouldBe 3
        violation.regressedTo shouldBe 1
    }

    @Test
    fun `each terminal has its own floor, so one arm being ahead of another is not a regression`() {
        // Two terminals of one case advance independently; a shared floor would reject a legal run
        // in which one arm of the graph is simply ahead of the other.
        val case = diamondCase(diamondScript()).let { base ->
            base.copy(
                topology = base.topology.copy(
                    terminals = base.topology.terminals + TerminalSpec("filtered", "flt"),
                ),
            )
        }
        val checker = WavePrefixOracle.checker(case, "marker", diamondReference(case))

        checker.observeTerminal("united", checker.prefixes[3].getValue("united")).shouldBeNull()
        checker.observeTerminal("filtered", checker.prefixes[1].getValue("filtered")).shouldBeNull()

        checker.floorOf("united") shouldBe 3
        checker.floorOf("filtered") shouldBe 1
    }

    // ------------------------------------------------------------------ the subset knob

    /** A reference that is wrong at every prefix AND at quiescence. */
    private val alwaysWrong = Reference { mapOf("united" to ModelState.SetState(setOf("nope"))) }

    @Test
    fun `the subset knob at 0 skips prefix checking while final-state comparison stays intact`() {
        var checker: WavePrefixOracle.Checker? = null

        val outcome = DifferentialRunner.run(
            case = diamondCase(diamondScript()),
            reference = alwaysWrong,
            wavePrefix = WavePrefixOption.OFF,
            onWavePrefixChecker = { checker = it },
        )

        withClue("no checker is built at all, so the run pays nothing for the check") {
            checker.shouldBeNull()
        }
        withClue("the final-state comparison is untouched by the knob") {
            outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        }
    }

    @Test
    fun `the subset knob at 1 checks the case, and a glitch outranks the settled mismatch`() {
        // The same case and the same wrong reference as the 0.0 test above: the ONLY difference is
        // the knob, so this pair proves the knob decides. It also pins the precedence
        // WavePrefixViolation > Mismatch — this run disagrees at every wave AND at quiescence,
        // and the earlier, more specific finding is what is reported.
        var checker: WavePrefixOracle.Checker? = null

        val outcome = DifferentialRunner.run(
            case = diamondCase(diamondScript()),
            reference = alwaysWrong,
            wavePrefix = WavePrefixOption.ALWAYS,
            onWavePrefixChecker = { checker = it },
        )

        val violation = outcome.shouldBeInstanceOf<RunOutcome.WavePrefixViolation>()
        violation.kind shouldBe RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
        checker.shouldNotBeNull().observations shouldBeGreaterThan 0
    }

    @Test
    fun `a reference that throws on a prefix is a broken oracle, not a glitch`() {
        // [ORA1-DIFF-08] / D10 reaching the prefix path too: prefix evaluation is model
        // evaluation, so a reference that cannot evaluate one is reported as a broken oracle
        // rather than as evidence about the kernel.
        val outcome = DifferentialRunner.run(
            case = diamondCase(diamondScript()),
            reference = Reference { error("boom") },
            wavePrefix = WavePrefixOption.ALWAYS,
        )

        outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
    }

    @Test
    fun `the default fraction is nonzero and selects a strict, documented subset of seeds`() {
        // D5: prefix checking may be NARROWED for [ORA1-PERF-01], never dropped. The default is
        // therefore a nonzero fraction, and OFF is only reachable as an explicit caller choice.
        WavePrefixOption.DEFAULT.fraction shouldBe WavePrefixOracle.DEFAULT_FRACTION
        WavePrefixOption.DEFAULT.fraction shouldBeGreaterThan 0.0

        val seeds = (0L until 200L).toList()
        val selected = seeds.count { WavePrefixOption.DEFAULT.selects(it) }

        withClue("a default that selects nothing would be D5's forbidden state, spelled as a knob") {
            selected shouldBeGreaterThan 0
        }
        withClue("and one that selects everything would not be a cost knob at all") {
            selected shouldBeLessThan seeds.size
        }
        withClue("measured admission over seeds 0..199 at fraction 0.25") {
            (selected in 30..70) shouldBe true
        }
        withClue("selection is a pure function of the seed, so a shrink replay checks what the sweep checked") {
            seeds.count { WavePrefixOption.DEFAULT.selects(it) } shouldBe selected
        }
        WavePrefixOption.ALWAYS.selects(7L) shouldBe true
        WavePrefixOption.OFF.selects(7L) shouldBe false
    }

    @Test
    fun `prefix checking refuses the cases it is not sound for, and says which limit it hit`() {
        val single = diamondCase(diamondScript())
        WavePrefixOracle.notApplicableBecause(single).shouldBeNull()

        val twoSource = single.copy(
            topology = single.topology.copy(
                nodes = single.topology.nodes + TopologyNode("src2", CoreOperators.Ids.SET, emptyList(), SourceId("s2")),
            ),
        )
        withClue("multi-source needs per-source frontiers — computenet-2hur") {
            WavePrefixOracle.notApplicableBecause(twoSource).shouldNotBeNull().contains("computenet-2hur") shouldBe true
        }

        val twoHost = single.copy(
            topology = single.topology.copy(placement = single.topology.placement + ("u" to 1)),
        )
        withClue("cross-host mid-wave states of undecided provenance — computenet-g25w") {
            WavePrefixOracle.notApplicableBecause(twoHost).shouldNotBeNull().contains("computenet-g25w") shouldBe true
        }
    }

    // ------------------------------------------------- coverage of the GENERATED path

    /**
     * The generated-path config, deliberately `civictech.oracle.gen.GraphSpecLinkSweepTest`'s
     * `sweepConfig` shape with `sourceCount = 1`.
     *
     * `sourceCount` is the ONLY departure, and it is [WavePrefixOracle.appliesTo]'s soundness
     * domain rather than a comfort setting. The **ordinary** writer and remove knobs are kept —
     * `writerCount = 2` and `unobservedRemoveRatio = 0.25`, the same values every other sweep in
     * this feature uses — so the known cross-writer unobserved-remove seam (computenet-qcm1,
     * computenet-4ru.6.3: a spawned `SetCell` retracts a live element on any remove, while the
     * model no-ops a cross-writer remove no `Observe` preceded) is **inside** this population.
     * The sweep partitions it out by measurement, not by configuration — see the sweep test.
     */
    private fun generatedSweepConfig() = GeneratorConfig(
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

    /** Every distinct path from [from] to [to], as the sequence of handles it visits. */
    private fun paths(topology: CaseTopology, from: String, to: String): List<List<String>> {
        val inputs = topology.nodes.associate { it.handle to it.inputs }
        fun walk(handle: String): List<List<String>> = when {
            handle == from -> listOf(listOf(from))
            else -> (inputs[handle] ?: emptyList()).flatMap { upstream ->
                walk(upstream).map { it + handle }
            }
        }
        return walk(to).distinct()
    }

    /**
     * Whether [topology] has a source-to-terminal pair joined by two or more paths whose
     * **operator sequences differ** — the reconvergence witness that MATTERS.
     *
     * A plain path count is a topological fact that a shape can satisfy while producing no
     * observation which distinguishes a per-arm publish from a wave-complete one: two identical
     * arms carry identical values, which is precisely deviation 1's argument against a
     * two-`filter` diamond. Requiring the arms to carry different operator sequences is the
     * cheap structural proxy for "the arms can disagree", and it is asserted BEFORE any agreement
     * assertion so a degenerate sweep fails loudly instead of passing vacuously.
     */
    private fun hasDiscriminatingReconvergence(topology: CaseTopology): Boolean {
        val catalogOf = topology.nodes.associate { it.handle to it.catalogId }
        val sources = topology.nodes.filter { it.source != null }.map { it.handle }
        return topology.terminals.any { terminal ->
            sources.any { src ->
                val labelled = paths(topology, src, terminal.handle).map { path -> path.map { catalogOf.getValue(it) } }
                labelled.size >= 2 && labelled.distinct().size >= 2
            }
        }
    }

    @Test
    fun `BS-8 on the generated path - a single-source sweep is admitted, reconvergent and prefix-clean`() {
        val config = generatedSweepConfig()
        val generator = CaseGenerator(config)
        val seeds = (0L until 60L).toList()
        val cases = seeds.map(generator::generate)

        // --- (1) the check actually applies to this population. The bead's fourth criterion is
        // --- about EFFECT, not about how the knob is spelled: a fraction over an empty eligible
        // --- population is zero coverage.
        val admitted = cases.count { WavePrefixOracle.appliesTo(it) }
        withClue("appliesTo must admit this config, or the generated path is checked on nothing") {
            admitted shouldBe cases.size
        }

        // --- (2) and the DEFAULT knob — not just ALWAYS — selects part of it, so the check runs
        // --- on generated cases without a caller opting in.
        val selectedByDefault = seeds.count { WavePrefixOption.DEFAULT.selects(it) }
        withClue("the default fraction must reach this population") {
            selectedByDefault shouldBeGreaterThan 0
        }

        // --- (3) the population is reconvergent in the sense that can expose a glitch at all.
        // Measured 47/60 on 2026-08-19 (Darwin arm64); asserted as a floor rather than an exact
        // count so a generator change that keeps the sweep reconvergent does not fail here, while
        // one that degenerates it into chains does — which a mutation check confirms (removing the
        // fan-in ids from the vocabulary drops this to 0).
        val reconvergent = cases.count { hasDiscriminatingReconvergence(it.topology) }
        withClue(
            "only $reconvergent of ${cases.size} generated cases have a source-to-terminal pair " +
                "joined by two paths with DIFFERENT operator sequences; without those the sweep " +
                "has degenerated into chains and cannot exercise glitch-freedom",
        ) {
            reconvergent shouldBeGreaterThanOrEqualTo 20
        }

        // --- (4) the property itself. Two populations are partitioned out BY MEASUREMENT and
        // --- PINNED, never by narrowing the config; everything else must be prefix-clean.
        //
        // (a) A case that already disagrees at quiescence with prefix checking OFF is not
        //     evidence about glitch-freedom at all: the comparison a glitch report rests on is
        //     already invalid there. That is the known cross-writer unobserved-remove seam
        //     (computenet-qcm1, computenet-4ru.6.3).
        // (b) Two further SIGNATURES are separated out, so a change in one is visible without
        //     re-measuring the others:
        //       - a REGRESSED observation across a wave the model did not change at that terminal
        //         ([RAW_VIEW_FLICKER_SEEDS]), and
        //       - a violation on a topology with exactly ONE path from source to terminal, which
        //         cannot be a reconvergence tear because there is no second arm
        //         ([CHAIN_ARTIFACT_SEEDS]).
        //     These are signatures, NOT causal verdicts. Measured at review time (2026-08-19,
        //     Darwin arm64, reproduced on the second read): all twelve pinned seeds — the five in
        //     (a) included — disappear at `writerCount = 1` AND at `addRemoveRatio = 1.0` (no
        //     remove generated at all), so the common ingredient is a cross-writer remove
        //     (computenet-qcm1's territory), appearing at an intermediate wave and healing by
        //     quiescence in the seven violation cases. `unobservedRemoveRatio = 0.0` does NOT
        //     remove it, so it is not confined to the *unobserved* remove that bead describes;
        //     which cross-writer remove path diverges is qcm1's to settle, not this file's. The
        //     one thing measured here is which knobs the population depends on. In particular the
        //     earlier
        //     reading of these two buckets as artifacts of THIS observation point (a raw
        //     `TerminalFold` versus `InternalConsistencyTest`'s aligned sink) does not hold:
        //     re-driving each seed with a `Barrier` after every Op and inspecting only the
        //     post-`drainToIdle` `onBarrier` states — where a raw fold and an aligned sink agree
        //     by construction — reproduces every violation, several across consecutive quiesced
        //     boundaries. Filed as computenet-eeys; not decided here (epic design D10).
        //
        // Anything else — a violation on a reconvergent shape that is not a plateau regression —
        // is a glitch CANDIDATE, pinned and filed (computenet-qjtp). Keeping that bucket separate
        // is what stops the partition from becoming an escape hatch: a NEW seed in it fails.
        // (Seed 36 also clears at `writerCount = 1`, so it is most likely the same seam; it stays
        // in the strictest bucket until computenet-qjtp says otherwise.)
        val settledMismatch = mutableListOf<Long>()
        val plateauFlicker = mutableListOf<Long>()
        val chainArtifact = mutableListOf<Long>()
        val glitchCandidate = mutableListOf<Long>()
        val unexpected = mutableListOf<String>()
        var totalObservations = 0

        cases.forEach { case ->
            when (val baseline = DifferentialRunner.run(case, wavePrefix = WavePrefixOption.OFF)) {
                RunOutcome.Success -> {
                    var checker: WavePrefixOracle.Checker? = null
                    val checked = DifferentialRunner.run(
                        case = case,
                        wavePrefix = WavePrefixOption.ALWAYS,
                        onWavePrefixChecker = { checker = it },
                    )
                    val observations = checker?.observations ?: 0
                    totalObservations += observations
                    if (observations == 0) unexpected += "seed ${case.seed}: prefix-checked, 0 observations"
                    when {
                        checked == RunOutcome.Success -> Unit
                        checked !is RunOutcome.WavePrefixViolation -> unexpected += "seed ${case.seed}: $checked"
                        checked.isWithinWaveTransient() -> plateauFlicker += case.seed
                        !hasReconvergence(case.topology) -> chainArtifact += case.seed
                        else -> glitchCandidate += case.seed
                    }
                }

                is RunOutcome.Mismatch -> settledMismatch += case.seed
                else -> unexpected += "seed ${case.seed}: $baseline"
            }
        }

        // Every assertion carries the whole measurement, so one failure reports the population
        // rather than only its own line.
        val summary = "settledMismatch=$settledMismatch, plateauFlicker=$plateauFlicker, " +
            "chainArtifact=$chainArtifact, glitchCandidate=$glitchCandidate, " +
            "unexpected=$unexpected, observations=$totalObservations, " +
            "discriminatingReconvergent=$reconvergent/${cases.size}, selectedByDefault=$selectedByDefault"

        withClue("a generated case must settle to Success or Mismatch; anything else is a harness fault. $summary") {
            unexpected.shouldBeEmpty()
        }
        withClue("the prefix check must have observed something on this population. $summary") {
            totalObservations shouldBeGreaterThan 0
        }
        withClue(
            "the cross-writer unobserved-remove seam's footprint (computenet-qcm1 / " +
                "computenet-4ru.6.3) — a change here is a change in the seam, not in this " +
                "oracle; measured 2026-08-19 on A0030. $summary",
        ) {
            settledMismatch shouldBe SEAM_SEEDS
        }
        withClue(
            "the raw-view plateau-flicker footprint (computenet-eeys) — pinned so it stays " +
                "visible; measured 2026-08-19 on A0030. $summary",
        ) {
            plateauFlicker shouldBe RAW_VIEW_FLICKER_SEEDS
        }
        withClue(
            "the single-path (chain) violation footprint (computenet-eeys) — a chain cannot tear, " +
                "so whatever this is, it is not a reconvergence tear. $summary",
        ) {
            chainArtifact shouldBe CHAIN_ARTIFACT_SEEDS
        }
        withClue(
            "glitch CANDIDATES: a violation on a reconvergent shape that is not a plateau " +
                "regression. A NEW seed here is a new finding — pin it and file it per D10, " +
                "never widen this list to make the suite green. $summary",
        ) {
            glitchCandidate shouldBe GLITCH_CANDIDATE_SEEDS
        }

        val pinned = settledMismatch.size + plateauFlicker.size + chainArtifact.size + glitchCandidate.size
        withClue("the majority of the population must be genuinely clean, not merely pinned. $summary") {
            pinned shouldBeLessThan cases.size / 3
        }
    }

    /**
     * Whether this violation is a **plateau regression**: a regression across a wave the model did
     * not change at that terminal — its answer is the same at the floor and at the floor's
     * successor.
     *
     * A structural signature only. It says the *model* did not move across that wave; it does NOT
     * establish that the kernel's dip and recovery both happened inside one wave — measured, they
     * do not (see the bucket comment in the sweep test). The NAME records the hypothesis this
     * predicate was first cut for, like [RAW_VIEW_FLICKER_SEEDS]'s does; the predicate itself is
     * unchanged by the correction, so it is kept rather than renamed.
     *
     * Deliberately narrow: every [RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX], and
     * every regression across a wave that really changed the terminal, is excluded and lands in a
     * stricter bucket.
     */
    private fun RunOutcome.WavePrefixViolation.isWithinWaveTransient(): Boolean =
        kind == RunOutcome.WavePrefixViolation.Kind.REGRESSED &&
            nearestPrefixes.size == 2 &&
            nearestPrefixes.values.distinct().size == 1

    /**
     * Whether any source reaches any terminal by two or more distinct paths. Its NEGATION is the
     * load-bearing direction: with exactly one path there is no second arm, so no observation can
     * be a torn composite and a violation there needs a different explanation than reconvergence.
     *
     * What that explanation is, this predicate does not say — and measured, it is NOT this
     * oracle's observation point either (see the bucket comment in the sweep test).
     */
    private fun hasReconvergence(topology: CaseTopology): Boolean {
        val sources = topology.nodes.filter { it.source != null }.map { it.handle }
        return topology.terminals.any { terminal ->
            sources.any { src -> paths(topology, src, terminal.handle).size >= 2 }
        }
    }

    private companion object {
        /**
         * The seeds of [generatedSweepConfig] over `0..59` whose runs already disagree at
         * quiescence with prefix checking OFF — the known cross-writer unobserved-remove seam
         * (computenet-qcm1, computenet-4ru.6.3), NOT glitch evidence and NOT this task's to fix
         * (epic design D10).
         *
         * Pinned so the population is stated rather than dodged: a seed leaving this list means
         * the seam narrowed, a seed joining it means the seam widened, and either way the sweep
         * says so instead of silently checking fewer cases.
         */
        val SEAM_SEEDS: List<Long> = listOf(8L, 30L, 40L, 50L, 58L)

        /**
         * Seeds whose terminal REGRESSES across a wave the model did not change — filed as
         * computenet-eeys. Pinned for the same reason [SEAM_SEEDS] is. The name records the
         * hypothesis this bucket was first cut for (a raw view flickering inside a wave); that
         * hypothesis is measurably wrong — the dips reproduce at quiesced barriers — and the
         * cause is the [SEAM_SEEDS] seam. Kept as a distinct signature, not as a diagnosis.
         */
        val RAW_VIEW_FLICKER_SEEDS: List<Long> = listOf(28L, 34L, 44L, 46L, 54L)

        /**
         * Seeds whose topology is a single path source-to-terminal and which nonetheless show a
         * `NO_MATCHING_PREFIX` observation. Seed 38 is a five-deep `flatMapSet` chain (verified:
         * one path, `set -> flatMapSet x5`), so the violation cannot be a reconvergence tear —
         * but it is visible at quiescence too, so it is not an artifact of the observation point
         * either (computenet-eeys).
         */
        val CHAIN_ARTIFACT_SEEDS: List<Long> = listOf(38L)

        /**
         * Glitch CANDIDATES: reconvergent shapes showing a state that is no prefix, which is
         * exactly what `[ORA1-DIFF-06]` exists to catch. Filed as computenet-qjtp with the
         * topology; not fixed here (D10). Seed 36 is `mapSet -> {flatMapSet -> mapSet, direct} ->
         * quorumSet -> union`, three distinct paths.
         */
        val GLITCH_CANDIDATE_SEEDS: List<Long> = listOf(36L)
    }
}
