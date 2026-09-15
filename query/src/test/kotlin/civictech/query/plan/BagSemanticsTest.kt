package civictech.query.plan

import civictech.cell.Propagate
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.view.MapView
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.QueryCompiler
import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.CompileResult
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.diag.RejectionCoverage.catalog
import civictech.query.diag.RejectionCoverage.keyed
import civictech.query.diag.RejectionCoverage.lineitem
import civictech.query.lower.Lowering
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.run.OutputShape
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The set/bag boundary (computenet-cab.5.2, epic §4.4): [BagSemantics] through
 * [QueryCompiler] — `ALL` set operations ([QRY1-SEM-04]), `COUNT`/`SUM`/`AVG` over a
 * non-key-preserving input ([QRY1-SEM-02]), the key-carried half ([QRY1-SEM-03]), cab.4.7's
 * deferred question (cab.5-D8), and the TPC-H Q1/Q3 early signal (epic §9.1).
 */
class BagSemanticsTest {

    private fun v(name: String) = Term.Var(name)

    private fun rejections(source: String, catalog: Catalog): List<Rejection> =
        withClue(source) { QueryCompiler.compile(source, catalog).shouldBeInstanceOf<CompileResult.Rejected>().rejections }

    private fun bagRejections(source: String, catalog: Catalog): List<Rejection> =
        rejections(source, catalog).filter { it.code == RejectionCode.BAG_SEMANTICS_REQUIRED }

    private fun plan(source: String, catalog: Catalog): LogicalPlan =
        Planner.plan((QueryParser.parse(source, catalog) as ParseResult.Parsed).query)

    // ------------------------------------------------------------------ ALL set operations

    private val allKinds = listOf(
        SetOpKind.DIFFERENCE to "EXCEPT",
        SetOpKind.UNION to "UNION",
        SetOpKind.INTERSECTION to "INTERSECT",
    )

    private fun allQuery(kind: SetOpKind) = Query(
        rules = listOf(Rule(Atom("q", listOf(v("X"))), listOf(Literal.Positive(Atom("r", listOf(v("X"))))))),
        catalog = catalog("r" to 1, "s" to 1),
        definitions = listOf(
            Definition(
                Atom("h", listOf(v("X"))),
                RelationalExpr.SetOp(kind, RelationalExpr.Relation(Atom("r", listOf(v("X")))), RelationalExpr.Relation(Atom("s", listOf(v("X")))), all = true),
            ),
        ),
    )

    @Test
    fun `Planner's ALL guard is unreachable through QueryCompiler for all three kinds`() {
        allKinds.forEach { (kind, surface) ->
            val query = allQuery(kind)
            withClue("control: the planner itself still throws on $surface ALL, so the compiler's result is the fence") {
                shouldThrow<IllegalArgumentException> { Planner.plan(query) }.message shouldContain "BAG_SEMANTICS_REQUIRED"
            }
            val rejection = withClue(surface) {
                QueryCompiler.compile(query).shouldBeInstanceOf<CompileResult.Rejected>().rejections.single()
            }
            rejection.code shouldBe RejectionCode.BAG_SEMANTICS_REQUIRED
            rejection.locus shouldBe Locus.RuleStatement(0, "h")
            rejection.specId shouldStartWith "[QRY1-SEM-04] $surface ALL"
            rejection.specId shouldContain "[24-OP-SEMIJOIN-01]"
            rejection.specId shouldContain "96 §E6 / 95 R17"
        }
    }

    @Test
    fun `an ALL nested inside a definition is found, and a rule depending on the refused definition is excluded rather than thrown`() {
        val rejected = rejections(
            "define h(X) := r(X) union (s(X) intersect all r(X)).\nq(X) :- h(X), s(X).",
            catalog("r" to 1, "s" to 1),
        )
        rejected.map { it.code } shouldBe listOf(RejectionCode.BAG_SEMANTICS_REQUIRED)
        rejected.single().specId shouldStartWith "[QRY1-SEM-04] INTERSECT ALL"
    }

    @Test
    fun `a DISTINCT set operation is not refused`() {
        QueryCompiler.compile("define h(X) := r(X) except s(X).", catalog("r" to 1, "s" to 1))
            .shouldBeInstanceOf<CompileResult.Compiled>()
    }

    // ------------------------------------------------------------------ BS-7

