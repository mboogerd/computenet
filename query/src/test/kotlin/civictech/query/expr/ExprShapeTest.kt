package civictech.query.expr

import civictech.query.architecture.HierarchyCompleteness
import civictech.query.schema.Row
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable

/**
 * cab.4-D1's shape guarantee for `civictech.query.expr` and `civictech.query.schema.Row`: pure
 * `Serializable` data, no function-typed FIELD anywhere (the interpreters below IMPLEMENT a
 * Kotlin function type — that is a supertype, not a stored lambda field — see
 * `Interpreters.kt`'s class doc), and — the completeness half `computenet-njvps` standardized —
 * [exprTypes] is checked to name every permitted subclass of the closed [Expr] hierarchy, so a
 * new `Expr` case added without updating this list fails loudly rather than passing vacuously.
 */
class ExprShapeTest {

    private val exprTypes: List<Class<*>> = listOf(
        Row::class.java,
        // The closed Expr hierarchy (cab.4-D1).
        Expr::class.java,
        Expr.Attr::class.java,
        Expr.Const::class.java,
        Expr.Cmp::class.java,
        Expr.And::class.java,
        Expr.Or::class.java,
        Expr.Not::class.java,
        // Typing result.
        TypingResult::class.java,
        TypingResult.Typed::class.java,
        TypingResult.TypeMismatch::class.java,
        TypingResult.UnknownAttribute::class.java,
        // The kernel function-value interpreters.
        ExprPredicate::class.java,
        RowProjection::class.java,
        RowKey::class.java,
        RowCombine::class.java,
        RowCombinePadded::class.java,
        RowSelector::class.java,
        RowLongSelector::class.java,
    )

    @Test
    fun `every expr type and Row implement java-io-Serializable`() {
        val nonSerializable = exprTypes.filterNot { Serializable::class.java.isAssignableFrom(it) }
        withClue("expr/Row types not implementing java.io.Serializable: $nonSerializable") {
            nonSerializable.shouldBeEmpty()
        }
    }

    @Test
    fun `no expr type or Row declares a function-typed field`() {
        // The interpreters implement a Kotlin function type as a supertype (their `invoke`
        // is an override, not a stored property) — this checks declared FIELDS, so a
        // structural lambda stashed in a property would still be caught.
        val functionTyped = exprTypes.flatMap { klass ->
            klass.declaredFields
                .filter { field -> kotlin.Function::class.java.isAssignableFrom(field.type) }
                .map { field -> "${klass.simpleName}.${field.name}" }
        }
        withClue("expr/Row fields with a function type: $functionTyped") {
            functionTyped.shouldBeEmpty()
        }
    }

    @Test
    fun `exprTypes names every permitted subclass of Expr and TypingResult`() {
        val missing = HierarchyCompleteness.missingFrom(Expr::class.java, exprTypes) +
            HierarchyCompleteness.missingFrom(TypingResult::class.java, exprTypes)
        withClue("exprTypes is missing sealed-hierarchy members: $missing") {
            missing.shouldBeEmpty()
        }
    }

    @Test
    fun `the type list itself is non-empty, a control for the checks above`() {
        withClue("exprTypes must not be empty, or the guard tests check nothing") {
            exprTypes.isEmpty() shouldBe false
        }
    }
}
