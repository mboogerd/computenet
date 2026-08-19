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
 * step, computenet-7em.5), so these run for real on Linux rather than
 * green-but-skipped: verified on run 32281154048, whose `build-test-fast` job
 * log carries all eleven `Ready*` test lines as PASSED and none as SKIPPED.
 *
 * Do not read that as an automated per-test guard, because it is narrower than
 * it looks. The `":demo:beadsmirror e2e executed, never replayed"` step reruns
 * and asserts over `e2e.TwoNodeRigTest` ALONE — deliberately, since a
 * module-wide `--rerun` was measured at roughly ten times the wall time — and a
 * `Test` task clears its results directory, so after that step
 * `test-results/test` holds only that class. A toolchain regression is caught
 * by TwoNodeRigTest's skipped-count, not by any module-wide skip scan covering
 * the tests below. The evidence for THESE tests executing is the job log above.
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


    /**
     * **The off-critical-path sweep (computenet-98u.4).** [SWEEP_DEFAULT_SEEDS]
     * contiguous seeds at [SWEEP_STEPS] steps each — the cheapest
     * configuration measured that SATURATES the reachable coverage alphabet
     * (40/40; see [ReadyScheduleTest]'s sweep test for the comparison table).
     *
     * **Gated OFF by default, and deliberately not by a JUnit tag.** A
     * `@Tag` gate would have to be declared in
     * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` alongside the existing
     * `multi-jvm` and `bench` gates — a file this task does not own. An
     * environment variable needs no build change and no new module wiring:
     * Gradle's test JVMs inherit the daemon's environment, so a plain
     * `./gradlew test`, and every one of the six required checks, skips this
     * with no configuration at all, while an operator opts in with
     *
     * ```
     * BEADSMIRROR_READY_SWEEP_SEEDS=16 ./gradlew :demo:beadsmirror:test \
     *     --tests '*ReadyDifferentialTest.ready sweep*' --rerun
     * ```
     *
     * **Cost, stated rather than hidden.** The harness pays roughly one `bd`
     * mutation (~0.92s) plus one `bd ready` (~0.39s) per step, so the default
     * 16 x 250 = 4000 steps is hours, not minutes. That is why it is off the
     * critical path — not because the coverage is optional. Coverage is
     * monotone in the seed count, so an operator with less time runs fewer
     * seeds and gets a stated fraction of the alphabet rather than a silently
     * narrowed sweep; `BEADSMIRROR_READY_SWEEP_SEEDS` is the whole knob, and
     * the seed range stays contiguous from 1 so no seed is ever skipped over.
     *
     * Every divergence surfaces the same way the per-PR seeds do — as a thrown
     * [ReadyDivergenceError] naming the seed and the step — so a sweep failure
     * is reproducible from its seed alone by running that seed here.
     */
    @Test
    fun `ready sweep over contiguous seeds saturates the coverage alphabet`() {
        val requested = System.getenv(SWEEP_ENV)
        assumeTrue(requested != null, "$SWEEP_ENV unset — off-critical-path sweep not requested")
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")

        val seeds = requested!!.trim().toIntOrNull() ?: SWEEP_DEFAULT_SEEDS
        require(seeds > 0) { "$SWEEP_ENV must be a positive seed count, was '$requested'" }
        val steps = System.getenv(SWEEP_STEPS_ENV)?.trim()?.toIntOrNull() ?: SWEEP_STEPS
        require(steps > 0) { "$SWEEP_STEPS_ENV must be a positive step count" }
        val config = ReadyScheduleConfig(steps = steps, maxIssues = 15)

        (1L..seeds.toLong()).forEach { seed ->
            val schedule = ReadySchedule.derive(seed, config)
            BdScratchWorkspace.create().use { workspace ->
                val report = ReadyDifferentialHarness(workspace, seed).run(schedule)
                report.comparisons shouldBe schedule.size
            }
        }
    }

    companion object {
        /** The feature's Ex/agree seed. Pinned — see the class KDoc. */
        const val AGREE_SEED: Long = 42L

        /** Pinned for the scripted case too, so a divergence record names a stable run. */
        const val BLOCKER_CYCLE_SEED: Long = 20260819L

        /** Pinned for computenet-98u.2.4's in_progress-membership test. */
        const val IN_PROGRESS_SEED: Long = 2026081902L

        /**
         * computenet-98u.4's second pinned per-PR seed — measured as the
         * strongest complement to [AGREE_SEED] under the current generator
         * (27 -> 36 of 40 reachable coverage elements). Pinned; asserted by
         * [ReadyScheduleTest].
         */
        const val SECOND_AGREE_SEED: Long = 21L

        /** Opt-in switch for the off-critical-path sweep below; also carries the seed count. */
        const val SWEEP_ENV: String = "BEADSMIRROR_READY_SWEEP_SEEDS"

        /**
         * Optional second knob on the sweep, overriding [SWEEP_STEPS]. Its
         * first purpose is that the gate itself is testable: a `1` x `20`
         * invocation proves the environment reaches the test JVM and the
         * sweep body runs, in half a minute rather than the hours the real
         * configuration costs. Its second is that an operator with a
         * different time budget scales BOTH axes explicitly, rather than
         * quietly reinterpreting the seed count.
         */
        const val SWEEP_STEPS_ENV: String = "BEADSMIRROR_READY_SWEEP_STEPS"

        /** Steps per seed in the sweep — the cheapest saturating length measured (computenet-98u.4). */
        const val SWEEP_STEPS: Int = 250

        /** Contiguous seeds 1..N the sweep needs at [SWEEP_STEPS] to reach 40/40. */
        const val SWEEP_DEFAULT_SEEDS: Int = 16

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


/**
 * computenet-98u.4's **second pinned per-PR seed**, deliberately its own
 * top-level class rather than a fifth method on [ReadyDifferentialTest].
 *
 * Gradle distributes test work across `maxParallelForks` by TEST CLASS
 * (`:demo:beadsmirror` runs on up to four forks — computenet-9vx3,
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`), and the methods of one
 * class run serially inside one fork. Measured on a 16-core box, `--rerun`,
 * this module: with both 60-step seeds in one class that class costs 404s and
 * becomes the module's critical path on its own, roughly +90s of module wall
 * time; split across two classes each costs ~160-190s, both fit under the
 * module's existing tail, and the coverage gain is free.
 *
 * A second class in the SAME FILE is what buys that — nothing about the split
 * is a package or file-layout decision, it is purely the unit Gradle
 * schedules on. Keep the two together here so the reason stays legible.
 */
class ReadySecondSeedDifferentialTest {

    @BeforeEach
    fun checkPrerequisites() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    /**
     * **The second pinned per-PR seed (computenet-98u.4).** Same sizing as the
     * Ex/agree run above, a different seed, and the reason it exists is
     * measured rather than assumed: seed 42 at 60 steps exercises 27 of the 40
     * reachable [ReadyEvent]s ([ReadyCoverage]); seed 42 **and** seed 21
     * together exercise 36, and 10 of the 13 `DEPENDENT`-role events — the
     * indirect-propagation half where an incremental derived cell goes stale
     * and a recompute-the-world implementation cannot.
     *
     * **Why a second seed and not a longer schedule.** Measured over 500 seeds
     * (pure schedule replay, no `bd`), coverage against steps for ONE seed:
     *
     * ```
     * steps    min   p10   p50   p90   max      (of 40 reachable)
     *    20      6    10    13    15    21
     *    60     16    20    23    27    33      <- today's required check
     *   120     21    27    28    30    33      (seed 42: 30)
     *   200     25    31    33    35    38      (seed 42: 32)
     *  1000     35    36    36    37    39
     * ```
     *
     * A single schedule never saturates: even at 1000 steps the median seed is
     * still short of the alphabet, because the rare events need a structural
     * coincidence (a status update landing on an issue that is *already* a
     * blocker of a live dependent) that one 15-issue workspace reaches only
     * occasionally. Two seeds at 60 steps reach 36; one seed at 120 steps —
     * the same 120 total steps, the same wall time, since the harness pays per
     * STEP — reaches 30. Seeds dominate steps at equal cost, so the per-PR
     * budget buys a second seed.
     *
     * **Nothing was narrowed to pay for it.** Seed 42 above is untouched and
     * still runs at 60 steps; this is added alongside. The four events neither
     * seed reaches are covered by the off-critical-path sweep below, which
     * saturates the alphabet — total coverage strictly increases here
     * (AGENTS.md's pinned-seed rule; doc/demo-findings.md F-11).
     *
     * The seeds are pinned exactly as seed 42 is: a red run here is triaged
     * against [ReadyDifferentialHarness]'s "What a divergence MEANS", never
     * traded for a friendlier seed.
     */
    @Test
    fun `derived ready set agrees with bd ready after every mutation of seed 21`() {
        val schedule = ReadySchedule.derive(ReadyDifferentialTest.SECOND_AGREE_SEED, ReadyScheduleConfig(steps = 60, maxIssues = 15))

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, ReadyDifferentialTest.SECOND_AGREE_SEED)

            val report = harness.run(schedule)

            report.comparisons shouldBe schedule.size

            // The pure replay [ReadyCoverage] computes its metric from, checked
            // against what the LIVE run actually observed, step for step. This
            // is what stops computenet-98u.4's coverage numbers from measuring
            // a model of the workspace instead of the workspace: if the replay
            // and the real mirror ever disagree about who is ready after some
            // step, every coverage figure derived from the replay is wrong and
            // this fails here rather than in a spreadsheet.
            report.outcomes.map { it.readyIds.toSet() } shouldBe ReadyCoverage.readySets(schedule)
        }
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
