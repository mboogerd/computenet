package civictech.query.ast

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * cab.2-D1's structural-equality precondition: **no AST node carries a source span or any
 * other producer-identifying marker.** Parser/builder equivalence (`[QRY1-LANG-02]`) is
 * asserted by `Query` equality, and `data class` equality includes every property — so a
 * `span`, a `locus`, or an `origin: "parser"` field on any node would make two ASTs of the
 * same query unequal by construction, and the equivalence test could never be written.
 * Spans travel in a parser-side table instead.
 *
 * Checked over declared fields of every `civictech.query.ast` type, by name and by type: a
 * name the marker vocabulary uses, or any field whose type comes from `civictech.query.diag`
 * (where `Locus`/`SourceSpan` live), is a violation. The classifier is proven both ways —
 * synthetic negative controls below, then the real types.
 */
class NoSpanInAstTest {

    companion object {
        /** Field-name stems that would make a node carry position or provenance. */
        private val MARKER_NAMES = listOf(
            "span", "locus", "location", "position", "offset",
            "line", "column", "origin", "produc", "source",
        )

        /** Names the violation kind(s) a single declared field carries, or empty if clean. */
        fun classify(fieldName: String, fieldTypeName: String): List<String> {
            val violations = mutableListOf<String>()
            val lower = fieldName.lowercase()
            MARKER_NAMES.filter { lower.contains(it) }.forEach { violations += "marker name '$it'" }
            if (fieldTypeName.startsWith("civictech.query.diag.")) {
                violations += "diag type $fieldTypeName"
            }
            return violations
        }
    }

    private val astTypes: List<Class<*>> = listOf(
        Term::class.java,
        Term.Var::class.java,
        Term.Const::class.java,
        Atom::class.java,
        Literal::class.java,
        Literal.Positive::class.java,
        Literal.Negated::class.java,
        Literal.Comparison::class.java,
        ComparisonOp::class.java,
        AggregateKind::class.java,
        Aggregate::class.java,
        Rule::class.java,
        SetOpKind::class.java,
        OuterJoinSide::class.java,
        JoinKey::class.java,
        RelationalExpr::class.java,
        RelationalExpr.Relation::class.java,
        RelationalExpr.SetOp::class.java,
        RelationalExpr.OuterJoin::class.java,
        Definition::class.java,
        Query::class.java,
    )

    @Test
    fun `a span-typed field is flagged`() {
        classify("span", "civictech.query.diag.Locus\$SourceSpan") shouldContainExactly
            listOf("marker name 'span'", "diag type civictech.query.diag.Locus\$SourceSpan")
    }

    @Test
    fun `a producer marker field is flagged by name alone`() {
        classify("producedBy", "java.lang.String") shouldContainExactly listOf("marker name 'produc'")
    }

    @Test
    fun `an ordinary AST field is not flagged`() {
        classify("predicate", "java.lang.String").shouldBeEmpty()
        classify("terms", "java.util.List").shouldBeEmpty()
    }

    /**
     * Instance fields only — an enum's constants are static fields of their own type and say
     * nothing about node shape.
     */
    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

    @Test
    fun `no ast type declares a source span or producer marker`() {
        val offenders = astTypes.flatMap { type ->
            instanceFields(type).flatMap { field ->
                classify(field.name, field.type.name).map { "${type.name}.${field.name}: $it" }
            }
        }
        withClue("AST fields carrying a span/provenance marker (breaks cab.2-D1 structural equality): $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the scan is non-vacuous - the type list is populated and really carries fields`() {
        withClue("astTypes must not be empty, or the guard test checks nothing") {
            astTypes.isEmpty() shouldBe false
        }
        // Without this, a scan that saw zero fields (e.g. a filter that excluded everything)
        // would make the assertion above pass while checking nothing at all.
        val scanned = astTypes.sumOf { instanceFields(it).size }
        withClue("the AST scan examined $scanned instance fields; it must examine several") {
            (scanned >= 10) shouldBe true
        }
    }
}
