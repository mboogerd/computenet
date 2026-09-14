package civictech.query.lower

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphStep
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.fullJoin
import civictech.cell.graph.graph
import civictech.cell.graph.leftJoin
import civictech.cell.graph.rightJoin
import civictech.query.expr.RowCombinePadded
import civictech.query.expr.RowKey
import civictech.query.expr.RowPad
import civictech.query.plan.JoinKey
import civictech.query.plan.LogicalPlan
import civictech.query.plan.OuterJoin
import civictech.query.plan.OuterJoinSide
import civictech.query.plan.PlanNode
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * cab.4-D6's fidelity test (computenet-cab.4.3, `[24-OP-OUTERJOIN-01..02]`): the lowered
 * `OuterJoin` must be the `RelationalGraphs.leftJoin`/`rightJoin`/`fullJoin` composition. The
 * kernel compositions are recorded live on a `SimWorld` host (test-only; the lowering itself never
 * touches one) and compared with the lowering's steps modulo the handle prefix: the `ConnectStep`
 * multiset, and the cell class each handle's factory creates (the kernel factories are lambdas,
 * so the factories themselves cannot compare equal).
 *
 * The plan nodes are in the shape the planner builds (cab.4.6): the join key is merged into ONE
 * output column `k` present on both inputs, `keys = [JoinKey("k", "k")]`.
 */
class OuterJoinFidelityTest {

    private val f = PlanFixtures
    private val leftColumns = listOf("k", "a")
    private val rightColumns = listOf("k", "b")
    private val outColumns = listOf("k", "a", "b")
    private val nodeHandle = "q/0:outerjoin"

    private fun outerJoin(side: OuterJoinSide, left: PlanNode = f.scan("l", "k", "a"), right: PlanNode = f.scan("r", "k", "b")) =
        OuterJoin(left, right, listOf(JoinKey("k", "k")), side, outColumns, left.provenance + right.provenance, false)

    private fun lowered(side: OuterJoinSide): List<GraphStep> =
        f.lowered(LogicalPlan(mapOf("q" to outerJoin(side))), f.catalog("l" to 2, "r" to 2)).spec.steps

    /** The kernel composition named "h" over spawned sources "l"/"r", renamed into the lowering's handles. */
    private fun recorded(side: OuterJoinSide): List<GraphStep> {
        val world = SimWorld(seed = 1)
        val leftKey = RowKey(leftColumns, listOf("k"))
        val rightKey = RowKey(rightColumns, listOf("k"))
        val combine = RowCombinePadded(leftColumns, rightColumns, outColumns)
        val spec = graph(world.host.managementInlet) {
            val l = spawn("l") { SetCell<Row>() }
            val r = spawn("r") { SetCell<Row>() }
            when (side) {
                OuterJoinSide.LEFT ->
                    leftJoin<Row, Row, Row, Row>("h", l, r, leftKey, rightKey) { a, b -> combine(a, b) }
                OuterJoinSide.RIGHT ->
                    rightJoin<Row, Row, Row, Row>("h", l, r, leftKey, rightKey) { a, b -> combine(a, b) }
                OuterJoinSide.FULL ->
                    fullJoin<Row, Row, Row, Row>("h", l, r, leftKey, rightKey) { a, b -> combine(a, b) }
            }
        }
        fun rename(handle: String) = when {
            handle == "l" -> "src:l"
            handle == "r" -> "src:r"
            handle.startsWith("h") -> nodeHandle + handle.removePrefix("h")
            else -> error("unexpected recorded handle $handle")
        }
        return spec.steps.map { step ->
            when (step) {
                is SpawnStep -> step.copy(handle = rename(step.handle))
                is ConnectStep -> step.copy(from = rename(step.from), to = rename(step.to))
                else -> error("unexpected recorded step $step")
            }
        }
    }

    private fun connects(steps: List<GraphStep>) = steps.filterIsInstance<ConnectStep>()

