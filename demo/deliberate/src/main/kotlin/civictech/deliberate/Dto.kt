package civictech.deliberate

import kotlinx.serialization.Serializable

/**
 * The UI contract (SPEC §6): `GET /graph` and every `/events` frame.
 * `ui/src/api/types.ts` mirrors this file field for field; change both or
 * neither. Encoded with `explicitNulls = false, encodeDefaults = true`.
 */
@Serializable
data class GraphDto(
    val questions: List<QuestionDto>,
    val nodes: List<NodeDto>,
)

@Serializable
data class QuestionDto(
    val root: String,
    val text: String,
    /** Claims in this question's tree. */
    val claims: Int,
    /** true while any claim in the tree is QUEUED, JUDGING or EXPLORING. */
    val active: Boolean,
)

enum class Status { QUEUED, JUDGING, EXPLORING, SATURATED, ROUND_LIMIT, PRUNED, DEPTH_LIMIT, BUDGET, STOPPED, FAILED }

enum class Override { AUTO, EXPAND, STOP }

@Serializable
data class NodeDto(
    val ref: String,
    val kind: String, // "CLAIM" | "EDGE"
    /** Propagated agora credence, [0,1]. */
    val credence: Double,
    /** The question tree this node belongs to (its root claim ref). */
    val root: String,
    // --- CLAIM only ---
    val text: String? = null,
    val depth: Int? = null,
    val status: Status? = null,
    val override: Override? = null,
    /** "question" for a root, else the proposer id ("claude" | "codex"). */
    val proposer: String? = null,
    /** EXP-03: other proposers that proposed the same point (DUPLICATE) or the replaced wording (REPLACE). */
    val alsoProposedBy: List<String>? = null,
    /** EXP-03 MERGE: true when this argument's text was rewritten together with an overlapping one. */
    val merged: Boolean? = null,
    /** Jev plausibility stance (CRED-01), once judged. */
    val plausibility: Double? = null,
    /** Jev relevance probability (EXP-05), judged when the argument was attached. */
    val relevance: Double? = null,
    /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent. */
    val quality: Double? = null,
    /** SPEC §3 "Exploration order" priority: reach × relevance × quality (root = 1). */
    val contribution: Double? = null,
    /** EXP-05 reach: product of Jev relation strengths along the path from the root (root = 1). */
    val reach: Double? = null,
    /** Last Jev saturation probabilities per side (EXP-04). */
    val proSaturation: Double? = null,
    val conSaturation: Double? = null,
    val rounds: Int? = null,
    val duplicatesDropped: Int? = null,
    /** EXP-03: triage actions taken on this claim's proposals, by action name (ADD, DUPLICATE, …). */
    val triage: Map<String, Int>? = null,
    val error: String? = null,
    // --- EDGE only (child → parent) ---
    val polarity: String? = null, // "SUPPORT" | "ATTACK"
    val source: String? = null,
    val target: String? = null,
    /** Jev relation strength stance (CRED-02), once judged. */
    val strength: Double? = null,
)
