package civictech.demo.beadsmirror.baseline

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Raised whenever `bd export` output cannot be turned into [ExportRow]s: the
 * `bd` process exited non-zero, or it exited zero and printed a line that is
 * not a JSON object, or one that is a JSON object without a usable `"id"`.
 *
 * The offending [line] rides along in the message, because the failure a
 * re-baseline can produce is "one line of thousands is not what we thought" and
 * a message that does not name it is unactionable. This is the export-side
 * counterpart of [civictech.demo.beadsmirror.dolt.DoltSqlException] (the
 * feed-side subprocess/parse failure) and of
 * [civictech.demo.beadsmirror.feed.FeedShapeException] (the feed-side envelope
 * shape failure).
 */
class BdExportException(
    val line: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(
    "bd export could not be read: $message" + (line?.let { "\n  line: $it" } ?: ""),
    cause,
)

/**
 * One `bd export` line: a whole issue as bd renders it, with its id lifted out.
 *
 * The row is kept in its raw [JsonObject] form on purpose. `bd export` and the
 * `dolt_diff_issues` feed render the same issue differently (BDS1's known
 * asymmetry — see [BaselineBuilder]), and normalising here would hide that
 * behind a lossy intermediate type; the baseline stores exactly what bd
 * printed, and reconciling the two renderings is computenet-dqj.5's business.
 */
data class ExportRow(val id: String, val json: JsonObject) {

    /** The issue's `metadata` object, or `null` when the row carries none (or a non-object one). */
    val metadata: JsonObject?
        get() = json[METADATA_FIELD] as? JsonObject

    companion object {
        const val ID_FIELD: String = "id"
        const val METADATA_FIELD: String = "metadata"
        const val DEPENDENCIES_FIELD: String = "dependencies"
    }
}

/**
 * Reads a bd workspace's current state as a snapshot: `bd export`'s JSONL, one
 * [ExportRow] per issue (computenet-dqj.3.1).
 *
 * This is the *baseline* input half of re-baselining. It has no opinion about
 * projector state, dots or feed positions — [BaselineBuilder] owns turning
 * these rows into records. Splitting them that way is what lets every
 * translation rule be tested on hand-built lines with no `bd` on PATH, through
 * the [parse] seam.
 *
 * **Invocation.** `bd --sandbox export`, with the subprocess's working
 * directory set to [workspaceRoot]. `--sandbox` is the same guard
 * [civictech.demo.beadsmirror.BdScratchWorkspace] uses at init time: without
 * it, `bd` resolves upwards and a scratch workspace whose database has gone
 * missing would silently export *this repository's* live `.beads` instead of
 * failing (verified 2026-08-16: `bd --sandbox export` outside any workspace
 * errors with "no beads database found" rather than resolving outward).
 *
 * **Failure is loud and total.** The whole output is parsed before any row is
 * returned, so a caller never re-baselines off a partially-read export.
 */
class BdExportReader(private val workspaceRoot: Path) {

    /** Runs `bd --sandbox export` in [workspaceRoot] and parses every line. */
    fun read(): List<ExportRow> = parse(runExport())

    private fun runExport(): List<String> {
        val process = ProcessBuilder("bd", "--sandbox", "export")
            .directory(workspaceRoot.toFile())
            .start()

        // Drain both streams concurrently, for the reason DoltSql does: an
        // export large enough to fill one pipe's OS buffer while we block on
        // the other deadlocks the subprocess against us.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = thread(name = "bd-export-stdout") {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        }
        val stderrReader = thread(name = "bd-export-stderr") {
            process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }

        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()

        if (exitCode != 0) {
            throw BdExportException(
                line = null,
                message = "bd exited $exitCode in $workspaceRoot: " +
                    stderr.toString().trim().ifEmpty { "(no stderr output)" },
            )
        }
        return stdout.lines()
    }

    companion object {

        /**
         * The pre-read seam: parse already-captured `bd export` output.
         *
         * Blank lines are skipped (the JSONL stream's trailing newline is one).
         * Every other line must be a JSON object carrying a non-blank string
         * `"id"`; anything else raises [BdExportException] naming that line
         * rather than being skipped, because a silently dropped issue is a
         * baseline that is quietly missing state.
         *
         * Non-issue record types are not filtered out here: bd's export
         * currently emits `_type: "issue"` lines only (verified live
         * 2026-08-16), and a future non-issue line without an `"id"` should
         * surface as this loud failure rather than as a mirror that decided on
         * its own what to ignore.
         */
        fun parse(lines: Iterable<String>): List<ExportRow> =
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else row(trimmed)
            }

        private fun row(line: String): ExportRow {
            val parsed = try {
                Json.parseToJsonElement(line)
            } catch (e: SerializationException) {
                throw BdExportException(line, "line is not JSON", e)
            }
            val obj = parsed as? JsonObject
                ?: throw BdExportException(line, "line is not a JSON object")
            val id = obj[ExportRow.ID_FIELD]
                ?: throw BdExportException(line, "line has no \"${ExportRow.ID_FIELD}\"")
            val text = (id as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw BdExportException(line, "\"${ExportRow.ID_FIELD}\" is not a string: $id")
            if (text.isBlank()) throw BdExportException(line, "\"${ExportRow.ID_FIELD}\" is blank")
            return ExportRow(text, obj)
        }
    }
}
