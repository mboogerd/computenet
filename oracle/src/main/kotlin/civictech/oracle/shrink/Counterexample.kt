package civictech.oracle.shrink

import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.model.ScriptEvent
import civictech.oracle.run.RunOutcome
import kotlin.reflect.KClass

/**
 * What a differential failure is *identified by* while it is being shrunk: the [RunOutcome]
 * variant and, where that variant names one, the terminal it was reported on
 * (`[ORA1-SHRINK-02]`).
 *
 * ## Why the variant and the terminal, and nothing else
 *
 * A reduction is retained only if the reduced case "still fails the same way". Taken literally —
 * an equal [RunOutcome] — that would retain nothing at all: every field of a
 * [RunOutcome.Mismatch] except the seed changes as soon as a script step is deleted (the script
 * is a field, the rendered spec is a field, the expected and actual states are fields). Taken
 * loosely — any failure — it would let a shrink walk from a value disagreement to a
 * non-quiescence and report a counterexample for a defect nobody was looking at. The variant
 * plus the terminal is the pair that survives reduction and still names one finding.
 *
 * ## The variants that carry no terminal
 *
 * [RunOutcome.DeadLetterFailure], [RunOutcome.NonQuiescence] and
 * [RunOutcome.ModelEvaluationFailure] carry no terminal name — there is no terminal to name; the
 * run never reached a comparison. For these the signature is the variant alone, so they shrink
 * by **kind matching only**. That is deliberately weaker, and for a dead letter it is all that
 * is available: `RunOutcome.DeadLetterFailure`'s own KDoc records that it is not `Serializable`
 * and is "diagnosed in-process, never replayed by the shrinker the way a [RunOutcome.Mismatch]
 * counterexample is". The shrinker does not exclude it — a reduced dead-lettering case is still
 * a smaller reproduction — but it cannot tell one dead-lettered message apart from another, so a
 * reduction that changes *which* message is lost is retained as if it were the same failure.
 * Read a shrunk dead-letter counterexample with that in mind.
 *
 * ORA2's two **mesh** verdicts ([RunOutcome.ReplicaDivergence], [RunOutcome.ReplicasAgreeButWrong])
 * join that group, and for a structural reason rather than an omission: neither is reported *on* a
 * terminal. A mesh verdict is about the replicas of one logical id, so the field that would
 * discriminate two such findings is the logical id and the differing key set, not a terminal name.
 * They are still told apart from each other and from every other kind by the variant — which is
 * the distinctness `ORA2 §CONV-03` requires — so a shrink can never walk from a divergence to a
 * unanimous-wrong answer and report the wrong defect. Within one variant it matches by kind alone,
 * the same deliberately-weaker discipline the three above have.
 *
 * @property kind the reported [RunOutcome] variant.
 * @property terminal the terminal the failure was reported on, or `null` for a variant that
 *   names none.
 */
data class FailureSignature(val kind: KClass<out RunOutcome>, val terminal: String?) {

    override fun toString(): String =
        "${kind.simpleName}${if (terminal == null) "" else " on '$terminal'"}"

    companion object {
        /**
         * [outcome]'s signature, or `null` for [RunOutcome.Success] — a success has no failure to
         * identify, which is why the shrinker's "did this candidate still fail the same way"
         * question is answered by a nullable comparison rather than by a boolean nobody can
         * trace back to a kind.
         */
        fun of(outcome: RunOutcome): FailureSignature? = when (outcome) {
            RunOutcome.Success -> null
            is RunOutcome.Mismatch -> FailureSignature(RunOutcome.Mismatch::class, outcome.terminal)
            is RunOutcome.WavePrefixViolation ->
                FailureSignature(RunOutcome.WavePrefixViolation::class, outcome.terminal)

            is RunOutcome.DeadLetterFailure -> FailureSignature(RunOutcome.DeadLetterFailure::class, null)
            is RunOutcome.NonQuiescence -> FailureSignature(RunOutcome.NonQuiescence::class, null)
            is RunOutcome.ModelEvaluationFailure ->
                FailureSignature(RunOutcome.ModelEvaluationFailure::class, null)

            is RunOutcome.ReplicaDivergence -> FailureSignature(RunOutcome.ReplicaDivergence::class, null)
            is RunOutcome.ReplicasAgreeButWrong ->
                FailureSignature(RunOutcome.ReplicasAgreeButWrong::class, null)
        }
    }
}

