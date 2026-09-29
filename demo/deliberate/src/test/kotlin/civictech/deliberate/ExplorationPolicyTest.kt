package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.deliberate.DeliberationEngine.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exploration rules (SPEC §3: EXP-04..06, EXP-10, CTL-02/03/05) as pure
 * functions: every gate and branch of [ExplorationPolicy], table-driven, with
 * no engine, no threads and no waits. [DeliberationEngineTest] keeps the
 * end-to-end cases that exercise the same rules through real rounds.
 */
class ExplorationPolicyTest {

    private val defaults = ExplorationPolicy(Config())
    private val SUPPORT = Polarity.SUPPORT
    private val ATTACK = Polarity.ATTACK

    /** One row of a table: [name] names the case in a failure. */
    private data class Case<I, O>(val name: String, val input: I, val expected: O)

    private fun <I, O> table(vararg cases: Case<I, O>, actual: (I) -> O) {
        for (c in cases) assertEquals(c.expected, actual(c.input), c.name)
    }

    private fun near(expected: Double, actual: Double, what: String) =
        assertEquals(expected, actual, 1e-12, what)

    /** Model C: sensitivity 0.5 at p = ½ — value of information 0.5. */
    private val fresh = ClaimView(depth = 1, contribution = 0.5, sensitivity = 0.5, plausibility = 0.5)
    private val q = QuestionView(treeSize = 10)

    // ---------------------------------------------------------------- scheduleGate (EXP-05/06, model C, links)

    @Test
    fun `schedule gate — every branch in order`() {
        val full = q.copy(treeSize = 180)
        val deep = ExplorationPolicy(Config(maxDepth = 5))
        table(
            Case("the root is never gated", fresh.copy(isRoot = true, depth = 99, sensitivity = 0.0) to full, null),
            Case("EXPAND skips every gate (CTL-02)", fresh.copy(override = Override.EXPAND, depth = 99) to full, null),
            Case("a forced round skips every gate (CTL-02)", fresh.copy(forceRound = true, sensitivity = 0.0) to full, null),
            Case("a link is gated like a claim when link exploration is on", fresh.copy(isLink = true) to q, null),
            Case("beyond an explicit maxDepth is DEPTH_LIMIT", fresh.copy(depth = 6) to q, Status.DEPTH_LIMIT),
            Case("at maxDepth passes", fresh.copy(depth = 5) to q, null),
            Case("depth wins over the value of information", fresh.copy(depth = 6, sensitivity = 0.0) to q, Status.DEPTH_LIMIT),
            Case("a full tree is BUDGET (EXP-06)", fresh to full, Status.BUDGET),
            // Changed by model C: the relevance floor (PRUNED below contribution 0.10) is gone; the
            // hard cap now wins over a low value of information.
            Case("budget wins over the value of information", fresh.copy(sensitivity = 0.0) to full, Status.BUDGET),
            Case("a value of information below 0.01 is DIMINISHING", fresh.copy(sensitivity = 0.0099) to q, Status.DIMINISHING),
            Case("at 0.01 it passes", fresh.copy(sensitivity = 0.01) to q, null),
            Case("a settled premise has no value: p = 0.999", fresh.copy(sensitivity = 1.0, plausibility = 0.999) to q, Status.DIMINISHING),
            Case("a low contribution no longer prunes", fresh.copy(contribution = 0.01) to q, null),
            Case("unknown sensitivity counts FALLBACK_STRENGTH", fresh.copy(sensitivity = null) to q, null),
            Case("pausing is not a gate (the engine holds, CTL-05)", fresh to q.copy(paused = true), null),
        ) { (c, qv) -> deep.scheduleGate(c, qv) }
        assertEquals(null, defaults.scheduleGate(fresh.copy(depth = 99), q), "no depth limit by default")
        assertEquals(null, ExplorationPolicy(Config(voiEpsilon = 0.0)).scheduleGate(fresh.copy(sensitivity = 0.0), q), "ε = 0 disables the stop")
    }