    /** Handle -> the class of the cell its factory creates, for every non-source spawn. */
    private fun cellClasses(steps: List<GraphStep>): Map<String, Class<*>> =
        steps.filterIsInstance<SpawnStep>()
            .filterNot { it.handle.startsWith("src:") }
            .associate { it.handle to it.factory.create(CellRef(UUID.randomUUID())).javaClass }

    private fun assertMirrors(side: OuterJoinSide) {
        val lowered = lowered(side)
        val recorded = recorded(side)
        withClue("$side: ConnectStep multiset equals RelationalGraphs' modulo handle prefix") {
            connects(lowered) shouldContainExactlyInAnyOrder connects(recorded)
        }
        withClue("$side: same cell class per handle") {
            cellClasses(lowered) shouldBe cellClasses(recorded)
        }
        withClue("non-vacuity: the composition has more than one cell") {
            (cellClasses(recorded).size >= 4) shouldBe true
        }
    }

    @Test
    fun `LEFT outer join lowers to exactly the RelationalGraphs leftJoin topology`() = assertMirrors(OuterJoinSide.LEFT)

    @Test
    fun `RIGHT outer join lowers to exactly the RelationalGraphs rightJoin topology`() = assertMirrors(OuterJoinSide.RIGHT)

    @Test
    fun `FULL outer join lowers to exactly the RelationalGraphs fullJoin topology`() = assertMirrors(OuterJoinSide.FULL)

    @Test
    fun `the node's own handle is the union, and it is the root's output`() {
        val result = f.lowered(LogicalPlan(mapOf("q" to outerJoin(OuterJoinSide.FULL))), f.catalog("l" to 2, "r" to 2))
        result.outputHandles shouldBe mapOf("q" to nodeHandle)
        f.spawns(result.spec.steps).single { it.handle == nodeHandle }.factory shouldBe UnionFactory(outColumns)
    }

    private fun pad(steps: List<GraphStep>, suffix: String): RowPad =
        f.spawns(steps).single { it.handle == nodeHandle + suffix }.factory.shouldBeInstanceOf<PadFactory>().pad

    @Test
    fun `null-padded rows fill the merged key column from the present side - LEFT, RIGHT and FULL`() {
        val leftRow = Row(listOf(1, 10))
        val rightRow = Row(listOf(2, 20))

        pad(lowered(OuterJoinSide.LEFT), "-null") shouldBe RowPad(leftColumns, outColumns)
        pad(lowered(OuterJoinSide.LEFT), "-null").invoke(leftRow).toList() shouldContainExactly listOf(Row(listOf(1, 10, null)))

        // RIGHT: the preserved side is the plan's right; its unmatched rows carry k from the RIGHT row.
        pad(lowered(OuterJoinSide.RIGHT), "-null") shouldBe RowPad(rightColumns, outColumns)
        pad(lowered(OuterJoinSide.RIGHT), "-null").invoke(rightRow).toList() shouldContainExactly listOf(Row(listOf(2, null, 20)))

        val full = lowered(OuterJoinSide.FULL)
        pad(full, "-left-null").invoke(leftRow).toList() shouldContainExactly listOf(Row(listOf(1, 10, null)))
        pad(full, "-right-null").invoke(rightRow).toList() shouldContainExactly listOf(Row(listOf(2, null, 20)))
    }

