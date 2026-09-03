package civictech.query.schema

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Literal
import civictech.query.ast.Query
import civictech.query.ast.Rule
import civictech.query.ast.Term
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * `[QRY1-LANG-06]`: the AST and schema types are pure data — no function value anywhere in
 * either hierarchy. Checked over the declared fields of every schema+ast type (reflection
 * over declared members, as the task's own Tests clause permits), rather than only trusting
 * that nobody adds one — a `(Row) -> Boolean` predicate property would compile cleanly as a
 * `data class` constructor parameter and slip past every other test in this module.
 *
 * Deliberately an explicit type list, not a classpath scan: `:query`'s test stack carries no
 * classpath-scanning library (kotlin-reflect included), and the guard is just as strong
 * enumerating the types this feature actually introduces — a new type this feature adds
 * without adding it here is a gap in this list, not a gap the scan would have closed either,
 * since a scanner would need the same "which packages" answer this list already gives.
 */
class NoFunctionTypedPropertyTest {

    private val schemaAndAstTypes: List<Class<*>> = listOf(
        // civictech.query.schema
        Attribute::class.java,
        RelationSchema::class.java,
        Catalog::class.java,
        // civictech.query.ast
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
        Query::class.java,
    )

    @Test
    fun `no schema or ast type declares a function-typed field`() {
        val offenders = schemaAndAstTypes.flatMap { type ->
            type.declaredFields
                .filter { isFunctionTyped(it) }
                .map { "${type.name}.${it.name}: ${it.type.name}" }
        }
        withClue("Function-typed fields found on AST/schema types (violates [QRY1-LANG-06]): $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the type list itself is non-empty, a control for the check above`() {
        // Without this, an accidentally-emptied list above would make the assertion
        // vacuously pass.
        withClue("schemaAndAstTypes must not be empty, or the guard test checks nothing") {
            schemaAndAstTypes.isEmpty() shouldBe false
        }
    }

    private fun isFunctionTyped(field: Field): Boolean {
        val t = field.type
        if (t.name.startsWith("kotlin.jvm.functions.Function")) return true
        if (t.name.startsWith("kotlin.Function")) return true
        if (java.util.function.Function::class.java.isAssignableFrom(t)) return true
        if (t.name == "kotlin.jvm.functions.FunctionN") return true
        return t.interfaces.any { it.name.startsWith("kotlin.jvm.functions.Function") }
    }
}
