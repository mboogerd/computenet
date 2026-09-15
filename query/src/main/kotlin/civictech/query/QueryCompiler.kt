package civictech.query

import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.diag.CompileResult
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringRefusal
import civictech.query.lower.LoweringResult
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.parse.RuleGraph
import civictech.query.parse.SafetyAnalysis
import civictech.query.parse.SpanTable
import civictech.query.parse.WellFormednessAnalysis
import civictech.query.parse.WellFormednessAnalysis.Statement
import civictech.query.plan.BagSemantics
import civictech.query.plan.Planner
import civictech.query.run.CompiledQuery
import civictech.query.schema.Catalog

/**
 * The compiler front door (epic computenet-cab §2.3, cab.5-D10): source text or a [Query] in,
 * [CompileResult] out. **Total** ([QRY1-REJECT-03]): every input returns `Compiled` or
 * `Rejected`; no phase is allowed to throw on a query an earlier phase could have rejected.
 * A rejection is always whole-query: there is no partial, approximate, or best-effort
 * `CompiledQuery` for an input any phase below refuses ([QRY1-REJECT-01]) — the accumulator
 * this file builds only ever feeds a single `Rejected(all)` or the one `Compiled`, never a
 * mix of the two.
 *
 * **Phases (cab.5-D2).** parse → well-formedness ([WellFormednessAnalysis]) → safety
 * ([SafetyAnalysis]) → `ALL` set operations ([BagSemantics.refuseAllSetOps]) → plan
 * ([Planner]) → plan-level semantics ([BagSemantics.refuseLossyAggregates]) → lowering
 * ([Lowering]). Every phase
 * appends to one rejection list, and the result is `Rejected(all)` iff that list is non-empty
 * ([QRY1-REJECT-10]).
 *
 * **Exclusion.** A statement rejected by a phase, and every statement that transitively
 * references the head predicate of an excluded statement, is removed from the query the later
 * phases see; the exclusion is not itself a rejection. That is what lets planning and lowering
 * still run — and report their own independent rejections — over the rest of a query the
 * planner could not have planned whole. A head that is also a catalog relation is not tainted
 * by exclusion: a reference to it resolves to the catalog schema, never to the rejected rule
 * (the planner scans a declared relation before it looks for a head). Every statement whose
 * head lies on a recursion cycle is excluded too, not only the one the RECURSION_UNSUPPORTED
 * rejection is located at. Loci always refer to the caller's query: a [Locus.RuleStatement]
 * produced over the reduced query is translated back to the original index.
 *
 * **Parse.** [QueryParser] recovers per statement (cab.5-D7): a [ParseResult.Rejected] carries
 * both its rejections and the *partial* query built from the statements that did parse. The
 * `source`-taking overload adds those parse rejections to the accumulator up front and then
 * runs every later phase over the partial query and its spans, so a syntax error in one
 * statement and an unsafe rule in another both surface from one call ([QRY1-REJECT-10]).
 *
 * **No side effect ([QRY1-REJECT-04]).** Neither overload takes a host, and
 * [CompileResult.Rejected] carries no `GraphSpec`: a rejection cannot have spawned a cell.
 * A plan-node locus of a [RejectionCode.NO_LOWERING] or plan-level
 * [RejectionCode.BAG_SEMANTICS_REQUIRED] rejection names the node in the plan of
 * the reduced query; exclusion removes whole head predicates' dependents, so a root that is
 * planned keeps its own numbering unless one of its own rules was excluded.
 */
object QueryCompiler {

    /**
     * Parses [source] against [catalog] and compiles the result. Total. A parse
     * [ParseResult.Rejected] does not stop here: its rejections join the accumulator and
     * later phases still run over its partial query, so a good statement's own rejections are
     * not hidden behind a bad statement's syntax error.
     */
    fun compile(source: String, catalog: Catalog): CompileResult =
        when (val parsed = QueryParser.parse(source, catalog)) {
            is ParseResult.Rejected -> {
                val laterPhases = compile(parsed.partial, parsed.spans)
                val laterRejections = (laterPhases as? CompileResult.Rejected)?.rejections.orEmpty()
                CompileResult.Rejected(parsed.rejections + laterRejections)
            }
            is ParseResult.Parsed -> compile(parsed.query, parsed.spans)
        }

