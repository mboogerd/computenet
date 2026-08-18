package civictech.oracle.run

import civictech.cell.host.DeadLetter
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * RunOutcome is a sealed hierarchy of exactly the five kinds the feature design names, each
 * matchable on type rather than message text, and [StateDifference.between] computes
 * `[ORA1-DIFF-02]`'s symmetric difference for each `ModelState` shape.
 */
class RunOutcomeTest {

    /** Exhaustive `when` over every RunOutcome subtype - a fifth branch fails to compile if
     * a kind is ever removed or added without updating this test, and a missing branch fails
     * to compile if a kind is added without extending it. */
    private fun kindOf(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Success -> "Success"
        is RunOutcome.Mismatch -> "Mismatch"
        is RunOutcome.DeadLetterFailure -> "DeadLetterFailure"
        is RunOutcome.NonQuiescence -> "NonQuiescence"
        is RunOutcome.ModelEvaluationFailure -> "ModelEvaluationFailure"
    }

    @Test
    fun `every kind is matchable on type`() {
        kindOf(RunOutcome.Success) shouldBe "Success"
        kindOf(
            RunOutcome.Mismatch(
                seed = 1L,
                terminal = "t",
                renderedGraphSpec = "spec",
                script = Script.EMPTY,
                expected = ModelState.EMPTY_SET,
                actual = ModelState.EMPTY_SET,
                difference = StateDifference.SetDifference(emptySet(), emptySet()),
            )
        ) shouldBe "Mismatch"
        kindOf(RunOutcome.DeadLetterFailure(seed = 2L, deadLetters = emptyList())) shouldBe "DeadLetterFailure"
        kindOf(RunOutcome.NonQuiescence(seed = 3L, stepBudget = 10)) shouldBe "NonQuiescence"
        kindOf(RunOutcome.ModelEvaluationFailure(seed = 4L, cause = IllegalStateException("boom"))) shouldBe
            "ModelEvaluationFailure"
    }

    @Test
    fun `Mismatch carries seed, terminal, rendered spec, script, expected, actual and difference`() {
        val script = Script.EMPTY
        val expected = ModelState.SetState(setOf("a"))
        val actual = ModelState.SetState(setOf("b"))
        val difference = StateDifference.between(expected, actual)

        val mismatch = RunOutcome.Mismatch(
            seed = 42L,
            terminal = "union",
            renderedGraphSpec = "digraph { union }",
            script = script,
            expected = expected,
            actual = actual,
            difference = difference,
        )

        mismatch.seed shouldBe 42L
        mismatch.terminal shouldBe "union"
        mismatch.renderedGraphSpec shouldBe "digraph { union }"
        mismatch.script shouldBe script
        mismatch.expected shouldBe expected
        mismatch.actual shouldBe actual
        mismatch.difference shouldBe StateDifference.SetDifference(setOf("a"), setOf("b"))
    }

    @Test
    fun `NonQuiescence carries seed and step budget`() {
        val outcome = RunOutcome.NonQuiescence(seed = 7L, stepBudget = 10)
        outcome.seed shouldBe 7L
        outcome.stepBudget shouldBe 10
    }

    @Test
    fun `DeadLetterFailure carries the observed dead letters`() {
        val letter = DeadLetter(
            hostRef = civictech.cell.CellRef(java.util.UUID.randomUUID()),
            cause = null,
            description = "boom",
        )
        val outcome = RunOutcome.DeadLetterFailure(seed = 5L, deadLetters = listOf(letter))
        outcome.deadLetters shouldBe listOf(letter)
    }

    @Test
    fun `ModelEvaluationFailure carries the thrown cause`() {
        val cause = IllegalStateException("boom")
        val outcome = RunOutcome.ModelEvaluationFailure(seed = 6L, cause = cause)
        outcome.cause shouldBe cause
    }

    @Test
    fun `StateDifference between two SetStates names elements only on one side`() {
        val diff = StateDifference.between(
            ModelState.SetState(setOf("a", "b")),
            ModelState.SetState(setOf("b", "c")),
        )
        diff shouldBe StateDifference.SetDifference(onlyInExpected = setOf("a"), onlyInActual = setOf("c"))
    }

    @Test
    fun `StateDifference between two MapStates names added, removed, and changed keys`() {
        val diff = StateDifference.between(
            ModelState.MapState(mapOf("a" to 1, "b" to 2, "c" to 3)),
            ModelState.MapState(mapOf("b" to 20, "c" to 3, "d" to 4)),
        )
        diff shouldBe StateDifference.MapDifference(
            onlyInExpected = mapOf("a" to 1),
            onlyInActual = mapOf("d" to 4),
            changed = mapOf("b" to (2 to 20)),
        )
    }

    @Test
    fun `StateDifference between two ScalarStates names the pair`() {
        val diff = StateDifference.between(ModelState.ScalarState(2L), ModelState.ScalarState(3L))
        diff shouldBe StateDifference.ScalarDifference(expected = 2L, actual = 3L)
    }

    @Test
    fun `StateDifference between mismatched shapes fails loudly`() {
        shouldThrow<IllegalStateException> {
            StateDifference.between(ModelState.EMPTY_SET, ModelState.ScalarState(1L))
        }
    }
}