    @Test
    fun `schedule gate — link exploration off prunes a link and nothing else`() {
        val off = ExplorationPolicy(Config(exploreLinks = false))
        assertEquals(Status.PRUNED, off.scheduleGate(fresh.copy(isLink = true), q))
        assertEquals(null, off.scheduleGate(fresh, q))
        assertEquals(null, off.scheduleGate(fresh.copy(isLink = true, override = Override.EXPAND), q), "EXPAND still explores a link")
    }

    // ---------------------------------------------------------------- start gates (EXP-06 then model C)

    @Test
    fun `start gates — budget, then value of information, and a forced round passes`() {
        table(
            Case("room left", false to 179, null),
            Case("a full tree", false to 180, Status.BUDGET),
            Case("forced passes budget", true to 180, null),
        ) { (forced, size) -> defaults.startBudgetGate(forced, size) }
        table(
            Case("worth a round", fresh, null),
            Case("below ε", fresh.copy(sensitivity = 0.001), Status.DIMINISHING),
            Case("forced passes the value of information", fresh.copy(sensitivity = 0.001, forceRound = true), null),
        ) { defaults.startVoiGate(it) }
    }

    // ---------------------------------------------------------------- terminalStatus

    @Test
    fun `terminal status — STOP, saturation, round limit, budget, value of information`() {
        val bothSaturated = fresh.copy(jevSaturated = setOf(SUPPORT, ATTACK))
        val full = q.copy(treeSize = 180)
        table(
            Case("a round left", fresh to q, null),
            Case("STOP ends it (CTL-03)", fresh.copy(override = Override.STOP) to q, Status.STOPPED),
            Case("STOP ends even a forced round", fresh.copy(override = Override.STOP, forceRound = true) to q, Status.STOPPED),
            Case("STOP wins over saturation", bothSaturated.copy(override = Override.STOP) to q, Status.STOPPED),
            Case("both sides saturated", bothSaturated to q, Status.SATURATED),
            Case("one side saturated leaves the other", fresh.copy(jevSaturated = setOf(SUPPORT)) to q, null),
            Case("a forced round ignores saturation", bothSaturated.copy(forceRound = true) to q, null),
            Case("rounds used up", fresh.copy(rounds = 3, roundLimit = 3) to q, Status.ROUND_LIMIT),
            Case("a forced round ignores the round limit", fresh.copy(rounds = 3, roundLimit = 3, forceRound = true) to q, null),
            Case("saturation wins over the round limit", bothSaturated.copy(rounds = 3, roundLimit = 3) to q, Status.SATURATED),
            Case("round limit wins over the budget", fresh.copy(rounds = 3, roundLimit = 3) to full, Status.ROUND_LIMIT),
            Case("a full tree", fresh to full, Status.BUDGET),
            Case("a forced round ignores the budget", fresh.copy(forceRound = true) to full, null),
            // 0.5 × 0.5^5 = 0.0156 still worth a round; × 0.5^6 = 0.0078 is not.
            Case("round decay keeps a valuable claim going", fresh.copy(rounds = 5, roundLimit = 9) to q, null),
            Case("round decay takes its value below ε", fresh.copy(rounds = 6, roundLimit = 9) to q, Status.DIMINISHING),
            Case("a forced round ignores the value of information", fresh.copy(sensitivity = 0.0, forceRound = true) to q, null),
        ) { (c, qv) -> defaults.terminalStatus(c, qv) }
    }

    // ---------------------------------------------------------------- EXP-04 caps and saturation

    @Test
    fun `saturated sides — the cap, and Jev only for a side that is not behind`() {
        val root = ClaimView(isRoot = true, depth = 0)
        table(
            Case("nothing saturated", fresh, emptySet()),
            Case("a child side at its cap of 3", fresh.copy(pros = 3), setOf(SUPPORT)),
            Case("a child side below its cap", fresh.copy(pros = 2), emptySet()),
            Case("the root's cap is 6", root.copy(pros = 3, cons = 6), setOf(ATTACK)),
            Case("Jev saturated and ahead", fresh.copy(pros = 2, cons = 1, jevSaturated = setOf(SUPPORT)), setOf(SUPPORT)),
            Case("Jev saturated and level", fresh.copy(pros = 1, cons = 1, jevSaturated = setOf(ATTACK)), setOf(ATTACK)),
            // The engine case: 2 pro / 1 con, Jev calls both saturated — the con side stays open.
            Case("a side behind is never saturated by Jev alone", fresh.copy(pros = 2, cons = 1, jevSaturated = setOf(SUPPORT, ATTACK)), setOf(SUPPORT)),
            Case("the cap saturates a side even when behind", fresh.copy(pros = 3, cons = 3), setOf(SUPPORT, ATTACK)),
        ) { defaults.saturatedSides(it) }
    }

