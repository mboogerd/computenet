package civictech.deliberate

import civictech.agora.cell.Polarity
import kotlin.math.abs
import kotlin.math.pow

/**
 * What [ExplorationPolicy] needs to know about one claim (or link): an
 * immutable copy of its fields, taken by the engine under its lock.
 */
internal data class ClaimView(
    val isRoot: Boolean = false,
    val isLink: Boolean = false,
    val depth: Int = 1,
    val status: Status = Status.QUEUED,
    val override: Override = Override.AUTO,
    /** CTL-02: the next round is forced. */
    val forceRound: Boolean = false,
    val rounds: Int = 0,
    val roundLimit: Int = 3,
    val reach: Double? = null,
    val contribution: Double? = null,
    /**
     * Model C: d headline(root) / d this node ([CredenceGraph.sensitivityOf]),
     * null until the sensitivity layer reached it.
     */
    val sensitivity: Double? = null,
    /** Model C: its plausibility; for a link, its argument's edge strength. Null while unjudged. */
    val plausibility: Double? = null,
    /** How many pro (SUPPORT) and con (ATTACK) arguments it holds. */
    val pros: Int = 0,
    val cons: Int = 0,
    /** EXP-04: the sides Jev last judged saturated. */
    val jevSaturated: Set<Side> = emptySet(),
    /** EXPLORING with rounds left; its next round is queued. */
    val waiting: Boolean = false,
    val rewriteInFlight: Boolean = false,
)

/** What [ExplorationPolicy] needs to know about a claim's question: its per-question counters. */
internal data class QuestionView(
    val treeSize: Int = 1,
    /** CTL-05. */
    val paused: Boolean = false,
    /** CTL-03 on a question root: the human stopped the whole question. */
    val stopped: Boolean = false,
)

/** EXP-10: one argument a round attached, as its round yield counts it. */
internal data class AttachedValue(val strength: Double?, val relevance: Double?, val quality: Double?)

/** How [DeliberationEngine] ends a claim's expansion: its final status and, if any, the error to record. */
internal data class Finish(val status: Status, val error: String? = null)

/**
 * The exploration rules of SPEC §3 (EXP-04..06, EXP-10, CTL-02/03/05) as
 * pure functions over [ClaimView] and [QuestionView]: no locks, no threads,
 * no I/O. The engine takes the views under its lock and applies the answers.
 *
 * Model C: the queue order and the stop read the dataflow. A node's value of
 * information is |d root / d node| × 4·p·(1 − p) ([valueOf]; its sensitivity
 * from the sensitivity cells, p its plausibility), decayed per round it ran
 * ([voiOf]). It orders the queue ([priorityOf]), and a node whose value of
 * information is below `voiEpsilon` gets no (further) round: it ends
 * DIMINISHING. A question therefore stops once the largest value of
 * information over its remaining nodes falls below ε — with its hard cost cap
 * (`maxClaims`, EXP-06 BUDGET) still standing. This replaced reach × relevance
 * as the order, the relevance floor, `maxDepth` and the yield stop as stop
 * rules (`maxDepth` survives only as an engine bound the tests use; the app
 * sets no depth).
 */
internal class ExplorationPolicy(val config: DeliberationEngine.Config) {

    companion object {
        /** SPEC §3: statuses that end a claim's expansion. DEPTH_LIMIT only arises from an explicit `maxDepth`. */
        val FINISHED = setOf(
            Status.SATURATED, Status.ROUND_LIMIT, Status.PRUNED, Status.DEPTH_LIMIT,
            Status.BUDGET, Status.DIMINISHING, Status.STOPPED, Status.FAILED, Status.FRAMED,
        )
        val ACTIVE = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
        val SIDES = listOf(Polarity.SUPPORT, Polarity.ATTACK)
        val Side.opposite get() = if (this == Polarity.SUPPORT) Polarity.ATTACK else Polarity.SUPPORT

        /**
         * Model B: a con candidate against a claim whose plausibility reaches this
         * is asked whether it disputes the claim or only its bearing ([Judge.bearing]).
         * Below it the triage path is unchanged. Motivated by a scratch model review
         * (2026-09-27, not in the repo), not by a calibration run: treat it as a
         * starting value, not a measured one.
         */
        const val BEARING_PLAUSIBILITY = 0.8

        /** QuestionDto.stoppedBy of a question the human stopped (CTL-03 on its root). */
        const val STOPPED_BY_HUMAN = "human"
    }

