package civictech.query.run

import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetOps
import civictech.cell.data.view.SetView
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.lower.PlanFixtures
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.Planner
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * computenet-67vnx end-to-end pin: a join whose left input row carries a `null` in a column the
 * right side lacks must produce that row with `null`, not throw — through the whole production
 * path (parser -> planner -> lowering -> compiled, live cells), agreeing with [BatchEvaluator].
 *
 * Reproduces the defect exactly as the discovering review observed it (computenet-cab.6.1's task
 * review, comment on computenet-cab.6.1): `j` is a `left outer join` of `a` with `b`; `b` is
 * empty, so every `j` row is left-only and pads its right-hand column (`B`) with `null`. `q`
 * then inner-joins `j` with `c` on the shared key `K` — the lowered `Join` uses [RowCombine]
 * (`civictech.query.expr.RowCombine`), whose `leftColumns` are `j`'s `[K, A, B]` and whose
 * `rightColumns` are `c`'s `[K, Z]`. Reading output column `B` must come from the left (`j`) row
 * because `B` is one of `leftColumns` — even though its value is `null` — never fall through to
 * the right (`c`) row, which does not carry a `B` column at all.
 */
class OuterJoinNullCombineTest {

    private val catalog = PlanFixtures.catalog("a" to 2, "b" to 2, "c" to 2)

    private val source = """
        define j(K, A, B) := a(K, A) left outer join b(P, B) on K = P.
        q(K, B, Z) :- j(K, A, B), c(K, Z).
    """.trimIndent()

    private fun row(vararg values: Int?): Row = Row(values.toList())

    private val aRows = setOf(row(1, 10))
    private val bRows = emptySet<Row>()
    private val cRows = setOf(row(1, 5))

    private fun compile(): CompiledQuery {
        val plan = Planner.plan((QueryParser.parse(source, catalog) as ParseResult.Parsed).query)
        val lowered = withClue("lowering of: $source") {
            Lowering.lower(plan, catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        }
        return CompiledQuery.from(lowered, plan)
    }

    private class Live(val world: SimWorld, val applied: AppliedQuery, val output: SetView<Row>) {
        fun writer(relation: String): SetOps<Row> = world.host.lookup(applied.sources.getValue(relation))!!.inlet.call
    }

    private fun apply(compiled: CompiledQuery, root: String, seed: Long): Live {
        val world = SimWorld(seed = seed)
        val applied = compiled.applyTo(world.host.managementInlet)
        val view = SetView<Row>()
        world.host.lookup(TypedRef<SetApi<Row>>(applied.outputs.getValue(root).ref))!!.outlet
            .subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))
        return Live(world, applied, view)
    }

    @Test
    fun `a join over a left-outer-join-padded relation keeps the left null, agreeing with BatchEvaluator`() {
        val expected = setOf(row(1, null, 5))

        val live = apply(compile(), "q", seed = 17)
        aRows.forEach(live.writer("a")::add)
        bRows.forEach(live.writer("b")::add)
        cRows.forEach(live.writer("c")::add)
        live.world.runToIdle()

        withClue("compiled query, live cells") {
            live.output.current() shouldBe expected
        }

        val plan = Planner.plan((QueryParser.parse(source, catalog) as ParseResult.Parsed).query)
        val batch = BatchEvaluator.evaluate(plan, mapOf("a" to aRows, "b" to bRows, "c" to cRows))
        withClue("BatchEvaluator") {
            batch.getValue("q") shouldBe RelationValue.Rows(expected)
        }
    }
}
