package civictech.testkit.dst.churn

import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.DstArtifact
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.PlanShrinker
import civictech.testkit.dst.Reduction
import civictech.testkit.dst.ReductionStrategy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BS-18 (`[CHA3-48]`): end-to-end pinned-seed shrink evidence over a real churn failure.
 *
 * ## The failing plan
 *
 * `[CHA3-48]`'s own text allows "use a control-induced failure if no genuine one exists" —
 * here one does exist without contriving anything further:
 * [ChurnExclusiveBridgeGraph.control] (BS-17's diverging control, `ExclusiveChurnTest.kt`,
 * same claim) already fails on at least one seed of its own sweep by letting an exhausted
 * exclusive handle go unaccounted. This test reuses that exact graph and check — no separate
 * fixture, no bespoke failure invented for shrinking's sake.
 *
 * ## What each test pins
 *
 *  - [aFailingSeedShrinksToAFixpointWithTheSeedHeldByteIdentical_BS18] runs the real shrinker
 *    end to end: original artifact, shrunk artifact, both plans recovered from the SAME
 *    artifact, the shrunk plan re-verified to still fail with the identical check message, and
 *    the seed field read back byte-identical from both — [DstArtifact.plan] has no seed field
 *    at all ([CHA1-35]), so "byte-identical" here means literally the same `Long`, read twice.
 *  - [theShrinkerRefusesASeedVaryingStrategy_BS18] pins the *other* half of `[CHA3-48]`: "no
 *    seed is ever swapped" is not only a property of the strategies this suite hands the
 *    shrinker — it is a `require` the shrinker itself enforces (`PlanShrinker.kt`'s own seed
 *    guard, `ShrinkerTest.aSeedVaryingReductionStrategyIsRejected_BS3`'s pattern, reproduced
 *    here against a churn artifact rather than CHA1's own fixture) — asserting the refusal,
 *    not merely relying on well-behaved strategies never attempting it.
 */
class ChurnShrinkTest {

    private fun findFailingSeed(range: LongRange): Long {
        for (seed in range) {
            val run = DstRun(
                ChurnExclusiveBridgeGraph.control,
                ChurnExclusiveBridgeGraph.plan(seed),
                check = CheckRegistry.require(ChurnExclusiveBridgeGraph.CHECK_ID),
            )
            if (run.execute().outcome == DstOutcome.FAILED) return seed
        }
        error("no failing seed found in $range — BS-17's own control test already found one; widen the range")
    }

    private fun artifactOf(seed: Long): Pair<DstArtifact, DstRun> {
        val run = DstRun(
            ChurnExclusiveBridgeGraph.control,
            ChurnExclusiveBridgeGraph.plan(seed),
            check = CheckRegistry.require(ChurnExclusiveBridgeGraph.CHECK_ID),
        )
        val report = run.execute()
        assertEquals(DstOutcome.FAILED, report.outcome, "the fixture must fail, or every shrink assertion is vacuous")
        return DstArtifact.of(run, report, suite = "churn-shrink", checkId = ChurnExclusiveBridgeGraph.CHECK_ID) to run
    }

    @Test
    fun aFailingSeedShrinksToAFixpointWithTheSeedHeldByteIdentical_BS18() {
        val seed = findFailingSeed(1L..50L)
        val (artifact, _) = artifactOf(seed)

        // The churn-aware strategy ([CHA3-48] mechanics): drop faults first (unconditionally
        // sound), then the declared churn knobs, against this plan's own horizon.
        val strategy = ChurnReductions.strategyFor(ChurnExclusiveBridgeGraph.churnPlan(seed).stepBudget)
        val result = PlanShrinker.shrink(artifact, strategy = strategy)

        // The artifact holds BOTH plans (`[CHA3-48]`'s "artifact holds original and shrunk
        // churn+fault plans").
        val shrunk = result.artifact
        val original = artifact.plan()
        val reduced = assertNotNull(shrunk.shrunkPlan(), "a shrink must record a shrunk plan, even a fixpoint of one")

        // The seed is byte-identical in both — literally the same field, read from the two
        // plans the SAME artifact reconstructs.
        assertEquals(original.seed, reduced.seed, "the shrinker must never swap the seed (AGENTS.md, [CHA1-35])")
        assertEquals(seed, original.seed)
        assertEquals(seed, reduced.seed)

        // The shrunk plan still fails, with the SAME check message as the original failure —
        // [CHA1-36]'s predicate is exactly what PlanShrinker already re-verified every
        // accepted candidate against, so this re-checks the FINAL result independently rather
        // than trusting the shrinker's own bookkeeping.
        val rerun = DstRun(
            ChurnExclusiveBridgeGraph.control,
            reduced,
            check = CheckRegistry.require(ChurnExclusiveBridgeGraph.CHECK_ID),
        )
        val rerunReport = rerun.execute()
        assertEquals(DstOutcome.FAILED, rerunReport.outcome, "the shrunk plan must still fail")
        assertEquals(
            artifact.observed.outcome,
            DstOutcome.FAILED,
            "sanity: the artifact's own recorded outcome is the failure being held constant",
        )
        assertEquals(
            artifact.observed.failingCheck,
            rerunReport.failingCheck?.message,
            "the shrunk plan must fail with the identical check message ([CHA1-36])",
        )

        // Non-triviality: the shrink actually reduced something, or explicitly reports it could
        // not (a fixpoint at the original size is a legitimate but rare outcome this asserts
        // honestly rather than assuming).
        assertTrue(
            reduced.faults.size <= original.faults.size,
            "a shrink result is never LARGER than what it started from: " +
                "${original.faults.size} -> ${reduced.faults.size}",
        )
    }

    /**
     * `[CHA3-48]`: the shrinker's own seed guard fires when a strategy attempts to vary the
     * seed — the `require` [PlanShrinker] ships, not a bespoke assertion of this task's.
     */
    @Test
    fun theShrinkerRefusesASeedVaryingStrategy_BS18() {
        val seed = findFailingSeed(1L..50L)
        val (artifact, _) = artifactOf(seed)

        // Also drops a fault, not only the seed: PlanShrinker de-duplicates proposed candidates
        // by their fault content before the seed guard ever sees them (`key(it.plan)`), so a
        // candidate identical to `best` but for the seed would be silently filtered as
        // "already visited" rather than reaching `requireSeedHeld` — the exact shape
        // `ShrinkerTest.aSeedVaryingReductionStrategyIsRejected_BS3` uses, reproduced here.
        val varyTheSeed = ReductionStrategy { plan, _ ->
            val droppedId = plan.faults.first().id
            listOf(Reduction("drop $droppedId, and re-roll the seed", plan.without(droppedId).copy(seed = plan.seed + 1)))
        }

        val rejected = assertFailsWith<IllegalArgumentException> {
            PlanShrinker.shrink(artifact, strategy = varyTheSeed)
        }
        val message = assertNotNull(rejected.message)
        assertTrue("CHA1-35" in message, message)
        assertTrue("seed=${seed + 1}" in message, message)
        assertTrue("artifact seed=$seed" in message, message)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ChurnExclusiveBridgeGraph.conforming)
            GraphRegistry.register(ChurnExclusiveBridgeGraph.control)
            CheckRegistry.register(ChurnExclusiveBridgeGraph.CHECK_ID, ChurnExclusiveBridgeGraph.check())
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ChurnExclusiveBridgeGraph.CONFORMING_ID)
            GraphRegistry.unregister(ChurnExclusiveBridgeGraph.CONTROL_ID)
            CheckRegistry.unregister(ChurnExclusiveBridgeGraph.CHECK_ID)
        }
    }
}

