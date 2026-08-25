package civictech.oracle

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The ORA1 and ORA2 marker forms, pinned (computenet-4ru.22, computenet-gmld).
 *
 * ## The decision this test enforces
 *
 * `ORA1 §…` and `ORA2 §…` are **acceptance clauses of the beads items that built this harness**
 * — ORA1's in epic `computenet-4ru` §4, ORA2's in feature `computenet-4ru.1` §4 — and **not**
 * EARS requirement ids. Neither family has normative text under `doc/spec/`, no
 * `concord/corpus` scenario `covers:` one, and none ever will: they constrain the *tester*, not
 * the runtime that `doc/spec` specifies and the corpus checks. Verified 2026-08-25 on base
 * `3d190aaff` in both directions — `git grep 'ORA1-' doc/` and `git grep 'ORA2-' doc/` are both
 * empty, and `DISPUTES.md` is the corpus's only mention of either family — so there is no
 * asymmetry between the two families to read anything into.
 *
 * That mattered concretely: a reviewer on `computenet-9892` was asked to confirm an
 * implementation satisfied `ORA2 §MODEL-12`, went looking for the spec text behind it, found
 * none, and had to return NOT VERIFIED. A marker that cannot fail a review — only fail to be
 * found — is the circularity `concord/` exists to break.
 *
 * ## Why the *shape* is the deliverable
 *
 * In this repo a bracketed SHOUTY id is the mark of an EARS requirement in `doc/spec`:
 * `[24-TMAP-03]`, `[42-REPL-04]`, `[22-GF-01]`. ORA1's and ORA2's markers wore that same shape
 * while meaning something else, which is what sent the reviewer to `doc/spec`. They now read
 * `ORA1 §HONEST-01`, `ORA2 §MODEL-12`, in the repo's own `<document> §<section>` idiom
 * (`96 §E1.5`, `epic computenet-4ru §2.3`), which points at the bead section that actually
 * defines them.
 *
 * A naming decision has no compiler. Nothing stops the next author from typing the bracketed
 * form back in, and nothing would go red — the same silent-deletion path
 * [HonestyLedgerTest] exists to close for prose. So this test reads the module's own sources,
 * the `:kernel` tests and build files ORA1's citations reach, and the ledger, and fails on the
 * old shape.
 *
 * ## Scope, and what it deliberately does not check
 *
 * ORA2 was renamed first (computenet-4ru.22); ORA1 followed in a separate item
 * (computenet-gmld) because its 465 citations reached outside computenet-4ru.22's file claim
 * (`:kernel` tests, `kernel/build.gradle.kts`, `oracle/build.gradle.kts`, and
 * `.claude/skills/work/SKILL.md`). This test does **not** scan `.claude/`: the `SKILL.md`
 * occurrence is not a citation of either family — it is a single illustrative example of "a
 * bracketed requirement id" inside a skill-authoring bullet — and editing it routes through the
 * remediate-friction lane's own gate (split out as computenet-yiof), which a `:oracle`/`:kernel`
 * test must not reach into.
 *
 * This test's own file is excluded from the scan, because it has to be able to name the shape
 * it forbids in order to explain it.
 */
class MarkerFormTest {

