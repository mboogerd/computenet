package civictech.query.parse

import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.plan.outerJoinRightRename
import civictech.query.plan.setOpColumns

/**
 * The fence in front of `civictech.query.plan.Planner` (cab.5-D6, computenet-cab.5.1): every
 * `require` of [Planner.plan][civictech.query.plan.Planner.plan] that a parsed or built
 * [Query] can reach is decided here first, as a [Rejection] with one of four codes, so the
 * compiler front door (`civictech.query.QueryCompiler`) never hands the planner a statement it
 * would throw on ([QRY1-REJECT-03]). The planner's `require`s stay where they are, as internal
 * fences; this analysis is the diagnostic, they are the backstop.
 *
 * Planner preconditions and their code (anchored by the planner's message text):
 *
 * - [RejectionCode.UNKNOWN_PREDICATE] — "names neither a catalog relation nor a rule or
 *   definition head" (and "No rules define predicate", reachable only through that one).
 * - [RejectionCode.ARITY_MISMATCH] — "does not match its declared arity" (atom vs catalog);
 *   "is used at arity … but defined at arity" (a use vs a rule head, a use vs a `define` head,
 *   and two rules of one head, whose later rule is renamed to the first's head); "declares
 *   arity … but its expression has arity"; "operands have different arity"; "Union branches
 *   disagree on output columns" (unreachable once rule arities agree — every rule branch is
 *   projected to the canonical head names — so it is fenced by the rule-vs-rule check).
 * - [RejectionCode.PREDICATE_REDEFINED] — "defined by both a rule and a define statement",
 *   "defined by more than one define statement".
 * - [RejectionCode.UNPLANNABLE_STATEMENT] — "a constant in a head", "a repeated variable in a
 *   head", "has no positive body atom" (a fact `q(1).` included), "aggregate-annotated but has
 *   a nullary head", outer-join keys that "must name a left column … and a right column", "an
 *   outer join key column used twice".
 *
 * Not fenced here, deliberately: recursion ("does not support recursion") and unbound head
 * variables ("are not all bound by its body plan") are [SafetyAnalysis]'s
 * RECURSION_UNSUPPORTED and UNSAFE_RULE; an `ALL` set operation is the bag-semantics sibling's
 * (`BAG_SEMANTICS_REQUIRED`). The planner's remaining `check`s and `require`s over plan-node
 * columns (project subset, unplaced comparisons, definition/set-op/outer-join column agreement)
 * are consequences of the checks above plus safety, not independent preconditions; the
 * compiler's totality corpus (`civictech.query.diag.CompileTotalityTest`) is the evidence.
 *
 * **Total**, and collecting: every finding in every statement is returned, in statement order
 * (rules, then definitions). **Locus**: the offending statement's span from [SpanTable] when
 * one is supplied, else [Locus.RuleStatement] — for a definition, its index into
 * [Query.definitions], exactly as [SafetyAnalysis] does.
 *
 * The definition-expression column computation mirrors the planner's private
 * `normalizeExpr`/`exprColumns` (positional set-operation operands, outer-join right columns
 * after key merging and renaming apart). Neither rule is a copy any more: the set-operation
 * column rule calls [civictech.query.plan.setOpColumns] and the outer-join key-merge/rename-apart
 * rule calls [civictech.query.plan.outerJoinRightRename], the same functions `normalizeExpr`
 * calls, so a change to either is felt here directly rather than only through the totality
 * corpus.
 */
object WellFormednessAnalysis {

    /** Every well-formedness rejection [query] produces; see the object KDoc. */
    fun analyze(query: Query, spans: SpanTable? = null): List<Rejection> =
        findings(query, spans).map { it.rejection }

    /** The rejections of [analyze], each paired with the statement it rejects. */
    internal fun findings(query: Query, spans: SpanTable?): List<Finding> =
        Walk(query, spans).run()

    /** A statement of a [Query]: a rule or a definition, by its index in its own list. */
    internal sealed interface Statement {
        val index: Int

        data class RuleAt(override val index: Int) : Statement

        data class DefinitionAt(override val index: Int) : Statement
    }

    /** One [rejection], and the [statement] it rejects. */
    internal data class Finding(val statement: Statement, val rejection: Rejection)

