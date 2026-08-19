package civictech.oracle.run

import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.testkit.forEachSeed

/**
 * A **seed sweep** over the generated-case path: generate `(seed, config)` with
 * [CaseGenerator], execute it with [DifferentialRunner.run], and report what the whole range
 * concluded — never what its first failing seed concluded.
 *
 * ## Every seed runs; the report is a density `[ORA1-DIFF-03]`
 *
 * The loop is `civictech.testkit.forEachSeed`, not a hand-rolled `for` with an assertion in
 * it. That is the whole point: `forEachSeed` runs **every** seed regardless of earlier
 * failures and rethrows one summary — `"failed on N of M seeds; first: seed=K — ..."` — with
 * the first failure as cause. A fail-fast loop would report "seed 27 disagrees" where the
 * honest reading is "4 of 200 disagree", and those two are different findings: the first looks
 * like a pinned counterexample, the second like a systematic seam. This object does not
 * reimplement that behavior and must not: `forEachSeed` IS the required density form.
 *
 * Because `forEachSeed`'s summary quotes only the **first** failure's message, [run] also
 * prints each failing seed as it happens (see [describe]). Without that, enumerating the other
 * failing seeds costs a second sweep with an ad-hoc collector — which is exactly what a prior
 * session on this task had to do.
 *
 * ## Progress `[ORA1-PERF-03]`
 *
 * [run] calls [onProgress] once per seed, **before** that seed's case is generated or
 * executed, with the seed and its 1-based index in the sweep. The default implementation
 * prints to stdout, which is all the requirement needs: a sweep that hangs must be
 * attributable to a specific case, and the last line printed names it. A caller that wants the
 * progress stream as data (a test, a future reporter) passes its own lambda.
 *
 * ## Budget `[ORA1-PERF-01]` / `[ORA1-PERF-02]`
 *
 * The default range is `0 until `[DEFAULT_SEED_COUNT] — **200 seeds** — sized from a
 * measurement, not from a guess. Measured on this repo's BS-1 configuration
 * (`OracleSweepTest.baselineConfig`: depth 1..4, 4 sources, the six set-algebra ids, 200
 * script ops, 2 terminals, 1 writer) on an Apple-silicon laptop, 2026-08-19:
 *
 * - **~3 ms per seed** wall clock, generation and execution together: 679 ms for the 200-seed
 *   range when BS-1 runs alone (3.4 ms/seed, coldest case), 545 ms when it runs after the rest
 *   of `OracleSweepTest` (2.7 ms/seed, warmed JIT), 817 ms at `-Poracle.seeds=400`
 *   (2.0 ms/seed).
 * - So the default range costs **well under a second**, a small fraction of
 *   `./gradlew :oracle:test`, and is comfortably inside the repo's normal per-module test
 *   budget — as is a 10x widening, should a local run want one.
 *
 * Treat the per-seed figure as the order of magnitude it is, and do not quote it as a
 * cross-machine constant: it was measured in one JVM on one machine (another machine measured
 * ~14 ms/seed at this same config on 2026-08-18, a 4x spread), and it is dominated by script
 * length and topology depth, so a config change invalidates it. `OracleSweepTest`'s BS-1 test
 * prints the figure it actually observed on every run, so the numbers above are checkable
 * rather than folklore.
 *
 * `-Poracle.seeds=N` widens (or narrows) the sweep with no source change:
 * `oracle/build.gradle.kts` forwards it to the test JVM as the [SEED_COUNT_PROPERTY] system
 * property and [defaultSeedCount] reads it. `N` is a seed **count**, so `-Poracle.seeds=1000`
 * means seeds `0..999`; the range always starts at 0 so a widened sweep is a superset of the
 * default one and a failing seed keeps its identity.
 *
 * ## Known taxonomy edge a sweep failure must be read against
 *
 * If a case exhausts its step budget **mid-script**, [DifferentialRunner]'s shared core stops
 * driving and, when nothing is left to step at that instant, compares the *partial* run
 * against the *full*-script model — reporting [RunOutcome.Mismatch] rather than
 * [RunOutcome.NonQuiescence]. So a `Mismatch` in a sweep is only kernel evidence when the run
 * was not budget-starved. The fix belongs in [DifferentialRunner] (a sibling task owns that
 * file), not here; what this file owns is saying so next to the density number, so nobody
 * reads a budget-starved seed as a kernel disagreement. At BS-1 size the default budget
 * ([DifferentialRunner.DEFAULT_STEP_BUDGET], 200 000 steps) is not remotely approached, so the
 * edge is not reachable from the default sweep — but a caller who lowers [run]'s `stepBudget`
 * moves into it.
 *
 * ## Non-goals
 *
 * The wave-prefix subset and its own knob, late-joiner and multi-host sweep configurations,
 * and divergence/mutation controls all belong to their own tasks. This object is the plain
 * "run the range and report the density" driver they can each reuse.
 */
