package civictech.query.parse

import civictech.query.ast.Query
import civictech.query.diag.Locus
import civictech.query.diag.Rejection

/**
 * Source spans for a parsed [Query]'s statements, held **outside** the AST.
 *
 * cab.2-D1 asserts parser/builder agreement by structural equality of `Query` values
 * ([QRY1-LANG-02]); a span embedded in any AST node would make the two producers unequal by
 * construction and the equivalence test unwritable (`civictech.query.ast.NoSpanInAstTest`
 * guards the AST side of that). So the parser hands its position information back here
 * instead — and the analysis feature, which needs a locus to point a `Rejection` at, reads
 * this table rather than the nodes.
 *
 * The mapping is **positional**: [rules]`[i]` is the span of [Query.rules]`[i]` and
 * [definitions]`[i]` the span of [Query.definitions]`[i]`. Positional rather than keyed by
 * the node, because `Rule`/`Definition` are value types — a query that states the same rule
 * twice has two statements with two spans and one map key, and a `Map<Rule, SourceSpan>`
 * would silently lose one of them.
 *
 * A statement's span runs from the first character of its head to its terminating `.`,
 * inclusive.
 */
data class SpanTable(
    val rules: List<Locus.SourceSpan>,
    val definitions: List<Locus.SourceSpan>,
) {

    /** The span of the rule at [index] in [Query.rules]. */
    fun ruleSpan(index: Int): Locus.SourceSpan = rules[index]

    /** The span of the definition at [index] in [Query.definitions]. */
    fun definitionSpan(index: Int): Locus.SourceSpan = definitions[index]
}

/**
 * The total outcome of [QueryParser.parse] ([QRY1-REJECT-03]'s front-door half): a parse
 * either yields a [Query] or a list of [Rejection]s, and never throws.
 *
 * This is deliberately a **parse-local** result rather than
 * `civictech.query.diag.CompileResult`: that type's success branch (`Compiled`, carrying a
 * `CompiledQuery`) belongs to the later lowering/API features and is not landed, so a parser
 * that returned `CompileResult` would have no shape to return on success. The failure branch
 * uses the shared [Rejection] vocabulary, so a compiler front-end can forward this task's
 * rejections into `CompileResult.Rejected` unchanged.
 */
sealed interface ParseResult {

    /** The text parsed: the [query] it denotes, and the [spans] of its statements. */
    data class Parsed(val query: Query, val spans: SpanTable) : ParseResult

    /**
     * The text did not parse. [rejections] is non-empty.
     *
     * This task reports exactly one rejection — the first syntax error — because
     * multi-error aggregation policy ([QRY1-REJECT-10]) is cab.5's, not this task's. The
     * field is a list rather than a single value so that policy can land here without
     * changing the type.
     */
    data class Rejected(val rejections: List<Rejection>) : ParseResult {
        init {
            require(rejections.isNotEmpty()) { "ParseResult.Rejected requires a reason" }
        }
    }
}
