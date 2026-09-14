package civictech.query.parse

import civictech.query.QueryCompiler
import civictech.query.ast.Atom
import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.diag.CompileResult
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.lower.PlanFixtures
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * One named test per `Planner.plan` precondition fenced by [WellFormednessAnalysis]
 * (cab.5-D6), each compiling the offending input through [QueryCompiler] and asserting the
 * code and the statement locus. Test names cite the planner `require` by its message text,
 * which is the stable anchor (line numbers move).
 */
class WellFormednessAnalysisTest {

    private fun catalog(vararg relations: Pair<String, Int>): Catalog = PlanFixtures.catalog(*relations)

    private fun parsed(source: String, catalog: Catalog) =
        QueryParser.parse(source, catalog).shouldBeInstanceOf<ParseResult.Parsed>()

    /** Compiles [source]; returns its rejections and the spans of its parse. */
    private fun rejected(source: String, catalog: Catalog): Pair<List<Rejection>, SpanTable> {
        val spans = parsed(source, catalog).spans
        val result = withClue("compiling: $source") {
            QueryCompiler.compile(source, catalog).shouldBeInstanceOf<CompileResult.Rejected>()
        }
        return result.rejections to spans
    }

    /** Asserts [source] is rejected with exactly [code] (once) at the span [locus] picks. */
    private fun assertSingle(source: String, catalog: Catalog, code: RejectionCode, locus: (SpanTable) -> Locus) {
        val (rejections, spans) = rejected(source, catalog)
        withClue("rejections of: $source -> $rejections") {
            rejections.map { it.code } shouldBe listOf(code)
            rejections.single().locus shouldBe locus(spans)
        }
    }

    // ------------------------------------------------------------------ PREDICATE_REDEFINED

    @Test
    fun `defined by both a rule and a define statement - PREDICATE_REDEFINED at the define`() {
        assertSingle(
            "q(X) :- r(X).\ndefine q(X) := s(X).", catalog("r" to 1, "s" to 1),
            RejectionCode.PREDICATE_REDEFINED,
        ) { it.definitionSpan(0) }
    }

    @Test
    fun `defined by more than one define statement - PREDICATE_REDEFINED at the second define`() {
        assertSingle(
            "define q(X) := r(X).\ndefine q(X) := s(X).", catalog("r" to 1, "s" to 1),
            RejectionCode.PREDICATE_REDEFINED,
        ) { it.definitionSpan(1) }
    }

    // ------------------------------------------------------------------ UNPLANNABLE_STATEMENT

    @Test
    fun `a constant in a head - UNPLANNABLE_STATEMENT`() {
        assertSingle("q(X, 1) :- r(X).", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.ruleSpan(0) }
    }

    @Test
    fun `a constant in a define head - UNPLANNABLE_STATEMENT`() {
        assertSingle("define q(1) := r(X).", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.definitionSpan(0) }
    }

    @Test
    fun `a repeated variable in a head - UNPLANNABLE_STATEMENT`() {
        assertSingle("q(X, X) :- r(X).", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.ruleSpan(0) }
    }

    @Test
    fun `has no positive body atom, a fact - UNPLANNABLE_STATEMENT and no throw`() {
        // `q(1).` trips two preconditions of one statement (constant head, no positive atom):
        // both are reported, both under the one code.
        val (rejections, spans) = rejected("q(1).", catalog("r" to 1))
        rejections.map { it.code }.toSet() shouldBe setOf(RejectionCode.UNPLANNABLE_STATEMENT)
        rejections.map { it.locus }.toSet() shouldBe setOf(spans.ruleSpan(0))
    }

    @Test
    fun `has no positive body atom, a body of only a comparison - UNPLANNABLE_STATEMENT`() {
        assertSingle("q() :- 1 < 2.", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.ruleSpan(0) }
    }

    @Test
    fun `has no positive body atom, a body of only a negation - UNPLANNABLE_STATEMENT, safety not re-reporting it`() {
        assertSingle("q(X) :- not r(X).", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.ruleSpan(0) }
    }

