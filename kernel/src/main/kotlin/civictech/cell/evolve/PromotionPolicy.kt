package civictech.cell.evolve

import civictech.cell.port.CycleHead
import civictech.cell.verify.Violation
import java.io.Serializable

/**
 * An observation window measured in observed production waves and/or
 * coverage stabilization (spec 53 "Judgment is declarative policy", decided
 * 93 I-17) — never wall-clock, never a barrier. [waves] counts waves
 * actually observed by the judge, not elapsed time.
 */
data class ObservationWindow(val waves: Int) : Serializable {
    init {
        require(waves > 0) { "an observation window must require at least one observed wave" }
    }
}

/**
 * A satisfaction criterion over the gate violations observed during the
 * [ObservationWindow] (spec 53). The strict default requires zero gate
 * violations across the whole window; a policy MAY loosen this with its own
 * grammar (e.g. an error-budget count) by supplying a different criterion.
 */
fun interface SatisfactionCriterion : Serializable {
    fun satisfies(violationCount: Int): Boolean

    companion object {
        /** Strict default (spec 53): zero gate violations over the window. */
        val ZERO_VIOLATIONS: SatisfactionCriterion = SatisfactionCriterion { count -> count == 0 }
    }
}

/**
 * `PromotionPolicy(gates, window, threshold, judge, baseline?)` — the
 * decided shape (spec 53 "Judgment is declarative policy", 93 I-17, G-50): a
 * serializable artifact beside the candidate GraphSpec so a promotion is
 * fully described by spec + policy, not by imperative caller-side checks.
 *
 * - [gates]: the names of the gate invariants that must hold (glitch-free
 *   where inlets share an upstream fork; convergence-at-quiescence across
 *   independent sources) — evaluated by [InvariantCell][civictech.cell.verify.InvariantCell]
 *   cells wired by the caller; the policy names them, it does not run them.
 * - [window]: waves observed, never wall-clock.
 * - [threshold]: the satisfaction criterion; strict default is zero
 *   violations over the window.
 * - [judge]: the name of the judge cell that evaluates this policy.
 * - [baseline]: enables the **differential shadow** — incumbent and
 *   candidate run as parallel effect-suppressed shadows tapped from the same
 *   production outlets and judged by the same gates; promote iff the
 *   candidate meets [threshold] AND is no worse than the incumbent.
 */
data class PromotionPolicy(
    val gates: List<String>,
    val window: ObservationWindow,
    val threshold: SatisfactionCriterion = SatisfactionCriterion.ZERO_VIOLATIONS,
    val judge: String,
    val baseline: Boolean = false,
) : Serializable

/**
 * The judge's verdict on a [PromotionPolicy] (spec 53). [Pending] means the
 * observation window has not yet been filled — never a rejection, never an
 * acceptance, just "ask again after more waves". A rejection always carries
 * a [Reject.reason] naming which clause failed. [Reject.terminal] distinguishes
 * a settled policy failure from a retryable gate deferral; low-level promotion
 * still refuses either kind during PRECHECK.
 */
sealed interface PromotionVerdict {
    object Pending : PromotionVerdict
    object Accept : PromotionVerdict
    data class Reject(
        val reason: String,
        val terminal: Boolean = true,
    ) : PromotionVerdict
}

/**
 * Evaluates a [PromotionPolicy] against observed waves and gate violations
 * (spec 53 "Judgment is declarative policy", G-50): the declarative
 * replacement for hand-checking `violations.shouldBeEmpty()` before calling
 * [Promotion.promote]. [Evolve.run] performs the standard composition: it
 * wires each [civictech.cell.verify.InvariantCell]'s `violations` outlet into
 * this judge and observes the candidate's production waves. Low-level callers
 * may still wire those outlets into [observeCandidateViolation] /
 * [observeIncumbentViolation] and call [observeCandidateWave] by hand.
 *
 * **Differential shadow** (policy.baseline = true): the candidate must both
 * satisfy [PromotionPolicy.threshold] on its own violation count AND be no
 * worse than the incumbent's observed violation count over the same window.
 *
 * **Cycle promotion gates on quiescence** ([cycleHead] non-null, spec 53 +
 * G-19): a cell on a live cycle may be relinked only once the cycle's delta
 * magnitude sits below the G-19 threshold ([FeedbackInlet.lastQuiescent][civictech.cell.port.FeedbackInlet.lastQuiescent] ==
 * `true`). Without confirmed G-19 throttling — no delta observed yet, or a
 * non-`Magnitude` payload — promotion is deferred, not attempted; this check
 * runs before window/threshold evaluation and short-circuits the verdict.
 */
class PromotionJudge(
    private val policy: PromotionPolicy,
    private val cycleHead: CycleHead<*>? = null,
) {
    private var candidateWaves = 0
    private var candidateViolations = 0
    private var incumbentViolations = 0
    private var settledCandidateWaves = 0
    private var settledCandidateViolations = 0
    private var settledIncumbentViolations = 0

    /** Record that the candidate has shadowed one more production wave. */
    @Synchronized
    fun observeCandidateWave() {
        candidateWaves++
    }

    /** Record a gate violation observed on the candidate's shadow. */
    @Synchronized
    fun observeCandidateViolation(violation: Violation) {
        candidateViolations++
    }

    /** Record a gate violation observed on the incumbent's shadow (differential baseline). */
    @Synchronized
    fun observeIncumbentViolation(violation: Violation) {
        incumbentViolations++
    }

    /**
     * Publish the observation prefix whose host queue has drained. An [EvolutionHandle]
     * evaluates only this prefix: a wave tap fires before the wave's consumers, so the raw
     * counters may include a wave whose invariant delivery is still queued.
     */
    @Synchronized
    internal fun settleObservation() {
        settledCandidateWaves = candidateWaves
        settledCandidateViolations = candidateViolations
        settledIncumbentViolations = incumbentViolations
    }

    /** Verdict over the last host-drained observation prefix. */
    @Synchronized
    internal fun settledVerdict(): PromotionVerdict = verdict(
        candidateWaves = settledCandidateWaves,
        candidateViolations = settledCandidateViolations,
        incumbentViolations = settledIncumbentViolations,
    )

    @Synchronized
    fun verdict(): PromotionVerdict = verdict(
        candidateWaves = candidateWaves,
        candidateViolations = candidateViolations,
        incumbentViolations = incumbentViolations,
    )

    private fun verdict(
        candidateWaves: Int,
        candidateViolations: Int,
        incumbentViolations: Int,
    ): PromotionVerdict {
        cycleHead?.let { head ->
            if (head.feedbackInput.lastQuiescent != true) {
                return PromotionVerdict.Reject(
                    "cycle promotion deferred, not attempted: the cycle has not confirmed quiescence " +
                        "under G-19 throttling yet (spec 53 §Cycle promotion gates on quiescence, 93 I-6)",
                    terminal = false,
                )
            }
        }

        if (candidateWaves < policy.window.waves) return PromotionVerdict.Pending

        if (!policy.threshold.satisfies(candidateViolations)) {
            return PromotionVerdict.Reject(
                "candidate violated the promotion policy's satisfaction criterion: " +
                    "$candidateViolations violation(s) over ${policy.window.waves} observed wave(s) " +
                    "on gates ${policy.gates}",
            )
        }

        if (policy.baseline && candidateViolations > incumbentViolations) {
            return PromotionVerdict.Reject(
                "differential shadow: candidate is worse than the incumbent baseline " +
                    "($candidateViolations violation(s) > $incumbentViolations incumbent baseline violation(s))",
            )
        }

        return PromotionVerdict.Accept
    }
}
