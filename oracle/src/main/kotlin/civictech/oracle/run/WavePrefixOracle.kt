package civictech.oracle.run

import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.model.ModelState
import kotlin.random.Random

/**
 * The wave-prefix glitch-freedom oracle `[ORA1-DIFF-06]` — epic computenet-4ru design **D5**,
 * REQUIRED and never weakened to final-state equality: while a case is driven, **every
 * intermediate observation of a terminal must equal the reference model's result for SOME
 * prefix of the wave sequence, and the matched prefix index must never regress**.
 *
 * Final-state equality alone cannot see a glitch. A reconvergent graph can publish a torn
 * composite mid-wave — one arm of a diamond updated, the other not — and still settle on the
 * right answer, so a run that only compares at quiescence reports `Success` for a graph that
 * showed a state no serial execution of the inputs could produce. That is what this file
 * bounds.
 *
 * ## The construction, and where it comes from
 *
 * `kernel/src/test/kotlin/civictech/cell/consistency/InternalConsistencyTest.kt` (~371-406,
 * `Oracle` / `prefixesOf` / `every observed composite is a completed-transfer prefix, for
 * every seed`) holds the construction this generalizes: a from-scratch recompute folded
 * forward one input wave at a time into a `prefixes` list, every observed composite required
 * to equal SOME entry of it, with the matched index monotone.
 *
 * That class is `private` and lives in the **kernel test source set** over a domain-specific
 * `Transfer` type, so it is unreachable from `:oracle`'s `src/main` and cannot be reused as
 * code. The **cross-check is therefore behavioral**, not by reuse: the same shape (a
 * reconvergent diamond driven with seed-randomized partial drains), the same positive property
 * (every observation is some prefix, monotone), and the same *negative* controls (a torn
 * composite matching no prefix is rejected) — see `WavePrefixTest`, which states the
 * correspondence case by case.
 *
 * ## Prefixes: one script Op is one wave
 *
 * [prefixesOf] evaluates the reference on the script restricted to its first *i* [CaseStep.Op]s,
 * for *i* in `0..opCount`. One Op is one source delta, hence one wave, and a
 * [CaseStep.Barrier] falls out naturally (it contributes no Op, so it advances no prefix).
 * `prefixes[0]` is the empty-input answer, which is the state a freshly built graph must show.
 *
 * A [civictech.oracle.model.ScriptEvent.Observe] Op is a model-only causality statement that
 * injects nothing into the kernel, so it can leave `prefixes[i] == prefixes[i-1]`. That is
 * harmless: [Checker] matches the LOWEST admissible index, so an equal pair is one plateau
 * rather than a forced advance.
 *
 * ## Where it is sound, and where it deliberately refuses
 *
 * [appliesTo] admits **single-source, single-host** cases only, and the refusal is a
 * soundness statement rather than a convenience:
 *
 * - **Multi-source.** A total-order prefix denotes a real frontier only for one source. With
 *   two independent sources the kernel may legally have absorbed three of source A's deltas
 *   and one of source B's — a per-source frontier the total-order prefix list does not
 *   contain — so an honest check needs per-source frontiers. Filed as **computenet-2hur**;
 *   measured at review time (2026-08-19, on the sibling machine): with the guard bypassed, 4
 *   of 38 correctly-settling three-source cases produced an observation matching no
 *   total-order prefix, whose provenance (legal interleaving vs. real glitch) is undecided.
 * - **Multi-host.** A cross-host arm was measured producing mid-wave states matching no
 *   prefix; whether that is a kernel glitch or an artifact of [CaseExecution.assemble]'s
 *   bare-`Propagate` cross-host wiring is open, filed as **computenet-g25w** per epic design
 *   D10 (a defect found here is a pinned seed and a filed bead, never a fix). Multi-host is
 *   an explicit non-goal of computenet-4ru.8.5 in any case.
 *
 * Neither refusal is a weakening to final-state equality: the check is unchanged wherever it
 * is sound, and [WavePrefixOption] cannot turn it off by default (see [DEFAULT_FRACTION]).
 *
 * ## Measured coverage — what this instrument actually bounds today
 *
 * Two numbers, stated here because a fraction in a knob is not coverage:
 *
 * - **Granularity.** On a co-hosted graph every hop runs inline inside the scheduler step that
 *   started it, so the observations are one-per-wave, not one-per-hop: a 3-Op script over the
 *   BS-8 diamond produces 3 productive steps and 3 observations. This oracle therefore bounds
 *   "the terminal equals the model at every wave, never only the last, and never goes
 *   backwards"; it cannot resolve an instant *inside* one wave on one host.
 * - **Generated-path admission.** [appliesTo] rejects every multi-source config, and the
 *   configs this feature's other suites construct are all `sourceCount >= 3` — measured 0/200
 *   seeds admitted on each (`GraphSpecLinkSweepTest.sweepConfig`, `Bs16Case.CONFIG`, the BS-1
 *   sweep config; measured 2026-08-19). Coverage of the generated path therefore rests on the
 *   single-source sweep `WavePrefixTest` runs itself. Widening it to the multi-source configs
 *   needs computenet-2hur.
 * - **What that sweep found** (`WavePrefixTest.generatedSweepConfig`, seeds 0..59, ordinary
 *   `writerCount = 2` / `unobservedRemoveRatio = 0.25` knobs, Darwin arm64, 2026-08-19): 60/60
 *   admitted, 47/60 carrying a source-to-terminal pair joined by two paths with *different*
 *   operator sequences, and **48/60 prefix-clean**. The other twelve are pinned seed lists in
 *   that file, not silence: five already `Mismatch` at quiescence with checking OFF, five
 *   REGRESSED across a wave the model did not change, one a single-path chain showing a state
 *   that is no prefix, one a reconvergent shape showing a state that is no prefix.
 *
 *   **The pinned twelve are one population, and it is the known cross-writer seam.** Measured
 *   at review time (2026-08-19, Darwin arm64, this same config and seed range): with
 *   `writerCount = 1` the sweep is **60/60 clean — no `Mismatch` and no violation at all** —
 *   while `unobservedRemoveRatio = 0.0` at `writerCount = 2` removes neither (it only
 *   re-shuffles which seeds carry them: 9 mismatches and 6 violations). So the discriminating
 *   variable is the second writer, i.e. the pre-existing cross-writer remove seam
 *   (computenet-qcm1, computenet-4ru.6.3: a spawned `SetCell` retracts a live element on any
 *   remove while the model no-ops a cross-writer remove no `Observe` preceded). The five
 *   `Mismatch` seeds are the cases where that divergence survives to quiescence; the seven
 *   violation seeds are the cases where it appears at an intermediate wave and *heals* before
 *   the end. **Catching those seven is this instrument's contribution** — the final-state
 *   comparison cannot see them at all.
 *
 *   **What the violations are NOT: an artifact of this observation point.** A [TerminalFold]
 *   applies each delta as it arrives, while `InternalConsistencyTest` reads an *aligned* sink
 *   ("the aligned sink buffers a wave's deltas and applies them together, so this particular
 *   flicker is invisible at the sink") — a real difference between the two read paths, but not
 *   the explanation here, and an earlier session's own probe had already discarded it. Two
 *   measurements, both at review time: (i) re-driving each violating seed with a `Barrier`
 *   after *every* Op and inspecting ONLY the `onBarrier` states — read after `drainToIdle()`,
 *   where a raw fold and an aligned sink hold the same value by construction — reproduces every
 *   violation, 1 to 13 offending states per seed, several persisting across consecutive
 *   quiesced boundaries; (ii) the granularity bullet above is why: at one productive step per
 *   wave, essentially every observation this oracle takes is already a quiesced wave boundary,
 *   so "the dip happened inside one wave" cannot be the mechanism for any of them. Treat the
 *   pinned seeds as the seam surfacing earlier, not as a limit of this instrument — and do not
 *   close the gap by weakening the check (D5).
 */
