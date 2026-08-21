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
import civictech.oracle.model.Membership
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
     * this feature uses — so the known cross-writer remove seam (a spawned `SetCell` retracts a
     * live element on any remove, while the model no-ops a cross-writer remove no `Observe`
     * preceded) is **inside** this population. The sweep partitions it out by measurement, not by
     * configuration — see the sweep test. Its *unobserved* half was computenet-qcm1 and its
     * **observed** half computenet-i3vo; both are fixed, so the seam no longer reaches this
     * population at all and every pinned list below is empty. The knobs are deliberately NOT
     * narrowed to keep it that way: `writerCount` stays 2 and `unobservedRemoveRatio` stays 0.25,
     * so a generator regression that reintroduces the step-class shows up here as seeds
     * reappearing in those lists rather than as a population that was configured never to contain
     * them. What the seam *is* — a remove the kernel applies and the model ignores, with the model
     * the wrong side on this single-replica drive path — is computenet-eeys, and it is still
     * demonstrated, on a hand-built script: see [kernelEffectiveModelInertRemoves].
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
        //     already invalid there. That is the known cross-writer remove seam — its *unobserved*
        //     half was computenet-qcm1 and is fixed; what is left here is computenet-eeys.
        // (b) Two further SIGNATURES are separated out, so a change in one is visible without
        //     re-measuring the others:
        //       - a REGRESSED observation across a wave the model did not change at that terminal
        //         ([HEALED_DIVERGENCE_SEEDS]), and
        //       - a violation on a topology with exactly ONE path from source to terminal, which
        //         cannot be a reconvergence tear because there is no second arm
        //         ([SINGLE_PATH_DIVERGENCE_SEEDS]).
        //     These are signatures, NOT causal verdicts. Measured at review time (2026-08-19,
        //     Darwin arm64, reproduced on the second read): all twelve then-pinned seeds — the
        //     five then in (a) included — disappear at `writerCount = 1` AND at
        //     `addRemoveRatio = 1.0` (no remove generated at all), so the common ingredient is a
        //     cross-writer remove, appearing at an intermediate wave and healing by quiescence in
        //     the violation cases. `unobservedRemoveRatio = 0.0` does NOT remove it, so it is not
        //     confined to the *unobserved* remove. **Settled by computenet-eeys**: the path is any
        //     remove whose element stays live in `Membership` — an add by a writer the remover
        //     never observed — while `SetCell.remove` retracts every live tag of it. That is
        //     `emitObservedRemove`'s territory, both its direct and its cross branch, and it
        //     audits `observed = true`, which is why the unobserved knob cannot reach it. See
        //     [kernelEffectiveModelInertRemoves] and its two tests below for the distinguishing
        //     run and the per-seed attribution.
        //     computenet-qcm1 has since landed (commit a3176733) and accounted for three of those
        //     twelve: seeds 34, 46 and 36 left the pinned population altogether and seed 8 moved
        //     from (a) to the flicker bucket, leaving NINE pinned seeds. The nine were a subset of
        //     the twelve, so the knob-dependence measured above carried over to them unchanged
        //     and was not re-run. **computenet-i3vo has since landed and accounts for the
        //     remaining nine**: it states qcm1's constraint as a post-condition over EVERY remove
        //     the generator emits, which reaches `emitObservedRemove`'s direct branch, so no
        //     generated seed carries the step-class at all and all four lists below are EMPTY.
        //     The knobs are unchanged — this is the seam being made unconstructable, not the
        //     population being narrowed away from it. The earlier
        //     reading of these two buckets as artifacts of THIS observation point (a raw
        //     `TerminalFold` versus `InternalConsistencyTest`'s aligned sink) does not hold:
        //     re-driving each seed with a `Barrier` after every Op and inspecting only the
        //     post-`drainToIdle` `onBarrier` states — where a raw fold and an aligned sink agree
        //     by construction — reproduces every violation, several across consecutive quiesced
        //     boundaries. All three buckets are ONE mechanism (computenet-eeys); they are kept
        //     apart only so a change in one is visible without re-measuring the others.
        //
        // Anything else — a violation on a reconvergent shape that is not a plateau regression —
        // is a glitch CANDIDATE, pinned and filed (computenet-qjtp). Keeping that bucket separate
        // is what stops the partition from becoming an escape hatch: a NEW seed in it fails.
        // (That bucket is EMPTY as of computenet-qcm1: seed 36, its only member, was the
        // generator's manufactured divergence and is clean now — see [GLITCH_CANDIDATE_SEEDS] for
        // why an empty exact-equality list is the strongest value here rather than a dormant one,
        // and for the mutation substitution that now applies to all four lists.)
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
                        checked.isPlateauRegression() -> plateauFlicker += case.seed
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
            "the cross-writer remove seam's footprint — EMPTY since computenet-i3vo constrained " +
                "every emitted remove (its unobserved half was computenet-qcm1); a seed appearing " +
                "here is the generator emitting the step-class again, not this oracle changing. " +
                "Re-measured 2026-08-21 on Darwin arm64 under computenet-i3vo. $summary",
        ) {
            settledMismatch shouldBe SEAM_SEEDS
        }
        withClue(
            "the healed-divergence footprint (computenet-eeys) — EMPTY since computenet-i3vo; " +
                "pinned so it stays visible, and it is the bucket only this instrument can see, " +
                "the final-state comparison being blind to it. Re-measured 2026-08-21 on Darwin " +
                "arm64. $summary",
        ) {
            plateauFlicker shouldBe HEALED_DIVERGENCE_SEEDS
        }
        withClue(
            "the single-path (chain) violation footprint (computenet-eeys) — a chain cannot tear, " +
                "so whatever appears here is not a reconvergence tear. EMPTY since " +
                "computenet-i3vo; re-measured 2026-08-21 on Darwin arm64. $summary",
        ) {
            chainArtifact shouldBe SINGLE_PATH_DIVERGENCE_SEEDS
        }
        withClue(
            "glitch CANDIDATES: a violation on a reconvergent shape that is not a plateau " +
                "regression. A NEW seed here is a new finding — pin it and file it per D10, " +
                "never widen this list to make the suite green. $summary",
        ) {
            glitchCandidate shouldBe GLITCH_CANDIDATE_SEEDS
        }

        val pinned = settledMismatch.size + plateauFlicker.size + chainArtifact.size + glitchCandidate.size
        // Kept as a floor rather than tightened to `pinned shouldBe 0`: the four exact-equality
        // assertions above already say the population is entirely clean, and this line's job is
        // the different one of refusing a future re-pin that buys green by pinning a third of the
        // sweep. Measured 0/60 pinned on 2026-08-21 under computenet-i3vo.
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
     * do not.
     *
     * **Renamed from `isWithinWaveTransient` by computenet-eeys**, whose name asserted exactly the
     * within-wave reading that measurement refuted. The predicate's body is unchanged; only the
     * claim its name made is gone.
     *
     * Deliberately narrow: every [RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX], and
     * every regression across a wave that really changed the terminal, is excluded and lands in a
     * stricter bucket.
     */
    private fun RunOutcome.WavePrefixViolation.isPlateauRegression(): Boolean =
        kind == RunOutcome.WavePrefixViolation.Kind.REGRESSED &&
            nearestPrefixes.size == 2 &&
            nearestPrefixes.values.distinct().size == 1

    /**
     * Whether any source reaches any terminal by two or more distinct paths. Its NEGATION is the
     * load-bearing direction: with exactly one path there is no second arm, so no observation can
     * be a torn composite and a violation there needs a different explanation than reconvergence.
     *
     * That explanation is settled (computenet-eeys): a writer-concurrent remove the kernel applies
     * and the model ignores, which diverges at the *source* and therefore needs no second arm.
     * See [kernelEffectiveModelInertRemoves] and [SINGLE_PATH_DIVERGENCE_SEEDS].
     */
    private fun hasReconvergence(topology: CaseTopology): Boolean {
        val sources = topology.nodes.filter { it.source != null }.map { it.handle }
        return topology.terminals.any { terminal ->
            sources.any { src -> paths(topology, src, terminal.handle).size >= 2 }
        }
    }


    // ------------------------------------- computenet-eeys: what the pinned seeds actually are

    /**
     * The removes of [script] that the **kernel applies and the reference model ignores** —
     * the settled mechanism behind every pinned seed below (computenet-eeys).
     *
     * A `Remove(w, x)` qualifies iff `x` is live before it *and still live after it* under
     * [Membership]. Both halves are read from the model's own fold rather than re-derived, so
     * this predicate cannot come to hold a second notion of liveness (the same discipline
     * `ScriptGenerator.emitUnobservedRemove` adopted under computenet-qcm1).
     *
     * Live-before is what makes the step take effect in the kernel: `SetCell.inletHandler.remove`
     * retracts `liveTags(element)` — every un-tombstoned tag the cell holds — and returns early
     * only when that set is empty. Still-live-after is what makes it a model no-op: `Membership`
     * covers only the adds the removing writer had *observed*, so an add by another writer that
     * `w` never observed survives. The two together are exactly a kernel-visible, model-invisible
     * state change, which is a Mismatch by construction wherever a terminal can see the element.
     *
     * Note this says nothing about the audit's observed/unobserved flavour, and deliberately so —
     * see [`a remove of an element another writer added is applied by the kernel and ignored by the model`].
     */
    private fun kernelEffectiveModelInertRemoves(script: CaseScript): List<String> {
        val found = mutableListOf<String>()
        script.toScript().slices.forEach { slice ->
            slice.events.forEachIndexed { position, event ->
                if (event is ScriptEvent.Remove) {
                    val before = Membership.live(slice.events.take(position))
                    val after = Membership.live(slice.events.take(position + 1))
                    if (event.element in before && event.element in after) {
                        found += "${slice.source.id}[$position] ${event.writer.id} removes ${event.element}"
                    }
                }
            }
        }
        return found
    }

    /**
     * **The distinguishing run this bead's first acceptance criterion asks for.**
     *
     * Three events, one writer-concurrent add, no seeds, no generator: `w0` adds `ab`, `w1` adds
     * `ab`, `w0` removes `ab`.
     *
     * - The **model** ([Membership], `[ORA1-MODEL-04]`/`[ORA1-MODEL-05]`) covers only the adds
     *   the removing writer observed. `w0` observed its own add and never observed `w1`'s, so
     *   `w1`'s add is uncovered and `ab` stays live.
     * - The **kernel** (`SetCell.inletHandler.remove`) retracts `liveTags("ab")` — *both* tags,
     *   because both are un-tombstoned in the one cell — and `ab` is gone.
     *
     * That is the whole divergence, and it is why `unobservedRemoveRatio = 0.0` does not clear
     * the pinned seeds: nothing here is an unobserved remove. `w0` is removing an element it
     * added itself, which is `ScriptGenerator.emitObservedRemove`'s *direct* branch and audits as
     * `observed = true`.
     *
     * The two controls below fix which side moves:
     *
     * - **One writer, same shape** — `Add(w0)`, `Add(w0)`, `Remove(w0)` — is `Success`. So it is
     *   writer *concurrency* and not repetition, which is why `writerCount = 1` is 60/60 clean.
     * - **A stale observation** — `Add(w0)`, `Observe(w1)`, `Add(w0)`, `Remove(w1)` — diverges
     *   too, through `emitObservedRemove`'s *cross* branch: `Observe` grants `w1` the adds at
     *   *earlier* positions only, while the generator's `SourceState.known` is monotone, so `w1`
     *   is offered an element whose newest add it never observed. Same mechanism, second branch.
     *
     * ## The verdict, since a mismatch alone does not say which side is wrong
     *
     * **The reference model is the wrong side on this drive path, and the kernel is right.**
     * `[24-SET-03]` requires a remove to retract "the tags it observed"; the observer is the
     * *cell*, and a `SetCell` driven through its own `inlet` has by construction observed every
     * add that reached it, because it is a single serialization point. Writer identity has no
     * kernel counterpart at all here: `CaseExecution` drives every writer's op through one inlet,
     * and `ScriptEvent.Observe` injects nothing (see [WavePrefixOracle]). Genuine concurrency in
     * this kernel lives across *replicas* — `SetCell.deltaInlet`/`applyRemote`, spec 40/42 — and
     * this harness builds one replica. So the model's per-writer rule is sound only for a script
     * whose writers really are separate replicas, and the generated cases' writers are not.
     */
    @Test
    fun `a remove of an element another writer added is applied by the kernel and ignored by the model`() {
        val w0 = WriterId("w0")
        val w1 = WriterId("w1")

        fun outcomeOf(vararg events: ScriptEvent) = DifferentialRunner.run(
            case = diamondCase(CaseScript(events.map { CaseStep.Op(source, it) })),
            wavePrefix = WavePrefixOption.OFF,
        )

        // --- the divergence, at its minimum.
        val diverging = outcomeOf(
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Add(w1, "ab"),
            ScriptEvent.Remove(w0, "ab"),
        ).shouldBeInstanceOf<RunOutcome.Mismatch>()

        withClue("the model keeps 'ab' live: w0's remove covers only w0's own add") {
            diverging.expected shouldBe ModelState.SetState(setOf("ab", "a", "b"))
        }
        withClue("the kernel retracted every live tag of 'ab', w1's included") {
            diverging.actual shouldBe ModelState.SetState(emptySet())
        }
        withClue("and this predicate is what names that step, on the model's own fold") {
            kernelEffectiveModelInertRemoves(
                CaseScript(
                    listOf(
                        CaseStep.Op(source, ScriptEvent.Add(w0, "ab")),
                        CaseStep.Op(source, ScriptEvent.Add(w1, "ab")),
                        CaseStep.Op(source, ScriptEvent.Remove(w0, "ab")),
                    ),
                ),
            ).size shouldBe 1
        }

        // --- control 1: the same shape under ONE writer settles. It is concurrency, not repetition.
        withClue("one writer: its remove covers both of its own adds, so both sides drop 'ab'") {
            outcomeOf(
                ScriptEvent.Add(w0, "ab"),
                ScriptEvent.Add(w0, "ab"),
                ScriptEvent.Remove(w0, "ab"),
            ) shouldBe RunOutcome.Success
        }

        // --- control 2: the cross-branch variant, where an Observe exists but is STALE.
        val stale = outcomeOf(
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Observe(w1),
            ScriptEvent.Add(w0, "ab"),
            ScriptEvent.Remove(w1, "ab"),
        ).shouldBeInstanceOf<RunOutcome.Mismatch>()
        withClue("Observe grants w1 the EARLIER add only; the later one is uncovered in the model") {
            stale.expected shouldBe ModelState.SetState(setOf("ab", "a", "b"))
        }
        withClue("the kernel retracts both tags regardless of when they were minted") {
            stale.actual shouldBe ModelState.SetState(emptySet())
        }
    }

    /**
     * The attribution, **inverted by `computenet-i3vo`**: no generated seed carries a
     * [kernelEffectiveModelInertRemoves] step any more, in any of the three configurations this
     * test drives — which is why all four pinned lists below are empty.
     *
     * What this test asserted before the fix, and why the inversion is the same claim: over
     * [generatedSweepConfig] seeds 0..59 at `writerCount = 2`, 22 of 60 seeds carried such a step
     * and 9 of those 22 surfaced as a Mismatch or a prefix violation (measured 2026-08-20, Darwin
     * arm64; reproduced unchanged on this branch's base, commit abcf53bc). The other 13 were
     * masked downstream, where the sweep's `filter`, `quorumSet` or `count` operators did not let
     * the element's presence reach the terminal. The condition was **necessary, not sufficient**,
     * and it was asserted in exactly that direction: every pinned seed carried one, more seeds
     * carried one than failed. `ScriptGenerator` now states the constraint as a post-condition
     * over every remove it emits (`computenet-i3vo`), so the carrier population is empty and the
     * pinned population — a subset of it — is empty with it.
     *
     * `unobservedRemoveRatio = 0.0` is kept as a configuration here for continuity: it used to be
     * the *discriminating* control (it did NOT clear the carriers, which is how the residual was
     * attributed to `emitObservedRemove` rather than to qcm1's branch), and it is now simply one
     * more configuration that is clean.
     *
     * **This is not a dormant predicate.** The two controls that keep it live:
     *
     *  - [`a remove of an element another writer added is applied by the kernel and ignored by the model`]
     *    builds the three-event script by hand, with no generator, and asserts this predicate
     *    finds exactly one step in it. The mechanism is still real and still diverges; what has
     *    changed is only that the generator no longer manufactures it.
     *  - [syntheticCarrierScript] below, asserted here, so a mutation that made
     *    [kernelEffectiveModelInertRemoves] return `emptyList()` unconditionally fails *this*
     *    test rather than only its neighbour.
     */
    @Test
    fun `no generated seed carries a kernel-effective, model-inert remove, under any writer or remove knob`() {
        val seeds = (0L until 60L).toList()

        fun carriers(config: GeneratorConfig): List<Long> {
            val generator = CaseGenerator(config)
            return seeds.filter { kernelEffectiveModelInertRemoves(generator.generate(it).script).isNotEmpty() }
        }

        withClue(
            "computenet-i3vo: every remove ScriptGenerator emits leaves its element not live in " +
                "Membership, so no generated seed can carry the mechanism at all",
        ) {
            carriers(generatedSweepConfig()).shouldBeEmpty()
        }
        withClue(
            "a writer observes its own adds, so under one writer no remove could leave its element " +
                "live even before computenet-i3vo — which is why writerCount = 1 was already 60/60 clean",
        ) {
            carriers(generatedSweepConfig().copy(writerCount = 1).validated()).shouldBeEmpty()
        }
        withClue(
            "unobservedRemoveRatio = 0.0 used to be the discriminating control — it did NOT clear " +
                "the carriers, which is how the residual was attributed to emitObservedRemove's " +
                "observed = true steps rather than to computenet-qcm1's branch",
        ) {
            carriers(generatedSweepConfig().copy(unobservedRemoveRatio = 0.0).validated()).shouldBeEmpty()
        }

        withClue(
            "and the predicate is not dormant: it still names the step in a hand-built script that " +
                "carries one, so the three empty results above are the generator's doing",
        ) {
            kernelEffectiveModelInertRemoves(syntheticCarrierScript()).size shouldBe 1
        }
    }

    /**
     * The minimal script that carries the mechanism — `w0` adds `ab`, `w1` adds `ab`, `w0` removes
     * `ab` — built by hand so [kernelEffectiveModelInertRemoves] has a positive case to be checked
     * against now that no generated seed produces one.
     *
     * The same three events the distinguishing run above drives through the differential; here
     * they are only folded through [Membership], so this helper asserts nothing about the kernel.
     */
    private fun syntheticCarrierScript(): CaseScript = CaseScript(
        listOf(
            CaseStep.Op(source, ScriptEvent.Add(WriterId("w0"), "ab")),
            CaseStep.Op(source, ScriptEvent.Add(WriterId("w1"), "ab")),
            CaseStep.Op(source, ScriptEvent.Remove(WriterId("w0"), "ab")),
        ),
    )

    private companion object {
        /**
         * The seeds of [generatedSweepConfig] over `0..59` whose runs already disagree at
         * quiescence with prefix checking OFF — the cross-writer remove seam, NOT glitch evidence
         * and NOT this task's to fix (epic design D10).
         *
         * Pinned so the population is stated rather than dodged: a seed leaving this list means
         * the seam narrowed, a seed joining it means the seam widened, and either way the sweep
         * says so instead of silently checking fewer cases.
         *
         * **Re-pinned 2026-08-19 by `computenet-qcm1` (commit a3176733), from
         * `[8, 30, 40, 50, 58]`.** That bead fixed `ScriptGenerator.emitUnobservedRemove`, whose
         * draw could name an element another writer had added and which was still live: the
         * kernel's `SetCell` retracts `liveTags(element)` unconditionally and the remove took
         * effect, while `Membership` no-ops it for lack of an `Observe` by that writer, so the
         * disagreement was manufactured by the generator rather than found in the kernel. Seed 8
         * left this bucket for exactly that reason — it now settles correctly at quiescence and
         * shows only an intermediate-wave regression, so it moved into [HEALED_DIVERGENCE_SEEDS].
         *
         * The distinction a later reader needs: this list only ever **shrinks** under a generator
         * fix, and every departure is accounted for below. Nothing was removed to make the suite
         * green, and one NEW seed appearing here still fails this assertion.
         *
         * **EMPTY as of 2026-08-21, re-pinned by `computenet-i3vo`, from `[30, 40, 50, 58]`.**
         * That bead completed what qcm1 started: the constraint qcm1 put on
         * `ScriptGenerator.emitUnobservedRemove` — a remove must not leave its element live in
         * `Membership` — is now a post-condition over **every** remove the generator emits, which
         * reaches `emitObservedRemove`'s *direct* branch where the residual sat. All four seeds
         * depart for the same single reason, individually: each of 30, 40, 50 and 58 carried at
         * least one [kernelEffectiveModelInertRemoves] step whose audit said `observed = true`,
         * and with that step-class unconstructable none of the four disagrees at quiescence any
         * more. No seed moved to another bucket this time — the other three lists emptied in the
         * same measurement rather than absorbing anyone. `computenet-eeys` settled that the
         * *model* is the wrong side on this drive path and left the repair to the generator; this
         * is that repair landing, not the kernel changing.
         *
         * See [GLITCH_CANDIDATE_SEEDS] for why an empty exact-equality list is the strongest value
         * these constants can hold, and for the substitution that re-demonstrates it under
         * mutation now that the pre-fix mutant is a no-op.
         */
        val SEAM_SEEDS: List<Long> = emptyList()

        /**
         * Seeds whose terminal REGRESSES across a wave the model did not change, and which
         * nonetheless settle correctly at quiescence — the writer-concurrent remove divergence
         * appearing at an intermediate wave and **healing** before the end. Pinned for the same
         * reason [SEAM_SEEDS] is; the difference between the two lists is only whether the
         * divergence survives to quiescence, not what it is.
         *
         * **Renamed from `RAW_VIEW_FLICKER_SEEDS` by computenet-eeys.** The old name recorded a
         * refuted hypothesis (a raw `TerminalFold` flickering inside a wave); the dips reproduce
         * at quiesced barriers, and the settled mechanism is the one
         * [kernelEffectiveModelInertRemoves] names — a remove the kernel applies and
         * [civictech.oracle.model.Membership] ignores. Catching these seven-then, four-now is
         * this instrument's whole contribution: the final-state comparison cannot see them.
         *
         * **Re-pinned 2026-08-19 by `computenet-qcm1` (commit a3176733), from
         * `[28, 34, 44, 46, 54]`.** Two changes, both consequences of that bead's fix to
         * `emitUnobservedRemove` and neither an adjustment to taste:
         *  - seeds **34 and 46 left the pinned population entirely** — they no longer violate at
         *    all, so the violation they showed was the generator's manufactured divergence;
         *  - seed **8 arrived from [SEAM_SEEDS]** — it stops disagreeing at quiescence and what
         *    remains of it is an intermediate-wave regression, which is this bucket's signature.
         *
         * **EMPTY as of 2026-08-21, re-pinned by `computenet-i3vo`, from `[8, 28, 44, 54]`.**
         * Each of the four leaves the pinned population entirely — no intermediate-wave regression
         * survives at any of them — and each for the same reason, checked one by one: 8, 28, 44
         * and 54 all carried a [kernelEffectiveModelInertRemoves] step, and the generator can no
         * longer emit one. That this bucket empties *together with* [SEAM_SEEDS] and
         * [SINGLE_PATH_DIVERGENCE_SEEDS] is the evidence for what computenet-eeys claimed and this
         * bead acted on — all three lists were ever only one mechanism, kept apart so a change in
         * one would be visible without re-measuring the others.
         */
        val HEALED_DIVERGENCE_SEEDS: List<Long> = emptyList()

        /**
         * Seeds whose topology is a single path source-to-terminal and which nonetheless show a
         * `NO_MATCHING_PREFIX` observation. Seed 38 is a five-deep `flatMapSet` chain (verified:
         * one path, `set -> flatMapSet x5`), so the violation cannot be a reconvergence tear —
         * and it is visible at quiescence too, so it is not an artifact of the observation point
         * either. Settled by computenet-eeys as the same writer-concurrent remove divergence as
         * the other two lists: a chain needs no second arm to show a state that is no prefix once
         * the source itself holds a membership the model does not
         * ([kernelEffectiveModelInertRemoves] finds one in seed 38's script, at `s[23]`).
         *
         * **Renamed from `CHAIN_ARTIFACT_SEEDS` by computenet-eeys** — it is not an artifact.
         *
         * **Unchanged by `computenet-qcm1`'s re-pin (2026-08-19).** Named here because it was the
         * control then: three of these four lists moved and this one did not, so that re-pin was
         * the measured footprint of one generator fix rather than a wholesale re-measurement that
         * happened to land on a green population.
         *
         * **EMPTY as of 2026-08-21, re-pinned by `computenet-i3vo`, from `[38]`.** Seed 38's one
         * carrier step was the one named above — `s[23]` — and it is exactly the step-class the
         * generator can no longer emit, so the chain shows no `NO_MATCHING_PREFIX` observation any
         * more. This departure is the sharpest of the three, because a chain has no second arm:
         * there was never an alternative reconvergence explanation for it to fall back on.
         */
        val SINGLE_PATH_DIVERGENCE_SEEDS: List<Long> = emptyList()

        /**
         * Glitch CANDIDATES: reconvergent shapes showing a state that is no prefix, which is
         * exactly what `[ORA1-DIFF-06]` exists to catch. A seed here is a finding to pin and
         * file (D10), never a list to widen.
         *
         * **EMPTY as of 2026-08-19, re-pinned by `computenet-qcm1` (commit a3176733), from
         * `[36]`.** Seed 36 — `mapSet -> {flatMapSet -> mapSet, direct} -> quorumSet -> union`,
         * three distinct paths, filed as computenet-qjtp — is clean once
         * `emitUnobservedRemove` stops naming live elements. So that candidate was **manufactured
         * by the generator**, not found in the kernel: this sweep's single glitch candidate was an
         * artifact of a remove the kernel applied and the reference model ignored. (Whether
         * computenet-qjtp is thereby resolved is that bead's owner's call, not this file's.)
         *
         * **The empty list is the STRONGEST value this constant can hold, not a weakened one.**
         * The assertion is exact list equality, so any candidate at all now fails it, where
         * before only a candidate other than 36 did. What DID change is that one particular
         * mutation check is now a no-op: the recorded check for this line was "replace
         * `listOf(36L)` with `emptyList()`, observe `expected:<[]> but was:<[36L]>`", and with the
         * list legitimately empty that mutant is identical to the original and can no longer
         * fail. The property it was demonstrating did not move to some other assertion — it is
         * still this one — so it was re-demonstrated from the other side instead, and that
         * substitution is the thing to repeat if this line is ever touched again: inject a
         * synthetic candidate into the measured list (`glitchCandidate += 999L` after the sweep
         * loop) and this assertion fails with `expected:<[]> but was:<[999]>`. Measured
         * 2026-08-19 on Darwin arm64 (MacBoo) under `computenet-qcm1`.
         *
         * **As of `computenet-i3vo` (2026-08-21) that reasoning governs all four lists**, since
         * the other three are empty too: whichever one is being touched, the substitution is the
         * same — inject a synthetic seed into the corresponding measured list after the sweep
         * loop (`settledMismatch += 999L`, `plateauFlicker += 999L`, `chainArtifact += 999L`) and
         * watch that list's assertion fail with `expected:<[]> but was:<[999]>`. Verified in that
         * form for [SEAM_SEEDS] on 2026-08-21, Darwin arm64.
         */
        val GLITCH_CANDIDATE_SEEDS: List<Long> = emptyList()
    }
}
