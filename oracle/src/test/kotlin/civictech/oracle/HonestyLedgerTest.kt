package civictech.oracle

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The honesty ledger, pinned as build-checked prose (`computenet-4ru.10.4`).
 *
 * Two requirements of epic `computenet-4ru` are satisfied by *text*, not by behaviour:
 *
 * - `[ORA1-HONEST-01]` — the module's entry point states that the reference model's correctness
 *   is **defended, not proven**, and names the four defenses with their landed test classes:
 *   independence (`[ORA1-MODEL-10]` / `ModelImportBoundaryTest`), the divergence control
 *   (`DivergenceControlTest`), the mutation check (`MutationCheckTest`), and the concord corpus
 *   cross-check (`CorpusCrossCheckTest`). It lives in
 *   `civictech.oracle.run.OracleSweep`'s file KDoc.
 * - `[ORA1-HONEST-02]` — every operator the vocabulary deliberately excludes carries a written
 *   reason at its exclusion site, which is `civictech.oracle.model.MapCellModel`'s file KDoc.
 *
 * Text satisfies a requirement only while it is still there. A KDoc paragraph has no compiler,
 * no caller and no test: a refactor that rewrites the file, or an edit that trims "a long
 * comment", removes the deliverable and nothing goes red. So this test **reads the two source
 * files from the module tree** and fails if the statement or any ledger entry's reason
 * disappears. `civictech.cell.ModuleInventoryTest` (`:kernel`) and
 * [civictech.oracle.model.ModelImportBoundaryTest] are the repo's idiom for source-reading tests
 * of exactly this kind.
 *
 * ## What this test can and cannot check
 *
 * It checks that each claim's **load-bearing fingerprint** is present: the requirement marker,
 * the four defense class names, one bullet per excluded operator, a kernel-source citation
 * inside each such bullet (the "verified against its own kernel source" clause is what
 * distinguishes a reason from an assertion), and the per-entry `DISPUTES` audit conclusion. It
 * cannot check that the prose is *true*, or that a reason is a *good* one — no test can. A
 * reader who rewrites an entry and keeps its fingerprint has to be honest on their own; what
 * this test removes is the silent-deletion path, which is the failure mode that actually
 * happened to comparable ledgers.
 *
 * ## The `DISPUTES.md` half
 *
 * The feature's audit concluded that no *exclusion* leaves a normative requirement unchecked
 * anywhere, so no exclusion produced a `concord/corpus/DISPUTES.md` filing — each entry records
 * that conclusion with the instrument that does check it. One filing exists all the same, and it
 * is not an exclusion: `[ORA1-DIFF-09]`/BS-12, the divergence control that cannot be built while
 * `civictech.oracle.model.Membership` and `SetCell` disagree about `[24-SET-03]`'s observer
 * (settled by `computenet-eeys`: the reference model is the wrong side, and no kernel defect is
 * implied). The last test below pins that entry's own fingerprint, so the filing cannot be
 * dropped while the disagreement stands.
 *
 * Every phrase assertion runs against **whitespace-flattened** text, so re-wrapping a
 * KDoc paragraph or a Markdown bullet never reddens this test — only deleting or rewriting the
 * claim does. A ledger test that fails on a line break trains its readers to edit the test.
 *
 * ## Deliberately NOT checked here
 *
 * The pair-shaped generation-unreachability of `joinSet`/`semiJoin`/`antiJoin`/`groupBy*` is
 * `computenet-4ru.16`'s call, parked for a human. This test pins that the ledger draws the
 * *distinction* between "uncheckable by a batch reference" and "checkable but unreachable by
 * shape-typed generation", and that 4ru.16 is named as undecided — it does not pin any verdict
 * on that family, and a future entry recording 4ru.16's outcome is not a failure here.
 */
class HonestyLedgerTest {

    // ---------------------------------------------------------------- sources

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun sourceText(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        withClue("$relativePath must exist — it is where a requirement of this epic is written") {
            file.isFile shouldBe true
        }
        return file.readText()
    }

    private fun oracleSweepSource() = sourceText("oracle/src/main/kotlin/civictech/oracle/run/OracleSweep.kt")

