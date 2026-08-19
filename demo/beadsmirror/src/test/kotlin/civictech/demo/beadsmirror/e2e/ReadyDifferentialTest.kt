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
 * module (CI installs neither and runs these green-but-skipped).
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

    private companion object {
        /** The feature's Ex/agree seed. Pinned — see the class KDoc. */
        const val AGREE_SEED: Long = 42L

        /** Pinned for the scripted case too, so a divergence record names a stable run. */
        const val BLOCKER_CYCLE_SEED: Long = 20260819L

        const val BLOCKER: String = "R-1"
        const val DEPENDENT: String = "R-2"
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
