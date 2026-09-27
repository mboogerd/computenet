package civictech.deliberate

import civictech.agora.cell.Polarity

/**
 * The two seams through which non-determinism enters (SPEC §1). The engine
 * depends only on these; tests use fakes, the app wires Jev and the CLIs.
 */

/** One side of a claim: its pro (SUPPORT) or con (ATTACK) arguments. */
typealias Side = Polarity

/** What a proposer or judge is told about a claim's place in the tree. */
data class ClaimContext(
    /** The root question text. */
    val question: String,
    /** Claim texts from the root down to (excluding) [claim]; empty for the root. */
    val path: List<String>,
    val claim: String,
    val pros: List<String>,
    val cons: List<String>,
)

/** An argument generator (SPEC EXP-02, EXP-09). Throws on failure. */
interface Proposer {
    /** Stable short id recorded as provenance, e.g. "claude" or "codex". */
    val id: String

    /** Up to [max] new one-sentence arguments on [side] of `ctx.claim`. */
    fun propose(ctx: ClaimContext, side: Side, max: Int): List<String>
}

/** Every Jev judgment the engine uses (SPEC §2, §3). Throws on failure. */
interface Judge {
    /** CRED-01: plausibility of [claim] in [0,1], judged without its arguments. */
    fun plausibility(question: String, path: List<String>, claim: String): Double

    /** CRED-02: strength in [0,1] with which [child] bears on [parent] as [side]. */
    fun relationStrength(question: String, parent: String, child: String, side: Side): Double

    /**
     * EXP-03: for each candidate, the index into [existing] it duplicates,
     * or null if it is new. Result is aligned with [candidates].
     */
    fun duplicates(claim: String, side: Side, existing: List<String>, candidates: List<String>): List<Int?>

    /**
     * EXP-04: probability that [side] of the claim is saturated, i.e.
     * 1 − p(an important consideration on that side is still missing).
     */
    fun saturation(ctx: ClaimContext, side: Side): Double

    /** EXP-05: probability that analysing the claim further matters for the question. */
    fun relevance(ctx: ClaimContext): Double
}
