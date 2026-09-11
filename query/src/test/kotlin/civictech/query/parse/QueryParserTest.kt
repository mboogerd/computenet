package civictech.query.parse

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.JoinKey
import civictech.query.ast.Literal
import civictech.query.ast.OuterJoinSide
import civictech.query.ast.RelationalExpr
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.diag.RejectionCode
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The text surface of `[QRY1-LANG-01]`, `[QRY1-LANG-03]` and `[QRY1-LANG-04]`, and the
 * totality guarantee of `[QRY1-REJECT-03]`'s front door.
 *
 * Two halves, deliberately separate:
 *
 * - **What parses to what** — every construct, asserted against a hand-built AST value, so
 *   the test states the shape the parser produces rather than only that it produced one.
 *   (Agreement with the *builder* is `SurfaceEquivalenceTest`'s, which needs both producers.)
 * - **What refuses, and how** — the `q(x) :- r(x,` feature example and its siblings, each
 *   asserting `RejectionCode.SYNTAX_ERROR` with a real `Locus.SourceSpan`; plus a generative
 *   no-throw sweep over mutated valid sources.
 *
 * `SYNTAX_ERROR` is the `RejectionCode` variant this feature adds, and these are the named
 * tests cab.1-D1 requires to land with it ([QRY1-REJECT-05]).
 */
class QueryParserTest {

    private val catalog = Catalog(
        mapOf(
            "link" to RelationSchema(
                listOf(Attribute("src", AttrType.STRING), Attribute("dst", AttrType.STRING)),
            ),
            "blocked" to RelationSchema(
                listOf(Attribute("src", AttrType.STRING), Attribute("dst", AttrType.STRING)),
            ),
            "dist" to RelationSchema(
                listOf(
                    Attribute("src", AttrType.STRING),
                    Attribute("dst", AttrType.STRING),
                    Attribute("d", AttrType.LONG),
                ),
            ),
            "score" to RelationSchema(
                listOf(Attribute("who", AttrType.STRING), Attribute("s", AttrType.DOUBLE)),
            ),
            "flagged" to RelationSchema(
                listOf(Attribute("who", AttrType.STRING), Attribute("on", AttrType.BOOL)),
            ),
        ),
    )

    private val x = Term.Var("X")
    private val y = Term.Var("Y")
    private val z = Term.Var("Z")
    private val d = Term.Var("D")

    private fun parsed(source: String): ParseResult.Parsed =
        withClue("expected '$source' to parse, got ${QueryParser.parse(source, catalog)}") {
            QueryParser.parse(source, catalog).shouldBeInstanceOf<ParseResult.Parsed>()
        }

    private fun rejected(source: String): ParseResult.Rejected =
        withClue("expected '$source' to be rejected, got ${QueryParser.parse(source, catalog)}") {
            QueryParser.parse(source, catalog).shouldBeInstanceOf<ParseResult.Rejected>()
        }

    // ------------------------------------------------------------------ rules

    @Test
    fun `a rule parses to a head atom over a literal body`() {
        val query = parsed("reach(X, Y) :- link(X, Y).").query
        query.rules shouldHaveSize 1
        query.rules[0].head shouldBe Atom("reach", listOf(x, y))
        query.rules[0].body shouldContainExactly listOf(
            Literal.Positive(Atom("link", listOf(x, y))),
        )
        query.rules[0].aggregate shouldBe null
        query.definitions.shouldHaveSize(0)
    }

    @Test
    fun `a body carries positive atoms, negated atoms and comparisons`() {
        val rule = parsed(
            "far(X, Y) :- dist(X, Y, D), not blocked(X, Y), D > 10.",
        ).query.rules.single()
        rule.body shouldContainExactly listOf(
            Literal.Positive(Atom("dist", listOf(x, y, d))),
            Literal.Negated(Atom("blocked", listOf(x, y))),
            Literal.Comparison(d, ComparisonOp.GT, Term.Const(10, AttrType.INT)),
        )
    }

    @Test
    fun `every comparison operator spelling maps onto its ComparisonOp`() {
        val spellings = listOf(
            "=" to ComparisonOp.EQ,
            "!=" to ComparisonOp.NE,
            "<>" to ComparisonOp.NE,
            "<" to ComparisonOp.LT,
            "<=" to ComparisonOp.LE,
            ">" to ComparisonOp.GT,
            ">=" to ComparisonOp.GE,
        )
        spellings.forEach { (text, op) ->
            val rule = parsed("p(X) :- link(X, Y), X $text Y.").query.rules.single()
            withClue("spelling '$text'") {
                rule.body[1] shouldBe Literal.Comparison(x, op, y)
            }
        }
    }

    @Test
    fun `a rule with no body is a fact`() {
        val rule = parsed("link('a', 'b').").query.rules.single()
        rule.body.shouldHaveSize(0)
        rule.head shouldBe Atom(
            "link",
            listOf(Term.Const("a", AttrType.STRING), Term.Const("b", AttrType.STRING)),
        )
    }

    // ------------------------------------------------------------------ terms and typing

    @Test
    fun `a constant at a declared attribute position takes the catalog's type`() {
        // `10` is lexically INT; `dist`'s third attribute is declared LONG, so the parser
        // produces the Const the builder's `const(10L)` produces.
        val rule = parsed("near(X, Y) :- dist(X, Y, 10).").query.rules.single()
        rule.body.single() shouldBe Literal.Positive(
            Atom("dist", listOf(x, y, Term.Const(10L, AttrType.LONG))),
        )
    }

    @Test
    fun `a constant outside any declared position keeps its lexical type`() {
        val rule = parsed("p(X) :- link(X, Y), X = 10, Y = 10L, X != 1.5, Y = true.")
            .query.rules.single()
        rule.body.drop(1) shouldContainExactly listOf(
            Literal.Comparison(x, ComparisonOp.EQ, Term.Const(10, AttrType.INT)),
            Literal.Comparison(y, ComparisonOp.EQ, Term.Const(10L, AttrType.LONG)),
            Literal.Comparison(x, ComparisonOp.NE, Term.Const(1.5, AttrType.DOUBLE)),
            Literal.Comparison(y, ComparisonOp.EQ, Term.Const(true, AttrType.BOOL)),
        )
    }

    @Test
    fun `a declared type that cannot hold the literal leaves the lexical type and passes no judgment`() {
        // `link.src` is STRING; the author wrote a number. Typing it is the analysis
        // feature's rejection to make, not a syntax error here.
        val rule = parsed("p(X) :- link(7, X).").query.rules.single()
        rule.body.single() shouldBe Literal.Positive(
            Atom("link", listOf(Term.Const(7, AttrType.INT), x)),
        )
    }

    @Test
    fun `an identifier is a variable when it starts uppercase or underscore, a symbol otherwise`() {
        val rule = parsed("p(X, _y, sym) :- link('a', X).").query.rules.single()
        rule.head shouldBe Atom(
            "p",
            listOf(x, Term.Var("_y"), Term.Const("sym", AttrType.STRING)),
        )
    }

    @Test
    fun `a double-quoted and a single-quoted string are the same constant`() {
        parsed("p(\"a\").").query.rules.single().head shouldBe
            parsed("p('a').").query.rules.single().head
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    fun `the head annotation accepts exactly the seven aggregates of QRY1-LANG-03`() {
        val expected = mapOf(
            "@count" to Aggregate(AggregateKind.COUNT),
            "@sum" to Aggregate(AggregateKind.SUM),
            "@avg" to Aggregate(AggregateKind.AVG),
            "@min" to Aggregate(AggregateKind.MIN),
            "@max" to Aggregate(AggregateKind.MAX),
            "@collectToSet" to Aggregate(AggregateKind.COLLECT_TO_SET),
            "@topK(3)" to Aggregate(AggregateKind.TOP_K, 3),
        )
        expected.forEach { (annotation, aggregate) ->
            withClue("annotation '$annotation'") {
                parsed("$annotation deg(X) :- link(X, Y).").query.rules.single()
                    .aggregate shouldBe aggregate
            }
        }
        withClue("every AggregateKind is covered by a spelling above") {
            expected.values.map { it.kind }.toSet() shouldBe AggregateKind.entries.toSet()
        }
    }

    @Test
    fun `an aggregate outside the closed seven is refused`() {
        // Refused as SYNTAX_ERROR — see QueryParser's "Known limitation": [QRY1-SEM-05]
        // wants ORDER_DEPENDENT_AGGREGATE, which is the semantic feature's variant to add.
        rejected("@first deg(X) :- link(X, Y).")
    }

    @Test
    fun `topK without a positive k is refused`() {
        rejected("@topK deg(X) :- link(X, Y).")
        rejected("@topK(0) deg(X) :- link(X, Y).")
    }

    @Test
    fun `a non-topK aggregate with a parameter is refused`() {
        rejected("@count(3) deg(X) :- link(X, Y).")
    }

    // ------------------------------------------------------------------ set operations

    @Test
    fun `union, intersect and except parse to their SetOpKind`() {
        val spellings = mapOf(
            "union" to SetOpKind.UNION,
            "intersect" to SetOpKind.INTERSECTION,
            "except" to SetOpKind.DIFFERENCE,
        )
        spellings.forEach { (keyword, kind) ->
            val definition = parsed(
                "define both(X, Y) := link(X, Y) $keyword blocked(X, Y).",
            ).query.definitions.single()
            withClue("keyword '$keyword'") {
                definition.head shouldBe Atom("both", listOf(x, y))
                definition.expr shouldBe RelationalExpr.SetOp(
                    kind,
                    RelationalExpr.Relation(Atom("link", listOf(x, y))),
                    RelationalExpr.Relation(Atom("blocked", listOf(x, y))),
                    all = false,
                )
            }
        }
    }

    @Test
    fun `ALL parses successfully and is stored on the all-flag, never judged`() {
        // cab.2-D3: refusing bag semantics is the rejection feature's BAG_SEMANTICS_REQUIRED,
        // so ALL must PARSE here — a syntax error would mis-attribute a semantic exclusion.
        listOf("union", "intersect", "except").forEach { keyword ->
            val expr = parsed(
                "define both(X, Y) := link(X, Y) $keyword all blocked(X, Y).",
            ).query.definitions.single().expr
            withClue("keyword '$keyword all'") {
                expr.shouldBeInstanceOf<RelationalExpr.SetOp>().all shouldBe true
            }
        }
    }

    @Test
    fun `set operations are left-associative and parentheses regroup them`() {
        val left = parsed(
            "define t(X, Y) := link(X, Y) union blocked(X, Y) union link(X, Y).",
        ).query.definitions.single().expr
        val leaf = RelationalExpr.Relation(Atom("link", listOf(x, y)))
        val other = RelationalExpr.Relation(Atom("blocked", listOf(x, y)))
        left shouldBe RelationalExpr.SetOp(
            SetOpKind.UNION,
            RelationalExpr.SetOp(SetOpKind.UNION, leaf, other),
            leaf,
        )

        val grouped = parsed(
            "define t(X, Y) := link(X, Y) union (blocked(X, Y) union link(X, Y)).",
        ).query.definitions.single().expr
        grouped shouldBe RelationalExpr.SetOp(
            SetOpKind.UNION,
            leaf,
            RelationalExpr.SetOp(SetOpKind.UNION, other, leaf),
        )
    }

    // ------------------------------------------------------------------ outer joins

    @Test
    fun `left, right and full outer joins parse to their OuterJoinSide`() {
        val sides = mapOf(
            "left" to OuterJoinSide.LEFT,
            "right" to OuterJoinSide.RIGHT,
            "full" to OuterJoinSide.FULL,
        )
        sides.forEach { (keyword, side) ->
            val expr = parsed(
                "define j(X, Y, Z) := link(X, Y) $keyword outer join dist(Y, Z, D) on Y = Y.",
            ).query.definitions.single().expr
            withClue("keyword '$keyword outer join'") {
                expr shouldBe RelationalExpr.OuterJoin(
                    side,
                    RelationalExpr.Relation(Atom("link", listOf(x, y))),
                    RelationalExpr.Relation(Atom("dist", listOf(y, z, d))),
                    listOf(JoinKey(y, y)),
                )
            }
        }
    }

    @Test
    fun `an outer join takes several key equalities`() {
        val expr = parsed(
            "define j(X, Y) := link(X, Y) full outer join blocked(X, Y) on X = X, Y = Y.",
        ).query.definitions.single().expr
        expr.shouldBeInstanceOf<RelationalExpr.OuterJoin>().on shouldContainExactly listOf(
            JoinKey(x, x),
            JoinKey(y, y),
        )
    }

    @Test
    fun `an outer join binds tighter than a set operation`() {
        val expr = parsed(
            "define j(X, Y) := blocked(X, Y) union link(X, Y) left outer join blocked(X, Y) on X = X.",
        ).query.definitions.single().expr
        expr.shouldBeInstanceOf<RelationalExpr.SetOp>().right
            .shouldBeInstanceOf<RelationalExpr.OuterJoin>()
    }

    @Test
    fun `a relation may be named after a contextual keyword`() {
        val rule = parsed("p(X) :- join(X), on(X), all(X).").query.rules.single()
        rule.body.map { (it as Literal.Positive).atom.predicate } shouldContainExactly
            listOf("join", "on", "all")
    }

    // ------------------------------------------------------------------ span table

    @Test
    fun `the span table maps each statement positionally, outside the AST`() {
        val result = parsed(
            """
            reach(X, Y) :- link(X, Y).
            define both(X, Y) := link(X, Y) union blocked(X, Y).
            far(X, Y) :- dist(X, Y, D).
            """.trimIndent(),
        )
        result.query.rules shouldHaveSize 2
        result.query.definitions shouldHaveSize 1
        result.spans.rules shouldHaveSize 2
        result.spans.definitions shouldHaveSize 1

        result.spans.ruleSpan(0) shouldBe Locus.SourceSpan(1, 1, 1, 26)
        result.spans.definitionSpan(0) shouldBe Locus.SourceSpan(2, 1, 2, 52)
        result.spans.ruleSpan(1) shouldBe Locus.SourceSpan(3, 1, 3, 27)
    }

    @Test
    fun `a statement span runs from its head to its terminator across lines`() {
        val result = parsed("reach(X, Y) :-\n    link(X, Y).")
        result.spans.ruleSpan(0) shouldBe Locus.SourceSpan(1, 1, 2, 15)
    }

    // ------------------------------------------------------------------ rejections

    @Test
    fun `the truncated rule q(x) - r(x, is rejected with a syntax span and never throws`() {
        // The feature example, verbatim: `q(x) :- r(x,`
        val result = QueryParser.parse("q(x) :- r(x,", catalog)
        val rejection = result.shouldBeInstanceOf<ParseResult.Rejected>().rejections.single()
        rejection.code shouldBe RejectionCode.SYNTAX_ERROR
        rejection.specId shouldBe QueryParser.SPEC_ID
        val span = rejection.locus.shouldBeInstanceOf<Locus.SourceSpan>()
        // The offending position is where the input ran out: line 1, one past the last char.
        span shouldBe Locus.SourceSpan(1, 13, 1, 13)
    }

    @Test
    fun `each malformed shape is rejected with SYNTAX_ERROR and a source span`() {
        val malformed = listOf(
            "q(x) :- r(x,",
            "q(X) :- r(X).)",
            "q(X) :- r(X)",
            "q(X) :- .",
            "q(X) :- r(X), .",
            ":- r(X).",
            "q(X) :- r('unterminated).",
            "q(X) :- r(X) # link(X).",
            "define q(X) := .",
            "define q(X) link(X).",
            "define q(X) := link(X) union.",
            "define q(X) := link(X) left outer join blocked(X, Y).",
            "define q(X) := link(X) left outer join blocked(X, Y) on 'a' = X.",
            "define q(X) := (link(X).",
        )
        malformed.forEach { source ->
            withClue("source '$source'") {
                val rejection = rejected(source).rejections.single()
                rejection.code shouldBe RejectionCode.SYNTAX_ERROR
                val span = rejection.locus.shouldBeInstanceOf<Locus.SourceSpan>()
                (span.startLine >= 1 && span.startColumn >= 1) shouldBe true
            }
        }
    }

    @Test
    fun `nesting beyond the bound is refused rather than overflowing the stack`() {
        val deep = "define q(X) := " + "(".repeat(QueryParser.MAX_NESTING * 4) +
            "link(X, Y)" + ")".repeat(QueryParser.MAX_NESTING * 4) + "."
        rejected(deep).rejections.single().code shouldBe RejectionCode.SYNTAX_ERROR
    }

    // ------------------------------------------------------------------ totality sweep

    /**
     * `[QRY1-REJECT-03]`'s totality, generatively. The corpus is built by **mutating valid
     * sources** rather than by generating text from scratch: a from-scratch generator drifts
     * toward the shapes the parser already handles, which is exactly the drift that would
     * make this test vacuous. Truncations, deletions, duplications and injected garbage
     * start from text that parses and walk away from it.
     *
     * Deterministic seed on purpose — a discovered failure must be reproducible.
     */
    @Test
    fun `parsing never throws over a generated corpus of malformed inputs`() {
        val random = Random(20260911)
        val garbage = "()[]{},.;:!@#$%^&*-+=<>?/\\\"'`~|".toList()
        var examined = 0

        VALID_SOURCES.forEach { source ->
            val mutants = mutableListOf<String>()

            // Every truncation, including the empty prefix.
            for (cut in 0..source.length) mutants += source.substring(0, cut)

            repeat(40) {
                // One character deleted.
                val i = random.nextInt(source.length)
                mutants += source.removeRange(i, i + 1)
                // One garbage character injected.
                val j = random.nextInt(source.length + 1)
                mutants += source.substring(0, j) + garbage.random(random) + source.substring(j)
                // A run of unbalanced parens spliced in.
                val k = random.nextInt(source.length + 1)
                val parens = (if (random.nextBoolean()) "(" else ")").repeat(random.nextInt(1, 12))
                mutants += source.substring(0, k) + parens + source.substring(k)
                // A slice duplicated.
                val a = random.nextInt(source.length)
                val b = random.nextInt(a, source.length)
                mutants += source.substring(0, b) + source.substring(a, b) + source.substring(b)
            }

            mutants.forEach { mutant ->
                examined++
                val outcome = runCatching { QueryParser.parse(mutant, catalog) }
                withClue("parse threw on mutant <<<$mutant>>>: ${outcome.exceptionOrNull()}") {
                    outcome.isSuccess shouldBe true
                }
                when (val result = outcome.getOrThrow()) {
                    is ParseResult.Rejected -> withClue("rejection shape for <<<$mutant>>>") {
                        val rejection = result.rejections.single()
                        rejection.code shouldBe RejectionCode.SYNTAX_ERROR
                        val span = rejection.locus.shouldBeInstanceOf<Locus.SourceSpan>()
                        (span.startLine >= 1 && span.startColumn >= 1) shouldBe true
                        (span.endLine >= span.startLine) shouldBe true
                    }
                    // A mutant that still parses is a fine outcome — deleting a body literal
                    // leaves a valid rule. The claim under test is only that nothing throws.
                    is ParseResult.Parsed -> Unit
                }
            }
        }

        withClue("the sweep must actually examine a large corpus, or it proves nothing") {
            (examined > 1000) shouldBe true
        }
    }

    @Test
    fun `empty and whitespace-only input parse to an empty query`() {
        listOf("", "   ", "\n\n", "% just a comment\n", "// just a comment").forEach { source ->
            withClue("source '$source'") {
                val query = parsed(source).query
                query.rules.shouldHaveSize(0)
                query.definitions.shouldHaveSize(0)
                query.catalog shouldBe catalog
            }
        }
    }

    private companion object {
        /**
         * Valid sources the generative sweep mutates. Kept spread across every construct so
         * the mutants reach the whole grammar and not only the rule form.
         */
        val VALID_SOURCES = listOf(
            "reach(X, Y) :- link(X, Y).",
            // Constants AT declared attribute positions, so the mutants reach the Catalog
            // retyping path. Without one of these the sweep never calls `coerce` with a
            // declaration, and a retyping that hands `Term.Const` a value of the wrong
            // runtime type throws its constructor `require` straight past `parse`'s catch —
            // a totality escape the sweep would not see (reviewer's mutation: coercing to
            // LONG returned the `Int` unchanged; only the typing unit test went red).
            "near(X, Y) :- dist(X, Y, 10).",
            "hot(X) :- score(X, 2), flagged(X, true), dist(X, 'b', 7L).",
            "far(X, Y) :- dist(X, Y, D), not blocked(X, Y), D > 10.",
            "@topK(3) best(X) :- score(X, S).",
            "@collectToSet skills(X) :- link(X, Y).",
            "define both(X, Y) := link(X, Y) intersect all blocked(X, Y).",
            "define j(X, Y, Z) := link(X, Y) left outer join dist(Y, Z, D) on Y = Y.",
            "define t(X, Y) := (link(X, Y) union blocked(X, Y)) except link(X, Y).",
            "link('a', \"b\").\nreach(X, Y) :- link(X, Y), X != Y.",
        )
    }
}