    /**
     * Compiles [query] against the catalog it carries. [spans], when supplied (a text parse's
     * [ParseResult.Parsed.spans]), locates statement-level rejections at source spans; a
     * [SpanTable] whose sizes do not match [query]'s statement lists is ignored rather than
     * trusted, so loci fall back to [Locus.RuleStatement]. [Remainder.statementOf] maps a
     * [Locus.SourceSpan] rejection back to the statement that produced it by value equality
     * against [SpanTable.rules]/[SpanTable.definitions] — the span itself carries no index —
     * so a span table whose spans are not pairwise distinct would map every rejection at a
     * duplicated span to the *first* statement holding that span, silently mis-excluding the
     * wrong one. A caller-supplied [SpanTable] with duplicate spans is therefore just as
     * untrusted as one of the wrong size: it is ignored, and loci fall back to
     * [Locus.RuleStatement], which locates by index and is never ambiguous. A text parse never
     * produces duplicate spans, so this only guards the AST overload's hand-built tables. Total.
     */
    fun compile(query: Query, spans: SpanTable? = null): CompileResult {
        val rejections = mutableListOf<Rejection>()
        val usableSpans = spans?.takeIf {
            it.rules.size == query.rules.size &&
                it.definitions.size == query.definitions.size &&
                (it.rules + it.definitions).let { all -> all.size == all.toSet().size }
        }
        var remaining = Remainder.of(query, usableSpans)

        // Well-formedness: runs over the whole query, so its loci are already the caller's.
        val wellFormedness = WellFormednessAnalysis.findings(remaining.query, remaining.spans)
        rejections += wellFormedness.map { it.rejection }
        remaining = remaining.excluding(wellFormedness.map { it.statement })

        // Safety: runs over the well-formed remainder; loci translated back.
        val safety = SafetyAnalysis.analyze(remaining.query, remaining.spans)
        rejections += safety.map { remaining.toOriginal(it) }
        val cyclic = RuleGraph.of(remaining.query).cycles().flatten().toSet()
        remaining = remaining.excluding(safety.mapNotNull { remaining.statementOf(it) } + remaining.statementsWithHeadIn(cyclic))

        // ALL set operations ([QRY1-SEM-04]): refused and excluded here, so the planner's own
        // ALL guard is never reached.
        val bagSetOps = BagSemantics.refuseAllSetOps(remaining.query, remaining.spans)
        rejections += bagSetOps.map { remaining.toOriginal(it) }
        remaining = remaining.excluding(bagSetOps.mapNotNull { remaining.statementOf(it) })

        // Plan: every reachable Planner precondition is fenced above.
        val plan = Planner.plan(remaining.query)

        // Plan-level semantics: a COUNT/SUM/AVG over a non-key-preserving input ([QRY1-SEM-02]).
        // Lowering still runs over the whole plan, so its independent refusals are reported too.
        rejections += BagSemantics.refuseLossyAggregates(plan)

        // Lowering: every refusal is its own NO_LOWERING rejection ([QRY1-REJECT-06]).
        return when (val lowered = Lowering.lower(plan, query.catalog)) {
            is LoweringResult.Refused -> {
                rejections += lowered.refusals.map(::noLowering)
                CompileResult.Rejected(rejections.toList())
            }
            is LoweringResult.Lowered ->
                if (rejections.isEmpty()) {
                    CompileResult.Compiled(CompiledQuery.from(lowered, plan))
                } else {
                    CompileResult.Rejected(rejections.toList())
                }
        }
    }

    /** [refusal] as its one [RejectionCode.NO_LOWERING] rejection, naming node kind and reason. */
    internal fun noLowering(refusal: LoweringRefusal): Rejection = Rejection(
        code = RejectionCode.NO_LOWERING,
        locus = refusal.locus,
        specId = "[QRY1-REJECT-06] ${refusal.nodeKind}: ${refusal.reason}",
    )

