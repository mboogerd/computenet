package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Task computenet-98u.2.1's own tests for [ReadySchedule]: determinism (a
 * pure function of `(seed, config)`, no `bd` involved) and legality (one
 * full derived schedule, executed in order against a real
 * [BdScratchWorkspace]).
 */
class ReadyScheduleTest {

    /**
     * Guards only the tests that actually shell out. Deliberately **not** an
     * `@BeforeEach`: computenet-98u.4's coverage tests below are pure replays
     * of a rendered schedule with no `bd`/`dolt` involvement at all, and a
     * class-wide assume would silently skip them on a machine without the
     * toolchain — reporting green while checking nothing.
     */
    private fun requireBdToolchain() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    /**
     * The feature's Ex/determinism, single-sided: two [ReadySchedule.derive]
     * calls with the same `(seed, config)` render `equals` lists. No `bd`
     * process involved — this is a property of [ReadySchedule.derive] alone.
     */
    @Test
    fun `derive is a pure function of seed and config`() {
        val config = ReadyScheduleConfig(steps = 60, maxIssues = 15)

        val first = ReadySchedule.derive(SEED, config)
        val second = ReadySchedule.derive(SEED, config)

        first shouldBe second
        first.size shouldBe 60
    }

    /**
     * A different seed renders a different schedule — the determinism
     * property is about repeatability, not about [ReadySchedule.derive]
     * ignoring its [seed] argument.
     */
    @Test
    fun `different seeds render different schedules`() {
        val config = ReadyScheduleConfig(steps = 60, maxIssues = 15)

        val a = ReadySchedule.derive(SEED, config)
        val b = ReadySchedule.derive(SEED + 1, config)

        (a == b) shouldBe false
    }

    /**
     * The legality criterion: one full schedule, derived at the feature's
     * default sizing, executed IN ORDER against a real `bd` scratch
     * workspace via [BdScratchWorkspace.run]'s fail-loud posture — any step
     * [ReadySchedule.derive] emitted that `bd` rejects fails this test
     * immediately with the failing command and `bd`'s own output
     * ([BdScratchWorkspace.run]'s `check` message), rather than silently
     * truncating the run.
     */
    @Test
    fun `every generated step is legal against a real bd workspace in order`() {
        requireBdToolchain()

        val schedule = ReadySchedule.derive(SEED, ReadyScheduleConfig(steps = 60, maxIssues = 15))

        BdScratchWorkspace.create().use { workspace ->
            schedule.forEach { step -> step.apply(workspace) }
        }
    }


    // ------------------------------------------------------------------
    // computenet-98u.4: readiness-transition coverage. Pure replays of
    // rendered schedules — no `bd`, no `dolt`, no live harness, milliseconds
    // per seed — which is what makes them cheap enough to assert the measured
    // sizing claims of [ReadyCoverage] and [ReadyDifferentialTest] on every
    // run instead of trusting a number in a comment.
    // ------------------------------------------------------------------

    /**
     * The denominator every coverage claim in this module is stated against.
     * [ReadyCoverage.REACHABLE_ELEMENTS] was measured at 40 over seeds
     * 1..3000 at 2000 steps (2026-08-19); this re-measures it over a smaller
     * but still saturating sweep and asserts **equality**, not a floor — a
     * generator change that makes a new [ReadyEvent] reachable, or that makes
     * an existing one unreachable, has moved every other number here and must
     * fail loudly rather than silently rebase them onto a new denominator.
     */
    @Test
    fun `the reachable coverage alphabet is the pinned 40 elements`() {
        val universe = ReadyCoverage.reachableAlphabet(
            (1L..600L).toList(),
            ReadyScheduleConfig(steps = 600, maxIssues = 15),
        )

        universe.size shouldBe ReadyCoverage.REACHABLE_ELEMENTS
    }

