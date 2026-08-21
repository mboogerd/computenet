package civictech.oracle.run

import civictech.cell.host.DeadLetter
import civictech.oracle.model.ModelDot
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import java.io.Serializable

/**
 * What one differential run of a generated (or bring-your-own) case concluded — the shared
 * result type every runner task (computenet-4ru.8) reports through, so a caller matches on
 * **kind**, never on a message string.
 *
 * Sealed to the five kinds the feature design names, the glitch kind design D5 requires, and the
 * two **mesh** kinds ORA2 adds ([ReplicaDivergence], [ReplicasAgreeButWrong], `[ORA2-CONV-03]`): a
 * [Mismatch] is a genuine disagreement between the kernel and the reference model at
 * quiescence; a [WavePrefixViolation] is a disagreement *during* the run — an intermediate
 * observation that is no prefix of the wave sequence (`[ORA1-DIFF-06]`); a [DeadLetterFailure]
 * or [NonQuiescence] is a run that never reached a comparable state at all; a
 * [ModelEvaluationFailure] is the reference model itself breaking, so a broken oracle is never
 * read as a broken kernel (D10). A [ReplicaDivergence] is the replicas of one logical id ending
 * quiescent holding different states, and a [ReplicasAgreeButWrong] is all of them holding the
 * SAME state that is not the model's — the distinction `[ORA2-CONV-02]` exists to force, because
 * a convergence check that only compared replicas to each other passes the second case silently.
 * Every failure kind carries the [seed] that produced it — the
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
     * **The replicas of one logical id do not agree** — ORA2's `[ORA2-CONV-03]` divergence
     * verdict, and the one `[ORA2-DIFF-09]` requires to be distinct from a [Mismatch].
     *
     * ## Why not a [Mismatch]
     *
     * A [Mismatch] is a statement about ONE stream: this terminal folded to that, the model says
     * otherwise. A divergence is a statement about a *mesh*: the replicas of one logical cell
     * ended quiescent holding different states, so there is no single "actual" to put in a
     * mismatch report at all, and whichever replica a single-stream runner happened to read would
     * have produced an arbitrary one of them. Reporting it as a value mismatch would also
     * attribute to the operator's semantics a failure that is really in replication — the two are
     * repaired in different files.
     *
     * ## Why it outranks [ReplicasAgreeButWrong]
     *
     * Once the replicas disagree, at most one of them can be holding the reference answer, so
     * "they agree on the wrong value" is not even expressible. Divergence is therefore reported
     * first and [ReplicasAgreeButWrong] is reachable only from an agreeing mesh — which is what
     * makes the two verdicts genuinely disjoint rather than two renderings of one condition.
     *
     * @property logicalId the replicated logical id, as text — the mesh this verdict is about.
     * @property caseMarker how the case is identified in a report, as on [Mismatch].
     * @property expected the model's ONE converged reference answer for the whole script
     *   (`[ORA2-CONV-01]`). Present even here: a divergence report that named only the replicas'
     *   disagreement would leave the reader unable to tell which of them — possibly none — is
     *   right.
     * @property perReplica each named replica's fold, keyed by its script `SourceId` text, folded
     *   from that replica's OWN delta outlet stream (`[ORA2-CONV-04]`).
     * @property keys per-key evidence for every key on which the reference and the replicas do not
     *   all agree, each naming the accepting replica of the reference's winning dot
     *   (`[ORA2-DIFF-09]`).
     */
    data class ReplicaDivergence(
        val seed: Long,
        val logicalId: String,
        val caseMarker: String,
        val script: Script,
        val expected: ModelState,
        val perReplica: Map<String, ModelState>,
        val keys: List<KeyDivergence>,
    ) : RunOutcome, Serializable

    /**
     * **Every replica agrees, and they are all wrong** — the verdict `[ORA2-CONV-02]` exists for.
     *
     * A convergence oracle that only asked "do the replicas agree?" passes a uniformly wrong mesh:
     * agreement is exactly what a shared bug in the dot algebra produces, since every replica runs
     * the same code over the same dots. `civictech.cell.verify.ReplicaConvergence` answers the
     * agreement question and structurally cannot answer this one — it holds no batch reference —
     * which is why [ConvergenceCheck] composes with it rather than replacing it, and why this kind
     * is distinct from [ReplicaDivergence] rather than a flag on it.
     *
     * Distinct from [Mismatch] as well, and not for report-shape reasons: a [Mismatch] says one
     * stream disagreed with the model, which leaves open that another stream agreed. This says the
     * disagreement is *unanimous across the mesh*, which is evidence about the shared semantics
     * rather than about one path through the graph.
     *
     * @property actual the state every replica folded to — well defined precisely because they
     *   agree.
     * @property replicas the script `SourceId` texts of the agreeing replicas, so a report names
     *   how wide the agreement was.
     * @property keys per-key evidence for the keys on which [expected] and [actual] differ.
     */
    data class ReplicasAgreeButWrong(
        val seed: Long,
        val logicalId: String,
        val caseMarker: String,
        val script: Script,
        val expected: ModelState,
        val actual: ModelState,
        val difference: StateDifference,
        val replicas: Set<String>,
        val keys: List<KeyDivergence>,
    ) : RunOutcome, Serializable

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
 * Per-key evidence in a mesh verdict: what the reference says the key binds, which dot won it and
 * **which replica accepted that dot**, and what each replica actually exposes.
 *
 * `[ORA2-DIFF-09]` names the accepting replica specifically, and the reason is diagnostic rather
 * than decorative: a divergent key is repaired by finding the gossip path that did not carry the
 * winning dot, and the dot's *origin* is where that path starts. `expected` alone says the answer
 * is wrong; [winningDot] says whose write the mesh failed to carry.
 *
 * [winningDot] is `null` exactly when the reference holds no live dot at the key — the key is
 * absent in the converged reference and present at some replica, which is the resurrection shape.
 *
 * @property expected the reference's value for [key], `null` when the key is absent there.
 * @property winningDot the reference's maximal live dot at [key] under the harness-supplied dot
 *   order; its [civictech.oracle.model.ModelDot.source] is the accepting replica.
 * @property actualByReplica each named replica's value for [key], keyed by its script source id
 *   text; a replica at which the key is absent maps to `null`.
 */
data class KeyDivergence(
    val key: Any?,
    val expected: Any?,
    val winningDot: ModelDot?,
    val actualByReplica: Map<String, Any?>,
) : Serializable {
    override fun toString(): String =
        "key=$key expected=$expected" +
            (winningDot?.let { " winningDot=$it accepted-by='${it.source.id}'" } ?: " (absent in the reference)") +
            " actual=$actualByReplica"
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
