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
    fun `scanRequirements attributes an id to the chapter that declares it, not an earlier-sorting chapter that only cites it`() {
        // Regression for computenet-nta1: a requirement declared in a
        // later-sorting chapter (40-fake.md) but cited from an earlier-sorting
        // one (20-fake.md) must be attributed to the declaring chapter, not to
        // whichever chapter the path sort visits first. Mirrors the bug's own
        // observed shape: 42-WM-01 declared in 42-replication.md, cited as
        // `[42-WM-01]` inline in 22-consistency.md, which sorts first.
        val spec = specDir()
        writeSpecChapter(
            File(spec, "20-earlier").apply { mkdirs() },
            "20-fake.md",
            """
            # 20 — Fake earlier-sorting chapter

            This chapter only cites the requirement declared elsewhere: a
            `[40-FAKE-01]` frontier read over the delivered-prefix rows.
            """.trimIndent(),
        )
        writeSpecChapter(
            File(spec, "40-later").apply { mkdirs() },
            "40-fake.md",
            """
            # 40 — Fake later-sorting chapter, the true owner

            [40-FAKE-01] The fake state SHALL carry the requirement this test
            declares.
            """.trimIndent(),
        )

        val requirements = ConcordanceScanner.scanRequirements(spec)

        requirements.single { it.id == "40-FAKE-01" }.sourceFile shouldBe "40-later/40-fake.md"
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

    @Test
    fun `buildConcordance's ConcordanceRow sourceFile names the declaring chapter end to end`() {
        // Same fixture shape as the scanRequirements regression above, run
        // through the full scan-plus-build pipeline so the assertion is on
        // ConcordanceRow.sourceFile itself, per the acceptance criteria.
        val spec = specDir()
        writeSpecChapter(
            File(spec, "20-earlier").apply { mkdirs() },
            "20-fake.md",
            """
            Cites it inline: a `[40-FAKE-02]` read over the rows.
            """.trimIndent(),
        )
        writeSpecChapter(
            File(spec, "40-later").apply { mkdirs() },
            "40-fake.md",
            """
            [40-FAKE-02] The fake state SHALL carry the requirement.
            """.trimIndent(),
        )

        val report = buildConcordance(ConcordanceScanner.scanRequirements(spec), emptyList())

        report.rows.single { it.requirement == "40-FAKE-02" }.sourceFile shouldBe "40-later/40-fake.md"
    }

    // --- scanDeclarationProvenance (computenet-7ei34) ---------------------

    @Test
    fun `scanDeclarationProvenance flags an id with no declaration-classified occurrence anywhere`() {
        // Mirrors the ticket's clause 1 shape: the id is declared mid-sentence
        // (lowercase continuation, per isDeclaration), so no occurrence reads
        // as a declaration and scanRequirements' firstDeclaration map has no
        // entry for it — the fallback path this lint exists to surface.
        val spec = specDir()
        writeSpecChapter(
            File(spec, "13-linking").apply { mkdirs() },
            "13-fake.md",
            """
            A `[13-FAKE-01]` `link.unlink()` call is idempotent under retry.
            """.trimIndent(),
        )

        val provenance = ConcordanceScanner.scanDeclarationProvenance(spec)

        provenance.idsWithNoDeclaration shouldContainExactly setOf("13-FAKE-01")
        provenance.idsWithMultipleNormativeDeclarations.size shouldBe 0
    }

    @Test
    fun `scanDeclarationProvenance flags an id declared in more than one normative chapter file`() {
        val spec = specDir()
        val dataflow = File(spec, "20-dataflow-semantics").apply { mkdirs() }
        writeSpecChapter(
            dataflow,
            "22-fake.md",
            """
            [22-FAKE-01] The observed state SHALL carry the requirement this chapter declares.
            """.trimIndent(),
        )
        writeSpecChapter(
            dataflow,
            "24-fake.md",
            """
            [22-FAKE-01] The same id, wrongly re-declared here too.
            """.trimIndent(),
        )

        val provenance = ConcordanceScanner.scanDeclarationProvenance(spec)

        provenance.idsWithNoDeclaration shouldHaveSize 0
        provenance.idsWithMultipleNormativeDeclarations shouldBe
            mapOf("22-FAKE-01" to listOf("20-dataflow-semantics/22-fake.md", "20-dataflow-semantics/24-fake.md"))
    }

    @Test
    fun `scanDeclarationProvenance flags an id whose only declaration-classified occurrence is a roadmap ticket, not a normative chapter`() {
        // computenet-x3hqg (residual of computenet-7ei34, clause 1): the
        // normative chapter only CITES the id mid-sentence (lowercase
        // continuation, backticked house style) — a citation, not a
        // declaration — while a 90-roadmap ticket's own citation happens to
        // be followed by an uppercase acronym (`EARS-GAP`), which the "Known
        // blind spot" (provenance.md §1) reads as a declaration purely on
        // case. scanRequirements then attributes the id to the roadmap file
        // via firstDeclaration, and neither idsWithNoDeclaration (an id IS
        // declared, just not normatively) nor idsWithMultipleNormativeDeclarations
        // (no normative chapter declares it at all) caught this before.
        val spec = specDir()
        writeSpecChapter(
            File(spec, "10-programming-model").apply { mkdirs() },
            "13-fake.md",
            """
            A `[13-FAKE-09]` link admission check runs before the topology mutates.
            """.trimIndent(),
        )
        writeSpecChapter(
            File(spec, "90-roadmap").apply { mkdirs() },
            "91-ticket.md",
            """
            Still open: the `[13-FAKE-09]` EARS-GAP self-doubt about admission ordering.
            """.trimIndent(),
        )

        val provenance = ConcordanceScanner.scanDeclarationProvenance(spec)

        provenance.idsWithNoDeclaration shouldHaveSize 0
        provenance.idsWithMultipleNormativeDeclarations.size shouldBe 0
        provenance.idsWithOnlyNonNormativeDeclaration shouldBe
            mapOf("13-FAKE-09" to listOf("90-roadmap/91-ticket.md"))
    }

    @Test
    fun `scanDeclarationProvenance does not count a roadmap ticket's declaration towards multiplicity`() {
        // Ticket clause 3's shape: one normative chapter declares the id, and
        // a 90-roadmap ticket ALSO reads as a declaration (e.g. a
        // block-quoted EARS sentence). This resolves correctly today by path
        // order and is not the ownership hazard the multiplicity lint
        // targets — a roadmap ticket is never itself a normative owner.
        val spec = specDir()
        writeSpecChapter(
            File(spec, "20-dataflow-semantics").apply { mkdirs() },
            "22-fake.md",
            """
            [22-FAKE-02] The observed state SHALL carry the requirement this chapter declares.
            """.trimIndent(),
        )
        writeSpecChapter(
            File(spec, "90-roadmap").apply { mkdirs() },
            "99-ticket.md",
            """
            > [22-FAKE-02] The observed state SHALL carry the requirement this chapter declares.
            """.trimIndent(),
        )

        val provenance = ConcordanceScanner.scanDeclarationProvenance(spec)

        provenance.idsWithNoDeclaration shouldHaveSize 0
        provenance.idsWithMultipleNormativeDeclarations.size shouldBe 0
    }

    // --- buildConcordance: the two new ownership lints (computenet-7ei34) --

    @Test
    fun `buildConcordance reports an unestablished-ownership note for an id with no declaration anywhere`() {
        val requirements = listOf(ConcordanceScanner.Requirement("13-FAKE-01", "13-linking/13-fake.md"))
        val provenance = ConcordanceScanner.DeclarationProvenance(
            idsWithNoDeclaration = setOf("13-FAKE-01"),
            idsWithMultipleNormativeDeclarations = emptyMap(),
        )

        val report = buildConcordance(requirements, emptyList(), provenance)

        val ownershipNotes = report.noteFindings.filter { it.message.contains("Unestablished ownership") }
        ownershipNotes shouldHaveSize 1
        ownershipNotes.single().message shouldContain "13-FAKE-01"
    }

    @Test
    fun `buildConcordance reports a contested-ownership note for an id declared in more than one normative chapter`() {
        val requirements = listOf(
            ConcordanceScanner.Requirement("22-FAKE-01", "20-dataflow-semantics/22-fake.md"),
        )
        val provenance = ConcordanceScanner.DeclarationProvenance(
            idsWithNoDeclaration = emptySet(),
            idsWithMultipleNormativeDeclarations = mapOf(
                "22-FAKE-01" to listOf("20-dataflow-semantics/22-fake.md", "20-dataflow-semantics/24-fake.md"),
            ),
        )

        val report = buildConcordance(requirements, emptyList(), provenance)

        val ownershipNotes = report.noteFindings.filter { it.message.contains("Contested ownership") }
        ownershipNotes shouldHaveSize 1
        ownershipNotes.single().message shouldContain "22-FAKE-01"
        ownershipNotes.single().message shouldContain "20-dataflow-semantics/22-fake.md"
        ownershipNotes.single().message shouldContain "20-dataflow-semantics/24-fake.md"
    }

    @Test
    fun `buildConcordance reports a roadmap-owned-declaration note for an id declared only outside a normative chapter`() {
        val requirements = listOf(ConcordanceScanner.Requirement("13-FAKE-09", "90-roadmap/91-ticket.md"))
        val provenance = ConcordanceScanner.DeclarationProvenance(
            idsWithNoDeclaration = emptySet(),
            idsWithMultipleNormativeDeclarations = emptyMap(),
            idsWithOnlyNonNormativeDeclaration = mapOf("13-FAKE-09" to listOf("90-roadmap/91-ticket.md")),
        )

        val report = buildConcordance(requirements, emptyList(), provenance)

        val ownershipNotes = report.noteFindings.filter { it.message.contains("Roadmap-owned declaration") }
        ownershipNotes shouldHaveSize 1
        ownershipNotes.single().message shouldContain "13-FAKE-09"
        ownershipNotes.single().message shouldContain "90-roadmap/91-ticket.md"
        // Distinct from Unestablished ownership: this id IS declared somewhere.
        report.noteFindings.none { it.message.contains("Unestablished ownership") } shouldBe true
    }

    @Test
    fun `buildConcordance emits no ownership notes when declarationProvenance is left at its default`() {
        val requirements = listOf(ConcordanceScanner.Requirement("90-FAKE-01", "90-fake.md"))
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("90-FAKE-01"), "fake-01.yaml"),
        )

        val report = buildConcordance(requirements, scenarios)

        report.findings shouldHaveSize 0
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

    // --- T02-D: chapter denominator honesty --------------------------------------------

    @Test
    fun `scanNormativeChapterFiles lists chapter files under 00-50 but not 90-roadmap`() {
        val spec = specDir()
        writeSpecChapter(File(spec, "10-programming-model").apply { mkdirs() }, "11-cells.md", "no ids here")
        writeSpecChapter(File(spec, "10-programming-model").apply { mkdirs() }, "12-ports.md", "[12-FAKE-01] an id")
        writeSpecChapter(File(spec, "90-roadmap").apply { mkdirs() }, "91-gap-analysis.md", "not normative")

        val chapters = ConcordanceScanner.scanNormativeChapterFiles(spec)

        chapters shouldContainExactly listOf("10-programming-model/11-cells.md", "10-programming-model/12-ports.md")
    }

    @Test
    fun `computeChapterDenominator partitions chapters by whether any id was found in them`() {
        val chapterFiles = listOf(
            "10-programming-model/11-cells.md",
            "10-programming-model/12-ports.md",
            "20-dataflow-semantics/23-ownership.md",
        )
        val requirements = listOf(
            ConcordanceScanner.Requirement("12-FAKE-01", "10-programming-model/12-ports.md"),
        )

        val denominator = computeChapterDenominator(chapterFiles, requirements)

        denominator.withIds shouldContainExactly listOf("10-programming-model/12-ports.md")
        denominator.withoutIds shouldContainExactly listOf(
            "10-programming-model/11-cells.md",
            "20-dataflow-semantics/23-ownership.md",
        )
        denominator.total shouldBe 3
    }

    @Test
    fun `renderConcordanceMarkdown with a denominator names the zero-id chapters`() {
        val requirements = listOf(ConcordanceScanner.Requirement("12-FAKE-01", "10-programming-model/12-ports.md"))
        val scenarios = listOf(
            ConcordanceScanner.CorpusScenario("FAKE-01", listOf("12-FAKE-01"), "fake-01.yaml"),
        )
        val denominator = ChapterDenominator(
            withIds = listOf("10-programming-model/12-ports.md"),
            withoutIds = listOf("10-programming-model/11-cells.md"),
        )

        val markdown = renderConcordanceMarkdown(buildConcordance(requirements, scenarios), denominator)

        markdown shouldContain "Denominator honesty"
        markdown shouldContain "1 of 2"
        markdown shouldContain "10-programming-model/11-cells.md"
    }
}
