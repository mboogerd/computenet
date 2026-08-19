package civictech.oracle.run

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.model.ModelState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
}