    /**
     * The per-PR figure [ReadyDifferentialTest] runs, asserted as coverage
     * rather than as a step count: the two pinned seeds at 60 steps together
     * reach 36 of the 40 reachable elements, and 10 of the 13
     * [ReadySubjectRole.DEPENDENT] elements — the indirect-propagation half
     * that is the whole reason role is an axis of the metric.
     *
     * **This is the guard on the recommendation.** The second seed was chosen
     * by measuring which seed most complements seed 42 under the CURRENT
     * generator; if [ReadySchedule]'s draw ever changes, the pinned pair stops
     * complementing and this says so in milliseconds — instead of the required
     * check quietly degrading to whatever the new draw happens to cover.
     */
    @Test
    fun `the two pinned per-PR seeds cover 36 of the 40 reachable elements`() {
        val config = ReadyScheduleConfig(steps = 60, maxIssues = 15)

        val covered = ReadyCoverage.elementsOf(ReadyDifferentialTest.AGREE_SEED, config) +
            ReadyCoverage.elementsOf(ReadyDifferentialTest.SECOND_AGREE_SEED, config)

        covered.size shouldBe 36
        covered.count { it.role == ReadySubjectRole.DEPENDENT } shouldBe 10
    }

    /**
     * Seed 42 alone — the coverage the required check had BEFORE
     * computenet-98u.4 — pinned so the second seed's contribution reads as a
     * measured delta (27 -> 36) rather than an assertion. Nothing was removed
     * to get there: seed 42 is unchanged and still runs, so the change is
     * purely additive (AGENTS.md's pinned-seed rule; doc/demo-findings.md
     * F-11).
     */
    @Test
    fun `the original single pinned seed covers 27 elements on its own`() {
        val covered = ReadyCoverage.elementsOf(
            ReadyDifferentialTest.AGREE_SEED,
            ReadyScheduleConfig(steps = 60, maxIssues = 15),
        )

        covered.size shouldBe 27
    }

    /**
     * The off-critical-path sweep configuration
     * ([ReadyDifferentialTest.SWEEP_STEPS] over
     * [ReadyDifferentialTest.SWEEP_DEFAULT_SEEDS] **contiguous** seeds)
     * saturates the alphabet. Contiguous, not cherry-picked: a hand-picked
     * covering set would be tuned to today's generator and would narrow what
     * the sweep can discover, which is what doc/demo-findings.md F-11 forbids
     * of a full-range seed sweep.
     *
     * Measured 2026-08-19 as the CHEAPEST saturating configuration in total
     * steps — and total steps is the cost currency, since the live harness
     * pays one `bd` mutation plus one `bd ready` per step regardless of which
     * schedule that step sits in:
     *
     * ```
     * steps/seed   contiguous seeds to reach 40/40   total steps
     *     60                    128                     7680
     *    120                     64                     7680
     *    250                     16                     4000   <- chosen
     *    500                     16                     8000
     * ```
     */
    @Test
    fun `the sweep configuration saturates the reachable alphabet`() {
        val covered = ReadyCoverage.reachableAlphabet(
            (1L..ReadyDifferentialTest.SWEEP_DEFAULT_SEEDS.toLong()).toList(),
            ReadyScheduleConfig(steps = ReadyDifferentialTest.SWEEP_STEPS, maxIssues = 15),
        )

        covered.size shouldBe ReadyCoverage.REACHABLE_ELEMENTS
    }

    /**
     * `maxIssues` is **not** a coverage knob — measured and pinned here so a
     * future session does not spend the experiment again. Over 200 seeds at 60
     * steps the median seed covers within one element of the same total at
     * every workspace size from 5 issues to 30. Readiness coverage is driven
     * by the shape of the verb draw and the edge set, not by how many issues
     * the draw spreads over, so shrinking the workspace is not a free way to
     * buy coverage.
     */
    @Test
    fun `workspace size does not move coverage`() {
        val medians = listOf(5, 10, 15, 30).map { maxIssues ->
            (1L..200L).map { seed ->
                ReadyCoverage.elementsOf(seed, ReadyScheduleConfig(steps = 60, maxIssues = maxIssues)).size
            }.sorted()[100]
        }

        (medians.max() - medians.min() <= 1) shouldBe true
    }

    private companion object {
        const val SEED: Long = 20260819L
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
