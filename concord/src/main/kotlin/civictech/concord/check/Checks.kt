package civictech.concord.check

import civictech.concord.driver.Driver
import civictech.concord.schema.Check
import civictech.concord.schema.EffectCount
import civictech.concord.schema.FinalView
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.ObservationsAllSatisfy
import civictech.concord.schema.ObservationsMonotone
import civictech.concord.schema.ReplicasConverge
import civictech.concord.schema.Scenario
import civictech.concord.schema.ViewsConverge

/**
 * The executable check vocabulary (§1.4) — the oracles that turn a declarative
 * [Check] plus one run's driver observations into a pass/fail. Implemented once
 * in the harness, in terms of driver verbs only, so no per-implementation
 * duplication (§1.4). This package is neutral: it imports `civictech.concord.*`
 * only, never `civictech.cell.*`.
 *
 * **W0 freezes the signatures; the bodies are stubs.** W1-B fills them (plus the
 * batch oracle) and unit-tests them against hand-computed fixtures. Do not change
 * a signature here without a schema-change ticket — W1-A's runner and W1-B's
 * oracle both bind to them.
 */
object Checks {

    /** Dispatch one check to its evaluator. Runner entry point (W1-A/W2 wire this in). */
    fun evaluate(check: Check, ctx: CheckContext): CheckResult = when (check) {
        is FinalView -> finalView(check, ctx)
        is ViewsConverge -> viewsConverge(check, ctx)
        is IncrementalEqualsBatch -> incrementalEqualsBatch(check, ctx)
        is LateJoinEqualsEarly -> lateJoinEqualsEarly(check, ctx)
        is ObservationsAllSatisfy -> observationsAllSatisfy(check, ctx)
        is ObservationsMonotone -> observationsMonotone(check, ctx)
        is ReplicasConverge -> replicasConverge(check, ctx)
        is NoDeadLetters -> noDeadLetters(ctx)
        is EffectCount -> effectCount(check, ctx)
    }

    /** At quiescence, `readView(view)` equals the golden value. */
    fun finalView(check: FinalView, ctx: CheckContext): CheckResult =
        stub("final-view")

    /** All listed views hold equal folds at quiescence. */
    fun viewsConverge(check: ViewsConverge, ctx: CheckContext): CheckResult =
        stub("views-converge")

    /** View equals the harness-side batch oracle over the accepted-op multiset. */
    fun incrementalEqualsBatch(check: IncrementalEqualsBatch, ctx: CheckContext): CheckResult =
        stub("incremental-equals-batch")

    /** Late-linked and early-linked views hold equal folds. */
    fun lateJoinEqualsEarly(check: LateJoinEqualsEarly, ctx: CheckContext): CheckResult =
        stub("late-join-equals-early")

    /** Every event on the observation stream satisfies the catalog predicate. */
    fun observationsAllSatisfy(check: ObservationsAllSatisfy, ctx: CheckContext): CheckResult =
        stub("observations-all-satisfy")

    /** The observation stream never regresses under the stated order. */
    fun observationsMonotone(check: ObservationsMonotone, ctx: CheckContext): CheckResult =
        stub("observations-monotone")

    /** All live replicas of the logical id hold equal folds. */
    fun replicasConverge(check: ReplicasConverge, ctx: CheckContext): CheckResult =
        stub("replicas-converge")

    /** Zero dead letters across all hosts. */
    fun noDeadLetters(ctx: CheckContext): CheckResult =
        stub("no-dead-letters")

    /** An effectful sink acted exactly N times per key. */
    fun effectCount(check: EffectCount, ctx: CheckContext): CheckResult =
        stub("effect-count")

    private fun stub(name: String): CheckResult = CheckResult.NotImplemented(name)
}

/**
 * Everything a check evaluator reads for one run of the sweep: the [driver]
 * (already advanced past the run's script and quiesced) and the [scenario] (for
 * the batch oracle, which folds catalog semantics over the script's accepted-op
 * multiset). W1-B may widen this — it is the check layer's own type.
 */
interface CheckContext {
    val driver: Driver
    val scenario: Scenario
}

/** The outcome of evaluating one [Check] on one run. */
sealed interface CheckResult {
    /** The check held. */
    data object Passed : CheckResult

    /** The check was violated; [message] states how (for the failure report). */
    data class Failed(val message: String) : CheckResult

    /** W0/W1 placeholder: the evaluator is not yet implemented ([check] names it). */
    data class NotImplemented(val check: String) : CheckResult
}
