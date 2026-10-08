package civictech.inspect.edit

import civictech.cell.graph.StepEvent
import civictech.cell.graph.StepResult
import civictech.inspect.inspectorJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * WKB2 F6 (computenet-wczst, wczst-D1/D3/D4/D5) — the write plane's wire
 * shapes beyond the ones earlier features already own. `DraftDto` is F12's
 * (`DraftCompiler.kt`) and is not redefined here; `ApplyRecord` is F5/F3's and
 * IS the body of `GET /apply/{id}` — there is deliberately no ApplyRecordDto
 * (wczst-D4).
 */

/**
 * `POST /api/inspect/apply`'s body (wczst-D1): the [draft] to apply and the
 * topology `seq` the client built it against.
 *
 * [baseSeq] is required — a body without it is `400 malformed body` — and is
 * **recorded, not checked**: it lands verbatim on
 * [ApplyRecord.baseTopologyVersion] and nothing compares it to the current
 * topology. WKB2 F7 (`computenet-u3svi`) adds that check and removes this
 * sentence.
 *
 * [confirmation] is decoded and ignored until WKB2 F8 (`computenet-vbmf1`).
 */
@Serializable
data class ApplyRequestDto(
    val draft: DraftDto,
    val baseSeq: Long,
    val confirmation: String? = null,
)

/** A promotion requested by a [DraftDto] (WKB2 F9, 8joqm-D8). */
@Serializable
data class PromotionRequestDto(
    val incumbent: String,
    val outletName: String = "outlet",
    /** Encoded live traffic-light ref; required for the single-instance [candidate] form. */
    val gate: String? = null,
    /** A draft node handle whose `replaces` field names [incumbent]. */
    val candidate: String? = null,
    /** A rolling candidate factory; its constructed cell keeps [incumbent]'s ref. */
    val replicaCandidate: ReplicaCandidateDto? = null,
    /** The workbench supports only the strict, gate-free policy shape. */
    val policy: PromotionPolicyDto? = null,
)

/** Catalogue-backed candidate construction for rolling promotion. */
@Serializable
data class ReplicaCandidateDto(
    val catalogueId: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

/** The serializable subset of [civictech.cell.evolve.PromotionPolicy] accepted by the workbench. */
@Serializable
data class PromotionPolicyDto(
    val windowWaves: Int,
    val judge: String,
)

/** `202` from `POST /api/inspect/apply`: the id to read the apply back under (wczst-D3). */
@Serializable
data class ApplyAcceptedDto(val applyId: String)

/**
 * `GET /api/inspect/applies` (wczst-D4): the audit ring's terminal records,
 * oldest first; `{"entries": []}` when there are none. An apply still in
 * flight is reachable by id, not listed here.
 */
@Serializable
data class AppliesDto(val entries: List<ApplyRecord>)

/**
 * `422` from `POST /api/inspect/apply` when the draft's plan is not appliable
 * (wczst-D3): the one named reason and the whole [plan]. Nothing entered the
 * applier, so there is no apply id, no record and no audit entry. A refusal
 * the applier's own PRECHECK finds later (the graph moved between this plan
 * and the apply) is instead a `refused-at-precheck` *record*.
 */
@Serializable
data class RefusedAtPrecheckDto(
    val reason: String = REFUSED_AT_PRECHECK,
    val plan: PlanDto,
) {
    companion object {
        const val REFUSED_AT_PRECHECK = "refused-at-precheck"
    }
}

/** `apply.phase` (wczst-D5): `{"applyId", "phase": "<ApplyPhase name>"}`. */
internal fun applyPhasePayload(record: ApplyRecord): JsonObject = buildJsonObject {
    put("applyId", record.applyId)
    put("phase", record.phase.name)
}

/**
 * `apply.step` (wczst-D5): `{"applyId", "index", "handle", "result"}`, where
 * `result` is the same [StepOutcome] vocabulary the record uses —
 * `StepResult.Applied` is `{"type":"applied"}`, `StepResult.Rejected(reason)`
 * is `{"type":"failed","reason":…}`.
 */
internal fun applyStepPayload(applyId: String, event: StepEvent): JsonObject = buildJsonObject {
    put("applyId", applyId)
    put("index", event.index)
    put("handle", event.handle)
    val outcome: StepOutcome = when (val result = event.result) {
        is StepResult.Applied -> StepOutcome.Applied
        is StepResult.Rejected -> StepOutcome.Failed(result.reason)
    }
    put("result", inspectorJson.encodeToJsonElement(StepOutcome.serializer(), outcome))
}

/**
 * `apply.done` (wczst-D5): `{"applyId", "outcome"}` — `outcome` is the
 * record's [ApplyOutcome] encoded exactly as `GET /apply/{id}` encodes it, so
 * an `unwound-with-residue` carries its `residue` inside `outcome` and the
 * event and the record have one shape.
 */
internal fun applyDonePayload(record: ApplyRecord): JsonObject = buildJsonObject {
    put("applyId", record.applyId)
    val outcome = checkNotNull(record.outcome) { "apply.done for ${record.applyId} before its outcome was set" }
    put("outcome", inspectorJson.encodeToJsonElement(ApplyOutcome.serializer(), outcome))
}
