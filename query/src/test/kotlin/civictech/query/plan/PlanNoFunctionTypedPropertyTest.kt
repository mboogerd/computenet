package civictech.query.plan

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * `[QRY1-LANG-06]` (plan half): the plan types are pure data — no function value anywhere in
 * the `LogicalPlan` hierarchy. Checked over the declared fields of every plan type
 * (reflection over declared members, following
 * `civictech.query.schema.NoFunctionTypedPropertyTest`'s explicit-type-list technique), so
 * that a future `(Row) -> Boolean` predicate or key-extractor property slipped onto a plan
 * node would fail here rather than only being caught by convention.
 *
 * A sibling file to `civictech.query.schema.NoFunctionTypedPropertyTest`, not an extension
 * of it — the plan package gets its own guard so the schema/ast test file stays untouched
 * (per this task's own Tests clause) and each package's closed type list is owned by the
 * feature that introduced it.
 */
class PlanNoFunctionTypedPropertyTest {

    private val planTypes: List<Class<*>> = listOf(
        PlanNode::class.java,
        JoinKey::class.java,
        OuterJoinSide::class.java,
        Scan::class.java,
        Select::class.java,
        Project::class.java,
        Join::class.java,
        SemiJoin::class.java,
        AntiJoin::class.java,
        Union::class.java,
        Intersect::class.java,
        Difference::class.java,
        GroupAggregate::class.java,
        OuterJoin::class.java,
        LogicalPlan::class.java,
    )

    @Test
    fun `no plan type declares a function-typed field`() {
        val offenders = planTypes.flatMap { type ->
            type.declaredFields
                .filter { isFunctionTyped(it) }
                .map { "${type.name}.${it.name}: ${it.type.name}" }
        }
        withClue("Function-typed fields found on plan types (violates [QRY1-LANG-06]): $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the type list itself is non-empty, a control for the check above`() {
        // Without this, an accidentally-emptied list above would make the assertion
        // vacuously pass.
        withClue("planTypes must not be empty, or the guard test checks nothing") {
            planTypes.isEmpty() shouldBe false
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
