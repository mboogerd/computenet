package civictech.oracle.run

import civictech.cell.host.DeadLetter
import civictech.oracle.model.ModelDot
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.SourceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * RunOutcome is a sealed hierarchy of exactly the kinds the feature designs name — ORA1's five
 * plus the glitch kind, plus ORA2's two mesh kinds (`[ORA2-CONV-03]`) — each
 * matchable on type rather than message text, and [StateDifference.between] computes
 * `[ORA1-DIFF-02]`'s symmetric difference for each `ModelState` shape.
 */
class RunOutcomeTest {

    /** Exhaustive `when` over every RunOutcome subtype - a branch for a removed kind fails to
     * compile, and a missing branch fails to compile if a kind is added without extending it.
     * [RunOutcome.WavePrefixViolation] is the glitch kind computenet-4ru.8.5 added
     * (`[ORA1-DIFF-06]`, design D5); extending this function is how that addition became
     * visible here rather than silent. */
    private fun kindOf(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Success -> "Success"
        is RunOutcome.Mismatch -> "Mismatch"
        is RunOutcome.DeadLetterFailure -> "DeadLetterFailure"
        is RunOutcome.NonQuiescence -> "NonQuiescence"
        is RunOutcome.ModelEvaluationFailure -> "ModelEvaluationFailure"
        is RunOutcome.WavePrefixViolation -> "WavePrefixViolation"
        is RunOutcome.ReplicaDivergence -> "ReplicaDivergence"
        is RunOutcome.ReplicasAgreeButWrong -> "ReplicasAgreeButWrong"
    }

    /** A [RunOutcome.ReplicaDivergence] with every field populated, for the kind assertions. */
    private fun divergence() = RunOutcome.ReplicaDivergence(
        seed = 6L,
        logicalId = "00000000-0000-0000-0000-000000000001",
        caseMarker = "mesh",
        script = Script.EMPTY,
        expected = ModelState.MapState(mapOf("k" to "v1")),
        perReplica = mapOf(
            "r0" to ModelState.MapState(emptyMap()),
            "r1" to ModelState.MapState(mapOf("k" to "v1")),
        ),
        keys = listOf(
            KeyDivergence(
                key = "k",
                expected = "v1",
                winningDot = ModelDot(1L, SourceId("r1")),
                actualByReplica = mapOf("r0" to null, "r1" to "v1"),
            ),
        ),
    )

    /** A [RunOutcome.ReplicasAgreeButWrong] with every field populated. */
    private fun agreeButWrong(): RunOutcome.ReplicasAgreeButWrong {
        val expected = ModelState.MapState(mapOf("k" to "v1"))
        val actual = ModelState.MapState(mapOf("k" to "v0"))
        return RunOutcome.ReplicasAgreeButWrong(
            seed = 7L,
            logicalId = "00000000-0000-0000-0000-000000000001",
            caseMarker = "mesh",
            script = Script.EMPTY,
            expected = expected,
            actual = actual,
            difference = StateDifference.between(expected, actual),
            replicas = setOf("r0", "r1"),
            keys = listOf(
                KeyDivergence("k", "v1", ModelDot(1L, SourceId("r1")), mapOf("r0" to "v0", "r1" to "v0")),
            ),
        )
    }