    @Test
    fun `next sides, Jev threshold, caps, room and fits`() {
        assertEquals(listOf(ATTACK), defaults.nextSides(fresh.copy(pros = 3)))
        assertEquals(listOf(SUPPORT, ATTACK), defaults.nextSides(fresh.copy(pros = 3, cons = 3, forceRound = true)), "forced asks both")
        table(
            Case("at the threshold saturates", 0.22, true),
            Case("just below does not", 0.2199, false),
            Case("certainty saturates", 1.0, true),
        ) { defaults.jevSaturates(it) }
        assertEquals(6, defaults.capOf(ClaimView(isRoot = true)))
        assertEquals(3, defaults.capOf(fresh))
        assertEquals(1, defaults.roomOn(fresh.copy(pros = 2), SUPPORT))
        assertEquals(3, defaults.roomOn(fresh.copy(pros = 2), ATTACK))
        assertEquals(-1, defaults.roomOn(fresh.copy(cons = 4), ATTACK), "overfilled")
        assertTrue(defaults.fits(fresh.copy(cons = 2), ATTACK, forced = false))
        assertTrue(!defaults.fits(fresh.copy(cons = 3), ATTACK, forced = false))
        assertTrue(defaults.fits(fresh.copy(cons = 3), ATTACK, forced = true), "a forced round has its own allowance")
    }

    // ---------------------------------------------------------------- CTL-05, EXP-06

    @Test
    fun `held, reserve and stoppedBy`() {
        table(
            Case("paused holds", fresh to q.copy(paused = true), true),
            Case("paused does not hold a forced round", fresh.copy(forceRound = true) to q.copy(paused = true), false),
            Case("running does not hold", fresh to q, false),
        ) { (c, qv) -> defaults.held(c, qv) }
        table(
            Case("room left", 179 to false, true),
            Case("full", 180 to false, false),
            Case("full but forced", 180 to true, true),
        ) { (n, forced) -> defaults.mayReserve(n, forced) }
        table(
            Case("growing", Triple(QuestionView(treeSize = 10), true, false), null),
            Case("the hard cap", Triple(QuestionView(treeSize = 180), false, true), "budget"),
            Case("the hard cap, still running", Triple(QuestionView(treeSize = 180), true, false), "budget"),
            Case("no work left and a node below ε", Triple(QuestionView(treeSize = 10), false, true), "voi"),
            Case("a node below ε but work left", Triple(QuestionView(treeSize = 10), true, true), null),
            Case("finished without the value-of-information stop", Triple(QuestionView(treeSize = 10), false, false), null),
        ) { (qv, active, dim) -> defaults.stoppedBy(qv, active, dim) }
    }

    @Test
    fun `finish — STOP wins, BUDGET stands whether or not a round ran`() {
        table(
            Case("a plain status", fresh to Status.SATURATED, Finish(Status.SATURATED)),
            Case("STOP wins (CTL-03)", fresh.copy(override = Override.STOP) to Status.ROUND_LIMIT, Finish(Status.STOPPED)),
            Case("BUDGET before any round", fresh to Status.BUDGET, Finish(Status.BUDGET)),
            Case(
                "BUDGET after a round (EXP-06)",
                fresh.copy(rounds = 1) to Status.BUDGET,
                Finish(Status.BUDGET),
            ),
            Case("FAILED keeps its status", fresh.copy(rounds = 1) to Status.FAILED, Finish(Status.FAILED)),
        ) { (c, s) -> defaults.finish(c, s) }
    }

