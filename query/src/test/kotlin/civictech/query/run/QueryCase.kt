package civictech.query.run

import civictech.cell.data.SetApi
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.link.LinkResult
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.SourceId
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.CaseGraph
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.MapTerminalFold
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.ScalarTerminalFold
import civictech.oracle.run.ScriptSource
import civictech.oracle.run.SetTerminalFold
import civictech.oracle.run.TerminalFold
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.LogicalPlan
import civictech.query.plan.Planner
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.SimWorld

/**
 * The differential adapter (cab.6-D1, `[QRY1-ORA-01]`, `[QRY1-ORA-02]`): one compiled query as
 * a bring-your-own case for `civictech.oracle.run.DifferentialRunner.check`.
 *
 * Everything past building the graph and naming the reference is the runner's — driving with
 * partial drains, dead-letter accounting, the step budget, the failure taxonomy and the
 * comparison. This class re-implements none of it (`NoOwnDifferentialMachineryTest` holds that
 * line); it only says what the graph is ([buildGraph]), what the answer should be
 * ([reference]) and how a failure is named ([marker], [describe]).
 *
 * - **Graph.** [CompiledQuery.applyTo] on the world's host; one [TerminalFold] per root, chosen
 *   by its [OutputShape]; every EDB source bound through the host's **proxy**
 *   (`ManagedHost.lookup`), never a raw cell inlet, so each injected op costs scheduler steps
 *   and the runner's partial drains really interleave (cab.6-D8).
 * - **Reference.** `BatchEvaluator.evaluate` over the SAME [LogicalPlan] on each relation's
 *   live rows (`Membership.live` of its script slice). A throw is deliberately NOT caught here:
 *   the runner turns it into `RunOutcome.ModelEvaluationFailure`, which is `[QRY1-ORA-11]`'s
 *   reference-evaluation failure, distinct from a compilation defect.
 *
 * **No wave-prefix check (cab.6-D4, cab.6-D12).** The runner's BYO path does not prefix-check
 * — a `Script` carries no total drive order to take prefixes over — and this suite emulates
 * none. It asserts nothing about intermediate states: every verdict here is about quiescent
 * folds only. That is the deliberate reading of `[QRY1-ORA-01]`'s "SHALL NOT re-implement …
 * the wave-prefix check", not an omission.
 */
data class QueryCase(val source: String, val catalog: Catalog, val compiled: CompiledQuery) {

    companion object {
        /**
         * Parse → [Planner.plan] → [Lowering.lower] → [CompiledQuery.from]. A parse rejection
         * or a lowering refusal fails loudly, listing why: a case that cannot compile is a
         * defect in the test, never a differential verdict.
         */
        fun compile(source: String, catalog: Catalog): QueryCase {
            val query = when (val parsed = QueryParser.parse(source, catalog)) {
                is ParseResult.Parsed -> parsed.query
                is ParseResult.Rejected -> error("QueryCase source did not parse: ${parsed.rejections}\n$source")
            }
            val plan = Planner.plan(query)
            val lowered = when (val result = Lowering.lower(plan, catalog)) {
                is LoweringResult.Lowered -> result
                is LoweringResult.Refused -> error("QueryCase source did not lower: ${result.refusals}\n$source")
            }
            return QueryCase(source, catalog, CompiledQuery.from(lowered, plan))
        }

        /** A [RelationValue] in the runner's comparison currency (cab.6-D5). */
        fun toModelState(value: RelationValue): ModelState = when (value) {
            is RelationValue.Rows -> ModelState.SetState(value.rows)
            is RelationValue.Groups -> ModelState.MapState(value.entries)
            is RelationValue.Count -> ModelState.ScalarState(value.count)
        }
    }

    /**
     * Applies the compiled spec to [world]'s host, links one fold per root and binds one
     * script source per EDB relation. Terminals are keyed by root name; sources by
     * `SourceId(relation)`.
     */
    fun buildGraph(world: SimWorld): CaseGraph {
        val host = world.host
        val applied = compiled.applyTo(host.managementInlet)

        val terminals = compiled.outputShapes.entries.associate { (root, shape) ->
            val fold: TerminalFold = when (shape) {
                OutputShape.SET_OF_ROWS -> SetTerminalFold<Row>()
                OutputShape.MAP_BY_GROUP -> MapTerminalFold<Any?, Any?>()
                OutputShape.COUNTER -> ScalarTerminalFold()
            }
            val outputRef = applied.outputs[root]?.ref
                ?: error("Root '$root' has an OutputShape but no applied output; outputs=${applied.outputs.keys.sorted()}")
            host.managementInlet.call.spawn(fold)
            val result = host.managementInlet.call.connect(outputRef, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "Linking root '$root' to its $shape fold was rejected: ${(result as LinkResult.Rejected).reason}"
            }
            root to fold
        }

        val sources = applied.sources.entries.associate { (relation, ref) ->
            SourceId(relation) to proxySource(world, relation, ref)
        }
        return CaseGraph(terminals = terminals, sources = sources)
    }