    /** A [RunOutcome.WavePrefixViolation] with every field populated, for the kind assertions. */
    private fun violation(kind: RunOutcome.WavePrefixViolation.Kind) = RunOutcome.WavePrefixViolation(
        seed = 5L,
        terminal = "t",
        kind = kind,
        renderedGraphSpec = "spec",
        script = Script.EMPTY,
        observed = ModelState.SetState(setOf("torn")),
        observationIndex = 3,
        matchedFloor = 1,
        regressedTo = if (kind == RunOutcome.WavePrefixViolation.Kind.REGRESSED) 0 else null,
        nearestPrefixes = mapOf(
            1 to ModelState.SetState(setOf("a")),
            2 to ModelState.SetState(setOf("a", "b")),
        ),
    )

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
        kindOf(violation(RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX)) shouldBe "WavePrefixViolation"
        kindOf(violation(RunOutcome.WavePrefixViolation.Kind.REGRESSED)) shouldBe "WavePrefixViolation"
        kindOf(divergence()) shouldBe "ReplicaDivergence"
        kindOf(agreeButWrong()) shouldBe "ReplicasAgreeButWrong"
    }

    @Test
    fun `ORA2-CONV-03 replica divergence and replicas-agree-but-wrong are distinct kinds, and neither is a Mismatch`() {
        // The distinctness is the requirement, not a nicety: a convergence oracle whose two
        // findings arrive as one kind cannot tell "the mesh failed to carry a write" from "every
        // replica computed the same wrong answer", and those are repaired in different files. The
        // assertions are on TYPE, so a later merge of the two kinds fails here rather than
        // quietly weakening every caller's `when`.
        val diverged = divergence()
        val wrong = agreeButWrong()

        diverged.shouldBeInstanceOf<RunOutcome.ReplicaDivergence>()
        wrong.shouldBeInstanceOf<RunOutcome.ReplicasAgreeButWrong>()
        (diverged is RunOutcome.ReplicasAgreeButWrong) shouldBe false
        (wrong is RunOutcome.ReplicaDivergence) shouldBe false
        (diverged is RunOutcome.Mismatch) shouldBe false
        (wrong is RunOutcome.Mismatch) shouldBe false
        kindOf(diverged) shouldNotBe kindOf(wrong)
    }

    @Test
    fun `a mesh verdict names the accepting replica of each differing key's winning dot`() {
        // [ORA2-DIFF-09]: `expected` alone says the answer is wrong; the winning dot's source says
        // WHOSE write the mesh failed to carry, which is where a divergence is actually diagnosed.
        val diverged = divergence()

        diverged.expected shouldBe ModelState.MapState(mapOf("k" to "v1"))
        diverged.perReplica.keys shouldBe setOf("r0", "r1")
        diverged.keys shouldHaveSize 1
        diverged.keys[0].key shouldBe "k"
        diverged.keys[0].winningDot shouldBe ModelDot(1L, SourceId("r1"))
        diverged.keys[0].winningDot!!.source.id shouldBe "r1"
        diverged.keys[0].actualByReplica shouldBe mapOf("r0" to null, "r1" to "v1")
        // and a key the reference does not hold names no dot rather than inventing one
        KeyDivergence("gone", null, null, mapOf("r0" to "ghost")).winningDot shouldBe null
    }

    @Test
    fun `WavePrefixViolation distinguishes a torn state from a regression by kind, not by message`() {
        // Both halves of [ORA1-DIFF-06]'s property are matchable without reading a string: the
        // bead's fifth acceptance clause ("matchable by kind from RunOutcome") is what this pins.
        val torn = violation(RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX)
        val regressed = violation(RunOutcome.WavePrefixViolation.Kind.REGRESSED)

        torn.kind shouldBe RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
        torn.regressedTo shouldBe null
        regressed.kind shouldBe RunOutcome.WavePrefixViolation.Kind.REGRESSED
        regressed.regressedTo shouldBe 0
        RunOutcome.WavePrefixViolation.Kind.entries.size shouldBe 2
    }

    @Test
    fun `WavePrefixViolation carries the sequence position a glitch report needs`() {
        // A glitch's evidence is a POSITION in a sequence, which is the whole reason it is not a
        // field-extended Mismatch (see its KDoc). These are the fields that carry it.
        val torn = violation(RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX)

        torn.seed shouldBe 5L
        torn.terminal shouldBe "t"
        torn.observed shouldBe ModelState.SetState(setOf("torn"))
        torn.observationIndex shouldBe 3
        torn.matchedFloor shouldBe 1
        torn.nearestPrefixes.keys.toList() shouldBe listOf(1, 2)
        torn.renderedGraphSpec shouldBe "spec"
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