    private fun mapCellModelSource() = sourceText("oracle/src/main/kotlin/civictech/oracle/model/MapCellModel.kt")

    private fun disputesSource() = sourceText("concord/corpus/DISPUTES.md")

    /**
     * Whitespace-flattened text: every run of whitespace becomes one space. Phrase assertions run
     * against this so that re-wrapping a paragraph is not a test failure — the claim's *words* are
     * what this test protects, not the column at which they wrap.
     */
    private fun String.flat(): String = replace(Regex("\\s+"), " ").trim()

    /**
     * Assert this text still carries [phrase], reporting the **phrase** rather than the haystack.
     *
     * `shouldContain` prints the whole subject on failure, and these subjects are multi-kilobyte
     * KDoc blocks — the one fact a reader needs (which claim went missing) ends up buried under
     * the entire ledger. Failing on a boolean with the phrase in the clue inverts that.
     */
    private fun String.mustState(phrase: String) {
        withClue("The text no longer states: \"$phrase\"") {
            contains(phrase) shouldBe true
        }
    }

    /** A file's first KDoc block with the `*` comment markers stripped, whitespace-flattened. */
    private fun firstKdoc(source: String, what: String): String {
        val start = source.indexOf("/**")
        val end = source.indexOf("*/", start)
        withClue("$what must open with a KDoc block — that is where the requirement is written") {
            (start >= 0 && end > start) shouldBe true
        }
        return source.substring(start + 3, end)
            .lines()
            .joinToString("\n") { it.trimStart().removePrefix("*").removePrefix(" ") }
    }

    /**
     * The exclusion ledger: `MapCellModel.kt`'s **first** KDoc block, comment markers stripped.
     *
     * Taking the first block rather than searching the whole file is what makes the bullet
     * accounting below meaningful — a matching phrase that drifted into the `MapCellModel` class
     * KDoc further down would otherwise satisfy a ledger assertion from outside the ledger.
     */
    private fun exclusionLedger(): String = firstKdoc(mapCellModelSource(), "MapCellModel.kt")

    /** `OracleSweep`'s file KDoc — where `[ORA1-HONEST-01]`'s statement lives. */
    private fun sweepKdoc(): String = firstKdoc(oracleSweepSource(), "OracleSweep.kt")

    /**
     * The ledger split into its bullets: the text from each `- **Name**` marker up to the next
     * one. Returned as pairs so a missing entry and an empty entry fail differently.
     */
    private fun ledgerBullets(): List<Pair<String, String>> {
        val ledger = exclusionLedger()
        val markers = Regex("(?m)^- \\*\\*(.+?)\\*\\*").findAll(ledger).toList()
        return markers.mapIndexed { index, match ->
            val bodyEnd = markers.getOrNull(index + 1)?.range?.first ?: ledger.length
            match.groupValues[1] to ledger.substring(match.range.first, bodyEnd)
        }
    }

    // -------------------------------------------------- [ORA1-HONEST-01]

    @Test
    fun `the module entry point states that the reference model is defended, not proven`() {
        val sweep = sweepKdoc().flat()

        withClue("[ORA1-HONEST-01] must be claimed by marker at the entry point, so a reader can find it") {
            sweep.mustState("[ORA1-HONEST-01]")
        }
        withClue(
            "The statement itself must be present in as many words. A green sweep says the two " +
                "sides AGREED; it does not say the reference is right, and that is the whole point " +
                "of this requirement.",
        ) {
            sweep.mustState("DEFENDED, not PROVEN")
            sweep.mustState("It does **not** say the reference model is correct")
        }
    }