    private fun proxySource(world: SimWorld, relation: String, ref: TypedRef<SetApi<Row>>): ScriptSource {
        val writer = world.host.lookup(ref)
            ?: error("Source '$relation' did not resolve on the host after applyTo")
        val ops = writer.inlet.call
        return object : ScriptSource {
            override fun add(element: Any?) = ops.add(element as Row)
            override fun remove(element: Any?) = ops.remove(element as Row)
        }
    }

    /**
     * `BatchEvaluator.evaluate` over [plan] (the compiled plan unless a test substitutes one)
     * on each EDB relation's live rows. No try/catch: a throw is the runner's to report
     * (`[QRY1-ORA-11]`).
     */
    fun reference(plan: LogicalPlan = compiled.plan): Reference = Reference { script ->
        val db = compiled.sourceHandles.keys.associateWith { relation ->
            Membership.live(script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as Row }
        }
        BatchEvaluator.evaluate(plan, db).mapValues { (_, value) -> toModelState(value) }
    }

    /**
     * The case marker `RunOutcome.Mismatch.renderedGraphSpec` carries (`[QRY1-ORA-09]`,
     * cab.6-D11): the query source, the [LogicalPlan] and the rendered GraphSpec, each under
     * a one-line header. The seed is not here because every failure kind already carries it.
     */
    fun marker(): String = buildString {
        appendLine("-- source")
        appendLine(source.trimEnd())
        appendLine("-- plan")
        appendLine(compiled.plan.toString())
        appendLine("-- spec")
        append(CaseExecution.renderSpec(compiled.spec))
    }

    /** One differential run through the runner's BYO path. */
    fun check(
        seed: Long,
        script: Script,
        reference: Reference = reference(),
        stepBudget: Int = DifferentialRunner.DEFAULT_STEP_BUDGET,
    ): RunOutcome = DifferentialRunner.check(seed, marker(), script, reference, stepBudget, ::buildGraph)

    /** Fails with [describe] unless the run is `RunOutcome.Success`. */
    fun assertSuccess(seed: Long, script: Script, reference: Reference = reference()) {
        val outcome = check(seed, script, reference)
        if (outcome != RunOutcome.Success) throw AssertionError(outcome.describe(this, script))
    }
}

/**
 * The failure message for [case]: the outcome kind, its seed, the terminal (for a mismatch),
 * the case marker and a replay line. A `ModelEvaluationFailure` opens with
 * `reference-evaluation failure (BatchEvaluator threw)` so it can never read as a compilation
 * defect (`[QRY1-ORA-11]`).
 *
 * [script] is needed for the replay line of kinds that do not carry one; a mismatch's own
 * script wins when present.
 */
fun RunOutcome.describe(case: QueryCase, script: Script? = null): String {
    val (seed, headline) = when (this) {
        RunOutcome.Success -> return "success"
        is RunOutcome.ModelEvaluationFailure -> seed to
            "reference-evaluation failure (BatchEvaluator threw), not a compilation defect: seed=$seed cause=$cause"
        is RunOutcome.Mismatch -> seed to
            "mismatch: seed=$seed terminal='$terminal' expected=$expected actual=$actual difference=$difference"
        is RunOutcome.NonQuiescence -> seed to "non-quiescence: seed=$seed stepBudget=$stepBudget"
        is RunOutcome.DeadLetterFailure -> seed to "dead-letter failure: seed=$seed deadLetters=$deadLetters"
        is RunOutcome.WavePrefixViolation -> seed to "wave-prefix violation: seed=$seed terminal='$terminal' kind=$kind"
        is RunOutcome.ReplicaDivergence -> seed to "replica divergence: seed=$seed logicalId=$logicalId"
        is RunOutcome.ReplicasAgreeButWrong -> seed to "replicas agree but wrong: seed=$seed logicalId=$logicalId"
    }
    val replayScript = (this as? RunOutcome.Mismatch)?.script ?: script
    return buildString {
        appendLine(headline)
        appendLine(case.marker())
        append("replay: QueryCase.compile(\"\"\"${case.source.trimEnd()}\"\"\", catalog)")
        append(".check(seed = ${seed}L, script = ${replayScript ?: "<script not supplied>"})")
    }
}
