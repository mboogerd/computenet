package civictech.query.diag

import civictech.query.QueryCompiler
import civictech.query.ast.Atom
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import civictech.query.diag.RejectionCoverage.catalog
import civictech.query.lower.Lowering
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.schema.Catalog
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The rejection suite epic §8 names: one named test per [RejectionCode] variant, each running
 * its [RejectionCoverage] producer through [QueryCompiler] ([QRY1-REJECT-05]); the
 * `NO_LOWERING` mapping ([QRY1-REJECT-06]); no side effect on a host ([QRY1-REJECT-04]); and
 * multi-phase collection with statement exclusion ([QRY1-REJECT-01], [QRY1-REJECT-10]).
 * Per-precondition well-formedness tests are `civictech.query.parse.WellFormednessAnalysisTest`'s.
 */
class RejectionTest {

    private fun rejectionsOf(code: RejectionCode, index: Int = 0): List<Rejection> {
        val producer = RejectionCoverage.producers.getValue(code)[index]
        return withClue(producer.name) {
            producer.compile().shouldBeInstanceOf<CompileResult.Rejected>().rejections
        }
    }

    private fun spansOf(source: String, catalog: Catalog) =
        (QueryParser.parse(source, catalog) as ParseResult.Parsed).spans

    // ------------------------------------------------------------------ one test per code

    @Test
    fun `SYNTAX_ERROR - a rule missing its terminator is rejected as a syntax error, not thrown`() {
        rejectionsOf(RejectionCode.SYNTAX_ERROR).map { it.code } shouldBe listOf(RejectionCode.SYNTAX_ERROR)
    }

    @Test
    fun `UNSAFE_RULE - an unbound head variable is rejected at the rule's span`() {
        val rejection = rejectionsOf(RejectionCode.UNSAFE_RULE).single()
        rejection.code shouldBe RejectionCode.UNSAFE_RULE
        rejection.locus shouldBe spansOf("q(X) :- r(Y).", catalog("r" to 1)).ruleSpan(0)
    }

    @Test
    fun `EDB_REDEFINED - a rule head naming a catalog relation is rejected`() {
        rejectionsOf(RejectionCode.EDB_REDEFINED).map { it.code } shouldBe listOf(RejectionCode.EDB_REDEFINED)
    }

    @Test
    fun `RECURSION_UNSUPPORTED - a self-recursive rule is rejected, and the planner never sees it`() {
        rejectionsOf(RejectionCode.RECURSION_UNSUPPORTED).map { it.code } shouldBe
            listOf(RejectionCode.RECURSION_UNSUPPORTED)
    }

    @Test
    fun `NO_LOWERING - a non-root GroupAggregate is rejected naming the node kind at its plan node`() {
        val rejection = rejectionsOf(RejectionCode.NO_LOWERING, 0).single()
        rejection.code shouldBe RejectionCode.NO_LOWERING
        rejection.locus.shouldBeInstanceOf<Locus.PlanNode>().id shouldContain ":groupaggregate"
        rejection.specId shouldBe "[QRY1-REJECT-06] GroupAggregate: ${Lowering.AGGREGATE_NOT_A_RELATION}"
    }

    @Test
    fun `NO_LOWERING - an ill-typed comparison is rejected naming Select and the typing reason`() {
        val rejection = rejectionsOf(RejectionCode.NO_LOWERING, 1).single()
        rejection.code shouldBe RejectionCode.NO_LOWERING
        rejection.locus.shouldBeInstanceOf<Locus.PlanNode>().id shouldContain ":select"
        rejection.specId shouldStartWith "[QRY1-REJECT-06] Select: [QRY1-LOWER-07]"
    }

    @Test
    fun `UNKNOWN_PREDICATE - a body atom naming nothing is rejected at the rule's span, or its statement index when built`() {
        val fromText = rejectionsOf(RejectionCode.UNKNOWN_PREDICATE, 0).single()
        fromText.code shouldBe RejectionCode.UNKNOWN_PREDICATE
        fromText.locus shouldBe spansOf("q(X) :- nope(X).", catalog("r" to 1)).ruleSpan(0)

        val built = rejectionsOf(RejectionCode.UNKNOWN_PREDICATE, 1).single()
        built.code shouldBe RejectionCode.UNKNOWN_PREDICATE
        built.locus shouldBe Locus.RuleStatement(0, "q")
    }

    @Test
    fun `ARITY_MISMATCH - an atom used at an arity other than its catalog schema's is rejected`() {
        rejectionsOf(RejectionCode.ARITY_MISMATCH).map { it.code } shouldBe listOf(RejectionCode.ARITY_MISMATCH)
    }

    @Test
    fun `PREDICATE_REDEFINED - a head defined by a rule and a define is rejected at the define`() {
        val rejection = rejectionsOf(RejectionCode.PREDICATE_REDEFINED).single()
        rejection.code shouldBe RejectionCode.PREDICATE_REDEFINED
        rejection.locus shouldBe spansOf("q(X) :- r(X).\ndefine q(X) := s(X).", catalog("r" to 1, "s" to 1))
            .definitionSpan(0)
    }

    @Test
    fun `UNPLANNABLE_STATEMENT - a fact is rejected, not thrown by the planner`() {
        val codes = rejectionsOf(RejectionCode.UNPLANNABLE_STATEMENT).map { it.code }.toSet()
        codes shouldBe setOf(RejectionCode.UNPLANNABLE_STATEMENT)
    }

    // ------------------------------------------------------------------ NO_LOWERING mapping