    // ---------------------------------------------------------------- EXP-04 caps and saturation

    /** EXP-04: the per-side cap — `maxArgsPerSide` for the root, `maxArgsPerSideChild` below it. */
    fun capOf(isRoot: Boolean): Int = if (isRoot) config.maxArgsPerSide else config.maxArgsPerSideChild

    fun capOf(c: ClaimView): Int = capOf(c.isRoot)

    /** How many arguments [c] holds on [side]. */
    fun countOf(c: ClaimView, side: Side): Int = if (side == Polarity.SUPPORT) c.pros else c.cons

    /** EXP-04: [side] of [c] already holds its cap of arguments. */
    fun atCap(c: ClaimView, side: Side): Boolean = countOf(c, side) >= capOf(c)

    /** EXP-04: how many more arguments fit on [side] of [c] (negative when overfilled). */
    fun roomOn(c: ClaimView, side: Side): Int = capOf(c) - countOf(c, side)

    /** Whether one more argument may be attached on [side] of [c]; a [forced] round (CTL-02) has its own allowance. */
    fun fits(c: ClaimView, side: Side, forced: Boolean): Boolean = forced || !atCap(c, side)

    /**
     * EXP-04: a side is saturated when it holds its cap, or when Jev last
     * judged it saturated **and** it holds at least as many arguments as the
     * other side — a side that is behind is never saturated by Jev alone.
     */
    fun saturatedSides(c: ClaimView): Set<Side> = SIDES.filter { side ->
        atCap(c, side) || (side in c.jevSaturated && countOf(c, side) >= countOf(c, side.opposite))
    }.toSet()

    /** EXP-04: whether a Jev saturation probability [p] saturates its side. */
    fun jevSaturates(p: Double): Boolean = p >= config.saturation

    /** The sides [c]'s next round asks about; a forced round (CTL-02) asks both. */
    fun nextSides(c: ClaimView): List<Side> = if (c.forceRound) SIDES else SIDES - saturatedSides(c)

    // ---------------------------------------------------------------- gates

    /** CTL-05: [c]'s question is paused and its next round is not a forced one (CTL-02). */
    fun held(c: ClaimView, q: QuestionView): Boolean = q.paused && !c.forceRound

    /**
     * CTL-03 on a question root: [c]'s question was stopped by the human and its
     * next round is not a forced one (CTL-02), so its queued work ends STOPPED.
     * Checked before [held]: a stopped question cancels what a pause would only withhold.
     */
    fun cancelled(c: ClaimView, q: QuestionView): Boolean = q.stopped && !c.forceRound

    /**
     * The gate that ends a QUEUED claim before its first round without any
     * judgment (EXP-05; CTL-02 skips them), or null when it is queued: links
     * off, beyond an explicit `maxDepth` (DEPTH_LIMIT), the hard cap (BUDGET,
     * EXP-06) and, model C, a value of information below ε (DIMINISHING).
     */
    fun scheduleGate(c: ClaimView, q: QuestionView): Status? =
        if (c.isRoot || c.override == Override.EXPAND || c.forceRound) null
        else when {
            c.isLink && !config.exploreLinks -> Status.PRUNED
            c.depth > config.maxDepth -> Status.DEPTH_LIMIT
            q.treeSize >= config.maxClaims -> Status.BUDGET
            belowEpsilon(c) -> Status.DIMINISHING
            else -> null
        }

    /** EXP-06 after EXP-05, once a dequeued claim was judged. A forced round (CTL-02) passes. */
    fun startBudgetGate(forceRound: Boolean, treeSize: Int): Status? =
        Status.BUDGET.takeIf { !forceRound && treeSize >= config.maxClaims }

    /**
     * Model C, after the budget gate, once a dequeued claim was judged: its value
     * of information, re-read now, is below ε. A forced round (CTL-02) passes.
     */
    fun startVoiGate(c: ClaimView): Status? = Status.DIMINISHING.takeIf { !c.forceRound && belowEpsilon(c) }

