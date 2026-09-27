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
    /** SPEC §2: the fixed layers averaged into every node's `consensus`. */
    val consensusMembers: List<String> = emptyList(),
)

@Serializable
data class QuestionDto(
    val root: String,
    val text: String,
    /** Claims in this question's tree. */
    val claims: Int,
    /** true while any claim in the tree is QUEUED, JUDGING or EXPLORING. */
    val active: Boolean,
    /** EXP-10: non-root rounds whose yield was recorded (rounds that asked for at least one proposal). */
    val yieldRounds: Int = 0,
    /** EXP-10: mean yield of the last `yieldWindow` recorded rounds (all of them while there are fewer). */
    val yieldRecent: Double? = null,
    /** EXP-10: mean yield of every recorded round before those; null until there are more than `yieldWindow`. */
    val yieldEarlier: Double? = null,
    /** Why the tree stopped growing early: "budget", "diminishing" (only when queued work was halted), or null. */
    val stoppedBy: String? = null,
    /** CTL-05: the question is paused — no new round starts in it until it is resumed (`POST /question/pause`). */
    val paused: Boolean = false,
    /** SPEC §12: USD spent on this question so far — the sum of every priced call (unpriced calls are left out). */
    val costUsd: Double = 0.0,
    /** SPEC §12: costUsd + claims still to explore × mean cost per completed round; null until 3 rounds completed. */
    val projectedUsd: Double? = null,
    /** SPEC §12: what the figure is made of, per backend. */
    val cost: CostDto = CostDto(),
)

/** SPEC §12: the details behind a question's cost figure. */
@Serializable
data class CostDto(
    /** One entry per backend that made at least one call for the question, in claude, codex, jev order. */
    val backends: List<BackendCostDto> = emptyList(),
    /** Rounds completed in the question (over all its claims). */
    val rounds: Int = 0,
    /** Claims still QUEUED, JUDGING or EXPLORING — the projection assumes one more round each. */
    val queued: Int = 0,
    /** Mean USD per completed round; null until 3 rounds completed. */
    val perRoundUsd: Double? = null,
)

@Serializable
data class BackendCostDto(
    /** "claude" | "codex" | "jev". */
    val backend: String,
    val models: List<String> = emptyList(),
    val calls: Int,
    /** Every input token, cached reads and cache writes included. */
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long,
    /** Every output token, reasoning included. */
    val outputTokens: Long,
    val reasoningTokens: Long,
    /** USD of the priced calls; null when none could be priced. */
    val usd: Double?,
    /** Calls left out of every total because they could not be priced (rate unknown, no reported cost). */
    val unpricedCalls: Int = 0,
    /** The price applied, in words (e.g. "$4.00/1M input · …"). */
    val rate: String,
    val rateSource: String,
    /** When the price was looked up (ISO date); null for a cost the backend reported itself. */
    val rateDate: String? = null,
    /** true when the price is not a published price of the provider. */
    val assumed: Boolean,
    /** A caveat to show with it (e.g. Claude's subscription note). */
    val note: String? = null,
)

/** SPEC §5. DIMINISHING (EXP-10): the question's returns diminished before this claim's next round. */
enum class Status { QUEUED, JUDGING, EXPLORING, SATURATED, ROUND_LIMIT, PRUNED, DEPTH_LIMIT, BUDGET, DIMINISHING, STOPPED, FAILED }

enum class Override { AUTO, EXPAND, STOP }

@Serializable
data class NodeDto(
    val ref: String,
    val kind: String, // "CLAIM" | "EDGE"
    /** Propagated credence in the headline semantics layer (`--semantics`), [0,1]. */
    val credence: Double,
    /** The question tree this node belongs to (its root claim ref). */
    val root: String,
    /** SPEC §2 "Credence layers and consensus": propagated credence per semantics layer id. */
    val credences: Map<String, Double> = emptyMap(),
    /** Geometric-odds mean of the consensus member layers' credences (the headline number). */
    val consensus: Double = credence,
    /** Lowest and highest credence over all layers. */
    val spreadLow: Double = credence,
    val spreadHigh: Double = credence,
    // --- CLAIM, and EDGE as a link (SPEC §3 "Links as claims": text, depth, status,
    // override, reach, contribution, saturation, rounds, duplicatesDropped, triage,
    // error, activity) ---
    /** A claim's text; an edge's link text, "“<source>” is a reason for|against “<target>”". */
    val text: String? = null,
    /** A link's depth is its argument's depth. */
    val depth: Int? = null,
    val status: Status? = null,
    val override: Override? = null,
    /**
     * What the node is doing right now, for the UI's activity line: "exploring"
     * (a round in flight), "judging" (its plausibility, before its first
     * round) or "assessing" (its attach-time judgments); null when idle.
     */
    val activity: String? = null,
    // --- CLAIM only ---
    /** "question" for a root, else the proposer id ("claude" | "codex"). */
    val proposer: String? = null,
    /** EXP-03: other proposers that proposed the same point (DUPLICATE) or the replaced wording (REPLACE). */
    val alsoProposedBy: List<String>? = null,
    /** EXP-03 MERGE: true when this argument's text was rewritten together with an overlapping one. */
    val merged: Boolean? = null,
    /**
     * EXP-03 REFINE (model B): texts proposed as specific instances of or
     * evidence for this argument, recorded on it instead of as child claims.
     */
    val evidence: List<String>? = null,
    /**
     * EXP-03 UNDERCUT: the ref of the EDGE this claim attacks — it denies that
     * that edge's source bears on its target. Its own edge's `target` is that ref.
     */
    val undercuts: String? = null,
    /**
     * SPEC §3 "Links as claims": the ref of the EDGE whose link this claim
     * argues about — for (SUPPORT: why the connection holds) or against
     * (ATTACK, then also [undercuts]). Null for an argument about a claim.
     */
    val onLink: String? = null,
    /** Jev plausibility stance (CRED-01), once judged. */
    val plausibility: Double? = null,
    /** Jev relevance probability (EXP-05), judged when the argument was attached. */
    val relevance: Double? = null,
    /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent (construction only). */
    val quality: Double? = null,
    /**
     * SPEC §3 "Exploration order" priority: reach × relevance × quality × 4·p·(1 − p),
     * p its plausibility (root = 1; model B); for a link, its argument's contribution × 4·s·(1 − s), s its strength.
     */
    val contribution: Double? = null,
    /** EXP-05 reach: product of Jev relation strengths along the path from the root (root = 1; a link: its argument's). */
    val reach: Double? = null,
    /** Last Jev saturation probabilities per side (EXP-04). */
    val proSaturation: Double? = null,
    val conSaturation: Double? = null,
    val rounds: Int? = null,
    val duplicatesDropped: Int? = null,
    /** EXP-03: triage actions taken on this claim's proposals, by action name (ADD, DUPLICATE, …). */
    val triage: Map<String, Int>? = null,
    val error: String? = null,
    // --- EDGE only (child → parent; the parent is an EDGE for an undercutter) ---
    val polarity: String? = null, // "SUPPORT" | "ATTACK"
    val source: String? = null,
    val target: String? = null,
    /** Jev relation strength stance (CRED-02), once judged. */
    val strength: Double? = null,
)
