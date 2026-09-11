package civictech.query.ast

import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * `[QRY1-LANG-06]` (AST half): the AST is `Serializable` pure data containing no function
 * value, asserted by a round-trip through `ObjectOutputStream`/`ObjectInputStream`. The
 * representative [Query] below covers `Var`/`Const`, negation, a comparison literal and an
 * aggregate-annotated head, over a multi-rule query, per the task's Tests clause.
 */
class QuerySerializationTest {

    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(value) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
    }

    /**
     * `edge(X, Y) :- link(X, Y), not blocked(X, Y).`
     * `nearby(X, Y) :- edge(X, Y), dist(X, Y, D), D <= 10.`
     * `count_edges(X, count) :- edge(X, _Y).`  (aggregate-annotated head)
     */
    private fun representativeQuery(): Query {
        val x = Term.Var("X")
        val y = Term.Var("Y")
        val d = Term.Var("D")
        val threshold = Term.Const(10L, AttrType.LONG)

        val rule1 = Rule(
            head = Atom("edge", listOf(x, y)),
            body = listOf(
                Literal.Positive(Atom("link", listOf(x, y))),
                Literal.Negated(Atom("blocked", listOf(x, y))),
            ),
        )
        val rule2 = Rule(
            head = Atom("nearby", listOf(x, y)),
            body = listOf(
                Literal.Positive(Atom("edge", listOf(x, y))),
                Literal.Positive(Atom("dist", listOf(x, y, d))),
                Literal.Comparison(d, ComparisonOp.LE, threshold),
            ),
        )
        val rule3 = Rule(
            head = Atom("count_edges", listOf(x)),
            body = listOf(Literal.Positive(Atom("edge", listOf(x, Term.Var("_Y"))))),
            aggregate = Aggregate(AggregateKind.COUNT),
        )

        val catalog = Catalog(
            mapOf(
                "link" to RelationSchema(listOf(Attribute("src", AttrType.STRING), Attribute("dst", AttrType.STRING))),
                "blocked" to RelationSchema(listOf(Attribute("src", AttrType.STRING), Attribute("dst", AttrType.STRING))),
                "dist" to RelationSchema(
                    listOf(Attribute("src", AttrType.STRING), Attribute("dst", AttrType.STRING), Attribute("d", AttrType.LONG)),
                ),
            ),
        )

        return Query(rules = listOf(rule1, rule2, rule3), catalog = catalog)
    }

    /**
     * The `[QRY1-LANG-04]` statement vocabulary: a nested set operation carrying the cab.2-D3
     * `all` flag, and an outer join with its key equalities.
     */
    private fun representativeDefinitions(): List<Definition> {
        val x = Term.Var("X")
        val y = Term.Var("Y")
        val a = RelationalExpr.Relation(Atom("a", listOf(x, y)))
        val b = RelationalExpr.Relation(Atom("b", listOf(x, y)))
        val c = RelationalExpr.Relation(Atom("c", listOf(x, y)))

        return listOf(
            Definition(
                Atom("combined", listOf(x, y)),
                RelationalExpr.SetOp(
                    SetOpKind.DIFFERENCE,
                    RelationalExpr.SetOp(SetOpKind.UNION, a, b, all = true),
                    RelationalExpr.SetOp(SetOpKind.INTERSECTION, b, c, all = false),
                ),
            ),
            Definition(
                Atom("joined", listOf(x, y)),
                RelationalExpr.OuterJoin(OuterJoinSide.FULL, a, c, listOf(JoinKey(x, y))),
            ),
        )
    }

    @Test
    fun `a representative multi-rule query round-trips equal to the original`() {
        val query = representativeQuery()
        roundTrip(query) shouldBe query
    }

    @Test
    fun `a topK aggregate annotation round-trips carrying its k`() {
        val query = Query(
            rules = listOf(
                Rule(
                    head = Atom("top", listOf(Term.Var("X"))),
                    body = listOf(Literal.Positive(Atom("scored", listOf(Term.Var("X"), Term.Var("S"))))),
                    aggregate = Aggregate(AggregateKind.TOP_K, k = 3),
                ),
            ),
            catalog = Catalog(emptyMap()),
        )

        val result = roundTrip(query)
        result shouldBe query
        result.rules.single().aggregate shouldBe Aggregate(AggregateKind.TOP_K, k = 3)
    }

    @Test
    fun `set-operation and outer-join definitions round-trip equal to the original`() {
        val query = Query(
            rules = representativeQuery().rules,
            catalog = representativeQuery().catalog,
            definitions = representativeDefinitions(),
        )

        roundTrip(query) shouldBe query
    }

    @Test
    fun `the ALL flag survives the round trip on every set-operation kind`() {
        // cab.2-D3: the flag is STORED and travels with the query, so the rejection feature
        // sees UNION ALL / INTERSECT ALL / EXCEPT ALL on an AST it received over the wire
        // exactly as on one it built locally.
        val a = RelationalExpr.Relation(Atom("a", listOf(Term.Var("X"))))
        val b = RelationalExpr.Relation(Atom("b", listOf(Term.Var("X"))))

        SetOpKind.entries.forEach { kind ->
            listOf(false, true).forEach { all ->
                val query = Query(
                    rules = emptyList(),
                    catalog = Catalog(emptyMap()),
                    definitions = listOf(
                        Definition(
                            Atom("out", listOf(Term.Var("X"))),
                            RelationalExpr.SetOp(kind, a, b, all = all),
                        ),
                    ),
                )
                val restored = roundTrip(query).definitions.single().expr as RelationalExpr.SetOp
                restored.kind shouldBe kind
                restored.all shouldBe all
            }
        }
    }

    @Test
    fun `every outer-join side round-trips with its key equalities`() {
        val x = Term.Var("X")
        val y = Term.Var("Y")
        val a = RelationalExpr.Relation(Atom("a", listOf(x)))
        val b = RelationalExpr.Relation(Atom("b", listOf(y)))

        OuterJoinSide.entries.forEach { side ->
            val join = RelationalExpr.OuterJoin(side, a, b, listOf(JoinKey(x, y)))
            val query = Query(
                rules = emptyList(),
                catalog = Catalog(emptyMap()),
                definitions = listOf(Definition(Atom("out", listOf(x, y)), join)),
            )
            roundTrip(query).definitions.single().expr shouldBe join
        }
    }
}