/**
 * How big a case is, along the three axes [Shrinker] reduces plus the terminal count — the
 * "original case size" a [Counterexample] reports so a reader can see what the shrink actually
 * bought without holding the original case.
 *
 * Four independent numbers rather than one score: a shrink that halves the script while leaving
 * the topology alone and one that collapses the topology while leaving the script alone are
 * different outcomes, and a single "size" would hide which happened.
 *
 * @property scriptSteps every [CaseStep] of the script, barriers included.
 * @property elementDomain how many *distinct* element payloads the script names — the axis
 *   `[ORA1-SHRINK-01]`'s second pass narrows.
 * @property nodes topology nodes, sources included.
 * @property terminals observed terminals, late ones included.
 */
data class CaseSize(
    val scriptSteps: Int,
    val elementDomain: Int,
    val nodes: Int,
    val terminals: Int,
) {
    override fun toString(): String =
        "$scriptSteps steps, $elementDomain elements, $nodes nodes, $terminals terminals"

    companion object {
        fun of(case: GeneratedCase): CaseSize = CaseSize(
            scriptSteps = case.script.steps.size,
            elementDomain = scriptElements(case.script).size,
            nodes = case.topology.nodes.size,
            terminals = case.topology.terminals.size,
        )
    }
}

/**
 * A confirmed, reduced failing case — [Shrinker]'s result.
 *
 * [outcome] is the outcome of the **final re-execution** of [case], not of the run that first
 * retained it (`[ORA1-SHRINK-05]`): a candidate that passes when re-executed is never reported,
 * so an outcome here is always one this very case produced on its last run.
 *
 * @property case the reduced case. Replayable as-is: `DifferentialRunner.run(case, reference,
 *   stepBudget)` with the same arguments the shrink was given reproduces [outcome].
 * @property outcome the confirming outcome — its [FailureSignature] equals the original
 *   failure's.
 * @property originalSize the size of the case the shrink started from, so the reduction is
 *   readable against it.
 * @property truncated `true` if the shrink budget ran out with reductions still untried — the
 *   `[ORA1-SHRINK-03]` statement, as a **matchable field** rather than a message substring, so a
 *   caller distinguishes "this is as small as it gets" from "this is as small as the budget
 *   allowed".
 */
data class Counterexample(
    val case: GeneratedCase,
    val outcome: RunOutcome,
    val originalSize: CaseSize,
    val truncated: Boolean,
) {
    /** [case]'s own size, for comparison against [originalSize]. */
    val size: CaseSize get() = CaseSize.of(case)

    /**
     * Renders this counterexample as pasteable, standalone Kotlin (`[ORA1-SHRINK-04]`) — the
     * seed as a literal, [case]'s topology and script rebuilt through catalog ids, and a replay
     * through `civictech.oracle.run.DifferentialRunner.run` that asserts the same [outcome]
     * kind on the same terminal. See [renderCounterexample] (`RenderKotlin.kt`) for the
     * rendering itself and the reasoning behind it — why [case]'s `spec` is never printed, and
     * why the emitted snippet carries no state from the run that produced this counterexample.
     *
     * @throws IllegalStateException if [outcome] is [RunOutcome.Success] — [Shrinker.run] never
     *   returns a passing case as a counterexample, so this is a guard against a value nothing
     *   here can construct through the intended path, not an expected caller error.
     */
    fun renderKotlin(): String = renderCounterexample(this)
}

/**
 * Every distinct element payload [script] names, in order of first appearance — `Add`/`Remove`
 * elements and `Put` *values*.
 *
 * Keys (`Put.key`, `RemoveKey.key`) and counter amounts are deliberately not included: they are
 * their own domains, and `[ORA1-SHRINK-01]`'s second pass is named for the element one. A
 * caller reading [CaseSize.elementDomain] on a keyed or counter case is therefore reading the
 * element axis only, and a narrowing pass leaves those two domains untouched.
 *
 * Order of first appearance, not sorted: it is the order the narrowing pass collapses along, and
 * a `List` of possibly-null `Any?` payloads has no total order to sort by anyway.
 */
internal fun scriptElements(script: CaseScript): List<Any?> {
    val seen = LinkedHashSet<Any?>()
    script.steps.forEach { step ->
        if (step !is CaseStep.Op) return@forEach
        when (val event = step.event) {
            is ScriptEvent.Add -> seen += event.element
            is ScriptEvent.Remove -> seen += event.element
            is ScriptEvent.Put -> seen += event.element
            else -> Unit
        }
    }
    return seen.toList()
}
