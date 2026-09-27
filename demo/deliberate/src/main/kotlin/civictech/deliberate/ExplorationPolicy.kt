package civictech.deliberate

import civictech.agora.cell.Polarity
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
    /** EXP-10: the question stopped because its returns diminished. */
    val diminished: Boolean = false,
    /** CTL-05. */
    val paused: Boolean = false,
)

/** EXP-10: one argument a round attached, as its round yield counts it. */
internal data class AttachedValue(val strength: Double?, val relevance: Double?, val quality: Double?)

/** How [DeliberationEngine] ends a claim's expansion: its final status and, if any, the error to record. */
internal data class Finish(val status: Status, val error: String? = null)

/**
 * The exploration rules of SPEC §3 (EXP-04..06, EXP-10, CTL-02/03/05) as
 * pure functions over [ClaimView] and [QuestionView]: no locks, no threads,
 * no I/O. The engine takes the views under its lock and applies the answers.
 */
internal class ExplorationPolicy(val config: DeliberationEngine.Config) {

    companion object {
        val FINISHED = setOf(
            Status.SATURATED, Status.ROUND_LIMIT, Status.PRUNED, Status.DEPTH_LIMIT,
            Status.BUDGET, Status.DIMINISHING, Status.STOPPED, Status.FAILED,
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
     * The gate that ends a QUEUED claim before its first round without any
     * judgment (EXP-05; CTL-02 skips them), or null when it is queued: links
     * off, beyond `maxDepth` (DEPTH_LIMIT), below the `minInfluence` floor
     * (PRUNED), the budget (BUDGET, EXP-06) and diminishing returns (EXP-10).
     */
    fun scheduleGate(c: ClaimView, q: QuestionView): Status? =
        if (c.isRoot || c.override == Override.EXPAND || c.forceRound) null
        else when {
            c.isLink && !config.exploreLinks -> Status.PRUNED
            c.depth > config.maxDepth -> Status.DEPTH_LIMIT
            contributionOf(c) < config.minInfluence -> Status.PRUNED
            q.treeSize >= config.maxClaims -> Status.BUDGET
            q.diminished -> Status.DIMINISHING
            else -> null
        }

    /** EXP-06 after EXP-05, once a dequeued claim was judged. A forced round (CTL-02) passes. */
    fun startBudgetGate(forceRound: Boolean, treeSize: Int): Status? =
        Status.BUDGET.takeIf { !forceRound && treeSize >= config.maxClaims }

    /** EXP-10 after the budget gate, once a dequeued claim was judged. A forced round (CTL-02) passes. */
    fun startDiminishingGate(forceRound: Boolean, diminished: Boolean): Status? =
        Status.DIMINISHING.takeIf { !forceRound && diminished }

    /**
     * The status that ends [c]'s expansion before its next round, or null if
     * it gets one. A forced round (CTL-02) ignores the round limit, saturation
     * and the budget.
     */
    fun terminalStatus(c: ClaimView, q: QuestionView): Status? = when {
        c.override == Override.STOP -> Status.STOPPED
        nextSides(c).isEmpty() -> Status.SATURATED
        !c.forceRound && c.rounds >= c.roundLimit -> Status.ROUND_LIMIT
        !c.forceRound && q.treeSize >= config.maxClaims -> Status.BUDGET
        !c.forceRound && q.diminished -> Status.DIMINISHING
        else -> null
    }

    /**
     * How a claim ends when its expansion stops with [status]. A STOP that
     * raced the last round boundary still wins (CTL-03). EXP-06: a claim that
     * already ran a round and then meets the budget ends ROUND_LIMIT with
     * error [DeliberationEngine.Config.BUDGET_EXHAUSTED]; BUDGET is kept for
     * a claim that would have expanded but never did.
     */
    fun finish(c: ClaimView, status: Status): Finish = when {
        c.override == Override.STOP -> Finish(Status.STOPPED)
        status == Status.BUDGET && c.rounds > 0 -> Finish(Status.ROUND_LIMIT, DeliberationEngine.Config.BUDGET_EXHAUSTED)
        else -> Finish(status)
    }

    /** EXP-06: whether a question holding [treeSize] claims may take one more; a forced round (CTL-02) always may. */
    fun mayReserve(treeSize: Int, forced: Boolean): Boolean = forced || treeSize < config.maxClaims

    /** Why a question stopped growing early, if it did (QuestionDto.stoppedBy). */
    fun stoppedBy(q: QuestionView): String? = when {
        q.treeSize >= config.maxClaims -> "budget"
        q.diminished -> "diminishing"
        else -> null
    }

    // ---------------------------------------------------------------- SPEC §3 "Exploration order", EXP-05

    /** A claim whose assessment failed falls back to its reach (EXP-05), then to the fallback strength. */
    fun contributionOf(c: ClaimView): Double = c.contribution ?: c.reach ?: DeliberationEngine.Config.FALLBACK_STRENGTH

    /** The queue priority of [c]'s next round: its contribution × roundDecay^(rounds run); forced first (CTL-02). */
    fun priorityOf(c: ClaimView): Double =
        if (c.override == Override.EXPAND) DeliberationEngine.Config.FORCED_PRIORITY
        else contributionOf(c) * config.roundDecay.pow(c.rounds)

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
     * SPEC §3 "Exploration order": reach × relevance × quality ×
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

    // ---------------------------------------------------------------- EXP-10 yield

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

    /** EXP-10: [yields] have diminished ([DeliberationEngine.YieldStop]) in a question below its budget. */
    fun yieldsDiminished(yields: List<Double>, treeSize: Int): Boolean {
        val stop = config.yieldStop ?: return false
        return treeSize < config.maxClaims && stop.diminished(yields, treeSize)
    }

    /** EXP-10: a claim the yield stop halts — queued or waiting work that no human forced and no rewrite holds. */
    fun haltable(c: ClaimView): Boolean =
        !c.forceRound && c.override != Override.EXPAND && !c.rewriteInFlight &&
            (c.status == Status.QUEUED || (c.status == Status.EXPLORING && c.waiting))

    /**
     * EXP-10: a decline observed only after the last work finished did not
     * stop the question. It stops only when a first round that was actually
     * queued is prevented from starting.
     */
    fun yieldStopHalts(halted: List<ClaimView>): Boolean = halted.any { it.status == Status.QUEUED }

    /** EXP-10: the status a halted claim ends with; a STOP override keeps STOPPED (CTL-03). */
    fun haltedStatus(c: ClaimView): Status = if (c.override == Override.STOP) Status.STOPPED else Status.DIMINISHING
}
