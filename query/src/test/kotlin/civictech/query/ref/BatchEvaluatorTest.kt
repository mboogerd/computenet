package civictech.query.ref

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.ComparisonOp
import civictech.query.lower.PlanFixtures
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.Difference
import civictech.query.plan.GroupAggregate
import civictech.query.plan.Intersect
import civictech.query.plan.LogicalPlan
import civictech.query.plan.PlanNode
import civictech.query.plan.Planner
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `BatchEvaluator` per plan node kind (computenet-cab.6.1, `[QRY1-ORA-02]`), over hand-built
 * plans ([PlanFixtures]) and over parser text through [Planner]. Every expected answer is
 * written out by hand from the fixture rows, never recomputed by a second evaluator.
 */
class BatchEvaluatorTest {

    private val f = PlanFixtures

    private fun row(vararg values: Any?): Row = Row(values.toList())

    private fun rows(vararg rows: Row): Set<Row> = rows.toSet()

    private fun plan(source: String, catalog: Catalog): LogicalPlan =
        Planner.plan((QueryParser.parse(source, catalog) as ParseResult.Parsed).query)

    private fun single(root: PlanNode, db: Map<String, Set<Row>>): RelationValue =
        BatchEvaluator.evaluate(LogicalPlan(mapOf("q" to root)), db).getValue("q")

    private fun answer(source: String, catalog: Catalog, db: Map<String, Set<Row>>, root: String): RelationValue =
        BatchEvaluator.evaluate(plan(source, catalog), db).getValue(root)

    // ------------------------------------------------------------------ one test per node kind

    @Test
    fun `Scan answers the relation verbatim, and an absent relation as the empty set`() {
        val r = rows(row(1, 10), row(2, 20))
        single(f.scan("r", "x", "y"), mapOf("r" to r)) shouldBe RelationValue.Rows(r)
        single(f.scan("r", "x", "y"), emptyMap()) shouldBe RelationValue.Rows(emptySet())
    }

    @Test
    fun `Select keeps the rows its comparison holds on`() {
        val db = mapOf("r" to rows(row(1, 2), row(2, 4), row(3, 3)))
        single(f.select(f.scan("r", "x", "y"), f.v("y"), ComparisonOp.GT, f.int(3)), db) shouldBe
            RelationValue.Rows(rows(row(2, 4)))
        single(f.select(f.scan("r", "x", "y"), f.v("x"), ComparisonOp.EQ, f.v("y")), db) shouldBe
            RelationValue.Rows(rows(row(3, 3)))
        single(f.select(f.scan("r", "x", "y"), f.v("x"), ComparisonOp.NE, f.v("y")), db) shouldBe
            RelationValue.Rows(rows(row(1, 2), row(2, 4)))
        single(f.select(f.scan("r", "x", "y"), f.v("y"), ComparisonOp.LE, f.int(3)), db) shouldBe
            RelationValue.Rows(rows(row(1, 2), row(3, 3)))
    }

    @Test
    fun `QRY1 §SEM-01 Project narrows by column name and collapses duplicates`() {
        val db = mapOf("r" to rows(row(1, 10), row(2, 10), row(3, 30)))
        single(f.project(f.scan("r", "x", "y"), "y"), db) shouldBe RelationValue.Rows(rows(row(10), row(30)))
        single(f.project(f.scan("r", "x", "y"), "y", "x"), db) shouldBe
            RelationValue.Rows(rows(row(10, 1), row(10, 2), row(30, 3)))
    }

    @Test
    fun `Join matches on its equi-keys, and an empty key list is the cross product`() {
        val db = mapOf("r" to rows(row(1, 10), row(2, 20)), "s" to rows(row(10, 100), row(10, 101), row(30, 300)))
        single(f.join(f.scan("r", "x", "y"), f.scan("s", "y", "z"), "y"), db) shouldBe
            RelationValue.Rows(rows(row(1, 10, 100), row(1, 10, 101)))

        val cross = mapOf("a" to rows(row(1), row(2)), "b" to rows(row(7), row(8)))
        single(f.join(f.scan("a", "x"), f.scan("b", "z")), cross) shouldBe
            RelationValue.Rows(rows(row(1, 7), row(1, 8), row(2, 7), row(2, 8)))
    }

    @Test
    fun `SemiJoin keeps input rows that have a witness`() {
        val db = mapOf("r" to rows(row(1, 10), row(2, 20)), "s" to rows(row(10), row(10 + 50)))
        single(f.semiJoin(f.scan("r", "x", "y"), f.scan("s", "y"), "y"), db) shouldBe RelationValue.Rows(rows(row(1, 10)))
    }

    @Test
    fun `AntiJoin keeps input rows that have no witness`() {
        val db = mapOf("r" to rows(row(1, 10), row(2, 20)), "s" to rows(row(10)))
        single(f.antiJoin(f.scan("r", "x", "y"), f.scan("s", "y"), "y"), db) shouldBe RelationValue.Rows(rows(row(2, 20)))
    }

