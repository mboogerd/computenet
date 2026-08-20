package civictech.oracle.run

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.gen.GraphGenerator
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `[ORA1-DIFF-01]` / `[ORA1-DIFF-03]` / `[ORA1-DIFF-04]` / `[ORA1-PERF-01]` /
 * `[ORA1-PERF-02]` / `[ORA1-PERF-03]`: the seed sweep — the baseline agreement run (BS-1), the
 * density-not-fail-fast behavior, the progress stream, and the `-Poracle.seeds` knob.
 */
class OracleSweepTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /**
     * BS-1's configuration: depth 1..4, the set-algebra vocabulary (the `SetOf(Scalar)` ids
     * `CoreOperators` registers), 200 script ops, two terminals — the four knobs the epic's
     * BS-1 row names — plus these choices for the rest:
     *
     * - **`writerCount = 1`.** BS-1 therefore exercises a **single writer**, and says nothing
     *   about cross-writer behavior. That is deliberate and it is a real limit of this test:
     *   at the generator's default `writerCount = 2`, roughly 144 of 200 seeds of this same
     *   config mismatch with one signature — `SetDifference(onlyInExpected=[x],
     *   onlyInActual=[])`, the model keeping live an element the kernel removed — because
     *   `SetCell.inletHandler.remove` retracts unconditionally while the batch `Membership`
     *   model no-ops a cross-writer remove that lacks a preceding `Observe`. That is
     *   *harness* semantics, tracked as computenet-qcm1 / computenet-4ru.6.3, not kernel
     *   evidence, and it is out of this test's scope. Do NOT read a green BS-1 as covering
     *   multi-writer scripts; the multi-writer sweep is that seam's own item.
     * - **`sourceCount = 4`**, not 2: `terminalCount = 2` fails `GraphGenerator`'s topology
     *   sizing on low-depth draws at two sources, so four is the smallest count that lets the
     *   named two-terminal shape generate across the whole depth range.
     * - **`unobservedRemoveRatio = 0.0`**: every generated remove is observed by its writer.
     *   Unobserved removes are the computenet-qcm1 seam above, so biasing towards them here
     *   would measure that seam rather than kernel-vs-model agreement.
     * - `elementDomainSize = 8` and `addRemoveRatio = 0.7` keep the set contents churning —
     *   a domain small enough that adds and removes collide, weighted so sets grow rather
     *   than empty out.
     */
    private fun baselineConfig() = GeneratorConfig(
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
     * BS-1 `[ORA1-DIFF-01]` `[ORA1-DIFF-04]`: every seed of the default range agrees with the
     * batch reference model, with no dead letter and no non-quiescence — a
     * [RunOutcome.DeadLetterFailure] or [RunOutcome.NonQuiescence] is not
     * [RunOutcome.Success], so [OracleSweep.run] fails the sweep on either, naming the seed.
     *
     * The sweep runs the range the `-Poracle.seeds` knob selects
     * ([OracleSweep.defaultSeeds]), defaulting to 200 seeds. It prints the per-seed cost it
     * actually observed, so the figure recorded in [OracleSweep]'s KDoc — the
     * `[ORA1-PERF-01]` sizing evidence — is checkable on any machine rather than folklore.
     *
     * ## History worth keeping, because it decides how to read a failure here
     *
     * This test was red at 4 of 200 seeds (27, 88, 154, 156) with a single writer, all four on
     * diamond topologies, all four `SetDifference(onlyInExpected=[...], onlyInActual=[])`. That
     * was a genuine **kernel** defect found by this sweep — `IntersectSetCell` advertising
     * borrowed input tags, so a reconvergent path back into a `UnionSetCell` dropped live
     * elements — filed as computenet-vvre and fixed in the kernel (PR #324), which is the
     * sanctioned route (epic D10: a defect the oracle finds is fixed where it lives, never
     * worked around in the runner). If this test goes red again, the first question is which
     * seeds and what difference shape — not whether the range can be narrowed. Narrowing the
     * seed range or the config to make it green is the one response that is never correct.
     */
    @Test
    fun `baseline sweep agrees with the batch reference on every seed`() {
        val seeds = OracleSweep.defaultSeeds()
        val count = seeds.last - seeds.first + 1L

        val startedAt = System.nanoTime()
        OracleSweep.run(baselineConfig(), seeds)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println(
            "[oracle-sweep] BS-1: $count seeds ($seeds) in $elapsedMs ms " +
                "(${"%.1f".format(elapsedMs.toDouble() / count)} ms/seed) [ORA1-PERF-01]",
        )
    }

    /**
     * The **pair-shaped** sweep config: the whole registered vocabulary
     * ([CoreOperators.Ids.ALL]), so `keyBy` and the eleven entries it bootstraps —
     * `joinSet`/`semiJoin`/`antiJoin` and the eight `groupBy*` — are drawable, at the wide
     * knobs `PairShapeBootstrapTest` and `GraphGeneratorTest` use (depth 3..5, three sources,
     * element domain 6, 40 script ops).
     *
     * Two knobs differ from `PairShapeBootstrapTest.wideConfig`, and both are deliberate
     * because this config is *executed* rather than only generated:
     *
     * - **`writerCount = 1`**, against `GeneratorConfig`'s default of 2. At two writers this
     *   config mismatches for a reason that has nothing to do with the pair family: the
     *   computenet-qcm1 / computenet-4ru.6.3 cross-writer remove asymmetry (`SetCell`'s inlet
     *   retracts unconditionally, while the batch `Membership` model no-ops a cross-writer
     *   remove that lacks a preceding `Observe`) — the same seam `baselineConfig` excludes for
     *   the same reason. So this test says **nothing** about multi-writer pair-shaped scripts;
     *   that is qcm1's item, not this one.
     * - **`unobservedRemoveRatio = 0.0`**, against 0.25. Unobserved removes are the same
     *   qcm1 seam approached from the other side, so biasing towards them here would measure
     *   that seam rather than kernel-vs-model agreement over the pair family.
     */
    private fun pairShapedConfig() = GeneratorConfig(
        depthRange = 3..5,
        sourceCount = 3,
        vocabulary = CoreOperators.Ids.ALL,
        elementDomainSize = 6,
        scriptLength = 40,
        addRemoveRatio = 0.6,
        unobservedRemoveRatio = 0.0,
        terminalCount = 2,
        writerCount = 1,
    ).validated()

    /**
     * `[ORA1-DIFF-01]` `[ORA1-DIFF-04]` for the **pair-shaped half of the vocabulary**
     * (computenet-q21w).
     *
     * ## The hole this closes
     *
     * computenet-4ru.16 registered `keyBy` and thereby made the eleven pair-shaped entries
     * *generable*; `PairShapeBootstrapTest` pins that they are generated. What no committed
     * test did was **run** one: every differential-execution suite in this module —
     * [OracleSweepTest]'s own BS-1 above, [DivergenceControlTest], [WavePrefixTest],
     * `PinnedSeedsTest`, [GeneratedCaseExecutionTest], [FailureTaxonomyTest], the shrinker
     * suites — names an explicit set-algebra vocabulary and none names `keyBy` or any
     * pair-shaped id, so ~40% of the operator algebra was reachable and unexecuted. This test
     * executes it, kernel against batch reference, and asserts agreement — no Mismatch, no
     * dead letter, no non-quiescence, which is what [OracleSweep.run] fails a seed on.
     *
     * ## Why the population is counted before it is swept
     *
     * The sweep alone would pass **vacuously** on a population that happens to contain no
     * pair-shaped node — which is exactly the state the module was in before 4ru.16, and
     * exactly the state it returns to if `keyBy` is unregistered or reshaped, or a generator
     * change steers the frontier away from `SetOf(Tuple(2))`. So the seeds whose topology
     * holds one of the twelve ids are counted first and the count is asserted, over the
     * *same* `(seed, config)` pairs the sweep then executes: [civictech.oracle.gen.CaseGenerator]
     * derives its graph as `GraphGenerator(config).generate(Random(seed))`, which is exactly
     * what [GraphGenerator.generate]`(seed)` is defined as, so the two enumerate the same
     * topologies rather than two similar ones.
     *
     * [PAIR_SHAPED_MINIMUM] is a floor well under what is observed, not the observed figure:
     * the assertion exists to catch the family *collapsing*, and pinning the exact count would
     * make this test fail on any innocuous generator retune. Observed 2026-08-20 on this
     * config (macOS/arm64): **53 of 200** seeds carry a pair-shaped node, a superset of — and
     * consistent with — the 23 join/groupBy-bearing seeds computenet-q21w measured on the
     * closely related `unobservedRemoveRatio = 0.25` variant. The list is printed on every run
     * so the margin is visible rather than inferred from a bare green tick.
     *
     * ## What a failure here means
     *
     * A dropped count is a **generation/registration** regression — read
     * `PairShapeBootstrapTest` next. A sweep failure is a **disagreement** between the kernel
     * and the reference over an operator this config draws, and per the epic's D10 it is fixed
     * where it lives, never narrowed away here. And read [OracleSweep]'s "what a green sweep
     * MEANS" KDoc before quoting this test: agreement defends the reference model, it does not
     * prove it.
     */
    @Test
    fun `the pair-shaped vocabulary executes differentially and agrees with the batch reference`() {
        val config = pairShapedConfig()
        val seeds = 0L until PAIR_SHAPED_SWEEP_SEEDS

        val generator = GraphGenerator(config)
        val pairShapedSeeds = seeds.filter { seed ->
            generator.generate(seed).topology.nodes.any { it.catalogId in PAIR_SHAPED_FAMILY }
        }
        println(
            "[oracle-sweep] pair-shaped: ${pairShapedSeeds.size} of $PAIR_SHAPED_SWEEP_SEEDS " +
                "seeds carry a keyBy/join/groupBy node: $pairShapedSeeds",
        )
        withClue(
            "No seed of this sweep draws a pair-shaped node, so executing it would assert " +
                "nothing about keyBy, joinSet/semiJoin/antiJoin or the groupBy* family — the " +
                "vacuity this test exists to exclude. Either `keyBy` (the SetOf(Scalar) -> " +
                "SetOf(Tuple(2)) bootstrap computenet-4ru.16 added) is no longer registered or " +
                "no longer has that shape, or a generator change has steered the frontier away " +
                "from pair-shaped nodes. See PairShapeBootstrapTest for the generation-side " +
                "measurement.",
        ) {
            pairShapedSeeds.size shouldBeGreaterThanOrEqual PAIR_SHAPED_MINIMUM
        }

        val startedAt = System.nanoTime()
        OracleSweep.run(config, seeds, onProgress = {})
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println(
            "[oracle-sweep] pair-shaped: $PAIR_SHAPED_SWEEP_SEEDS seeds in $elapsedMs ms " +
                "(${"%.1f".format(elapsedMs.toDouble() / PAIR_SHAPED_SWEEP_SEEDS)} ms/seed)",
        )
    }

    /**
     * `[ORA1-DIFF-03]`: the sweep runs **every** seed and reports the failure *density*, rather
     * than aborting at the first failure.
     *
     * Failures are provoked by substituting a deliberately wrong reference — the divergence
     * seam [DifferentialRunner.run] already exposes — so the test needs no broken kernel and
     * cannot go green because the kernel got fixed. Every seed of the range must have been
     * *started* (the progress stream is the witness) and the thrown summary must be
     * `forEachSeed`'s density form, counting all of them.
     */
    @Test
    fun `a sweep runs every seed and reports failure density rather than aborting at the first`() {
        val started = mutableListOf<Long>()
        // Names both of the case's terminals (the generator names them `terminal-0` and
        // `terminal-1`) so every seed reaches a comparison and reports Mismatch, rather than
        // failing earlier as "the reference produced no state for terminal ...".
        val wrongReference = Reference {
            mapOf(
                "terminal-0" to ModelState.SetState(setOf("no-such-element")),
                "terminal-1" to ModelState.SetState(setOf("no-such-element")),
            )
        }

        val failure = assertThrows<AssertionError> {
            OracleSweep.run(
                config = baselineConfig().copy(scriptLength = 20),
                seeds = 0L..4L,
                reference = wrongReference,
                onProgress = { started += it.seed },
            )
        }

        started shouldBe listOf(0L, 1L, 2L, 3L, 4L)
        val message = failure.message ?: error("forEachSeed's density summary carried no message")
        message shouldContain "failed on 5 of 5 seeds"
        message shouldContain "first: seed=0"
        message shouldContain "Mismatch"
    }

    /**
     * `[ORA1-PERF-03]`: while running, the sweep reports the current seed and the case's index
     * in the sweep — so a hang is attributable to one specific case rather than to "the sweep".
     */
    @Test
    fun `a sweep reports the current seed and case index while it runs`() {
        val progress = mutableListOf<OracleSweep.Progress>()

        OracleSweep.run(
            config = baselineConfig().copy(scriptLength = 20),
            seeds = 5L..8L,
            onProgress = { progress += it },
        )

        progress shouldBe listOf(
            OracleSweep.Progress(seed = 5L, index = 1, total = 4),
            OracleSweep.Progress(seed = 6L, index = 2, total = 4),
            OracleSweep.Progress(seed = 7L, index = 3, total = 4),
            OracleSweep.Progress(seed = 8L, index = 4, total = 4),
        )
        // The line the default reporter prints, asserted so the format a hanging sweep is
        // diagnosed from cannot silently lose the seed or the index.
        progress.first().toString() shouldBe "[oracle-sweep] case 1/4 seed=5"
    }

    /**
     * `[ORA1-PERF-02]`: `-Poracle.seeds=N` reaches the default range computation with no source
     * change. `oracle/build.gradle.kts` forwards the Gradle property to this system property;
     * this test covers the half of that path that lives in Kotlin, and the widened Gradle
     * invocation is cited on the bead.
     */
    @Test
    fun `the oracle seeds system property selects the default seed range`() {
        withSeedProperty("40") {
            OracleSweep.defaultSeedCount() shouldBe 40
            OracleSweep.defaultSeeds() shouldBe 0L..39L
        }
        withSeedProperty("1000") {
            OracleSweep.defaultSeeds() shouldBe 0L..999L
        }
    }

    /**
     * A malformed or non-positive `-Poracle.seeds` falls back to [OracleSweep.DEFAULT_SEED_COUNT]
     * rather than throwing or sweeping an empty range: a typo in a local invocation must not
     * look like a test failure, and must not silently check nothing either.
     */
    @Test
    fun `an unusable oracle seeds value falls back to the default count`() {
        withSeedProperty(null) {
            OracleSweep.defaultSeedCount() shouldBe OracleSweep.DEFAULT_SEED_COUNT
        }
        listOf("not-a-number", "0", "-5", "").forEach { value ->
            withSeedProperty(value) {
                OracleSweep.defaultSeedCount() shouldBe OracleSweep.DEFAULT_SEED_COUNT
            }
        }
        OracleSweep.DEFAULT_SEED_COUNT shouldBe 200
    }

    /**
     * The cross-task seam between this file and computenet-4ru.8.5, closed at feature review:
     * a sweep failure of the kind computenet-4ru.8.5 added — [RunOutcome.WavePrefixViolation] —
     * must be rendered field by field like a [RunOutcome.Mismatch] is, **never** through
     * `toString`. It carries a `script` field too, so the `else` branch dumped every event of the
     * case once per failing seed, which is the exact failure `OracleSweep.describe` exists to
     * prevent.
     *
     * Measured before the fix (2026-08-19, Darwin arm64): driving
     * `WavePrefixTest.generatedSweepConfig` (`sourceCount = 1`, `scriptLength = 30`) through
     * [OracleSweep.run] over seeds `0 until 60` printed five `WavePrefixViolation` failures at
     * ~1.9-2.0 kB each with the whole `Script(...)` inline, against ~0.57 kB and no script for the
     * two `Mismatch` failures in the same sweep. Latent on the BS-1 config above — `sourceCount =
     * 4` means [WavePrefixOracle.appliesTo] admits 0 of its 200 seeds — but [OracleSweep.run]
     * passes no [WavePrefixOption], so any single-source sweep config inherits
     * [WavePrefixOption.DEFAULT] and reaches it.
     *
     * Asserted on a fabricated outcome rather than on a generated seed deliberately: the rendering
     * is what is under test, and pinning generator seeds here would make this test fail for
     * reasons that have nothing to do with it. Every expected value below is a literal written
     * into the fabricated value, not recomputed from production code.
     */
    @Test
    fun `a sweep renders a wave-prefix violation field by field, never dumping the script`() {
        val violation = RunOutcome.WavePrefixViolation(
            seed = 46L,
            terminal = "terminal-0",
            kind = RunOutcome.WavePrefixViolation.Kind.REGRESSED,
            renderedGraphSpec = "spawn source-0 : set",
            script = Script(
                listOf(
                    SourceScript(
                        SourceId("source-0"),
                        List(30) { ScriptEvent.Add(WriterId("writer-0"), "buried-script-event-$it") },
                    ),
                ),
            ),
            observed = ModelState.SetState(setOf("a")),
            observationIndex = 7,
            matchedFloor = 4,
            regressedTo = 2,
            nearestPrefixes = mapOf(4 to ModelState.SetState(setOf("a", "b"))),
        )

        val rendered = OracleSweep.describe(violation)

        // The load-bearing half: no script, at all. One event name is enough to prove it — if the
        // script is rendered, every one of the thirty is.
        rendered shouldNotContain "buried-script-event"
        rendered shouldNotContain "script="
        // And the evidence a reader actually needs is present.
        rendered shouldContain "WavePrefixViolation("
        rendered shouldContain "terminal=terminal-0"
        rendered shouldContain "kind=REGRESSED"
        rendered shouldContain "observationIndex=7"
        rendered shouldContain "matchedFloor=4"
        rendered shouldContain "regressedTo=2"
        rendered shouldContain "spec=spawn source-0 : set"
        // A kind that carries no script is still fine through `toString`.
        OracleSweep.describe(RunOutcome.NonQuiescence(seed = 1L, stepBudget = 7)) shouldBe
            "NonQuiescence(seed=1, stepBudget=7)"
    }

    /**
     * Runs [block] with [OracleSweep.SEED_COUNT_PROPERTY] set to [value] (or cleared, for
     * `null`), restoring whatever the surrounding invocation had — a `-Poracle.seeds` run
     * really does set this property for the whole test JVM, so leaking a value here would
     * change what a *later* test in this class sweeps.
     */
    private fun withSeedProperty(value: String?, block: () -> Unit) {
        val previous = System.getProperty(OracleSweep.SEED_COUNT_PROPERTY)
        try {
            if (value == null) {
                System.clearProperty(OracleSweep.SEED_COUNT_PROPERTY)
            } else {
                System.setProperty(OracleSweep.SEED_COUNT_PROPERTY, value)
            }
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(OracleSweep.SEED_COUNT_PROPERTY)
            } else {
                System.setProperty(OracleSweep.SEED_COUNT_PROPERTY, previous)
            }
        }
    }

    private companion object {
        /**
         * The eleven entries computenet-4ru.16 unblocked, plus `keyBy` itself — the bootstrap
         * that produces the `SetOf(Tuple(2))` the other eleven consume. A topology holding any
         * one of them is a pair-shaped case.
         */
        val PAIR_SHAPED_FAMILY: Set<String> = setOf(
            CoreOperators.Ids.KEY_BY,
            CoreOperators.Ids.JOIN_SET,
            CoreOperators.Ids.SEMI_JOIN,
            CoreOperators.Ids.ANTI_JOIN,
            CoreOperators.Ids.GROUP_BY_GLOBAL,
        ) + CoreOperators.Ids.GROUP_BY_AGGREGATES

        /**
         * The same 200-seed population size `PairShapeBootstrapTest` and `GraphGeneratorTest`
         * draw, so the count printed here is on the same scale as theirs rather than a
         * different one.
         */
        const val PAIR_SHAPED_SWEEP_SEEDS: Long = 200L

        /**
         * A deliberately loose floor on how many of [PAIR_SHAPED_SWEEP_SEEDS] must carry a
         * pair-shaped node — the guard against a vacuous sweep, not a pin on the generator's
         * exact draws. See the test's KDoc for the observed figure.
         */
        const val PAIR_SHAPED_MINIMUM: Int = 10
    }
}
