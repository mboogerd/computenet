package civictech.query.lower

import civictech.cell.CellRef
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.SpawnStep
import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.ComparisonOp
import civictech.query.expr.RowKey
import civictech.query.expr.RowLongSelector
import civictech.query.expr.RowSelector
import civictech.query.plan.GroupAggregate
import civictech.query.plan.LogicalPlan
import civictech.query.plan.PlanNode
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import civictech.query.schema.Row
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Root-position `GroupAggregate` lowering (computenet-cab.4.3, cab.4-D8, `[24-OP-GROUPBY-01..05]`,
 * `[24-OP-COUNT-01]`, `[24-AGG-01]`): structure only — which cell, which key, which aggregate
 * spec — never an evaluated aggregate (the differential feature owns extensional evidence).
 */
class AggregateLoweringTest {

    private val f = PlanFixtures

    /** `r(g, v)` with `v` of [valueType]. */
    private fun catalog(valueType: AttrType) = Catalog(
        mapOf("r" to RelationSchema(listOf(Attribute("a0", AttrType.STRING), Attribute("a1", valueType)))),
    )

    private fun aggregate(kind: AggregateKind, grouped: Boolean, k: Int? = null, input: PlanNode = f.scan("r", "g", "v")) =
        GroupAggregate(
            input = input,
            groupByColumns = if (grouped) listOf("g") else emptyList(),
            aggregatedColumn = "v",
            aggregate = Aggregate(kind, k),
            outputColumn = "v",
            outputColumns = if (grouped) listOf("g", "v") else listOf("v"),
            provenance = input.provenance,
            keyPreserving = false,
        )

    private fun lowerRoot(node: GroupAggregate, valueType: AttrType = AttrType.INT) =
        f.lowered(LogicalPlan(mapOf("q" to node)), catalog(valueType))

    private fun aggregateSpawn(node: GroupAggregate, valueType: AttrType = AttrType.INT): SpawnStep =
        f.spawns(lowerRoot(node, valueType).spec.steps).single { it.handle == "q/0:groupaggregate" }

    private val columns = listOf("g", "v")

    @Test
    fun `a grouped aggregate lowers to GroupByCell keyed on the group-by columns`() {
        val result = lowerRoot(aggregate(AggregateKind.COUNT, grouped = true))

        result.spec.steps shouldContainExactly listOf(
            SpawnStep("src:r", SetSourceFactory("r")),
            SpawnStep("q/0:groupaggregate", GroupByFactory(RowKey(columns, listOf("g")), AggregateSpec.Count)),
            ConnectStep("src:r", "outlet", "q/0:groupaggregate", "inlet"),
        )
        result.outputHandles shouldBe mapOf("q" to "q/0:groupaggregate")
        result.spec.steps.filterIsInstance<SpawnStep>().last().factory.create(CellRef(UUID.randomUUID()))
            .shouldBeInstanceOf<GroupByCell<*, *, *, *>>()
    }

    @Test
    fun `a scalar COUNT lowers to CountCell, not GroupByCell-global`() {
        val spawn = aggregateSpawn(aggregate(AggregateKind.COUNT, grouped = false))

        spawn.factory shouldBe CountFactory(columns)
        spawn.factory.create(CellRef(UUID.randomUUID())).shouldBeInstanceOf<CountCell<*>>()
    }

    @Test
    fun `a scalar aggregate other than COUNT lowers to GroupByCell-global, keyless`() {
        val spawn = aggregateSpawn(aggregate(AggregateKind.MAX, grouped = false))

        spawn.factory shouldBe GroupByFactory(null, AggregateSpec.Max(RowSelector(columns, "v"), AttrType.INT))
        spawn.factory.create(CellRef(UUID.randomUUID())).shouldBeInstanceOf<GroupByCell<*, *, *, *>>()
    }

