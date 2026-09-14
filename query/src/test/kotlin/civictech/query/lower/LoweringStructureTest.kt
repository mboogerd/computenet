package civictech.query.lower

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.query.architecture.HierarchyCompleteness
import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.expr.Expr
import civictech.query.expr.ExprPredicate
import civictech.query.expr.RowCombine
import civictech.query.expr.RowKey
import civictech.query.expr.RowProjection
import civictech.query.plan.AntiJoin
import civictech.query.plan.Difference
import civictech.query.plan.GroupAggregate
import civictech.query.plan.Intersect
import civictech.query.plan.Join
import civictech.query.plan.JoinKey
import civictech.query.plan.LogicalPlan
import civictech.query.plan.OuterJoin
import civictech.query.plan.OuterJoinSide
import civictech.query.plan.PlanNode
import civictech.query.plan.Planner
import civictech.query.plan.Project
import civictech.query.plan.Scan
import civictech.query.plan.Select
import civictech.query.plan.SemiJoin
import civictech.query.plan.Union
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Hand-built plan nodes for the lowering tests, built the way `LogicalPlanSerializationTest`
 * builds them, so the structural assertions do not depend on the parser's or planner's
 * current shapes. Analysis slots: provenance is the union of the inputs' (what
 * [civictech.query.plan.PlanAnalyses] computes); key preservation is irrelevant to lowering
 * and always `false`.
 */
internal object PlanFixtures {

    fun scan(relation: String, vararg columns: String) =
        Scan(relation, columns.toList(), setOf(relation), keyPreserving = false)

    fun select(input: PlanNode, left: Term, op: ComparisonOp, right: Term) =
        Select(input, Literal.Comparison(left, op, right), input.outputColumns, input.provenance, false)

    fun project(input: PlanNode, vararg columns: String) =
        Project(input, columns.toList(), input.provenance, false)

    fun join(left: PlanNode, right: PlanNode, vararg keys: String) = Join(
        left, right, keys.map { JoinKey(it, it) },
        left.outputColumns + right.outputColumns.filterNot { it in left.outputColumns },
        left.provenance + right.provenance, false,
    )

    fun semiJoin(input: PlanNode, witness: PlanNode, vararg keys: String) = SemiJoin(
        input, witness, keys.map { JoinKey(it, it) }, input.outputColumns,
        input.provenance + witness.provenance, false,
    )

    fun antiJoin(input: PlanNode, witness: PlanNode, vararg keys: String) = AntiJoin(
        input, witness, keys.map { JoinKey(it, it) }, input.outputColumns,
        input.provenance + witness.provenance, false,
    )

    fun union(vararg inputs: PlanNode) = Union(
        inputs.toList(), inputs.first().outputColumns, inputs.flatMap { it.provenance }.toSet(), false,
    )

    fun groupAggregate(input: PlanNode) = GroupAggregate(
        input = input,
        groupByColumns = input.outputColumns.dropLast(1),
        aggregatedColumn = input.outputColumns.last(),
        aggregate = Aggregate(AggregateKind.COUNT),
        outputColumn = input.outputColumns.last(),
        outputColumns = input.outputColumns,
        provenance = input.provenance,
        keyPreserving = false,
    )

    fun v(name: String) = Term.Var(name)

    fun int(value: Int) = Term.Const(value, AttrType.INT)

    fun str(value: String) = Term.Const(value, AttrType.STRING)

    /** Every relation named, each with INT attributes a0..a(n-1) of the given arity. */
    fun catalog(vararg relations: Pair<String, Int>) = Catalog(
        relations.associate { (name, arity) ->
            name to RelationSchema((0 until arity).map { Attribute("a$it", AttrType.INT) })
        },
    )

    fun atom(predicate: String, vararg variables: String) = Atom(predicate, variables.map { Term.Var(it) })

    fun lowered(plan: LogicalPlan, catalog: Catalog): LoweringResult.Lowered =
        Lowering.lower(plan, catalog).shouldBeInstanceOf<LoweringResult.Lowered>()

    fun refused(plan: LogicalPlan, catalog: Catalog): LoweringResult.Refused =
        Lowering.lower(plan, catalog).shouldBeInstanceOf<LoweringResult.Refused>()

    fun spawns(steps: List<GraphStep>) = steps.filterIsInstance<SpawnStep>()

    fun connects(steps: List<GraphStep>) = steps.filterIsInstance<ConnectStep>()
}

/**
 * One structural test per lowering rule (computenet-cab.4.2, `[QRY1-LOWER-02]` for the
 * Scan/Select/Project/Join/SemiJoin/AntiJoin/Union rows): each asserts the emitted spawn's
 * factory class and interpreter values and the connect topology, not any evaluated answer —
 * applying a spec to a host is the CompiledQuery task's.
 */
