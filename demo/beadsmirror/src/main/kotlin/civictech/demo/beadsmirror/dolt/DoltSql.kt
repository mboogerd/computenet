package civictech.demo.beadsmirror.dolt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Runs `dolt sql` as a subprocess against a given Dolt database root and
 * parses its JSON row output. This is the access layer for computenet-dqj.1's
 * feed reader (and, one level further out, the projector) — it knows nothing
 * about `dolt_diff_issues`/`dolt_diff_dependencies` shapes or bd semantics,
 * only how to turn a SQL query into rows or a typed failure.
 *
 * [root] is the directory a `dolt` invocation should run in — the workspace's
 * `.beads/embeddeddolt/<name>/` directory, per BDS0/the epic's live probe,
 * NOT the `.dolt` subdirectory beneath it.
 */
class DoltSql(private val root: Path) {

    /**
     * Runs [sql] via `dolt sql -r json -q <sql>` and returns its rows as
     * plain JSON objects — the caller (the feed reader) owns interpreting
     * column shapes, since `dolt_diff_issues` carries no stability promise
     * of its own (epic computenet-dqj §3).
     *
     * An empty result set prints as `{}` with no `"rows"` key at all, not
     * `{"rows": []}` — verified against a live scratch workspace — and is
     * treated as zero rows, not an error.
     */
    fun query(sql: String): List<Map<String, JsonElement>> {
        val result = runDolt("sql", "-r", "json", "-q", sql)
        if (result.exitCode != 0) {
            throw DoltSqlException(
                query = sql,
                message = "dolt exited ${result.exitCode}: ${result.stderr.trim().ifEmpty { "(no stderr output)" }}",
            )
        }

        val envelope = result.stdout.trim().ifEmpty { "{}" }
        val parsed = try {
            Json.parseToJsonElement(envelope)
        } catch (e: SerializationException) {
            throw DoltSqlException(sql, "could not parse dolt's output as JSON: $envelope", e)
        }

        val obj = parsed as? JsonObject
            ?: throw DoltSqlException(sql, "expected a JSON object envelope, got: $envelope")

        val rowsElement = obj["rows"] ?: return emptyList()
        val rowsArray = rowsElement as? JsonArray
            ?: throw DoltSqlException(sql, "expected \"rows\" to be a JSON array, got: $rowsElement")

        return rowsArray.map { row ->
            row as? JsonObject
                ?: throw DoltSqlException(sql, "expected each row to be a JSON object, got: $row")
        }
    }

    private fun runDolt(vararg args: String): ProcessOutput {
        val process = ProcessBuilder(listOf("dolt") + args)
            .directory(root.toFile())
            .start()

        // Drain both streams concurrently: a query with enough output to fill
        // one pipe's OS buffer while we block reading the other would
        // deadlock the subprocess against us otherwise.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = thread(name = "dolt-sql-stdout") {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        }
        val stderrReader = thread(name = "dolt-sql-stderr") {
            process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }

        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()

        return ProcessOutput(exitCode, stdout.toString(), stderr.toString())
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)
}
