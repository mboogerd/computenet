package civictech.oracle.shrink

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a shrink is allowed to spend (`ORA1 §SHRINK-03`): a candidate count **and** a wall
 * clock, either of which exhausts the budget on its own.
 *
 * ## Why both, rather than either alone
 *
 * They bound different things. A candidate count bounds how much *work the shrinker chooses* to
 * do, and it is the reproducible one: two runs of the same shrink spend the same candidates, so a
 * count-bounded shrink is deterministic and its result is stable across machines. A wall clock
 * bounds how long *one candidate* can take, which a count cannot: a candidate is a whole
 * differential run, and its cost is a function of script length and topology depth, so a case
 * whose runs take a second each turns a 500-candidate budget into eight minutes. A shrink is
 * something a failing sweep runs on the way to reporting, so it needs the second bound to stay
 * inside a test's patience — and the first so that what it produced is reproducible when it did
 * finish inside both.
 *
 * The wall clock is checked *between* candidates, never inside one: interrupting a running
 * differential case would leave a half-driven `SimWorld` and an outcome nothing could be
 * concluded from. So the real bound is [wallClock] plus one candidate's cost, and that is the
 * honest reading of it — not a hard deadline.
 *
 * ## Defaults
 *
 * [DEFAULT_CANDIDATES] and [DEFAULT_WALL_CLOCK] are sized against the one measurement this
 * module has: `OracleSweep`'s KDoc records ~3 ms per generated case at BS-1 size (200 script
 * ops, depth 1..4) on an Apple-silicon laptop, with a 4x spread measured across two machines. At
 * that figure 500 candidates is roughly 1.5 s, and the 30 s clock is what stops a case an order
 * of magnitude more expensive than BS-1 from turning a shrink into a hang. Both are a starting
 * point a caller overrides, not a claim about any particular case: neither figure was measured on
 * a *shrink*, because a shrink's cost is dominated by the case it is given.
 */
data class ShrinkBudget(
    /** How many reduction candidates may be executed. */
    val maxCandidates: Int = DEFAULT_CANDIDATES,
    /** How long the whole shrink may spend, checked between candidates. */
    val wallClock: Duration = DEFAULT_WALL_CLOCK,
) {
    init {
        require(maxCandidates > 0) { "maxCandidates must be positive: $maxCandidates" }
        require(wallClock.isPositive()) { "wallClock must be positive: $wallClock" }
    }

    /** A fresh [Meter] over this budget, its clock starting now. */
    fun meter(): Meter = Meter(this)

    /**
     * One shrink's spend against one [ShrinkBudget] — the mutable half, kept out of the budget
     * itself so a budget stays a comparable value a caller can hold, log and reuse.
     */
    class Meter internal constructor(private val budget: ShrinkBudget) {

        private val startedAt: Long = System.nanoTime()

        /** How many candidates have been executed. */
        var candidatesSpent: Int = 0
            private set

        /** How long the shrink has been running. */
        val elapsed: Duration get() = (System.nanoTime() - startedAt).nanoseconds

        /** Whether either bound is reached — no further candidate may be executed. */
        val exhausted: Boolean
            get() = candidatesSpent >= budget.maxCandidates || elapsed >= budget.wallClock

        /** Records one executed candidate. */
        fun spend() {
            candidatesSpent += 1
        }

        override fun toString(): String =
            "$candidatesSpent/${budget.maxCandidates} candidates, $elapsed/${budget.wallClock}"
    }

    companion object {
        /** See this class's "Defaults" KDoc for the measurement this is sized against. */
        const val DEFAULT_CANDIDATES: Int = 500

        /** See this class's "Defaults" KDoc for the measurement this is sized against. */
        val DEFAULT_WALL_CLOCK: Duration = 30.seconds
    }
}
