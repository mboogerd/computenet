package civictech.query.parse

import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode

/**
 * Three semantic analyses over a parsed/built [Query] (computenet-cab.2.3), each realizing
 * one [RejectionCode] this task adds:
 *
 * - **UNSAFE_RULE** ([QRY1-LANG-07]): a rule head, negated-atom, or comparison variable that
 *   does not occur in any positive body atom of the same rule.
 * - **EDB_REDEFINED** ([QRY1-LANG-08]): a rule head predicate that is also a relation
 *   declared in the [Query.catalog].
 * - **RECURSION_UNSUPPORTED** ([QRY1-LANG-09]): a rule that reaches its own head predicate
 *   through [RuleGraph]'s head-predicate dependency graph — self- or mutual recursion.
 *
 * Both UNSAFE_RULE and EDB_REDEFINED are checked over [Query.rules] only, per the acceptance
 * criteria's own wording ("a rule is unsafe", "a rule head predicate") — [civictech.query.ast.Definition]
 * has no body literals (no negation, no comparison) for UNSAFE_RULE to check, and no
 * acceptance criterion asks whether a definition's head redefines an EDB relation. Only
 * RECURSION_UNSUPPORTED's [RuleGraph] walks [Query.definitions] as well as [Query.rules] —
 * see [RuleGraph]'s KDoc for why, and the danger this project's own AGENTS.md names: an
 * analysis that walks only [Query.rules] would make every `define` statement unreachable
 * through the very refusal it exists to raise for recursive statements.
 *
 * All three analyses are **total**: [analyze] never throws on any well-formed [Query]
 * ([QRY1-REJECT-03]'s front-door share) and no `LogicalPlan`/graph is compiled here or on
 * rejection — this module doesn't own a compiler, only the checks. [analyze] returns every
 * independent rejection found across all three checks in one list, not the first one
 * (multi-error aggregation *policy* — how a later front door merges these with a parser's or
 * builder's own rejections — is cab.5's; this analysis simply does not stop early on its own
 * account).
 *
 * [Rejection] carries no free-text message field by design ([QRY1-REJECT-02], see
 * `civictech.query.parse.QueryParser.fail`'s KDoc for the same constraint on the parser
 * side) — `specId` is the only string-valued field available to name the requirement *and*
 * the offending detail (the unbound variable, the redefined relation, the predicates on a
 * cycle), so these analyses fold both into `specId`, exactly as the bead's own wording asks
 * ("for recursion also cite [21-CYCLE-01]/[21-CYCLE-03] **in the message**").
 */
object SafetyAnalysis {

    /**
     * Every rejection [query] produces under the three checks above. [spans], when supplied
     * by a text-parse ([civictech.query.parse.ParseResult.Parsed.spans]), is used to point a
     * rejection at the offending statement's source span; when `null` (a builder-produced
     * [Query] has no source text) each rejection instead carries the additive
     * [Locus.RuleStatement] locus this task adds to [Locus] — the AST itself gains no span
     * field either way (cab.2-D1).
     */
    fun analyze(query: Query, spans: SpanTable? = null): List<Rejection> =
        unsafeRuleRejections(query, spans) +
            edbRedefinedRejections(query, spans) +
            recursionRejections(query, spans)

    private fun ruleLocus(query: Query, index: Int, spans: SpanTable?): Locus =
        spans?.ruleSpan(index) ?: Locus.RuleStatement(index, query.rules[index].head.predicate)

    /** Variables bound by at least one positive body atom of [rule] ([QRY1-LANG-07]'s ground). */
    private fun positivelyBoundVars(rule: Rule): Set<String> {
        val bound = mutableSetOf<String>()
        for (literal in rule.body) {
            if (literal is Literal.Positive) {
                for (term in literal.atom.terms) {
                    if (term is Term.Var) bound += term.name
                }
            }
        }
        return bound
    }

    private fun unsafeRuleRejections(query: Query, spans: SpanTable?): List<Rejection> {
        val rejections = mutableListOf<Rejection>()
        query.rules.forEachIndexed { index, rule ->
            val bound = positivelyBoundVars(rule)
            val unbound = sortedSetOf<String>()
            fun note(term: Term) {
                if (term is Term.Var && term.name !in bound) unbound += term.name
            }
            rule.head.terms.forEach(::note)
            for (literal in rule.body) {
                when (literal) {
                    is Literal.Negated -> literal.atom.terms.forEach(::note)
                    is Literal.Comparison -> {
                        note(literal.left)
                        note(literal.right)
                    }
                    is Literal.Positive -> Unit
                }
            }
            if (unbound.isNotEmpty()) {
                rejections += Rejection(
                    code = RejectionCode.UNSAFE_RULE,
                    locus = ruleLocus(query, index, spans),
                    specId = "[QRY1-LANG-07]: rule head '${rule.head.predicate}' is unsafe — " +
                        "variable(s) not bound by any positive body atom: ${unbound.joinToString(", ")}",
                )
            }
        }
        return rejections
    }

    private fun edbRedefinedRejections(query: Query, spans: SpanTable?): List<Rejection> {
        val rejections = mutableListOf<Rejection>()
        query.rules.forEachIndexed { index, rule ->
            if (rule.head.predicate in query.catalog.relations) {
                rejections += Rejection(
                    code = RejectionCode.EDB_REDEFINED,
                    locus = ruleLocus(query, index, spans),
                    specId = "[QRY1-LANG-08]: rule head redefines EDB relation " +
                        "'${rule.head.predicate}' declared in the Catalog",
                )
            }
        }
        return rejections
    }

    private fun recursionRejections(query: Query, spans: SpanTable?): List<Rejection> {
        val graph = RuleGraph.of(query)
        val cycles = graph.cycles()
        if (cycles.isEmpty()) return emptyList()

        fun localeFor(cycle: Set<String>): Locus {
            query.rules.forEachIndexed { index, rule ->
                if (rule.head.predicate in cycle) return ruleLocus(query, index, spans)
            }
            query.definitions.forEachIndexed { index, definition ->
                if (definition.head.predicate in cycle) {
                    return spans?.definitionSpan(index)
                        ?: Locus.RuleStatement(index, definition.head.predicate)
                }
            }
            // Unreachable given RuleGraph.of only ever adds an edge FROM a statement's own
            // head predicate, so every predicate that can be part of a cycle (which requires
            // an outgoing edge back into the component) is some statement's head. Kept as a
            // non-throwing fallback rather than `error(...)` so a defect in that invariant
            // degrades to an imprecise locus instead of breaking this analysis's totality.
            return Locus.PlanNode("cycle:${cycle.sorted().joinToString(",")}")
        }

        return cycles.map { cycle ->
            Rejection(
                code = RejectionCode.RECURSION_UNSUPPORTED,
                locus = localeFor(cycle),
                specId = "[QRY1-LANG-09]: recursive rule graph unsupported, owner QRY2 — cycle " +
                    "machinery unbuilt ([21-CYCLE-01], [21-CYCLE-03]) — predicates on cycle: " +
                    cycle.sorted().joinToString(", "),
            )
        }
    }
}