object WavePrefixOracle {

    /**
     * The fraction of eligible cases prefix-checked when a caller names no [WavePrefixOption]
     * — **nonzero by construction**, which is D5's floor: prefix checking may be narrowed for
     * `[ORA1-PERF-01]`, never dropped. Turning it off is an explicit
     * [WavePrefixOption.OFF] at a call site, never a default.
     *
     * Prefix checking costs one model evaluation per Op (the prefix list) plus one terminal
     * read per productive scheduler step, so a fully-checked sweep is several times the cost
     * of a plain drain — which is why the default is a documented subset rather than all.
     *
     * Read together with the admission numbers above: 0.25 is the fraction of *eligible*
     * cases, and eligibility is what [appliesTo] decides.
     */
    const val DEFAULT_FRACTION: Double = 0.25

    /** Whether the prefix check is sound for [case] — see [notApplicableBecause]. */
    fun appliesTo(case: GeneratedCase): Boolean = notApplicableBecause(case) == null

    /**
     * Why the prefix check does not apply to [case], or `null` if it does.
     *
     * A *reason*, not a boolean, so a sweep that skips a case can say which soundness limit it
     * hit and against which filed bead — a silent skip would make a zero-coverage run
     * indistinguishable from a clean one, which is the failure mode this epic exists to avoid.
     */
    fun notApplicableBecause(case: GeneratedCase): String? {
        val sources = case.topology.nodes.count { it.source != null }
        if (sources != 1) {
            return "the case drives $sources sources; a total-order prefix denotes a real " +
                "frontier only for a single source, so multi-source needs per-source " +
                "frontiers (computenet-2hur)"
        }
        val hosts = case.topology.placement.values.filter { it != 0 }.distinct()
        if (hosts.isNotEmpty()) {
            return "the case places cells on host ordinals ${hosts.sorted()} besides 0; " +
                "cross-host arms were measured publishing mid-wave states matching no prefix, " +
                "of undecided provenance (computenet-g25w)"
        }
        return null
    }

