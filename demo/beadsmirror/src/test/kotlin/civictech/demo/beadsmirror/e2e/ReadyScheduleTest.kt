package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-98u.2.1's own tests for [ReadySchedule]: determinism (a
 * pure function of `(seed, config)`, no `bd` involved) and legality (one
 * full derived schedule, executed in order against a real
 * [BdScratchWorkspace]).
 */
class ReadyScheduleTest {

    @BeforeEach
    fun checkPrerequisites() {
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
        val schedule = ReadySchedule.derive(SEED, ReadyScheduleConfig(steps = 60, maxIssues = 15))

        BdScratchWorkspace.create().use { workspace ->
            schedule.forEach { step -> step.apply(workspace) }
        }
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
