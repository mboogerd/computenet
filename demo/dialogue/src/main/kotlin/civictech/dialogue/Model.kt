package civictech.dialogue

import civictech.agora.cell.Polarity
import kotlinx.serialization.Serializable

/**
 * One utterance in a recorded dialogue transcript (epic computenet-2aw §2.3).
 * `tsMillis` is the utterance's explicit event-time attribute — per the
 * kernel's Windows convention (`civictech.cell.data.Windows`), there is no
 * wall-clock timestamping anywhere in this model.
 */
@Serializable
data class Utterance(
    val id: String,
    val turn: Int,
    val speaker: String,
    val tsMillis: Long,
    val text: String,
)

/**
 * A segment of an utterance, produced by segmentation (feature F2). Kept
 * here alongside `Utterance` because both are §2.3 data-model types this
 * task is responsible for, even though nothing in this task produces a
 * `Segment` yet.
 */
@Serializable
data class Segment(
    val id: String,
    val utteranceId: String,
    val ordinal: Int,
    val speaker: String,
    val text: String,
)

/**
 * Identifies a canonical claim distilled from one or more utterances/segments.
 * Not `@Serializable` in the epic text, but `CanonicalClaim`/`CanonicalRelation`
 * below embed it as a property, and kotlinx.serialization's generated
 * serializer for those enclosing `@Serializable` data classes needs a
 * serializer for every property type — an unannotated `@JvmInline value
 * class` does not get one for free. Annotated here so the canonical types
 * round-trip through kotlinx JSON, per the task's explicit discretion to do
 * so.
 */
@Serializable
@JvmInline
value class ClaimKey(val value: String)

/** Identifies a canonical relation (edge) between two canonical claims. */
@Serializable
@JvmInline
value class RelationKey(val value: String)

/**
 * A claim distilled from the transcript, canonicalized across the
 * utterances that expressed it.
 */
@Serializable
data class CanonicalClaim(
    val key: ClaimKey,
    val text: String,
    val fromUtterances: Set<String>,
)

/**
 * A relation (attack/support) between two canonical claims. `polarity` is
 * agora's existing `civictech.agora.cell.Polarity` — reused rather than
 * reintroduced, per the epic's explicit instruction.
 */
@Serializable
data class CanonicalRelation(
    val key: RelationKey,
    val source: ClaimKey,
    val target: ClaimKey,
    val polarity: Polarity,
    val fromUtterances: Set<String>,
)

/**
 * A speaker's derived stance on a claim, projected from the extracted graph.
 * `value == null` means no stance could be projected.
 */
@Serializable
data class ProjectedStance(
    val claim: ClaimKey,
    val speaker: String,
    val value: Double?,
)
