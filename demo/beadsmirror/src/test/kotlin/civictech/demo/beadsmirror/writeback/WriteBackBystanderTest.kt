package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-6wc.1.4, clause 3 / R3 (the E3 scenario inverted): a bystander
 * issue the winner does not name survives byte-identically across a
 * write-back pass that imposes some OTHER issue.
 *
 * This is the regression test against ever widening [WriteBackApplier]'s
 * per-pass bundle: bulk `bd import --allow-stale` forces every row bd holds,
 * regardless of local edits, and re-reports even a landed round-up as a "tie"
 * (epic computenet-6wc claim (b) E3/E4,
 * `doc/spike/bds0/claim-b-ordering-authority.md` "Instrument 1"). The
 * applier avoids that entirely by importing exactly one row per issue the
 * winner map actually names ([BdImport.importRow] taking a single
 * [JsonObject], never a batch) — so a bystander absent from the winner
 * cannot be touched, structurally, not just by convention.
 */
class WriteBackBystanderTest {

    // ---------------------------------------------------------------- helpers

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
     * row — every allowlisted field it actually carries, rendered the way
     * `MirrorProjector.view()` renders fold values (`JsonElement.toString()`).
     * Same convention [WriteBackApplierTest] uses.
     */
    private fun winnerFieldsFrom(row: ExportRow): MutableMap<String, String> =
        ImposedFields.FIELDS
            .mapNotNull { field ->
                val value = row.json[field]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                field to value.toString()
            }
            .toMap(LinkedHashMap())

    /** Builds the one-line JSONL row [WriteBackPlanner.buildRow] would, without going through planning at all. */
    private fun buildRowDirectly(id: String, fields: Map<String, String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        for ((field, rendering) in fields) {
            out[field] = renderForImport(field, rendering)
        }
        return JsonObject(out)
    }

    private class CountingImporter(ws: BdScratchWorkspace) {
        private val real = BdImport(ws.root)
        var calls = 0
            private set
        fun invoke(row: JsonObject): ImportResult {
            calls++
            return real.importRow(row)
        }
    }

    // --------------------------------------------------------------- the tests

    /**
     * Plants two never-gossiped edits on bystander Y: a label (labels never
     * ride the fold — [ImposedFields.FIELDS] has no `labels` entry, so it can
     * never even be COMPARED, let alone imposed) and a description edit
     * (description IS an allowlisted field — the one a bulk import WOULD
     * clobber if X and Y were ever batched into one invocation). The winner
     * view names ONLY X. After [WriteBackApplier.applyOnce], Y's `bd export`
     * line must be string-equal, byte for byte, to the line captured before
     * the pass ran, and the importer must have been invoked exactly once.
     */
    @Test
    fun `a bystander never named by the winner survives byte-identically`() {
        BdScratchWorkspace.create().use { ws ->
            val x = createIssue(ws, "bystander subject X")
            val y = createIssue(ws, "bystander subject Y")

            ws.run("update", y, "--add-label", "wip")
            ws.run("update", y, "--description", "y's own never-gossiped description")

            val yBefore = row(ws, y).json.toString()

            val winner = mapOf(x to winnerFieldsFrom(row(ws, x)).apply { put("priority", "1") })
            val importer = CountingImporter(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { winner }).applyOnce()

            report.importerInvocations shouldBe 1
            importer.calls shouldBe 1
            report.imposed shouldBe 1
            report.failed shouldBe 0
            row(ws, x).json["priority"] shouldBe JsonPrimitive(1)

            // The byte-identity assertion: Y's export line, unchanged.
            row(ws, y).json.toString() shouldBe yBefore
        }
    }

    /**
     * Second variant: hand-feed [BdImport.importRow] one row at a time —
     * bypassing [WriteBackApplier]'s per-pass winner filter entirely — for
     * BOTH X (a real change) and Y (a row built from Y's OWN current export,
     * i.e. already equal). Even a REDUNDANT single-row import of an
     * already-equal row must not clobber what it doesn't carry — this is the
     * structural half of the regression: it holds even if a future caller
     * widened the bundle to import Y too, as long as each import stays
     * single-row.
     */
    @Test
    fun `a redundant single-row import of Y's own unchanged row is not a clobber`() {
        BdScratchWorkspace.create().use { ws ->
            val x = createIssue(ws, "bystander redundant subject X")
            val y = createIssue(ws, "bystander redundant subject Y")

            ws.run("update", y, "--add-label", "wip")
            ws.run("update", y, "--description", "y's own never-gossiped description")

            val yBefore = row(ws, y).json.toString()

            val xRow = buildRowDirectly(x, winnerFieldsFrom(row(ws, x)).apply { put("priority", "1") })
            val yRow = buildRowDirectly(y, winnerFieldsFrom(row(ws, y)))

            // Y's own row, from its own export, is indeed already equal — the
            // planner would call this a NoOp. Prove that importing it ANYWAY
            // (redundantly) is still not a clobber.
            WriteBackPlanner.preflight(yRow, row(ws, y)) shouldBe emptyList()

            val bdImport = BdImport(ws.root)
            bdImport.importRow(xRow).succeeded shouldBe true
            bdImport.importRow(yRow).succeeded shouldBe true

            row(ws, x).json["priority"] shouldBe JsonPrimitive(1)
            row(ws, y).json.toString() shouldBe yBefore
        }
    }
}