    @Test
    fun `aggregate-annotated but has a nullary head - UNPLANNABLE_STATEMENT`() {
        assertSingle("@count c() :- r(X).", catalog("r" to 1), RejectionCode.UNPLANNABLE_STATEMENT) { it.ruleSpan(0) }
    }

    @Test
    fun `outer join keys must name a left column and a right column - UNPLANNABLE_STATEMENT`() {
        assertSingle(
            "define h(X, Y) := r(X) left outer join s(Y) on X = Z.", catalog("r" to 1, "s" to 1),
            RejectionCode.UNPLANNABLE_STATEMENT,
        ) { it.definitionSpan(0) }
    }

    @Test
    fun `an outer join key column used twice - UNPLANNABLE_STATEMENT`() {
        assertSingle(
            "define h(X, Y) := r(X, Y) left outer join s(A, B) on X = A, X = B.", catalog("r" to 2, "s" to 2),
            RejectionCode.UNPLANNABLE_STATEMENT,
        ) { it.definitionSpan(0) }
    }

    // ------------------------------------------------------------------ UNKNOWN_PREDICATE

    @Test
    fun `names neither a catalog relation nor a rule or definition head, in a body - UNKNOWN_PREDICATE`() {
        assertSingle("q(X) :- nope(X).", catalog("r" to 1), RejectionCode.UNKNOWN_PREDICATE) { it.ruleSpan(0) }
    }

    @Test
    fun `names neither a catalog relation nor a head, in a negated atom - UNKNOWN_PREDICATE`() {
        assertSingle("q(X) :- r(X), not nope(X).", catalog("r" to 1), RejectionCode.UNKNOWN_PREDICATE) { it.ruleSpan(0) }
    }

    @Test
    fun `names neither a catalog relation nor a head, in a definition leaf - UNKNOWN_PREDICATE`() {
        assertSingle(
            "define h(X) := r(X) union nope(X).", catalog("r" to 1),
            RejectionCode.UNKNOWN_PREDICATE,
        ) { it.definitionSpan(0) }
    }

    // ------------------------------------------------------------------ ARITY_MISMATCH

    @Test
    fun `does not match its declared arity, atom vs catalog - ARITY_MISMATCH`() {
        assertSingle("q(X) :- r(X, Y).", catalog("r" to 1), RejectionCode.ARITY_MISMATCH) { it.ruleSpan(0) }
    }

    @Test
    fun `does not match its declared arity, negated atom vs catalog - ARITY_MISMATCH`() {
        assertSingle("q(X) :- r(X), not s(X, X).", catalog("r" to 1, "s" to 1), RejectionCode.ARITY_MISMATCH) { it.ruleSpan(0) }
    }

    @Test
    fun `is used at arity but defined at arity, a use of a define head - ARITY_MISMATCH at the use`() {
        assertSingle(
            "define h(X) := r(X).\nq(X) :- h(X, Y), r(Y).", catalog("r" to 1),
            RejectionCode.ARITY_MISMATCH,
        ) { it.ruleSpan(0) }
    }

    @Test
    fun `is used at arity but defined at arity, a use of a rule head - ARITY_MISMATCH at the use`() {
        assertSingle(
            "p(X) :- r(X).\nq(X) :- p(X, Y), r(Y).", catalog("r" to 1),
            RejectionCode.ARITY_MISMATCH,
        ) { it.ruleSpan(1) }
    }

    @Test
    fun `is used at arity but defined at arity, two rules of one head (Union branches disagree) - ARITY_MISMATCH at the later rule`() {
        assertSingle(
            "q(X) :- r(X).\nq(X, Y) :- s(X, Y).", catalog("r" to 1, "s" to 2),
            RejectionCode.ARITY_MISMATCH,
        ) { it.ruleSpan(1) }
    }

    @Test
    fun `declares arity in its head but its expression has arity - ARITY_MISMATCH`() {
        assertSingle("define h(X, Y) := r(X).", catalog("r" to 1), RejectionCode.ARITY_MISMATCH) { it.definitionSpan(0) }
    }

    @Test
    fun `operands have different arity - ARITY_MISMATCH`() {
        assertSingle(
            "define h(X) := r(X) union s(X, Y).", catalog("r" to 1, "s" to 2),
            RejectionCode.ARITY_MISMATCH,
        ) { it.definitionSpan(0) }
    }