class LoweringStructureTest {

    private val f = PlanFixtures

    // ---------------------------------------------------------------- per-rule structure

    @Test
    fun `Scan lowers to the shared src SetCell and emits no cell of its own`() {
        val result = f.lowered(LogicalPlan(mapOf("q" to f.scan("r", "x", "y"))), f.catalog("r" to 2))

        result.spec.steps shouldContainExactly listOf(SpawnStep("src:r", SetSourceFactory("r"), IdentityBinding.FreshLogical))
        result.sourceHandles shouldBe mapOf("r" to "src:r")
        result.outputHandles shouldBe mapOf("q" to "src:r")
    }

    @Test
    fun `Select lowers to FilterCell over an ExprPredicate typed against the scan's columns`() {
        val plan = LogicalPlan(mapOf("q" to f.select(f.scan("r", "x", "y"), f.v("y"), ComparisonOp.GT, f.int(3))))
        val result = f.lowered(plan, f.catalog("r" to 2))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep(
                "q/0:select",
                FilterFactory(
                    ExprPredicate(
                        listOf("x", "y"),
                        Expr.Cmp(ComparisonOp.GT, Expr.Attr("y"), Expr.Const(3, AttrType.INT)),
                    ),
                ),
            ),
            ConnectStep("src:r", "outlet", "q/0:select", "inlet"),
        )
        result.outputHandles shouldBe mapOf("q" to "q/0:select")
    }

    @Test
    fun `QRY1 §HONEST-02 an ill-typed Select is refused, not lowered and not thrown`() {
        val plan = LogicalPlan(mapOf("q" to f.select(f.scan("r", "x", "y"), f.v("y"), ComparisonOp.EQ, f.str("three"))))
        val refusal = f.refused(plan, f.catalog("r" to 2)).refusals.single()

        refusal.nodeKind shouldBe "Select"
        refusal.locus shouldBe Locus.PlanNode("q/0:select")
        refusal.reason shouldContain "[QRY1-HONEST-02]"
    }

    @Test
    fun `Project lowers to FlatMapSetCell over a RowProjection`() {
        val plan = LogicalPlan(mapOf("q" to f.project(f.scan("r", "x", "y"), "y")))
        val result = f.lowered(plan, f.catalog("r" to 2))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("q/0:project", FlatMapFactory(RowProjection(listOf("x", "y"), listOf("y")))),
            ConnectStep("src:r", "outlet", "q/0:project", "inlet"),
        )
    }

    @Test
    fun `Join lowers to JoinSetCell keyed on each side's columns with left and right connects`() {
        val plan = LogicalPlan(mapOf("q" to f.join(f.scan("r", "x", "y"), f.scan("s", "y", "z"), "y")))
        val result = f.lowered(plan, f.catalog("r" to 2, "s" to 2))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("src:s", SetSourceFactory("s")),
            SpawnStep(
                "q/0:join",
                JoinFactory(
                    leftKey = RowKey(listOf("x", "y"), listOf("y")),
                    rightKey = RowKey(listOf("y", "z"), listOf("y")),
                    combine = RowCombine(listOf("x", "y"), listOf("y", "z"), listOf("x", "y", "z")),
                ),
            ),
            ConnectStep("src:r", "outlet", "q/0:join", "left"),
            ConnectStep("src:s", "outlet", "q/0:join", "right"),
        )
    }

    @Test
    fun `a Join with no equi-keys lowers to JoinSetCell at the unit key, the cross product`() {
        val plan = LogicalPlan(mapOf("q" to f.join(f.scan("r", "x"), f.scan("s", "y"))))
        val join = f.spawns(f.lowered(plan, f.catalog("r" to 1, "s" to 1)).spec.steps)
            .single { it.handle == "q/0:join" }.factory.shouldBeInstanceOf<JoinFactory>()

        join.leftKey shouldBe RowKey(listOf("x"), emptyList())
        join.rightKey shouldBe RowKey(listOf("y"), emptyList())
        join.combine shouldBe RowCombine(listOf("x"), listOf("y"), listOf("x", "y"))
    }

    @Test
    fun `SemiJoin lowers to SemiJoinCell negated=false, ungated, with left and right connects`() {
        val plan = LogicalPlan(mapOf("q" to f.semiJoin(f.scan("r", "x", "y"), f.scan("s", "y"), "y")))
        val result = f.lowered(plan, f.catalog("r" to 2, "s" to 1))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("src:s", SetSourceFactory("s")),
            SpawnStep(
                "q/0:semijoin",
                SemiJoinFactory(
                    leftKey = RowKey(listOf("x", "y"), listOf("y")),
                    rightKey = RowKey(listOf("y"), listOf("y")),
                    negated = false,
                    emitOnFrontier = false,
                ),
            ),
            ConnectStep("src:r", "outlet", "q/0:semijoin", "left"),
            ConnectStep("src:s", "outlet", "q/0:semijoin", "right"),
        )
        result.diagnostics.shouldBeEmpty()
    }

    @Test
    fun `AntiJoin lowers to SemiJoinCell negated=true with left and right connects`() {
        val plan = LogicalPlan(mapOf("q" to f.antiJoin(f.scan("r", "x", "y"), f.scan("s", "y"), "y")))
        val result = f.lowered(plan, f.catalog("r" to 2, "s" to 1))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("src:s", SetSourceFactory("s")),
            SpawnStep(
                "q/0:antijoin",
                SemiJoinFactory(
                    leftKey = RowKey(listOf("x", "y"), listOf("y")),
                    rightKey = RowKey(listOf("y"), listOf("y")),
                    negated = true,
                    emitOnFrontier = false,
                ),
            ),
            ConnectStep("src:r", "outlet", "q/0:antijoin", "left"),
            ConnectStep("src:s", "outlet", "q/0:antijoin", "right"),
        )
    }

    @Test
    fun `QRY1 §LOWER-08 §LOWER-09 AntiJoin gating - shared shallow gated, shared deep GateNotProvable, disjoint EventuallyConsistent`() {
        val catalog = f.catalog("e" to 2, "s" to 1)

        // 1. Shared source, both arms at most one operator deep: gated, no diagnostic.
        val shallow = f.antiJoin(
            f.scan("e", "x", "y"),
            f.select(f.scan("e", "y", "w"), f.v("w"), ComparisonOp.GT, f.int(0)),
            "y",
        )
        val shallowResult = f.lowered(LogicalPlan(mapOf("q" to shallow)), catalog)
        antiJoinFactory(shallowResult).emitOnFrontier shouldBe true
        shallowResult.diagnostics.shouldBeEmpty()

        // 2. Shared source, the witness arm two operators deep: ungated, GateNotProvable.
        val deep = f.antiJoin(
            f.scan("e", "x", "y"),
            f.project(f.select(f.scan("e", "y", "w"), f.v("w"), ComparisonOp.GT, f.int(0)), "y"),
            "y",
        )
        val deepResult = f.lowered(LogicalPlan(mapOf("q" to deep)), catalog)
        antiJoinFactory(deepResult).emitOnFrontier shouldBe false
        val notProvable = deepResult.diagnostics.single().shouldBeInstanceOf<LoweringDiagnostic.GateNotProvable>()
        notProvable.handle shouldBe "q/0:antijoin"
        notProvable.locus shouldBe Locus.PlanNode("q/0:antijoin")
        notProvable.reason shouldContain "F-15"

        // 3. No shared source: ungated, EventuallyConsistent.
        val disjoint = f.antiJoin(f.scan("e", "x", "y"), f.scan("s", "y"), "y")
        val disjointResult = f.lowered(LogicalPlan(mapOf("q" to disjoint)), catalog)
        antiJoinFactory(disjointResult).emitOnFrontier shouldBe false
        val eventual = disjointResult.diagnostics.single().shouldBeInstanceOf<LoweringDiagnostic.EventuallyConsistent>()
        eventual.handle shouldBe "q/0:antijoin"
        eventual.specId shouldBe "[24-OP-SEMIJOIN-04]/[24-OP-OUTERJOIN-02]"
    }

    @Test
    fun `Union lowers to one UnionSetCell with every branch connected to its single inlet`() {
        val plan = LogicalPlan(
            mapOf("q" to f.union(f.project(f.scan("r", "x", "y"), "x"), f.project(f.scan("s", "x", "z"), "x"))),
        )
        val result = f.lowered(plan, f.catalog("r" to 2, "s" to 2))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("src:s", SetSourceFactory("s")),
            SpawnStep("q/1:project", FlatMapFactory(RowProjection(listOf("x", "y"), listOf("x")))),
            ConnectStep("src:r", "outlet", "q/1:project", "inlet"),
            SpawnStep("q/3:project", FlatMapFactory(RowProjection(listOf("x", "z"), listOf("x")))),
            ConnectStep("src:s", "outlet", "q/3:project", "inlet"),
            SpawnStep("q/0:union", UnionFactory(listOf("x"))),
            ConnectStep("q/1:project", "outlet", "q/0:union", "inlet"),
            ConnectStep("q/3:project", "outlet", "q/0:union", "inlet"),
        )
        result.outputHandles shouldBe mapOf("q" to "q/0:union")
    }

    // ---------------------------------------------------------------- planner-built examples

    @Test
    fun `feature example 1 - q(x) from r(x,y) with y greater than 3 lowers to source, filter, projection in order`() {
        val query = Query(
            rules = listOf(
                Rule(
                    f.atom("q", "x"),
                    listOf(
                        Literal.Positive(f.atom("r", "x", "y")),
                        Literal.Comparison(Term.Var("y"), ComparisonOp.GT, Term.Const(3, AttrType.INT)),
                    ),
                ),
            ),
            catalog = f.catalog("r" to 2),
        )
        val result = f.lowered(Planner.plan(query), query.catalog)

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep(
                "q/1:select",
                FilterFactory(
                    ExprPredicate(
                        listOf("x", "y"),
                        Expr.Cmp(ComparisonOp.GT, Expr.Attr("y"), Expr.Const(3, AttrType.INT)),
                    ),
                ),
            ),
            ConnectStep("src:r", "outlet", "q/1:select", "inlet"),
            SpawnStep("q/0:project", FlatMapFactory(RowProjection(listOf("x", "y"), listOf("x")))),
            ConnectStep("q/1:select", "outlet", "q/0:project", "inlet"),
        )
        result.outputHandles shouldBe mapOf("q" to "q/0:project")
        result.sourceHandles shouldBe mapOf("r" to "src:r")
    }

    @Test
    fun `QRY1 §LOWER-11 the self-join q(x,z) from e(x,y), e(y,z) has ONE src-e spawn feeding both join inlets`() {
        val query = Query(
            rules = listOf(
                Rule(
                    f.atom("q", "x", "z"),
                    listOf(Literal.Positive(f.atom("e", "x", "y")), Literal.Positive(f.atom("e", "y", "z"))),
                ),
            ),
            catalog = f.catalog("e" to 2),
        )
        val steps = f.lowered(Planner.plan(query), query.catalog).spec.steps

        withClue("a relation scanned twice has exactly one source spawn") {
            f.spawns(steps).filter { it.factory is SetSourceFactory }.map { it.handle } shouldContainExactly listOf("src:e")
        }
        val join = f.spawns(steps).single { it.factory is JoinFactory }
        f.connects(steps).filter { it.to == join.handle } shouldContainExactly listOf(
            ConnectStep("src:e", "outlet", join.handle, "left"),
            ConnectStep("src:e", "outlet", join.handle, "right"),
        )
    }

    @Test
    fun `QRY1 §LOWER-10 two rules with head q yield exactly one UnionSetCell`() {
        val query = Query(
            rules = listOf(
                Rule(f.atom("q", "x"), listOf(Literal.Positive(f.atom("r", "x", "y")))),
                Rule(f.atom("q", "x"), listOf(Literal.Positive(f.atom("s", "x", "z")))),
            ),
            catalog = f.catalog("r" to 2, "s" to 2),
        )
        val result = f.lowered(Planner.plan(query), query.catalog)

        val unions = f.spawns(result.spec.steps).filter { it.factory is UnionFactory }
        unions shouldHaveSize 1
        result.outputHandles.getValue("q") shouldBe unions.single().handle
        f.connects(result.spec.steps).filter { it.to == unions.single().handle }.map { it.inlet }
            .shouldContainExactly("inlet", "inlet")
    }

    // ---------------------------------------------------------------- refusals and totality

    @Test
    fun `a GroupAggregate under a Select is refused naming GroupAggregate`() {
        val aggregate = f.groupAggregate(f.scan("r", "g", "v"))
        val plan = LogicalPlan(mapOf("q" to f.select(aggregate, f.v("v"), ComparisonOp.GT, f.int(1))))
        val refusal = f.refused(plan, f.catalog("r" to 2)).refusals.single()

        refusal.nodeKind shouldBe "GroupAggregate"
        refusal.locus shouldBe Locus.PlanNode("q/1:groupaggregate")
        refusal.reason shouldBe Lowering.AGGREGATE_NOT_A_RELATION
    }

    @Test
    fun `Intersect, Difference, OuterJoin and a root GroupAggregate are refused as not yet lowered, all collected`() {
        val r = f.scan("r", "x")
        val s = f.scan("s", "x")
        val plan = LogicalPlan(
            mapOf(
                "a" to Intersect(r, s, listOf("x"), setOf("r", "s"), false),
                "b" to Difference(r, s, listOf("x"), setOf("r", "s"), false),
                "c" to OuterJoin(r, s, listOf(JoinKey("x", "x")), OuterJoinSide.LEFT, listOf("x"), setOf("r", "s"), false),
                "d" to f.groupAggregate(f.scan("r", "x")),
            ),
        )
        val refusals = f.refused(plan, f.catalog("r" to 1, "s" to 1)).refusals

        refusals.map { it.nodeKind to it.locus.id } shouldContainExactly listOf(
            "Intersect" to "a/0:intersect",
            "Difference" to "b/0:difference",
            "OuterJoin" to "c/0:outerjoin",
            "GroupAggregate" to "d/0:groupaggregate",
        )
        refusals.forEach { it.reason shouldBe Lowering.NOT_YET_LOWERED }
    }

    @Test
    fun `a Scan of a relation absent from the catalog is refused, not thrown`() {
        val refusal = f.refused(LogicalPlan(mapOf("q" to f.scan("missing", "x"))), f.catalog("r" to 1)).refusals.single()

        refusal.nodeKind shouldBe "Scan"
        refusal.reason shouldContain "missing"
    }

    @Test
    fun `cab-4-D5 the dispatch is total - one node of every PlanNode kind lowers or refuses, never throws`() {
        val r = f.scan("r", "x", "y")
        val s = f.scan("s", "x", "y")
        val everyKind: List<PlanNode> = listOf(
            r,
            f.select(r, f.v("x"), ComparisonOp.EQ, f.int(1)),
            f.project(r, "x"),
            f.join(r, s, "x", "y"),
            f.semiJoin(r, s, "x"),
            f.antiJoin(r, s, "x"),
            f.union(r, s),
            Intersect(r, s, listOf("x", "y"), setOf("r", "s"), false),
            Difference(r, s, listOf("x", "y"), setOf("r", "s"), false),
            f.groupAggregate(r),
            OuterJoin(r, s, listOf(JoinKey("x", "x")), OuterJoinSide.FULL, listOf("x", "y"), setOf("r", "s"), false),
        )
        withClue("the list above must exercise every PlanNode kind") {
            HierarchyCompleteness.missingFrom(PlanNode::class.java, everyKind.map { it.javaClass }).shouldBeEmpty()
        }
        everyKind.forEach { node ->
            Lowering.lower(LogicalPlan(mapOf("q" to node)), f.catalog("r" to 2, "s" to 2))
                .shouldBeInstanceOf<LoweringResult>()
        }
    }

    @Test
    fun `every connect follows the spawns of both its handles`() {
        val plan = LogicalPlan(
            mapOf(
                "p" to f.project(f.join(f.scan("e", "x", "y"), f.antiJoin(f.scan("e", "y", "z"), f.scan("s", "z"), "z"), "y"), "x"),
                "q" to f.union(f.project(f.scan("e", "x", "y"), "x"), f.project(f.scan("s", "x"), "x")),
            ),
        )
        val steps = f.lowered(plan, f.catalog("e" to 2, "s" to 1)).spec.steps

        val spawned = mutableSetOf<String>()
        steps.forEach { step ->
            when (step) {
                is SpawnStep -> spawned += step.handle
                is ConnectStep -> withClue("$step precedes a spawn it references") {
                    (step.from in spawned && step.to in spawned) shouldBe true
                }
                else -> error("unexpected step $step")
            }
        }
    }

    @Test
    fun `QRY1 §LOWER-01 civictech-query-lower makes no host, GraphBuilder, spawn or connect call`() {
        val root = File("src/main/kotlin/civictech/query/lower")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        withClue("non-vacuity: the lower package has source files") { files.isEmpty() shouldBe false }

        val forbidden = Regex("""\bGraphBuilder\b|civictech\.cell\.host\b|\bHostManagementApi\b|\.applyTo\s*\(|\bspawn\s*\(|\bconnect\s*\(|\blink\s*\(""")
        val offenders = files.flatMap { file ->
            forbidden.findAll(stripComments(file.readText())).map { "${file.name}: ${it.value}" }.toList()
        }
        withClue("live-graph construction in civictech.query.lower") { offenders.shouldBeEmpty() }

        // Control: the scanner flags the shape it exists to catch.
        forbidden.containsMatchIn(stripComments("fun f(b: GraphBuilder) { b.spawn(\"x\") { TODO() } }")) shouldBe true
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    private fun antiJoinFactory(result: LoweringResult.Lowered): SemiJoinFactory =
        f.spawns(result.spec.steps).single { it.handle == "q/0:antijoin" }.factory.shouldBeInstanceOf<SemiJoinFactory>()
}
