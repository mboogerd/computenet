package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.dolt.DoltSql
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-b7my1 (feature computenet-6wc.2, R1-R3): a peer's close lands
 * through [WriteBackApplier] as ordinary field imposition — including where
 * the LOCAL `bd close` guards would refuse it — while `bd close` itself
 * (originate) still refuses and mutates nothing, against REAL `bd --sandbox`
 * scratch workspaces.
 *
 * **"No `bd close` in the applier's command trace" has no command trace to
 * inspect.** [WriteBackPurityTest] pins [BdImport] as the ONLY process
 * spawner in the writeback package, and the importer here is the same
 * injected seam [WriteBackApplierTest] uses — a counting wrapper around the
 * real [BdImport]. So "close is ordinary field imposition, not a special
 * verb" is established the same way every other imposed field is: one
 * importer call per issue, carrying exactly the winner's row.
 *
 * The open-children guard is EPIC-ONLY (doc/spike/bds0/claim-c-close-replication.md
 * C2) — the guard fixture's parent P is created `--type epic`; a task parent
 * with an open child closes fine and would not discriminate R2/R3.
 */
class WriteBackCloseTest {

    // ---------------------------------------------------------------- helpers

    private fun export(ws: BdScratchWorkspace): List<ExportRow> = BdExportReader(ws.root).read()

    private fun row(ws: BdScratchWorkspace, id: String): ExportRow = export(ws).single { it.id == id }

    /** Same convention [WriteBackApplierTest]/[WriteBackBystanderTest] use. */
    private fun winnerFieldsFrom(row: ExportRow): MutableMap<String, String> =
        ImposedFields.FIELDS
            .mapNotNull { field ->
                val value = row.json[field]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                field to value.toString()
            }
            .toMap(LinkedHashMap())

    private fun createIssue(ws: BdScratchWorkspace, title: String, type: String? = null): String {
        val args = mutableListOf("create", title, "-p", "3", "--json")
        if (type != null) {
            args += listOf("--type", type)
        }
        val output = ws.run(*args.toTypedArray())
        val start = output.indexOf('{')
        check(start >= 0) { "bd create --json printed no JSON object:\n$output" }
        val created = Json.parseToJsonElement(output.substring(start)) as JsonObject
        return (created.getValue("id") as JsonPrimitive).content
    }

    private fun doltCommits(ws: BdScratchWorkspace): Int =
        DoltSql(ws.doltRoot).query("select commit_hash from dolt_log").size

    /** A counting importer delegating to the real single-row [BdImport]. */
    private class CountingImporter(ws: BdScratchWorkspace) {
        private val real = BdImport(ws.root)
        val rows = mutableListOf<JsonObject>()
        fun invoke(row: JsonObject): ImportResult {
            rows += row
            return real.importRow(row)
        }
    }

    /** [winnerFieldsFrom] for [id], overridden with a close (status/closed_at/close_reason). */
    private fun closedWinnerFor(ws: BdScratchWorkspace, id: String, closedAt: String, reason: String) =
        winnerFieldsFrom(row(ws, id)).apply {
            put("status", "\"closed\"")
            put("closed_at", "\"$closedAt\"")
            put("close_reason", "\"$reason\"")
        }

    // --------------------------------------------------------------- the tests

    /**
     * R1 mechanism half / criterion 1: an open issue's close (status,
     * closed_at, close_reason) is imposed through exactly one importer call;
     * the re-exported row carries all three byte-identically.
     */
    @Test
    fun `R1 - a close is imposed through exactly one single-row import`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R1 close subject")
            row(ws, id).json["status"] shouldBe JsonPrimitive("open")

            val closedAt = "2026-09-18T09:00:00Z"
            val winner = mapOf(id to closedWinnerFor(ws, id, closedAt, "done"))
            val importer = CountingImporter(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { winner }).applyOnce()

            report.importerInvocations shouldBe 1
            val imported = importer.rows.single()
            imported["id"] shouldBe JsonPrimitive(id)
            imported["status"] shouldBe JsonPrimitive("closed")
            imported["closed_at"] shouldBe JsonPrimitive(closedAt)
            imported["close_reason"] shouldBe JsonPrimitive("done")
            report.imposed shouldBe 1
            report.failed shouldBe 0

            val after = row(ws, id).json
            after["status"] shouldBe JsonPrimitive("closed")
            after["closed_at"] shouldBe JsonPrimitive(closedAt)
            after["close_reason"] shouldBe JsonPrimitive("done")

