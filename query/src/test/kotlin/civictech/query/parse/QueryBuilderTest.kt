package civictech.query.parse

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.JoinKey
import civictech.query.ast.Literal
import civictech.query.ast.OuterJoinSide
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * `[QRY1-LANG-02]`'s builder half: `query { }` expresses every construct of the surface
 * language and produces the shared `civictech.query.ast` vocabulary — asserted against
 * hand-built AST values, so the test says what the builder produces rather than only that it
 * produces something.
 *
 * The cross-surface equivalence test (parser output == builder output) is the parser task's,
 * since it needs both producers; this file is the builder-side construct coverage it will
 * rest on.
 */
class QueryBuilderTest {

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
        ),
    )

    private val x = Term.Var("X")
    private val y = Term.Var("Y")
    private val d = Term.Var("D")

    // ---------------------------------------------------------------- rules

    @Test
    fun `a rule with a positive atom, a negated atom and a comparison builds the literal body`() {
        val built = query(catalog) {
            val link = relation("link")
            val blocked = relation("blocked")
            val dist = relation("dist")
            rule(derived("edge")(v("X"), v("Y"))) {
                +link(v("X"), v("Y"))
                not(blocked(v("X"), v("Y")))
                +dist(v("X"), v("Y"), v("D"))
                v("D") le const(10L)
            }
        }

        built shouldBe Query(
            rules = listOf(
                Rule(
                    head = Atom("edge", listOf(x, y)),
                    body = listOf(
                        Literal.Positive(Atom("link", listOf(x, y))),
                        Literal.Negated(Atom("blocked", listOf(x, y))),
                        Literal.Positive(Atom("dist", listOf(x, y, d))),
                        Literal.Comparison(d, ComparisonOp.LE, Term.Const(10L, AttrType.LONG)),
                    ),
                ),
            ),
            catalog = catalog,
        )
    }

    @Test
    fun `every comparison operator has a builder spelling`() {
        val built = query {
            rule(derived("q")(v("X"))) {
                v("X") eq v("Y")
                v("X") ne v("Y")
                v("X") lt v("Y")
                v("X") le v("Y")
                v("X") gt v("Y")
                v("X") ge v("Y")
            }
        }

        built.rules.single().body.map { (it as Literal.Comparison).op } shouldBe
            listOf(
                ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.LT,
                ComparisonOp.LE, ComparisonOp.GT, ComparisonOp.GE,
            )
    }

    @Test
    fun `rules and definitions are collected in the order they are written`() {
        val built = query {
            rule(derived("a")(v("X"))) { +derived("r")(v("X")) }
            rule(derived("b")(v("X"))) { +derived("s")(v("X")) }
            define(derived("c")(v("X"))) { rel(derived("a")(v("X"))) union rel(derived("b")(v("X"))) }
        }

        built.rules.map { it.head.predicate } shouldBe listOf("a", "b")
        built.definitions.map { it.head.predicate } shouldBe listOf("c")
    }

    // ----------------------------------------------------------- constants

    @Test
    fun `each constant overload carries the AttrType its Kotlin type implies`() {
        val built = query {
            rule(derived("q")(v("X"))) {
                v("X") eq const(1)
                v("X") eq const(2L)
                v("X") eq const(3.5)
                v("X") eq const("s")
                v("X") eq const(true)
            }
        }

        built.rules.single().body.map { (it as Literal.Comparison).right } shouldBe listOf(
            Term.Const(1, AttrType.INT),
            Term.Const(2L, AttrType.LONG),
            Term.Const(3.5, AttrType.DOUBLE),
            Term.Const("s", AttrType.STRING),
            Term.Const(true, AttrType.BOOL),
        )
    }

    // ---------------------------------------------------------- aggregates

    @Test
    fun `the builder spells exactly the seven aggregate kinds, topK carrying its k`() {
        val built = query {
            rule(derived("a")(v("X")), aggregate = count()) { +derived("r")(v("X")) }
            rule(derived("b")(v("X")), aggregate = sum()) { +derived("r")(v("X")) }
            rule(derived("c")(v("X")), aggregate = avg()) { +derived("r")(v("X")) }
            rule(derived("d")(v("X")), aggregate = min()) { +derived("r")(v("X")) }
            rule(derived("e")(v("X")), aggregate = max()) { +derived("r")(v("X")) }
            rule(derived("f")(v("X")), aggregate = topK(3)) { +derived("r")(v("X")) }
            rule(derived("g")(v("X")), aggregate = collectToSet()) { +derived("r")(v("X")) }
        }

        built.rules.map { it.aggregate } shouldBe listOf(
            Aggregate(AggregateKind.COUNT),
            Aggregate(AggregateKind.SUM),
            Aggregate(AggregateKind.AVG),
            Aggregate(AggregateKind.MIN),
            Aggregate(AggregateKind.MAX),
            Aggregate(AggregateKind.TOP_K, k = 3),
            Aggregate(AggregateKind.COLLECT_TO_SET),
        )
        // Non-vacuity against [QRY1-LANG-03]'s closed list: the builder covers every kind.
        built.rules.mapNotNull { it.aggregate?.kind }.toSet() shouldBe AggregateKind.values().toSet()
    }

    @Test
    fun `a rule without an aggregate annotation carries none`() {
        query { rule(derived("a")(v("X"))) { +derived("r")(v("X")) } }
            .rules.single().aggregate shouldBe null
    }

    // ------------------------------------------------------- set operations

    @Test
    fun `each set operation has a distinct and an ALL builder spelling`() {
        val built = query {
            val a = derived("a")
            val b = derived("b")
            define(derived("u")(v("X"))) { rel(a(v("X"))) union rel(b(v("X"))) }
            define(derived("ua")(v("X"))) { rel(a(v("X"))) unionAll rel(b(v("X"))) }
            define(derived("i")(v("X"))) { rel(a(v("X"))) intersect rel(b(v("X"))) }
            define(derived("ia")(v("X"))) { rel(a(v("X"))) intersectAll rel(b(v("X"))) }
            define(derived("d")(v("X"))) { rel(a(v("X"))) difference rel(b(v("X"))) }
            define(derived("da")(v("X"))) { rel(a(v("X"))) differenceAll rel(b(v("X"))) }
        }

        val left = RelationalExpr.Relation(Atom("a", listOf(x)))
        val right = RelationalExpr.Relation(Atom("b", listOf(x)))
        built.definitions.map { it.expr } shouldBe listOf(
            RelationalExpr.SetOp(SetOpKind.UNION, left, right, all = false),
            RelationalExpr.SetOp(SetOpKind.UNION, left, right, all = true),
            RelationalExpr.SetOp(SetOpKind.INTERSECTION, left, right, all = false),
            RelationalExpr.SetOp(SetOpKind.INTERSECTION, left, right, all = true),
            RelationalExpr.SetOp(SetOpKind.DIFFERENCE, left, right, all = false),
            RelationalExpr.SetOp(SetOpKind.DIFFERENCE, left, right, all = true),
        )
    }

    @Test
    fun `the ALL spellings really set the flag - not one of them is a distinct operation`() {
        // cab.2-D3: the builder STORES the flag and judges nothing. If `unionAll` produced
        // `all = false` the definitions above would still build and still be well-typed; only
        // this assertion (and the equality above) separates the two spellings.
        val built = query {
            define(derived("ua")(v("X"))) { rel(derived("a")(v("X"))) unionAll rel(derived("b")(v("X"))) }
            define(derived("ia")(v("X"))) { rel(derived("a")(v("X"))) intersectAll rel(derived("b")(v("X"))) }
            define(derived("da")(v("X"))) { rel(derived("a")(v("X"))) differenceAll rel(derived("b")(v("X"))) }
        }
        built.definitions.map { (it.expr as RelationalExpr.SetOp).all } shouldBe listOf(true, true, true)
    }

    @Test
    fun `set operations nest inside one definition`() {
        val built = query {
            define(derived("t")(v("X"))) {
                (rel(derived("a")(v("X"))) union rel(derived("b")(v("X")))) difference rel(derived("c")(v("X")))
            }
        }

        built.definitions.single().expr shouldBe RelationalExpr.SetOp(
            SetOpKind.DIFFERENCE,
            RelationalExpr.SetOp(
                SetOpKind.UNION,
                RelationalExpr.Relation(Atom("a", listOf(x))),
                RelationalExpr.Relation(Atom("b", listOf(x))),
            ),
            RelationalExpr.Relation(Atom("c", listOf(x))),
        )
    }

    // --------------------------------------------------------- outer joins

    @Test
    fun `all three outer joins have a builder spelling carrying their key equalities`() {
        val built = query {
            val a = derived("a")
            val b = derived("b")
            define(derived("l")(v("X"), v("Y"))) {
                leftJoin(rel(a(v("X"))), rel(b(v("Y"))), v("X") matches v("Y"))
            }
            define(derived("r")(v("X"), v("Y"))) {
                rightJoin(rel(a(v("X"))), rel(b(v("Y"))), v("X") matches v("Y"))
            }
            define(derived("f")(v("X"), v("Y"))) {
                fullJoin(rel(a(v("X"))), rel(b(v("Y"))), v("X") matches v("Y"))
            }
        }

        val left = RelationalExpr.Relation(Atom("a", listOf(x)))
        val right = RelationalExpr.Relation(Atom("b", listOf(y)))
        built.definitions.map { it.expr } shouldBe listOf(
            RelationalExpr.OuterJoin(OuterJoinSide.LEFT, left, right, listOf(JoinKey(x, y))),
            RelationalExpr.OuterJoin(OuterJoinSide.RIGHT, left, right, listOf(JoinKey(x, y))),
            RelationalExpr.OuterJoin(OuterJoinSide.FULL, left, right, listOf(JoinKey(x, y))),
        )
        built.definitions.map { (it.expr as RelationalExpr.OuterJoin).side }.toSet() shouldBe
            OuterJoinSide.values().toSet()
    }

    @Test
    fun `an outer join accepts several key equalities and keeps their order`() {
        val built = query {
            define(derived("j")(v("X"))) {
                leftJoin(
                    rel(derived("a")(v("X"), v("Y"))),
                    rel(derived("b")(v("P"), v("Q"))),
                    v("X") matches v("P"),
                    v("Y") matches v("Q"),
                )
            }
        }

        (built.definitions.single().expr as RelationalExpr.OuterJoin).on shouldBe
            listOf(JoinKey(Term.Var("X"), Term.Var("P")), JoinKey(Term.Var("Y"), Term.Var("Q")))
    }

    @Test
    fun `an outer join and a set operation compose in one expression`() {
        val built = query {
            define(derived("t")(v("X"))) {
                leftJoin(
                    rel(derived("a")(v("X"))) union rel(derived("b")(v("X"))),
                    rel(derived("c")(v("Y"))),
                    v("X") matches v("Y"),
                )
            }
        }

        val join = built.definitions.single().expr as RelationalExpr.OuterJoin
        (join.left as RelationalExpr.SetOp).kind shouldBe SetOpKind.UNION
    }

    // -------------------------------------------- catalog-derived typing

    @Test
    fun `a relation reference is checked against the Catalog`() {
        val error = shouldThrow<IllegalArgumentException> {
            query(catalog) { rule(derived("q")(v("X"))) { +relation("nosuch")(v("X")) } }
        }
        error.message!! shouldContain "not declared in the Catalog"
    }

    @Test
    fun `applying a catalog relation at the wrong arity fails at the builder call`() {
        val error = shouldThrow<IllegalArgumentException> {
            query(catalog) { rule(derived("q")(v("X"))) { +relation("link")(v("X")) } }
        }
        error.message!! shouldContain "declares 2 attribute(s)"
    }

    @Test
    fun `a derived relation is unchecked - it has no Catalog schema`() {
        val built = query(catalog) {
            rule(derived("q")(v("X"), v("Y"), v("Z"))) { +derived("anything")(v("X")) }
        }
        built.rules.single().head.terms.size shouldBe 3
    }

    @Test
    fun `attribute-named application orders terms by the declared schema`() {
        val built = query(catalog) {
            rule(derived("q")(v("X"))) {
                // Written out of declaration order on purpose: the schema, not the call, decides.
                +relation("dist").by("d" to v("D"), "dst" to v("Y"), "src" to v("X"))
            }
        }

        (built.rules.single().body.single() as Literal.Positive).atom shouldBe
            Atom("dist", listOf(x, y, d))
    }

    @Test
    fun `attribute-named application rejects an unknown attribute`() {
        val error = shouldThrow<IllegalArgumentException> {
            query(catalog) {
                rule(derived("q")(v("X"))) { +relation("link").by("src" to v("X"), "nope" to v("Y")) }
            }
        }
        error.message!! shouldContain "declares no attribute(s) [nope]"
    }

    @Test
    fun `attribute-named application rejects a partially bound relation`() {
        val error = shouldThrow<IllegalArgumentException> {
            query(catalog) { rule(derived("q")(v("X"))) { +relation("link").by("src" to v("X")) } }
        }
        error.message!! shouldContain "unbound"
    }

    // ------------------------------------------------- the builder judges nothing

    @Test
    fun `an unsafe rule builds - safety analysis is a sibling feature's`() {
        // `q(X) :- r(X, Y), not s(X, Z).` — Z is bound by no positive atom, which is
        // [QRY1-LANG-07]'s UNSAFE_RULE. The BUILDER must still produce it, or the analysis
        // feature would have no AST to refuse.
        val built = query {
            rule(derived("q")(v("X"))) {
                +derived("r")(v("X"), v("Y"))
                not(derived("s")(v("X"), v("Z")))
            }
        }
        built.rules.single().body.size shouldBe 2
    }

    @Test
    fun `a head redefining a declared EDB relation builds - EDB_REDEFINED is a sibling's`() {
        val built = query(catalog) { rule(derived("link")(v("X"), v("Y"))) { +derived("s")(v("X"), v("Y")) } }
        built.rules.single().head.predicate shouldBe "link"
    }

    @Test
    fun `a self-recursive rule builds - RECURSION_UNSUPPORTED is a sibling's`() {
        val built = query {
            rule(derived("path")(v("X"), v("Z"))) {
                +derived("path")(v("X"), v("Y"))
                +derived("edge")(v("Y"), v("Z"))
            }
        }
        built.rules.single().head.predicate shouldBe "path"
    }

    // ------------------------------------------------------------ plumbing

    @Test
    fun `the builder carries the Catalog it was given onto the Query`() {
        query(catalog) { }.catalog shouldBe catalog
    }

    @Test
    fun `query with no Catalog builds against an empty one`() {
        query { } shouldBe Query(rules = emptyList(), catalog = Catalog(emptyMap()))
    }

    @Test
    fun `a built Query carries no span or producer marker - it equals the hand-built AST`() {
        // cab.2-D1: builder output is structurally equal to a Query assembled by hand, which
        // is only possible because no node records who produced it or where it came from.
        val handBuilt = Query(
            rules = listOf(
                Rule(
                    head = Atom("edge", listOf(x, y)),
                    body = listOf(Literal.Positive(Atom("link", listOf(x, y)))),
                ),
            ),
            catalog = catalog,
            definitions = listOf(
                Definition(
                    Atom("either", listOf(x, y)),
                    RelationalExpr.SetOp(
                        SetOpKind.UNION,
                        RelationalExpr.Relation(Atom("edge", listOf(x, y))),
                        RelationalExpr.Relation(Atom("link", listOf(x, y))),
                    ),
                ),
            ),
        )

        val built = query(catalog) {
            rule(derived("edge")(v("X"), v("Y"))) { +relation("link")(v("X"), v("Y")) }
            define(derived("either")(v("X"), v("Y"))) {
                rel(derived("edge")(v("X"), v("Y"))) union rel(relation("link")(v("X"), v("Y")))
            }
        }

        built shouldBe handBuilt
    }
}
