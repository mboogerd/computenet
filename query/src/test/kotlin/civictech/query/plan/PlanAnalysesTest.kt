package civictech.query.plan

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The two per-node plan analyses of computenet-cab.3.3: provenance (`[QRY1-PLAN-05]`, the
 * BS-6 precondition) and key-preservation (`[QRY1-PLAN-06]`, the BS-7 precondition), as
 * [PlanAnalyses] states them and [Planner] records them.
 *
 * Sibling to `PlannerStructureTest`, which owns plan *shape*: nothing here asserts which node
 * kind sits where beyond what is needed to reach the node whose analysis is under test. The
 * per-node reasoning these assertions pin is written out in [PlanAnalyses]'s KDoc — a `false`
 * asserted here is asserted as a *decision* (a [Union] collapses equal tuples; a
 * [GroupAggregate] folds groups), not as a placeholder.
 *
 * Node kinds this planner has no surface syntax for — [Intersect], [Difference],
 * [OuterJoin] — are exercised against [PlanAnalyses] directly, since a planner-built plan
 * cannot reach them yet and their rules are read by the rejection and lowering features
 * regardless of how the node was built.
 */
class PlanAnalysesTest {

    // ---------------------------------------------------------------- [QRY1-PLAN-05]

    @Test
    fun `QRY1 §PLAN-05 a self-join reports provenance {e} at both join inputs and the join`() {
        // q(x, z) :- e(x, y), e(y, z).   — the BS-6 shared-source diamond the feature names.
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
        val join = root.input.shouldBeInstanceOf<Join>()
        withClue("both inputs descend from the same single EDB relation, and so does the join") {
            join.left.provenance shouldBe setOf("e")
            join.right.provenance shouldBe setOf("e")
            join.provenance shouldBe setOf("e")
            root.provenance shouldBe setOf("e")
        }
    }

    @Test
    fun `QRY1 §PLAN-05 an IDB predicate's provenance is the union over its rules (cab_3-D2)`() {
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

        withClue("p is defined by two rules over distinct EDB relations") {
            plan.roots.getValue("p").provenance shouldBe setOf("a", "b")
        }
        val consumer = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        withClue("the consumer sees p's union, not one branch of it") {
            consumer.left.provenance shouldBe setOf("a", "b")
            consumer.provenance shouldBe setOf("a", "b", "t")
        }
    }

