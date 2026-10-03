package civictech.deliberate

import kotlinx.serialization.Serializable

/**
 * The UI contract (SPEC §6): `GET /graph` and every `/events` frame.
 * `ui/src/api/types.ts` mirrors this file field for field; change both or
 * neither. Encoded with `explicitNulls = false, encodeDefaults = true`.
 * The wire JSON of every DTO below is pinned by `DtoGoldenTest` against
 * `ui/test/fixtures/golden.json`, shared with the UI's `golden.test.ts`;
 * change the contract by editing both and regenerating the fixture with
 * `DELIBERATE_GOLDEN_REGEN=1`.
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
    /**
     * Why the tree stopped growing early: "budget" (the hard cap, EXP-06), "voi"
     * (model C: no work left, and at least one node ended DIMINISHING because its
     * value of information fell below ε), or null. ("diminishing", the removed
     * yield stop, is no longer sent.)
     */
    val stoppedBy: String? = null,
    /** CTL-05: the question is paused — no new round starts in it until it is resumed (`POST /question/pause`). */
    val paused: Boolean = false,
    /** SPEC §12: USD spent on this question so far — the sum of every priced call (unpriced calls are left out). */
    val costUsd: Double = 0.0,
    /** SPEC §12: costUsd + claims still to explore × mean cost per completed round; null until 3 rounds completed. */
    val projectedUsd: Double? = null,
    /** SPEC §12: what the figure is made of, per backend. */
    val cost: CostDto = CostDto(),
    /**
     * Model C, "what would change the answer": up to 3 refs of the question's
     * nodes (claims below the root, and links as EDGE refs) with the highest
     * |sensitivity| × 4·p·(1 − p) — p the plausibility, a link's its strength,
     * unjudged ½ — best first. Nodes whose sensitivity is not known yet are left out.
     */
    val cruxes: List<String> = emptyList(),
    /**
     * Model D: Jev's plausibility of the question itself, judged before any
     * argument — its "first impression", which stays the root's prior (so the
     * root node's `credence` still starts from it). Null until judged.
     */
    val firstImpression: Double? = null,
    /**
     * Model D, "what the arguments say": the root's headline credence with the
     * same arguments weighed from a neutral prior (weight
     * `LayerSet.WEAK_PRIOR_WEIGHT` on the first impression) instead. Every layer
     * is computed; this is the headline of them. Null until the root cell emitted.
     */
    val neutralCredence: Double? = null,
    /**
     * Model D: true when the root's credence and [neutralCredence] fall strictly
     * on different sides of ½ — the first impression, not the arguments, decides
     * which way the answer leans.
     */
    val verdictsDisagree: Boolean = false,
    /**
     * Model A: how the question was framed before its first round; null when it
     * was explored as asked. When set, [firstImpression] and [neutralCredence]
     * are null and [verdictsDisagree] false — each position carries its own.
     */
    val framing: FramingDto? = null,
)

/** Model A: a framed question's readings (or positions), in the framer's order. */
@Serializable
data class FramingDto(
    /** "READINGS" | "POSITIONS". */
    val mode: String,
    /** READINGS: the ambiguous term the readings resolve. */
    val term: String? = null,
    val positions: List<PositionDto>,
)

/** Model A: one reading or position — a claim explored as a root of its own. */
@Serializable
data class PositionDto(
    /** Its CLAIM node's ref. */
    val ref: String,
    val text: String,
    /** Its headline credence (equal to its NodeDto's). */
    val credence: Double,
    /** Model D: Jev's plausibility of it, judged against the original question. */
    val firstImpression: Double? = null,
    /** Model D: its headline credence from a neutral prior. */
    val neutralCredence: Double? = null,
    /** Model D: its credence and [neutralCredence] fall strictly on different sides of ½. */
    val verdictsDisagree: Boolean = false,
    /** POSITIONS: its share of the consensus shares (the shares sum to 1); null for READINGS. */
    val share: Double? = null,
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

/**
 * SPEC §5. DIMINISHING (model C): its value of information fell below ε before
 * its next round (records written before model C: the removed yield stop).
 * DEPTH_LIMIT arises only from an explicit engine `maxDepth` (no longer a stop rule).
 */
enum class Status {
    QUEUED, JUDGING, EXPLORING, SATURATED, ROUND_LIMIT, PRUNED, DEPTH_LIMIT, BUDGET, DIMINISHING, STOPPED, FAILED,
    /** Model A: a question root that was framed — its readings/positions are explored instead of it. */
    FRAMED,
}

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
    /**
     * Model D's local arguments-first view per layer: the same direct argument
     * inputs with this node's prior set to ½. An unargued node keeps
     * [credences], because there is no argument-driven standing yet.
     */
    val argumentsFirstCredences: Map<String, Double> = credences,
    /** Geometric-odds consensus of [argumentsFirstCredences]. */
    val argumentsFirstConsensus: Double = consensus,
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
    /** Model A: on a reading/position, the ref of the framed question root it belongs to. */
    val positionOf: String? = null,
    /** Jev plausibility stance (CRED-01), once judged. */
    val plausibility: Double? = null,
    /** Jev relevance probability (EXP-05), judged when the argument was attached. */
    val relevance: Double? = null,
    /** Jev quality probability (EXP-05): a well-constructed argument bearing on its parent (construction only). */
    val quality: Double? = null,
    /**
     * Shown only since model C (the queue follows sensitivity × 4·p·(1 − p)): reach × relevance × quality × 4·p·(1 − p),
     * p its plausibility (root = 1; model B); for a link, its argument's contribution without the 4·p·(1 − p) factor × 4·s·(1 − s), s its strength.
     */
    val contribution: Double? = null,
    /**
     * Model C: d headline(root) / d this node's credence — how far the question's
     * headline credence moves per unit move of this node's (every layer at once),
     * from the sensitivity cells. Root ≈ 1; a link's is its edge's. Null until known.
     */
    val sensitivity: Double? = null,
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
