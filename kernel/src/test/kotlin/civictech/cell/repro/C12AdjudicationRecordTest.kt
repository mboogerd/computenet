package civictech.cell.repro

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **BS-13 — C-12 is closed, and the suite says so with evidence.**
 * Realizes `[CHA2-30]`, `[CHA2-31]`, `[CHA2-32]`, `[CHA2-33]`.
 *
 * This is a **documentation-of-record** test, not a reproduction. It asserts
 * over checked-in artifacts rather than kernel behaviour, deliberately and for
 * one reason: **there is no C-12 divergence left to exercise.** The milestone
 * plan's CHA2 row names C-12 (RESTART tag/wave aliasing) as
 * decided-but-divergent; adjudication D-C12 concluded that the divergence
 * claim was transcribed from stale M3.5 *prose*, not from the code. Writing a
 * failing test to match the plan row would manufacture a defect, which
 * `AGENTS.md` forbids in both directions ("A requirement that cannot be checked
 * honestly is filed in `concord/corpus/DISPUTES.md`, never weakened into a
 * passing scenario" — and never strengthened into a failing one).
 *
 * So what this test protects is the *record*: if someone later reverts a
 * D-C12 artifact, deletes the adjudication ticket, drops the conformance
 * scenario, or quietly re-opens C-12 by writing a reproduction for it, the
 * build says so. The adjudication itself, with file:line evidence at base
 * commit `46ed020`, lives in `doc/evidence-lane-findings.md` (recorded by
 * `computenet-umx.1.1`).
 *
 * **Scope limits, stated here because they bound what a green run proves:**
 * this test checks that the artifacts exist and carry their resolution
 * markers. It does not re-derive the adjudication, does not verify the RESTART
 * mechanism (that is `concord/corpus/21-propagation/21-REBASE-01.yaml`, cited
 * and not duplicated, `[CHA2-31]`), and cannot detect a C-12 reproduction
 * written under a name that mentions neither `C12` nor `C-12`.
 *
 * The suite lives in `:kernel`'s test source set (`[CHA2-05]`) because
 * `ManagedHost.replayAsBaseline` is `internal` and unreachable from
 * `:testkit`; the visibility is not to be widened to move it.
 */
class C12AdjudicationRecordTest {

    @Test
    fun `the gap-analysis C-12 row records the divergence claim as stale and the row as resolved`() {
        val row = read("doc/spec/90-roadmap/91-gap-analysis.md")
            .lineSequence()
            .firstOrNull { it.startsWith("| C-12 |") }

        requireNotNull(row) { "No C-12 row found in doc/spec/90-roadmap/91-gap-analysis.md" }

        row shouldContain "Resolved (W2.1 core, adjudicated + conformance-covered D-C12)"
        row shouldContain "adjudicated D-C12"
        // [CHA2-33]: the row itself keeps D-C12's residuals open.
        row shouldContain "Residuals unchanged, and NOT closed by this row"
        row shouldContain "G-43"
        row shouldContain "G-42"
    }

    @Test
    fun `the D-C12 adjudication ticket is checked in`() {
        val ticket = repoFile("doc/spec/90-roadmap/99-defects-engines-plan/tickets/D-C12.md")
        ticket.isFile shouldBe true
    }

    @Test
    fun `the RESTART re-baseline conformance scenario is checked in and covers its requirement id`() {
        // [CHA2-31]: CHA2 cites this scenario as the existing coverage of the
        // RESTART re-baseline mechanism; it authors no duplicate.
        read("concord/corpus/21-propagation/21-REBASE-01.yaml") shouldContain "covers: [21-REBASE-01]"
        read("doc/spec/CONCORDANCE.md") shouldContain "| 21-REBASE-01 | 21-REBASE-01 | covered |"
    }

    @Test
    fun `the honesty ledger marks the C-12 dispute resolved by D-C12`() {
        val marker = read("concord/corpus/DISPUTES.md")
            .lineSequence()
            .firstOrNull { it.contains("21-REBASE-01") && it.contains("RESOLVED (D-C12") }

        requireNotNull(marker) {
            "concord/corpus/DISPUTES.md carries no `21-REBASE-01 … RESOLVED (D-C12…)` marker"
        }
        marker shouldContain "15-RESTART-01"
    }

    @Test
    fun `the evidence-lane findings file names the D-C12 residuals as out of scope`() {
        // [CHA2-33]: G-43 (freshest-checkpoint tiers, pull-merge direction) and
        // G-42 (epoch/generation reclamation) are NOT C-12, are NOT closed, and
        // are NOT covered by CHA2. The findings file must say so in its own
        // C-12 entry — not merely somewhere in the file.
        val c12Entry = sectionOf(read(FINDINGS), "### C-12")

        c12Entry shouldContain "G-43"
        c12Entry shouldContain "G-42"
        c12Entry shouldContain "not C-12"
        // The exclusion has to be stated, not merely alluded to. Matching the
        // bare phrase "out of scope" was verified vacuous by mutation: the
        // entry's own description of this test also contains it, so deleting
        // the actual exclusion sentence left the assertion green.
        c12Entry shouldContain "out of scope for CHA2"
        // [CHA2-30] / [CHA2-32]: the verdict and its counter-evidence.
        c12Entry shouldContain "D-C12"
        c12Entry shouldContain "21-REBASE-01"
    }

    @Test
    fun `the findings file adjudicates all three ledger rules against a pinned base commit`() {
        // [CHA2-01]: one entry per ledger id, against the kernel in main.
        val findings = read(FINDINGS)
        findings shouldContain "### C-9"
        findings shouldContain "### C-11"
        findings shouldContain "### C-12"
        findings shouldContain BASE_COMMIT
    }

    @Test
    fun `the repro package contains no C-12 reproduction`() {
        // [CHA2-30]: zero C-12 reproductions, expected-failure or otherwise.
        // Two signals, both cheap: a source file named for C-12 that is not
        // this record, and a test function named for C-12 anywhere in the
        // package. Prose mentions of C-12 are deliberately not flagged —
        // sibling C-9 work legitimately cites C-12 (see
        // `civictech/cell/link/CatchUp.kt`'s baseline-exemption KDoc).
        val sources = repoFile("kernel/src/test/kotlin/civictech/cell/repro")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val offendingFiles = sources
            .filter { it.name != "C12AdjudicationRecordTest.kt" }
            .filter { it.name.contains("C12") || it.name.contains("C-12") }
            .map { it.name }
        offendingFiles.shouldBeEmpty()

        val offendingTests = sources
            .filter { it.name != "C12AdjudicationRecordTest.kt" }
            .flatMap { file ->
                TEST_FN.findAll(file.readText())
                    .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    .filter { it.contains("c12", ignoreCase = true) || it.contains("c-12", ignoreCase = true) }
                    .map { "${file.name}: $it" }
            }
        offendingTests.shouldBeEmpty()
    }

    private companion object {
        const val FINDINGS = "doc/evidence-lane-findings.md"

        /** The commit this adjudication was verified against; see the findings file's header. */
        const val BASE_COMMIT = "46ed020"

        /** Matches a function declaration name, backtick-quoted or plain. */
        val TEST_FN = Regex("""\bfun\s+(?:`([^`]+)`|([A-Za-z_]\w*))\s*\(""")

        /**
         * Repo root, resolved by walking up from the working directory to the
         * settings script. Gradle runs tests with the module directory as the
         * working directory, so a relative path would otherwise resolve
         * against `kernel/`.
         */
        val REPO_ROOT: File by lazy {
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: error(
                    "Could not resolve the repository root: no settings.gradle.kts found walking up " +
                        "from ${System.getProperty("user.dir")}",
                )
        }

        fun repoFile(relative: String): File = File(REPO_ROOT, relative)

        fun read(relative: String): String {
            val file = repoFile(relative)
            require(file.isFile) { "Expected checked-in artifact is missing: $relative (under $REPO_ROOT)" }
            return file.readText()
        }

        /**
         * The slice of [markdown] from the line starting with [heading] up to
         * the next heading of the same level, so an assertion about the C-12
         * entry cannot be satisfied by text belonging to the C-9 or C-11 entry.
         */
        fun sectionOf(markdown: String, heading: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith(heading) }
            require(start >= 0) { "No section starting with `$heading` found" }
            val level = heading.takeWhile { it == '#' }
            val end = lines
                .drop(start + 1)
                .indexOfFirst { it.startsWith("$level ") || it.startsWith("${level.dropLast(1)} ") }
            return if (end < 0) {
                lines.drop(start).joinToString("\n")
            } else {
                lines.subList(start, start + 1 + end).joinToString("\n")
            }
        }
    }
}