    // ------------------------------------------------------------------ analysis itself

    @Test
    fun `a built query without spans is located by statement index, a definition by its index into definitions`() {
        val query = Query(
            rules = listOf(Rule(Atom("q", listOf(Term.Var("X"))), listOf(Literal.Positive(Atom("r", listOf(Term.Var("X"))))))),
            catalog = catalog("r" to 1),
            definitions = listOf(
                Definition(Atom("h", listOf(Term.Var("X"))), RelationalExpr.Relation(Atom("r", listOf(Term.Var("X"))))),
                Definition(Atom("k", listOf(Term.Const(1, AttrType.INT))), RelationalExpr.Relation(Atom("r", listOf(Term.Var("X"))))),
            ),
        )

        val rejections = WellFormednessAnalysis.analyze(query)

        rejections.map { it.code to it.locus } shouldBe listOf(
            RejectionCode.UNPLANNABLE_STATEMENT to Locus.RuleStatement(1, "k"),
        )
        rejections.single().specId shouldStartWith "[QRY1-REJECT-03]"
    }

    @Test
    fun `every independent finding is collected, in statement order`() {
        val source = "q(X) :- nope(X).\np(X, X) :- r(X).\ndefine h(X, Y) := r(X)."
        val (rejections, spans) = rejected(source, catalog("r" to 1))
        rejections.map { it.code to it.locus } shouldBe listOf(
            RejectionCode.UNKNOWN_PREDICATE to spans.ruleSpan(0),
            RejectionCode.UNPLANNABLE_STATEMENT to spans.ruleSpan(1),
            RejectionCode.ARITY_MISMATCH to spans.definitionSpan(0),
        )
    }

    @Test
    fun `a well-formed query with rules, a set operation and an outer join has no finding`() {
        val source = """
            q(X) :- r(X, Y), not s(X), Y > 3.
            p(X) :- q(X).
            define u(X) := s(X) union p(X).
            define o(X, Y) := r(X, Y) left outer join s(Z) on X = Z.
        """.trimIndent()
        val parse = parsed(source, catalog("r" to 2, "s" to 1))
        WellFormednessAnalysis.analyze(parse.query, parse.spans).shouldBeEmpty()
    }

    // ------------------------------------------------------ shared with Planner.normalizeExpr

    /**
     * `define o(X, Y, Z) := t(X, Y) left outer join t(Y, X) on X = Y.`: the outer join's key
     * merges the right's `Y` onto the left's `X`; the right's other column, `X`, is not a key
     * but collides with the left's own `X` and must be renamed apart (`civictech.query.plan`'s
     * `outerJoinRightRename`), so the expression exposes 3 columns — `X, Y` from the left plus
     * the renamed-apart right `X` — matching the head's declared arity. This is the query the
     * discovering review (computenet-cab.5.1) used to show the analysis's copy of this rule
     * could drift from the planner's; it is now a call to the same function, so mutating that
     * rename (e.g. dropping the "renamed apart" branch, merging the colliding column away
     * instead) fails this test rather than only the totality corpus.
     */
    @Test
    fun `outer join right column colliding with a left column is renamed apart, not merged away`() {
        val source = "define o(X, Y, Z) := t(X, Y) left outer join t(Y, X) on X = Y."
        val parse = parsed(source, catalog("t" to 2))
        WellFormednessAnalysis.analyze(parse.query, parse.spans).shouldBeEmpty()
    }

    /**
     * `define h(X) := r(X, X).`: the leaf atom's two terms are the same variable, so it exposes
     * one distinct column, matching the head's declared arity of 1. If the Relation branch of
     * `columnsOf` counted terms instead of distinct variables (e.g. `.distinct()` dropped), it
     * would expose 2 columns and this well-formed definition would be falsely rejected as
     * ARITY_MISMATCH.
     */
    @Test
    fun `a relation's repeated variable is counted once, not per occurrence`() {
        val source = "define h(X) := r(X, X)."
        val parse = parsed(source, catalog("r" to 2))
        WellFormednessAnalysis.analyze(parse.query, parse.spans).shouldBeEmpty()
    }
}
