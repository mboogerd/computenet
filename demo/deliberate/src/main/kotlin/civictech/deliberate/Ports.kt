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

/** EXP-03: what to do with a proposed argument, relative to the claim's existing arguments. */
enum class TriageAction {
    /** A substantively new point on its stated side. */
    ADD,
    /** The same point as its target: dropped, its proposer recorded on the target. */
    DUPLICATE,
    /** A clearly stronger/clearer version of its target: swaps the target's text if unexplored. */
    REPLACE,
    /** Overlaps its target, each adding something: the two are rewritten as one ([Merger]) if unexplored. */
    MERGE,
    /** A specific instance of / evidence for its target: attached as SUPPORT under the target. */
    REFINE,
    /** Argues the opposite side from the one it was proposed for: attached there. */
    OTHER_SIDE,
    /** Not a real argument about the claim (off-topic, incoherent, a question, restates the claim). */
    DROP,
}

/**
 * EXP-03 MERGE: rewrites two overlapping arguments on [side] of [claim] into
 * one self-contained sentence. Throws on failure.
 */
fun interface Merger {
    fun merge(claim: String, side: Side, a: String, b: String): String
}

/** A proposed argument awaiting triage. */
data class Candidate(val text: String, val side: Side)

/**
 * The triage verdict for one candidate. [target] indexes `ctx.pros + ctx.cons`
 * followed by the candidates before this one (index `pros + cons + j` is
 * candidate `j`); it is null when the action needs none or no target fits.
 */
data class Triage(val action: TriageAction, val target: Int? = null)

/** CRED-01, CRED-02, EXP-05: everything Jev says about a new argument when it is attached. */
data class Assessment(
    val plausibility: Double,
    val strength: Double,
    val quality: Double,
    val relevance: Double,
)

/** Every Jev judgment the engine uses (SPEC §2, §3). Throws on failure. */
interface Judge {
    /** CRED-01: plausibility of [claim] in [0,1], judged without its arguments. */
    fun plausibility(question: String, path: List<String>, claim: String): Double

    /** CRED-02: strength in [0,1] with which [child] bears on [parent] as [side]. */
    fun relationStrength(question: String, parent: String, child: String, side: Side): Double

    /** EXP-05: probability that [child] is a well-constructed argument bearing on [parent] as [side]. */
    fun quality(question: String, parent: String, child: String, side: Side): Double

    /**
     * CRED-01, CRED-02, EXP-05: all judgments of a new argument [child] at
     * attach time. [path] runs from the root down to and including its parent
     * (`path.last()`). [JevJudge] asks them as one request.
     */
    fun assess(question: String, path: List<String>, child: String, side: Side): Assessment = Assessment(
        plausibility = plausibility(question, path, child),
        strength = relationStrength(question, path.last(), child, side),
        quality = quality(question, path.last(), child, side),
        relevance = relevance(ClaimContext(question, path, child, emptyList(), emptyList())),
    )

    /**
     * EXP-03: one verdict per candidate, aligned with [candidates], judged
     * against the claim's existing arguments on both sides (`ctx.pros`,
     * `ctx.cons`) and the candidates before it (see [Triage.target]).
     */
    fun triage(ctx: ClaimContext, candidates: List<Candidate>): List<Triage>

    /**
     * EXP-04: probability that [side] of the claim is saturated, i.e.
     * 1 − p(an important consideration on that side is still missing).
     */
    fun saturation(ctx: ClaimContext, side: Side): Double

    /** EXP-05: probability that analysing the claim further matters for the question. */
    fun relevance(ctx: ClaimContext): Double
}
