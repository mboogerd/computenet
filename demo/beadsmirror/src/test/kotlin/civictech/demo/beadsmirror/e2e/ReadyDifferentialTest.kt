package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-98u.2.2's own tests for [ReadyDifferentialHarness] — the
 * feature's Ex/agree and Ex/blocker-cycle.
 *
 * Both drive **real** `bd` mutations against a **real** throwaway
 * [BdScratchWorkspace] and shell out to `bd ready --json` once per step, so
 * they are substantially slower than a unit test and are guarded on `bd`/`dolt`
 * being on PATH with `assumeTrue`, like every other live-workspace test in this
 * module. CI installs both (`.github/workflows/ci.yml`'s "Install bd and dolt"
 * step, computenet-7em.5) and its report step FAILS the job if any
 * `:demo:beadsmirror` test skips itself for a missing binary, so these run for
 * real on Linux rather than green-but-skipped.
 *
 * **The seeds below are pinned.** If one of them ever goes red, the answer is
 * to read the [DivergenceRecord] it prints and triage it against
 * [ReadyDifferentialHarness]'s "What a divergence MEANS" — never to try another
 * seed until the run is green (AGENTS.md; [SeededSchedule]'s pinned-seed
 * precedent).
 */
class ReadyDifferentialTest {

    @BeforeEach
    fun checkPrerequisites() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    /**
     * **Ex/agree.** Seed 42 at the feature's default sizing (~60 steps over
     * ~15 issues): every post-mutation comparison passes, and the run reports
     * exactly one comparison per step.
     *
     * That last assertion is the point of the `comparisons` count, not
     * decoration: a harness whose feed wait gave up and skipped a step would
     * otherwise pass this test while checking less than it claims.
     */
    @Test
    fun `derived ready set agrees with bd ready after every mutation of seed 42`() {
        val schedule = ReadySchedule.derive(AGREE_SEED, ReadyScheduleConfig(steps = 60, maxIssues = 15))

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, AGREE_SEED)

            val report = harness.run(schedule)

