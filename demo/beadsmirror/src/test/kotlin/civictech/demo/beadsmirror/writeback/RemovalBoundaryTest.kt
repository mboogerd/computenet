package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.dolt.DoltSql
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * Feature computenet-6wc.2, R4 and the unit half of R5, plus "document close
 * as the sole removal interface in the module": pins the write-back layer's
 * removal boundary at the source-scan and applier levels.
 *
 * **R4.** [RemovalBoundaryScanner] flags any double-quoted string literal
 * `"delete"` anywhere under `demo/beadsmirror`'s main source set — the WHOLE
 * module, not only `writeback/`: the module is the unit that can spawn `bd`,
 * and the feature's clause 4 says "the write-back module SHALL contain no
 * invocation of `bd delete`". A double-quoted literal is how Kotlin source
 * actually spells a process argument (`ProcessBuilder("bd", "delete", ...)`),
 * the same distinction [WriteBackSourceGuardTest]'s KDoc explains for
 * `"--allow-stale"`: KDoc prose documenting `bd delete` quotes it with
 * backticks, never as a double-quoted Kotlin string literal, so the scan is
 * precise rather than merely a keyword search.
 *
 * **R5, unit half.** [WriteBackApplier] is exercised against a real `bd`
 * scratch workspace holding one OPEN issue and one CLOSED issue. An empty
 * winner (nothing named at all) leaves both byte-identical, with zero
 * importer invocations, zero events and no Dolt commit; a later pass whose
 * winner names only a third issue still leaves both untouched. This is the
 * closed-row case neither existing test covers:
 * [WriteBackPlannerTest] "an issue present in the export but absent from the
 * fold is never visited" is the PURE planner (no bd at all, no closed-row
 * distinction to make), and [WriteBackBystanderTest] "a bystander never named
 * by the winner survives byte-identically" is real bd but always WITH another
 * issue being imposed in the same pass. Neither proves a CLOSED row survives
 * absence — a closed row must not be reopened by absence any more than an
 * open row is closed by it, which is exactly the asymmetry `bd delete` would
 * otherwise tempt: "the fold no longer lists it" must never be read as
 * "close it" any more than as "delete it".
 */
object RemovalBoundaryScanner {

    data class Violation(val file: String, val match: String)

    private const val FORBIDDEN_LITERAL = "\"delete\""

    /** Pure over source text: whether [text] is clean depends only on its own content. */
    fun scanText(fileLabel: String, text: String): List<Violation> =
        if (text.contains(FORBIDDEN_LITERAL)) listOf(Violation(fileLabel, FORBIDDEN_LITERAL)) else emptyList()

    /**
     * Scans every `.kt` file directly and transitively under [dir]. Fails
     * loudly — never an empty, vacuously "clean" result — when [dir] is
     * missing or holds no `.kt` file, the same guard
     * [WriteBackPurityTest.scanDirectory] and [WriteBackSourceGuardTest]'s
     * `sourceFiles()` carry, for the same reason: a moved or renamed source
     * set must not make the positive test below pass for having scanned
     * nothing.
     */
    fun scanDirectory(dir: File): List<Violation> {
        check(dir.isDirectory) {
            "demo/beadsmirror main source directory does not exist: ${dir.absolutePath} " +
                "— cannot verify the removal-boundary guard against an absent source set."
        }
        val ktFiles = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(ktFiles.isNotEmpty()) {
            "demo/beadsmirror main source directory has no .kt files: ${dir.absolutePath} " +
                "— cannot verify the removal-boundary guard against zero files."
        }
        return ktFiles.flatMap { scanText(it.path, it.readText()) }
    }
}

class RemovalBoundaryTest {

    // ------------------------------------------------------------- R4: scan