    @Test
    fun `Union is the distinct union of its branches`() {
        val db = mapOf("a" to rows(row(1), row(2)), "b" to rows(row(2), row(3)))
        single(f.union(f.scan("a", "x"), f.scan("b", "x")), db) shouldBe RelationValue.Rows(rows(row(1), row(2), row(3)))
    }

    @Test
    fun `Intersect keeps rows both operands hold`() {
        val db = mapOf("a" to rows(row(1), row(2)), "b" to rows(row(2), row(3)))
        val node = Intersect(f.scan("a", "x"), f.scan("b", "x"), listOf("x"), setOf("a", "b"), false)
        single(node, db) shouldBe RelationValue.Rows(rows(row(2)))
    }

    @Test
    fun `Difference keeps left rows the right operand lacks`() {
        val db = mapOf("a" to rows(row(1), row(2)), "b" to rows(row(2), row(3)))
        val node = Difference(f.scan("a", "x"), f.scan("b", "x"), listOf("x"), setOf("a", "b"), false)
        single(node, db) shouldBe RelationValue.Rows(rows(row(1)))
    }

    @Test
    fun `GroupAggregate counts per group, keyed by a Row of the group-by values`() {
        val db = mapOf("r" to rows(row(1, 10), row(1, 11), row(2, 20)))
        single(f.groupAggregate(f.scan("r", "x", "y")), db) shouldBe RelationValue.Groups(mapOf(row(1) to 2L, row(2) to 1L))
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    fun `QRY1 §SEM-01§SEM-02 COUNT population is the distinct body rows`() {
        answer("@count c(X) :- e(X, Y).", f.catalog("e" to 2), mapOf("e" to rows(row(1, 10), row(1, 11))), "c") shouldBe
            RelationValue.Count(2L)

        answer(
            "@count cnt(X, C) :- r(X, C).",
            f.catalog("r" to 2),
            mapOf("r" to rows(row(1, 10), row(1, 11), row(2, 20))),
            "cnt",
        ) shouldBe RelationValue.Groups(mapOf(row(1) to 2L, row(2) to 1L))
    }

    @Test
    fun `a scalar COUNT over an empty input is zero`() {
        answer("@count c(X) :- e(X, Y).", f.catalog("e" to 2), emptyMap(), "c") shouldBe RelationValue.Count(0L)
    }

    @Test
    fun `24-OP-GROUPBY-02 a group whose last row is removed is absent, not zero`() {
        val source = "@count cnt(X, C) :- r(X, C)."
        val catalog = f.catalog("r" to 2)
        answer(source, catalog, mapOf("r" to rows(row(1, 10), row(2, 20))), "cnt") shouldBe
            RelationValue.Groups(mapOf(row(1) to 1L, row(2) to 1L))

        val after = answer(source, catalog, mapOf("r" to rows(row(1, 10))), "cnt").shouldBeInstanceOf<RelationValue.Groups>()
        after.entries shouldBe mapOf(row(1) to 1L)
        after.entries.containsKey(row(2)) shouldBe false
    }

    @Test
    fun `a scalar non-COUNT aggregate is keyed global, over distinct body rows, and absent when empty`() {
        val catalog = f.catalog("r" to 2)
        // (1,10) and (2,10) are two body rows with equal V: the sum is 20, not 10.
        answer("@sum s(V) :- r(X, V).", catalog, mapOf("r" to rows(row(1, 10), row(2, 10))), "s") shouldBe
            RelationValue.Groups(mapOf("global" to 20L))
        answer("@sum s(V) :- r(X, V).", catalog, emptyMap(), "s") shouldBe RelationValue.Groups(emptyMap())
    }

    @Test
    fun `SUM widens to Long and AVG returns a Double`() {
        val catalog = f.catalog("r" to 2)
        val db = mapOf("r" to rows(row(1, 1), row(1, 2), row(2, 7)))
        answer("@sum s(X, V) :- r(X, V).", catalog, db, "s") shouldBe RelationValue.Groups(mapOf(row(1) to 3L, row(2) to 7L))
        val avg = answer("@avg a(X, V) :- r(X, V).", catalog, db, "a").shouldBeInstanceOf<RelationValue.Groups>()
        avg.entries shouldBe mapOf(row(1) to 1.5, row(2) to 7.0)
        avg.entries.getValue(row(1)).shouldBeInstanceOf<Double>()
    }

    @Test
    fun `MIN and MAX answer the extreme column value`() {
        val catalog = f.catalog("r" to 2)
        val db = mapOf("r" to rows(row(1, 5), row(1, 3), row(1, 9)))
        answer("@min m(X, V) :- r(X, V).", catalog, db, "m") shouldBe RelationValue.Groups(mapOf(row(1) to 3))
        answer("@max m(X, V) :- r(X, V).", catalog, db, "m") shouldBe RelationValue.Groups(mapOf(row(1) to 9))
    }

    @Test
    fun `TOP_K is descending, repeats a value by multiplicity, and caps at k`() {
        val catalog = f.catalog("r" to 3)
        // Two distinct body rows carry V = 5, so 5 appears twice.
        val db = mapOf("r" to rows(row(1, 5, 100), row(1, 5, 101), row(1, 7, 102), row(1, 1, 103)))
        answer("@topK(3) t(X, V) :- r(X, V, W).", catalog, db, "t") shouldBe RelationValue.Groups(mapOf(row(1) to listOf(7, 5, 5)))
    }

    @Test
    fun `COLLECT_TO_SET collects whole input rows per group`() {
        val catalog = f.catalog("r" to 2)
        val db = mapOf("r" to rows(row(1, 10), row(1, 11), row(2, 20)))
        answer("@collectToSet s(X, Y) :- r(X, Y).", catalog, db, "s") shouldBe RelationValue.Groups(
            mapOf(row(1) to setOf(row(1, 10), row(1, 11)), row(2) to setOf(row(2, 20))),
        )
    }

    // ------------------------------------------------------------------ outer joins

    private val outerCatalog = f.catalog("a" to 2, "b" to 2)
    private val outerDb = mapOf("a" to rows(row(1, 10), row(2, 20)), "b" to rows(row(2, 200), row(3, 300)))

    private fun outer(side: String): RelationValue =
        answer("define j(K, A, B) := a(K, A) $side outer join b(K, B) on K = K.", outerCatalog, outerDb, "j")

    @Test
    fun `LEFT outer join pads unmatched left rows with null`() {
        outer("left") shouldBe RelationValue.Rows(rows(row(1, 10, null), row(2, 20, 200)))
    }

    @Test
    fun `RIGHT outer join pads unmatched right rows, which carry their own key value`() {
        outer("right") shouldBe RelationValue.Rows(rows(row(2, 20, 200), row(3, null, 300)))
    }

    @Test
    fun `FULL outer join is matched plus both padded sides`() {
        outer("full") shouldBe RelationValue.Rows(rows(row(1, 10, null), row(2, 20, 200), row(3, null, 300)))
    }

    // ------------------------------------------------------------------ self-join

    @Test
    fun `a self-join answers the two-hop fold on a fixed edge set`() {
        val edges = rows(row(1, 2), row(2, 3), row(2, 4), row(3, 1), row(5, 5))
        // The GatingEvidenceTest.selfJoinMinus fold shape, with nothing blocked and no filter.
        val expected = edges.flatMap { first ->
            edges.filter { it.values[0] == first.values[1] }.map { Row(listOf(first.values[0], it.values[1])) }
        }.toSet()
        expected shouldBe rows(row(1, 3), row(1, 4), row(2, 1), row(3, 2), row(5, 5))

        answer("q(X, Z) :- e(X, Y), e(Y, Z).", f.catalog("e" to 2), mapOf("e" to edges), "q") shouldBe
            RelationValue.Rows(expected)
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `QRY1 §ORA-11 a column no input produces throws naming the root and node`() {
        val ex = shouldThrow<IllegalStateException> {
            BatchEvaluator.evaluate(
                LogicalPlan(mapOf("good" to f.scan("r", "x", "y"), "bad" to f.project(f.scan("r", "x", "y"), "zz"))),
                mapOf("r" to rows(row(1, 2))),
            )
        }
        ex.message shouldContain "root 'bad'"
        ex.message shouldContain "Project"
        ex.message shouldContain "zz"
    }

    @Test
    fun `QRY1 §ORA-11 mixed-type comparison operands throw naming the root and node`() {
        val ex = shouldThrow<IllegalStateException> {
            single(f.select(f.scan("r", "x", "y"), f.v("y"), ComparisonOp.EQ, f.str("three")), mapOf("r" to rows(row(1, 3))))
        }
        ex.message shouldContain "root 'q'"
        ex.message shouldContain "Select"
    }

    @Test
    fun `QRY1 §ORA-11 SUM and AVG over a non-integer value throw naming the root and node`() {
        val db = mapOf("r" to rows(row(1, "ten")))
        for (kind in listOf(AggregateKind.SUM, AggregateKind.AVG)) {
            val node = GroupAggregate(
                input = f.scan("r", "x", "y"),
                groupByColumns = listOf("x"),
                aggregatedColumn = "y",
                aggregate = Aggregate(kind),
                outputColumn = "y",
                outputColumns = listOf("x", "y"),
                provenance = setOf("r"),
                keyPreserving = false,
            )
            val ex = shouldThrow<IllegalStateException> { single(node, db) }
            ex.message shouldContain "root 'q'"
            ex.message shouldContain "GroupAggregate"
            ex.message shouldContain "$kind"
        }
    }

    @Test
    fun `an aggregate below the root throws rather than answering`() {
        val ex = shouldThrow<IllegalStateException> {
            single(f.project(f.groupAggregate(f.scan("r", "x", "y")), "x"), mapOf("r" to rows(row(1, 2))))
        }
        ex.message shouldContain "root 'q'"
        ex.message shouldContain "GroupAggregate"
    }
}
