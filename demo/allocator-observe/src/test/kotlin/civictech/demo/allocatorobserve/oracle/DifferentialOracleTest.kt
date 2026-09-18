package civictech.demo.allocatorobserve.oracle

import civictech.demo.allocatorobserve.AllocatorObserveApp
import civictech.demo.allocatorobserve.AllocatorObserveConfig
import civictech.testkit.HttpProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Feature `computenet-fpml.5`'s acceptance gate (task `computenet-fpml.5.3`,
 * design fpml.5-D8/D9): the document a real `AllocatorObserveApp` SERVES over
 * `GET /state/report` equals [ReferenceReport]'s independent computation over
 * the same lines, history, `now` and window — in three scenarios drawn from
 * the fixture week (`WeekFixture`, fpml.5-D9): a straight replay, a mid-week
 * log replacement (re-baseline) with the declaration change in the same
 * week, and the same replacement after a process restart.
 *
 * ## Why this is the centrepiece, not another restart or reference test
 *
 * `ReferenceReportTest` checks [ReferenceReport] against hand-derived
 * numbers; `AppRestartEquivalenceTest` checks a restart against an
 * uninterrupted run of the SAME app. Neither compares the served pipeline
 * against an INDEPENDENT computation. This class is the one place both
 * properties are asked of the real thing at once: the served report, after a
 * restart and a re-baseline, still equals what the specification says it
 * should be — not merely what this app's own other run produced.
 *
 * This file imports both sides of the comparison on purpose. Unlike
 * [ReferenceReport] and [ReportComparison], it IS the comparison rather than
 * one of the things being compared, so [ReferenceIndependenceTest]'s lexical
 * scan — which is scoped to this package's non-`Test` sources — neither
 * reaches nor should reach it.
 *
 * ## The step script
 *
 * Every scenario replays the fixture week with the same clock/log/
 * declaration idiom `AppRestartEquivalenceTest` uses: a `pollInterval` long
 * enough that the background poll thread never ticks on its own, and an
 * explicit [AllocatorObserveApp.pollOnce] after every filesystem edit and
 * clock move — so the only thing under test is whether the served document
 * matches the reference, never scheduling.
 */
class DifferentialOracleTest {

    @TempDir
    lateinit var tmp: Path

    private val started = mutableListOf<AllocatorObserveApp>()
    private val closeables = mutableListOf<AutoCloseable>()
    private var bisectSeq = 0