    @Test
    fun `the statement names all four defenses by their landed test class names`() {
        val sweep = sweepKdoc().flat()

        // Each pair is (what the defense is, the class that landed it). A defense named without
        // its class is a promise; the class name is what makes it checkable by a reader.
        val defenses = listOf(
            "independence [ORA1-MODEL-10]" to "ModelImportBoundaryTest",
            "the divergence control" to "DivergenceControlTest",
            "the mutation check" to "MutationCheckTest",
            "the corpus cross-check" to "CorpusCrossCheckTest",
        )

        val missing = defenses.filterNot { (_, className) -> sweep.contains(className) }
        withClue(
            "OracleSweep's [ORA1-HONEST-01] section must name every defense by its landed test " +
                "class. Missing: ${missing.map { it.first }}. If a defense was renamed, rename it " +
                "here too; if one was DELETED, the honest edit is to say so in the KDoc, not to " +
                "drop the line.",
        ) {
            missing.shouldBeEmpty()
        }

        withClue(
            "The divergence control is currently the weakest of the four ([ORA1-DIFF-09]/BS-12 is " +
                "filed in DISPUTES.md, not built). A ledger that lists it flat alongside the other " +
                "three overstates what the sweep's green is worth.",
        ) {
            sweep.mustState("blocked on computenet-eeys")
            sweep.mustState("weakest of the four")
        }
    }

    @Test
    fun `the two halves of the ledger cross-reference each other`() {
        withClue("A reader arriving at either half must be able to find the other (the bead's own clause)") {
            sweepKdoc().flat().mustState("civictech.oracle.model.MapCellModel")
            exclusionLedger().flat().mustState("[ORA1-HONEST-01]")
            exclusionLedger().flat().mustState("civictech.oracle.run.OracleSweep")
        }
    }

    // -------------------------------------------------- [ORA2-HONEST-01]

    /**
     * `[ORA2-HONEST-01]`: the same module entry point states that ORA2's tagged/keyed model is
     * LESS independent of the tag algebra than ORA1's membership-only model, and names
     * `[ORA2-CTL-01]`..`[ORA2-CTL-04]` as the compensating evidence — feature computenet-4ru.1
     * §4.9 and §4.10, computenet-4ru.1.5's own acceptance criteria.
     */
    @Test
    fun `the module entry point states that ORA2's model is LESS independent, and names the four controls`() {
        val sweep = sweepKdoc().flat()

        withClue("[ORA2-HONEST-01] must be claimed by marker, so a reader can find it") {
            sweep.mustState("[ORA2-HONEST-01]")
        }
        withClue(
            "The statement itself: ORA2's model reads MODELLED dot order and is therefore LESS " +
                "independent of the tag algebra it checks than ORA1's membership-only model is.",
        ) {
            sweep.mustState("LESS independent of the tag algebra")
            sweep.mustState("modelled dot order")
        }
        withClue("Every one of the four blocking controls must be named by marker, not just counted") {
            listOf("[ORA2-CTL-01]", "[ORA2-CTL-02]", "[ORA2-CTL-03]", "[ORA2-CTL-04]").forEach { marker ->
                sweep.mustState(marker)
            }
        }
        withClue("The landed test class carrying the four controls must be named") {
            sweep.mustState("civictech.oracle.tagged.TaggedControlsTest")
        }
        withClue(
            "The blocking status feature §4.9 states in as many words: a green sweep is not " +
                "evidence without these four.",
        ) {
            sweep.mustState("a green ORA2 sweep is not evidence without")
        }
    }

    // -------------------------------------------------- [ORA2-HONEST-02]

    @Test
    fun `the module entry point states what a sweep records about the optional families`() {
        val sweep = sweepKdoc().flat()

        withClue("[ORA2-HONEST-02] must be claimed by marker") {
            sweep.mustState("[ORA2-HONEST-02]")
        }
        withClue("It must name the instrument that does the recording, and that it reuses the existing report") {
            sweep.mustState("reportOptionalFamilies")
            sweep.mustState("civictech.oracle.bind.OptionalFamilies")
            sweep.mustState("not a second report format")
        }
    }

    // -------------------------------------------------- [ORA1-HONEST-02]

    /**
     * The excluded operators, spelled out by hand rather than derived from the ledger itself —
     * derivation would make this test verify the ledger against itself, and an entry that was
     * silently deleted would still pass. Same reasoning as
     * `civictech.oracle.bind.VocabularyCompletenessTest`'s hand-spelled vocabulary.
     */
    private val excludedOperators = listOf(
        "`ListCell` / `ListDelta`",
        "`OrMapCell` / `TaggedMapDelta`",
        "`MergeableGroupByCell`",
        "Window close / eviction",
        "`CoalescingCombineCell`",
        "`WatermarkCell`",
    )

