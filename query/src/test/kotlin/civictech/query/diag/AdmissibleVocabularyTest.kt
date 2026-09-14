package civictech.query.diag

import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.graph.SpawnStep
import civictech.query.architecture.HierarchyCompleteness
import civictech.query.ast.AggregateKind
import civictech.query.ast.Aggregate
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.lower.AggregateSpec
import civictech.query.lower.CountFactory
import civictech.query.lower.FilterFactory
import civictech.query.lower.FlatMapFactory
import civictech.query.lower.GroupByFactory
import civictech.query.lower.IntersectFactory
import civictech.query.lower.JoinFactory
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.lower.PadFactory
import civictech.query.lower.SemiJoinFactory
import civictech.query.lower.SetSourceFactory
import civictech.query.lower.UnionFactory
import civictech.query.plan.Planner
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Pins every unexpressibility cab.5-D5 relies on to justify NOT adding [RejectionCode.NON_TOTAL_ORDER][RejectionCode],
 * `MULTIWRITER_NONCONVERGENT`, `WINDOW_CLOSE_UNSUPPORTED`, `GLOBAL_ORDER_UNSUPPORTED` or
 * `EXCLUSIVE_PAYLOAD` — see `RejectionCode`'s header KDoc. Each test below is a tripwire, not a
 * feature test: **when a test in this file fails, the antecedent it pins has become
 * constructible, and the failing test's KDoc names the `RejectionCode` variant (with a producer
 * in `RejectionCoverage`) that the failure obliges someone to add.** None of these codes exists
 * yet, so today every test here passes vacuously — that is the point: [QRY1-REJECT-05]/cab.1-D1
 * forbid a variant with no producing test, and a surface construct added only to refuse it is
 * outside `[QRY1-LANG-01]`'s closed grammar (epic §4.1).
 *
 * Also pins [QRY1-SEM-01]/[QRY1-SEM-08] (set semantics, no compiler-added deletion machinery)
 * for a representative multi-relation, aggregated compile.
 */
class AdmissibleVocabularyTest {

    // ---------------------------------------------------------------- NON_TOTAL_ORDER

    /**
     * When this fails, add `NON_TOTAL_ORDER` with a producer in `RejectionCoverage`
     * ([QRY1-SEM-06]). `min`/`max`/`topK` need a tie-break only when a selector value can be
     * incomparable or tie-ambiguous in a way the kernel cannot resolve; every [AttrType]'s
     * runtime type is a totally-ordered `Comparable` (`Double.compareTo` is total: NaN
     * greatest, `-0.0 < 0.0`), so no such value exists.
     */
    @Test
    fun `NON_TOTAL_ORDER has no antecedent - every AttrType runtime type is a totally-ordered Comparable`() {
        // .javaObjectType, not .java: a primitive's .java (Int::class.java == int.class) implements
        // no interface at all, which would make every primitive-backed AttrType a false positive.
        val nonComparable = AttrType.entries.filterNot { Comparable::class.java.isAssignableFrom(it.runtimeType.javaObjectType) }
        withClue("AttrType runtime types with no total order: $nonComparable") {
            nonComparable.shouldBeEmpty()
        }
    }

    /**
     * When this fails, add `NON_TOTAL_ORDER` with a producer in `RejectionCoverage`
     * ([QRY1-SEM-06]). `AggregateSpec`'s only extremum-shaped variants are `Min`/`Max`/`TopK`,
     * each already typed against the column's totally-ordered [AttrType]; a new sealed
     * subclass here is the surface a tie-ambiguous extremum would need before it could be
     * refused.
     */
    @Test
    fun `NON_TOTAL_ORDER has no antecedent - AggregateSpec's permitted subclasses are exactly the known set`() {
        val known = listOf(
            AggregateSpec.Count::class.java,
            AggregateSpec.Sum::class.java,
            AggregateSpec.Avg::class.java,
            AggregateSpec.Min::class.java,
            AggregateSpec.Max::class.java,
            AggregateSpec.TopK::class.java,
            AggregateSpec.CollectToSet::class.java,
        )
        withClue("AggregateSpec grew a subclass beyond the known extremum-free set") {
            HierarchyCompleteness.missingFrom(AggregateSpec::class.java, known).shouldBeEmpty()
        }
    }

    // ------------------------------------------ WINDOW_CLOSE_UNSUPPORTED / GLOBAL_ORDER_UNSUPPORTED

    /**
     * When this fails, add `WINDOW_CLOSE_UNSUPPORTED` ([QRY1-REJECT-07]) and/or
     * `GLOBAL_ORDER_UNSUPPORTED` ([QRY1-REJECT-08]) with producers in `RejectionCoverage` — a
     * new [Literal] case is the surface a window or a global-order construct would need. §3.2's
     * `QuorumSetCell` stays outside the admissible vocabulary by the same closure
     * ([QRY1-HONEST-03], cab.4-D4): there is no window or ordering construct through which a
     * quorum could be planned.
     */
    @Test
    fun `WINDOW_CLOSE_UNSUPPORTED and GLOBAL_ORDER_UNSUPPORTED have no antecedent - Literal has no window or ordering case`() {
        val known = listOf(Literal.Positive::class.java, Literal.Negated::class.java, Literal.Comparison::class.java)
        withClue("Literal grew a case beyond Positive/Negated/Comparison") {
            HierarchyCompleteness.missingFrom(Literal::class.java, known).shouldBeEmpty()
        }
    }