    private companion object {
        /**
         * The retired shape: a bracketed `ORA1` or `ORA2` id, as in the pre-rename sources.
         * Written by concatenation so the pattern is not itself a literal occurrence.
         */
        val FORBIDDEN = Regex("\\" + "[ORA[12]-[A-Z]+-[0-9*]")

        /** The section form that replaced it, for ORA1. */
        val REQUIRED_ORA1 = Regex("ORA1 §[A-Z]+-[0-9*]")

        /** The section form that replaced it, for ORA2. */
        val REQUIRED_ORA2 = Regex("ORA2 §[A-Z]+-[0-9*]")

        const val SELF = "MarkerFormTest.kt"
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /**
     * Every file either decision governs: the `:oracle` sources, the `:kernel` tests and build
     * files ORA1's citations reach, and the corpus honesty ledger. Deliberately excludes
     * `.claude/` — see the class KDoc's "Scope" section.
     */
    private fun governedFiles(): List<File> {
        val root = repoRoot()
        val oracleSources = File(root, "oracle/src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "md") }
            .filter { it.name != SELF }
        val kernelTestSources = File(root, "kernel/src/test").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val buildFiles = listOf(
            File(root, "kernel/build.gradle.kts"),
            File(root, "oracle/build.gradle.kts"),
        )
        return (oracleSources + kernelTestSources + buildFiles + File(root, "concord/corpus/DISPUTES.md")).toList()
    }

    @Test
    fun `no oracle source, kernel test, build file or ledger entry writes an ORA1 or ORA2 marker in the retired bracketed form`() {
        val root = repoRoot()
        val offenders = governedFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> FORBIDDEN.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root)}:${index + 1}" }
        }

        withClue(
            "These lines write an ORA1 or ORA2 marker in the bracketed shape this repo reserves " +
                "for EARS requirement ids in doc/spec. Both families are acceptance clauses of the " +
                "beads items that built the :oracle harness, which have no doc/spec home and are " +
                "not meant to: write them as \"ORA1 §HONEST-01\" / \"ORA2 §MODEL-12\". See " +
                "OracleSweep.kt's file KDoc and concord/corpus/DISPUTES.md. Offenders: $offenders",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the section form is actually in use for both families, so the scan above is not vacuously green`() {
        val files = governedFiles().map { it.readText() }
        val ora1Hits = files.count { REQUIRED_ORA1.containsMatchIn(it) }
        val ora2Hits = files.count { REQUIRED_ORA2.containsMatchIn(it) }

        withClue(
            "Not one governed file writes a marker as \"ORA1 §SLUG-nn\". Either the family was " +
                "renamed again without updating this test, or the scan is passing because there is " +
                "nothing left to scan — and a green check on an empty set is the failure mode this " +
                "assertion exists to catch.",
        ) {
            (ora1Hits >= 20) shouldBe true
        }
        withClue(
            "Not one governed file writes a marker as \"ORA2 §SLUG-nn\". Either the family was " +
                "renamed again without updating this test, or the scan is passing because there is " +
                "nothing left to scan — and a green check on an empty set is the failure mode this " +
                "assertion exists to catch.",
        ) {
            (ora2Hits >= 20) shouldBe true
        }
    }


    /**
     * A file's text with each line's leading KDoc `*` stripped and every whitespace run collapsed
     * to one space. Both halves matter: without the `*` strip a phrase that happens to wrap in a
     * KDoc block can never be found, and without the flattening a re-wrap reddens the pins below.
     * A ledger test that fails on a line break trains its readers to edit the test.
     */
    private fun flatten(file: File): String =
        file.readLines()
            .joinToString(" ") { it.trimStart().removePrefix("*").trim() }
            .replace(Regex("\\s+"), " ")

    @Test
    fun `the decision is recorded where both a code reader and a requirement-side reader will land`() {
        val root = repoRoot()

        // The load-bearing fingerprints, not the whole paragraph: a rewrite that keeps the claim
        // stays green, a deletion does not. Whitespace-flattened, so re-wrapping is never a failure.
        val sweep = flatten(File(root, "oracle/src/main/kotlin/civictech/oracle/run/OracleSweep.kt"))
        val disputes = flatten(File(root, "concord/corpus/DISPUTES.md"))

        listOf(
            "acceptance clauses of the beads items that built this harness" to sweep,
            "not EARS requirement ids" to sweep,
            "no asymmetry" to sweep,
            "acceptance clauses of the beads items that built the `:oracle` harness" to disputes,
            "not EARS requirement ids" to disputes,
            "no asymmetry" to disputes,
        ).forEach { (phrase, text) ->
            withClue("The decision record no longer states: \"$phrase\"") {
                text.contains(phrase) shouldBe true
            }
        }
    }
}
