package civictech.demo.allocatorobserve.oracle

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The external half of the F5 oracle (design entry fpml.5-D7): compares a
 * `report` document produced OUTSIDE this repository — by socaity's replay
 * script, once `socaity-9wu` ships — against what this module computes for
 * the same log, the same explicit declaration history, the same `now` and
 * the same window length.
 *
 * ## This test is GATED, and CI never claims it ran
 *
 * As of this task (`computenet-fpml.5.4`, 2026-09-18) `socaity-9wu` is open
 * and no replay script exists in socaity.dev, so there is no real external
 * report to compare against in this environment, let alone in CI. Without
 * `-Pallocator.oracle.report=<file>` this test calls
 * [org.junit.jupiter.api.Assumptions.assumeTrue] with `false`, which JUnit
 * reports as **skipped** — a distinct outcome from passing, visible as a
 * `<skipped>` element in the JUnit XML and counted separately by
 * `.claude/skills/work/scripts/junit-count.py`. A gated test that silently
 * passed when its input was absent would report evidence nobody produced;
 * this one instead visibly reports that the comparison was not run. See the
 * README's "Running against the socaity replay script" section for the
 * command that supplies the four inputs and runs it for real.
 *
 * ## What "gated" does not mean
 *
 * It does not mean untested. [ExternalHarnessSelfTest] below exercises the
 * exact same [runComparison] code path this test calls, ungated, using
 * [ReferenceReport] as a stand-in external producer (never a report this
 * module itself produced and relabelled as external — see the feature's
 * non-goals). That is the proof the plumbing works; only the cross-repo
 * comparison against socaity's own script is gated.
 */
class ExternalOracleComparisonTest {

    @Test
    fun `external oracle report is compared field-for-field against the served pipeline`() {
        val reportProperty = System.getProperty("allocator.oracle.report")
        assumeTrue(
            reportProperty != null,
            "external oracle report not supplied (-Pallocator.oracle.report=<file>): comparison NOT run",
        )
        val logProperty =
            requireNotNull(System.getProperty("allocator.oracle.log")) {
                "allocator.oracle.report was supplied but allocator.oracle.log was not"
            }
        val declarationsProperty =
            requireNotNull(System.getProperty("allocator.oracle.declarations")) {
                "allocator.oracle.report was supplied but allocator.oracle.declarations was not"
            }
        val nowProperty =
            requireNotNull(System.getProperty("allocator.oracle.now")) {
                "allocator.oracle.report was supplied but allocator.oracle.now was not"
            }
        val windowHours = (System.getProperty("allocator.oracle.windowHours") ?: "168").toLong()

        val reportFile = Path.of(reportProperty)
        val logFile = Path.of(logProperty)
        val history = ReportUnderTest.parseHistory(Path.of(declarationsProperty))
        val now = Instant.parse(nowProperty)
        val windowLength = Duration.ofHours(windowHours)

        val expected = Json.parseToJsonElement(Files.readString(reportFile))
        val divergences = runComparison(expected, logFile, history, now, windowLength)
        divergences.forEach { println("DIVERGENCE ${it.path} expected=${it.expected} actual=${it.actual}") }

        withClue("${divergences.size} divergence(s) against the external oracle report: $divergences") {
            divergences.shouldBeEmpty()
        }
    }
}

/**
 * The plumbing both [ExternalOracleComparisonTest] and [ExternalHarnessSelfTest]
 * run: compute what this module serves for [logFile]/[history]/[now]/[windowLength]
 * ([ReportUnderTest.compute]) and list every field on which it disagrees with
 * [expected] ([ReportComparison.compare]).
 */
private fun runComparison(
    expected: JsonElement,
    logFile: Path,
    history: List<DeclarationSpec>,
    now: Instant,
    windowLength: Duration,
): List<Divergence> {
    val actual = ReportUnderTest.compute(logFile, history, now, windowLength)
    return ReportComparison.compare(expected, actual)
}

/**
 * Proves the external-harness plumbing works, ungated, so
 * [ExternalOracleComparisonTest] being skipped in every CI run is not this
 * module's only evidence that it is correct.
 *
 * [ReferenceReport] stands in for the external producer here — the feature's
 * own non-goal rules out ever writing a "socaity report" produced BY this
 * module (or by the reference) and presenting it as the script's real output;
 * this self-test says exactly that in its own name and KDoc, never in a
 * comment a reader has to find first.
 */
class ExternalHarnessSelfTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("spend.jsonl")

    @Test
    fun `an external report matching the fixture week yields zero divergences`() {
        Files.writeString(log, WeekLog)
        val expected = ReferenceReport.compute(LINES, HISTORY, NOW, WINDOW)

        val divergences = runComparison(expected, log, HISTORY, NOW, WINDOW)

        divergences.shouldBeEmpty()
    }

    @Test
    fun `an external report edited to disagree on one field is listed exactly`() {
        Files.writeString(log, WeekLog)
        val expected = ReferenceReport.compute(LINES, HISTORY, NOW, WINDOW)
        val edited = withGlassFactoryEnactedShareSetTo(expected, 0.5)

        val divergences = runComparison(edited, log, HISTORY, NOW, WINDOW)

        divergences.map { it.path } shouldBe listOf("report.window.perProject.glass-factory.enactedShare")
    }

    @Test
    fun `parseHistory round-trips the fixture history`() {
        val historyFile = dir.resolve("history.json")
        Files.writeString(
            historyFile,
            """
            [
              {"observedAt":"${D1.observedAt}","declaration":{"weights":${D1.weights.toJson()},"monthlyCapHours":${D1.monthlyCapHours},"window":${D1.window.toJsonOrNull()}}},
              {"observedAt":"${D2.observedAt}","declaration":{"weights":${D2.weights.toJson()},"monthlyCapHours":${D2.monthlyCapHours},"window":${D2.window.toJsonOrNull()}}}
            ]
            """.trimIndent(),
        )

        val parsed = ReportUnderTest.parseHistory(historyFile)

        parsed shouldBe HISTORY
    }

    private fun Map<String, Double>.toJson(): String =
        entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"$k\":$v" }

    private fun String?.toJsonOrNull(): String = this?.let { "\"$it\"" } ?: "null"

    private val WeekLog: String get() = LINES.joinToString("") { "$it\n" }
}

/** [expected]'s `report.window.perProject.glass-factory.enactedShare`, replaced by [value]. */
private fun withGlassFactoryEnactedShareSetTo(expected: JsonElement, value: Double): JsonElement {
    val root = expected.jsonObject
    val window = root.getValue("window").jsonObject
    val perProject = window.getValue("perProject").jsonObject
    val glassFactory = perProject.getValue("glass-factory").jsonObject

    val editedGlassFactory = JsonObject(glassFactory + ("enactedShare" to JsonPrimitive(value)))
    val editedPerProject = JsonObject(perProject + ("glass-factory" to editedGlassFactory))
    val editedWindow = JsonObject(window + ("perProject" to editedPerProject))
    return JsonObject(root + ("window" to editedWindow))
}