    /** Same pin as above, over [RelationalExpr] — see that test's KDoc. */
    @Test
    fun `WINDOW_CLOSE_UNSUPPORTED and GLOBAL_ORDER_UNSUPPORTED have no antecedent - RelationalExpr has no window or ordering case`() {
        val known = listOf(RelationalExpr.Relation::class.java, RelationalExpr.SetOp::class.java, RelationalExpr.OuterJoin::class.java)
        withClue("RelationalExpr grew a case beyond Relation/SetOp/OuterJoin") {
            HierarchyCompleteness.missingFrom(RelationalExpr::class.java, known).shouldBeEmpty()
        }
    }

    /** Same pin as above, over [Term] — see `Literal`'s test's KDoc. */
    @Test
    fun `WINDOW_CLOSE_UNSUPPORTED and GLOBAL_ORDER_UNSUPPORTED have no antecedent - Term has no window or ordering case`() {
        val known = listOf(Term.Var::class.java, Term.Const::class.java)
        withClue("Term grew a case beyond Var/Const") {
            HierarchyCompleteness.missingFrom(Term::class.java, known).shouldBeEmpty()
        }
    }

    /**
     * Same pin as `Literal`'s test's KDoc, over the three closed-enum vocabularies a window or
     * global-order construct would also have to extend: [AggregateKind] (a windowed or
     * order-dependent aggregate kind), [SetOpKind] (no relevant kind exists today either way,
     * kept exhaustive for the same reason) and [ComparisonOp] (no ordering-relation operator).
     */
    @Test
    fun `WINDOW_CLOSE_UNSUPPORTED and GLOBAL_ORDER_UNSUPPORTED have no antecedent - AggregateKind SetOpKind ComparisonOp are exactly their current members`() {
        AggregateKind.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("COUNT", "SUM", "AVG", "MIN", "MAX", "TOP_K", "COLLECT_TO_SET")
        SetOpKind.entries.map { it.name } shouldContainExactlyInAnyOrder listOf("UNION", "INTERSECTION", "DIFFERENCE")
        ComparisonOp.entries.map { it.name } shouldContainExactlyInAnyOrder listOf("EQ", "NE", "LT", "LE", "GT", "GE")
    }

    // ---------------------------------------------------------------- EXCLUSIVE_PAYLOAD

