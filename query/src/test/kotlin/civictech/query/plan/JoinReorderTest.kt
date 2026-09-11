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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Structural reordering guard for [Planner] (`[QRY1-PLAN-07]`, computenet-cab.3.4).
 *
 * [Planner] does **no cost-based join reordering** — established by reading
 * [PlanningContext.planRule] and recorded in [Planner]'s own KDoc and [PlanOrder]'s. The
 * guard this suite pins is therefore the KDoc-prescribed stand-in: the join tree over a
 * rule's core atoms is *exactly* the canonical left-deep assoc/comm form of the rule's own
 * textual (body) order, and whatever reordering-adjacent machinery exists (semijoin/antijoin
 * extraction, aggregation) never crosses into a different rewrite class — no cross product
 * where a shared variable was available, no dropped or duplicated input, no atom folded
 * across an [AntiJoin] or [GroupAggregate] boundary.
 */
class JoinReorderTest {

    // ---------------------------------------------------------------- canonical form

    @Test
    fun `the join tree over N atoms is exactly the left-deep textual-order tree, no reordering`() {
        // q(a, b, c, d) :- r1(a, b), r2(b, c), r3(c, d), r4(d, a).   four atoms, left-deep
        // chain. Every variable reaches the head so no atom is eligible for semijoin
        // extraction (`[QRY1-PLAN-04]`) — this test is about join ORDER, not that other,
        // separately-tested rewrite class, so it deliberately keeps all four atoms in the
        // join core.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "a", "b", "c", "d"),
                        body = listOf(
                            positive(atom("r1", "a", "b")),
                            positive(atom("r2", "b", "c")),
                            positive(atom("r3", "c", "d")),
                            positive(atom("r4", "d", "a")),
                        ),
                    ),
                ),
                catalog = catalogOf("r1" to 2, "r2" to 2, "r3" to 2, "r4" to 2),
            ),
        )

        val leaves = PlanOrder.leftDeepJoinLeaves(joinSpineRoot(plan.roots.getValue("q")))
        withClue("leaves, left to right, must be r1, r2, r3, r4 in exactly the rule's own body order") {
            leaves.map { it.shouldBeInstanceOf<Scan>().relation } shouldContainExactly listOf("r1", "r2", "r3", "r4")
        }
    }

    @Test
    fun `reordering a rule's body reorders the resulting join tree the same way, textually`() {
        // The discriminating control: the SAME atoms in a DIFFERENT body order must produce a
        // DIFFERENT left-deep leaf order — if the planner did any of its own reordering
        // (e.g. sorting atoms by relation name), this test would fail instead of the one above.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "a", "b", "c", "d"),
                        body = listOf(
                            positive(atom("r4", "d", "a")),
                            positive(atom("r2", "b", "c")),
                            positive(atom("r3", "c", "d")),
                            positive(atom("r1", "a", "b")),
                        ),
                    ),
                ),
                catalog = catalogOf("r1" to 2, "r2" to 2, "r3" to 2, "r4" to 2),
            ),
        )

        val leaves = PlanOrder.leftDeepJoinLeaves(joinSpineRoot(plan.roots.getValue("q")))
        leaves.map { it.shouldBeInstanceOf<Scan>().relation } shouldContainExactly listOf("r4", "r2", "r3", "r1")
    }

    // ---------------------------------------------------------------- never Join -> cross product

    @Test
    fun `a shared-variable body never crosses into a cross product anywhere in the tree`() {
        // q(a, b, c, d) :- r1(a, b), r2(b, c), r3(c, d).   every adjacent pair shares a
        // variable. All four variables reach the head so none of the three atoms is eligible
        // for semijoin extraction — otherwise peeling off the middle atom (r2) could
        // legitimately leave the outer two (r1, r3) with nothing shared, which is a correct
        // cross product and not the violation this test is checking for.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "a", "b", "c", "d"),
                        body = listOf(
                            positive(atom("r1", "a", "b")),
                            positive(atom("r2", "b", "c")),
                            positive(atom("r3", "c", "d")),
                        ),
                    ),
                ),
                catalog = catalogOf("r1" to 2, "r2" to 2, "r3" to 2),
            ),
        )

        val nodes = PlanOrder.allNodes(plan.roots.getValue("q"))
        withClue("no Join in this tree may have an empty equiKeys list: $nodes") {
            nodes.filterIsInstance<Join>().none { it.equiKeys.isEmpty() } shouldBe true
        }
    }

    // ---------------------------------------------------------------- never drop or duplicate an input

    @Test
    fun `a self-join over the same relation keeps both atoms as distinct leaves, none dropped or duplicated`() {
        // q(x, z) :- e(x, y), e(y, z).   two distinct atoms over the same relation name.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "z"),
                        body = listOf(positive(atom("e", "x", "y")), positive(atom("e", "y", "z"))),
                    ),
                ),
                catalog = catalogOf("e" to 2),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val leaves = PlanOrder.leftDeepJoinLeaves(root.input)
        withClue("both atoms of the self-join must survive as two leaves, neither dropped nor tripled") {
            leaves shouldHaveSize 2
            leaves.map { it.shouldBeInstanceOf<Scan>().outputColumns } shouldContainExactly
                listOf(listOf("x", "y"), listOf("y", "z"))
        }
    }

    // ---------------------------------------------------------------- never cross an AntiJoin boundary

    @Test
    fun `a negated atom sits strictly above the join spine, never folded into it`() {
        // q(x) :- r(x, y), s(y, z), not t(x).
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(
                            positive(atom("r", "x", "y")),
                            positive(atom("s", "y", "z")),
                            Literal.Negated(atom("t", "x")),
                        ),
                    ),
                ),
                catalog = catalogOf("r" to 2, "s" to 2, "t" to 1),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val antiJoin = root.input.shouldBeInstanceOf<AntiJoin>()
        withClue("t must never appear inside the join spine below the antijoin") {
            antiJoin.witness.shouldBeInstanceOf<Scan>().relation shouldBe "t"
            val spineRelations = PlanOrder.leftDeepJoinLeaves(antiJoin.input)
                .map { it.shouldBeInstanceOf<Scan>().relation }
            spineRelations shouldContainExactly listOf("r", "s")
        }
        withClue("the whole tree has exactly one Join (r, s) and no second one hiding t") {
            PlanOrder.allNodes(root).filterIsInstance<Join>() shouldHaveSize 1
        }
    }

    // ---------------------------------------------------------------- never cross a GroupAggregate boundary

    @Test
    fun `an aggregate head's grouping never absorbs an atom into the join spine below it`() {
        // q(x, n) :- r(x, y), s(y, n).   aggregate SUM(n) grouped by x.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    Rule(
                        head = atom("q", "x", "n"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("s", "y", "n"))),
                        aggregate = Aggregate(AggregateKind.SUM),
                    ),
                ),
                catalog = catalogOf("r" to 2, "s" to 2),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<GroupAggregate>()
        withClue("the join spine below the GroupAggregate is exactly r, s in body order — " +
            "the aggregate boundary sits strictly above it") {
            val spineRelations = PlanOrder.leftDeepJoinLeaves(root.input)
                .map { it.shouldBeInstanceOf<Scan>().relation }
            spineRelations shouldContainExactly listOf("r", "s")
        }
        withClue("exactly one Join exists in the whole tree, below the GroupAggregate") {
            PlanOrder.allNodes(root).filterIsInstance<Join>() shouldHaveSize 1
            PlanOrder.allNodes(root).filterIsInstance<GroupAggregate>() shouldHaveSize 1
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Unwraps the head [Project] when the planner added one (its output-column order differs
     * from the join spine's own accumulated order) — identity [Project]s are skipped by the
     * planner itself, so when the two orders coincide [node] already **is** the join spine.
     */
    private fun joinSpineRoot(node: PlanNode): PlanNode = if (node is Project) node.input else node

    private fun atom(predicate: String, vararg variables: String): Atom =
        Atom(predicate, variables.map { Term.Var(it) })

    private fun positive(atom: Atom): Literal.Positive = Literal.Positive(atom)

    private fun rule(head: Atom, body: List<Literal>): Rule = Rule(head, body)

    private fun catalogOf(vararg relations: Pair<String, Int>): Catalog =
        Catalog(
            relations.associate { (name, arity) ->
                name to RelationSchema((0 until arity).map { Attribute("f$it", AttrType.INT) })
            },
        )
}
