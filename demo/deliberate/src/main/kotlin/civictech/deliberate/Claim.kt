package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef

/**
 * A claim — or a **link** (SPEC §3 "Links as claims"): the statement that
 * an argument bears on its parent. A link's [ref] is the argument's edge
 * ref, its [text] is built from its two ends ([linkText]), its [parent] is
 * the argument's parent and its [depth] the argument's depth; its
 * [children] are the arguments about the connection (SUPPORT: why it
 * holds; ATTACK: undercutters), attached by edges targeting the edge. It
 * is explored exactly like a claim, but is never assessed itself: its
 * `jev` stance is the argument's CRED-02 strength.
 *
 * [DeliberationEngine]'s mutable metadata: every access holds the engine's lock.
 */
internal class Claim(
    val ref: CellRef,
    val root: CellRef,
    /** The claim (or link) this one argues about: its edge's target. For a link: its argument's parent. */
    val parent: Claim?,
    /** The polarity of the edge attaching it; for a link, its argument's polarity. */
    val side: Side?,
    /** EXP-03 REPLACE may swap it while the claim is still unexplored (the graph's text is immutable). */
    var text: String,
    val depth: Int,
    var proposer: String,
    var roundLimit: Int,
    /** Set only on a link: the argument whose connection to [parent] it states. */
    val argument: Claim? = null,
) {
    val isLink get() = argument != null
    /** On an argument: its link — the claim-like node of its edge. */
    var link: Claim? = null
    /**
     * The original topology text: [ClaimNodeFactory.text] for a graph claim,
     * or its initial [linkText] for a link; the record stores [text] only when
     * a rewrite changed it.
     */
    val structureText: String = text
    var status = Status.QUEUED
    var override = Override.AUTO
    var plausibility: Double? = null
    var relevance: Double? = null
    var quality: Double? = null
    /** EXP-05: 1 for the root; parent reach × edge strength once the edge is judged. */
    var reach: Double? = if (parent == null) 1.0 else null
    /** SPEC §3 "Exploration order": reach × relevance × quality; 1 for the root. */
    var contribution: Double? = if (parent == null) 1.0 else null
    var proSaturation: Double? = null
    var conSaturation: Double? = null
    var rounds = 0
    var duplicatesDropped = 0
    val triage = sortedMapOf<TriageAction, Int>()
    val alsoProposedBy = mutableListOf<String>()
    /**
     * EXP-03 REFINE (model B): the texts of candidates triaged as a specific
     * instance of or evidence for this argument, in arrival order — recorded
     * here instead of as child claims. Distinct by normalized text.
     */
    val evidence = mutableListOf<String>()
    /** EXP-03 MERGE rewrote this argument together with an overlapping one. */
    var merged = false
    var error: String? = null
    /** EXP-04: the sides Jev last judged saturated (the cap and the balance rule apply on top, see saturatedSides). */
    val saturated = mutableSetOf<Side>()
    /**
     * CTL-02: the next round is forced (saturation, depth, contribution and
     * budget ignored). Not durable: an EXPAND interrupted by a restart is not
     * resumed — the human expands again (DUR-06).
     */
    var forceRound = false
    var roundInFlight = false
    /** Its attach-time assessment (CRED-01/02, EXP-05) is in flight — for the UI's activity line. */
    var assessing = false
    /** EXPLORING with rounds left; its next round is queued. */
    var waiting = false
    /** Invalidates stale priority-queue entries when a queued claim is reprioritized. */
    var queueGeneration = 0L
    /** Keeps a queued target from starting while REPLACE/MERGE and re-assessment are in progress. */
    var rewriteInFlight = false
    /** Whether the first rewrite reservation invalidated an already queued task. */
    var rewriteWasQueued = false
    var anyCallSucceeded = false
    /** CTL-05: work its paused question withheld (a queued first round, or a waiting next one); resume re-schedules it. */
    var parked = false
    /** CTL-05: restored unassessed in a paused question; resume assesses it before scheduling it. */
    var needsAssessment = false
    var edge: Edge? = null
    /** Its pro and con arguments; for a link, its supporters and undercutters. */
    val children = mutableListOf<Claim>()
    /**
     * Model A: on a question root, how it was framed once framing succeeded
     * (FRAMED); its readings/positions are claims with no parent in the same tree.
     */
    var framing: Framing? = null

    /** How many arguments it holds on [side]. */
    fun countOf(side: Side) = children.count { it.side == side }

    /** Texts from the root down to (excluding) this claim. */
    fun path(): List<String> = generateSequence(parent) { it.parent }.map { it.text }.toList().asReversed()

    /**
     * What [ExplorationPolicy] sees of it; [sensitivity] (model C) is the
     * sensitivity layer's d root / d this node, which only the graph knows.
     */
    fun view(sensitivity: Double? = null) = ClaimView(
        isRoot = parent == null, isLink = isLink, depth = depth, status = status, override = override,
        forceRound = forceRound, rounds = rounds, roundLimit = roundLimit, reach = reach, contribution = contribution,
        sensitivity = sensitivity, plausibility = if (isLink) argument!!.edge?.strength else plausibility,
        pros = countOf(Polarity.SUPPORT), cons = countOf(Polarity.ATTACK), jevSaturated = saturated.toSet(),
        waiting = waiting, rewriteInFlight = rewriteInFlight,
    )

    companion object {
        const val QUESTION = "question"
        /** The proposer field of a link (it has none; its arguments do). */
        const val LINK = "link"
        /** Model A: the proposer field of a reading of a framed question (explored as a root). */
        const val READING = "reading"
        /** Model A: the proposer field of a position of a framed question (explored as a root). */
        const val POSITION = "position"
    }
}

