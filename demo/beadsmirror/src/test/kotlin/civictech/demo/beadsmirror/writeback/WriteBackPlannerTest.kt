package civictech.demo.beadsmirror.writeback

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.baseline.ExportRow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-6wc.1.1: [WriteBackPlanner.plan] turns a fold + a destination
 * `bd export` into single-row [PlanOutcome]s. Hand-built [ExportRow]s (via
 * [BdExportReader.parse]) and hand-built view maps throughout — pure, no
 * `bd`/`dolt` on PATH.
 *
 * Numbered tests trace the bead's numbered acceptance criteria 1-7 (8 lives
 * in [WriteBackPurityTest]).
 */
class WriteBackPlannerTest {

    private fun exportRow(line: String): ExportRow = BdExportReader.parse(listOf(line)).single()

    private fun impose(outcomes: List<PlanOutcome>, issueId: String): PlanOutcome.Impose =
        outcomes.single { it is PlanOutcome.Impose && it.imposition.issueId == issueId } as PlanOutcome.Impose

    @Test
    fun `1 - a differing priority is imposed with the exact loss shape`() {
        val view = mapOf("X" to mapOf("priority" to "1"))
        val export = listOf(exportRow("""{"id":"X","priority":3}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        val outcome = impose(outcomes, "X")
        outcome.imposition.row["priority"] shouldBe JsonPrimitive(1)
        outcome.imposition.losses shouldBe listOf(FieldLoss("priority", old = JsonPrimitive(3), new = JsonPrimitive(1)))
    }

    @Test
    fun `2 - a field the winner lacks is omitted from the row, not nulled`() {
        val view = mapOf("X" to emptyMap<String, String>())
        val export = listOf(exportRow("""{"id":"X","notes":"local-only"}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        val outcome = impose(outcomes, "X")
        outcome.imposition.row.containsKey("notes") shouldBe false
        outcome.imposition.losses shouldBe listOf(FieldLoss("notes", old = JsonPrimitive("local-only"), new = null))
    }

    @Test
    fun `3 - agreeing datetime renderings (dolt space form vs export Z form) are a no-op, not a phantom imposition`() {
        val view = mapOf("X" to mapOf("updated_at" to "\"2026-09-12 07:17:03\""))
        val export = listOf(exportRow("""{"id":"X","updated_at":"2026-09-12T07:17:03Z"}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        outcomes shouldBe listOf(PlanOutcome.NoOp("X"))
    }

    @Test
    fun `4 - a dolt-rendered datetime is re-rendered RFC3339 Z, a sub-second RFC3339 value passes through unchanged`() {
        val doltRendered = renderForImport("updated_at", "\"2026-09-12 07:17:03\"")
        doltRendered shouldBe JsonPrimitive("2026-09-12T07:17:03Z")

        val subSecond = renderForImport("updated_at", "\"2026-09-12T10:00:00.700Z\"")
        subSecond shouldBe JsonPrimitive("2026-09-12T10:00:00.700Z")
    }

    @Test
    fun `5 - the built row carries no key outside ImposedFields plus id`() {
        val view = mapOf("X" to mapOf("priority" to "1"))
        val export = listOf(
            exportRow(
                """{"id":"X","priority":3,"labels":["a"],"comments":[{"text":"hi"}],
                    |"dependencies":[{"issue_id":"X","depends_on_id":"Y","type":"blocks"}],
                    |"_type":"issue","comment_count":1,"content_hash":"deadbeef"}"""
                    .trimMargin().replace("\n", ""),
            ),
        )

        val outcomes = WriteBackPlanner.plan(view, export)

        val row = impose(outcomes, "X").imposition.row
        val disallowed = setOf("labels", "comments", "dependencies", "_type", "comment_count", "content_hash")
        disallowed.forEach { key -> row.containsKey(key) shouldBe false }
        row.keys.all { it == "id" || it in ImposedFields.FIELDS } shouldBe true
    }

    @Test
    fun `6 - an unrenderable field yields Unrenderable for that issue, other issues still plan`() {
        val view = mapOf(
            "X" to mapOf("priority" to "not json at all"),
            "Y" to mapOf("priority" to "1"),
        )
        val export = listOf(exportRow("""{"id":"Y","priority":3}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        outcomes shouldBe listOf(
            PlanOutcome.Unrenderable("X", "priority", "not json at all"),
            impose(outcomes, "Y"),
        )
    }

    @Test
    fun `7 - plan is deterministic and ordered by issue id`() {
        val view = mapOf(
            "z-issue" to mapOf("priority" to "1"),
            "a-issue" to mapOf("priority" to "2"),
        )
        val export = emptyList<ExportRow>()

        val first = WriteBackPlanner.plan(view, export)
        val second = WriteBackPlanner.plan(view, export)

        first shouldBe second
        first.map { (it as PlanOutcome.Impose).imposition.issueId } shouldBe listOf("a-issue", "z-issue")
    }

    @Test
    fun `an issue present in the fold but absent from the export is imposed, so bd import creates it`() {
        val view = mapOf("new-issue" to mapOf("priority" to "1", "title" to "\"New\""))
        val export = emptyList<ExportRow>()

        val outcomes = WriteBackPlanner.plan(view, export)

        val outcome = impose(outcomes, "new-issue")
        outcome.imposition.row["id"] shouldBe JsonPrimitive("new-issue")
        outcome.imposition.row["priority"] shouldBe JsonPrimitive(1)
        outcome.imposition.row["title"] shouldBe JsonPrimitive("New")
    }

    @Test
    fun `an issue present in the export but absent from the fold is never visited`() {
        val view = emptyMap<String, Map<String, String>>()
        val export = listOf(exportRow("""{"id":"export-only","priority":1}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        outcomes shouldBe emptyList()
    }

    @Test
    fun `a fold value equal to a JSON null export value is a no-op, not a clearing imposition`() {
        val view = mapOf("X" to emptyMap<String, String>())
        val export = listOf(exportRow("""{"id":"X","notes":null}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        outcomes shouldBe listOf(PlanOutcome.NoOp("X"))
    }

    @Test
    fun `non-datetime allowlisted values pass through renderForImport structurally`() {
        val element = renderForImport("metadata", """{"cn_dot":"abc"}""")
        element shouldBe JsonObject(mapOf("cn_dot" to JsonPrimitive("abc")))
    }

    @Test
    fun `3b - agreeing instants still no-op when the export side renders an explicit zero fraction`() {
        // Same instant as the dolt-form view value, but the export side carries an explicit
        // zero fractional part instead of bd export's own bare-seconds rendering. Review found
        // that preflight's original field comparison was byte-for-byte on the export side (only
        // the fold side was canonicalized by renderForImport), so this exact case reddened
        // before the preflight fix: a phantom Impose with a spurious updated_at FieldLoss.
        val view = mapOf("X" to mapOf("updated_at" to "\"2026-09-12 07:17:03\""))
        val export = listOf(exportRow("""{"id":"X","updated_at":"2026-09-12T07:17:03.000Z"}"""))

        val outcomes = WriteBackPlanner.plan(view, export)

        outcomes shouldBe listOf(PlanOutcome.NoOp("X"))
    }

    @Test
    fun `preflight is callable standalone against a built row and a fresh export`() {
        val row = JsonObject(mapOf("id" to JsonPrimitive("X"), "priority" to JsonPrimitive(1)))
        val freshExport = exportRow("""{"id":"X","priority":3}""")

        val losses = WriteBackPlanner.preflight(row, freshExport)

        losses shouldBe listOf(FieldLoss("priority", old = JsonPrimitive(3), new = JsonPrimitive(1)))
    }
}
