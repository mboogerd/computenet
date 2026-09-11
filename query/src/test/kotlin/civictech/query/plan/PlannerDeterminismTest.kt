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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

/**
 * Determinism evidence for [Planner] (`[QRY1-PLAN-01]`, cab.3-D1, cab.3-D3's in-JVM half).
 *
 * The two-JVM half of "identical across machines" is BS-15, owned by the demo feature
 * computenet-cab.7 — nothing here builds a two-process rig, per this task's non-goal.
 *
 * **Why two builds that plan the same rule set from differently-ordered insertion actually
 * discriminates a hash-order bug**, rather than proving nothing (the failure mode this test
 * exists to avoid: planning the *same* [Query] object twice, which `PlannerStructureTest`'s
 * `` `planning the same query twice yields equal plans` `` already covers and which cannot
 * catch a hash-order dependency, since both runs would see the identical concrete
 * collections):
 * - [seedA] and [seedB] build [Query]s that are `equals`-equal — same rules, same catalog
 *   content — from **genuinely different concrete collections**: [seedA]'s `Catalog.relations`
 *   map is built by inserting `edge`, `weight`, `blocked` in that declaration order; [seedB]'s
 *   is the exact reverse insertion order. [catalogsDifferInIterationOrder] asserts the two
 *   maps' key *iteration* order actually differs (not just that the code intends it to) before
 *   either is planned — the mechanism does no good if the two builds happen to coincide.
 * - Likewise the flat `rules` list: [seedA] lists the two `path` rules before the `summary`
 *   rule that consumes `path`; [seedB] lists `summary` first. The two `path` rules keep their
 *   own relative order in both builds (`path` rule 1 before `path` rule 2) — reordering *that*
 *   would change which branch is first in `path`'s [Union] and make the two plans genuinely
 *   unequal for a reason that has nothing to do with hash order, which would make the test
 *   assert something false. Only the position of the *unrelated* `summary` rule block moves.
 * - A fixed reversal/rotation, not a `Random`-seeded shuffle, is used deliberately: with only
 *   three catalog entries and two rule blocks a seeded shuffle has a real chance of coincidence
 *   (silently reproducing the same order and making the test vacuous), where a full reversal
 *   is guaranteed different by construction and needs no retry logic to prove it.
 *
 * If [Planner] or [PlanAnalyses] ever iterated a `HashMap`/`HashSet` in the *catalog's* or the
 * *rules-by-head map's* own insertion order — the one place this planning path holds a
 * `Map`/`Set` built from caller-supplied insertion order rather than from a
 * `.sorted()`-derived `LinkedHashSet` (see the audit in [Planner]'s KDoc) — planning [seedA]
 * and [seedB] would diverge and this test would fail.
 */
class PlannerDeterminismTest {

    // ---------------------------------------------------------------- fixtures: two orders

