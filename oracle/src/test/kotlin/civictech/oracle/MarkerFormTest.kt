package civictech.oracle

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The ORA2 marker form, pinned (computenet-4ru.22).
 *
 * ## The decision this test enforces
 *
 * `ORA1-…` and `ORA2 §…` are **acceptance clauses of the beads items that built this harness**
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
 * `[24-TMAP-03]`, `[42-REPL-04]`, `[22-GF-01]`. ORA2's markers wore that same shape while
 * meaning something else, which is what sent the reviewer to `doc/spec`. They now read
 * `ORA2 §MODEL-12`, in the repo's own `<document> §<section>` idiom (`96 §E1.5`,
 * `epic computenet-4ru §2.3`), which points at the bead section that actually defines them.
 *
 * A naming decision has no compiler. Nothing stops the next author from typing the bracketed
 * form back in, and nothing would go red — the same silent-deletion path
 * [HonestyLedgerTest] exists to close for prose. So this test reads the module's own sources
 * and the ledger, and fails on the old shape.
 *
 * ## Scope, and what it deliberately does not check
 *
 * ORA1's markers still carry the bracketed shape, and this test does **not** bar them. That is
 * not a judgement that ORA1 is a spec family — it is not — but a file-claim boundary:
 * renaming its 448 citations reaches `:kernel` tests, two `build.gradle.kts` files and
 * `.claude/skills/work/SKILL.md`, none of which computenet-4ru.22 could touch. `computenet-gmld`
 * carries it, together with the one stale ORA2 citation surviving at
 * `kernel/src/test/kotlin/civictech/cell/data/SetConvergenceTest.kt`. Widen [FORBIDDEN] to
 * ORA1 when that lands.
 *
 * This test's own file is excluded from the scan, because it has to be able to name the shape
 * it forbids in order to explain it.
 */
class MarkerFormTest {

    private companion object {
        /**
         * The retired shape: a bracketed `ORA2` id, as in the pre-computenet-4ru.22 sources.
         * Written by concatenation so the pattern is not itself a literal occurrence.
         */
        val FORBIDDEN = Regex("\\" + "[ORA2-[A-Z]+-[0-9*]")

        /** The form that replaced it. */
        val REQUIRED = Regex("ORA2 §[A-Z]+-[0-9*]")

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

    /** Every file the decision governs: the `:oracle` sources, plus the corpus honesty ledger. */
    private fun governedFiles(): List<File> {
        val root = repoRoot()
        val sources = File(root, "oracle/src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "md") }
            .filter { it.name != SELF }
        return (sources + File(root, "concord/corpus/DISPUTES.md")).toList()
    }

    @Test
    fun `no oracle source or ledger entry writes an ORA2 marker in the retired bracketed form`() {
        val root = repoRoot()
        val offenders = governedFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> FORBIDDEN.containsMatchIn(line) }
                .map { (index, _) -> "${file.relativeTo(root)}:${index + 1}" }
        }

        withClue(
            "These lines write an ORA2 marker in the bracketed shape this repo reserves for EARS " +
                "requirement ids in doc/spec. ORA2's markers are acceptance clauses of feature " +
                "computenet-4ru.1, which has no doc/spec home and is not meant to: write them as " +
                "\"ORA2 §MODEL-12\". See OracleSweep.kt's file KDoc and " +
                "concord/corpus/DISPUTES.md. Offenders: $offenders",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the section form is actually in use, so the scan above is not vacuously green`() {
        val hits = governedFiles().count { REQUIRED.containsMatchIn(it.readText()) }

        withClue(
            "Not one governed file writes a marker as \"ORA2 §SLUG-nn\". Either the family was " +
                "renamed again without updating this test, or the scan is passing because there is " +
                "nothing left to scan — and a green check on an empty set is the failure mode this " +
                "assertion exists to catch.",
        ) {
            (hits >= 20) shouldBe true
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
