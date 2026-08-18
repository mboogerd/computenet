package civictech.oracle.run

import civictech.cell.host.DeadLetter
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import java.io.Serializable

/**
 * What one differential run of a generated (or bring-your-own) case concluded — the shared
 * result type every runner task (computenet-4ru.8) reports through, so a caller matches on
 * **kind**, never on a message string.
 *
 * Sealed to exactly the five kinds the feature design names: a [Mismatch] is a genuine
 * disagreement between the kernel and the reference model; a [DeadLetterFailure] or
 * [NonQuiescence] is a run that never reached a comparable state at all; a
 * [ModelEvaluationFailure] is the reference model itself breaking, so a broken oracle is never
 * read as a broken kernel (D10). Every failure kind carries the [seed] that produced it — the
 * one fact a shrinker (a later feature) always needs to replay the case.
 *
 * This file defines the taxonomy as data only; the differential runner that *produces* these
 * values, and the wave-prefix and sweep behavior around it, is the next task in this feature.
 */
sealed interface RunOutcome {

    /** The run reached quiescence on every terminal and every fold agreed with the model. */
    data object Success : RunOutcome

    /**
     * A terminal's folded [ModelState] disagreed with the model's — [ORA1-DIFF-02]'s report
     * fields, defined here as data; the comparison and reporting behavior that produces one is
     * the next task's.
     *
     * @property terminal The disagreeing terminal's name ([civictech.oracle.gen.TerminalSpec.name]
     *   for a generated case, or the caller's own name for a bring-your-own one).
     * @property renderedGraphSpec The case's [civictech.cell.graph.GraphSpec] rendered to a
     *   human-readable string for the generated path; the caller's own marker string for the
     *   bring-your-own path ([ORA1-DIFF-11]).
     * @property difference The symmetric difference between [expected] and [actual], shaped by
     *   which [ModelState] variant they share.
     */
    data class Mismatch(
        val seed: Long,
        val terminal: String,
        val renderedGraphSpec: String,
        val script: Script,
        val expected: ModelState,
        val actual: ModelState,
        val difference: StateDifference,
    ) : RunOutcome, Serializable

    /**
     * At least one message was dead-lettered during the run — a run that never reached a
     * state comparable to the model at all, distinct from a [Mismatch] ([ORA1-DIFF-04]).
     *
     * Not [Serializable]: [DeadLetter] carries a [Throwable] cause and an
     * [civictech.cell.proxy.HostedPortInvocation] that do not round-trip a JVM boundary; a
     * dead-letter case is diagnosed in-process, never replayed by the shrinker the way a
     * [Mismatch] counterexample is.
     */
    data class DeadLetterFailure(
        val seed: Long,
        val deadLetters: List<DeadLetter>,
    ) : RunOutcome

    /**
     * The run did not reach quiescence within its step budget — named seed and budget, never
     * folded into a [Mismatch] report ([ORA1-DIFF-07]).
     */
    data class NonQuiescence(
        val seed: Long,
        val stepBudget: Int,
    ) : RunOutcome, Serializable

    /**
     * The reference model itself threw while evaluating the script — a broken oracle, not a
     * broken kernel (D10, [ORA1-DIFF-08]).
     *
     * Not [Serializable]: an arbitrary thrown [Throwable] is not guaranteed to be.
     */
    data class ModelEvaluationFailure(
        val seed: Long,
        val cause: Throwable,
    ) : RunOutcome
}

/**
 * The symmetric difference between two [ModelState]s of the same shape — [Mismatch]'s report
 * field, computed once here so every caller reports the same shape of evidence.
 */
sealed interface StateDifference : Serializable {

    /** Elements present on only one side. */
    data class SetDifference(
        val onlyInExpected: Set<Any?>,
        val onlyInActual: Set<Any?>,
    ) : StateDifference

    /** Keys present on only one side, plus keys present on both with a differing value. */
    data class MapDifference(
        val onlyInExpected: Map<Any?, Any?>,
        val onlyInActual: Map<Any?, Any?>,
        val changed: Map<Any?, Pair<Any?, Any?>>,
    ) : StateDifference

    /** The two disagreeing scalar values. */
    data class ScalarDifference(
        val expected: Any?,
        val actual: Any?,
    ) : StateDifference

    companion object {
        /**
         * The symmetric difference between [expected] and [actual]. Both must be the same
         * [ModelState] variant — a shape mismatch is a wiring bug in the caller, not a
         * reportable oracle finding, so it fails loudly rather than producing a difference
         * that would mislead about what disagreed.
         */
        fun between(expected: ModelState, actual: ModelState): StateDifference = when {
            expected is ModelState.SetState && actual is ModelState.SetState ->
                SetDifference(
                    onlyInExpected = expected.elements - actual.elements,
                    onlyInActual = actual.elements - expected.elements,
                )

            expected is ModelState.MapState && actual is ModelState.MapState -> {
                val onlyInExpected = expected.entries.filterKeys { it !in actual.entries }
                val onlyInActual = actual.entries.filterKeys { it !in expected.entries }
                val changed = expected.entries.keys.intersect(actual.entries.keys)
                    .filter { expected.entries[it] != actual.entries[it] }
                    .associateWith { key -> expected.entries.getValue(key) to actual.entries.getValue(key) }
                MapDifference(onlyInExpected, onlyInActual, changed)
            }

            expected is ModelState.ScalarState && actual is ModelState.ScalarState ->
                ScalarDifference(expected.value, actual.value)

            else -> error(
                "StateDifference.between requires matching ModelState variants, got " +
                    "${expected::class.simpleName} vs ${actual::class.simpleName}"
            )
        }
    }
}
