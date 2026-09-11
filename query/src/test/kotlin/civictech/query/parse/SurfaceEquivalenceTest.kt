package civictech.query.parse

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.JoinKey
import civictech.query.ast.OuterJoinSide
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `[QRY1-LANG-02]`'s closure: **no construct is expressible in one surface and not the
 * other**. For each supported construct this asserts `parser output == builder output` as
 * AST *values* — `Query` structural equality, which `data class` equality gives over every
 * property, all the way down.
 *
 * Structural equality is the point, and is not incidental (cab.2-D1):
 *
 * - Explicitly **not** a re-parse of a pretty-printed form. A printer/parser round-trip
 *   would be testing the printer, and would still pass if the two surfaces agreed on text
 *   while producing different ASTs.
 * - It is why spans live in [SpanTable], outside the AST: a `span` on any node would make
 *   the parser's `Query` unequal to the builder's by construction, and this test could not
 *   be written at all (`civictech.query.ast.NoSpanInAstTest` guards that precondition).
 *
 * The construct lists below are closed against the AST's own enums — [AggregateKind],
 * [SetOpKind], [OuterJoinSide] — so a construct added to the AST without a line here fails
 * this test rather than quietly escaping the comparison.
 */
class SurfaceEquivalenceTest {

    private val catalog = Catalog(
        mapOf(
            "candSkills" to RelationSchema(
                listOf(Attribute("candidate", AttrType.STRING), Attribute("skill", AttrType.STRING)),
                rowKey = setOf("candidate", "skill"),
            ),
            "jobSkills" to RelationSchema(
                listOf(Attribute("job", AttrType.STRING), Attribute("skill", AttrType.STRING)),
                rowKey = setOf("job", "skill"),
            ),
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
            "reading" to RelationSchema(
                listOf(
                    Attribute("sensor", AttrType.STRING),
                    Attribute("level", AttrType.DOUBLE),
                    Attribute("on", AttrType.BOOL),
                ),
            ),
        ),
    )

    /** `link(X, Y)` and `blocked(X, Y)`, the two leaves every algebra case below joins. */
    private val linkAtom = Atom("link", listOf(Term.Var("X"), Term.Var("Y")))
    private val blockedAtom = Atom("blocked", listOf(Term.Var("X"), Term.Var("Y")))
    private val xyKey = JoinKey(Term.Var("X"), Term.Var("Y"))

    private fun parse(text: String): Query =
        withClue("expected '$text' to parse, got ${QueryParser.parse(text, catalog)}") {
            QueryParser.parse(text, catalog).shouldBeInstanceOf<ParseResult.Parsed>().query
        }

    /** The equivalence assertion itself: two surfaces, one AST value. */
    private fun equivalent(text: String, built: Query) {
        withClue("text surface and query { } disagree for:\n$text") {
            parse(text) shouldBe built
        }
    }

    // ------------------------------------------------------------------ the feature example

    /**
     * The skillmatch join, written once as Datalog text and once through `query { }`.
     *
     * The shape is read from `demo/skillmatch/.../SkillMatchApp.kt`'s `SkillPipeline`:
     * `candSkills ⋈ jobSkills on skill`, combined per (candidate, job, skill) — the
     * `JoinSetCell` whose `leftKey` and `rightKey` are both `skill` and whose `combine`
     * builds `Match(cs.candidate, js.job, cs.skill)`. Here it is one rule, and the
     * hand-wiring is gone.
     */
    @Test
    fun `the skillmatch join is the same Query from text and from the builder`() {
        equivalent(
            "matches(C, J, S) :- candSkills(C, S), jobSkills(J, S).",
            query(catalog) {
                val c = v("C")
                val j = v("J")
                val s = v("S")
                rule(derived("matches")(c, j, s)) {
                    +relation("candSkills")(c, s)
                    +relation("jobSkills")(j, s)
                }
            },
        )
    }

    // ------------------------------------------------------------------ rule constructs