    /**
     * The prefix list for [script] under [reference]: `prefixesOf(...)[i]` is every terminal's
     * modelled state after the script's first *i* [CaseStep.Op]s, for *i* in `0..opCount`.
     *
     * Computed once per case — `opCount + 1` model evaluations — because the comparison is
     * `O(prefixes x observations)` and re-evaluating per observation would make it
     * `O(prefixes x observations x scriptLength)`.
     *
     * @throws Throwable whatever [reference] throws. The caller ([DifferentialRunner.run])
     *   turns that into [RunOutcome.ModelEvaluationFailure]: a reference that cannot evaluate a
     *   prefix is a broken oracle, never a broken kernel (D10, `[ORA1-DIFF-08]`).
     */
    fun prefixesOf(script: CaseScript, reference: Reference): List<Map<String, ModelState>> {
        val ops = script.steps.filterIsInstance<CaseStep.Op>()
        return (0..ops.size).map { i -> reference.evaluate(CaseScript(ops.take(i)).toScript()) }
    }

    /** A [Checker] over [case]'s script, with the prefix list computed by [prefixesOf]. */
    fun checker(case: GeneratedCase, caseMarker: String, reference: Reference): Checker =
        Checker(case.seed, caseMarker, case.script, prefixesOf(case.script, reference))

    /**
     * The running check: hand it every intermediate observation, in order, and it answers
     * `null` (admissible) or the [RunOutcome.WavePrefixViolation] that observation is.
     *
     * ## The floor is the whole non-regression property
     *
     * Per terminal it keeps the index of the lowest prefix that terminal has already matched,
     * and searches only `floor..lastIndex` for the next observation. An observation that
     * matches only a prefix *below* the floor is therefore not "found later" — it is reported
     * as [RunOutcome.WavePrefixViolation.Kind.REGRESSED], with the index it went back to. That
     * search bound is load-bearing and is mutation-checked in `WavePrefixTest`: widening it to
     * `0..lastIndex` makes the regression control pass, which is exactly the vacuous check
     * this property must not degrade into.
     *
     * Per terminal, not per run: two terminals of one case advance independently, and requiring
     * a shared floor would reject a legal run in which one arm of the graph is simply ahead.
     *
     * A terminal appearing for the first time part-way through (a late joiner linked at a
     * [CaseStep.Barrier]) starts at floor 0 and catches up monotonically, which is admissible
     * and is what `[24-CATCHUP-01]` requires of it.
     */
    class Checker internal constructor(
        private val seed: Long,
        private val caseMarker: String,
        private val script: CaseScript,
        /** `prefixes[i]` = every terminal's modelled state after the first *i* Ops. */
        val prefixes: List<Map<String, ModelState>>,
    ) {
        private val floors = LinkedHashMap<String, Int>()

        /** How many observations have been offered — a run's non-vacuity witness. */
        var observations: Int = 0
            private set

        /** How many terminal states have been compared, across all observations. */
        var comparisons: Int = 0
            private set

        /** [terminal]'s current matched-prefix floor, or `null` if it has never matched one. */
        fun floorOf(terminal: String): Int? = floors[terminal]

        /**
         * One intermediate observation — every terminal's fold at one instant, read through the
         * terminals' own views (never cell internals; see
         * [DifferentialRunner.Driving.readTerminals]).
         *
         * Returns the first violation among the terminals, or `null` if every one of them is
         * admissible. Terminals are checked in the observation's own iteration order, which is
         * the case's terminal declaration order.
         */
        fun observe(states: Map<String, ModelState>): RunOutcome.WavePrefixViolation? {
            observations++
            states.forEach { (terminal, state) ->
                val violation = observeTerminal(terminal, state)
                if (violation != null) return violation
            }
            return null
        }

        /**
         * One terminal's observed [state]. Advances that terminal's floor on a match; otherwise
         * reports [RunOutcome.WavePrefixViolation.Kind.REGRESSED] if the state is some *earlier*
         * prefix and [RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX] if it is no prefix
         * at all.
         *
         * Public so a test can feed the checker a fabricated observation stream directly — which
         * is how the torn and regressing controls discriminate without needing a kernel that
         * actually glitches.
         */
        fun observeTerminal(terminal: String, state: ModelState): RunOutcome.WavePrefixViolation? {
            comparisons++
            val floor = floors[terminal] ?: 0
            val matched = (floor..prefixes.lastIndex).firstOrNull { prefixes[it][terminal] == state }
            if (matched != null) {
                floors[terminal] = matched
                return null
            }
            val regressedTo = (0 until floor).firstOrNull { prefixes[it][terminal] == state }
            return RunOutcome.WavePrefixViolation(
                seed = seed,
                terminal = terminal,
                kind = if (regressedTo == null) {
                    RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
                } else {
                    RunOutcome.WavePrefixViolation.Kind.REGRESSED
                },
                renderedGraphSpec = caseMarker,
                script = script.toScript(),
                observed = state,
                observationIndex = observations,
                matchedFloor = floor,
                regressedTo = regressedTo,
                nearestPrefixes = nearestPrefixes(terminal, floor),
            )
        }

        /**
         * [terminal]'s modelled state at the floor and at the floor's successor — the two
         * prefixes a torn observation sits between, which is the evidence a reader needs and
         * the whole prefix list is not.
         */
        private fun nearestPrefixes(terminal: String, floor: Int): Map<Int, ModelState> {
            val nearest = LinkedHashMap<Int, ModelState>()
            listOf(floor, floor + 1).forEach { index ->
                prefixes.getOrNull(index)?.get(terminal)?.let { nearest[index] = it }
            }
            return nearest
        }
    }
}