    // ---------------------------------------------------------------- SPEC §3 "Exploration order", EXP-05

    /**
     * Model C rewrote this test: the priority was contribution (reach × relevance ×
     * quality) decayed per round, with reach and FALLBACK_STRENGTH as fallbacks; it is
     * now |sensitivity| × 4·p·(1 − p) decayed per round. Changed expectations:
     * contribution and reach no longer enter it at all; an unknown sensitivity (not an
     * unknown contribution) falls back to FALLBACK_STRENGTH; the root is 1 whatever its
     * sensitivity or plausibility.
     */
    @Test
    fun `priority — forced first, else sensitivity times 4p(1-p) decayed per round`() {
        near(Config.FORCED_PRIORITY, defaults.priorityOf(fresh.copy(override = Override.EXPAND, rounds = 5)), "EXPAND")
        near(0.8, defaults.priorityOf(fresh.copy(sensitivity = 0.8)), "p = ½: the sensitivity itself")
        near(0.8 * 0.36, defaults.priorityOf(fresh.copy(sensitivity = 0.8, plausibility = 0.9)), "p = 0.9 keeps 0.36")
        near(0.8 * 0.36, defaults.priorityOf(fresh.copy(sensitivity = -0.8, plausibility = 0.1)), "a con: |d root / d c|")
        near(0.0, defaults.priorityOf(fresh.copy(sensitivity = 0.8, plausibility = 1.0)), "a settled claim")
        near(0.8, defaults.priorityOf(fresh.copy(sensitivity = 0.8, plausibility = null)), "unjudged p counts ½")
        near(0.2, defaults.priorityOf(fresh.copy(sensitivity = 0.8, rounds = 2)), "0.8 × 0.5²")
        near(0.8, defaults.priorityOf(fresh.copy(sensitivity = 0.8, contribution = 0.01, reach = 0.01)), "contribution and reach play no part")
        near(Config.FALLBACK_STRENGTH, defaults.priorityOf(fresh.copy(sensitivity = null)), "sensitivity not delivered yet")
        near(1.0, defaults.priorityOf(ClaimView(isRoot = true, depth = 0, sensitivity = 0.3, plausibility = 0.95)), "the root is 1")
        near(0.25, defaults.priorityOf(ClaimView(isRoot = true, depth = 0, rounds = 2)), "the root decays per round")
        near(0.8, ExplorationPolicy(Config(roundDecay = 1.0)).priorityOf(fresh.copy(sensitivity = 0.8, rounds = 3)), "no decay")
        // A link's p is its strength: an undecided link keeps its edge's sensitivity whole.
        near(0.4, defaults.priorityOf(fresh.copy(isLink = true, sensitivity = 0.4, plausibility = 0.5)), "an undecided link")
        near(0.0, defaults.priorityOf(fresh.copy(isLink = true, sensitivity = 0.4, plausibility = 1.0)), "a decisive link")
        // The crux score is the same product without fallbacks.
        near(0.8 * 0.36, defaults.cruxScore(-0.8, 0.9)!!, "crux score")
        assertEquals(null, defaults.cruxScore(null, 0.5), "no crux score without a sensitivity")
    }

    @Test
    fun `reach and contribution`() {
        near(0.8, defaults.reachOf(1.0, 0.8), "root × strength")
        near(0.32, defaults.reachOf(0.4, 0.8), "decays along the path")
        near(0.5, defaults.reachOf(null, null), "unjudged edge assumes the fallback")
        near(1.0, defaults.reachOf(1.0, 2.0), "strength clamped to 1")
        near(0.0, defaults.reachOf(1.0, -1.0), "strength clamped to 0")
        near(0.12, defaults.contribution(0.8, 0.5, 0.3), "reach × relevance × quality")
        near(0.8, defaults.contribution(0.8, null, null), "unjudged factors count 1")
    }

