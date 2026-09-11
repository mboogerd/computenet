package civictech.query.plan

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Term
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * `[QRY1-LANG-06]` (plan half): `LogicalPlan` is `Serializable` pure data containing no
 * function value, asserted by a round-trip through `ObjectOutputStream`/`ObjectInputStream`
 * over a representative plan covering every closed node kind — following
 * `civictech.query.ast.QuerySerializationTest`'s `roundTrip` pattern.
 */
class LogicalPlanSerializationTest {

    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(value) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
    }

    private val link = Scan(
        relation = "link",
        outputColumns = listOf("src", "dst"),
        provenance = setOf("link"),
        keyPreserving = true,
        preservedKey = setOf("src", "dst"),
    )

    private val blocked = Scan(
        relation = "blocked",
        outputColumns = listOf("src", "dst"),
        provenance = setOf("blocked"),
        keyPreserving = false,
    )

    private val dist = Scan(
        relation = "dist",
        outputColumns = listOf("src", "dst", "d"),
        provenance = setOf("dist"),
        keyPreserving = false,
    )

    private val members = Scan(
        relation = "members",
        outputColumns = listOf("group", "person"),
        provenance = setOf("members"),
        keyPreserving = false,
    )

    private val banned = Scan(
        relation = "banned",
        outputColumns = listOf("person"),
        provenance = setOf("banned"),
        keyPreserving = false,
    )

    private val vip = Scan(
        relation = "vip",
        outputColumns = listOf("person"),
        provenance = setOf("vip"),
        keyPreserving = false,
    )

    /** `edge(X, Y) :- link(X, Y), not blocked(X, Y).` — Scan, Select is layered on below. */
    private val edgeSelect = Select(
        input = link,
        condition = Literal.Comparison(Term.Var("src"), ComparisonOp.NE, Term.Var("dst")),
        outputColumns = listOf("src", "dst"),
        provenance = setOf("link"),
        keyPreserving = true,
        preservedKey = setOf("src", "dst"),
    )

    private val edgeProject = Project(
        input = edgeSelect,
        outputColumns = listOf("src", "dst"),
        provenance = setOf("link"),
        keyPreserving = true,
        preservedKey = setOf("src", "dst"),
    )

    /** A cross product — no shared variable, `[QRY1-PLAN-02]`, an empty `equiKeys` list. */
    private val crossProduct = Join(
        left = link,
        right = dist,
        equiKeys = emptyList(),
        outputColumns = listOf("src", "dst", "dsrc", "ddst", "d"),
        provenance = setOf("link", "dist"),
        keyPreserving = false,
    )

    /** An equi-join on a shared variable. */
    private val equiJoin = Join(
        left = link,
        right = dist,
        equiKeys = listOf(JoinKey(left = "src", right = "src"), JoinKey(left = "dst", right = "dst")),
        outputColumns = listOf("src", "dst", "d"),
        provenance = setOf("link", "dist"),
        keyPreserving = false,
    )

    private val semiJoin = SemiJoin(
        input = members,
        witness = vip,
        keys = listOf(JoinKey(left = "person", right = "person")),
        outputColumns = listOf("group", "person"),
        provenance = setOf("members", "vip"),
        keyPreserving = false,
    )

    private val antiJoin = AntiJoin(
        input = members,
        witness = banned,
        keys = listOf(JoinKey(left = "person", right = "person")),
        outputColumns = listOf("group", "person"),
        provenance = setOf("members", "banned"),
        keyPreserving = false,
    )

    private val union = Union(
        inputs = listOf(edgeProject, equiJoin.let { Project(it, listOf("src", "dst"), it.provenance, false) }),
        outputColumns = listOf("src", "dst"),
        provenance = setOf("link", "dist"),
        keyPreserving = false,
    )

    private val intersect = Intersect(
        left = semiJoin,
        right = antiJoin,
        outputColumns = listOf("group", "person"),
        provenance = setOf("members", "vip", "banned"),
        keyPreserving = false,
    )

    private val difference = Difference(
        left = members,
        right = banned,
        outputColumns = listOf("group", "person"),
        provenance = setOf("members", "banned"),
        keyPreserving = false,
    )

    private val groupAggregate = GroupAggregate(
        input = members,
        groupByColumns = listOf("group"),
        aggregatedColumn = "person",
        aggregate = Aggregate(AggregateKind.COUNT),
        outputColumn = "member_count",
        outputColumns = listOf("group", "member_count"),
        provenance = setOf("members"),
        keyPreserving = false,
    )

    private val outerJoin = OuterJoin(
        left = members,
        right = vip,
        keys = listOf(JoinKey(left = "person", right = "person")),
        side = OuterJoinSide.LEFT,
        outputColumns = listOf("group", "person"),
        provenance = setOf("members", "vip"),
        keyPreserving = false,
    )

    private fun representativePlan(): LogicalPlan = LogicalPlan(
        roots = mapOf(
            "edge" to edgeProject,
            "cross" to crossProduct,
            "close" to equiJoin,
            "reachable_vip" to semiJoin,
            "unbanned_member" to antiJoin,
            "unioned" to union,
            "both" to intersect,
            "not_banned" to difference,
            "counts" to groupAggregate,
            "outer" to outerJoin,
        ),
    )

    @Test
    fun `a representative plan covering every node kind round-trips equal to the original`() {
        val plan = representativePlan()
        roundTrip(plan) shouldBe plan
    }

    @Test
    fun `a Scan round-trips carrying its key-preservation witness`() {
        roundTrip(link) shouldBe link
        roundTrip(link).preservedKey shouldBe setOf("src", "dst")
    }

    @Test
    fun `a non-key-preserving node round-trips with a null preservedKey`() {
        roundTrip(blocked) shouldBe blocked
        roundTrip(blocked).preservedKey shouldBe null
    }

    @Test
    fun `a cross product Join round-trips with an empty equiKeys list`() {
        val result = roundTrip(crossProduct)
        result shouldBe crossProduct
        result.equiKeys shouldBe emptyList()
    }

    @Test
    fun `provenance survives a round-trip through a multi-relation node`() {
        roundTrip(intersect).provenance shouldBe setOf("members", "vip", "banned")
    }

    @Test
    fun `a topK GroupAggregate round-trips carrying its aggregate's k`() {
        val topK = groupAggregate.copy(aggregate = Aggregate(AggregateKind.TOP_K, k = 5))
        val result = roundTrip(topK)
        result shouldBe topK
        result.aggregate shouldBe Aggregate(AggregateKind.TOP_K, k = 5)
    }
}
