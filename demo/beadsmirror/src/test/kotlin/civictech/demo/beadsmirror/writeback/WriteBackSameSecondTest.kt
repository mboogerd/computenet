package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-6wc.1.4, clause 4 / R4 (observable half): the same-second /
 * sub-second adjudication is decided by a post-import `bd export` RE-READ,
 * never by `bd import`'s own report — because that report's
 * `tie_kept_local_ids`/`stale_skipped_ids`/`updated_issues` fields are
 * measurably untrustworthy (E4: an incoming sub-second `updated_at >= .500`
 * rounds UP and overwrites the local value, yet is reported as a *tie*).
 *
 * Both scenario tests here force the DESTINATION's local `updated_at` to a
 * known whole second first — a direct single-row `bd import --allow-stale`
 * of the issue's own current export row with only `updated_at` patched (the
 * E4 technique from the feature comment thread) — because [BdScratchWorkspace.run]
 * has no way to pipe a forged row into `bd import -`'s stdin, while
 * [BdImport.importRow] already does exactly that.
 */
class WriteBackSameSecondTest {

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

    private fun winnerFieldsFrom(row: ExportRow): MutableMap<String, String> =
        ImposedFields.FIELDS
            .mapNotNull { field ->
                val value = row.json[field]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                field to value.toString()
            }
            .toMap(LinkedHashMap())

    /**
     * Forces [id]'s LOCAL `updated_at` to exactly [instant] (an RFC3339 `Z`
     * string) via a direct single-row import of the issue's own current
     * export row with only `updated_at` overridden — every other field rides
     * along unchanged, so nothing else about the issue is disturbed.
     */
    private fun forceLocalUpdatedAt(ws: BdScratchWorkspace, id: String, instant: String) {
        val current = row(ws, id).json
        val patched = JsonObject(current.toMutableMap().apply { put("updated_at", JsonPrimitive(instant)) })
        BdImport(ws.root).importRow(patched).succeeded shouldBe true
    }

    // --------------------------------------------------------------- the tests

    /**
     * Incoming winner `updated_at` is `...T10:00:00.700Z`; local is forced to
     * `...T10:00:01Z` (what E4's round-half-up would have produced from that
     * same incoming value on the way in). The imposed event's `observed` row
     * must equal an INDEPENDENT post-import `bd export` re-read on every
     * field the applier compares — the outcome is read back, not asserted
     * against the literal incoming value or bd's rounding rule.
     */
    @Test
    fun `a sub-second winner behind the local whole second is imposed and its outcome comes from the re-read`() {
        BdScratchWorkspace.create().use { ws ->
            val x = createIssue(ws, "same-second sub-second subject")
            forceLocalUpdatedAt(ws, x, "2026-01-01T10:00:01Z")

            val fields = winnerFieldsFrom(row(ws, x)).apply {
                put("updated_at", "\"2026-01-01T10:00:00.700Z\"")
                put("priority", "1")
            }

            val report = WriteBackApplier.forWorkspace(ws.root, { mapOf(x to fields) }).applyOnce()

            report.failed shouldBe 0
            val imposedEvent = report.events.filterIsInstance<WriteBackEvent.Imposed>().single()
            val independentReRead = export(ws).single { it.id == x }.json

            // The observed row IS the independent re-read, field for field —
            // not a value pinned to bd's rounding, and not the literal
            // incoming value.
            imposedEvent.observed shouldBe independentReRead
            imposedEvent.observed["priority"] shouldBe JsonPrimitive(1)
        }
    }

    /**
     * Claim (b) E2 variant: incoming and local `updated_at` are the SAME
     * whole second, and a different field (`notes`) changed. The row must
     * still be imposed (a same-second row lands under `--allow-stale`), and
     * its outcome again comes from the re-read.
     */
    @Test
    fun `a same-whole-second winner with a changed field is still imposed and its outcome comes from the re-read`() {
        BdScratchWorkspace.create().use { ws ->
            val x = createIssue(ws, "same-second exact-match subject")
            forceLocalUpdatedAt(ws, x, "2026-01-01T10:00:05Z")

            val fields = winnerFieldsFrom(row(ws, x)).apply {
                put("updated_at", "\"2026-01-01T10:00:05Z\"")
                put("notes", "\"changed by the incoming winner\"")
            }

            val report = WriteBackApplier.forWorkspace(ws.root, { mapOf(x to fields) }).applyOnce()

            report.failed shouldBe 0
            val imposedEvent = report.events.filterIsInstance<WriteBackEvent.Imposed>().single()
            val independentReRead = export(ws).single { it.id == x }.json

            imposedEvent.observed shouldBe independentReRead
            imposedEvent.observed["notes"] shouldBe JsonPrimitive("changed by the incoming winner")
        }
    }

    /**
     * Structural half, expressed as a compile-time-backed reflection check
     * rather than a text scan (this one needs to see field NAMES, which a
     * source-text scan for a literal would only approximate): no
     * [WriteBackEvent] or [WriteBackFailure] variant carries a field whose
     * name reads as one of `bd import`'s untrusted report fields. If a
     * future change ever added a `tieKeptLocal`/`staleSkipped`-shaped field
     * to any of these types, this test fails at the reflection step —
     * independent of whether any test ever exercises that field.
     */
    @Test
    fun `no WriteBackEvent or WriteBackFailure type carries a tie or stale field`() {
        val forbiddenNeedles = listOf("tiekeptlocal", "staleskipped", "tie_kept_local", "stale_skipped")
        val typesToScan = listOf(
            WriteBackEvent.PreFlight::class.java,
            WriteBackEvent.Imposed::class.java,
            WriteBackEvent.Skipped::class.java,
            WriteBackEvent.Failed::class.java,
            WriteBackFailure.ImportExited::class.java,
            WriteBackFailure.ReadBackMismatch::class.java,
            WriteBackFailure.ReadBackMissing::class.java,
            WriteBackFailure.Unrenderable::class.java,
        )

        val offending = typesToScan.flatMap { type ->
            type.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .filter { name -> forbiddenNeedles.any { name.lowercase().contains(it) } }
                .map { "${type.simpleName}.$it" }
        }

        offending.shouldBeEmpty()
    }
}
