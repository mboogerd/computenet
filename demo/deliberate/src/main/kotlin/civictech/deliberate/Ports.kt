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
    /**
     * SPEC §3 "Links as claims": set when [claim] is a *link* — the statement
     * that [LinkContext.argument] is a reason for/against [LinkContext.parent].
     * Its pros then say why the connection holds, its cons why it fails
     * (undercutters); proposers are asked about the connection, not about
     * whether either claim is true.
     */
    val link: LinkContext? = null,
)

/** The two ends of a link: [argument] is offered as a reason [side] ([Polarity.SUPPORT] = for) [parent]. */
data class LinkContext(val argument: String, val parent: String, val side: Side)

/** An argument generator (SPEC EXP-02, EXP-09). Throws on failure. */
interface Proposer {
    /** Stable short id recorded as provenance, e.g. "claude" or "codex". */
    val id: String

    /** Up to [max] new one-sentence arguments on [side] of `ctx.claim` (of the link, when `ctx.link` is set). */
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
    /**
     * A specific instance of / evidence for its target: recorded in the target's
     * evidence list ([Claim.evidence]), not attached as a child claim (model B).
     */
    REFINE,
    /**
     * Argues the opposite side from the one it was proposed for: attached there.
     * In a link round, a genuine counter-argument is attached against the
     * link's parent claim instead of being mistaken for an argument about the link.
     */
    OTHER_SIDE,
    /**
     * Does not dispute the claim but denies that its target argument bears on it
     * ("this does not show X"): attached as an ATTACK on the target's edge.
     */
    UNDERCUT,
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

/**
 * Model B: what a con candidate against a well-believed claim actually denies
 * ([Judge.bearing]) — the claim itself, or only that it bears on its parent.
 */
enum class Bearing {
    /** It disputes the claim itself: attached as a con argument of the claim (the ordinary path). */
    DISPUTES_CLAIM,
    /** It grants the claim but denies it bears on its parent: attached as an UNDERCUT of the claim's link. */
    DENIES_BEARING,
    /** Neither: not a real argument about the claim or its bearing — rejected as a DROP. */
    NEITHER,
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
    /** EXP-05: the construction Noul alone — canonical form is asked of the proposers, never scored. */
    val quality: Double,
    val relevance: Double,
)

/**
 * Every Jev judgment the engine uses (SPEC §2, §3) — and nothing else: the
 * single-judgment probes the calibration measures (relation strength,
 * quality, relevance on their own) live on [JevJudge]. Throws on failure.
 */
interface Judge {
    /**
     * CRED-01: plausibility of [claim] in [0,1], judged on the root [question]
     * and the claim alone — without its path or arguments. The engine asks it
     * for a claim that has no attach-time [assess] result (the root, or an
     * argument whose assessment failed). A claim the judge says is outside its
     * knowledge is [OUTSIDE_KNOWLEDGE] (model D). No current date is passed:
     * the judgment rests on the judge's own knowledge.
     */
    fun plausibility(question: String, claim: String): Double

    /**
     * CRED-01, CRED-02, EXP-05: all judgments of a new argument [child] at
     * attach time. [path] runs from the root down to and including its parent
     * (`path.last()`). [JevJudge] asks them as one request.
     */
    fun assess(question: String, path: List<String>, child: String, side: Side): Assessment

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

    /**
     * Model B, asked after [triage] for the con candidates it would ADD to a
     * claim Jev already believes ([ExplorationPolicy.asksBearing]): one answer
     * per text in [candidates], aligned. `ctx` is the claim's context; [link]
     * is its connection to its parent (`link.argument` is `ctx.claim`). The
     * default answers [Bearing.DISPUTES_CLAIM] for every candidate — the
     * behaviour before this question existed — so a judge that does not
     * implement it changes nothing. A wrapper around a judge must forward it.
     */
    fun bearing(ctx: ClaimContext, link: LinkContext, candidates: List<String>): List<Bearing> =
        candidates.map { Bearing.DISPUTES_CLAIM }

    companion object {
        /**
         * Model D: the plausibility of a claim the judge answers is outside its
         * knowledge — neither believed nor doubted, rather than the low score a
         * model gives what it has not heard of.
         */
        const val OUTSIDE_KNOWLEDGE = 0.5
    }
}

/** Model A: how a question root is framed before its first round ([Framer]). */
enum class FramingMode { NONE, READINGS, POSITIONS }

/**
 * Model A: a [Framer]'s answer. READINGS: [items] are restated yes/no
 * questions, each fixing one sense of the ambiguous [term]. POSITIONS:
 * [items] are mutually exclusive declarative answers to an open question.
 * NONE: the question has one natural reading and is explored as asked.
 */
data class Framing(val mode: FramingMode, val term: String? = null, val items: List<String> = emptyList()) {
    companion object {
        val NONE = Framing(FramingMode.NONE)
        const val MAX_READINGS = 3
        const val MAX_POSITIONS = 5
    }
}

/**
 * Model A: asked once per question root, before its first round, whether
 * the question should be explored as asked (NONE), as several readings of
 * an ambiguous term (READINGS) or as several competing answers (POSITIONS).
 * Throws on failure; the engine then explores the question unframed.
 */
fun interface Framer {
    fun frame(question: String): Framing
}