    private const val ARITY_SPEC = "[QRY1-LANG-05]"
    private const val UNKNOWN_SPEC = "[QRY1-LANG-05]"
    private const val REDEFINED_SPEC = "[QRY1-LANG-04]"
    private const val UNPLANNABLE_SPEC = "[QRY1-REJECT-03]"

    private class Walk(private val query: Query, private val spans: SpanTable?) {

        private val findings = mutableListOf<Finding>()

        /** Arity of the first rule of each head — the planner's canonical head names. */
        private val firstRuleArity = LinkedHashMap<String, Int>()

        /** Arity of the first `define` of each head — the one the planner keeps. */
        private val firstDefinitionArity = LinkedHashMap<String, Int>()

        init {
            for (rule in query.rules) firstRuleArity.putIfAbsent(rule.head.predicate, rule.head.terms.size)
            for (definition in query.definitions) {
                firstDefinitionArity.putIfAbsent(definition.head.predicate, definition.head.terms.size)
            }
        }

        fun run(): List<Finding> {
            query.rules.forEachIndexed { index, _ -> checkRule(index) }
            query.definitions.forEachIndexed { index, _ -> checkDefinition(index) }
            return findings.toList()
        }

        private fun report(statement: Statement, code: RejectionCode, specId: String) {
            val locus = when (statement) {
                is Statement.RuleAt -> spans?.rules?.getOrNull(statement.index)
                    ?: Locus.RuleStatement(statement.index, query.rules[statement.index].head.predicate)
                is Statement.DefinitionAt -> spans?.definitions?.getOrNull(statement.index)
                    ?: Locus.RuleStatement(statement.index, query.definitions[statement.index].head.predicate)
            }
            findings += Finding(statement, Rejection(code, locus, specId))
        }

        private fun checkRule(index: Int) {
            val rule = query.rules[index]
            val at = Statement.RuleAt(index)
            val head = rule.head
            checkHead(at, head, "rule")

            val canonical = firstRuleArity.getValue(head.predicate)
            if (head.terms.size != canonical) {
                report(
                    at, RejectionCode.ARITY_MISMATCH,
                    "$ARITY_SPEC rule head '${head.predicate}' has arity ${head.terms.size}, but " +
                        "an earlier rule defines '${head.predicate}' at arity $canonical",
                )
            }
            if (rule.body.none { it is Literal.Positive }) {
                report(
                    at, RejectionCode.UNPLANNABLE_STATEMENT,
                    "$UNPLANNABLE_SPEC rule '${head.predicate}' has no positive body atom " +
                        "(a fact, or a body of only negations and comparisons); the planner has no " +
                        "source to plan it from",
                )
            }
            if (rule.aggregate != null && head.terms.isEmpty()) {
                report(
                    at, RejectionCode.UNPLANNABLE_STATEMENT,
                    "$UNPLANNABLE_SPEC rule '${head.predicate}' is aggregate-annotated " +
                        "(${rule.aggregate.kind}) but has a nullary head; the aggregated column is " +
                        "the last head variable",
                )
            }
            for (literal in rule.body) {
                when (literal) {
                    is Literal.Positive -> checkUse(at, literal.atom)
                    is Literal.Negated -> checkUse(at, literal.atom)
                    is Literal.Comparison -> Unit
                }
            }
        }

        private fun checkDefinition(index: Int) {
            val definition = query.definitions[index]
            val at = Statement.DefinitionAt(index)
            val head = definition.head
            checkHead(at, head, "define")

            val predicate = head.predicate
            if (predicate in firstRuleArity) {
                report(
                    at, RejectionCode.PREDICATE_REDEFINED,
                    "$REDEFINED_SPEC predicate '$predicate' is defined by both a rule and a define " +
                        "statement; a head predicate has exactly one kind of definition",
                )
            }
            if (query.definitions.subList(0, index).any { it.head.predicate == predicate }) {
                report(
                    at, RejectionCode.PREDICATE_REDEFINED,
                    "$REDEFINED_SPEC predicate '$predicate' is defined by more than one define " +
                        "statement; combine the expressions with union instead",
                )
            }
            val columns = columnsOf(at, definition.expr, "$predicate#def/")
            if (columns.size != head.terms.size) {
                report(
                    at, RejectionCode.ARITY_MISMATCH,
                    "$ARITY_SPEC define '$predicate' declares arity ${head.terms.size} in its head " +
                        "but its expression has arity ${columns.size}",
                )
            }
        }

