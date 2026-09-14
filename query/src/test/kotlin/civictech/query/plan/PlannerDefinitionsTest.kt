package civictech.query.plan

import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.parse.query
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import civictech.query.ast.Atom as AstAtom

/**
 * [Planner] over [Query.definitions] (computenet-cab.4.6, closing computenet-5pplz's "plan"
 * branch): a `define` statement's set operation or outer join plans to the matching
 * [Union]/[Intersect]/[Difference]/[OuterJoin] node, as a root of the plan and as an input
 * to any rule or definition that names its head.
 *
 * Every definition here is built with the `query { }` builder; the parser produces the same
 * AST (cab.2-D1), so nothing parser-specific is exercised. Analyses are asserted against the
 * existing [PlanAnalyses] rules applied to the node's own children, never re-derived here.
 */
class PlannerDefinitionsTest {

    private val catalog = Catalog(
        mapOf(
            "a" to schema("a1", "a2", rowKey = setOf("a1")),
            "b" to schema("b1", "b2"),
            "c" to schema("c1", "c2"),
            "t" to schema("t1", "t2", "t3"),
        ),
    )

    // ---------------------------------------------------------------- (g) 5pplz acceptance

    @Test
    fun `5pplz a query whose only statement is a definition plans with that head as a root`() {
        val q = query(catalog) {
            val x = v("X")
            val a = relation("a")
            define(derived("h")(x, v("Y"))) { rel(a(x, v("Y"))) union rel(relation("b")(v("P"), v("Q"))) }
        }
        q.rules.size shouldBe 0

        val plan = Planner.plan(q)

        withClue("a Query carrying a Definition must never plan to a LogicalPlan that omits its head") {
            plan.roots.keys shouldContain "h"
        }
        plan.roots.keys shouldBe setOf("h")
    }

    // ---------------------------------------------------------------- (a) union

