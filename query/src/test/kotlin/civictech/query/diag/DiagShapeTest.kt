package civictech.query.diag

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * [QRY1-LANG-06]-style guarantee for the diag half of the module: every diag type is pure
 * data — [Serializable], no function-typed property — the same shape the AST/schema types
 * (sibling task) carry.
 *
 * [Rejection] and [RejectionCode] cannot be *instantiated* today ([RejectionCode] is landed
 * empty, cab.1-D1), so `DiagSerializationTest`'s round-trip cannot reach them. This test
 * covers them anyway, structurally: `java.lang.Class` reflection needs no instance, only the
 * class itself, and no `kotlin-reflect` dependency (`:query` declares none).
 *
 * Enumerated explicitly rather than discovered by a classpath scan (no scanning library on
 * this module's classpath): extend [diagTypes] whenever a new diag type is added, the same
 * discipline [RejectionCode]'s own KDoc asks of new variants.
 */
class DiagShapeTest {

    private val diagTypes: List<Class<*>> = listOf(
        RejectionCode::class.java,
        Locus::class.java,
        Locus.SourceSpan::class.java,
        Locus.PlanNode::class.java,
        Rejection::class.java,
        CompileResult::class.java,
        CompileResult.Rejected::class.java,
    )

    @Test
    fun `every diag type implements java-io-Serializable`() {
        val nonSerializable = diagTypes.filterNot { Serializable::class.java.isAssignableFrom(it) }
        withClue("diag types not implementing java.io.Serializable: $nonSerializable") {
            nonSerializable.shouldBeEmpty()
        }
    }

    @Test
    fun `no diag type declares a function-typed field`() {
        // kotlin.Function is the stdlib marker every FunctionN interface extends (lambdas,
        // method references, function-typed properties) — checkable via java.lang.Class
        // alone, no kotlin-reflect needed.
        val functionTyped = diagTypes.flatMap { klass ->
            klass.declaredFields
                .filter { field -> kotlin.Function::class.java.isAssignableFrom(field.type) }
                .map { field -> "${klass.simpleName}.${field.name}" }
        }
        withClue("diag fields with a function type: $functionTyped") {
            functionTyped.shouldBeEmpty()
        }
    }
}