    @Test
    fun `a positive body atom agrees across surfaces`() {
        equivalent(
            "reach(X, Y) :- link(X, Y).",
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                rule(derived("reach")(x, y)) { +relation("link")(x, y) }
            },
        )
    }

    @Test
    fun `a negated body atom agrees across surfaces`() {
        equivalent(
            "open(X, Y) :- link(X, Y), not blocked(X, Y).",
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                rule(derived("open")(x, y)) {
                    +relation("link")(x, y)
                    not(relation("blocked")(x, y))
                }
            },
        )
    }

    @Test
    fun `every comparison operator agrees across surfaces`() {
        // The comparison is written as a two-argument lambda rather than closing over the
        // terms: `v` and `const` are QueryScope members and are only in scope inside the
        // query { } block, while these case lambdas are built outside it.
        val cases = listOf<Triple<ComparisonOp, String, RuleScope.(Term, Term) -> Unit>>(
            Triple(ComparisonOp.EQ, "=", { left, right -> left eq right }),
            Triple(ComparisonOp.NE, "!=", { left, right -> left ne right }),
            Triple(ComparisonOp.LT, "<", { left, right -> left lt right }),
            Triple(ComparisonOp.LE, "<=", { left, right -> left le right }),
            Triple(ComparisonOp.GT, ">", { left, right -> left gt right }),
            Triple(ComparisonOp.GE, ">=", { left, right -> left ge right }),
        )
        cases.forEach { (_, spelling, comparison) ->
            equivalent(
                "far(X, Y) :- dist(X, Y, D), D $spelling 10L.",
                query(catalog) {
                    val x = v("X")
                    val y = v("Y")
                    val d = v("D")
                    rule(derived("far")(x, y)) {
                        +relation("dist")(x, y, d)
                        comparison(d, const(10L))
                    }
                },
            )
        }
        withClue("the case list must cover every ComparisonOp, or a construct escapes") {
            cases.map { it.first }.toSet() shouldBe ComparisonOp.entries.toSet()
        }
    }

    @Test
    fun `every constant type agrees across surfaces`() {
        equivalent(
            "p(X) :- link(X, 'a'), X = 1, X = 2L, X = 3.5, X = true.",
            query(catalog) {
                val x = v("X")
                rule(derived("p")(x)) {
                    +relation("link")(x, const("a"))
                    x eq const(1)
                    x eq const(2L)
                    x eq const(3.5)
                    x eq const(true)
                }
            },
        )
    }

    /**
     * The catalog-typing half of the surface: the text says `10`, the builder says
     * `const(10L)`, and they agree because `dist.d` is declared `LONG`. Without the parser's
     * `Catalog` retyping these would be `Const(10, INT)` and `Const(10L, LONG)` — unequal,
     * and the two surfaces would not be one language.
     */
    @Test
    fun `a catalog-typed constant agrees with the builder's explicitly typed one`() {
        equivalent(
            "near(X, Y) :- dist(X, Y, 10).",
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                rule(derived("near")(x, y)) { +relation("dist")(x, y, const(10L)) }
            },
        )
    }

    /**
     * The other two retyping branches, `DOUBLE` and `BOOL`, given the same parser-vs-builder
     * equality case the `LONG` branch above has. Added by the cab.2 feature review: both
     * branches were reachable only through `QueryParserTest`'s generative totality sweep,
     * which asserts nothing about the value a retyping produces — only that producing it
     * does not throw. Mutating `coerce`'s `DOUBLE` arm to hand back the un-converted `Int`,
     * and its `BOOL` arm to hand back a `String`, each reddened that sweep and nothing else
     * in the 188-test suite; this case is the assertion that the retyped *value* is the one
     * the builder writes explicitly.
     *
     * `3` at a `DOUBLE`-declared position must become `Const(3.0, DOUBLE)`, not
     * `Const(3, INT)`; `true` at a `BOOL`-declared position stays `Const(true, BOOL)`.
     */
    @Test
    fun `catalog-typed DOUBLE and BOOL constants agree with the builder's explicit ones`() {
        equivalent(
            "warm(X) :- reading(X, 3, true).",
            query(catalog) {
                val x = v("X")
                rule(derived("warm")(x)) { +relation("reading")(x, const(3.0), const(true)) }
            },
        )
    }

    @Test
    fun `a fact - a rule with an empty body - agrees across surfaces`() {
        equivalent(
            "link('a', 'b').",
            query(catalog) { rule(relation("link")(const("a"), const("b"))) { } },
        )
    }

    @Test
    fun `each of the seven aggregate head annotations agrees across surfaces`() {
        val cases = listOf<Triple<AggregateKind, String, QueryScope.() -> Aggregate>>(
            Triple(AggregateKind.COUNT, "@count", { count() }),
            Triple(AggregateKind.SUM, "@sum", { sum() }),
            Triple(AggregateKind.AVG, "@avg", { avg() }),
            Triple(AggregateKind.MIN, "@min", { min() }),
            Triple(AggregateKind.MAX, "@max", { max() }),
            Triple(AggregateKind.COLLECT_TO_SET, "@collectToSet", { collectToSet() }),
            Triple(AggregateKind.TOP_K, "@topK(3)", { topK(3) }),
        )
        cases.forEach { (_, annotation, builderAggregate) ->
            equivalent(
                "$annotation deg(X) :- link(X, Y).",
                query(catalog) {
                    val x = v("X")
                    val y = v("Y")
                    rule(derived("deg")(x), aggregate = builderAggregate()) {
                        +relation("link")(x, y)
                    }
                },
            )
        }
        withClue("the case list must cover every AggregateKind, or a construct escapes") {
            cases.map { it.first }.toSet() shouldBe AggregateKind.entries.toSet()
        }
    }

    // ------------------------------------------------------------------ relational algebra

    @Test
    fun `each set operation agrees across surfaces, in the set and the ALL spelling`() {
        val setSpellings = listOf<Triple<SetOpKind, String, RelationalScope.() -> RelationalExpr>>(
            Triple(SetOpKind.UNION, "union", { rel(linkAtom) union rel(blockedAtom) }),
            Triple(SetOpKind.INTERSECTION, "intersect", { rel(linkAtom) intersect rel(blockedAtom) }),
            Triple(SetOpKind.DIFFERENCE, "except", { rel(linkAtom) difference rel(blockedAtom) }),
        )
        val allSpellings = listOf<Triple<SetOpKind, String, RelationalScope.() -> RelationalExpr>>(
            Triple(SetOpKind.UNION, "union all", { rel(linkAtom) unionAll rel(blockedAtom) }),
            Triple(
                SetOpKind.INTERSECTION,
                "intersect all",
                { rel(linkAtom) intersectAll rel(blockedAtom) },
            ),
            Triple(
                SetOpKind.DIFFERENCE,
                "except all",
                { rel(linkAtom) differenceAll rel(blockedAtom) },
            ),
        )

        (setSpellings + allSpellings).forEach { (_, keyword, expr) ->
            equivalent(
                "define both(X, Y) := link(X, Y) $keyword blocked(X, Y).",
                query(catalog) {
                    val x = v("X")
                    val y = v("Y")
                    define(derived("both")(x, y)) { expr() }
                },
            )
        }
        withClue("both spelling lists must cover every SetOpKind") {
            setSpellings.map { it.first }.toSet() shouldBe SetOpKind.entries.toSet()
            allSpellings.map { it.first }.toSet() shouldBe SetOpKind.entries.toSet()
        }
    }

    @Test
    fun `each outer join side agrees across surfaces`() {
        val cases = listOf<Triple<OuterJoinSide, String, RelationalScope.() -> RelationalExpr>>(
            Triple(
                OuterJoinSide.LEFT,
                "left",
                { leftJoin(rel(linkAtom), rel(blockedAtom), xyKey) },
            ),
            Triple(
                OuterJoinSide.RIGHT,
                "right",
                { rightJoin(rel(linkAtom), rel(blockedAtom), xyKey) },
            ),
            Triple(
                OuterJoinSide.FULL,
                "full",
                { fullJoin(rel(linkAtom), rel(blockedAtom), xyKey) },
            ),
        )
        cases.forEach { (_, keyword, expr) ->
            equivalent(
                "define j(X, Y) := link(X, Y) $keyword outer join blocked(X, Y) on X = Y.",
                query(catalog) {
                    val x = v("X")
                    val y = v("Y")
                    define(derived("j")(x, y)) { expr() }
                },
            )
        }
        withClue("the case list must cover every OuterJoinSide") {
            cases.map { it.first }.toSet() shouldBe OuterJoinSide.entries.toSet()
        }
    }

    @Test
    fun `several join keys agree across surfaces`() {
        equivalent(
            "define j(X, Y) := link(X, Y) left outer join blocked(X, Y) on X = X, Y = Y.",
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                define(derived("j")(x, y)) {
                    leftJoin(
                        rel(relation("link")(x, y)),
                        rel(relation("blocked")(x, y)),
                        x matches x,
                        y matches y,
                    )
                }
            },
        )
    }

    @Test
    fun `a nested, parenthesised expression agrees across surfaces`() {
        equivalent(
            "define t(X, Y) := (link(X, Y) union blocked(X, Y)) except link(X, Y).",
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                define(derived("t")(x, y)) {
                    (rel(relation("link")(x, y)) union rel(relation("blocked")(x, y)))
                        .difference(rel(relation("link")(x, y)))
                }
            },
        )
    }

    @Test
    fun `rules and definitions in one source agree across surfaces, in statement order`() {
        equivalent(
            """
            reach(X, Y) :- link(X, Y).
            define both(X, Y) := link(X, Y) union blocked(X, Y).
            @count deg(X) :- link(X, Y).
            """.trimIndent(),
            query(catalog) {
                val x = v("X")
                val y = v("Y")
                rule(derived("reach")(x, y)) { +relation("link")(x, y) }
                define(derived("both")(x, y)) {
                    rel(relation("link")(x, y)) union rel(relation("blocked")(x, y))
                }
                rule(derived("deg")(x), aggregate = count()) { +relation("link")(x, y) }
            },
        )
    }
}