    @AfterEach
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        started.forEach { runCatching { it.stop() } }
    }

    private companion object {
        const val REPORT_PATH = "/state/report"
        const val INGEST_PATH = "/state/ingest"

        // Declaration instants match D1/D2 exactly, as AppRestartEquivalenceTest's
        // own script does, so the app's declaration history lands at the very
        // instants ReferenceReport.compute(..., HISTORY, ...) assumes.
        val STEP_0_AT: Instant = D1.observedAt
        val AFTER_R6_AT: Instant = Instant.parse("2026-08-11T23:00:00Z")
        val STEP_D2_AT: Instant = D2.observedAt
        val AFTER_R7_AT: Instant = Instant.parse("2026-08-12T11:30:00Z")
        val RESTART_AT: Instant = Instant.parse("2026-08-12T11:45:00Z")
        val AFTER_REPLACEMENT_AT: Instant = Instant.parse("2026-08-12T12:15:00Z")

        /** Tolerance for the hand-derived re-baseline number (bead: "+-1e-9"). */
        const val HOURS_TOLERANCE: Double = 1e-9
    }

    /** One app's world: its own directory, log, run dir, declaration file and clock — `AppRestartEquivalenceTest.Rig`'s idiom. */
    private inner class Rig(name: String) {
        val dir: Path = tmp.resolve(name).also { Files.createDirectories(it) }
        val log: Path = dir.resolve("spend.jsonl")
        val runDir: Path = dir.resolve("run")
        val declaration: Path = dir.resolve("allocation.yaml")
        val clock = AtomicReference(STEP_0_AT)

        /** A new process over this world. A restart builds a second one over the same [dir]. */
        fun app(window: Duration = WINDOW): AllocatorObserveApp =
            AllocatorObserveApp(
                AllocatorObserveConfig(
                    logPath = log,
                    runDir = runDir,
                    declarationPath = declaration,
                    port = 0,
                    pollInterval = Duration.ofDays(1),
                    windowLength = window,
                ),
                now = { clock.get() },
            ).also { started += it }

        fun append(vararg lines: String) {
            Files.writeString(
                log,
                lines.joinToString("") { "$it\n" },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }

        /**
         * Atomically replaces [log]'s whole content with [lines] (the bead's
         * mid-week replacement): write a sibling temp file, then
         * `ATOMIC_MOVE` + `REPLACE_EXISTING` over the real path, so the
         * reader never observes a half-written file.
         */
        fun replace(vararg lines: String) {
            val temp = dir.resolve("spend.jsonl.tmp")
            Files.writeString(temp, lines.joinToString("") { "$it\n" })
            Files.move(temp, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        /** [log]'s real, current, physical content — what the served pipeline actually consumed. */
        fun physicalLines(): List<String> = Files.readAllLines(log)
    }

    private fun probe(app: AllocatorObserveApp): HttpProbe =
        HttpProbe("http://localhost:${app.boundPort}").also { closeables += it }

    private fun reportOf(app: AllocatorObserveApp): JsonObject =
        Json.parseToJsonElement(probe(app).state(REPORT_PATH)).jsonObject

    private fun ingestOf(app: AllocatorObserveApp): JsonObject =
        Json.parseToJsonElement(probe(app).state(INGEST_PATH)).jsonObject

    private fun JsonElement.at(vararg path: String): JsonElement =
        path.fold(this) { element, key -> element.jsonObject.getValue(key) }

    private infix fun Double.shouldBeNear(expected: Double) {
        val delta = kotlin.math.abs(this - expected)
        withClue("expected $expected, got $this (delta $delta > $HOURS_TOLERANCE)") {
            (delta <= HOURS_TOLERANCE) shouldBe true
        }
    }

    // -----------------------------------------------------------------------
    // Divergence trace (fpml.5-D8)
    // -----------------------------------------------------------------------

    /**
     * Fails with the divergence trace fpml.5-D8 requires: when
     * [ReportComparison.compare] of [actual] against the reference computed
     * over [expectedLines] is non-empty, this does not merely report that
     * list — it re-runs BOTH sides over growing prefixes (a fresh
     * [AllocatorObserveApp] fed [physicalLines]`.take(k)` directly —
     * [servedReportOverLines] — compared against the reference over
     * [expectedLines]`.take(k)`) until it finds the smallest prefix length
     * whose OWN comparison already diverges, and fails naming that length and
     * the physical line at it, followed by every divergence at the full
     * length.
     *
     * [physicalLines] is deliberately the log's REAL, on-disk content — read
     * by the caller via [Rig.physicalLines] — rather than [expectedLines]
     * again: in the ordinary case the two are the same list, but a bisection
     * that rebuilt only from [expectedLines] could never reproduce a
     * divergence caused by the served pipeline actually having consumed
     * different bytes than the caller believes it did (measured while
     * proving this method discriminates, task bead comment). Reading the
     * real file is what makes the trace point at the actual culprit rather
     * than silently agreeing with the caller's assumption.
     *
     * A passing call ([actual] already equals the reference) never touches
     * [servedReportOverLines]: the bisection is failure-path-only cost.
     */
    private fun assertNoDivergence(
        actual: JsonObject,
        expectedLines: List<String>,
        physicalLines: List<String> = expectedLines,
        history: List<DeclarationSpec> = HISTORY,
        now: Instant = NOW,
        window: Duration = WINDOW,
    ) {
        val expected = ReferenceReport.compute(expectedLines, history, now, window)
        val divergences = ReportComparison.compare(expected, actual)
        if (divergences.isEmpty()) return

        val bound = maxOf(expectedLines.size, physicalLines.size)
        val firstDivergingLength =
            (1..bound).firstOrNull { k ->
                ReportComparison.compare(
                    ReferenceReport.compute(expectedLines.take(k), history, now, window),
                    servedReportOverLines(physicalLines.take(k), history, now, window),
                ).isNotEmpty()
            }

        val trace =
            if (firstDivergingLength != null) {
                val line = physicalLines.getOrNull(firstDivergingLength - 1) ?: "(no such physical line)"
                "first diverging line k=$firstDivergingLength: $line\n"
            } else {
                "no prefix of length 1..$bound reproduces a divergence on its own; " +
                    "the divergence appears only over the full content\n"
            }
        throw AssertionError(trace + divergences.joinToString("\n") { it.toString() })
    }

    /**
     * A fresh [AllocatorObserveApp] that observes [history] — exactly [D1]
     * then [D2], the only history this test ever bisects over — at its own
     * instants, one poll per event, then ingests [lines] as a single
     * whole-file read, and returns its served `/state/report` document
     * published at [now] over [window]. Used only by [assertNoDivergence]'s
     * bisection, so a passing scenario never pays for it.
     */
    private fun servedReportOverLines(
        lines: List<String>,
        history: List<DeclarationSpec>,
        now: Instant,
        window: Duration,
    ): JsonObject {
        val sorted = history.sortedBy { it.observedAt }
        check(sorted == listOf(D1, D2)) {
            "servedReportOverLines only knows how to write allocation.yaml for the fixture's D1/D2 history, got $sorted"
        }

        val rig = Rig("bisect-${bisectSeq++}")
        rig.clock.set(D1.observedAt)
        Files.writeString(rig.declaration, D1_YAML)
        Files.writeString(rig.log, "")
        val app = rig.app(window).start()

        rig.clock.set(D2.observedAt)
        Files.writeString(rig.declaration, D2_YAML)
        app.pollOnce()

        Files.writeString(rig.log, lines.joinToString("") { "$it\n" })
        rig.clock.set(now)
        app.pollOnce()

        return reportOf(app)
    }

    // -----------------------------------------------------------------------
    // Scenario 1: straight replay
    // -----------------------------------------------------------------------

    @Test
    fun `fixture week straight through equals the reference`() {
        val rig = Rig("straight")
        rig.clock.set(STEP_0_AT)
        Files.writeString(rig.declaration, D1_YAML)
        Files.writeString(rig.log, "")
        val app = rig.app().start() // step 0's poll: observes D1 at its own instant, empty log

        rig.append(R1, R2, R3, R4, R5, R6)
        rig.clock.set(AFTER_R6_AT)
        app.pollOnce()

        Files.writeString(rig.declaration, D2_YAML)
        rig.clock.set(STEP_D2_AT)
        app.pollOnce() // observes D2 at its own instant

        rig.append(R7, R8, R9, R10)
        rig.clock.set(NOW)
        app.pollOnce()

        assertNoDivergence(reportOf(app), LINES, rig.physicalLines())
    }

    // -----------------------------------------------------------------------
    // Scenarios 2 and 3: mid-week replacement, with and without a restart
    // -----------------------------------------------------------------------

    /**
     * Steps 0..2 (D1, r1..r6, D2 — `AppRestartEquivalenceTest`'s own script),
     * then r7 alone so seven lines are ingested before anything is replaced
     * (`WeekFixture.REBASELINE_PREFIX_LINES`). When [restart] is true, the app
     * is stopped and a second one started over the same run directory and log
     * BEFORE the replacement lands, so the restarted app's cold-start read
     * sees the seven-line log and only the NEXT poll sees the five-line
     * replacement — task `computenet-fpml.5.2`'s idiom, reused rather than
     * re-derived.
     *
     * The replacement (`WeekFixture.REBASELINE_REPLACEMENT_LINES`) then lands
     * atomically, r6..r10 are appended, and a final poll at [NOW] leaves the
     * final content at `WeekFixture.REBASELINE_FINAL_LINES`. Returns the app
     * the final poll ran on (the restarted one, when [restart]) together with
     * the [Rig] whose log holds the real final content.
     */
    private fun replaceOnce(restart: Boolean): Pair<AllocatorObserveApp, Rig> {
        val rig = Rig(if (restart) "restart" else "rebaseline")
        rig.clock.set(STEP_0_AT)
        Files.writeString(rig.declaration, D1_YAML)
        Files.writeString(rig.log, "")
        var app = rig.app().start()

        rig.append(R1, R2, R3, R4, R5, R6)
        rig.clock.set(AFTER_R6_AT)
        app.pollOnce()

        Files.writeString(rig.declaration, D2_YAML)
        rig.clock.set(STEP_D2_AT)
        app.pollOnce()

        rig.append(R7)
        rig.clock.set(AFTER_R7_AT)
        app.pollOnce()
        withClue("seven lines should be ingested before the replacement lands") {
            ingestOf(app).getValue("recordCount").jsonPrimitive.int shouldBe 7
        }

        if (restart) {
            app.stop()
            rig.clock.set(RESTART_AT)
            app = rig.app().start()
            withClue("a restarted app's declaration-history replay should fail on nothing") {
                app.declarationReplayFailures shouldBe 0L
            }
            withClue("the restarted app's cold-start read should see the same seven lines") {
                ingestOf(app).getValue("recordCount").jsonPrimitive.int shouldBe 7
            }
        }

        // The bead's own `unverified:` hypothesis, checked rather than assumed:
        // a five-line replacement of the fixture's seven-line log is shorter in
        // BYTES (fewer lines outweighs R5_CORRECTED's few extra characters), so
        // the re-baseline goes through ReBaselineCause.Truncated, not Replaced.
        val checkpointOffsetAfterSevenLines = ingestOf(app).getValue("checkpointOffset").jsonPrimitive.long
        val replacementBytes =
            REBASELINE_REPLACEMENT_LINES.joinToString("") { "$it\n" }.toByteArray().size.toLong()
        withClue(
            "expected the five-line replacement ($replacementBytes bytes) to be shorter than the seven-line " +
                "log's checkpoint offset ($checkpointOffsetAfterSevenLines bytes), so the Truncated re-baseline " +
                "branch is the one this test exercises",
        ) {
            (replacementBytes < checkpointOffsetAfterSevenLines) shouldBe true
        }

        rig.replace(*REBASELINE_REPLACEMENT_LINES.toTypedArray())
        rig.clock.set(AFTER_REPLACEMENT_AT)
        app.pollOnce()
        withClue("the replacement should be seen as exactly one re-baseline, converged to five records") {
            val afterReplacement = ingestOf(app)
            afterReplacement.getValue("reBaselineCount").jsonPrimitive.long shouldBe 1L
            afterReplacement.getValue("recordCount").jsonPrimitive.int shouldBe 5
        }

        rig.append(R6, R7, R8, R9, R10)
        rig.clock.set(NOW)
        app.pollOnce()

        return app to rig
    }

    @Test
    fun `mid-week replacement re-baselines and converges to the reference over the final content`() {
        val (app, rig) = replaceOnce(restart = false)
        val actual = reportOf(app)

        assertNoDivergence(actual, REBASELINE_FINAL_LINES, rig.physicalLines())

        withClue("computenet's corrected hour should have carried through to the served report") {
            actual.at("window", "perProject", "computenet", "enactedHours")
                .jsonPrimitive.content.toDouble() shouldBeNear 13.0
        }
        ingestOf(app).getValue("reBaselineCount").jsonPrimitive.long shouldBe 1L
    }

    @Test
    fun `restart then re-baseline in the same week still equals the reference`() {
        val (app, rig) = replaceOnce(restart = true)
        val actual = reportOf(app)

        assertNoDivergence(actual, REBASELINE_FINAL_LINES, rig.physicalLines())

        withClue("computenet's corrected hour should have carried through across the restart") {
            actual.at("window", "perProject", "computenet", "enactedHours")
                .jsonPrimitive.content.toDouble() shouldBeNear 13.0
        }
        ingestOf(app).getValue("reBaselineCount").jsonPrimitive.long shouldBe 1L
    }
}
