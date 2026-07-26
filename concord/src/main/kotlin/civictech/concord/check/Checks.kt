package civictech.concord.check

import civictech.concord.driver.Driver
import civictech.concord.oracle.BatchOracle
import civictech.concord.oracle.Functions
import civictech.concord.oracle.OracleUnsupported
import civictech.concord.oracle.Values
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
    fun finalView(check: FinalView, ctx: CheckContext): CheckResult {
        val actual = ctx.driver.readView(check.view)
        val type = viewType(ctx.scenario, check.view)
        return if (Values.equalForView(actual, check.expected, type)) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("final-view(${check.view}): expected ${Values.render(check.expected)} but read ${Values.render(actual)}")
        }
    }

    /** All listed views hold equal folds at quiescence. */
    fun viewsConverge(check: ViewsConverge, ctx: CheckContext): CheckResult {
        if (check.views.size < 2) return CheckResult.Passed
        val ref = check.views.first()
        val refType = viewType(ctx.scenario, ref)
        val refVal = ctx.driver.readView(ref)
        for (other in check.views.drop(1)) {
            val v = ctx.driver.readView(other)
            if (!Values.equalForView(refVal, v, refType)) {
                return CheckResult.Failed("views-converge: $ref=${Values.render(refVal)} but $other=${Values.render(v)}")
            }
        }
        return CheckResult.Passed
    }

    /** View equals the harness-side batch oracle over the accepted-op multiset. */
    fun incrementalEqualsBatch(check: IncrementalEqualsBatch, ctx: CheckContext): CheckResult {
        val oracle = try {
            BatchOracle(ctx.scenario)
        } catch (e: OracleUnsupported) {
            return CheckResult.Failed("incremental-equals-batch: oracle cannot model this scenario — ${e.message}")
        }
        val targets = if (check.view == "*") oracle.allViewValues().keys.toList() else listOf(check.view)
        for (viewId in targets) {
            val expected = try {
                oracle.view(viewId)
            } catch (e: OracleUnsupported) {
                return CheckResult.Failed("incremental-equals-batch($viewId): ${e.message}")
            }
            val actual = ctx.driver.readView(viewId)
            if (!Values.equalForView(actual, expected, viewType(ctx.scenario, viewId))) {
                return CheckResult.Failed(
                    "incremental-equals-batch($viewId): oracle=${Values.render(expected)} but read=${Values.render(actual)}",
                )
            }
        }
        return CheckResult.Passed
    }

    /**
     * Late-linked and early-linked views hold equal folds. When [check] names the
     * two views explicitly they are compared; otherwise the two view cells of the
     * graph are inferred (a two-view late-join scenario).
     */
    fun lateJoinEqualsEarly(check: LateJoinEqualsEarly, ctx: CheckContext): CheckResult {
        val views = viewCells(ctx.scenario)
        val earlyId = check.early ?: views.getOrNull(0)
        val lateId = check.late ?: views.getOrNull(1)
        if (earlyId == null || lateId == null) {
            return CheckResult.Failed("late-join-equals-early: could not identify early/late views (name them explicitly)")
        }
        val e = ctx.driver.readView(earlyId)
        val l = ctx.driver.readView(lateId)
        return if (Values.equalForView(e, l, viewType(ctx.scenario, earlyId))) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("late-join-equals-early: early $earlyId=${Values.render(e)} but late $lateId=${Values.render(l)}")
        }
    }

    /** Every event on the observation stream satisfies the catalog predicate. */
    fun observationsAllSatisfy(check: ObservationsAllSatisfy, ctx: CheckContext): CheckResult {
        val predicate = Functions.predicate(check.fn)
        val log = ctx.driver.observationLog(check.view)
        val offending = log.withIndex().firstOrNull { !predicate(it.value) }
            ?: return CheckResult.Passed
        return CheckResult.Failed(
            "observations-all-satisfy(${check.view}, ${check.fn}): event #${offending.index} ${Values.render(offending.value)} fails the predicate",
        )
    }

    /** The observation stream never regresses under the stated order. */
    fun observationsMonotone(check: ObservationsMonotone, ctx: CheckContext): CheckResult {
        val log = ctx.driver.observationLog(check.view)
        val decreasing = check.order?.lowercase()?.let { it.startsWith("desc") || it.startsWith("non-inc") } ?: false
        for (i in 1 until log.size) {
            val step = Values.compare(log[i - 1], log[i])
            val regressed = if (decreasing) step < 0 else step > 0
            if (regressed) {
                return CheckResult.Failed(
                    "observations-monotone(${check.view}): event #$i ${Values.render(log[i])} regresses from ${Values.render(log[i - 1])}",
                )
            }
        }
        return CheckResult.Passed
    }

    /** All live replicas of the logical id hold equal folds. */
    fun replicasConverge(check: ReplicasConverge, ctx: CheckContext): CheckResult {
        val replicas = ctx.scenario.graph?.cells.orEmpty().filter { it.replicaOf == check.logical }.map { it.id }
        if (replicas.size < 2) return CheckResult.Passed
        val ref = replicas.first()
        val refType = viewType(ctx.scenario, ref)
        val refVal = ctx.driver.readView(ref)
        for (other in replicas.drop(1)) {
            val v = ctx.driver.readView(other)
            if (!Values.equalForView(refVal, v, refType)) {
                return CheckResult.Failed(
                    "replicas-converge(${check.logical}): $ref=${Values.render(refVal)} but $other=${Values.render(v)}",
                )
            }
        }
        return CheckResult.Passed
    }

    /** Zero dead letters across all hosts. */
    fun noDeadLetters(ctx: CheckContext): CheckResult {
        val dls = ctx.driver.deadLetters()
        return if (dls.isEmpty()) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("no-dead-letters: ${dls.size} dead letter(s), first: ${dls.first().reason}")
        }
    }

    /**
     * An effectful sink acted exactly N times per key. When [EffectCount.key] is
     * given, only that key is asserted; otherwise every distinct key the sink
     * produced must have exactly the stated count.
     */
    fun effectCount(check: EffectCount, ctx: CheckContext): CheckResult {
        val effects = ctx.driver.effectLog(check.sink)
        val byKey: Map<String?, Int> = effects.groupingBy { it.key }.eachCount()
        val relevant = if (check.key != null) mapOf(check.key to (byKey[check.key] ?: 0)) else byKey
        if (relevant.isEmpty() && check.exactly != 0) {
            return CheckResult.Failed("effect-count(${check.sink}): expected ${check.exactly} per key but the sink produced no effects")
        }
        for ((key, count) in relevant) {
            if (count != check.exactly) {
                return CheckResult.Failed("effect-count(${check.sink}, key=$key): expected ${check.exactly} but observed $count")
            }
        }
        return CheckResult.Passed
    }

    // --- helpers ------------------------------------------------------------

    /** The catalog type of a cell (used to pick order-sensitive vs order-insensitive comparison). */
    private fun viewType(scenario: Scenario, cellId: String): String? =
        scenario.graph?.cells?.firstOrNull { it.id == cellId }?.type

    /** The ids of the graph's terminal view cells, in declaration order. */
    private fun viewCells(scenario: Scenario): List<String> =
        scenario.graph?.cells.orEmpty().filter { it.type in Values.VIEW_TYPES }.map { it.id }
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