    /**
     * The statements of the caller's query that later phases still see: [query] is the reduced
     * query, [spans] its positional spans (or `null`), and [ruleOrigins]/[definitionOrigins]
     * map each reduced index back to the caller's index.
     */
    private class Remainder(
        val query: Query,
        val spans: SpanTable?,
        val ruleOrigins: List<Int>,
        val definitionOrigins: List<Int>,
    ) {

        /**
         * This remainder without [rejected] (indices into [query]) and without every statement
         * that transitively references an excluded, non-catalog head predicate.
         */
        fun excluding(rejected: Collection<Statement>): Remainder {
            if (rejected.isEmpty()) return this
            val excludedRules = rejected.filterIsInstance<Statement.RuleAt>().mapTo(HashSet()) { it.index }
            val excludedDefinitions = rejected.filterIsInstance<Statement.DefinitionAt>().mapTo(HashSet()) { it.index }
            val tainted = HashSet<String>()
            fun taint(predicate: String) {
                if (predicate !in query.catalog.relations) tainted += predicate
            }
            excludedRules.forEach { taint(query.rules[it].head.predicate) }
            excludedDefinitions.forEach { taint(query.definitions[it].head.predicate) }

            var changed = true
            while (changed) {
                changed = false
                query.rules.forEachIndexed { index, rule ->
                    if (index !in excludedRules && referencedBy(rule).any { it in tainted }) {
                        excludedRules += index
                        taint(rule.head.predicate)
                        changed = true
                    }
                }
                query.definitions.forEachIndexed { index, definition ->
                    if (index !in excludedDefinitions && leavesOf(definition.expr).any { it in tainted }) {
                        excludedDefinitions += index
                        taint(definition.head.predicate)
                        changed = true
                    }
                }
            }

            val keptRules = query.rules.indices.filter { it !in excludedRules }
            val keptDefinitions = query.definitions.indices.filter { it !in excludedDefinitions }
            return Remainder(
                query = query.copy(
                    rules = keptRules.map { query.rules[it] },
                    definitions = keptDefinitions.map { query.definitions[it] },
                ),
                spans = spans?.let { table ->
                    SpanTable(
                        rules = keptRules.map { table.rules[it] },
                        definitions = keptDefinitions.map { table.definitions[it] },
                    )
                },
                ruleOrigins = keptRules.map { ruleOrigins[it] },
                definitionOrigins = keptDefinitions.map { definitionOrigins[it] },
            )
        }

        /** Every statement of [query] whose head predicate is in [heads]. */
        fun statementsWithHeadIn(heads: Set<String>): List<Statement> =
            query.rules.indices.filter { query.rules[it].head.predicate in heads }.map { Statement.RuleAt(it) } +
                query.definitions.indices.filter { query.definitions[it].head.predicate in heads }
                    .map { Statement.DefinitionAt(it) }

        /**
         * The statement of [query] a rejection produced over [query] is located at, or `null`
         * for a locus that names no statement. A [Locus.RuleStatement] resolves to a rule
         * before a definition: well-formedness has already excluded every `define` whose head
         * a rule also defines, so a rule and a definition at one index never share a head here.
         */
        fun statementOf(rejection: Rejection): Statement? = when (val locus = rejection.locus) {
            is Locus.SourceSpan -> spans?.let { table ->
                table.rules.indexOf(locus).takeIf { it >= 0 }?.let { Statement.RuleAt(it) }
                    ?: table.definitions.indexOf(locus).takeIf { it >= 0 }?.let { Statement.DefinitionAt(it) }
            }
            is Locus.RuleStatement -> when {
                query.rules.getOrNull(locus.ruleIndex)?.head?.predicate == locus.headPredicate ->
                    Statement.RuleAt(locus.ruleIndex)
                query.definitions.getOrNull(locus.ruleIndex)?.head?.predicate == locus.headPredicate ->
                    Statement.DefinitionAt(locus.ruleIndex)
                else -> null
            }
            is Locus.PlanNode -> null
        }

        /** [rejection], produced over [query], with a statement locus re-indexed to the caller's query. */
        fun toOriginal(rejection: Rejection): Rejection {
            val locus = rejection.locus as? Locus.RuleStatement ?: return rejection
            val original = when (val statement = statementOf(rejection)) {
                is Statement.RuleAt -> ruleOrigins[statement.index]
                is Statement.DefinitionAt -> definitionOrigins[statement.index]
                null -> return rejection
            }
            return rejection.copy(locus = locus.copy(ruleIndex = original))
        }

        companion object {
            fun of(query: Query, spans: SpanTable?) =
                Remainder(query, spans, query.rules.indices.toList(), query.definitions.indices.toList())

            private fun referencedBy(rule: civictech.query.ast.Rule): List<String> = rule.body.mapNotNull {
                when (it) {
                    is Literal.Positive -> it.atom.predicate
                    is Literal.Negated -> it.atom.predicate
                    is Literal.Comparison -> null
                }
            }

            private fun leavesOf(expr: RelationalExpr): List<String> = when (expr) {
                is RelationalExpr.Relation -> listOf(expr.atom.predicate)
                is RelationalExpr.SetOp -> leavesOf(expr.left) + leavesOf(expr.right)
                is RelationalExpr.OuterJoin -> leavesOf(expr.left) + leavesOf(expr.right)
            }
        }
    }
}
