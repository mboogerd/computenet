package civictech.inspect.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One audited write-plane apply, as [AuditRing] retains it (`[WKB2-04]`).
 *
 * This is the **minimal** record: apply id, submitting identity, the
 * submitted spec verbatim, the topology version it was built against,
 * per-step outcomes, and the submission/completion timestamps — exactly the
 * `[WKB2-04]` field list, nothing more. It deliberately carries no terminal
 * `outcome: ApplyOutcome?` field, so this type has no dependency on feature
 * F1's `ApplyOutcome` (`civictech.inspect.edit.ApplyOutcome`, PR #1062):
 * whichever of this feature (F5) and the staged-applier feature (F3,
 * `computenet-e1ojt`) lands first defines this record, the other extends it.
 * F5 landed first, so **F3 is the extender**: it is expected to add the
 * terminal outcome field and a typed `Draft` beside [submittedDraft], not to
 * redefine this file from scratch.
 *
 * [identity] is the label [WriteGate] admitted for this apply (`[WKB2-49]`,
 * record half) — the capability-holder's default identity when the caller
 * presented none more specific.
 */
@Serializable
data class ApplyRecord(
    val applyId: String,
    val identity: String,
    val submittedDraft: JsonElement,
    val baseTopologyVersion: Long,
    val steps: Map<String, StepOutcome> = emptyMap(),
    val submittedAtMs: Long,
    val completedAtMs: Long? = null,
)

/**
 * The four states a single apply step can end in under the STAGE/UNWIND
 * design (epic `computenet-7p8` `--design` §"Sequencing" 3). Wire names are
 * kebab-case, carried in the `"type"` discriminator like every other sealed
 * DTO in this module (`civictech.inspect.edit.ApplyOutcome`).
 *
 * F3 may rename or extend this vocabulary when it wires the staged applier
 * through; this feature only needs the four states a step can be observed in
 * while it is being audited.
 */
@Serializable
sealed interface StepOutcome {

    /** The step ran and completed without error. */
    @Serializable
    @SerialName("applied")
    data object Applied : StepOutcome

    /** The step ran and failed; [reason] is a human-readable cause. */
    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String) : StepOutcome

    /** The step had applied, then was compensated during an unwind. */
    @Serializable
    @SerialName("unwound")
    data object Unwound : StepOutcome

    /** The step has not executed yet (the default for a step not yet reached). */
    @Serializable
    @SerialName("not-run")
    data object NotRun : StepOutcome
}