    @Test
    fun `every excluded operator has a ledger entry`() {
        val present = ledgerBullets().map { it.first }
        val missing = excludedOperators.filterNot { expected -> present.any { it.startsWith(expected) } }
        withClue(
            "[ORA1-HONEST-02]: an operator excluded from the vocabulary must carry its reason at " +
                "the exclusion site. Missing entries: $missing; entries found: $present. Adding an " +
                "operator BACK into the vocabulary is a legitimate reason to remove its entry — and " +
                "then this list changes in the same commit as OperatorCatalog's registration.",
        ) {
            missing.shouldBeEmpty()
        }
    }

    @Test
    fun `every ledger entry carries a reason evidenced against kernel source`() {
        val reasonless = ledgerBullets()
            .filter { (name, _) -> excludedOperators.any { name.startsWith(it) } }
            .filterNot { (_, body) ->
                // The clause the ledger's own KDoc promises: "a reason verified against its own
                // kernel source". A bullet that cites no kernel path is an assertion, not a reason.
                body.contains("kernel/src/main/kotlin/") || body.contains("epic computenet-4ru")
            }
            .map { it.first }

        withClue(
            "Each exclusion's body must carry the evidence its reason rests on — a kernel source " +
                "path, or the epic scope decision that assigns the operator elsewhere. Bare: " +
                "$reasonless",
        ) {
            reasonless.shouldBeEmpty()
        }
    }

    @Test
    fun `every ledger entry records its DISPUTES audit conclusion`() {
        val unaudited = ledgerBullets()
            .filter { (name, _) -> excludedOperators.any { name.startsWith(it) } }
            .filterNot { (_, body) -> body.contains("DISPUTES audit:") }
            .map { it.first }

        withClue(
            "The bead's third criterion: the DISPUTES WHERE-clause assessment is recorded PER " +
                "ENTRY with its conclusion, so the audit is readable evidence rather than a silent " +
                "judgment. Entries with no recorded conclusion: $unaudited",
        ) {
            unaudited.shouldBeEmpty()
        }
    }

    @Test
    fun `the ledger distinguishes uncheckable-by-batch-reference from unreachable-by-generation`() {
        val ledger = exclusionLedger()

        withClue(
            "The two are different defect classes and only the first is this ledger's subject. " +
                "Without the distinction stated, recording computenet-4ru.16's eventual outcome is " +
                "a re-design rather than one entry.",
        ) {
            ledger.mustState("Uncheckable by a batch reference")
            ledger.mustState("unreachable by shape-typed generation")
        }
        withClue("computenet-4ru.16 is parked for a human; this ledger must name it as UNDECIDED, not answer it") {
            ledger.mustState("computenet-4ru.16")
            ledger.mustState("undecided")
        }
    }

    @Test
    fun `the WatermarkCell exclusion is resolved with a reason, not left as an open pointer`() {
        val ledger = exclusionLedger()

        withClue(
            "computenet-fx5b: WatermarkCell was in neither OperatorCatalog nor this ledger, " +
                "recorded as an open pointer. fx5b settles it as an exclusion — replication " +
                "settlement is CHA1/CHA3's decided scope (epic computenet-4ru §6), and the cell " +
                "is also structurally undriveable by a batch script reference (no @Contract, no " +
                "application-facing Use<Ops> inlet). Both reasons must survive in the ledger " +
                "text, not just the bead comment.",
        ) {
            ledger.mustState("WatermarkCell")
            ledger.mustState("computenet-fx5b")
            ledger.mustState("kernel/src/main/kotlin/civictech/cell/data/Watermark.kt")
            ledger.mustState("there is no `@Contract`")
            ledger.mustState("Replication, partition, crash-restart, membership")
            ledger.mustState("not named in epic computenet-4ru §3.1's operator inventory")
        }
    }

