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
 * A naming decision has no compiler. Nothing stops the next author from typing the retired shape
 * back in, and nothing would go red — the same silent-deletion path [HonestyLedgerTest] exists to
 * close for prose. So this test reads the module's own sources, the `:kernel` tests and build
 * files ORA1's citations reach, and the ledger, and fails on the old shape.
 *
 * ## Both retired shapes, not just the bracketed one (computenet-os2f)
 *
 * The retired shape has two surface forms: bracketed (`[ORA1-SHRINK-01]`, as it read in
 * `doc/spec`-style EARS ids) and bare (`ORA1-SHRINK-01`). A bracket-only ban is structurally
 * blind to the bare form's most common home — a Kotlin backticked declaration name — because `[`
 * is illegal inside one; both computenet-4ru.22's and computenet-gmld's renames actually missed
 * un-bracketed citations, not bracketed ones (12 backticked test-function names, a header
 * comment, an inline comment; repaired by hand in 4344a78f6 after the fact, unpinned before this
 * test). `FORBIDDEN` now matches the bracket as optional, so it catches both.
 *
 * That widening would also redden legitimate historical prose that deliberately quotes the
 * retired bare shape to explain the rename — `DISPUTES.md`'s two "renamed from a
 * square-bracketed ..." sentences and `OracleSweep.kt`'s "deliberately **not**
 * square-bracketed ..." sentence, four marker occurrences on three lines total. `HISTORICAL_MENTION`
 * exempts a line that also contains the word "square-bracketed" — every one of those sentences
 * uses it to say what shape it is naming, which is also the load-bearing reason none of them read
 * naturally without it. See `HISTORICAL_MENTION`'s KDoc for the discriminator's known blind spot.
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
         * The retired shape, bracketed OR bare: `[ORA1-SHRINK-01]` as in the pre-rename
         * sources, and `ORA1-SHRINK-01` -- the shape a bracket-only ban is structurally blind
         * to, because `[` is illegal inside a Kotlin backticked declaration name and so never
         * appeared there even before the rename (computenet-os2f). The leading `[` is now
         * optional (`\[?`) rather than required; concatenated so the pattern is not itself a
         * literal occurrence.
         */
        val FORBIDDEN = Regex("\\" + "[?ORA[12]-[A-Z]+-[0-9*]")

        /**
         * The phrase every known legitimate mention of the retired *bare* shape uses to say so:
         * `concord/corpus/DISPUTES.md`'s two "renamed from a square-bracketed ..." / "(a
         * square-bracketed `ORA2-CONV-01..04`)" sentences, and `OracleSweep.kt`'s "deliberately
         * **not** square-bracketed ..." sentence. A bare marker near a line that also carries this
         * phrase is a decision record explaining the retired shape, not a citation left in it by
         * an incomplete rename -- exactly the four mentions computenet-os2f's bead names.
         *
         * Checked over a **window** of the matched line plus its two predecessors, not the single
         * matched line alone: `DISPUTES.md`'s `ORA2-CONV-01..04` sentence wraps across a markdown
         * line break, so "square-bracketed" lands on the line *before* the one carrying the marker
         * (`(a square-bracketed` / `` `ORA2-CONV-01..04`) was also renamed``). A same-line-only
         * check would misclassify a real historical mention as an offender. See
         * [isHistoricalMention].
         *
         * Chosen over gating by syntactic context (backticked declaration vs. string/comment)
         * because the legitimate population is small, stable, and self-labels with this exact
         * word; a proximity content check is simpler to read and to audit than a Kotlin-lexer-
         * aware scanner, and simple beats clever here (computenet-os2f's dispatch).
         *
         * **Known blind spot, stated rather than hidden:** this is a proximity check, not a
         * grammatical one. A future legitimate exception must co-locate its marker and this
         * phrase within the window, and nothing stops a genuinely un-renamed marker from landing
         * within three lines of unrelated prose that happens to discuss "square-bracketed" ids --
         * that combination would pass. The bracket-only ban this replaces caught neither case at
         * all; this narrows the blind spot rather than closing it.
         */
        val HISTORICAL_MENTION = Regex("square-bracketed")

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

    /**
     * True when [lines]`[index]` (which has already matched [FORBIDDEN]) sits inside a
     * legitimate historical mention of the retired shape: [HISTORICAL_MENTION] appears on that
     * line or either of its two predecessors, so a markdown line-wrap between the explanatory
     * phrase and the marker it explains does not misclassify the marker as an offender. See
     * [HISTORICAL_MENTION]'s KDoc.
     */
    private fun isHistoricalMention(lines: List<String>, index: Int): Boolean {
        val window = lines.subList(maxOf(0, index - 2), index + 1)
        return window.any { HISTORICAL_MENTION.containsMatchIn(it) }
    }

    @Test
    fun `no oracle source, kernel test, build file or ledger entry writes an ORA1 or ORA2 marker in the retired bracketed or bare hyphen form`() {
        val root = repoRoot()
        val offenders = governedFiles().flatMap { file ->
            val lines = file.readLines()
            lines.withIndex()
                .filter { (index, line) ->
                    FORBIDDEN.containsMatchIn(line) && !isHistoricalMention(lines, index)
                }
                .map { (index, _) -> "${file.relativeTo(root)}:${index + 1}" }
        }

        withClue(
            "These lines write an ORA1 or ORA2 marker in the bracketed shape this repo reserves " +
                "for EARS requirement ids in doc/spec, or in the bare hyphen shape " +
                "(\"ORA1-SHRINK-01\") that a Kotlin backticked declaration name is forced into " +
                "because `[` is illegal there. Both families are acceptance clauses of the beads " +
                "items that built the :oracle harness, which have no doc/spec home and are not " +
                "meant to: write them as \"ORA1 §HONEST-01\" / \"ORA2 §MODEL-12\". See " +
                "OracleSweep.kt's file KDoc and concord/corpus/DISPUTES.md. A line that quotes the " +
                "retired shape to explain the rename, in a paragraph containing the word " +
                "\"square-bracketed\" within two lines above it, is exempt -- see " +
                "HISTORICAL_MENTION's and isHistoricalMention's KDoc for which four mentions that " +
                "covers and its known blind spot. Offenders: $offenders",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the four legitimate historical mentions of the retired bare shape are recognized as markers and exempted, not merely absent`() {
        // Each signature below is expected to locate a line that (a) actually matches FORBIDDEN
        // -- i.e. genuinely carries the retired bare shape, so the exemption is not vacuous -- and
        // (b) is recognized by isHistoricalMention's window check. If either fails, the offender
        // scan above is passing on these four for the wrong reason: because they stopped existing,
        // or moved out of the window, not because the discriminator recognized them.
        val root = repoRoot()
        val historicalLineSignatures = listOf(
            // DISPUTES.md: "ORA2's markers were renamed from a square-bracketed `ORA2-MODEL-12` to ..."
            // -- phrase and marker on the same line.
            File(root, "concord/corpus/DISPUTES.md") to "renamed from a square-bracketed `ORA2-MODEL-12`",
            // DISPUTES.md: "... (a square-bracketed" / "`ORA2-CONV-01..04`) was also renamed ..."
            // -- the marker's own line, one line below the "square-bracketed" phrase (a markdown wrap).
            File(root, "concord/corpus/DISPUTES.md") to "`ORA2-CONV-01..04`) was also renamed",
            // OracleSweep.kt:99 -- two markers, both on the phrase's own line.
            File(root, "oracle/src/main/kotlin/civictech/oracle/run/OracleSweep.kt") to
                "deliberately **not** square-bracketed `ORA2-MODEL-12` or `ORA1-HONEST-01`",
        )
        historicalLineSignatures.forEach { (file, signature) ->
            val lines = file.readLines()
            val index = lines.indexOfFirst { it.contains(signature) }
            withClue("Expected a line containing \"$signature\" in ${file.relativeTo(root)}; it may have moved or been reworded") {
                (index >= 0) shouldBe true
            }
            val found = lines[index]
            withClue("This historical-mention line no longer carries an ORA1/ORA2 marker at all: $found") {
                FORBIDDEN.containsMatchIn(found) shouldBe true
            }
            withClue(
                "This historical-mention line (and its two predecessors) no longer carry " +
                    "\"square-bracketed\", so isHistoricalMention would no longer exempt it: $found",
            ) {
                isHistoricalMention(lines, index) shouldBe true
            }
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
