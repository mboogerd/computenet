package civictech.query.parse

import civictech.query.ast.Atom
import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.RejectionCode
import civictech.query.schema.Catalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [SafetyAnalysis]'s RECURSION_UNSUPPORTED check ([QRY1-LANG-09], cab.2-D2): DFS/SCC over
 * [RuleGraph], rejecting a rule set whose head-predicate dependency graph has a cycle,
 * self-loop or mutual, and naming every predicate on the cycle plus QRY2 as the eventual
 * owner and the unbuilt cycle machinery ([21-CYCLE-01], [21-CYCLE-03]) as the reason.
 */
class RecursionRefusalTest {

    private fun x() = Term.Var("X")
    private fun y() = Term.Var("Y")
    private fun z() = Term.Var("Z")

    @Test
    fun `BS-2 verbatim - self-recursive path rule rejects RECURSION_UNSUPPORTED naming QRY2`() {
        // path(x,y) :- edge(x,y). path(x,z) :- path(x,y), edge(y,z).
        val base = Rule(
            head = Atom("path", listOf(x(), y())),
            body = listOf(Literal.Positive(Atom("edge", listOf(x(), y())))),
        )
        val recursive = Rule(
            head = Atom("path", listOf(x(), z())),
            body = listOf(
                Literal.Positive(Atom("path", listOf(x(), y()))),
                Literal.Positive(Atom("edge", listOf(y(), z()))),
            ),
        )
        val query = Query(rules = listOf(base, recursive), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.RECURSION_UNSUPPORTED
        rejections.single().specId shouldContain "QRY2"
        rejections.single().specId shouldContain "21-CYCLE-01"
        rejections.single().specId shouldContain "21-CYCLE-03"
        rejections.single().specId shouldContain "path"
    }

    @Test
    fun `mutual recursion between two predicates rejects the whole cycle naming every predicate on it`() {
        // p(x) :- q(x). q(x) :- p(x).
        val pRule = Rule(head = Atom("p", listOf(x())), body = listOf(Literal.Positive(Atom("q", listOf(x())))))
        val qRule = Rule(head = Atom("q", listOf(x())), body = listOf(Literal.Positive(Atom("p", listOf(x())))))
        val query = Query(rules = listOf(pRule, qRule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        val rejection = rejections.single()
        rejection.code shouldBe RejectionCode.RECURSION_UNSUPPORTED
        // Same vacuity the three-predicate test below already narrowed away, and present
        // here for the same reason: a bare `shouldContain "p"` is satisfied by this
        // message's own boilerplate ("unsupported"), so half of the criterion this test
        // NAMES — "naming every predicate on it" — was asserted by a check that cannot
        // fail. Measured by the cab.2 feature review: a mutation reporting only the cycle's
        // last predicate left this test green. Assert the formatted list instead.
        rejection.specId shouldContain "predicates on cycle: p, q"
    }

    @Test
    fun `mutual recursion through three predicates rejects the whole cycle naming all three`() {
        // a(x) :- b(x). b(x) :- c(x). c(x) :- a(x).
        val aRule = Rule(head = Atom("a", listOf(x())), body = listOf(Literal.Positive(Atom("b", listOf(x())))))
        val bRule = Rule(head = Atom("b", listOf(x())), body = listOf(Literal.Positive(Atom("c", listOf(x())))))
        val cRule = Rule(head = Atom("c", listOf(x())), body = listOf(Literal.Positive(Atom("a", listOf(x())))))
        val query = Query(rules = listOf(aRule, bRule, cRule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        val rejection = rejections.single()
        // A bare `shouldContain "a"` / "b" / "c" is satisfied by this message's own boilerplate
        // ("machinery", "unbuilt", "cycle" already contain a/b/c) regardless of which predicates
        // are actually named, so it would not catch a mutation that reported only one
        // representative predicate instead of the whole cycle. Assert the formatted predicate
        // list itself, as the two-independent-cycles test below already does.
        rejection.specId shouldContain "predicates on cycle: a, b, c"
    }

    @Test
    fun `an acyclic rule graph does not reject RECURSION_UNSUPPORTED`() {
        val edge = Rule(head = Atom("path", listOf(x(), y())), body = listOf(Literal.Positive(Atom("edge", listOf(x(), y())))))
        val chain = Rule(
            head = Atom("longPath", listOf(x(), z())),
            body = listOf(
                Literal.Positive(Atom("path", listOf(x(), y()))),
                Literal.Positive(Atom("edge", listOf(y(), z()))),
            ),
        )
        val query = Query(rules = listOf(edge, chain), catalog = Catalog(emptyMap()))

        SafetyAnalysis.analyze(query).shouldBeEmpty()
    }

    @Test
    fun `a definition whose set-op operand cycles back to its own head rejects RECURSION_UNSUPPORTED`() {
        // either(x) := rel(edge(x)) UNION rel(either(x))  — a definition referencing its own
        // head predicate through a set-op operand, resolving this task's traversal question:
        // set-op operands contribute edges exactly like positive body atoms, so this is a
        // self-loop exactly as a self-recursive rule would be.
        val definition = Definition(
            head = Atom("either", listOf(x())),
            expr = RelationalExpr.SetOp(
                kind = SetOpKind.UNION,
                left = RelationalExpr.Relation(Atom("edge", listOf(x()))),
                right = RelationalExpr.Relation(Atom("either", listOf(x()))),
            ),
        )
        val query = Query(rules = emptyList(), catalog = Catalog(emptyMap()), definitions = listOf(definition))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.RECURSION_UNSUPPORTED
        rejections.single().specId shouldContain "either"
    }

    @Test
    fun `two independent unrelated cycles both come back in one analyze call, not first-only`() {
        // path is self-recursive; separately, m and n are mutually recursive. Two disjoint
        // cycles in one query — the aggregation property RECURSION_UNSUPPORTED shares with
        // the other two checks, isolated to this check alone: a query with exactly one
        // defect can't distinguish "returns all" from "returns first", two independent
        // cycles can.
        val pathBase = Rule(head = Atom("path", listOf(x(), y())), body = listOf(Literal.Positive(Atom("edge", listOf(x(), y())))))
        val pathRec = Rule(
            head = Atom("path", listOf(x(), z())),
            body = listOf(
                Literal.Positive(Atom("path", listOf(x(), y()))),
                Literal.Positive(Atom("edge", listOf(y(), z()))),
            ),
        )
        val mRule = Rule(head = Atom("m", listOf(x())), body = listOf(Literal.Positive(Atom("n", listOf(x())))))
        val nRule = Rule(head = Atom("n", listOf(x())), body = listOf(Literal.Positive(Atom("m", listOf(x())))))
        val query = Query(rules = listOf(pathBase, pathRec, mRule, nRule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 2
        rejections.map { it.code }.toSet() shouldBe setOf(RejectionCode.RECURSION_UNSUPPORTED)
        val specIds = rejections.map { it.specId }
        specIds.any { it.contains("predicates on cycle: path") } shouldBe true
        specIds.any { it.contains("predicates on cycle: m, n") } shouldBe true
    }
}
