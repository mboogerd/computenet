package civictech.query.parse

import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.diag.RejectionCode
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [SafetyAnalysis]'s UNSAFE_RULE ([QRY1-LANG-07]) and EDB_REDEFINED ([QRY1-LANG-08]) checks,
 * plus the cross-cutting totality and multi-rejection-aggregation properties both checks
 * share with RECURSION_UNSUPPORTED. `RecursionRefusalTest` covers RECURSION_UNSUPPORTED
 * itself.
 */
class SafetyAnalysisTest {

    private fun x() = Term.Var("X")
    private fun y() = Term.Var("Y")
    private fun z() = Term.Var("Z")

    private val edbCatalog = Catalog(
        mapOf(
            "r" to RelationSchema(listOf(Attribute("a", AttrType.STRING), Attribute("b", AttrType.STRING))),
        ),
    )

    @Test
    fun `a rule with a negated-atom variable unbound by any positive atom rejects UNSAFE_RULE naming it`() {
        // Bead's own example: q(x) :- r(x, y), not s(x, z). — z is bound by no positive atom.
        val rule = Rule(
            head = Atom("q", listOf(x())),
            body = listOf(
                Literal.Positive(Atom("r", listOf(x(), y()))),
                Literal.Negated(Atom("s", listOf(x(), z()))),
            ),
        )
        val query = Query(rules = listOf(rule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.UNSAFE_RULE
        rejections.single().specId shouldContain "Z"
        rejections.single().locus shouldBe Locus.RuleStatement(ruleIndex = 0, headPredicate = "q")
    }

    @Test
    fun `a rule with a head variable unbound by any positive atom rejects UNSAFE_RULE naming it`() {
        val rule = Rule(
            head = Atom("q", listOf(x(), y())),
            body = listOf(Literal.Positive(Atom("r", listOf(x())))),
        )
        val query = Query(rules = listOf(rule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.UNSAFE_RULE
        rejections.single().specId shouldContain "Y"
    }

    @Test
    fun `a rule with a comparison variable unbound by any positive atom rejects UNSAFE_RULE naming it`() {
        val rule = Rule(
            head = Atom("q", listOf(x())),
            body = listOf(
                Literal.Positive(Atom("r", listOf(x()))),
                Literal.Comparison(y(), ComparisonOp.GT, Term.Const(0, AttrType.INT)),
            ),
        )
        val query = Query(rules = listOf(rule), catalog = Catalog(emptyMap()))

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.UNSAFE_RULE
        rejections.single().specId shouldContain "Y"
    }

    @Test
    fun `every rule variable bound by a positive atom is safe - no rejection`() {
        val rule = Rule(
            head = Atom("q", listOf(x())),
            body = listOf(
                Literal.Positive(Atom("r", listOf(x(), y()))),
                Literal.Negated(Atom("s", listOf(x(), y()))),
                Literal.Comparison(y(), ComparisonOp.GT, Term.Const(0, AttrType.INT)),
            ),
        )
        val query = Query(rules = listOf(rule), catalog = Catalog(emptyMap()))

        SafetyAnalysis.analyze(query).shouldBeEmpty()
    }

    @Test
    fun `a rule head redefining a declared EDB relation rejects EDB_REDEFINED`() {
        // Catalog declares EDB relation r; rule r(x) :- s(x).
        val rule = Rule(
            head = Atom("r", listOf(x())),
            body = listOf(Literal.Positive(Atom("s", listOf(x())))),
        )
        val query = Query(rules = listOf(rule), catalog = edbCatalog)

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 1
        rejections.single().code shouldBe RejectionCode.EDB_REDEFINED
        rejections.single().locus shouldBe Locus.RuleStatement(ruleIndex = 0, headPredicate = "r")
    }

    @Test
    fun `a rule head naming a predicate absent from the Catalog does not reject EDB_REDEFINED`() {
        val rule = Rule(
            head = Atom("derivedOnly", listOf(x())),
            body = listOf(Literal.Positive(Atom("s", listOf(x())))),
        )
        val query = Query(rules = listOf(rule), catalog = edbCatalog)

        SafetyAnalysis.analyze(query).shouldBeEmpty()
    }

    @Test
    fun `analyze never throws on an empty well-formed Query`() {
        SafetyAnalysis.analyze(Query(rules = emptyList(), catalog = Catalog(emptyMap()))).shouldBeEmpty()
    }

    @Test
    fun `all independent rejections in one query are returned together, not first-only`() {
        // rule 0: unsafe (Z unbound). rule 1: redefines EDB relation r. Two independent
        // defects in two different rules of the same query — a mutation that stops after the
        // first rejection would return only one of these.
        val unsafe = Rule(
            head = Atom("q", listOf(x())),
            body = listOf(
                Literal.Positive(Atom("p", listOf(x()))),
                Literal.Negated(Atom("s", listOf(x(), z()))),
            ),
        )
        val redefines = Rule(
            head = Atom("r", listOf(x())),
            body = listOf(Literal.Positive(Atom("t", listOf(x())))),
        )
        val query = Query(rules = listOf(unsafe, redefines), catalog = edbCatalog)

        val rejections = SafetyAnalysis.analyze(query)

        rejections shouldHaveSize 2
        rejections.map { it.code }.toSet() shouldBe setOf(RejectionCode.UNSAFE_RULE, RejectionCode.EDB_REDEFINED)
    }

    @Test
    fun `a definition's set-op operand leaf variables do not confuse the safety check for the rule sharing its predicate`() {
        // Only Query.rules are subject to UNSAFE_RULE / EDB_REDEFINED (see this file's own
        // and SafetyAnalysis's KDoc for why); this asserts the presence of a definitions list
        // entry alongside a rule does not perturb the rule's own analysis.
        val safeRule = Rule(
            head = Atom("q", listOf(x())),
            body = listOf(Literal.Positive(Atom("r", listOf(x())))),
        )
        val definition = Definition(
            head = Atom("either", listOf(x())),
            expr = RelationalExpr.SetOp(
                kind = SetOpKind.UNION,
                left = RelationalExpr.Relation(Atom("r", listOf(x()))),
                right = RelationalExpr.Relation(Atom("u", listOf(x()))),
            ),
        )
        val query = Query(rules = listOf(safeRule), catalog = Catalog(emptyMap()), definitions = listOf(definition))

        SafetyAnalysis.analyze(query).shouldBeEmpty()
    }
}
