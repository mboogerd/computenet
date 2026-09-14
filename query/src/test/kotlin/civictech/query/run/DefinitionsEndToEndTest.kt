package civictech.query.run

import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetOps
import civictech.cell.data.view.SetView
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.lower.LoweringDiagnostic
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.lower.PlanFixtures
import civictech.query.lower.SemiJoinFactory
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.Planner
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The planner→lowering seam for `define` statements (computenet-cab.4 feature review): the
 * `Planner` plans `Query.definitions` to Union/Intersect/Difference/OuterJoin roots
 * (computenet-cab.4.6, closing computenet-5pplz), and `Lowering` lowers those node kinds
 * (computenet-cab.4.3) — but `PlannerDefinitionsTest` stops at the plan and the lowering tests
 * build their plans by hand. Here each definition goes the whole production path, parser text
 * to answers on a live [SimWorld] host, so a column-order, operand-order or side mismatch
 * between the two halves shows up as a wrong answer.
 *
 * Expected answers are written out by hand from the fixture rows, not recomputed. Set operations
 * match operands positionally (`Planner`'s "a set operation's right operand is substituted
 * positionally to its left operand's columns").
 */
class DefinitionsEndToEndTest {

    private val catalog = PlanFixtures.catalog("a" to 2, "b" to 2)

    private val aRows = listOf(row(1, 10), row(2, 20), row(3, 30))
    private val bRows = listOf(row(2, 20), row(3, 99), row(4, 40))

    private fun row(vararg values: Int?): Row = Row(values.toList())

    private fun compile(source: String): CompiledQuery {
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

    /** Compiles [source], writes the fixture rows into `a` and `b`, runs to idle, returns [root]'s rows. */
    private fun answer(source: String, root: String = "h"): Set<Row> {
        val live = apply(compile(source), root, seed = 11)
        live.applied.sources["a"]?.let { live.writer("a").let { w -> aRows.forEach(w::add) } }
        live.applied.sources["b"]?.let { live.writer("b").let { w -> bRows.forEach(w::add) } }
        live.world.runToIdle()
        return live.output.current()
    }

    @Test
    fun `a union definition answers the positional union of its operands`() {
        answer("define h(X, Y) := a(X, Y) union b(P, Q).") shouldBe
            setOf(row(1, 10), row(2, 20), row(3, 30), row(3, 99), row(4, 40))
    }

    @Test
    fun `an intersect definition answers the rows both operands hold`() {
        answer("define h(X, Y) := a(X, Y) intersect b(P, Q).") shouldBe setOf(row(2, 20))
    }

    @Test
    fun `an except definition answers the left operand's rows the right lacks`() {
        answer("define h(X, Y) := a(X, Y) except b(P, Q).") shouldBe setOf(row(1, 10), row(3, 30))
    }

    @Test
    fun `left, right and full outer join definitions null-pad the unmatched side's columns`() {
        withClue("left") {
            answer("define h(X, Y, Z) := a(X, Y) left outer join b(P, Z) on X = P.") shouldBe
                setOf(row(1, 10, null), row(2, 20, 20), row(3, 30, 99))
        }
        withClue("right") {
            answer("define h(X, Y, Z) := a(X, Y) right outer join b(P, Z) on X = P.") shouldBe
                setOf(row(2, 20, 20), row(3, 30, 99), row(4, null, 40))
        }
        withClue("full") {
            answer("define h(X, Y, Z) := a(X, Y) full outer join b(P, Z) on X = P.") shouldBe
                setOf(row(1, 10, null), row(2, 20, 20), row(3, 30, 99), row(4, null, 40))
        }
    }

    @Test
    fun `a rule over a defined head answers over the definition's rows`() {
        answer(
            """
            define h(X, Y) := a(X, Y) except b(P, Q).
            q(Y) :- h(X, Y), X > 1.
            """.trimIndent(),
            root = "q",
        ) shouldBe setOf(row(30))
    }

    /**
     * A definition's planned provenance reaches `Gating.decide`: an outer join of `a` with itself
     * is gated with no diagnostic, one of `a` with `b` is ungated with an `EventuallyConsistent`
     * diagnostic — and the gated one answers correctly through adds and a retraction on every seed.
     */
    @Test
    fun `an outer join definition over one relation is gated and stays correct through retraction`() {
        withClue("independent operands: ungated, EventuallyConsistent") {
            val independent = compile("define h(X, Y, Z) := a(X, Y) left outer join b(P, Z) on X = P.")
            unmatchedGate(independent) shouldBe false
            independent.diagnostics.single().shouldBeInstanceOf<LoweringDiagnostic.EventuallyConsistent>()
        }

        val selfJoin = compile("define h(X, Y, Z) := a(X, Y) left outer join a(P, Z) on Y = P.")
        withClue("shared single-scan operands: gated, no diagnostic") {
            unmatchedGate(selfJoin) shouldBe true
            selfJoin.diagnostics shouldBe emptyList()
        }
        for (seed in 0L..30L) {
            val live = apply(selfJoin, "h", seed)
            val a = live.writer("a")
            a.add(row(1, 2)); a.add(row(2, 3)); a.add(row(3, 9))
            live.world.runToIdle()
            withClue("seed=$seed after adds") {
                live.output.current() shouldBe setOf(row(1, 2, 3), row(2, 3, 9), row(3, 9, null))
            }
            a.remove(row(3, 9))
            live.world.runToIdle()
            withClue("seed=$seed after retracting (3,9): (2,3) loses its match") {
                live.output.current() shouldBe setOf(row(1, 2, 3), row(2, 3, null))
            }
            a.add(row(3, 5))
            live.world.runToIdle()
            withClue("seed=$seed after adding (3,5): (2,3) regains one") {
                live.output.current() shouldBe setOf(row(1, 2, 3), row(2, 3, 5), row(3, 5, null))
            }
        }
    }

    private fun unmatchedGate(compiled: CompiledQuery): Boolean =
        compiled.spec.steps.filterIsInstance<civictech.cell.graph.SpawnStep>()
            .single { it.handle == "h/0:outerjoin-unmatched" }
            .factory.shouldBeInstanceOf<SemiJoinFactory>().emitOnFrontier
}