            val observed = report.events.filterIsInstance<WriteBackEvent.Imposed>().single().observed
            observed["status"] shouldBe JsonPrimitive("closed")
            observed["closed_at"] shouldBe JsonPrimitive(closedAt)
            observed["close_reason"] shouldBe JsonPrimitive("done")
        }
    }

    /**
     * R2 / criterion 2: a guard-violating close — an epic with an open
     * parent-child child, a task blocked by an open blocker — applies through
     * the applier right after the SAME structure's own `bd close` just
     * refused it one command earlier. The child and blocker are absent from
     * the winner map and survive byte-identically.
     */
    @Test
    fun `R2 - a guard-violating close applies through the applier after bd close refused it`() {
        BdScratchWorkspace.create().use { ws ->
            val p = createIssue(ws, "R2 epic parent", type = "epic")
            val k = createIssue(ws, "R2 open child")
            ws.run("dep", "add", k, p, "--type", "parent-child")

            val q = createIssue(ws, "R2 blocked task")
            val r = createIssue(ws, "R2 open blocker")
            ws.run("dep", "add", q, "--blocked-by", r)

            // Control, in the same test: bd close on each refuses, mutates nothing.
            val pRefusal = ws.runAllowingFailure("close", p, "--reason", "originate attempt")
            pRefusal.exitCode shouldBe 1
            pRefusal.output shouldContain "cannot close"
            val qRefusal = ws.runAllowingFailure("close", q, "--reason", "originate attempt")
            qRefusal.exitCode shouldBe 1
            qRefusal.output shouldContain "cannot close"

            row(ws, p).json["status"] shouldBe JsonPrimitive("open")
            row(ws, p).json["closed_at"].let { it == null || it is JsonNull } shouldBe true
            row(ws, q).json["status"] shouldBe JsonPrimitive("open")
            row(ws, q).json["closed_at"].let { it == null || it is JsonNull } shouldBe true

            val kBefore = row(ws, k).json.toString()
            val rBefore = row(ws, r).json.toString()

            val closedAt = "2026-09-18T09:05:00Z"
            val winner = mapOf(
                p to closedWinnerFor(ws, p, closedAt, "adjudicated elsewhere"),
                q to closedWinnerFor(ws, q, closedAt, "adjudicated elsewhere"),
            )
            val importer = CountingImporter(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { winner }).applyOnce()

            report.imposed shouldBe 2
            report.failed shouldBe 0
            report.importerInvocations shouldBe 2

            val pAfter = row(ws, p).json
            pAfter["status"] shouldBe JsonPrimitive("closed")
            pAfter["closed_at"] shouldBe JsonPrimitive(closedAt)
            pAfter["close_reason"] shouldBe JsonPrimitive("adjudicated elsewhere")

            val qAfter = row(ws, q).json
            qAfter["status"] shouldBe JsonPrimitive("closed")
            qAfter["closed_at"] shouldBe JsonPrimitive(closedAt)
            qAfter["close_reason"] shouldBe JsonPrimitive("adjudicated elsewhere")

            // K and R were never named by the winner and survive byte-identically.
            row(ws, k).json.toString() shouldBe kBefore
            row(ws, r).json.toString() shouldBe rBefore
        }
    }

    /**
     * R3 / criterion 3: `bd close` against the same guard-violating structure
     * exits non-zero, mutates nothing (byte-identical export), and adds no
     * Dolt commit. Detection is by exit status and the `cannot close` prose —
     * NEVER an error identifier (spike C2: `ErrCloseBlocked`/
     * `ErrCloseOpenChildren` are not observable).
     */
    @Test
    fun `R3 - bd close on a guard-violating structure refuses and mutates nothing`() {
        BdScratchWorkspace.create().use { ws ->
            val p = createIssue(ws, "R3 epic parent", type = "epic")
            val k = createIssue(ws, "R3 open child")
            ws.run("dep", "add", k, p, "--type", "parent-child")

            val q = createIssue(ws, "R3 blocked task")
            val r = createIssue(ws, "R3 open blocker")
            ws.run("dep", "add", q, "--blocked-by", r)

            val pBefore = row(ws, p).json.toString()
            val qBefore = row(ws, q).json.toString()
            val commitsBefore = doltCommits(ws)

            val pRefusal = ws.runAllowingFailure("close", p, "--reason", "originate attempt")
            val qRefusal = ws.runAllowingFailure("close", q, "--reason", "originate attempt")

            pRefusal.exitCode shouldBe 1
            pRefusal.output shouldContain "cannot close"
            qRefusal.exitCode shouldBe 1
            qRefusal.output shouldContain "cannot close"

            row(ws, p).json.toString() shouldBe pBefore
            row(ws, q).json.toString() shouldBe qBefore
            doltCommits(ws) shouldBe commitsBefore
        }
    }
}
