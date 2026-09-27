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

    private val fresh = ClaimView(depth = 1, contribution = 0.5)
    private val q = QuestionView(treeSize = 10)

    // ---------------------------------------------------------------- scheduleGate (EXP-05/06, EXP-10, links)

    @Test
    fun `schedule gate — every branch in order`() {
        val full = q.copy(treeSize = 180)
        table(
            Case("the root is never gated", fresh.copy(isRoot = true, depth = 99, contribution = 0.0) to full.copy(diminished = true), null),
            Case("EXPAND skips every gate (CTL-02)", fresh.copy(override = Override.EXPAND, depth = 99) to full, null),
            Case("a forced round skips every gate (CTL-02)", fresh.copy(forceRound = true, contribution = 0.0) to full, null),
            Case("a link is gated like a claim when link exploration is on", fresh.copy(isLink = true) to q, null),
            Case("beyond maxDepth is DEPTH_LIMIT", fresh.copy(depth = 6) to q, Status.DEPTH_LIMIT),
            Case("at maxDepth passes", fresh.copy(depth = 5) to q, null),
            Case("depth wins over influence", fresh.copy(depth = 6, contribution = 0.0) to q, Status.DEPTH_LIMIT),
            Case("below the 0.10 floor is PRUNED", fresh.copy(contribution = 0.099) to q, Status.PRUNED),
            Case("at the floor passes", fresh.copy(contribution = 0.10) to q, null),
            Case("a failed assessment falls back to reach", fresh.copy(contribution = null, reach = 0.05) to q, Status.PRUNED),
            Case("no reach either falls back to FALLBACK_STRENGTH", fresh.copy(contribution = null, reach = null) to q, null),
            Case("influence runs before the budget: PRUNED, not BUDGET", fresh.copy(contribution = 0.01) to full, Status.PRUNED),
            Case("a full tree is BUDGET (EXP-06)", fresh to full, Status.BUDGET),
            Case("budget wins over diminishing", fresh to full.copy(diminished = true), Status.BUDGET),
            Case("a diminished question is DIMINISHING (EXP-10)", fresh to q.copy(diminished = true), Status.DIMINISHING),
            Case("pausing is not a gate (the engine holds, CTL-05)", fresh to q.copy(paused = true), null),
        ) { (c, qv) -> defaults.scheduleGate(c, qv) }
    }

    @Test
    fun `schedule gate — link exploration off prunes a link and nothing else`() {
        val off = ExplorationPolicy(Config(exploreLinks = false))
        assertEquals(Status.PRUNED, off.scheduleGate(fresh.copy(isLink = true), q))
        assertEquals(null, off.scheduleGate(fresh, q))
        assertEquals(null, off.scheduleGate(fresh.copy(isLink = true, override = Override.EXPAND), q), "EXPAND still explores a link")
    }

    // ---------------------------------------------------------------- start gates (EXP-06 then EXP-10)

    @Test
    fun `start gates — budget, then diminishing, and a forced round passes`() {
        table(
            Case("room left", false to 179, null),
            Case("a full tree", false to 180, Status.BUDGET),
            Case("forced passes budget", true to 180, null),
        ) { (forced, size) -> defaults.startBudgetGate(forced, size) }
        table(
            Case("growing", false to false, null),
            Case("a stopped question", false to true, Status.DIMINISHING),
            Case("forced passes diminishing", true to true, null),
        ) { (forced, diminished) -> defaults.startDiminishingGate(forced, diminished) }
    }

    // ---------------------------------------------------------------- terminalStatus

    @Test
    fun `terminal status — STOP, saturation, round limit, budget, diminishing`() {
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
            Case("a stopped question", fresh to q.copy(diminished = true), Status.DIMINISHING),
            Case("a forced round ignores the yield stop", fresh.copy(forceRound = true) to q.copy(diminished = true), null),
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
            Case("growing", QuestionView(treeSize = 10), null),
            Case("budget", QuestionView(treeSize = 180, diminished = true), "budget"),
            Case("diminishing", QuestionView(treeSize = 10, diminished = true), "diminishing"),
        ) { defaults.stoppedBy(it) }
    }

    @Test
    fun `finish — STOP wins, and budget after a round reads ROUND_LIMIT with an error`() {
        table(
            Case("a plain status", fresh to Status.SATURATED, Finish(Status.SATURATED)),
            Case("STOP wins (CTL-03)", fresh.copy(override = Override.STOP) to Status.ROUND_LIMIT, Finish(Status.STOPPED)),
            Case("BUDGET before any round", fresh to Status.BUDGET, Finish(Status.BUDGET)),
            Case(
                "BUDGET after a round (EXP-06)",
                fresh.copy(rounds = 1) to Status.BUDGET,
                Finish(Status.ROUND_LIMIT, Config.BUDGET_EXHAUSTED),
            ),
            Case("FAILED keeps its status", fresh.copy(rounds = 1) to Status.FAILED, Finish(Status.FAILED)),
        ) { (c, s) -> defaults.finish(c, s) }
    }

    // ---------------------------------------------------------------- SPEC §3 "Exploration order", EXP-05

    @Test
    fun `priority — forced first, else contribution decayed per round`() {
        near(Config.FORCED_PRIORITY, defaults.priorityOf(fresh.copy(override = Override.EXPAND, rounds = 5)), "EXPAND")
        near(0.8, defaults.priorityOf(fresh.copy(contribution = 0.8)), "a fresh claim")
        near(0.2, defaults.priorityOf(fresh.copy(contribution = 0.8, rounds = 2)), "0.8 × 0.5²")
        near(0.3, defaults.priorityOf(fresh.copy(contribution = null, reach = 0.3)), "reach fallback")
        near(Config.FALLBACK_STRENGTH, defaults.priorityOf(fresh.copy(contribution = null, reach = null)), "strength fallback")
        near(0.8, ExplorationPolicy(Config(roundDecay = 1.0)).priorityOf(fresh.copy(contribution = 0.8, rounds = 3)), "no decay")
    }

    @Test
    fun `reach and contribution`() {
        near(0.8, defaults.reachOf(1.0, 0.8), "root × strength")
        near(0.32, defaults.reachOf(0.4, 0.8), "decays along the path")
        near(0.5, defaults.reachOf(null, null), "unjudged edge assumes the fallback")
        near(1.0, defaults.reachOf(1.0, 2.0), "strength clamped to 1")
        near(0.0, defaults.reachOf(1.0, -1.0), "strength clamped to 0")
        // The engine case: 0.8 × 0.5 × 0.3 = 0.12 ≥ 0.10 is explored, 0.8 × 0.5 × 0.2 = 0.08 is PRUNED.
        near(0.12, defaults.contribution(0.8, 0.5, 0.3), "reach × relevance × quality")
        near(0.08, defaults.contribution(0.8, 0.5, 0.2), "below the floor")
        near(0.8, defaults.contribution(0.8, null, null), "unjudged factors count 1")
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

    @Test
    fun `yield stop needs the claim floor, twice the window of rounds, and a real decline`() {
        val stop = DeliberationEngine.YieldStop(window = 2, ratio = 0.6, minClaims = 10)
        val decaying = listOf(1.0, 1.0, 0.1, 0.1)
        assertTrue(stop.diminished(decaying, claims = 10))
        assertTrue(!stop.diminished(decaying, claims = 9), "below the claim floor")
        assertTrue(!stop.diminished(decaying.drop(1), claims = 50), "fewer than 2 × window rounds")
        assertTrue(!stop.diminished(listOf(0.5, 0.5, 0.5, 0.5, 0.5), claims = 50), "flat yields")
        // recent 0.6 is not below 0.6 × 1.0
        assertTrue(!stop.diminished(listOf(1.0, 1.0, 0.6, 0.6), claims = 50))
        assertTrue(!stop.diminished(listOf(0.0, 0.0, 0.0, 0.0), claims = 50), "nothing to decline from")
    }

    @Test
    fun `yields diminish only with a yield stop and below the budget`() {
        val policy = ExplorationPolicy(Config(yieldStop = DeliberationEngine.YieldStop(window = 2, ratio = 0.6, minClaims = 10)))
        val decaying = listOf(1.0, 1.0, 0.1, 0.1)
        table(
            Case("a real decline", decaying to 20, true),
            Case("flat", listOf(0.5, 0.5, 0.5, 0.5) to 20, false),
            Case("below the claim floor", decaying to 9, false),
            Case("at the budget the budget explains the stop", decaying to 180, false),
        ) { (ys, size) -> policy.yieldsDiminished(ys, size) }
        assertTrue(!ExplorationPolicy(Config(yieldStop = null)).yieldsDiminished(decaying, 20), "yield stop off")
    }

    @Test
    fun `yield stop halts queued and waiting work that nobody forced`() {
        table(
            Case("queued", fresh, true),
            Case("waiting for its next round", fresh.copy(status = Status.EXPLORING, waiting = true), true),
            Case("a round in flight", fresh.copy(status = Status.EXPLORING), false),
            Case("judging", fresh.copy(status = Status.JUDGING), false),
            Case("finished", fresh.copy(status = Status.ROUND_LIMIT), false),
            Case("forced", fresh.copy(forceRound = true), false),
            Case("EXPAND", fresh.copy(override = Override.EXPAND), false),
            Case("mid-rewrite", fresh.copy(rewriteInFlight = true), false),
        ) { defaults.haltable(it) }
        val waiting = fresh.copy(status = Status.EXPLORING, waiting = true)
        table(
            Case("nothing to halt", emptyList(), false),
            Case("only waiting claims: the question had exhausted itself", listOf(waiting), false),
            Case("a queued first round is prevented", listOf(waiting, fresh), true),
        ) { defaults.yieldStopHalts(it) }
        assertEquals(Status.DIMINISHING, defaults.haltedStatus(fresh))
        assertEquals(Status.STOPPED, defaults.haltedStatus(fresh.copy(override = Override.STOP)))
    }
}
