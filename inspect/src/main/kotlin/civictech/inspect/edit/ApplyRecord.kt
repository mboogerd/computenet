package civictech.inspect.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One audited write-plane apply, as [AuditRing] retains it (`[WKB2-04]`).
 *
 * The first seven fields are the `[WKB2-04]` minimum F5 (`computenet-uya03`)
 * defined: apply id, submitting identity, the submitted spec verbatim, the
 * topology version it was built against, per-step outcomes, and the
 * submission/completion timestamps. F5 landed first, so F3
 * (`computenet-e1ojt`, [StagedApplier]) was the extender: it added the four
 * trailing fields below, each defaulted so a payload written before them
 * still decodes, rather than redefining the record.
 *
 * - [phase] — the [ApplyPhase] the apply is in, or ended in (`[WKB2-16]`).
 * - [outcome] — the terminal [ApplyOutcome]; null while the apply is in flight.
 * - [plan] — the whole PRECHECK plan (`[WKB2-15]`, e1ojt-D4); null before
 *   PRECHECK has produced one.
 * - [stagedRefs] — every ref STAGE spawned, in creation order, in the
 *   inspector's `"<uuid>:<instanceId>"` encoding. It is what a reader marks as
 *   staged while [phase] is [ApplyPhase.STAGE]; it is kept after an unwind as
 *   the audit of what was created and then despawned.
 * - [promotions] — the replacement-specific audit entries (8joqm-D7).
 * - [awaiting] — a sibling's in-flight wait discriminator; null in the
 *   synchronous single-instance form implemented here.
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
    val phase: ApplyPhase = ApplyPhase.PRECHECK,
    val outcome: ApplyOutcome? = null,
    val plan: PlanDto? = null,
    val stagedRefs: List<String> = emptyList(),
    val promotions: List<PromotionRecord> = emptyList(),
    val awaiting: String? = null,
)

/**
 * One promotion's additive audit shape (8joqm-D7, `[WKB2-22]`..`[WKB2-24]`).
 * [incumbentRetired] becomes true only for the single-instance form: a
 * committed rolling rebind keeps the incumbent's ref serving as the candidate
 * and retains the incumbent on every surviving peer, so committed-before-
 * retire holds vacuously because that form has no RETIRE operation.
 */
@Serializable
data class PromotionRecord(
    val incumbent: String,
    val candidate: String?,
    val gate: String?,
    val outletName: String,
    val form: String,
    val status: String,
    val reason: String? = null,
    val retained: Boolean? = null,
    val incumbentRetired: Boolean = false,
    val reversible: Boolean = false,
    val reversibleNote: String =
        "a committed promotion is not reversible by the write plane: rollback after RETIRE " +
            "is a fresh swap in the reverse direction (53 §The promotion swap); the retired " +
            "incumbent's retention window is not built (JAR2/WKB3)",
) {
    /** Open string vocabulary: sibling tasks add waiting/rolling statuses without changing this wire type. */
    object Status {
        const val NOT_RUN = "not-run"
        const val COMMITTED = "committed"
        const val REFUSED_AT_PRECHECK = "refused-at-precheck"
        const val ROLLED_BACK_AT_COMMIT = "rolled-back-at-commit"
        const val FAILED = "failed"
    }

    object Form {
        const val SINGLE = "single"
        const val ROLLING = "rolling"
    }
}

/**
 * The four states a single apply step can end in under the STAGE/UNWIND
 * design (epic `computenet-7p8` `--design` §"Sequencing" 3). Wire names are
 * kebab-case, carried in the `"type"` discriminator like every other sealed
 * DTO in this module (`civictech.inspect.edit.ApplyOutcome`).
 *
 * F3 ([StagedApplier]) wired the staged applier through this vocabulary
 * unchanged: a step not yet reached is [NotRun], a step that ran is [Applied]
 * or [Failed], and an applied step compensated by UNWIND becomes [Unwound].
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
