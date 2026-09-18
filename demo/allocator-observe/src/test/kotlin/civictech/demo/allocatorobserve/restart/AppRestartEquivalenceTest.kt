package civictech.demo.allocatorobserve.restart

import civictech.demo.allocatorobserve.AllocatorObserveApp
import civictech.demo.allocatorobserve.AllocatorObserveConfig
import civictech.demo.allocatorobserve.oracle.D1_YAML
import civictech.demo.allocatorobserve.oracle.D2_YAML
import civictech.demo.allocatorobserve.oracle.NOW
import civictech.demo.allocatorobserve.oracle.R1
import civictech.demo.allocatorobserve.oracle.R10
import civictech.demo.allocatorobserve.oracle.R2
import civictech.demo.allocatorobserve.oracle.R3
import civictech.demo.allocatorobserve.oracle.R4
import civictech.demo.allocatorobserve.oracle.R5
import civictech.demo.allocatorobserve.oracle.R6
import civictech.demo.allocatorobserve.oracle.R7
import civictech.demo.allocatorobserve.oracle.R8
import civictech.demo.allocatorobserve.oracle.R9
import civictech.demo.allocatorobserve.oracle.WINDOW
import civictech.testkit.HttpProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Feature `computenet-fpml.5`'s rule 2, end to end (task `computenet-fpml.5.2`,
 * design fpml.5-D4): an `AllocatorObserveApp` stopped at any poll boundary of
 * the fixture week and restarted over the same run directory and spend log
 * serves the very document an app that was never stopped serves.
 *
 * ## The step script
 *
 * Four steps over the fpml.5-D9 fixture week, each one filesystem edit plus a
 * clock move followed by exactly one poll. `pollInterval` is a day, so the
 * background thread never ticks and every observation follows a poll the test
 * made itself (`AllocatorObserveAppTest`'s discipline).
 *
 * | step | inputs                                   | clock            |
 * |------|------------------------------------------|------------------|
 * | 0    | declaration D1 (60/40), empty log        | 2026-08-08T00:00Z |
 * | 1    | append r1..r6                            | 2026-08-11T23:00Z |
 * | 2    | declaration D2 (30/70)                   | 2026-08-12T00:00Z |
 * | 3    | append r7..r10                           | 2026-08-15T00:00Z |
 *
 * Step 0's poll is the one `start()` runs before binding, so the declaration
 * events land at exactly D1's and D2's fixture `observedAt` instants and the
 * final report's sub-interval boundary is the fixture's.
 *
 * A restart at boundary `s` runs steps 0..s on the first app, stops it, and
 * lets the SECOND app's `start()` tick be step `s + 1`'s poll — a restart takes
 * wall-clock time, so the replacement process comes up with the clock already
 * moved on. That detail is load-bearing rather than cosmetic: a restart at an
 * unmoved clock would re-observe the current declaration at the very instant
 * the lost event carried, so a lost history would reconstruct itself by
 * coincidence and the journal would look unnecessary (measured: with the second
 * app started before step `s + 1`'s inputs, `s = 0` passed with the replay
 * commented out).
 *
 * ## What each restart boundary discriminates
 *
 * The three boundaries are not three copies of one assertion; each kills a
 * different half of the fix, which is why all three are run:
 *
 * - **s = 0** (stop after the declaration, before any record) fails without the
 *   declaration-history journal: D1 is lost and re-observed three days late, so
 *   the window's first sub-interval starts at the wrong instant and
 *   `beforeFirstDeclarationHours` becomes non-empty. It cannot fail for want of
 *   cold-start reading — no record had been consumed yet, and an empty log
 *   leaves no checkpoint to resume past.
 * - **s = 1** (stop after r1..r6) fails without the cold-start whole-file read:
 *   the restarted app resumes past the checkpoint into an empty fold and never
 *   sees records 1..6. It also fails without the journal, for s = 0's reason.
 * - **s = 2** (stop after the mid-week re-weighting) fails without cold start
 *   for the same reason as s = 1, with both declarations already journalled —
 *   so it isolates the spend half at a boundary where the declaration half is
 *   settled.
 *
 * Both mutations were run before this test was reported (see the task's bead
 * comment): commenting out `journal.replayInto(declarations)` fails s = 0, and
 * making `ColdStartOffsetStore.read()` delegate unconditionally fails s = 1.
 */
class AppRestartEquivalenceTest {

    @TempDir
    lateinit var tmp: Path

    private val started = mutableListOf<AllocatorObserveApp>()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        started.forEach { runCatching { it.stop() } }
    }

    private companion object {
        val STEP_0_AT: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val STEP_1_AT: Instant = Instant.parse("2026-08-11T23:00:00Z")
        val STEP_2_AT: Instant = Instant.parse("2026-08-12T00:00:00Z")
        const val REPORT_PATH = "/state/report"
        const val INGEST_PATH = "/state/ingest"
        const val LAST_STEP = 3
    }

    /** One app's world: its own directory, log, run dir, declaration and clock. */
    private inner class Rig(name: String) {
        val dir: Path = tmp.resolve(name).also { Files.createDirectories(it) }
        val log: Path = dir.resolve("spend.jsonl")
        val runDir: Path = dir.resolve("run")
        val declaration: Path = dir.resolve("allocation.yaml")
        val clock = AtomicReference(STEP_0_AT)

        /** A new process over this world. Every restart builds one of these. */
        fun app(): AllocatorObserveApp =
            AllocatorObserveApp(
                AllocatorObserveConfig(
                    logPath = log,
                    runDir = runDir,
                    declarationPath = declaration,
                    port = 0,
                    pollInterval = Duration.ofDays(1),
                    windowLength = WINDOW,
                ),
                now = { clock.get() },
            ).also { started += it }

        /** Applies step [step]'s inputs. The caller polls afterwards. */
        fun inputs(step: Int) {
            when (step) {
                0 -> {
                    clock.set(STEP_0_AT)
                    Files.writeString(declaration, D1_YAML)
                    Files.writeString(log, "")
                }

                1 -> {
                    append(R1, R2, R3, R4, R5, R6)
                    clock.set(STEP_1_AT)
                }

                2 -> {
                    Files.writeString(declaration, D2_YAML)
                    clock.set(STEP_2_AT)
                }

                3 -> {
                    append(R7, R8, R9, R10)
                    clock.set(NOW)
                }

                else -> error("no such step: $step")
            }
        }

        private fun append(vararg lines: String) {
            Files.writeString(
                log,
                lines.joinToString("") { "$it\n" },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun probe(app: AllocatorObserveApp): HttpProbe =
        HttpProbe("http://localhost:${app.boundPort}").also { closeables += it }

    /** The uninterrupted run: one app, all four steps, its own directory. */
    private fun control(): String {
        val rig = Rig("control")
        rig.inputs(0)
        val app = rig.app().start()
        for (step in 1..LAST_STEP) {
            rig.inputs(step)
            app.pollOnce()
        }
        return probe(app).state(REPORT_PATH)
    }

    private fun restartedAt(boundary: Int): AllocatorObserveApp {
        val rig = Rig("restart-$boundary")
        rig.inputs(0)
        val first = rig.app().start()
        for (step in 1..boundary) {
            rig.inputs(step)
            first.pollOnce()
        }
        first.stop()

        // A second process over the SAME run directory and log. Its `start()`
        // tick is step `boundary + 1`'s poll — a restart takes wall-clock time,
        // so the app comes back with the clock already moved on rather than
        // frozen at the instant it died. That tick is the cold-start whole-file
        // read; the journal replay happened in the constructor before it.
        rig.inputs(boundary + 1)
        val second = rig.app().start()
        second.declarationReplayFailures shouldBe 0L
        for (step in boundary + 2..LAST_STEP) {
            rig.inputs(step)
            second.pollOnce()
        }
        return second
    }

    @Test
    fun `a restart at any poll boundary serves the uninterrupted run's report`() {
        val expected = control()

        for (boundary in 0..LAST_STEP - 1) {
            val app = restartedAt(boundary)
            val probe = probe(app)

            withClue(clue(boundary)) { probe.state(REPORT_PATH) shouldBe expected }

            val ingest = Json.parseToJsonElement(probe.state(INGEST_PATH)).jsonObject
            withClue(clue(boundary)) { ingest.getValue("recordCount").jsonPrimitive.int shouldBe 10 }
            withClue(clue(boundary)) { ingest.getValue("declarationEvents").jsonPrimitive.int shouldBe 2 }
        }
    }

    /**
     * The same restart, read from the control's own numbers rather than from
     * equality alone: a mutation that broke BOTH sides identically would pass
     * the byte comparison above, so the control's record and declaration counts
     * are pinned too.
     */
    @Test
    fun `the uninterrupted run itself sees all ten records and both declarations`() {
        val rig = Rig("control-counts")
        rig.inputs(0)
        val app = rig.app().start()
        for (step in 1..LAST_STEP) {
            rig.inputs(step)
            app.pollOnce()
        }

        val ingest = Json.parseToJsonElement(probe(app).state(INGEST_PATH)).jsonObject
        ingest.getValue("recordCount").jsonPrimitive.int shouldBe 10
        ingest.getValue("declarationEvents").jsonPrimitive.int shouldBe 2
    }

    /** Names the failing boundary when an assertion inside the loop fails. */
    private fun clue(boundary: Int): String = "restart boundary s=$boundary"
}
