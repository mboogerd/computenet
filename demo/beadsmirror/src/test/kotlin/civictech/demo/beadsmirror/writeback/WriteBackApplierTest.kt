package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.dolt.DoltSql
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * computenet-6wc.1.3: [WriteBackApplier] against REAL `bd --sandbox` scratch
 * workspaces — one `bd import --allow-stale` per row, the loss record before
 * the import, the outcome from a post-import `bd export` re-read, and a
 * per-row failure that does not stop the queue.
 *
 * The winner map is built from the destination's OWN export renderings and
 * then perturbed, which is deliberate: the applier does not care where the map
 * came from, and a hand-built map missing the fields bd itself requires would
 * make every test impose a clear-everything row and measure the wrong thing.
 *
 * The importer is injected in every test — as a counting wrapper around the
 * real [BdImport] where a real write is wanted, and as a substitute where a
 * failure path has to be reached. That is the seam the bead's R6 uses to
 * inject a failing row without needing bd to refuse it.
 */
class WriteBackApplierTest {

    // ---------------------------------------------------------------- helpers

    /** The destination's current export, as [ExportRow]s. */
    private fun export(ws: BdScratchWorkspace): List<ExportRow> = BdExportReader(ws.root).read()

    private fun row(ws: BdScratchWorkspace, id: String): ExportRow =
        export(ws).single { it.id == id }

    /**
     * A winner's field map for one issue, taken from that issue's own export
     * row — every allowlisted field it actually carries, rendered the way
     * `MirrorProjector.view()` renders fold values (`JsonElement.toString()`).
     */
    private fun winnerFieldsFrom(row: ExportRow): MutableMap<String, String> =
        ImposedFields.FIELDS
            .mapNotNull { field ->
                val value = row.json[field]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                field to value.toString()
            }
            .toMap(LinkedHashMap())