        private fun checkHead(at: Statement, head: Atom, kind: String) {
            val constants = head.terms.filterIsInstance<Term.Const>()
            if (constants.isNotEmpty()) {
                report(
                    at, RejectionCode.UNPLANNABLE_STATEMENT,
                    "$UNPLANNABLE_SPEC $kind head '${head.predicate}' carries constant(s) " +
                        "${constants.map { it.value }}; a head position must be a variable",
                )
            }
            val names = head.terms.filterIsInstance<Term.Var>().map { it.name }
            val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (repeated.isNotEmpty()) {
                report(
                    at, RejectionCode.UNPLANNABLE_STATEMENT,
                    "$UNPLANNABLE_SPEC $kind head '${head.predicate}' repeats variable(s) " +
                        "${repeated.sorted()}; a head names each column once",
                )
            }
        }

        /** A body atom, negated atom, or definition leaf: the predicate must exist at this arity. */
        private fun checkUse(at: Statement, atom: Atom) {
            val predicate = atom.predicate
            // The planner's resolution order: a catalog schema first, then a define, then rules.
            val schema = query.catalog.relations[predicate]
            val (expected, what) = when {
                schema != null -> schema.attributes.size to "its catalog schema"
                predicate in firstDefinitionArity -> firstDefinitionArity.getValue(predicate) to "its define head"
                predicate in firstRuleArity -> firstRuleArity.getValue(predicate) to "its rule head"
                else -> {
                    report(
                        at, RejectionCode.UNKNOWN_PREDICATE,
                        "$UNKNOWN_SPEC atom '$predicate' names neither a catalog relation nor a " +
                            "rule or define head",
                    )
                    return
                }
            }
            if (atom.terms.size != expected) {
                report(
                    at, RejectionCode.ARITY_MISMATCH,
                    "$ARITY_SPEC atom '$predicate' is used at arity ${atom.terms.size} but " +
                        "$what has arity $expected",
                )
            }
        }

        /**
         * The columns [expr] exposes after the planner's normalization, reporting every
         * set-operation arity and outer-join key problem found on the way. Keeps computing
         * past a problem (set operations take the left operand's columns either way) so
         * independent problems in one expression are all reported.
         */
        private fun columnsOf(at: Statement, expr: RelationalExpr, scope: String): List<String> = when (expr) {
            is RelationalExpr.Relation -> {
                checkUse(at, expr.atom)
                expr.atom.terms.filterIsInstance<Term.Var>().map { it.name }.distinct()
            }
            is RelationalExpr.SetOp -> {
                val left = columnsOf(at, expr.left, "${scope}L/")
                val right = columnsOf(at, expr.right, "${scope}R/")
                if (left.size != right.size) {
                    report(
                        at, RejectionCode.ARITY_MISMATCH,
                        "$ARITY_SPEC ${expr.kind} operands have different arity: left $left " +
                            "(arity ${left.size}) vs right $right (arity ${right.size})",
                    )
                }
                setOpColumns(left, right)
            }
            is RelationalExpr.OuterJoin -> {
                val left = columnsOf(at, expr.left, "${scope}L/")
                val right = columnsOf(at, expr.right, "${scope}R/")
                val leftKeys = expr.on.map { it.left.name }
                val rightKeys = expr.on.map { it.right.name }
                val keyText = expr.on.map { "${it.left.name} = ${it.right.name}" }
                if (!(leftKeys.all { it in left } && rightKeys.all { it in right })) {
                    report(
                        at, RejectionCode.UNPLANNABLE_STATEMENT,
                        "$UNPLANNABLE_SPEC ${expr.side} outer join keys $keyText must name a left " +
                            "column of $left and a right column of $right",
                    )
                }
                if (leftKeys.distinct().size != leftKeys.size || rightKeys.distinct().size != rightKeys.size) {
                    report(
                        at, RejectionCode.UNPLANNABLE_STATEMENT,
                        "$UNPLANNABLE_SPEC ${expr.side} outer join uses a key column twice: $keyText",
                    )
                }
                val renameTarget = outerJoinRightRename(left, right, leftKeys, rightKeys, scope)
                val renamedRight = right.map { column -> renameTarget.getValue(column) }
                left + renamedRight.filter { it !in left }
            }
        }
    }
}
