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
 * Sealed to the five kinds the feature design names plus the glitch kind design D5 requires: a
 * [Mismatch] is a genuine disagreement between the kernel and the reference model at
 * quiescence; a [WavePrefixViolation] is a disagreement *during* the run — an intermediate
 * observation that is no prefix of the wave sequence (`[ORA1-DIFF-06]`); a [DeadLetterFailure]
 * or [NonQuiescence] is a run that never reached a comparable state at all; a
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

    /**
     * A **glitch**: while the case was driven, an intermediate observation of a terminal
     * equalled no prefix of the wave sequence, or equalled an *earlier* prefix than one that
     * terminal had already shown (`[ORA1-DIFF-06]`, epic design D5). Produced by
     * [WavePrefixOracle.Checker]; see that class for the property and its soundness limits.
     *
     * ## Why a dedicated kind rather than a field-extended [Mismatch]
     *
     * The epic treats a glitch as a mismatch *in kind* — both are disagreements with the model —
     * and the bead left the shape to this task. It is a separate kind, for three reasons worth
     * recording so the shrinker and controls features can rely on it:
     *
     * 1. **The evidence has a different shape.** [Mismatch]'s fields presuppose ONE expected
     *    value, at quiescence. A glitch's evidence is a *position in a sequence*: which
     *    observation, which floor it had already reached, which prefixes it sits between. Those
     *    do not fit `expected`/`actual`/`difference` without making every one of them
     *    conditional on a kind flag.
     * 2. **A shrinker must treat them differently.** A settled [Mismatch] shrinks by dropping
     *    ops freely — any smaller script that still disagrees at quiescence is a valid
     *    counterexample. A glitch only exists *while* the case is driven, so a shrinker must
     *    preserve the intermediate driving (and the partial drains) that exposed it. Matching on
     *    kind is how it tells which discipline applies.
     * 3. **The controls feature asserts that a divergent reference yields [Mismatch].** A glitch
     *    arriving as a `Mismatch` with different fields populated would pollute that assertion,
     *    and a control that cannot distinguish "the oracle was deliberately wrong" from "the
     *    kernel showed a torn state" is not a control.
     *
     * Precedence: reported **after** [NonQuiescence], [DeadLetterFailure] and
     * [ModelEvaluationFailure] — each of those invalidates the comparison a glitch report rests
     * on — and **before** [Mismatch], because a run that glitched mid-way is evidence about the
     * kernel whether or not it also settled wrong. See [DifferentialRunner]'s "Kind precedence".
     *
     * @property terminal the offending terminal's name.
     * @property kind which half of the property broke — matchable, so a caller need not parse a
     *   message to tell a torn state from a regression.
     * @property observed the state that matched no admissible prefix.
     * @property observationIndex 1-based index of the offending observation in the run's
     *   observation sequence, so a report says *when* it happened, not only what.
     * @property matchedFloor the highest prefix index this terminal had already matched — the
     *   lowest index still admissible when the offending observation arrived.
     * @property regressedTo for [Kind.REGRESSED], the earlier prefix index [observed] equals;
     *   `null` for [Kind.NO_MATCHING_PREFIX].
     * @property nearestPrefixes this terminal's modelled state at [matchedFloor] and its
     *   successor — the two prefixes a torn observation sits between.
     */
    data class WavePrefixViolation(
        val seed: Long,
        val terminal: String,
        val kind: Kind,
        val renderedGraphSpec: String,
        val script: Script,
        val observed: ModelState,
        val observationIndex: Int,
        val matchedFloor: Int,
        val regressedTo: Int?,
        val nearestPrefixes: Map<Int, ModelState>,
    ) : RunOutcome, Serializable {

        /** Which half of `[ORA1-DIFF-06]`'s property the observation broke. */
        enum class Kind {
            /**
             * The observation equals the model's answer for **no** prefix of the wave sequence —
             * a state no serial execution of the inputs could produce. The canonical shape is a
             * torn composite: one arm of a reconvergent graph updated, the other not.
             */
            NO_MATCHING_PREFIX,

            /**
             * The observation equals an **earlier** prefix than one this terminal had already
             * shown — the terminal went backwards, which is a glitch even though every state in
             * the sequence is individually a legal prefix.
             */
            REGRESSED,
        }
    }
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
