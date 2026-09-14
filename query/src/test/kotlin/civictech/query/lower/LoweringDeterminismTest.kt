package civictech.query.lower

import civictech.cell.graph.GraphSpec
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.plan.LogicalPlan
import civictech.query.plan.Planner
import civictech.query.schema.AttrType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * `[QRY1-LOWER-05]`: lowering the same plan twice yields `==` [GraphSpec]s — the whole step
 * list, factories compared by `equals` — with identical `ObjectOutputStream` bytes; and
 * `[QRY1-LOWER-06]`/BS-12's structural half: a serialized-then-deserialized spec is `==` to the
 * original. Applying the deserialized spec to a host is the CompiledQuery task's half.
 */
class LoweringDeterminismTest {

    private val f = PlanFixtures

    /** A plan exercising every rule this task owns, sources shared across two roots. */
    private val plan = LogicalPlan(
        mapOf(
            "p" to f.project(
                f.join(
                    f.select(f.scan("e", "x", "y"), f.v("y"), ComparisonOp.GE, f.int(2)),
                    f.antiJoin(f.semiJoin(f.scan("e", "y", "z"), f.scan("s", "z"), "z"), f.scan("s", "y"), "y"),
                    "y",
                ),
                "x", "z",
            ),
            "q" to f.union(f.project(f.scan("e", "x", "y"), "x"), f.project(f.scan("s", "x"), "x")),
        ),
    )

    private val catalog = f.catalog("e" to 2, "s" to 1)

    private fun bytes(value: Any): ByteArray = ByteArrayOutputStream().apply {
        ObjectOutputStream(this).use { it.writeObject(value) }
    }.toByteArray()

    private fun <T> roundTrip(value: T): T {
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes(value as Any))).use { it.readObject() as T }
    }

    @Test
    fun `QRY1 §LOWER-05 lowering one plan twice yields equal specs with identical serialized bytes`() {
        val first = f.lowered(plan, catalog)
        val second = f.lowered(plan, catalog)

        withClue("non-vacuity: the spec has steps") { first.spec.steps.shouldNotBeEmpty() }
        first.spec shouldBe second.spec
        first shouldBe second
        withClue("serialized specs must be byte-identical") {
            bytes(first.spec).contentEquals(bytes(second.spec)) shouldBe true
        }
    }

    @Test
    fun `QRY1 §LOWER-05 a planner-built plan lowers identically twice, including through a fresh planning`() {
        val query = Query(
            rules = listOf(
                Rule(
                    f.atom("q", "x"),
                    listOf(
                        Literal.Positive(f.atom("r", "x", "y")),
                        Literal.Negated(f.atom("r", "y", "x")),
                        Literal.Comparison(Term.Var("y"), ComparisonOp.GT, Term.Const(3, AttrType.INT)),
                    ),
                ),
                Rule(f.atom("q", "x"), listOf(Literal.Positive(f.atom("r", "x", "x")))),
            ),
            catalog = f.catalog("r" to 2),
        )
        val first = f.lowered(Planner.plan(query), query.catalog)
        val second = f.lowered(Planner.plan(query), query.catalog)

        first shouldBe second
        bytes(first.spec).contentEquals(bytes(second.spec)) shouldBe true
    }

    @Test
    fun `QRY1 §LOWER-06 a spec round-trips through Java serialization equal to the original`() {
        val result = f.lowered(plan, catalog)

        roundTrip(result.spec) shouldBe result.spec
        roundTrip(result) shouldBe result
    }

    @Test
    fun `control - a different plan lowers to a different spec, so the equality above is not vacuous`() {
        val other = LogicalPlan(mapOf("q" to f.project(f.scan("e", "x", "y"), "y")))
        f.lowered(other, catalog).spec shouldNotBe f.lowered(plan, catalog).spec
    }
}