    /**
     * The status that ends [c]'s expansion before its next round, or null if
     * it gets one. A forced round (CTL-02) ignores the round limit, saturation,
     * the budget and the value of information.
     */
    fun terminalStatus(c: ClaimView, q: QuestionView): Status? = when {
        c.override == Override.STOP -> Status.STOPPED
        cancelled(c, q) -> Status.STOPPED
        nextSides(c).isEmpty() -> Status.SATURATED
        !c.forceRound && c.rounds >= c.roundLimit -> Status.ROUND_LIMIT
        !c.forceRound && q.treeSize >= config.maxClaims -> Status.BUDGET
        !c.forceRound && belowEpsilon(c) -> Status.DIMINISHING
        else -> null
    }

    /**
     * How a claim ends when its expansion stops with [status]. A STOP that
     * raced the last round boundary still wins (CTL-03). EXP-06: a claim that
     * meets the hard cap ends BUDGET whether or not it ran a round; `rounds`
     * still distinguishes the two cases.
     */
    fun finish(c: ClaimView, status: Status): Finish = when {
        c.override == Override.STOP -> Finish(Status.STOPPED)
        else -> Finish(status)
    }

    /** EXP-06: whether a question holding [treeSize] claims may take one more; a forced round (CTL-02) always may. */
    fun mayReserve(treeSize: Int, forced: Boolean): Boolean = forced || treeSize < config.maxClaims

    /**
     * Why a question stopped growing early, if it did (QuestionDto.stoppedBy):
     * the human stopped it (CTL-03 on its root), its hard cap, or — once no work
     * is left ([active] false) — model C's value-of-information stop, when it
     * left at least one node DIMINISHING.
     */
    fun stoppedBy(q: QuestionView, active: Boolean, anyDiminishing: Boolean): String? = when {
        q.stopped -> STOPPED_BY_HUMAN
        q.treeSize >= config.maxClaims -> "budget"
        !active && anyDiminishing -> "voi"
        else -> null
    }

    // ---------------------------------------------------------------- SPEC §3 "Exploration order", EXP-05

    /**
     * Model C: [c]'s value of information before round decay —
     * |d root / d c| × 4·p·(1 − p) ([uncertainty]). The root is 1: the question
     * itself is always worth its rounds. An unjudged p counts as ½ (factor 1);
     * a sensitivity the sensitivity layer has not delivered yet counts as
     * [DeliberationEngine.Config.FALLBACK_STRENGTH], middling, as a failed
     * strength judgment does for reach.
     */
    fun valueOf(c: ClaimView): Double =
        if (c.isRoot) 1.0
        else abs(c.sensitivity ?: DeliberationEngine.Config.FALLBACK_STRENGTH) * uncertainty(c.plausibility)

    /** Model C: the value of information of [c]'s next round: [valueOf] × roundDecay^(rounds run). */
    fun voiOf(c: ClaimView): Double = valueOf(c) * config.roundDecay.pow(c.rounds)

    /** Model C: [c]'s next round is worth less than `voiEpsilon`. */
    fun belowEpsilon(c: ClaimView): Boolean = voiOf(c) < config.voiEpsilon

    /** The queue priority of [c]'s next round: its value of information ([voiOf]); forced first (CTL-02). */
    fun priorityOf(c: ClaimView): Double =
        if (c.override == Override.EXPAND) DeliberationEngine.Config.FORCED_PRIORITY
        else voiOf(c)

    /**
     * Model C: how much settling a node could still move the answer —
     * |[sensitivity]| × 4·p·(1 − p) (the crux score, [valueOf] without the
     * fallbacks), or null when its sensitivity is not known yet.
     */
    fun cruxScore(sensitivity: Double?, plausibility: Double?): Double? =
        sensitivity?.let { abs(it) * uncertainty(plausibility) }

    /** An edge's strength for reach, [DeliberationEngine.Config.FALLBACK_STRENGTH] when unjudged, clamped to [0,1]. */
    fun strengthOf(strength: Double?): Double =
        (strength ?: DeliberationEngine.Config.FALLBACK_STRENGTH).coerceIn(0.0, 1.0)