/**
 * The **runner-level** knob `[ORA1-PERF-01]` allows for prefix checking's cost: which fraction
 * of eligible cases get checked.
 *
 * Deliberately a runner option and not a `civictech.oracle.gen.GeneratorConfig` field. Prefix
 * checking changes how a case is *observed*, not what case is generated: the same
 * `(seed, config)` pair must produce the same [civictech.oracle.gen.GeneratedCase] whether or
 * not anybody prefix-checks it (`[ORA1-GEN-01]`), and putting the knob in the config would make
 * the corpus depend on the observation policy. `GeneratorConfig` also belongs to
 * computenet-4ru.6, not here.
 *
 * ## Selection is a pure function of the case seed
 *
 * [selects] hashes the seed rather than counting cases, so which cases get checked does not
 * depend on sweep order, sweep width, or how many JVMs a sweep is split across — the same seed
 * is checked or not checked identically everywhere. A counter-based "every fourth case" would
 * silently re-partition when a sweep range changed, and a shrink loop replaying one seed could
 * not reproduce the check that found the counterexample.
 *
 * @property fraction the fraction of eligible cases to check, in `0.0..1.0`. `0.0` disables
 *   prefix checking (final-state comparison is untouched); `1.0` checks every eligible case.
 *   The **default** is [WavePrefixOracle.DEFAULT_FRACTION], which is nonzero — D5 permits
 *   narrowing, never dropping.
 */
data class WavePrefixOption(val fraction: Double) {

    init {
        require(fraction in 0.0..1.0) { "WavePrefixOption.fraction must be in 0.0..1.0: $fraction" }
    }

    /** Whether the case with this [seed] is prefix-checked. Pure in [seed]. */
    fun selects(seed: Long): Boolean = when {
        fraction >= 1.0 -> true
        fraction <= 0.0 -> false
        else -> Random(seed xor SELECTION_SALT).nextDouble() < fraction
    }

    companion object {
        /**
         * A salt, so selection does not correlate with any other seed-derived stream a case
         * already has (`GeneratedCase.controllerSeed`, the injection interleaving). Without it,
         * "which cases are prefix-checked" and "which schedules those cases run under" would be
         * drawn from the same bits.
         */
        private const val SELECTION_SALT: Long = 0x57415645_50524658L // "WAVEPRFX"

        /**
         * The default: [WavePrefixOracle.DEFAULT_FRACTION] of eligible cases. Nonzero by
         * construction (D5) — a caller who wants no prefix checking asks for [OFF] explicitly.
         */
        val DEFAULT: WavePrefixOption = WavePrefixOption(WavePrefixOracle.DEFAULT_FRACTION)

        /** Every eligible case. What a targeted test of the property itself wants. */
        val ALWAYS: WavePrefixOption = WavePrefixOption(1.0)

        /**
         * No prefix checking at all — final-state comparison, dead-letter accounting and the
         * step budget are untouched. An explicit caller choice, never a default.
         */
        val OFF: WavePrefixOption = WavePrefixOption(0.0)
    }
}
