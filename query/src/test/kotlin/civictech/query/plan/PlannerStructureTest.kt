package civictech.query.plan

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Structural plan assertions over [Planner] (computenet-cab.3.2, epic computenet-cab §4.2).
 *
 * Each test asserts the *shape* of the plan — which node kind sits where, and which columns
 * a join or a semijoin keys on — rather than any evaluated answer: this task owns the
 * translation, and the analyses' correctness ([QRY1-PLAN-05]/[QRY1-PLAN-06]) and the
 * determinism evidence (cab.3-D1) belong to this feature's successor task. The key-slot test
 * below therefore pins what the planner *constructs*, not the full decision procedure.
 */
class PlannerStructureTest {

    // ---------------------------------------------------------------- [QRY1-PLAN-02]

    @Test
    fun `QRY1 §PLAN-02 a shared variable between two positive atoms plans as one equi-join on it`() {
        // q(x, z) :- e(x, y), e(y, z).   — feature example 1, the BS-6 precondition's join half.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "z"),
                        body = listOf(positive(atom("e", "x", "y")), positive(atom("e", "y", "z"))),
                    ),
                ),
                catalog = catalogOf("e" to listOf("a", "b")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.outputColumns shouldContainExactly listOf("x", "z")

        val join = root.input.shouldBeInstanceOf<Join>()
        withClue("the shared variable y is the equi-join key, not a filter over a cross product") {
            join.equiKeys shouldContainExactly listOf(JoinKey("y", "y"))
        }
        join.outputColumns shouldContainExactly listOf("x", "y", "z")

        val left = join.left.shouldBeInstanceOf<Scan>()
        val right = join.right.shouldBeInstanceOf<Scan>()
        left.relation shouldBe "e"
        right.relation shouldBe "e"
        left.outputColumns shouldContainExactly listOf("x", "y")
        right.outputColumns shouldContainExactly listOf("y", "z")

        withClue("provenance is populated bottom-up: both join inputs and the join report {e}") {
            left.provenance shouldBe setOf("e")
            right.provenance shouldBe setOf("e")
            join.provenance shouldBe setOf("e")
            root.provenance shouldBe setOf("e")
        }
    }

    @Test
    fun `QRY1 §PLAN-02 a body with no shared variable plans as a cross product`() {
        // q(x, y) :- r(x), s(y).
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "y"),
                        body = listOf(positive(atom("r", "x")), positive(atom("s", "y"))),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a"), "s" to listOf("a")),
            ),
        )

        val join = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        withClue("no variable is shared, so the cross product is a Join with zero equi-keys") {
            join.equiKeys.shouldBeEmpty()
        }
        join.outputColumns shouldContainExactly listOf("x", "y")
        join.left.shouldBeInstanceOf<Scan>().relation shouldBe "r"
        join.right.shouldBeInstanceOf<Scan>().relation shouldBe "s"
        join.provenance shouldBe setOf("r", "s")
    }

    @Test
    fun `QRY1 §PLAN-02 no filtered cross product appears where an equi-join is available`() {
        // The negative case: for q(x, z) :- e(x, y), e(y, z). the planner must NOT emit a
        // zero-key Join with an equality Select above it. There is no comparison in this
        // rule at all, so ANY Select node here would be a manufactured filter, and any
        // zero-key Join would be a cross product where an equi-join was available.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "z"),
                        body = listOf(positive(atom("e", "x", "y")), positive(atom("e", "y", "z"))),
                    ),
                ),
                catalog = catalogOf("e" to listOf("a", "b")),
            ),
        )

        val nodes = allNodes(plan.roots.getValue("q"))
        withClue("a shared-variable body must not plan as a cross product: $nodes") {
            nodes.filterIsInstance<Join>().filter { it.equiKeys.isEmpty() }.shouldBeEmpty()
        }
        withClue("no comparison exists in this rule, so no Select may appear: $nodes") {
            nodes.filterIsInstance<Select>().shouldBeEmpty()
        }
        nodes.filterIsInstance<Join>() shouldHaveSize 1
    }

    // ---------------------------------------------------------------- [QRY1-PLAN-03]

    @Test
    fun `QRY1 §PLAN-03 a comparison sits on the cross-product node that first binds both its variables`() {
        // q(x) :- r(x), s(y), x < y.   — feature example 2. The lowest node binding both x
        // and y is the cross product; the comparison must not float above the head Project.
        val comparison = Literal.Comparison(Term.Var("x"), ComparisonOp.LT, Term.Var("y"))
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(positive(atom("r", "x")), positive(atom("s", "y")), comparison),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a"), "s" to listOf("a")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.outputColumns shouldContainExactly listOf("x")

        val selection = root.input.shouldBeInstanceOf<Select>()
        selection.condition shouldBe comparison

        val join = selection.input.shouldBeInstanceOf<Join>()
        withClue("r and s share no variable, so the node binding {x, y} is a cross product") {
            join.equiKeys.shouldBeEmpty()
        }
        withClue("the comparison belongs on the join, not pushed below it onto a single scan") {
            join.left.shouldBeInstanceOf<Scan>().relation shouldBe "r"
            join.right.shouldBeInstanceOf<Scan>().relation shouldBe "s"
        }
    }

    @Test
    fun `QRY1 §PLAN-03 a comparison over one atom's variables sits below the join, not above it`() {
        // q(x, z) :- e(x, y), e(y, z), x > 3.   — x is bound by the first atom alone, so the
        // LOWEST node binding it is that atom's scan, strictly below the join.
        val comparison = Literal.Comparison(Term.Var("x"), ComparisonOp.GT, Term.Const(3, AttrType.INT))
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "z"),
                        body = listOf(
                            positive(atom("e", "x", "y")),
                            positive(atom("e", "y", "z")),
                            comparison,
                        ),
                    ),
                ),
                catalog = catalogOf("e" to listOf("a", "b")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val join = withClue("the comparison must not sit above the join, where both x and y are bound") {
            root.input.shouldBeInstanceOf<Join>()
        }
        val leftSelect = withClue("the comparison belongs directly above the scan that binds x") {
            join.left.shouldBeInstanceOf<Select>()
        }
        leftSelect.condition shouldBe comparison
        leftSelect.input.shouldBeInstanceOf<Scan>().relation shouldBe "e"
        join.right.shouldBeInstanceOf<Scan>().relation shouldBe "e"

        withClue("exactly one Select carries the one comparison") {
            allNodes(root).filterIsInstance<Select>() shouldHaveSize 1
        }
    }

    // ---------------------------------------------------------------- [QRY1-PLAN-04]

    @Test
    fun `QRY1 §PLAN-04 a positive atom bound elsewhere and not reaching the head plans as SemiJoin`() {
        // q(x) :- r(x, y), s(y).   — feature example 3, citing [24-OP-SEMIJOIN-01].
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("s", "y"))),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a", "b"), "s" to listOf("a")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.outputColumns shouldContainExactly listOf("x")

        val semiJoin = withClue("y is bound by r and reaches no head column, so s is existential") {
            root.input.shouldBeInstanceOf<SemiJoin>()
        }
        semiJoin.keys shouldContainExactly listOf(JoinKey("y", "y"))
        semiJoin.input.shouldBeInstanceOf<Scan>().relation shouldBe "r"
        semiJoin.witness.shouldBeInstanceOf<Scan>().relation shouldBe "s"
        withClue("the witness's columns are not projected into the semijoin's output") {
            semiJoin.outputColumns shouldContainExactly listOf("x", "y")
        }
        withClue("an existential atom must not plan as a Join: ${allNodes(root)}") {
            allNodes(root).filterIsInstance<Join>().shouldBeEmpty()
        }
        semiJoin.provenance shouldBe setOf("r", "s")
    }

    @Test
    fun `QRY1 §PLAN-04 an atom whose variable reaches the head stays a Join`() {
        // The discriminating control for the test above: q(x, y) :- r(x, y), s(y). differs
        // only in that y now reaches the head, so s may not be reduced to a semijoin.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "y"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("s", "y"))),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a", "b"), "s" to listOf("a")),
            ),
        )

        val join = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        join.equiKeys shouldContainExactly listOf(JoinKey("y", "y"))
        allNodes(join).filterIsInstance<SemiJoin>().shouldBeEmpty()
    }

    // ---------------------------------------------------------------- negation, union, aggregate

    @Test
    fun `a negated atom plans as AntiJoin on its shared variables`() {
        // q(x) :- r(x, y), not s(y).
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(positive(atom("r", "x", "y")), Literal.Negated(atom("s", "y"))),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a", "b"), "s" to listOf("a")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val antiJoin = root.input.shouldBeInstanceOf<AntiJoin>()
        antiJoin.keys shouldContainExactly listOf(JoinKey("y", "y"))
        antiJoin.input.shouldBeInstanceOf<Scan>().relation shouldBe "r"
        antiJoin.witness.shouldBeInstanceOf<Scan>().relation shouldBe "s"
        antiJoin.provenance shouldBe setOf("r", "s")
    }

    @Test
    fun `a two-rule IDB predicate composes as Union feeding its consumer`() {
        // p(x) :- a(x).   p(x) :- b(x).   q(x) :- p(x), t(x).
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("p", "x"), body = listOf(positive(atom("a", "x")))),
                    rule(head = atom("p", "x"), body = listOf(positive(atom("b", "x")))),
                    rule(
                        head = atom("q", "x"),
                        body = listOf(positive(atom("p", "x")), positive(atom("t", "x"))),
                    ),
                ),
                catalog = catalogOf("a" to listOf("v"), "b" to listOf("v"), "t" to listOf("v")),
            ),
        )

        withClue("each head predicate gets its own root") {
            plan.roots.keys.toList() shouldContainExactly listOf("p", "q")
        }

        val pRoot = plan.roots.getValue("p").shouldBeInstanceOf<Union>()
        pRoot.inputs.map { it.shouldBeInstanceOf<Scan>().relation } shouldContainExactly listOf("a", "b")
        pRoot.provenance shouldBe setOf("a", "b")

        val consumer = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        val union = withClue("the consumer's input is the union of p's two rules") {
            consumer.left.shouldBeInstanceOf<Union>()
        }
        union.inputs shouldHaveSize 2
        union.inputs.map { it.shouldBeInstanceOf<Scan>().relation } shouldContainExactly listOf("a", "b")
        consumer.equiKeys shouldContainExactly listOf(JoinKey("x", "x"))
        consumer.right.shouldBeInstanceOf<Scan>().relation shouldBe "t"
        consumer.provenance shouldBe setOf("a", "b", "t")
    }

    @Test
    fun `an aggregate-annotated head plans as GroupAggregate over the body plan`() {
        // q(x, n) :- r(x, n).   with a count annotation on the head.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    Rule(
                        head = atom("q", "x", "n"),
                        body = listOf(positive(atom("r", "x", "n"))),
                        aggregate = Aggregate(AggregateKind.COUNT),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a", "b")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<GroupAggregate>()
        root.groupByColumns shouldContainExactly listOf("x")
        root.aggregatedColumn shouldBe "n"
        root.outputColumn shouldBe "n"
        root.aggregate shouldBe Aggregate(AggregateKind.COUNT)
        root.outputColumns shouldContainExactly listOf("x", "n")
        root.input.shouldBeInstanceOf<Scan>().relation shouldBe "r"
    }

    // ---------------------------------------------------------------- construction hygiene

    @Test
    fun `a constant argument plans as a Select above the scan, not as a join condition`() {
        // q(x) :- r(x, 7).
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(
                            Literal.Positive(
                                Atom("r", listOf(Term.Var("x"), Term.Const(7, AttrType.INT))),
                            ),
                        ),
                    ),
                ),
                catalog = catalogOf("r" to listOf("a", "b")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.outputColumns shouldContainExactly listOf("x")
        val selection = root.input.shouldBeInstanceOf<Select>()
        selection.condition.op shouldBe ComparisonOp.EQ
        selection.condition.right shouldBe Term.Const(7, AttrType.INT)
        selection.input.shouldBeInstanceOf<Scan>().relation shouldBe "r"
    }

    @Test
    fun `key-preservation slots are constructed, conservatively, from the catalog row key`() {
        // Construction-level only: the full decision procedure ([QRY1-PLAN-06]) belongs to
        // this feature's successor analyses task, which amends these values. What this pins
        // is that the planner populates the slots from the Catalog instead of defaulting
        // them, and that a projection dropping the witnessing key column withdraws the claim.
        val catalog = Catalog(
            mapOf(
                "r" to RelationSchema(
                    attributes = listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT)),
                    rowKey = setOf("a"),
                ),
            ),
        )
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("q", "y"), body = listOf(positive(atom("r", "x", "y")))),
                ),
                catalog = catalog,
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val scan = root.input.shouldBeInstanceOf<Scan>()
        withClue("r's declared row key {a} lands on the scan's first column, named x here") {
            scan.keyPreserving shouldBe true
            scan.preservedKey shouldBe setOf("x")
        }
        withClue("projecting x away drops the witness, so the claim is withdrawn") {
            root.keyPreserving shouldBe false
            root.preservedKey.shouldBeNull()
        }
    }

    @Test
    fun `planning the same query twice yields equal plans`() {
        val query = Query(
            rules = listOf(
                rule(
                    head = atom("q", "x", "z"),
                    body = listOf(positive(atom("e", "x", "y")), positive(atom("e", "y", "z"))),
                ),
            ),
            catalog = catalogOf("e" to listOf("a", "b")),
        )
        Planner.plan(query) shouldBe Planner.plan(query)
    }

    // ---------------------------------------------------------------- fixtures

    private fun atom(predicate: String, vararg variables: String): Atom =
        Atom(predicate, variables.map { Term.Var(it) })

    private fun positive(atom: Atom): Literal.Positive = Literal.Positive(atom)

    private fun rule(head: Atom, body: List<Literal>): Rule = Rule(head, body)

    private fun catalogOf(vararg relations: Pair<String, List<String>>): Catalog =
        Catalog(
            relations.associate { (name, attributes) ->
                name to RelationSchema(attributes.map { Attribute(it, AttrType.INT) })
            },
        )

    /** Every node of the plan rooted at [node], root first. */
    private fun allNodes(node: PlanNode): List<PlanNode> = listOf(node) + when (node) {
        is Scan -> emptyList()
        is Select -> allNodes(node.input)
        is Project -> allNodes(node.input)
        is Join -> allNodes(node.left) + allNodes(node.right)
        is SemiJoin -> allNodes(node.input) + allNodes(node.witness)
        is AntiJoin -> allNodes(node.input) + allNodes(node.witness)
        is Union -> node.inputs.flatMap { allNodes(it) }
        is Intersect -> allNodes(node.left) + allNodes(node.right)
        is Difference -> allNodes(node.left) + allNodes(node.right)
        is GroupAggregate -> allNodes(node.input)
        is OuterJoin -> allNodes(node.left) + allNodes(node.right)
    }
}