internal class Edge(val ref: CellRef, val root: CellRef, val source: CellRef, val target: CellRef, val side: Side) {
    var strength: Double? = null
}

/** A link's claim text, built from its two ends (never stored). */
internal fun linkText(arg: Claim): String {
    val direction = if (arg.side == Polarity.SUPPORT) "for" else "against"
    return "“${arg.text}” is a reason $direction “${arg.parent!!.text}”"
}

/**
 * [DeliberationEngine]'s metadata beyond the claims' own fields — every
 * collection read or written only while the engine's lock is held.
 */
internal class EngineState {
    /** Every claim by its ref, and every link (SPEC §3 "Links as claims") by its edge's ref. */
    val claims = LinkedHashMap<CellRef, Claim>()
    val edges = LinkedHashMap<CellRef, Edge>()
    val questions = LinkedHashMap<CellRef, String>()
    val treeSize = HashMap<CellRef, Int>()
    /** EXP-10: per question, the yield of every recorded non-root round in completion order (shown, no longer a stop). */
    val yields = HashMap<CellRef, MutableList<Double>>()
    /** CTL-05: paused questions — no new round starts in them except a forced one (CTL-02). */
    val paused = HashSet<CellRef>()
    /**
     * CTL-03 on a question root: questions the human stopped — their queued work
     * is cancelled (STOPPED) and no new round starts in them except a forced one
     * (CTL-02), until AUTO on the root restarts them.
     */
    val stopped = HashSet<CellRef>()

    /** What [ExplorationPolicy] sees of question [root]. */
    fun questionView(root: CellRef) = QuestionView(
        treeSize = treeSize.getValue(root), paused = root in paused, stopped = root in stopped,
    )

    /** The snapshot's tolerant view: the old projection reported an absent tree as empty. */
    fun projectionQuestionView(root: CellRef) = QuestionView(
        treeSize = treeSize[root] ?: 0, paused = root in paused, stopped = root in stopped,
    )

    /** What proposers and Jev are told about [c] (a claim or a link). */
    fun contextOf(c: Claim): ClaimContext = ClaimContext(
        question = questions.getValue(c.root),
        path = c.path(),
        claim = c.text,
        pros = c.children.filter { it.side == Polarity.SUPPORT }.map { it.text },
        cons = c.children.filter { it.side == Polarity.ATTACK }.map { it.text },
        link = c.argument?.let { a -> LinkContext(a.text, a.parent!!.text, a.side!!) },
    )

    /** Creates and registers [arg]'s link: at its depth, about its parent. */
    fun linkFor(arg: Claim, roundLimit: Int): Claim {
        val edge = arg.edge!!
        return Claim(edge.ref, arg.root, arg.parent, arg.side, linkText(arg), arg.depth, Claim.LINK, roundLimit, argument = arg)
            .also { l ->
                arg.link = l
                claims[edge.ref] = l
            }
    }
}