object OracleSweep {

    /**
     * The system property `oracle/build.gradle.kts` forwards `-Poracle.seeds` into, read by
     * [defaultSeedCount]. Named after the Gradle property so the two are searchable together.
     */
    const val SEED_COUNT_PROPERTY: String = "oracle.seeds"

    /**
     * The default number of seeds a sweep runs — chosen from the measurement recorded in this
     * object's KDoc (~3 ms per seed at BS-1 size on the machine that measured it, so under a
     * second for the whole range).
     */
    const val DEFAULT_SEED_COUNT: Int = 200

    /**
     * Where a sweep currently is: [seed] is the seed being generated and executed, [index] its
     * 1-based position in the sweep, [total] the number of seeds the sweep will run.
     *
     * [toString] is the line the default progress reporter prints, so a caller that wants the
     * default format with a different sink writes `onProgress = { logger.info(it.toString()) }`.
     */
    data class Progress(val seed: Long, val index: Int, val total: Int) {
        override fun toString(): String = "[oracle-sweep] case $index/$total seed=$seed"
    }

    /**
     * The seed count this JVM sweeps by default: [SEED_COUNT_PROPERTY] when it holds a
     * positive integer, [DEFAULT_SEED_COUNT] otherwise.
     *
     * A malformed or non-positive value falls back **loudly** (a printed line) rather than
     * throwing: a typo in a local `-Poracle.seeds` should not look like a test failure, and it
     * must not silently run a sweep of a size nobody asked for either.
     */
    fun defaultSeedCount(): Int {
        val raw = System.getProperty(SEED_COUNT_PROPERTY) ?: return DEFAULT_SEED_COUNT
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null || parsed <= 0) {
            println(
                "[oracle-sweep] ignoring -P$SEED_COUNT_PROPERTY=$raw (not a positive integer); " +
                    "sweeping the default $DEFAULT_SEED_COUNT seeds",
            )
            return DEFAULT_SEED_COUNT
        }
        return parsed
    }

    /** The default seed range: `0 until `[defaultSeedCount]. Always anchored at 0 — see the KDoc. */
    fun defaultSeeds(): LongRange = 0L until defaultSeedCount().toLong()

    /**
     * Sweeps [seeds], generating each case from [config] and executing it, and throws
     * `forEachSeed`'s one density summary if any seed did not reach [RunOutcome.Success].
     *
     * [config] is validated against [civictech.oracle.bind.OperatorCatalog] **once**, at the
     * single [CaseGenerator] this function constructs, rather than per seed — so a vocabulary
     * naming an unregistered id fails before the first case instead of 200 times.
     *
     * A seed "fails" when its outcome is anything other than [RunOutcome.Success], and also
     * when generation or execution *throws* (an unresolvable catalog id, a wiring bug in a
     * generated case): `forEachSeed` collects throwables, so both land in the same density
     * number instead of aborting the range.
     *
     * @param config the generator configuration every case in the sweep is drawn from.
     * @param seeds the seeds to run. Defaults to [defaultSeeds], i.e. the `-Poracle.seeds`
     *   knob; pass an explicit range for a focused sweep that should not follow the knob.
     * @param stepBudget each case's step budget, spent inside [DifferentialRunner.run]. Read
     *   this object's "Known taxonomy edge" KDoc before lowering it.
     * @param reference the substitutable oracle, forwarded to [DifferentialRunner.run]; `null`
     *   (the default) resolves each case's reference from the catalog, which is what a real
     *   sweep wants. A non-null value is the divergence-control seam — a deliberately wrong
     *   reference must make the sweep report failures — and is how this file's own
     *   density test provokes failures without needing a broken kernel.
     * @param onProgress called once per seed before that seed runs `[ORA1-PERF-03]`. Defaults
     *   to printing [Progress] to stdout.
     */
    fun run(
        config: GeneratorConfig,
        seeds: LongRange = defaultSeeds(),
        stepBudget: Int = DifferentialRunner.DEFAULT_STEP_BUDGET,
        reference: Reference? = null,
        onProgress: (Progress) -> Unit = { println(it) },
    ) {
        val generator = CaseGenerator(config)
        val total = (seeds.last - seeds.first + 1L).coerceAtLeast(0L).toInt()
        var index = 0
        forEachSeed(seeds) { seed ->
            index++
            onProgress(Progress(seed, index, total))
            val case = generator.generate(seed)
            val outcome = DifferentialRunner.run(case, reference = reference, stepBudget = stepBudget)
            if (outcome != RunOutcome.Success) {
                val report = "seed=$seed (case $index/$total): ${describe(outcome)}"
                // Printed as well as thrown: forEachSeed's summary quotes only the FIRST
                // failure, so without this line the other failing seeds of a density are
                // invisible and cost a second sweep to enumerate.
                println("[oracle-sweep] FAILED $report")
                throw AssertionError(report)
            }
        }
    }

    /**
     * One line describing a non-success outcome.
     *
     * **Every script-carrying kind is rendered field by field rather than by `toString`**, and
     * that is the whole point of this function: a `script` field holds every event of the case
     * (200 at BS-1 size), and printing it once per failing seed buries the evidence — the fields
     * that say *what* disagreed — in kilobytes of replayable input. The script is recoverable
     * from the seed and the config, which is exactly why the seed is what a failure carries.
     *
     * Two kinds carry one: [RunOutcome.Mismatch] and [RunOutcome.WavePrefixViolation]. The
     * remaining kinds ([RunOutcome.NonQuiescence], [RunOutcome.DeadLetterFailure],
     * [RunOutcome.ModelEvaluationFailure]) hold no script, so `toString` is the honest rendering
     * for them — a new sealed kind that *does* carry one needs a branch here, not the `else`.
     *
     * Measured at feature-review time (2026-08-19, Darwin arm64) on the single-source config
     * `WavePrefixTest.generatedSweepConfig` at `scriptLength = 30`, seeds `0 until 60`: the
     * `else` branch rendered each of the five `WavePrefixViolation` seeds at ~1.9–2.0 kB with the
     * whole `Script(...)` inline, against ~0.57 kB and no script for the `Mismatch` seeds through
     * the branch above. Not reachable from the BS-1 sweep — `sourceCount = 4` admits no prefix
     * checking at all ([WavePrefixOracle.appliesTo]; measured 0 of 200 seeds) — but reachable
     * from any single-source sweep config, because [run] passes no [WavePrefixOption] and so
     * inherits [WavePrefixOption.DEFAULT].
     *
     * `internal` rather than private so `OracleSweepTest` can assert the rendering directly,
     * without depending on which generated seeds happen to violate.
     */
    internal fun describe(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Mismatch ->
            "Mismatch(terminal=${outcome.terminal}, difference=${outcome.difference}, " +
                "expected=${outcome.expected}, actual=${outcome.actual}, " +
                "spec=${outcome.renderedGraphSpec})"

        is RunOutcome.WavePrefixViolation ->
            "WavePrefixViolation(terminal=${outcome.terminal}, kind=${outcome.kind}, " +
                "observationIndex=${outcome.observationIndex}, matchedFloor=${outcome.matchedFloor}, " +
                "regressedTo=${outcome.regressedTo}, observed=${outcome.observed}, " +
                "nearestPrefixes=${outcome.nearestPrefixes}, spec=${outcome.renderedGraphSpec})"

        else -> outcome.toString()
    }
}