    private val edgeSchema = RelationSchema(listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT)))
    private val weightSchema = RelationSchema(
        listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT), Attribute("c", AttrType.INT)),
    )
    private val blockedSchema = RelationSchema(listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT)))

    /** Insertion order A: declaration order. */
    private fun catalogSeedA(): Catalog {
        val map = LinkedHashMap<String, RelationSchema>()
        map["edge"] = edgeSchema
        map["weight"] = weightSchema
        map["blocked"] = blockedSchema
        return Catalog(map)
    }

    /** Insertion order B: the exact reverse of [catalogSeedA] — guaranteed different, not just likely. */
    private fun catalogSeedB(): Catalog {
        val map = LinkedHashMap<String, RelationSchema>()
        map["blocked"] = blockedSchema
        map["weight"] = weightSchema
        map["edge"] = edgeSchema
        return Catalog(map)
    }

    // path(x, y) :- edge(x, y).
    private val pathRule1 = Rule(
        head = Atom("path", listOf(Term.Var("x"), Term.Var("y"))),
        body = listOf(Literal.Positive(Atom("edge", listOf(Term.Var("x"), Term.Var("y"))))),
    )

    // path(x, y) :- edge(x, z), edge(z, y).   -- a second, non-recursive rule for the same head.
    private val pathRule2 = Rule(
        head = Atom("path", listOf(Term.Var("x"), Term.Var("y"))),
        body = listOf(
            Literal.Positive(Atom("edge", listOf(Term.Var("x"), Term.Var("z")))),
            Literal.Positive(Atom("edge", listOf(Term.Var("z"), Term.Var("y")))),
        ),
    )

    // summary(x, n) :- path(x, y), weight(x, y, n), n > 5, not blocked(x, y).   [SUM over n, group by x]
    private val summaryRule = Rule(
        head = Atom("summary", listOf(Term.Var("x"), Term.Var("n"))),
        body = listOf(
            Literal.Positive(Atom("path", listOf(Term.Var("x"), Term.Var("y")))),
            Literal.Positive(Atom("weight", listOf(Term.Var("x"), Term.Var("y"), Term.Var("n")))),
            Literal.Comparison(Term.Var("n"), ComparisonOp.GT, Term.Const(5, AttrType.INT)),
            Literal.Negated(Atom("blocked", listOf(Term.Var("x"), Term.Var("y")))),
        ),
        aggregate = Aggregate(AggregateKind.SUM),
    )

    /** Rule-list order A: both `path` rules, then `summary`. */
    private fun querySeedA(): Query = Query(rules = listOf(pathRule1, pathRule2, summaryRule), catalog = catalogSeedA())

    /**
     * Rule-list order B: `summary` first, then the two `path` rules — a different flat
     * insertion order, but `path`'s own two rules keep their relative order (rule 1 before
     * rule 2), so `path`'s [Union] branches stay in the same order in both builds.
     */
    private fun querySeedB(): Query = Query(rules = listOf(summaryRule, pathRule1, pathRule2), catalog = catalogSeedB())

    // ---------------------------------------------------------------- the mechanism itself

    @Test
    fun `the two seeds genuinely differ in insertion order, not just in which Query object they are`() {
        withClue("a catalog map iterated in the same order as its reverse would make the byte/equality test vacuous") {
            catalogSeedA().relations.keys.toList() shouldNotBe catalogSeedB().relations.keys.toList()
        }
        withClue("a rules list identical in order would make the byte/equality test vacuous") {
            querySeedA().rules shouldNotBe querySeedB().rules
        }
        withClue("but the two Querys must still be equal in content — same rules (as sets), same catalog") {
            querySeedA().rules.toSet() shouldBe querySeedB().rules.toSet()
            querySeedA().catalog shouldBe querySeedB().catalog
        }
    }

    // ---------------------------------------------------------------- [QRY1-PLAN-01] / cab.3-D1

    @Test
    fun `QRY1 §PLAN-01 planning from two differently-seeded insertion orders yields equals-equal plans`() {
        val planA = Planner.plan(querySeedA())
        val planB = Planner.plan(querySeedB())

        withClue("the query is well-formed and exercises joins, comparisons, negation, an " +
            "aggregate head and a multi-rule IDB predicate ('path') in one query") {
            planA.roots.keys shouldBe setOf("path", "summary")
        }
        planA shouldBe planB
    }

    // ---------------------------------------------------------------- cab.3-D3 (in-JVM half)

    @Test
    fun `cab3-D3 the two plans' ObjectOutputStream byte arrays are identical`() {
        val planA = Planner.plan(querySeedA())
        val planB = Planner.plan(querySeedB())

        val bytesA = serialize(planA)
        val bytesB = serialize(planB)

        withClue("byte-for-byte identity is the in-JVM half of cab.3-D3; the two-JVM half is " +
            "BS-15, owned by computenet-cab.7 and not asserted here") {
            bytesA shouldBe bytesB
        }
    }

    private fun serialize(plan: LogicalPlan): ByteArray =
        ByteArrayOutputStream().apply { ObjectOutputStream(this).use { it.writeObject(plan) } }.toByteArray()
}