    @Test
    fun `QRY1 §REJECT-06 every lowering refusal becomes its own NO_LOWERING rejection, never collapsed`() {
        // Two independent refusals in two roots: both are reported, one rejection each.
        val result = QueryCompiler.compile(
            """
            @count c(X, N) :- r(X, N).
            q(X) :- c(X, N).
            t(X) :- r(X, Y), Y = "three".
            """.trimIndent(),
            catalog("r" to 2),
        )
        val rejections = result.shouldBeInstanceOf<CompileResult.Rejected>().rejections
        rejections.map { it.code } shouldBe listOf(RejectionCode.NO_LOWERING, RejectionCode.NO_LOWERING)
        rejections.map { it.specId.substringBefore(":") } shouldContainExactlyInAnyOrder
            listOf("[QRY1-REJECT-06] GroupAggregate", "[QRY1-REJECT-06] Select")
        rejections.map { (it.locus as Locus.PlanNode).id.substringBefore("/") } shouldContainExactlyInAnyOrder
            listOf("q", "t")
    }

    // ------------------------------------------------------------------ no side effect

    @Test
    fun `QRY1 §REJECT-04 a rejected compile spawns no cell on a fresh SimWorld host`() {
        val world = SimWorld(seed = 41)
        var published = 0
        world.registry.onPublish { published++ }

        val result = QueryCompiler.compile("q(X) :- nope(X).\n@count c(X, N) :- r(X, N).\nu(X) :- c(X, N).", catalog("r" to 2))

        result.shouldBeInstanceOf<CompileResult.Rejected>()
        world.runToIdle() shouldBe 0
        withClue("cells published on the host after a rejected compile") { published shouldBe 0 }

        // Positive control: the same instrument does see the spawns of an applied compile.
        val control = SimWorld(seed = 42)
        var controlPublished = 0
        control.registry.onPublish { controlPublished++ }
        QueryCompiler.compile("q(X) :- r(X, Y), Y > 3.", catalog("r" to 2))
            .shouldBeInstanceOf<CompileResult.Compiled>().query.applyTo(control.host.managementInlet)
        withClue("the publish counter must observe spawns, or its zero above proves nothing") {
            (controlPublished > 0) shouldBe true
        }
    }

    // ------------------------------------------------------------------ multi-phase collection

    @Test
    fun `QRY1 §REJECT-10 one compile reports well-formedness, safety and lowering rejections together`() {
        val source = """
            q(X) :- nope(X).
            bad(X) :- r(Y, Z).
            @count c(X, N) :- r(X, N).
            u(X) :- c(X, N).
            dep(X) :- q(X), r(X, X).
            ok(X) :- r(X, Y).
        """.trimIndent()
        val catalog = catalog("r" to 2)
        val spans = spansOf(source, catalog)

        val rejections = QueryCompiler.compile(source, catalog).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        // `dep` references the rejected head `q`: it is excluded from planning, and its
        // exclusion is not itself a rejection — without exclusion the planner would throw on it.
        rejections.map { it.code } shouldBe listOf(
            RejectionCode.UNKNOWN_PREDICATE,
            RejectionCode.UNSAFE_RULE,
            RejectionCode.NO_LOWERING,
        )
        rejections[0].locus shouldBe spans.ruleSpan(0)
        rejections[1].locus shouldBe spans.ruleSpan(1)
        rejections[2].specId shouldStartWith "[QRY1-REJECT-06] GroupAggregate"
    }

    @Test
    fun `QRY1 §REJECT-10 a later-phase statement locus is re-indexed to the caller's query after exclusion`() {
        // Rule 0 is rejected by well-formedness and excluded; the unsafe rule is index 0 of the
        // query safety analysis sees, but index 1 of the caller's.
        fun v(name: String) = Term.Var(name)
        val query = Query(
            rules = listOf(
                Rule(Atom("q", listOf(v("X"))), listOf(Literal.Positive(Atom("nope", listOf(v("X")))))),
                Rule(Atom("bad", listOf(v("X"))), listOf(Literal.Positive(Atom("r", listOf(v("Y"), v("Z")))))),
            ),
            catalog = catalog("r" to 2),
        )

        val rejections = QueryCompiler.compile(query).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections.map { it.code to it.locus } shouldBe listOf(
            RejectionCode.UNKNOWN_PREDICATE to Locus.RuleStatement(0, "q"),
            RejectionCode.UNSAFE_RULE to Locus.RuleStatement(1, "bad"),
        )
    }

    @Test
    fun `QRY1 §REJECT-10 a dependent of a rejected rule whose head is a catalog relation still reports its own rejection`() {
        // `r(X) :- s(X).` is EDB_REDEFINED; `q`'s reference to `r` resolves to the catalog
        // relation, not to that rule, so `q` is independent of the rejection and its own
        // ill-typed comparison must still surface as NO_LOWERING (QueryCompiler's KDoc:
        // a head that is also a catalog relation is not tainted by exclusion).
        val rejections = QueryCompiler.compile(
            "r(X) :- s(X).\nq(X) :- r(X), t(X, Y), Y = \"three\".",
            catalog("r" to 1, "s" to 1, "t" to 2),
        ).shouldBeInstanceOf<CompileResult.Rejected>().rejections

        rejections.map { it.code } shouldBe listOf(RejectionCode.EDB_REDEFINED, RejectionCode.NO_LOWERING)
    }

    @Test
    fun `a query with no rejection in any phase compiles`() {
        QueryCompiler.compile("q(X) :- r(X, Y), Y > 3.", catalog("r" to 2))
            .shouldBeInstanceOf<CompileResult.Compiled>()
    }
}