    /**
     * The `counter`/`pnCounter` coverage note (computenet-gff7): the ledger's own section
     * text, from its `###` heading to the next one.
     *
     * Scoped to the section rather than asserted against the whole ledger, because the words
     * that matter here — `CoalescingCombineCell` above all — also occur in that cell's own
     * exclusion entry further down. A whole-ledger `mustState("CoalescingCombineCell")` would
     * stay green with this section deleted outright, which is precisely the silent-deletion
     * path this file exists to close.
     */
    private fun counterCoverageNote(): String {
        val ledger = exclusionLedger()
        val start = ledger.indexOf("### `counter` / `pnCounter`")
        withClue(
            "The ledger must carry the counter/pnCounter coverage note as its own section " +
                "(computenet-gff7). It is what stops `registered` reading as `exercised` for the " +
                "two entries no generated case can spawn.",
        ) {
            (start >= 0) shouldBe true
        }
        val end = ledger.indexOf("\n###", start + 1).let { if (it < 0) ledger.length else it }
        return ledger.substring(start, end)
    }

    @Test
    fun `the ledger records that counter and pnCounter are registered but never exercised`() {
        val note = counterCoverageNote().flat()

        withClue(
            "computenet-gff7's third criterion: the ledger must say WHICH of its two outcomes " +
                "holds for counter/pnCounter, so that `registered` stops implying `exercised`. " +
                "The outcome recorded is NOT-FIXED-deliberately, and the verdict has to be in the " +
                "text — a section that describes the hole without stating the decision leaves the " +
                "next reader to re-litigate it.",
        ) {
            note.mustState("REGISTERED but NOT EXERCISED")
            note.mustState("computenet-gff7")
            note.mustState("\"registered\" does not imply \"exercised\"")
            note.mustState("no registered operator consumes a bare scalar on any port")
        }
    }

    @Test
    fun `the counter coverage note gives its reason and its DISPUTES audit, like every other entry`() {
        val note = counterCoverageNote().flat()

        withClue(
            "The same two clauses every exclusion entry carries — a reason evidenced against " +
                "kernel source, and a recorded DISPUTES conclusion — bind this note too. Its " +
                "reason is specifically that the ONE cell serving Propagate<CounterDelta> on an " +
                "inlet is CoalescingCombineCell, which this ledger already excludes: the note is " +
                "an exclusion's consequence, and without that citation it is an assertion rather " +
                "than a reason.",
        ) {
            note.mustState("CoalescingCombineCell")
            note.mustState("kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt")
            note.mustState("DISPUTES audit: no filing")
        }
    }

    // -------------------------------------------------- the DISPUTES filing

    @Test
    fun `the DISPUTES filing for ORA1-DIFF-09 records the disagreement it names`() {
        val disputes = disputesSource()

        val entryStart = disputes.indexOf("## ORA1 (the divergence control)")
        withClue(
            "The [ORA1-DIFF-09]/BS-12 filing must exist in concord/corpus/DISPUTES.md. The epic's " +
                "rule is that a requirement which cannot be checked honestly is FILED, never " +
                "weakened into a passing scenario — deleting this entry without building the " +
                "control is the weakening.",
        ) {
            entryStart shouldBeGreaterThan -1
        }
        val entry = disputes.substring(entryStart).flat()

        withClue("It must name the requirement in dispute, and both artifacts that read it differently") {
            entry.mustState("[24-SET-03]")
            entry.mustState("SetCell.inletHandler.remove")
            entry.mustState("civictech.oracle.model.Membership")
        }
        withClue(
            "It must state which side is wrong — settled by computenet-eeys — so no reader takes " +
                "the entry for an open question or for a kernel defect.",
        ) {
            entry.mustState("computenet-eeys")
            entry.mustState("the reference model is the wrong side")
            entry.mustState("no kernel defect is implied")
        }
        withClue("It must carry the measurement, not only the argument") {
            entry.mustState("22 of 60")
        }
        withClue(
            "And it must name what is not lost: DivergenceControlTest pins the measurement, and " +
                "its second test is the tripwire that reddens when BS-12 becomes implementable.",
        ) {
            entry.mustState("DivergenceControlTest")
            entry.mustState("tripwire")
        }
    }
}
