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
}