    /**
     * Creates one issue at priority 3 and returns its id.
     *
     * [BdScratchWorkspace.run] merges stderr into stdout, and `bd` prefixes
     * its JSON with a `Warning: ...` line on these scratch workspaces, so the
     * object is taken from the first `{` rather than by parsing the whole
     * captured output.
     */
    private fun createIssue(ws: BdScratchWorkspace, title: String): String {
        val output = ws.run("create", title, "-p", "3", "--json")
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

    /** A marker recorded into the same ordered list as the events, so ordering is assertable. */
    private data class ImporterCalled(val row: JsonObject)

    // --------------------------------------------------------------- the tests

    /**
     * R1 / criterion 1: a differing priority is imposed through exactly one
     * importer call, whose row names only that issue; the destination's
     * re-export and `dolt_log` both show the write.
     */
    @Test
    fun `R1 - a differing priority is imposed through exactly one single-row import`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R1 subject")
            // A bystander the winner does not mention at all: the imposed row
            // must name only the issue being imposed.
            val otherId = createIssue(ws, "R1 bystander")
            val winner = mapOf(id to winnerFieldsFrom(row(ws, id)).apply { put("priority", "1") })
            val importer = CountingImporter(ws)
            val commitsBefore = doltCommits(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { winner }).applyOnce()

            report.importerInvocations shouldBe 1
            importer.rows.single()["id"] shouldBe JsonPrimitive(id)
            // The row names ONE issue: no other created id appears anywhere in it.
            importer.rows.single().toString() shouldNotContain otherId
            report.imposed shouldBe 1
            report.failed shouldBe 0
            row(ws, id).json["priority"] shouldBe JsonPrimitive(1)
            doltCommits(ws) shouldBe commitsBefore + 1
            report.events.filterIsInstance<WriteBackEvent.Imposed>()
                .single().observed["priority"] shouldBe JsonPrimitive(1)
        }
    }

    /**
     * R2 / criterion 2: the `PreFlight` loss record is observable strictly
     * BEFORE the importer runs. Events and importer calls go into ONE ordered
     * list, so the assertion is about order and not merely about presence.
     */
    @Test
    fun `R2 - the PreFlight loss record is emitted before the importer is called`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R2 subject")
            ws.run("update", id, "--notes", "local-only")
            val winner = mapOf(id to winnerFieldsFrom(row(ws, id)).apply { remove("notes") })

            val trace = mutableListOf<Any>()
            val real = BdImport(ws.root)
            val importer: (JsonObject) -> ImportResult = { row ->
                trace += ImporterCalled(row)
                real.importRow(row)
            }

            val report = WriteBackApplier({ export(ws) }, importer, { winner }, onEvent = { trace += it }).applyOnce()

            // ONE assertion carrying the whole criterion, and it discriminates in
            // BOTH directions: the PreFlight events lying strictly BEFORE the
            // importer call must be exactly one, carrying exactly the loss.
            // Drop the emission and this is empty; emit it after the import and
            // it is empty too. (An `indexOf(PreFlight) < indexOf(importer)`
            // comparison would NOT do: with the emission dropped the PreFlight
            // index is -1, which still compares less than the import's 0.)
            val importIndex = trace.indexOfFirst { it is ImporterCalled }
            importIndex shouldBeGreaterThan -1
            trace.subList(0, importIndex)
                .filterIsInstance<WriteBackEvent.PreFlight>()
                .single() shouldBe WriteBackEvent.PreFlight(
                id,
                listOf(FieldLoss("notes", old = JsonPrimitive("local-only"), new = null)),
            )
            report.imposed shouldBe 1
            row(ws, id).json["notes"].let { it == null || it is JsonNull } shouldBe true
        }
    }

    /**
     * R5 / criterion 3: a winner equal to the destination runs NO import at
     * all. Asserted on the invocation count, not the commit count — an
     * identical-row import exits zero and adds no Dolt commit, so a commit
     * count alone cannot tell the two apart (measured 2026-09-12).
     */
    @Test
    fun `R5 - a winner equal to the destination runs no import`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R5 subject")
            val winner = mapOf(id to winnerFieldsFrom(row(ws, id)))
            val importer = CountingImporter(ws)
            val commitsBefore = doltCommits(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { winner }).applyOnce()

            report.importerInvocations shouldBe 0
            importer.rows.shouldHaveSize(0)
            report.events shouldContainExactly listOf(WriteBackEvent.Skipped(id, SkipReason.Equal))
            doltCommits(ws) shouldBe commitsBefore
        }
    }

    /**
     * R5 variant: the same equality holds when the winner renders `updated_at`
     * in the Dolt diff-feed form (`"2026-09-12 07:17:03"`) rather than the
     * export's RFC3339 `Z` form — the shape the mirror's fold actually holds.
     */
    @Test
    fun `R5 - a winner whose updated_at is the Dolt rendering is still equal`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R5 dolt-rendering subject")
            val fields = winnerFieldsFrom(row(ws, id))
            val exportForm = fields.getValue("updated_at").trim('"')
            fields["updated_at"] = "\"" + exportForm.removeSuffix("Z").replace('T', ' ') + "\""
            val importer = CountingImporter(ws)

            val report = WriteBackApplier({ export(ws) }, importer::invoke, { mapOf(id to fields) }).applyOnce()

            // The importer's OWN record, not just the applier's counter: the
            // counter is bookkeeping the applier could get wrong independently
            // of whether a subprocess actually ran.
            importer.rows.shouldHaveSize(0)
            report.importerInvocations shouldBe 0
            report.events shouldContainExactly listOf(WriteBackEvent.Skipped(id, SkipReason.Equal))
        }
    }

    /**
     * R6 seam / criterion 4: an injected failure for Y does not stop X and Z,
     * a second pass with the SAME winner retries nothing, and a third pass
     * with Y's winner CHANGED imposes it once.
     */
    @Test
    fun `R6 - a failed row neither stops the queue nor is retried until its winner changes`() {
        BdScratchWorkspace.create().use { ws ->
            val x = createIssue(ws, "R6 subject X")
            val y = createIssue(ws, "R6 subject Y")
            val z = createIssue(ws, "R6 subject Z")

            var winner = listOf(x, y, z).associateWith { id ->
                winnerFieldsFrom(row(ws, id)).apply { put("priority", "1") }
            }

            val real = BdImport(ws.root)
            var calls = mutableListOf<String>()
            val importer: (JsonObject) -> ImportResult = { row ->
                val id = (row["id"] as JsonPrimitive).content
                calls += id
                if (id == y) {
                    ImportResult(1, """{"error":"injected failure","schema_version":1}""", "")
                } else {
                    real.importRow(row)
                }
            }
            val applier = WriteBackApplier({ export(ws) }, importer, { winner })

            val first = applier.applyOnce()

            calls shouldContainExactly listOf(x, y, z).sorted()
            first.importerInvocations shouldBe 3
            first.imposed shouldBe 2
            first.failed shouldBe 1
            first.events.filterIsInstance<WriteBackEvent.Imposed>().map { it.issueId } shouldContainExactly
                listOf(x, z).sorted()
            val failure = first.events.filterIsInstance<WriteBackEvent.Failed>().single()
            failure.issueId shouldBe y
            failure.failure.shouldBeInstanceOf<WriteBackFailure.ImportExited>().let {
                it.exitCode shouldBe 1
                it.stdout shouldContain "injected failure"
            }

            // Second pass, same winner: X and Z are now equal; Y is remembered.
            calls = mutableListOf()
            val second = applier.applyOnce()

            second.importerInvocations shouldBe 0
            calls.shouldHaveSize(0)
            second.events.filterIsInstance<WriteBackEvent.Skipped>()
                .single { it.issueId == y }.reason shouldBe SkipReason.PreviouslyFailed
            second.events.filterIsInstance<WriteBackEvent.Skipped>()
                .filter { it.issueId != y }.map { it.reason } shouldContainExactly
                listOf(SkipReason.Equal, SkipReason.Equal)

            // Third pass, Y's winner CHANGED: a new imposition, imported once.
            winner = winner.mapValues { (id, fields) ->
                if (id == y) LinkedHashMap(fields).apply { put("priority", "2") } else fields
            }
            calls = mutableListOf()
            val third = applier.applyOnce()

            calls shouldContainExactly listOf(y)
            third.importerInvocations shouldBe 1
        }
    }

    /**
     * R6 real / criterion 5: a genuine `bd import` refusal — an `updated_at`
     * that is not a timestamp at all — is captured as `ImportExited` with bd's
     * own error text, and the destination row is untouched.
     */
    @Test
    fun `R6 real - a genuine bd import refusal is captured and leaves the row unchanged`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "R6 real subject")
            val before = row(ws, id).json
            val winner = mapOf(
                id to winnerFieldsFrom(row(ws, id)).apply { put("updated_at", "\"not-a-time\"") },
            )

            val report = WriteBackApplier.forWorkspace(ws.root, { winner }).applyOnce()

            report.importerInvocations shouldBe 1
            report.failed shouldBe 1
            val failure = report.events.filterIsInstance<WriteBackEvent.Failed>().single()
            failure.issueId shouldBe id
            failure.failure.shouldBeInstanceOf<WriteBackFailure.ImportExited>().let {
                it.exitCode shouldBe 1
                it.stdout shouldContain "error"
            }
            row(ws, id).json shouldBe before
        }
    }

    /**
     * Criterion 6: an import that exits ZERO but writes nothing is a FAILURE,
     * because the outcome is decided by the post-import re-read and not by the
     * report. The mismatching fields are named.
     */
    @Test
    fun `an import that exits zero but writes nothing is a ReadBackMismatch naming the fields`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "read-back subject")
            val winner = mapOf(id to winnerFieldsFrom(row(ws, id)).apply { put("priority", "1") })

            val report = WriteBackApplier(
                { export(ws) },
                { ImportResult(0, """{"created":1,"ids":["$id"],"skipped":0,"source":"stdin"}""", "") },
                { winner },
            ).applyOnce()

            report.importerInvocations shouldBe 1
            report.imposed shouldBe 0
            report.failed shouldBe 1
            val failure = report.events.filterIsInstance<WriteBackEvent.Failed>().single()
            failure.issueId shouldBe id
            failure.failure.shouldBeInstanceOf<WriteBackFailure.ReadBackMismatch>()
                .fields.map { it.field } shouldContainExactly listOf("priority")
        }
    }

    /**
     * Criterion 6's other half, and the reason `updated_at` is excluded: a row
     * that lands with a DIFFERENT stored `updated_at` than was imposed is
     * still `Imposed`, and the re-read value rides inside `observed` rather
     * than being adjudicated.
     */
    @Test
    fun `a differing stored updated_at is reported in observed, not adjudicated as a mismatch`() {
        BdScratchWorkspace.create().use { ws ->
            val id = createIssue(ws, "updated-at subject")
            val fields = winnerFieldsFrom(row(ws, id)).apply { put("priority", "1") }
            // E4: an incoming sub-second >= .500 is rounded UP by bd on the way
            // in, so what is stored differs from what was imposed by design.
            val exportInstant = Instant.parse(fields.getValue("updated_at").trim('"'))
            val imposedInstant = exportInstant.plusMillis(700)
            fields["updated_at"] = "\"$imposedInstant\""

            val report = WriteBackApplier.forWorkspace(ws.root, { mapOf(id to fields) }).applyOnce()

            report.imposed shouldBe 1
            report.failed shouldBe 0
            val observed = report.events.filterIsInstance<WriteBackEvent.Imposed>().single().observed
            observed["priority"] shouldBe JsonPrimitive(1)
            // Rounded UP to the next whole second, and NOT equal to what was
            // imposed — which is exactly why the field is excluded from the
            // read-back comparison rather than adjudicated.
            Instant.parse((observed.getValue("updated_at") as JsonPrimitive).content) shouldBe
                exportInstant.plusSeconds(1)
        }
    }
}