    @Test
    fun `QRY1 §PLAN-05 a negated atom's witness relation is part of the antijoin's provenance`() {
        // q(x) :- r(x), !s(x).   Removing a row is an observable effect of s, so s is a source
        // the antijoin descends from — see PlanAnalyses' KDoc.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(positive(atom("r", "x")), Literal.Negated(atom("s", "x"))),
                    ),
                ),
                catalog = catalogOf("r" to listOf("v"), "s" to listOf("v")),
            ),
        )

        plan.roots.getValue("q").shouldBeInstanceOf<AntiJoin>().provenance shouldBe setOf("r", "s")
    }

    // ---------------------------------------------------------------- [QRY1-PLAN-06]

    @Test
    fun `QRY1 §PLAN-06 a projection carrying the declared row key is key-preserving with that witness`() {
        // r(a INT, b STRING) keyed on {a}; q(a) :- r(a, b).   — feature example 4, first half.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("q", "x"), body = listOf(positive(atom("r", "x", "y")))),
                ),
                catalog = keyedCatalog(),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.outputColumns shouldContainExactly listOf("x")
        withClue("the key attribute a sits at the projected column x, so the key survives") {
            root.keyPreserving shouldBe true
            root.preservedKey shouldBe setOf("x")
        }
    }

    @Test
    fun `QRY1 §PLAN-06 a projection dropping the declared row key is not key-preserving`() {
        // q(b) :- r(a, b).   — feature example 4, second half, the BS-7 rejection precondition.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("q", "y"), body = listOf(positive(atom("r", "x", "y")))),
                ),
                catalog = keyedCatalog(),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        withClue("x carried r's key {a} and was projected away, so the claim is withdrawn") {
            root.keyPreserving shouldBe false
            root.preservedKey.shouldBeNull()
        }
        withClue("the scan below still names the key, so a rejection can say which key was lost") {
            val scan = root.input.shouldBeInstanceOf<Scan>()
            scan.keyPreserving shouldBe true
            scan.preservedKey shouldBe setOf("x")
        }
    }

    @Test
    fun `QRY1 §PLAN-06 a relation with no declared row key plans without error and is not key-preserving`() {
        // `[QRY1-API-05]`: rowKey == null is a valid catalog entry, not an error.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("q", "x"), body = listOf(positive(atom("u", "x", "y")))),
                ),
                catalog = catalogOf("u" to listOf("a", "b")),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val scan = root.input.shouldBeInstanceOf<Scan>()
        withClue("no declared key means nothing to preserve — no witness, and no crash") {
            scan.keyPreserving shouldBe false
            scan.preservedKey.shouldBeNull()
            root.keyPreserving shouldBe false
            root.preservedKey.shouldBeNull()
        }
    }

    @Test
    fun `a row filter preserves key injectivity`() {
        // q(x) :- r(x, y), !s(x).   Select/SemiJoin/AntiJoin remove rows and merge none.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x"),
                        body = listOf(
                            positive(atom("r", "x", "y")),
                            Literal.Negated(atom("s", "x")),
                        ),
                    ),
                ),
                catalog = Catalog(keyedCatalog().relations + catalogOf("s" to listOf("v")).relations),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        val antiJoin = root.input.shouldBeInstanceOf<AntiJoin>()
        withClue("the antijoin keeps its input's witness {x}, and the projection keeps it too") {
            antiJoin.keyPreserving shouldBe true
            antiJoin.preservedKey shouldBe setOf("x")
            root.keyPreserving shouldBe true
            root.preservedKey shouldBe setOf("x")
        }
    }

    @Test
    fun `a join of two keyed inputs is key-preserving on the union of both witnesses`() {
        // q(x, y, z, w) :- r(x, y), s(z, w).   Each output row is the pair of input rows that
        // produced it, so (K_left, K_right) determines it.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "y", "z", "w"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("s", "z", "w"))),
                    ),
                ),
                catalog = twoKeyedCatalog(),
            ),
        )

        val join = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        withClue("r is keyed on its first attribute (column x), s on its first (column z)") {
            join.keyPreserving shouldBe true
            join.preservedKey shouldBe setOf("x", "z")
        }
    }

    @Test
    fun `a join loses the claim when either side has none`() {
        // q(x, y, z, w) :- r(x, y), u(z, w).   u has no declared row key, so the pair is unnamed.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "x", "y", "z", "w"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("u", "z", "w"))),
                    ),
                ),
                catalog = Catalog(keyedCatalog().relations + catalogOf("u" to listOf("a", "b")).relations),
            ),
        )

        val join = plan.roots.getValue("q").shouldBeInstanceOf<Join>()
        join.keyPreserving shouldBe false
        join.preservedKey.shouldBeNull()
    }

    @Test
    fun `a projection above a join that drops one side's witness withdraws the claim`() {
        // q(y, z, w) :- r(x, y), s(z, w).   x carries r's key and does not reach the head.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(
                        head = atom("q", "y", "z", "w"),
                        body = listOf(positive(atom("r", "x", "y")), positive(atom("s", "z", "w"))),
                    ),
                ),
                catalog = twoKeyedCatalog(),
            ),
        )

        val root = plan.roots.getValue("q").shouldBeInstanceOf<Project>()
        root.keyPreserving shouldBe false
        root.preservedKey.shouldBeNull()
        withClue("half a pair key is not a key: the join below still holds the full witness") {
            root.input.shouldBeInstanceOf<Join>().preservedKey shouldBe setOf("x", "z")
        }
    }

    @Test
    fun `a union is never key-preserving, even when every branch is`() {
        // p(x) :- r(x, y).   p(x) :- s(x, w).   UNION DISTINCT merges tuples equal across
        // branches — a genuine collapse, not an unestablished claim.
        val plan = Planner.plan(
            Query(
                rules = listOf(
                    rule(head = atom("p", "x"), body = listOf(positive(atom("r", "x", "y")))),
                    rule(head = atom("p", "x"), body = listOf(positive(atom("s", "x", "w")))),
                ),
                catalog = twoKeyedCatalog(),
            ),
        )

        val union = plan.roots.getValue("p").shouldBeInstanceOf<Union>()
        withClue("each branch is key-preserving on its own") {
            union.inputs.forEach { it.keyPreserving shouldBe true }
        }
        union.keyPreserving shouldBe false
        union.preservedKey.shouldBeNull()
    }

    @Test
    fun `a group aggregate keeps the claim only when the key survives into the grouping columns`() {
        // q(x, n) :- r(x, n). with a count annotation: groups by x, which carries r's key {a},
        // so every group holds at most one input row.
        val grouped = Planner.plan(
            Query(
                rules = listOf(
                    Rule(
                        head = atom("q", "x", "y"),
                        body = listOf(positive(atom("r", "x", "y"))),
                        aggregate = Aggregate(AggregateKind.COUNT),
                    ),
                ),
                catalog = keyedCatalog(),
            ),
        ).roots.getValue("q").shouldBeInstanceOf<GroupAggregate>()

        grouped.groupByColumns shouldContainExactly listOf("x")
        withClue("the key column x is a grouping column, so nothing is folded together") {
            grouped.keyPreserving shouldBe true
            grouped.preservedKey shouldBe setOf("x")
        }

        // The mirror: grouping by the non-key column folds many keyed rows into one.
        val folded = Planner.plan(
            Query(
                rules = listOf(
                    Rule(
                        head = atom("q", "y", "x"),
                        body = listOf(positive(atom("r", "x", "y"))),
                        aggregate = Aggregate(AggregateKind.COUNT),
                    ),
                ),
                catalog = keyedCatalog(),
            ),
        ).roots.getValue("q").shouldBeInstanceOf<GroupAggregate>()

        folded.groupByColumns shouldContainExactly listOf("y")
        withClue("x is aggregated over, not grouped by: the key is folded away") {
            folded.keyPreserving shouldBe false
            folded.preservedKey.shouldBeNull()
        }
    }

    // ------------------------------------------- node kinds the planner has no surface for

    @Test
    fun `intersect and difference propagate a subset-of-tuples witness`() {
        val left = keyedScan("r", listOf("x", "y"), setOf("x"))
        val right = keyedScan("s", listOf("x", "y"), setOf("x"))
        val unkeyed = unkeyedScan("u", listOf("x", "y"))
        val columns = listOf("x", "y")

        withClue("an intersection's tuples are a subset of both sides', so either witness holds") {
            PlanAnalyses.intersectKey(left, right, columns) shouldBe KeyAnalysis(true, setOf("x"))
            PlanAnalyses.intersectKey(unkeyed, right, columns) shouldBe KeyAnalysis(true, setOf("x"))
            PlanAnalyses.intersectKey(unkeyed, unkeyed, columns) shouldBe PlanAnalyses.NO_KEY
        }
        withClue("a difference carries only the left side's tuples, so only its witness") {
            PlanAnalyses.differenceKey(left, columns) shouldBe KeyAnalysis(true, setOf("x"))
            PlanAnalyses.differenceKey(unkeyed, columns) shouldBe PlanAnalyses.NO_KEY
        }
    }

    @Test
    fun `an outer join takes the same paired witness as an inner join`() {
        // Null-padding adds rows and merges none: (K_left, null) collides with no other row.
        val left = keyedScan("r", listOf("x", "y"), setOf("x"))
        val right = keyedScan("s", listOf("z", "w"), setOf("z"))
        val unkeyed = unkeyedScan("u", listOf("z", "w"))
        val columns = listOf("x", "y", "z", "w")

        PlanAnalyses.outerJoinKey(left, right, columns) shouldBe KeyAnalysis(true, setOf("x", "z"))
        PlanAnalyses.outerJoinKey(left, unkeyed, columns) shouldBe PlanAnalyses.NO_KEY
        withClue("a witness column missing from the output cannot witness anything") {
            PlanAnalyses.outerJoinKey(left, right, listOf("y", "z", "w")) shouldBe PlanAnalyses.NO_KEY
        }
    }

    @Test
    fun `a scan maps the declared row key onto the column names of its positions`() {
        // The positional translation, isolated: the key is named in attribute terms and comes
        // back in the node's own column terms.
        PlanAnalyses.scanKey(
            rowKey = setOf("b"),
            attributeNames = listOf("a", "b"),
            columns = listOf("x", "y"),
        ) shouldBe KeyAnalysis(true, setOf("y"))

        PlanAnalyses.scanKey(
            rowKey = setOf("a", "b"),
            attributeNames = listOf("a", "b"),
            columns = listOf("x", "y"),
        ) shouldBe KeyAnalysis(true, setOf("x", "y"))

        PlanAnalyses.scanKey(
            rowKey = null,
            attributeNames = listOf("a", "b"),
            columns = listOf("x", "y"),
        ) shouldBe PlanAnalyses.NO_KEY
    }

    // ---------------------------------------------------------------- fixtures

    private fun atom(predicate: String, vararg variables: String): Atom =
        Atom(predicate, variables.map { Term.Var(it) })

    private fun positive(atom: Atom): Literal.Positive = Literal.Positive(atom)

    private fun rule(head: Atom, body: List<Literal>): Rule = Rule(head, body)

    /** Relations with no declared row key, each attribute an INT. */
    private fun catalogOf(vararg relations: Pair<String, List<String>>): Catalog =
        Catalog(
            relations.associate { (name, attributes) ->
                name to RelationSchema(attributes.map { Attribute(it, AttrType.INT) })
            },
        )

    /** `r(a INT, b STRING)` keyed on `{a}` — the feature's example-4 catalog. */
    private fun keyedCatalog(): Catalog =
        Catalog(
            mapOf(
                "r" to RelationSchema(
                    attributes = listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.STRING)),
                    rowKey = setOf("a"),
                ),
            ),
        )

    /** [keyedCatalog] plus `s(c INT, d STRING)` keyed on `{c}`. */
    private fun twoKeyedCatalog(): Catalog =
        Catalog(
            keyedCatalog().relations + mapOf(
                "s" to RelationSchema(
                    attributes = listOf(Attribute("c", AttrType.INT), Attribute("d", AttrType.STRING)),
                    rowKey = setOf("c"),
                ),
            ),
        )

    private fun keyedScan(relation: String, columns: List<String>, key: Set<String>): Scan =
        Scan(
            relation = relation,
            outputColumns = columns,
            provenance = setOf(relation),
            keyPreserving = true,
            preservedKey = key,
        )

    private fun unkeyedScan(relation: String, columns: List<String>): Scan =
        Scan(
            relation = relation,
            outputColumns = columns,
            provenance = setOf(relation),
            keyPreserving = false,
            preservedKey = null,
        )
}