    /**
     * EXP-05: reach decays by the edge strength; a failed judgment assumes
     * FALLBACK_STRENGTH. A link's reach is its argument's, so an argument
     * about a link has reach(the argument's parent) × strength(the argument's
     * edge) × strength(its own edge).
     */
    fun reachOf(parentReach: Double?, edgeStrength: Double?): Double = (parentReach ?: 1.0) * strengthOf(edgeStrength)

    /**
     * SPEC §3 "Exploration order" before model C, now shown only (the queue
     * follows [priorityOf]): reach × relevance × quality ×
     * [uncertainty] (plausibility), an unjudged factor counting 1. Without a
     * [plausibility] this is the argument's worth to its parent alone — what
     * its link's [linkContribution] is built from.
     */
    fun contribution(reach: Double, relevance: Double?, quality: Double?, plausibility: Double? = null): Double =
        reach * (relevance ?: 1.0) * (quality ?: 1.0) * uncertainty(plausibility)

    /**
     * Model B: how unsettled a claim of plausibility [p] still is, 4·p·(1 − p):
     * 1 at p = ½, 0 for a claim Jev judged certainly true or false — exploring
     * its premise further cannot move it. Unjudged (null) counts 1. The claim's
     * bearing on its parent is its link's business ([linkContribution]).
     */
    fun uncertainty(p: Double?): Double = p?.coerceIn(0.0, 1.0)?.let { 4 * it * (1 - it) } ?: 1.0

    /**
     * Model B: whether a candidate proposed on [side] of a claim of plausibility
     * [plausibility], which triage resolved to [action], is asked [Judge.bearing]:
     * only a con that triage would ADD, against a claim at or above
     * [BEARING_PLAUSIBILITY]. The caller also requires the claim to have a link.
     */
    fun asksBearing(plausibility: Double?, side: Side, action: TriageAction): Boolean =
        side == Polarity.ATTACK && action == TriageAction.ADD && plausibility != null && plausibility >= BEARING_PLAUSIBILITY

    /**
     * SPEC §3 "Links as claims": a link is worth exploring in proportion to
     * how much its argument can move the parent — the argument's contribution
     * — times how unsettled its strength s still is, 4·s·(1 − s): 1 at s = ½,
     * 0 for a link judged irrelevant or decisive. A clear-cut link is left
     * alone unless the human expands it. [argContribution] is the argument's
     * contribution *without* its own plausibility factor ([uncertainty]): a
     * well-believed argument's premise is settled, but whether it bears on its
     * parent is not (model B), so a link may outrank its own argument.
     */
    fun linkContribution(argContribution: Double?, argReach: Double?, edgeStrength: Double?): Double {
        val s = strengthOf(edgeStrength)
        return (argContribution ?: argReach ?: DeliberationEngine.Config.FALLBACK_STRENGTH) * 4 * s * (1 - s)
    }

    // ---------------------------------------------------------------- EXP-10 yield (shown; model C replaced its stop)

    /** EXP-10: a root round, or a round that asked for nothing, records no yield. */
    fun recordsYield(isRoot: Boolean, requested: Int): Boolean = !isRoot && requested > 0

    /**
     * EXP-10: the yield of a round that attached [attached] out of [requested]
     * asked-for arguments, triaged as [counts]: Σ (strength × relevance ×
     * quality) over the attached arguments × the share of triaged proposals
     * that were neither DUPLICATE nor DROP, per argument asked for.
     */
    fun roundYield(attached: List<AttachedValue>, counts: Map<TriageAction, Int>, requested: Int): Double {
        val value = attached.sumOf { n ->
            (n.strength ?: DeliberationEngine.Config.FALLBACK_STRENGTH) * (n.relevance ?: 1.0) * (n.quality ?: 1.0)
        }
        val triaged = counts.values.sum()
        val novelty = if (triaged == 0) 1.0
        else 1.0 - ((counts[TriageAction.DUPLICATE] ?: 0) + (counts[TriageAction.DROP] ?: 0)).toDouble() / triaged
        return value * novelty / requested
    }
}