            report.comparisons shouldBe schedule.size
        }
    }

    /**
     * **Ex/blocker-cycle.** A small **scripted** (non-random) step list, so the
     * membership flip this checks is legible rather than incidental: `A` and
     * `B` are created, `B` is blocked by `A`, and then `A` is closed, reopened
     * and closed again. `B`'s ready membership flips at each of those three
     * steps and the derived side agrees with the oracle at **every** step —
     * the harness compares after each one, so a wrong answer at any point
     * fails here.
     *
     * This is the case beads' denormalized `is_blocked` column is maintained
     * for and where hypothesis (c) of the harness's triage would surface: the
     * derived side recomputes blockedness from the live edge set plus `A`'s
     * mirrored status every time, while the oracle reads a column beads has to
     * remember to update on both the close and the reopen path.
     */
    @Test
    fun `closing and reopening a blocker flips the dependent's membership on both sides`() {
        val schedule = listOf(
            ScheduleStep.Create(BLOCKER, "blocker $BLOCKER"),
            ScheduleStep.Create(DEPENDENT, "dependent $DEPENDENT"),
            ScheduleStep.DepAdd(blockedId = DEPENDENT, blockerId = BLOCKER, type = "blocks"),
            ScheduleStep.Close(BLOCKER),
            ScheduleStep.Reopen(BLOCKER),
            ScheduleStep.Close(BLOCKER),
        )

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, BLOCKER_CYCLE_SEED)

            val report = harness.run(schedule)

            report.comparisons shouldBe schedule.size

            // The membership flip itself, read off the recorded outcomes: blocked
            // while the blocker is open, ready while it is closed. Without this the
            // test could pass on a schedule where nothing ever changed.
            val membership = report.outcomes.map { DEPENDENT in it.readyIds }
            membership shouldBe listOf(
                false, // step 0: only the blocker exists yet
                true, //  step 1: dependent created, unblocked
                false, // step 2: dep added, blocker open -> blocked
                true, //  step 3: blocker closed -> unblocked
                false, // step 4: blocker reopened -> blocked again
                true, //  step 5: blocker closed again -> unblocked again
            )
        }
    }

    /**
     * **Task computenet-98u.2.4's resolution (a) test.** Drives one issue
     * `open -> in_progress -> blocked -> in_progress` and checks its ready
     * membership at every step, exercising [ReadyDifferentialHarness]'s
     * widened oracle ([ReadyDifferentialHarness.oracleIds] /
     * [ReadyDifferentialHarness.COMPARISON_STATUSES]) on the half of
     * READY-COVERAGE row 3 (`status IN ('open', 'in_progress')`) that used to
     * be aligned away rather than differentially tested — see the harness
     * type KDoc's "comparison domain" section.
     *
     * **Why this discriminates the widening, not just re-states it.** Before
     * computenet-98u.2.4, both [ReadyDifferentialHarness.derivedIds] and
     * [ReadyDifferentialHarness.oracleIds] restricted the comparison domain to
     * `status == "open"`. Under that code, step 1 below (the transition to
     * `in_progress`) would have dropped [PROBE] out of the comparison domain
     * on **both** sides — `bd ready --json` already omits `in_progress`
     * issues (the harness KDoc's probe), and the old `derivedIds()` filtered
     * it out too — so the two sides would still have agreed (silently, on an
     * empty intersection for that id) and the run would have stayed green.
     * The only way to see the old code was wrong was to look at what
     * [ComparisonOutcome.readyIds] actually contained: with the domain
     * restricted to `open`, [PROBE] would be **absent** from
     * `report.outcomes[1].readyIds`, so `membership[1]` would read `false`
     * against the pre-widening code and this test's `membership shouldBe`
     * assertion below would fail. Against the widened harness it reads
     * `true`, because [ReadyDifferentialHarness.inProgressOracleIds] gives
     * the oracle side an answer for `in_progress` that `bd ready --json`
     * cannot, and [ReadyDifferentialHarness.derivedIds] now keeps
     * `in_progress` ids in the comparison domain instead of filtering them
     * away. So this test fails under the pre-widening harness and passes
     * under the post-widening one — it discriminates the change, it does not
     * merely restate it.
     *
     * The `blocked` step (status outside
     * [civictech.demo.beadsmirror.ready.ReadyPredicate.DEFAULT_READY_STATUSES]
     * entirely) and the final flip back to `in_progress` guard against a
     * sloppier widening that made every status ready, or made the transition
     * one-directional/sticky: [PROBE] must fall OUT of both `readyIds` sets
     * while `blocked` and come back when it returns to `in_progress`.
     */
    @Test
    fun `an issue transitioned into in_progress is ready on both sides, and leaves readiness when blocked`() {
        val schedule = listOf(
            ScheduleStep.Create(PROBE, "in_progress membership probe $PROBE"),
            ScheduleStep.StatusUpdate(PROBE, "in_progress"),
            ScheduleStep.StatusUpdate(PROBE, "blocked"),
            ScheduleStep.StatusUpdate(PROBE, "in_progress"),
        )

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, IN_PROGRESS_SEED)

            val report = harness.run(schedule)

            // Every step compared without a skipped or errored comparison — the
            // ReadyDivergenceError the widened oracleIds() would throw if only
            // one side of the in_progress widening had landed (e.g. derivedIds()
            // widened but inProgressOracleIds() forgotten) surfaces here as a
            // thrown exception, failing this call before the membership
            // assertion below is even reached.
            report.comparisons shouldBe schedule.size

            val membership = report.outcomes.map { PROBE in it.readyIds }
            membership shouldBe listOf(
                true, //  step 0: created open -> ready
                true, //  step 1: in_progress -> still ready (the widened half)
                false, // step 2: blocked -> outside the ready status set entirely
                true, //  step 3: back to in_progress -> ready again, not sticky
            )
        }
    }

    private companion object {
        /** The feature's Ex/agree seed. Pinned — see the class KDoc. */
        const val AGREE_SEED: Long = 42L

        /** Pinned for the scripted case too, so a divergence record names a stable run. */
        const val BLOCKER_CYCLE_SEED: Long = 20260819L

        /** Pinned for computenet-98u.2.4's in_progress-membership test. */
        const val IN_PROGRESS_SEED: Long = 2026081902L

        const val BLOCKER: String = "R-1"
        const val DEPENDENT: String = "R-2"
        const val PROBE: String = "R-3"
    }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