    @Test
    fun `model B — contribution is damped by the plausibility uncertainty 4·p·(1 − p)`() {
        listOf(
            Case("p = 0: a claim known false contributes nothing", 0.0, 0.0),
            Case("p = ½ keeps it whole", 0.5, 0.12),
            Case("p = 1: a claim known true contributes nothing", 1.0, 0.0),
            Case("p = 0.9 keeps 0.36 of it", 0.9, 0.12 * 0.36),
            Case("unjudged plausibility counts 1", null, 0.12),
        ).forEach { (name, p, expected) -> near(expected, defaults.contribution(0.8, 0.5, 0.3, p), name) }
        near(0.0, defaults.uncertainty(0.0), "4p(1-p) at 0")
        near(1.0, defaults.uncertainty(0.5), "4p(1-p) at ½")
        near(0.0, defaults.uncertainty(1.0), "4p(1-p) at 1")
        near(0.0, defaults.uncertainty(1.5), "clamped to [0,1]")
    }

    @Test
    fun `model B — the bearing question gates on a con that triage would ADD, at or above 0_8`() {
        val at = ExplorationPolicy.BEARING_PLAUSIBILITY
        assertEquals(0.8, at)
        table(
            Case("at the threshold", Triple(at, ATTACK, TriageAction.ADD), true),
            Case("above it", Triple(1.0, ATTACK, TriageAction.ADD), true),
            Case("just below it: unchanged triage path", Triple(0.7999, ATTACK, TriageAction.ADD), false),
            Case("unjudged plausibility", Triple(null, ATTACK, TriageAction.ADD), false),
            Case("a pro is never asked", Triple(0.9, SUPPORT, TriageAction.ADD), false),
            Case("a con triage placed elsewhere keeps its verdict", Triple(0.9, ATTACK, TriageAction.DUPLICATE), false),
            Case("a con triage dropped stays dropped", Triple(0.9, ATTACK, TriageAction.DROP), false),
        ) { (p, side, action) -> defaults.asksBearing(p, side, action) }
    }

    @Test
    fun `link contribution — the argument's contribution times 4·s·(1 − s)`() {
        listOf(
            Case("undecided strength keeps it whole", Triple(0.6, 0.9, 0.5), 0.6),
            Case("a decisive link is left alone", Triple(0.6, 0.9, 1.0), 0.0),
            Case("an irrelevant link is left alone", Triple(0.6, 0.9, 0.0), 0.0),
            Case("s = 0.8 keeps 0.64 of it", Triple(0.5, 0.9, 0.8), 0.32),
            Case("unjudged strength assumes ½", Triple(0.6, 0.9, null), 0.6),
            Case("no contribution falls back to reach", Triple(null, 0.9, 0.5), 0.9),
            Case("nothing falls back to FALLBACK_STRENGTH", Triple(null, null, 0.5), 0.5),
        ).forEach { (name, input, expected) ->
            val (contribution, reach, s) = input
            near(expected, defaults.linkContribution(contribution, reach, s), name)
        }
    }

    // ---------------------------------------------------------------- EXP-10 yield

    @Test
    fun `round yield is value x novelty per argument asked`() {
        // The engine case: 4 asked, triage keeps 3 and drops 1 — novelty 3/4.
        val three = List(3) { AttachedValue(0.8, 0.5, 0.9) }
        val counts = mapOf(TriageAction.ADD to 3, TriageAction.DROP to 1)
        near(3 * 0.8 * 0.5 * 0.9 * 0.75 / 4, defaults.roundYield(three, counts, 4), "value × novelty / asked")
        near(0.5 / 2, defaults.roundYield(listOf(AttachedValue(null, null, null)), emptyMap(), 2), "fallback strength, nothing triaged")
        near(0.0, defaults.roundYield(emptyList(), mapOf(TriageAction.DUPLICATE to 2), 2), "only duplicates")
        near(
            0.5 * 0.5 / 1,
            defaults.roundYield(listOf(AttachedValue(0.5, 1.0, 1.0)), mapOf(TriageAction.ADD to 1, TriageAction.DUPLICATE to 1), 1),
            "half novel",
        )
        table(
            Case("a root round records none", true to 4, false),
            Case("a round that asked nothing records none", false to 0, false),
            Case("a child round records one", false to 1, true),
        ) { (isRoot, requested) -> defaults.recordsYield(isRoot, requested) }
    }
}
