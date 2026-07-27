package civictech.concord.lint

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * T02-C unit tests: pure-logic exercise of the three doc lints against tiny
 * fake fixtures — never the real, evolving `doc/spec` tree (same rationale
 * as `ConcordanceTest`: these must never flake as the real spec grows).
 */
class DocLintsTest {

    @TempDir
    lateinit var tmp: File

    private fun specDir(): File = File(tmp, "spec").apply { mkdirs() }
    private fun kernelCellDir(): File = File(tmp, "cell").apply { mkdirs() }

    private fun writeSpec(dir: File, name: String, content: String) =
        File(dir, name).apply { parentFile.mkdirs() }.writeText(content)

    // --- 1. Package-pointer resolution --------------------------------------------------

    @Test
    fun `a package pointer to an existing directory is not flagged`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        File(cellRoot, "control").mkdirs()
        writeSpec(spec, "34-fake.md", "Implementation: `cell.control.AttentionSupport` over sub-channels.")

        DocLints.checkPackagePointers(spec, cellRoot) shouldHaveSize 0
    }

    @Test
    fun `a package pointer to a nonexistent directory is a fatal finding`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        File(cellRoot, "control").mkdirs() // attention/ deliberately absent
        writeSpec(spec, "34-fake.md", "Implementation: `cell.attention.AttentionSupport` over sub-channels.")

        val findings = DocLints.checkPackagePointers(spec, cellRoot)

        findings shouldHaveSize 1
        findings.single().severity shouldBe Severity.FATAL
        findings.single().message shouldContain "cell.attention.AttentionSupport"
        findings.single().message shouldContain "34-fake.md"
    }

    @Test
    fun `the same unresolved package is only reported once per file`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        writeSpec(
            spec,
            "34-fake.md",
            "First: `cell.attention.AttentionSupport`. Second: `cell.attention.AttentionBand`.",
        )

        DocLints.checkPackagePointers(spec, cellRoot) shouldHaveSize 1
    }

    @Test
    fun `a multi-segment package pointer resolves against the nested directory`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        File(cellRoot, "data/op").mkdirs()
        writeSpec(spec, "24-fake.md", "`cell.data.op.UnionSetCell` tracks the union.")

        DocLints.checkPackagePointers(spec, cellRoot) shouldHaveSize 0
    }

    @Test
    fun `a bare two-segment reference with no package component is not flagged`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        writeSpec(spec, "10-fake.md", "`cell.Handle` is a root-level type, not a package pointer.")

        DocLints.checkPackagePointers(spec, cellRoot) shouldHaveSize 0
    }

    @Test
    fun `a method-access suffix after a resolvable type is ignored`() {
        val spec = specDir()
        val cellRoot = kernelCellDir()
        File(cellRoot, "evolve").mkdirs()
        writeSpec(spec, "53-fake.md", "`cell.evolve.Shadow.spawn` suppresses effects.")

        DocLints.checkPackagePointers(spec, cellRoot) shouldHaveSize 0
    }

    // --- 2. Requirement-id density ----------------------------------------------------

    @Test
    fun `chapterIdDensity counts distinct ids per chapter file under the normative dirs`() {
        val spec = specDir()
        writeSpec(spec, "20-dataflow-semantics/21-propagation.md", "[21-PROP-01] ... [21-PROP-01] again ... [21-PROP-02]")
        writeSpec(spec, "20-dataflow-semantics/22-consistency.md", "No ids here.")
        writeSpec(spec, "90-roadmap/91-gap-analysis.md", "[should-not-count] not scanned, wrong dir prefix")

        val densities = DocLints.chapterIdDensity(spec).associateBy { it.relativePath }

        densities.getValue("20-dataflow-semantics/21-propagation.md").idCount shouldBe 2
        densities.getValue("20-dataflow-semantics/22-consistency.md").idCount shouldBe 0
        densities.keys shouldHaveSize 2
    }

    @Test
    fun `densityFindings emits one non-fatal note per zero-id chapter`() {
        val densities = listOf(
            ChapterDensity("20-dataflow-semantics/21-propagation.md", 2),
            ChapterDensity("20-dataflow-semantics/22-consistency.md", 0),
        )

        val findings = DocLints.densityFindings(densities)

        findings shouldHaveSize 1
        findings.single().severity shouldBe Severity.NOTE
        findings.single().message shouldContain "22-consistency.md"
    }

    // --- 3. Status-header vocabulary ---------------------------------------------------

    @Test
    fun `a valid Status header in the allowed vocabulary is not flagged`() {
        val spec = specDir()
        writeSpec(
            spec,
            "34-fake.md",
            """
            # 34 — Fake chapter

            > **Status**: Implemented (M6): decisions 1-4 are code

            ## Body
            """.trimIndent(),
        )

        DocLints.checkStatusHeaders(spec) shouldHaveSize 0
    }

    @Test
    fun `a missing Status header is a fatal finding`() {
        val spec = specDir()
        writeSpec(spec, "34-fake.md", "# 34 — Fake chapter\n\nNo status line here.\n\n## Body\n")

        val findings = DocLints.checkStatusHeaders(spec)

        findings shouldHaveSize 1
        findings.single().severity shouldBe Severity.FATAL
        findings.single().message shouldContain "Missing Status header"
    }

    @Test
    fun `a Status header outside the allowed vocabulary is a fatal finding`() {
        val spec = specDir()
        writeSpec(spec, "96-fake.md", "# 96 — Fake plan\n\n**Status**: Proposed — nothing committed.\n\n## Body\n")

        val findings = DocLints.checkStatusHeaders(spec)

        findings shouldHaveSize 1
        findings.single().message shouldContain "Status vocabulary violation"
        findings.single().message shouldContain "Proposed"
    }

    @Test
    fun `a second Status line later in the body is not counted against the header`() {
        val spec = specDir()
        writeSpec(
            spec,
            "22-fake.md",
            """
            # 22 — Fake chapter

            > **Status**: Specified; core implemented

            ## Some section

            > **Status**: Implemented (PN-2). A sub-section callout, not the header.
            """.trimIndent(),
        )

        DocLints.checkStatusHeaders(spec) shouldHaveSize 0
    }

    @Test
    fun `README and CONCORDANCE are excluded from the Status-header requirement`() {
        val spec = specDir()
        writeSpec(spec, "README.md", "# Spec\n\nNo status line, and that's fine.\n")
        writeSpec(spec, "CONCORDANCE.md", "# Concordance\n\nGenerated, no status line.\n")

        DocLints.checkStatusHeaders(spec) shouldHaveSize 0
    }
}
