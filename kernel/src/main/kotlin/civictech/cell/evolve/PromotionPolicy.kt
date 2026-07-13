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
 * a [Reject.reason] naming which clause failed.
 */
sealed interface PromotionVerdict {
    object Pending : PromotionVerdict
    object Accept : PromotionVerdict
    data class Reject(val reason: String) : PromotionVerdict
}

/**
 * Evaluates a [PromotionPolicy] against observed waves and gate violations
 * (spec 53 "Judgment is declarative policy", G-50): the declarative
 * replacement for hand-checking `violations.shouldBeEmpty()` before calling
 * [Promotion.promote]. A judge is a plain accumulator, not a cell — wire an
 * [civictech.cell.verify.InvariantCell]'s `violations` outlet (or several,
 * one per named gate) into [observeCandidateViolation] /
 * [observeIncumbentViolation], and call [observeCandidateWave] once per
 * production wave the candidate has shadowed.
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

    /** Record that the candidate has shadowed one more production wave. */
    fun observeCandidateWave() {
        candidateWaves++
    }

    /** Record a gate violation observed on the candidate's shadow. */
    fun observeCandidateViolation(violation: Violation) {
        candidateViolations++
    }

    /** Record a gate violation observed on the incumbent's shadow (differential baseline). */
    fun observeIncumbentViolation(violation: Violation) {
        incumbentViolations++
    }

    fun verdict(): PromotionVerdict {
        cycleHead?.let { head ->
            if (head.feedbackInput.lastQuiescent != true) {
                return PromotionVerdict.Reject(
                    "cycle promotion deferred, not attempted: the cycle has not confirmed quiescence " +
                        "under G-19 throttling yet (spec 53 §Cycle promotion gates on quiescence, 93 I-6)",
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