    @Test
    fun `BS-7 first half - a sum over a projection that drops lineitem's row key is BAG_SEMANTICS_REQUIRED at the GroupAggregate`() {
        val source = "@sum total(V) :- q(X, V).\nq(X, V) :- lineitem(O, X, V)."
        val rejection = rejections(source, lineitem).single()

        rejection.code shouldBe RejectionCode.BAG_SEMANTICS_REQUIRED
        rejection.specId shouldBe "[QRY1-SEM-02] SUM over a non-key-preserving input (lost row key {O}); owners: 96 §E6 / 95 R17"
        // The locus is the handle the lowering gives the same node (lowering ignores keys).
        val lowered = Lowering.lower(plan(source, lineitem), lineitem)
            .shouldBeInstanceOf<civictech.query.lower.LoweringResult.Lowered>()
        rejection.locus shouldBe Locus.PlanNode(lowered.outputHandles.getValue("total"))
    }

    @Test
    fun `BS-7 second half - with the row key carried the sum compiles and answers the hand-computed total`() {
        val compiled = QueryCompiler.compile("@sum total(V) :- q(O, X, V).\nq(O, X, V) :- lineitem(O, X, V).", lineitem)
            .shouldBeInstanceOf<CompileResult.Compiled>().query
        compiled.outputShapes["total"] shouldBe OutputShape.MAP_BY_GROUP

        val world = SimWorld(seed = 17)
        val applied = compiled.applyTo(world.host.managementInlet)
        val writer = world.host.lookup(applied.sources.getValue("lineitem"))!!.inlet.call
        val totals = MapView<Any?, Any?>()
        world.host.lookup(TypedRef<GroupByApi<Row, Any?, Any?>>(applied.outputs.getValue("total").ref))!!
            .outlet.subscribe(Use.fixed(Propagate { delta -> totals.apply(delta) }, PortRef.generate()))

        // Two lines share (x, v) = (10, 5): set semantics over the key-dropped projection would
        // have summed the distinct values 5 + 7 = 12; the key-carried answer is 5 + 7 + 5.
        writer.add(Row(listOf(1, 10, 5)))
        writer.add(Row(listOf(2, 10, 7)))
        writer.add(Row(listOf(3, 10, 5)))
        world.runToIdle()

        withClue("batch sum of v over three distinct lineitem rows") {
            totals.current().values.toList() shouldBe listOf(17L)
        }
    }

    // ------------------------------------------------------------------ SEM-02 per kind

    @Test
    fun `COUNT and AVG over a key-dropping projection are refused, naming the lost key`() {
        bagRejections("@count c(X) :- p(X).\np(X) :- e(X, Y).", keyed(Triple("e", 2, listOf(0, 1)))).single().specId shouldBe
            "[QRY1-SEM-02] COUNT over a non-key-preserving input (lost row key {X, Y}); owners: 96 §E6 / 95 R17"
        bagRejections("@avg a(V) :- q(X, V).\nq(X, V) :- lineitem(O, X, V).", lineitem).single().specId shouldStartWith
            "[QRY1-SEM-02] AVG over a non-key-preserving input (lost row key {O})"
    }

    @Test
    fun `a relation with no declared row key feeding a sum is refused, naming the absence of a row key`() {
        bagRejections("@sum t(V) :- p(V).\np(V) :- r(K, V).", catalog("r" to 2)).single().specId shouldBe
            "[QRY1-SEM-02] SUM over a non-key-preserving input (no declared row key on r); owners: 96 §E6 / 95 R17"
    }