    /**
     * When this fails, add `EXCLUSIVE_PAYLOAD` with a producer in `RejectionCoverage`
     * ([QRY1-REJECT-09], citing `doc/spec/20-dataflow-semantics/23-ownership.md`). A `Row` is a
     * `List<Any?>` of [AttrType] runtime values; an exclusive payload edge needs one of those
     * runtime types to BE `Owned`/`Leased`/`Frozen`.
     */
    @Test
    fun `EXCLUSIVE_PAYLOAD has no antecedent - no AttrType runtime type is Owned Leased or Frozen`() {
        val exclusive = setOf(Owned::class.java, Leased::class.java, Frozen::class.java)
        val offenders = AttrType.entries.filter { it.runtimeType.java in exclusive }
        withClue("AttrType runtime types that are exclusive payloads: $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    /**
     * When this fails, add `EXCLUSIVE_PAYLOAD` with a producer in `RejectionCoverage`
     * ([QRY1-REJECT-09]). cab.5-D4's "a catalog declaring such a relation type" needs a
     * [RelationSchema] field to declare it on; today it declares only [attributes][RelationSchema.attributes]
     * and [rowKey][RelationSchema.rowKey] (cab.5-D5 supersedes D4 on this ground). Shared with
     * `MULTIWRITER_NONCONVERGENT`'s pin below, which needs the same absence for a different
     * reason (no writer-count field).
     */
    @Test
    fun `EXCLUSIVE_PAYLOAD has no antecedent - RelationSchema declares exactly attributes and rowKey`() {
        val fieldNames = RelationSchema::class.java.declaredFields.map { it.name }.toSet()
        withClue("RelationSchema declared fields: $fieldNames") {
            fieldNames shouldBe setOf("attributes", "rowKey")
        }
    }

    // ---------------------------------------------------------------- MULTIWRITER_NONCONVERGENT

    /**
     * When this fails, add `MULTIWRITER_NONCONVERGENT` with a producer in `RejectionCoverage`
     * ([QRY1-SEM-07]). Every relation lowers to exactly one `src:<relation>` spawn backed by
     * [SetSourceFactory] (`Lowering`'s own KDoc: "One src:<relation> SetCell per relation") —
     * there is no second writer to converge with. Compiles a three-relation chain
     * (`q(a, c) :- r(a, b), s(b, c), t(c).`) directly through [Planner]/[Lowering], the same
     * route `QueryCompiler` (not yet built) will take.
     */
    @Test
    fun `MULTIWRITER_NONCONVERGENT has no antecedent - every src spawn of a three-relation compile carries SetSourceFactory`() {
        val query = Query(
            rules = listOf(
                Rule(
                    Atom("q", listOf(Term.Var("a"), Term.Var("c"))),
                    listOf(
                        Literal.Positive(Atom("r", listOf(Term.Var("a"), Term.Var("b")))),
                        Literal.Positive(Atom("s", listOf(Term.Var("b"), Term.Var("c")))),
                        Literal.Positive(Atom("t", listOf(Term.Var("c")))),
                    ),
                ),
            ),
            catalog = threeRelationCatalog,
        )
        val result = Lowering.lower(Planner.plan(query), query.catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        val srcSpawns = result.spec.steps.filterIsInstance<SpawnStep>().filter { it.handle.startsWith("src:") }
        withClue("non-vacuity: a three-relation chain has src spawns") { srcSpawns.shouldNotBeEmptyClue() }
        val notSingleWriter = srcSpawns.filterNot { it.factory is SetSourceFactory }
        withClue("src spawns not backed by SetSourceFactory: $notSingleWriter") {
            notSingleWriter.shouldBeEmpty()
        }
    }

    /**
     * When this fails, add `MULTIWRITER_NONCONVERGENT` with a producer in `RejectionCoverage`
     * ([QRY1-SEM-07]). Same reflection as `EXCLUSIVE_PAYLOAD`'s pin, for a different reason:
     * there is no writer-count field to declare more than one writer on.
     */
    @Test
    fun `MULTIWRITER_NONCONVERGENT has no antecedent - RelationSchema declares no backing or writer field`() {
        val fieldNames = RelationSchema::class.java.declaredFields.map { it.name }.toSet()
        withClue("RelationSchema declared fields: $fieldNames") {
            fieldNames shouldBe setOf("attributes", "rowKey")
        }
    }

    // ---------------------------------------------------------------- QRY1-SEM-01 / QRY1-SEM-08

    /**
     * [QRY1-SEM-01] (set semantics throughout) and [QRY1-SEM-08] (no compiler-added deletion
     * machinery): compiling the BS-10 chain `q(a, c) :- r(a, b), s(b, c), t(c).` alongside a
     * grouped count `cnt(a, n) :- r(a, b).`, every spawned factory is one of the known
     * operator-family factories `LowerShapeTest.factoryTypes` lists (kept here as a
     * hand-maintained mirror per that file's own `[QRY1-LOWER-06]` discipline, since
     * `LowerShapeTest.kt` is a sibling task's file, not this one's claim — a factory landing in
     * one list without the other is a drift this comment flags, not a silent gap). No eleventh,
     * compensating/recompute-shaped class exists ([24-OP-JOINSET-02], [24-OP-GROUPBY-02]).
     */
    @Test
    fun `QRY1-SEM-01 QRY1-SEM-08 - a joined and grouped compile spawns only the known operator-family factories, no compensating or recompute cell`() {
        val knownFactoryTypes: List<Class<*>> = listOf(
            SetSourceFactory::class.java,
            FilterFactory::class.java,
            FlatMapFactory::class.java,
            JoinFactory::class.java,
            SemiJoinFactory::class.java,
            UnionFactory::class.java,
            IntersectFactory::class.java,
            PadFactory::class.java,
            GroupByFactory::class.java,
            CountFactory::class.java,
        )
        val query = Query(
            rules = listOf(
                Rule(
                    Atom("q", listOf(Term.Var("a"), Term.Var("c"))),
                    listOf(
                        Literal.Positive(Atom("r", listOf(Term.Var("a"), Term.Var("b")))),
                        Literal.Positive(Atom("s", listOf(Term.Var("b"), Term.Var("c")))),
                        Literal.Positive(Atom("t", listOf(Term.Var("c")))),
                    ),
                ),
                Rule(
                    Atom("cnt", listOf(Term.Var("a"), Term.Var("b"))),
                    listOf(Literal.Positive(Atom("r", listOf(Term.Var("a"), Term.Var("b"))))),
                    aggregate = Aggregate(AggregateKind.COUNT),
                ),
            ),
            catalog = threeRelationCatalog,
        )
        val result = Lowering.lower(Planner.plan(query), query.catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        val factories = result.spec.steps.filterIsInstance<SpawnStep>().map { it.factory }
        withClue("non-vacuity: this compile spawns cells") { factories.shouldNotBeEmptyClue() }
        val unknown = factories.filterNot { factory -> knownFactoryTypes.any { it.isInstance(factory) } }
        withClue("spawned factories outside the known operator-family set: ${unknown.map { it::class.java.name }}") {
            unknown.shouldBeEmpty()
        }
    }

    private companion object {
        val threeRelationCatalog = Catalog(
            mapOf(
                "r" to RelationSchema(listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT))),
                "s" to RelationSchema(listOf(Attribute("b", AttrType.INT), Attribute("c", AttrType.INT))),
                "t" to RelationSchema(listOf(Attribute("c", AttrType.INT))),
            ),
        )
    }

    private fun <T> List<T>.shouldNotBeEmptyClue() {
        withClue("expected a non-empty list") { this.isEmpty() shouldBe false }
    }
}