    @Test
    fun `a union definition plans as Union whose branches share the head's columns`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) union rel(relation("b")(v("P"), v("Q"))) }
        }

        val root = Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<Union>()
        root.outputColumns shouldContainExactly listOf("X", "Y")
        root.inputs.size shouldBe 2
        val left = root.inputs[0].shouldBeInstanceOf<Scan>()
        val right = root.inputs[1].shouldBeInstanceOf<Scan>()
        left.relation shouldBe "a"
        right.relation shouldBe "b"
        withClue("the right operand is planned under the left operand's column names, no rename node") {
            left.outputColumns shouldContainExactly listOf("X", "Y")
            right.outputColumns shouldContainExactly listOf("X", "Y")
        }
        root.provenance shouldBe setOf("a", "b")
        root.keyAnalysis() shouldBe PlanAnalyses.unionKey(root.inputs)
    }

    // ---------------------------------------------------------------- (b) intersect / except

    @Test
    fun `an intersect definition plans as Intersect over the two operands`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) intersect rel(relation("b")(v("P"), v("Q"))) }
        }

        val root = Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<Intersect>()
        root.left.shouldBeInstanceOf<Scan>().relation shouldBe "a"
        root.right.shouldBeInstanceOf<Scan>().relation shouldBe "b"
        root.outputColumns shouldContainExactly listOf("X", "Y")
        root.left.outputColumns shouldContainExactly listOf("X", "Y")
        root.right.outputColumns shouldContainExactly listOf("X", "Y")
        root.provenance shouldBe setOf("a", "b")
        withClue("a's declared row key survives into an intersection, via PlanAnalyses.intersectKey") {
            root.keyAnalysis() shouldBe PlanAnalyses.intersectKey(root.left, root.right, root.outputColumns)
            root.preservedKey shouldBe setOf("X")
        }
    }

    @Test
    fun `an except definition plans as Difference with the left operand as left`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("b")(v("P"), v("Q"))) difference rel(relation("a")(x, y)) }
        }

        val root = Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<Difference>()
        root.left.shouldBeInstanceOf<Scan>().relation shouldBe "b"
        root.right.shouldBeInstanceOf<Scan>().relation shouldBe "a"
        withClue("the head renames the whole expression positionally, both operands included") {
            root.outputColumns shouldContainExactly listOf("X", "Y")
            root.left.outputColumns shouldContainExactly listOf("X", "Y")
            root.right.outputColumns shouldContainExactly listOf("X", "Y")
        }
        root.provenance shouldBe setOf("a", "b")
        withClue("b has no row key, so the difference claims none even though the right side has one") {
            root.keyAnalysis() shouldBe PlanAnalyses.differenceKey(root.left, root.outputColumns)
            root.keyPreserving shouldBe false
        }
    }

    // ---------------------------------------------------------------- (c) outer joins

    @Test
    fun `a left outer join definition plans as OuterJoin LEFT keyed on the merged key column`() {
        // define h(X, Y, Z) := a(X, Y) left outer join b(P, Z) on Y = P.
        val root = planOuterJoin { l, r, key -> leftJoin(l, r, key) }
        root.side shouldBe OuterJoinSide.LEFT
        assertOuterJoinShape(root)
    }

    @Test
    fun `a right outer join definition plans as OuterJoin RIGHT with the same column shape`() {
        val root = planOuterJoin { l, r, key -> rightJoin(l, r, key) }
        root.side shouldBe OuterJoinSide.RIGHT
        assertOuterJoinShape(root)
    }

    @Test
    fun `a full outer join definition plans as OuterJoin FULL with the same column shape`() {
        val root = planOuterJoin { l, r, key -> fullJoin(l, r, key) }
        root.side shouldBe OuterJoinSide.FULL
        assertOuterJoinShape(root)
    }

    @Test
    fun `an outer join renames apart a right column that shares a name with a left column but is not a key`() {
        // define h(A, B, C) := a(X, Y) left outer join b(P, Y) on X = P.   -- the two Ys are NOT joined.
        val q = query(catalog) {
            val x = v("X"); val y = v("Y"); val p = v("P")
            define(derived("h")(v("A"), v("B"), v("C"))) {
                leftJoin(rel(relation("a")(x, y)), rel(relation("b")(p, y)), x matches p)
            }
        }

        val root = Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<OuterJoin>()
        withClue("only the ON clause joins; a coincidentally shared name stays a distinct column") {
            root.keys shouldContainExactly listOf(JoinKey("A", "A"))
            root.left.outputColumns shouldContainExactly listOf("A", "B")
            root.right.outputColumns shouldContainExactly listOf("A", "C")
            root.outputColumns shouldContainExactly listOf("A", "B", "C")
        }
    }

    // ---------------------------------------------------------------- (d) rules consume definitions

    @Test
    fun `a rule body atom over a defined head plans over that definition's expression`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) union rel(relation("b")(v("P"), v("Q"))) }
            rule(derived("q")(x)) { +derived("h")(x, y) }
        }

        val plan = Planner.plan(q)
        plan.roots.keys shouldBe setOf("h", "q")
        val project = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        project.outputColumns shouldContainExactly listOf("X")
        val union = project.input.shouldBeInstanceOf<Union>()
        union.outputColumns shouldContainExactly listOf("X", "Y")
        union.inputs.map { (it as Scan).relation } shouldContainExactly listOf("a", "b")
        project.provenance shouldBe setOf("a", "b")
    }

    @Test
    fun `a rule consuming a defined head under different variable names substitutes them into the operands`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) intersect rel(relation("b")(y, x)) }
            rule(derived("q")(v("M"))) { +derived("h")(v("M"), const(7)) }
        }

        val project = Planner.plan(q).roots.getValue("q").shouldBeInstanceOf<Project>()
        project.outputColumns shouldContainExactly listOf("M")
        val select = project.input.shouldBeInstanceOf<Select>()
        withClue("a constant at a defined head's position is the planAtom synthetic-column Select, reused") {
            select.condition.op shouldBe ComparisonOp.EQ
            select.condition.right shouldBe Term.Const(7, AttrType.INT)
        }
        val intersect = select.input.shouldBeInstanceOf<Intersect>()
        intersect.outputColumns[0] shouldBe "M"
        intersect.left.outputColumns shouldBe intersect.outputColumns
        intersect.right.outputColumns shouldBe intersect.outputColumns
    }

    // ---------------------------------------------------------------- (e) composition

    @Test
    fun `a definition over another definition composes`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("ab")(x, y)) { rel(relation("a")(x, y)) union rel(relation("b")(x, y)) }
            define(derived("h")(v("U"), v("W"))) { rel(derived("ab")(x, y)) difference rel(relation("c")(x, y)) }
        }

        val plan = Planner.plan(q)
        plan.roots.keys shouldBe setOf("ab", "h")
        val root = plan.roots.getValue("h").shouldBeInstanceOf<Difference>()
        root.outputColumns shouldContainExactly listOf("U", "W")
        val inner = root.left.shouldBeInstanceOf<Union>()
        inner.outputColumns shouldContainExactly listOf("U", "W")
        inner.inputs.map { it.outputColumns } shouldContainExactly listOf(listOf("U", "W"), listOf("U", "W"))
        root.right.shouldBeInstanceOf<Scan>().outputColumns shouldContainExactly listOf("U", "W")
        root.provenance shouldBe setOf("a", "b", "c")
    }

    @Test
    fun `a definition's head names its columns positionally, not by matching variable names`() {
        // define h(Y, X) := a(X, Y).   -- column 0 of h is a's column 0, named Y.
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(y, x)) { rel(relation("a")(x, y)) }
        }

        val scan = Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<Scan>()
        scan.relation shouldBe "a"
        scan.outputColumns shouldContainExactly listOf("Y", "X")
        withClue("a1 is a's row key, at position 0, which the head names Y") {
            scan.preservedKey shouldBe setOf("Y")
        }
    }

    // ---------------------------------------------------------------- (f) bag semantics

    @Test
    fun `QRY1 §SEM-04 an ALL set operation fails fast naming BAG_SEMANTICS_REQUIRED`() {
        val builders: List<Pair<String, (RelationalExpr, RelationalExpr) -> RelationalExpr>> = listOf(
            "UNION ALL" to { l, r -> RelationalExpr.SetOp(SetOpKind.UNION, l, r, all = true) },
            "INTERSECT ALL" to { l, r -> RelationalExpr.SetOp(SetOpKind.INTERSECTION, l, r, all = true) },
            "EXCEPT ALL" to { l, r -> RelationalExpr.SetOp(SetOpKind.DIFFERENCE, l, r, all = true) },
        )
        for ((label, build) in builders) {
            val q = Query(
                rules = emptyList(),
                catalog = catalog,
                definitions = listOf(
                    Definition(
                        head = atom("h", "X", "Y"),
                        expr = build(RelationalExpr.Relation(atom("a", "X", "Y")), RelationalExpr.Relation(atom("b", "X", "Y"))),
                    ),
                ),
            )
            withClue("$label must never be silently planned as its DISTINCT form") {
                shouldThrow<IllegalArgumentException> { Planner.plan(q) }.message shouldContain "BAG_SEMANTICS_REQUIRED"
            }
        }
    }

    // ---------------------------------------------------------------- fail-fast guards

    @Test
    fun `recursion through a definition fails fast in the planner`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) union rel(derived("g")(x, y)) }
            rule(derived("g")(x, y)) { +derived("h")(x, y) }
        }

        shouldThrow<IllegalArgumentException> { Planner.plan(q) }.message shouldContain "recursion"
    }

    @Test
    fun `a head defined twice, or by both a rule and a definition, fails fast`() {
        val twice = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) }
            define(derived("h")(x, y)) { rel(relation("b")(x, y)) }
        }
        shouldThrow<IllegalArgumentException> { Planner.plan(twice) }.message shouldContain "'h'"

        val both = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) }
            rule(derived("h")(x, y)) { +relation("b")(x, y) }
        }
        shouldThrow<IllegalArgumentException> { Planner.plan(both) }.message shouldContain "'h'"
    }

    @Test
    fun `set operation operands of different arity fail fast`() {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y")
            define(derived("h")(x, y)) { rel(relation("a")(x, y)) union rel(relation("t")(x, y, v("Z"))) }
        }
        shouldThrow<IllegalArgumentException> { Planner.plan(q) }.message shouldContain "arity"
    }

    // ---------------------------------------------------------------- fixtures

    private fun planOuterJoin(
        join: civictech.query.parse.RelationalScope.(RelationalExpr, RelationalExpr, civictech.query.ast.JoinKey) -> RelationalExpr,
    ): OuterJoin {
        val q = query(catalog) {
            val x = v("X"); val y = v("Y"); val z = v("Z"); val p = v("P")
            define(derived("h")(x, y, z)) {
                join(rel(relation("a")(x, y)), rel(relation("b")(p, z)), y matches p)
            }
        }
        return Planner.plan(q).roots.getValue("h").shouldBeInstanceOf<OuterJoin>()
    }

    /**
     * The shape every outer-join side shares for `a(X, Y) <side> outer join b(P, Z) on Y = P`
     * under head `h(X, Y, Z)`: the right key variable P is substituted by the left key
     * variable Y, so the node's key is `JoinKey("Y", "Y")` and the output is left's columns
     * plus right's not already in left — `joinOn`'s shape.
     */
    private fun assertOuterJoinShape(root: OuterJoin) {
        root.keys shouldContainExactly listOf(JoinKey("Y", "Y"))
        root.left.shouldBeInstanceOf<Scan>().outputColumns shouldContainExactly listOf("X", "Y")
        root.right.shouldBeInstanceOf<Scan>().outputColumns shouldContainExactly listOf("Y", "Z")
        root.outputColumns shouldContainExactly listOf("X", "Y", "Z")
        root.provenance shouldBe setOf("a", "b")
        root.keyAnalysis() shouldBe PlanAnalyses.outerJoinKey(root.left, root.right, root.outputColumns)
    }

    private fun atom(predicate: String, vararg variables: String): AstAtom =
        AstAtom(predicate, variables.map { Term.Var(it) })

    private fun schema(vararg attributes: String, rowKey: Set<String>? = null): RelationSchema =
        RelationSchema(attributes.map { Attribute(it, AttrType.INT) }, rowKey)
}