    /** The permanent proof the scanner CAN fail: a synthetic `bd delete` invocation is flagged. */
    @Test
    fun `a synthetic source invoking bd delete via a double-quoted literal is flagged`() {
        val tempDir = Files.createTempDirectory("removal-boundary-negative").toFile()
        try {
            val offender = File(tempDir, "Offender.kt")
            offender.writeText(
                """
                package civictech.demo.beadsmirror.writeback

                class Offender {
                    fun run() = ProcessBuilder("bd", "delete", "some-id", "--force").start()
                }
                """.trimIndent(),
            )

            val violations = RemovalBoundaryScanner.scanDirectory(tempDir)

            violations shouldBe listOf(RemovalBoundaryScanner.Violation(offender.path, "\"delete\""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Backtick-quoted KDoc prose naming `bd delete` is NOT a Kotlin string literal and is not flagged. */
    @Test
    fun `backtick KDoc prose mentioning bd delete is not flagged`() {
        val tempDir = Files.createTempDirectory("removal-boundary-kdoc").toFile()
        try {
            File(tempDir, "Innocent.kt").writeText(
                """
                package civictech.demo.beadsmirror.writeback

                /**
                 * This class never invokes `bd delete` — see RemovalBoundaryTest, which is
                 * exactly what proves this file is clean despite the words above.
                 */
                class Innocent
                """.trimIndent(),
            )

            val violations = RemovalBoundaryScanner.scanDirectory(tempDir)

            violations.shouldBeEmpty()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `an absent source directory fails loudly rather than passing vacuously`() {
        val parent = Files.createTempDirectory("removal-boundary-missing").toFile()
        try {
            val missing = File(parent, "does-not-exist")

            val failure = assertThrows<IllegalStateException> {
                RemovalBoundaryScanner.scanDirectory(missing)
            }
            failure.message!!.contains("does not exist") shouldBe true
        } finally {
            parent.deleteRecursively()
        }
    }

    /**
     * The positive gate, over the WHOLE `demo/beadsmirror` main source set —
     * not only `writeback/` — because clause 4 names "the write-back module",
     * i.e. whatever in this module can spawn `bd`. A Gradle Test task's
     * working directory is the project directory (`demo/beadsmirror`), so
     * this resolves to `src/main/kotlin/civictech/demo/beadsmirror`.
     */
    @Test
    fun `the whole demo-beadsmirror main source set carries no double-quoted delete literal`() {
        val mainSourceDir = File("src/main/kotlin/civictech/demo/beadsmirror")

        val violations = RemovalBoundaryScanner.scanDirectory(mainSourceDir)

        withClue("demo/beadsmirror main sources invoke bd delete: $violations") {
            violations.shouldBeEmpty()
        }
    }

    // ------------------------------------------------ R5, unit half: applier

    private fun export(ws: BdScratchWorkspace): List<ExportRow> = BdExportReader(ws.root).read()

    private fun row(ws: BdScratchWorkspace, id: String): ExportRow = export(ws).single { it.id == id }

    private fun createIssue(ws: BdScratchWorkspace, title: String): String {
        val output = ws.run("create", title, "-p", "3", "--json")
        val start = output.indexOf('{')
        check(start >= 0) { "bd create --json printed no JSON object:\n$output" }
        val created = Json.parseToJsonElement(output.substring(start)) as JsonObject
        return (created.getValue("id") as JsonPrimitive).content
    }

    /**
     * A winner's field map for one issue, taken from that issue's own export
     * row — same convention [WriteBackApplierTest] and [WriteBackBystanderTest] use.
     */
    private fun winnerFieldsFrom(row: ExportRow): MutableMap<String, String> =
        ImposedFields.FIELDS
            .mapNotNull { field ->
                val value = row.json[field]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                field to value.toString()
            }
            .toMap(LinkedHashMap())

    private fun doltCommits(ws: BdScratchWorkspace): Int =
        DoltSql(ws.doltRoot).query("select commit_hash from dolt_log").size

    /** A counting importer delegating to the real single-row [BdImport], recording every row it saw. */
    private class CountingImporter(ws: BdScratchWorkspace) {
        private val real = BdImport(ws.root)
        val rows = mutableListOf<JsonObject>()
        fun invoke(row: JsonObject): ImportResult {
            rows += row
            return real.importRow(row)
        }
    }

    /**
     * R5, unit half, the closed-row case: an empty winner (nothing named at
     * all — the strongest form of "absent from the fold") leaves an OPEN
     * issue and a CLOSED issue both string-identical to their pre-pass
     * captures, with zero importer invocations, zero events, and no Dolt
     * commit. A later pass whose winner names only a third issue still
     * leaves both A and B untouched, and imports exactly once, naming that
     * third issue — proving the earlier absence was never latently queued.
     */
    @Test
    fun `an empty winner leaves an open row and a closed row untouched, absence is never a removal`() {
        BdScratchWorkspace.create().use { ws ->
            val open = createIssue(ws, "removal boundary subject, open")
            val closed = createIssue(ws, "removal boundary subject, closed")
            ws.run("close", closed, "--reason", "done")

            val openBefore = row(ws, open).json.toString()
            val closedBefore = row(ws, closed).json.toString()
            val commitsBefore = doltCommits(ws)

            val importer = CountingImporter(ws)
            var winner: Map<String, Map<String, String>> = emptyMap()
            val applier = WriteBackApplier({ export(ws) }, importer::invoke, { winner })

            val firstPass = applier.applyOnce()

            firstPass.importerInvocations shouldBe 0
            importer.rows.shouldBeEmpty()
            firstPass.events.shouldBeEmpty()
            row(ws, open).json.toString() shouldBe openBefore
            row(ws, closed).json.toString() shouldBe closedBefore
            doltCommits(ws) shouldBe commitsBefore

            // Second pass: a winner naming only a THIRD issue. The open and
            // closed rows from the first pass are still untouched; exactly
            // one import runs, and it names the third issue.
            val third = createIssue(ws, "removal boundary subject, imposed")
            winner = mapOf(third to winnerFieldsFrom(row(ws, third)).apply { put("priority", "1") })

            val secondPass = applier.applyOnce()

            secondPass.importerInvocations shouldBe 1
            importer.rows.single()["id"] shouldBe JsonPrimitive(third)
            row(ws, open).json.toString() shouldBe openBefore
            row(ws, closed).json.toString() shouldBe closedBefore
        }
    }
}