    @Test
    fun `each aggregate kind maps to its spec, MIN MAX TOP_K carrying a RowSelector typed by the column`() {
        val key = RowKey(columns, listOf("g"))
        val expected = mapOf(
            AggregateKind.COUNT to AggregateSpec.Count,
            AggregateKind.SUM to AggregateSpec.Sum(RowLongSelector(columns, "v")),
            AggregateKind.AVG to AggregateSpec.Avg(RowLongSelector(columns, "v")),
            AggregateKind.MIN to AggregateSpec.Min(RowSelector(columns, "v"), AttrType.LONG),
            AggregateKind.MAX to AggregateSpec.Max(RowSelector(columns, "v"), AttrType.LONG),
            AggregateKind.TOP_K to AggregateSpec.TopK(3, RowSelector(columns, "v"), AttrType.LONG),
            AggregateKind.COLLECT_TO_SET to AggregateSpec.CollectToSet,
        )
        withClue("every AggregateKind is covered") { expected.keys shouldBe AggregateKind.entries.toSet() }
        expected.forEach { (kind, spec) ->
            val node = aggregate(kind, grouped = true, k = if (kind == AggregateKind.TOP_K) 3 else null)
            withClue(kind) {
                val factory = aggregateSpawn(node, AttrType.LONG).factory
                factory shouldBe GroupByFactory(key, spec)
                factory.create(CellRef(UUID.randomUUID())).shouldBeInstanceOf<GroupByCell<*, *, *, *>>()
            }
        }
    }

    @Test
    fun `MIN MAX and TOP_K build an aggregator for every column type`() {
        AttrType.entries.forEach { type ->
            listOf(
                AggregateSpec.Min(RowSelector(columns, "v"), type),
                AggregateSpec.Max(RowSelector(columns, "v"), type),
                AggregateSpec.TopK(2, RowSelector(columns, "v"), type),
            ).forEach { spec ->
                withClue("$spec") { GroupByFactory(RowKey(columns, listOf("g")), spec).create(CellRef(UUID.randomUUID())) }
            }
        }
    }

    @Test
    fun `QRY1 §24-AGG-01 SUM and AVG over a DOUBLE, STRING or BOOL column are refused citing the Long-only rationale`() {
        listOf(AttrType.DOUBLE, AttrType.STRING, AttrType.BOOL).forEach { type ->
            listOf(AggregateKind.SUM, AggregateKind.AVG).forEach { kind ->
                withClue("$kind over $type") {
                    val refusal = f.refused(LogicalPlan(mapOf("q" to aggregate(kind, grouped = true))), catalog(type))
                        .refusals.single()
                    refusal.nodeKind shouldBe "GroupAggregate"
                    refusal.reason shouldContain "[24-AGG-01]"
                    refusal.reason shouldContain "Aggregator.kt:30"
                    refusal.reason shouldContain "order-sensitive"
                }
            }
        }
        // Control: INT and LONG lower.
        listOf(AttrType.INT, AttrType.LONG).forEach { type -> lowerRoot(aggregate(AggregateKind.SUM, grouped = true), type) }
    }

    @Test
    fun `COLLECT_TO_SET collects whole input rows`() {
        val aggregator = AggregateSpec.CollectToSet.aggregator()
        @Suppress("UNCHECKED_CAST")
        val collect = aggregator as civictech.cell.data.Aggregator<Row, Set<Row>, java.util.HashSet<Row>>
        val row = Row(listOf("g1", 7))
        collect.value(collect.insert(collect.empty(), row)) shouldBe setOf(row)
    }

    @Test
    fun `a GroupAggregate below the root stays refused as not a relation`() {
        val plan = LogicalPlan(mapOf("q" to f.select(aggregate(AggregateKind.SUM, grouped = true), f.v("v"), ComparisonOp.GT, f.int(1))))
        f.refused(plan, catalog(AttrType.INT)).refusals.single().reason shouldBe Lowering.AGGREGATE_NOT_A_RELATION
    }

    @Test
    fun `an aggregated or group-by column no input produces is refused, not thrown`() {
        val bad = aggregate(AggregateKind.MIN, grouped = true).copy(aggregatedColumn = "missing")
        f.refused(LogicalPlan(mapOf("q" to bad)), catalog(AttrType.INT)).refusals.single().reason shouldContain "missing"
    }
}
