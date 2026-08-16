package civictech.demo.beadsmirror.baseline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.3.1: `bd export` JSONL is read into [ExportRow]s, or fails
 * loudly naming the line that could not be read.
 *
 * Everything here drives the [BdExportReader.parse] seam over hand-built
 * lines, so the whole rule set is a real CI gate with no `bd` on PATH. The
 * subprocess half (cwd, `--sandbox`) is exercised by
 * [BaselineBuilderTest]'s live, assumption-guarded test.
 */
class BdExportReaderTest {

    private val alpha =
        """{"_type":"issue","id":"ws-a","title":"Alpha","status":"open","priority":1}"""
    private val beta =
        """{"_type":"issue","id":"ws-b","title":"Beta","status":"closed","priority":2}"""

    @Test
    fun `parses one row per non-blank line, keeping the raw JSON`() {
        val rows = BdExportReader.parse(listOf(alpha, beta))

        rows.map { it.id } shouldBe listOf("ws-a", "ws-b")
        rows[0].json["title"]!!.jsonPrimitive.content shouldBe "Alpha"
        rows[1].json["priority"] shouldBe JsonPrimitive(2)
    }

    @Test
    fun `skips blank lines, including the stream's trailing newline`() {
        BdExportReader.parse(listOf(alpha, "", "   ", beta, "")).map { it.id } shouldBe
            listOf("ws-a", "ws-b")
    }

    @Test
    fun `reads the metadata object off a row that carries one`() {
        val row = BdExportReader.parse(
            listOf("""{"id":"ws-a","metadata":{"cn_dot":"src:7"}}""")
        ).single()

        row.metadata!!["cn_dot"]!!.jsonPrimitive.content shouldBe "src:7"
    }

    @Test
    fun `a row without metadata reports none rather than an empty object`() {
        BdExportReader.parse(listOf(alpha)).single().metadata shouldBe null
    }

    @Test
    fun `a non-JSON line fails loudly, naming the line`() {
        val failure = shouldThrow<BdExportException> {
            BdExportReader.parse(listOf(alpha, "not json at all"))
        }

        failure.line shouldBe "not json at all"
        failure.message!! shouldContain "not json at all"
    }

    @Test
    fun `a JSON line that is not an object fails loudly`() {
        val failure = shouldThrow<BdExportException> { BdExportReader.parse(listOf("""["ws-a"]""")) }

        failure.message!! shouldContain "not a JSON object"
    }

    @Test
    fun `a line missing id fails loudly rather than being skipped`() {
        val orphan = """{"_type":"issue","title":"nameless"}"""

        val failure = shouldThrow<BdExportException> { BdExportReader.parse(listOf(alpha, orphan)) }

        failure.line shouldBe orphan
        failure.message!! shouldContain "no \"id\""
    }

    @Test
    fun `a non-string or blank id fails loudly`() {
        shouldThrow<BdExportException> { BdExportReader.parse(listOf("""{"id":17}""")) }
            .message!! shouldContain "not a string"
        shouldThrow<BdExportException> { BdExportReader.parse(listOf("""{"id":"  "}""")) }
            .message!! shouldContain "blank"
    }

    @Test
    fun `the nested dependencies array survives into the raw row`() {
        val line = """
            {"id":"ws-a","dependencies":[{"issue_id":"ws-a","depends_on_id":"ws-b","type":"blocks"}]}
        """.trimIndent()

        val row = BdExportReader.parse(listOf(line)).single()

        val dependency = row.json[ExportRow.DEPENDENCIES_FIELD]!!.let { it as kotlinx.serialization.json.JsonArray }
            .single().jsonObject
        dependency["depends_on_id"]!!.jsonPrimitive.content shouldBe "ws-b"
    }
}