    @Test
    fun `MIN, MAX, TOP_K and COLLECT_TO_SET over a key-dropping projection are never BAG_SEMANTICS_REQUIRED`() {
        val insensitive = listOf(
            Aggregate(AggregateKind.MIN),
            Aggregate(AggregateKind.MAX),
            Aggregate(AggregateKind.TOP_K, 2),
            Aggregate(AggregateKind.COLLECT_TO_SET),
        )
        insensitive.forEach { aggregate ->
            val query = Query(
                rules = listOf(
                    Rule(Atom("agg", listOf(v("V"))), listOf(Literal.Positive(Atom("q", listOf(v("X"), v("V"))))), aggregate),
                    Rule(Atom("q", listOf(v("X"), v("V"))), listOf(Literal.Positive(Atom("lineitem", listOf(v("O"), v("X"), v("V")))))),
                ),
                catalog = lineitem,
            )
            withClue("the input is genuinely non-key-preserving, or this check proves nothing") {
                Planner.plan(query).roots.getValue("agg").shouldBeInstanceOf<GroupAggregate>().input.keyPreserving shouldBe false
            }
            val codes = (QueryCompiler.compile(query) as? CompileResult.Rejected)?.rejections?.map { it.code }.orEmpty()
            withClue("${aggregate.kind} -> $codes") {
                codes.filter { it == RejectionCode.BAG_SEMANTICS_REQUIRED }.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a key-dropping projection under a join under a COUNT is caught at the aggregate`() {
        val catalog = keyed(Triple("e", 2, listOf(0, 1)), Triple("t", 2, listOf(0, 1)))
        bagRejections("@count c(X, Z) :- p(X), t(X, Z).\np(X) :- e(X, Y).", catalog).single().specId shouldStartWith
            "[QRY1-SEM-02] COUNT over a non-key-preserving input (lost row key {X, Y}"
    }

    @Test
    fun `a join feeding only a set-valued root compiles regardless of key preservation`() {
        QueryCompiler.compile("q(X) :- p(X), s(X).\np(X) :- r(X, Y).", catalog("r" to 2, "s" to 1))
            .shouldBeInstanceOf<CompileResult.Compiled>()
    }

    @Test
    fun `an aggregate inlined into another root is refused at each occurrence, numbered as the lowering numbers it`() {
        // The q root's inlined GroupAggregate is also NO_LOWERING; both name the same node.
        val rejected = rejections(
            "@count c(X, N) :- p(X, N).\np(X, N) :- r(X, N, Z).\nq(X) :- c(X, N).",
            keyed(Triple("r", 3, listOf(2))),
        )
        val bag = rejected.filter { it.code == RejectionCode.BAG_SEMANTICS_REQUIRED }.map { it.locus }
        val noLowering = rejected.single { it.code == RejectionCode.NO_LOWERING }.locus
        bag.map { (it as Locus.PlanNode).id.substringBefore("/") } shouldBe listOf("c", "q")
        bag shouldContain noLowering
    }

    // ------------------------------------------------------------------ cab.5-D8

    @Test
    fun `cab-4-7's deferred question - an aggregated head over a strict subset of body variables compiles and is not BAG_SEMANTICS_REQUIRED`() {
        QueryCompiler.compile("@count c(X) :- e(X, Y).", keyed(Triple("e", 2, listOf(0, 1))))
            .shouldBeInstanceOf<CompileResult.Compiled>()
    }

    @Test
    fun `cab-4-7's contrast - the same aggregate over an intermediate predicate that drops the key is BAG_SEMANTICS_REQUIRED`() {
        val catalog = keyed(Triple("e", 2, listOf(0, 1)))
        val rejection = rejections("@count c(X) :- p(X).\np(X) :- e(X, Y).", catalog).single()
        rejection.code shouldBe RejectionCode.BAG_SEMANTICS_REQUIRED
        rejection.locus shouldBe Locus.PlanNode("c/0:groupaggregate")
    }

    @Test
    fun `a COUNT directly over a relation with no declared row key is refused - the annotation's conservative limit`() {
        // BagSemantics' KDoc: keyPreserving false means "not established", so the keyless form
        // of cab.4.7's rule is refused even though its set and bag answers agree.
        bagRejections("@count c(X) :- e(X, Y).", catalog("e" to 2)).single().specId shouldContain "no declared row key on e"
    }

    // ------------------------------------------------------------------ epic §9.1 early signal

    @Test
    fun `TPC-H Q1 shape - grouped sums, avg and count over a filtered lineitem scan compile`() {
        val tpch = keyed(Triple("lineitem", 7, listOf(0, 1)))
        val body = "lineitem(O, L, RF, LS, Q, P, D), D <= 100"
        val source = """
            @sum sum_qty(RF, LS, Q) :- $body.
            @sum sum_base_price(RF, LS, P) :- $body.
            @avg avg_qty(RF, LS, Q) :- $body.
            @count count_order(RF, LS, L) :- $body.
        """.trimIndent()
        val result = QueryCompiler.compile(source, tpch)
        withClue("TPC-H Q1 outcome (a finding either way): $result") {
            result.shouldBeInstanceOf<CompileResult.Compiled>()
        }
    }

    @Test
    fun `TPC-H Q3 shape - a join then a grouped sum compiles`() {
        val tpch = keyed(
            Triple("customer", 2, listOf(0)),
            Triple("orders", 4, listOf(0)),
            Triple("lineitem", 4, listOf(0, 1)),
        )
        val source = "@sum revenue(O, D, SP, P) :- customer(C, 1), orders(O, C, D, SP), lineitem(O, L, P, SD), D < 50, SD > 50."
        val result = QueryCompiler.compile(source, tpch)
        withClue("TPC-H Q3 outcome (a finding either way): $result") {
            result.shouldBeInstanceOf<CompileResult.Compiled>()
        }
    }
}
