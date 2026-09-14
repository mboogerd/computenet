package civictech.query.run

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * `[QRY1-LANG-06]`-style shape guarantee for `civictech.query.run`, the same discipline
 * `DiagShapeTest`/`LowerShapeTest` apply to their own packages: every type here is pure data
 * — `Serializable`, no function-typed field. `OutputShape` and `CompiledQuery`/`AppliedQuery`
 * are plain (non-sealed) types with no permitted-subclass set to check, so there is no
 * completeness half here the way `HierarchyCompleteness` covers `Locus`/`CompileResult`/
 * `LoweringResult`.
 */
class RunShapeTest {

    private val runTypes: List<Class<*>> = listOf(
        CompiledQuery::class.java,
        AppliedQuery::class.java,
        OutputShape::class.java,
    )

    @Test
    fun `every run type implements java-io-Serializable`() {
        val nonSerializable = runTypes.filterNot { Serializable::class.java.isAssignableFrom(it) }
        withClue("run types not implementing java.io.Serializable: $nonSerializable") {
            nonSerializable.shouldBeEmpty()
        }
    }

    @Test
    fun `no run type declares a function-typed field`() {
        val functionTyped = runTypes.flatMap { klass ->
            klass.declaredFields
                .filter { field -> kotlin.Function::class.java.isAssignableFrom(field.type) }
                .map { field -> "${klass.simpleName}.${field.name}" }
        }
        withClue("run fields with a function type: $functionTyped") {
            functionTyped.shouldBeEmpty()
        }
    }
}
