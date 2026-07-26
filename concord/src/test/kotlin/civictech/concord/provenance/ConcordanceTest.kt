package civictech.concord.provenance

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * W1-D unit tests: pure-logic exercise of the concordance generator and its
 * three lints (provenance.md §3), against tiny fake fixtures — never the
 * real, evolving corpus (per the ticket, so these never flake as the real
 * corpus grows).
 */
class ConcordanceTest {

    @TempDir
    lateinit var tmp: File

    private fun specDir(): File = File(tmp, "spec").apply { mkdirs() }
    private fun corpusDir(): File = File(tmp, "corpus").apply { mkdirs() }

    private fun writeSpecChapter(dir: File, name: String, content: String) =
        File(dir, name).writeText(content)

    private fun writeScenario(dir: File, name: String, content: String) =
        File(dir, name).apply { parentFile.mkdirs() }.writeText(content)

    // --- scanRequirements -----------------------------------------------

    @Test
    fun `scanRequirements finds inline NN-SLUG-nn ids and ignores non-matching brackets`() {
        val spec = specDir()
        writeSpecChapter(
            spec,
            "90-fake.md",
            """
            # 90 — Fake chapter
            Some prose referencing [93] (not an id: no slug/ordinal) and a real one:
            [90-FAKE-01] The system SHALL do the fake thing.
            Also [90-FAKE-02] WHEN triggered, the system SHALL do another fake thing.
            """.trimIndent(),
        )

        val requirements = ConcordanceScanner.scanRequirements(spec)

        requirements.map { it.id }.sorted() shouldContainExactly listOf("90-FAKE-01", "90-FAKE-02")
    }

    @Test
    fun `scanRequirements recognizes multi-segment slug ids`() {
        // The minted operator ids carry a compound slug (24-OP-UNION-01); the
        // scanner must recognize them, not just single-segment ids (21-PROP-01).
        val spec = specDir()
        writeSpecChapter(
            spec,
            "24-fake.md",
            """
            # 24 — Data cells
            `[24-OP-UNION-01]` UnionSetCell SHALL track the union.
            `[24-OP-GROUPBY-01]` GroupByCell SHALL partition by key.
            And a single-segment one still works: [21-PROP-01].
            """.trimIndent(),
        )

        ConcordanceScanner.scanRequirements(spec).map { it.id }.sorted() shouldContainExactly
            listOf("21-PROP-01", "24-OP-GROUPBY-01", "24-OP-UNION-01")
    }

    @Test
    fun `scanRequirements deduplicates an id referenced more than once`() {
        val spec = specDir()
        writeSpecChapter(
            spec,
            "90-fake.md",
            """
            [90-FAKE-01] The system SHALL do the fake thing.
            Later, prose refers back to [90-FAKE-01] again.
            """.trimIndent(),
        )

        ConcordanceScanner.scanRequirements(spec) shouldHaveSize 1
    }

    // --- scanScenarios ----------------------------------------------------

    @Test
    fun `scanScenarios reads flow-style and block-style covers`() {
        val corpus = corpusDir()
        writeScenario(
            corpus,
            "flow.yaml",
            """
            id: FAKE-FLOW-01
            title: flow style
            covers: [90-FAKE-01, 90-FAKE-02]
            profile: core
            kind: example
            """.trimIndent(),
        )
        writeScenario(
            corpus,
            "block.yaml",
            """
            id: FAKE-BLOCK-01
            title: block style
            covers:
              - 90-FAKE-01
            profile: core
            kind: example
            """.trimIndent(),
        )

        val scenarios = ConcordanceScanner.scanScenarios(corpus).associateBy { it.id }

        scenarios.getValue("FAKE-FLOW-01").covers shouldContainExactly listOf("90-FAKE-01", "90-FAKE-02")
        scenarios.getValue("FAKE-BLOCK-01").covers shouldContainExactly listOf("90-FAKE-01")
    }

    @Test
    fun `scanScenarios reads an empty flow-style covers list`() {
        val corpus = corpusDir()
        writeScenario(
            corpus,
            "empty.yaml",
            """
            id: FAKE-EMPTY-01
            covers: []
            profile: core
            kind: example
            """.trimIndent(),
        )

        ConcordanceScanner.scanScenarios(corpus).single().covers shouldContainExactly emptyList()
    }

    // --- buildConcordance: the four lint cases ----------------------------

    @Test
    fun `clean case, every requirement covered, produces no findings`() {
        val requirements = listOf(
            ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"),
            ConcordanceScanner.Requirement("90-FAKE-02", "90-fake.md"),
        )
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("90-FAKE-01", "90-FAKE-02"), "fake-01.yaml"),
        )

        val report = buildConcordance(requirements, scenarios)

        report.findings shouldHaveSize 0
        report.rows shouldHaveSize 2
        report.rows.all { !it.isGap } shouldBe true
    }

    @Test
    fun `dangling covers id is a fatal finding`() {
        val requirements = listOf(ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"))
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("90-FAKE-01", "90-GHOST-99"), "fake-01.yaml"),
        )

        val report = buildConcordance(requirements, scenarios)

        report.fatalFindings shouldHaveSize 1
        report.fatalFindings.single().message shouldContain "90-GHOST-99"
        // The dangling id must not silently show up as coverage of anything real.
        report.rows.single { it.requirement == "90-FAKE-01" }.isGap shouldBe false
    }

    @Test
    fun `orphan scenario with empty covers is a fatal finding`() {
        val requirements = listOf(ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"))
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-ORPHAN-01", emptyList(), "orphan.yaml"),
        )

        val report = buildConcordance(requirements, scenarios)

        report.fatalFindings shouldHaveSize 1
        report.fatalFindings.single().message shouldContain "FAKE-ORPHAN-01"
        report.noteFindings shouldHaveSize 1 // 90-FAKE-01 is now also an (unrelated) coverage gap
    }

    @Test
    fun `requirement with no covering scenario is a non-fatal coverage gap`() {
        val requirements = listOf(
            ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"),
            ConcordanceScanner.Requirement("90-FAKE-02", "90-fake.md"),
        )
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("90-FAKE-01"), "fake-01.yaml"),
        )

        val report = buildConcordance(requirements, scenarios)

        report.fatalFindings shouldHaveSize 0
        report.noteFindings shouldHaveSize 1
        report.noteFindings.single().message shouldContain "90-FAKE-02"
        report.rows.single { it.requirement == "90-FAKE-02" }.isGap shouldBe true
        report.rows.single { it.requirement == "90-FAKE-01" }.isGap shouldBe false
    }

    @Test
    fun `renderConcordanceMarkdown emits the Requirement, Scenarios, Status table`() {
        val requirements = listOf(ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"))
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("90-FAKE-01"), "fake-01.yaml"),
        )

        val markdown = renderConcordanceMarkdown(buildConcordance(requirements, scenarios))

        markdown shouldContain "| Requirement | Scenarios | Status |"
        markdown shouldContain "| 90-FAKE-01 | FAKE-01 | covered |"
    }
}