    @Test
    fun `QRY1 §LOWER-08 §LOWER-09 each antijoin in the composition is gated by Gating-decide, the join and union never`() {
        // Disjoint sources: every antijoin ungated with an EventuallyConsistent diagnostic of its own handle.
        val disjoint = f.lowered(LogicalPlan(mapOf("q" to outerJoin(OuterJoinSide.FULL))), f.catalog("l" to 2, "r" to 2))
        disjoint.diagnostics.map { it.shouldBeInstanceOf<LoweringDiagnostic.EventuallyConsistent>().handle } shouldContainExactly
            listOf("$nodeHandle-left-only", "$nodeHandle-right-only")
        f.spawns(disjoint.spec.steps).mapNotNull { it.factory as? SemiJoinFactory }.map { it.emitOnFrontier } shouldContainExactly
            listOf(false, false)

        // A shared, shallow source: both antijoins gated, no diagnostic.
        val shared = outerJoin(
            OuterJoinSide.FULL,
            left = f.scan("e", "k", "a"),
            right = f.select(f.scan("e", "k", "b"), f.v("b"), civictech.query.ast.ComparisonOp.GT, f.int(0)),
        )
        val sharedResult = f.lowered(LogicalPlan(mapOf("q" to shared)), f.catalog("e" to 2))
        sharedResult.diagnostics.shouldBeEmpty()
        val semis = f.spawns(sharedResult.spec.steps).mapNotNull { it.factory as? SemiJoinFactory }
        semis.map { it.emitOnFrontier to it.negated } shouldContainExactly listOf(true to true, true to true)

        // LEFT, disjoint: its one antijoin carries the AntiJoin rule's diagnostic.
        val left = f.lowered(LogicalPlan(mapOf("q" to outerJoin(OuterJoinSide.LEFT))), f.catalog("l" to 2, "r" to 2))
        left.diagnostics shouldContainExactly listOf(
            Gating.decide(outerJoin(OuterJoinSide.LEFT).left, outerJoin(OuterJoinSide.LEFT).right,
                civictech.query.diag.Locus.PlanNode(nodeHandle), "$nodeHandle-unmatched").diagnostic!!,
        )
    }

    @Test
    fun `each keyed cell reads its own inlet's key position - RIGHT and FULL swap keys exactly as RelationalGraphs does`() {
        // The key column sits at a DIFFERENT index on each side, so a RowKey handed to the wrong
        // inlet is a different data value (the fidelity test above cannot see this: its fixture
        // keys both sides at index 0, and the kernel's key lambdas cannot be compared).
        val l = listOf("a", "k")
        val r = listOf("k", "b")
        val lKey = RowKey(l, listOf("k"))
        val rKey = RowKey(r, listOf("k"))
        fun keyed(side: OuterJoinSide): Map<String, Any> {
            val node = OuterJoin(
                f.scan("l", "a", "k"), f.scan("r", "k", "b"), listOf(JoinKey("k", "k")), side,
                outColumns, setOf("l", "r"), false,
            )
            return f.spawns(f.lowered(LogicalPlan(mapOf("q" to node)), f.catalog("l" to 2, "r" to 2)).spec.steps)
                .filter { it.factory is JoinFactory || it.factory is SemiJoinFactory }
                .associate { it.handle.removePrefix(nodeHandle) to it.factory }
        }
        // Disjoint sources: every antijoin is ungated (emitOnFrontier = false).
        keyed(OuterJoinSide.LEFT) shouldBe mapOf(
            "-matched" to JoinFactory(lKey, rKey, civictech.query.expr.RowCombine(l, r, outColumns)),
            "-unmatched" to SemiJoinFactory(lKey, rKey, true, false),
        )
        keyed(OuterJoinSide.RIGHT) shouldBe mapOf(
            "-matched" to JoinFactory(rKey, lKey, civictech.query.expr.RowCombine(r, l, outColumns)),
            "-unmatched" to SemiJoinFactory(rKey, lKey, true, false),
        )
        keyed(OuterJoinSide.FULL) shouldBe mapOf(
            "-matched" to JoinFactory(lKey, rKey, civictech.query.expr.RowCombine(l, r, outColumns)),
            "-left-only" to SemiJoinFactory(lKey, rKey, true, false),
            "-right-only" to SemiJoinFactory(rKey, lKey, true, false),
        )
    }

    @Test
    fun `an outer join whose key names no input column is refused, not thrown`() {
        val bad = OuterJoin(
            f.scan("l", "k", "a"), f.scan("r", "k", "b"), listOf(JoinKey("z", "z")), OuterJoinSide.LEFT,
            outColumns, setOf("l", "r"), false,
        )
        f.refused(LogicalPlan(mapOf("q" to bad)), f.catalog("l" to 2, "r" to 2)).refusals.single().nodeKind shouldBe "OuterJoin"
    }
}
