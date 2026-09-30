package civictech.concord.provenance

import java.io.File
import kotlin.system.exitProcess

/**
 * L4 provenance (Concord §1.5, `concord/schema/provenance.md`): scans the L0
 * requirement ids declared inline in `doc/spec` chapters (recursively) and the
 * L2 `covers:` tags declared in `concord/corpus` scenarios (recursively), and derives the
 * concordance table plus the three lints (§3 of provenance.md).
 *
 * Deliberately textual, not schema-based: this is a Gradle task (not a JUnit
 * harness), and `concord/build.gradle.kts` keeps the YAML front end (kaml) and
 * the [civictech.concord.schema.Scenario] types test-scoped. A corpus scenario
 * needs only two fields for provenance purposes (`id`, `covers`), so a light
 * line-oriented scan avoids pulling a YAML parser into `main` for that.
 */
object ConcordanceScanner {

    /** An L0 requirement id declared inline as `[NN-SLUG-nn]` in a spec chapter. */
    data class Requirement(val id: String, val sourceFile: String)

    /** A corpus scenario's provenance-relevant fields. */
    data class CorpusScenario(val id: String?, val covers: List<String>, val sourceFile: String)

    /**
     * Matches the id scheme in provenance.md §1: `«chapter»-«slug»-«nn»`, e.g.
     * `21-PROP-01`. The slug may itself be multi-segment — the minted operator
     * ids carry a compound slug (`24-OP-UNION-01`, `24-OP-GROUPBY-01`), so a
     * single `[A-Z][A-Z0-9]*` run is not enough; one or more `-SEGMENT` runs are
     * allowed before the trailing `-nn` ordinal. `[93]` (no slug/ordinal) is
     * still ignored.
     */
    private val idPattern = Regex("""\[(\d{2}-[A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*-\d{2})]""")

    /**
     * Distinguishes a genuine declaration from a citation of an
     * already-declared id (`concord/schema/provenance.md` §1, "Declaration
     * vs citation"). An id opens a declaration when the requirement text
     * immediately following it starts a new sentence — the first letter,
     * after skipping any wrapping backtick or markdown bold marker, is
     * uppercase: `[42-WM-01] The delivered-watermark state SHALL carry…` or
     * `` `[24-WL-01]` A lateness declaration SHALL be… ``. A citation
     * continues an existing sentence in lowercase, or is followed by
     * punctuation: `a `[42-WM-01]` delivered-prefix row`, `(`[24-WL-04]`)`.
     */
    private fun isDeclaration(text: String, afterIndex: Int): Boolean {
        var i = afterIndex
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text[i] == '`' -> i++
                text.startsWith("**", i) -> i += 2
                else -> return text[i].isUpperCase()
            }
        }
        return false
    }

    /**
     * Scans every `.md` file under [specRoot] for inline `[NN-SLUG-nn]` tags.
     * A given id may be declared (and re-referenced, or cited from another
     * chapter) in more than one place; the [Requirement] record's
     * [Requirement.sourceFile] is the chapter that DECLARES the id — the
     * first-by-path-order sighting classified by [isDeclaration] — falling
     * back to the first sighting of any kind when no occurrence of an id
     * looks like a declaration (a corpus shape this heuristic cannot read,
     * kept working exactly as before rather than guessing). Duplicates never
     * produce a second requirement row.
     */
    fun scanRequirements(specRoot: File): List<Requirement> {
        if (!specRoot.exists()) return emptyList()
        val firstSeen = LinkedHashMap<String, Requirement>()
        val firstDeclaration = LinkedHashMap<String, Requirement>()
        specRoot.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .forEach { file ->
                val relative = file.relativeTo(specRoot).path
                val text = file.readText()
                idPattern.findAll(text).forEach { m ->
                    val id = m.groupValues[1]
                    firstSeen.putIfAbsent(id, Requirement(id, relative))
                    if (isDeclaration(text, m.range.last + 1)) {
                        firstDeclaration.putIfAbsent(id, Requirement(id, relative))
                    }
                }
            }
        return firstSeen.keys.map { id -> firstDeclaration[id] ?: firstSeen.getValue(id) }
    }

    /** The six normative chapter directory prefixes (00 foundations .. 50 process); 90-roadmap is not normative text. */
    private val normativeChapterPrefixes = listOf("00-", "10-", "20-", "30-", "40-", "50-")

    /**
     * Every chapter file under a normative directory (00/10/20/30/40/50),
     * whether or not it carries any requirement id — the full denominator
     * `provenance.md` and the concordance table's coverage % implicitly range
     * over (T02-D). A chapter directory currently holds chapter files
     * directly (no nesting), but this scans one level deep defensively.
     */
    fun scanNormativeChapterFiles(specRoot: File): List<String> {
        if (!specRoot.exists()) return emptyList()
        val chapterDirs = specRoot.listFiles { f -> f.isDirectory && normativeChapterPrefixes.any { p -> f.name.startsWith(p) } }
            ?.sortedBy { it.name }
            ?: emptyList()
        return chapterDirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name }
                ?.map { it.relativeTo(specRoot).path }
                .orEmpty()
        }
    }

    /**
     * Provenance data for the two ownership-visibility lints (computenet-7ei34,
     * residual of computenet-nta1; provenance.md §3, "Unestablished ownership"
     * / "Contested ownership"). [scanRequirements] silently resolves both
     * shapes below — this is the same-cost second scan that makes the
     * resolution visible instead.
     *
     * - [idsWithNoDeclaration]: ids where [isDeclaration] returned false for
     *   *every* occurrence anywhere under `specRoot` (not just normative
     *   chapters) — [scanRequirements] falls back to first-sighting-by-path
     *   for these. A declaration-classified occurrence in a non-normative
     *   `90-roadmap/` file still keeps an id out of this set, matching
     *   [scanRequirements]'s own fallback rule exactly (a roadmap ticket
     *   quoting the chapter's sentence is enough to resolve the id there
     *   too, for better or worse — see clause 1 of the ticket report).
     * - [idsWithMultipleNormativeDeclarations]: ids with a declaration-classified
     *   occurrence in more than one *normative chapter file* (a `.md` file
     *   directly under a `00-`..`50-` directory — the same file granularity
     *   `scanNormativeChapterFiles` reports, and the granularity the id scheme
     *   itself uses: "«chapter»" in `«chapter»-«slug»-«nn»` names one such
     *   file, e.g. `22`, `24`), mapped to the sorted list of those files'
     *   relative paths. A normative chapter's declaration alongside a
     *   `90-roadmap/` ticket's citation that misreads as a declaration
     *   (ticket clause 2/3) does not count here — that shape resolves
     *   correctly today by path order, and roadmap prose is not itself an
     *   owner. Two normative chapter files both reading as the declarer is
     *   the genuine ambiguity this set exists to surface.
     */
    data class DeclarationProvenance(
        val idsWithNoDeclaration: Set<String>,
        val idsWithMultipleNormativeDeclarations: Map<String, List<String>>,
    )

    fun scanDeclarationProvenance(specRoot: File): DeclarationProvenance {
        if (!specRoot.exists()) return DeclarationProvenance(emptySet(), emptyMap())
        val everSeen = mutableSetOf<String>()
        val everDeclared = mutableSetOf<String>()
        val normativeDeclarationChapters = LinkedHashMap<String, MutableSet<String>>()
        specRoot.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .forEach { file ->
                val relative = file.relativeTo(specRoot).path
                val topDir = relative.substringBefore('/')
                val isNormative = normativeChapterPrefixes.any { topDir.startsWith(it) }
                val text = file.readText()
                idPattern.findAll(text).forEach { m ->
                    val id = m.groupValues[1]
                    everSeen += id
                    if (isDeclaration(text, m.range.last + 1)) {
                        everDeclared += id
                        if (isNormative) {
                            normativeDeclarationChapters.getOrPut(id) { linkedSetOf() }.add(relative)
                        }
                    }
                }
            }
        val idsWithMultiple = normativeDeclarationChapters
            .filterValues { it.size > 1 }
            .mapValues { it.value.sorted() }
        return DeclarationProvenance(everSeen - everDeclared, idsWithMultiple)
    }

    private val idLine = Regex("""^id:\s*['"]?([^'"#]+?)['"]?\s*(#.*)?$""")
    private val coversInlineLine = Regex("""^covers:\s*\[(.*?)]\s*(#.*)?$""")
    private val coversBlockHeader = Regex("""^covers:\s*(#.*)?$""")
    private val blockListItem = Regex("""^-\s*['"]?([^'"#]+?)['"]?\s*(#.*)?$""")

    /**
     * Scans every `.yaml`/`.yml` file directly under [corpusRoot] (recursively)
     * for the scenario's top-level `id:` and `covers:` fields. Handles both
     * flow style (`covers: [a, b]`, `covers: []`) and block style
     * (`covers:` followed by indented `- a` lines), the two shapes the schema
     * (`concord/schema/scenario.md`) allows for a `List<String>`.
     */
    fun scanScenarios(corpusRoot: File): List<CorpusScenario> {
        if (!corpusRoot.exists()) return emptyList()
        return corpusRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .sortedBy { it.path }
            .map { file -> parseScenario(file, file.relativeTo(corpusRoot).path) }
            .toList()
    }

    private fun parseScenario(file: File, relative: String): CorpusScenario {
        var id: String? = null
        val covers = mutableListOf<String>()
        var inCoversBlock = false

        file.readLines().forEach { rawLine ->
            // Only top-level (unindented) keys are the scenario's own fields;
            // indentation ends a `covers:` block list.
            val isIndented = rawLine.startsWith(" ") || rawLine.startsWith("\t")
            val line = rawLine.trim()

            if (inCoversBlock) {
                if (isIndented && blockListItem.matches(line)) {
                    covers += blockListItem.matchEntire(line)!!.groupValues[1].trim()
                    return@forEach
                } else {
                    inCoversBlock = false
                    // fall through: this line may itself be `id:`/`covers:` etc.
                }
            }

            if (line.isEmpty() || line.startsWith("#") || isIndented) return@forEach

            idLine.matchEntire(line)?.let { id = it.groupValues[1].trim() }
            coversInlineLine.matchEntire(line)?.let { m ->
                covers += m.groupValues[1].split(",")
                    .map { it.trim().trim('\'', '"') }
                    .filter { it.isNotEmpty() }
            }
            if (coversBlockHeader.matches(line)) inCoversBlock = true
        }

        return CorpusScenario(id, covers, relative)
    }
}

/** Severity of a provenance lint finding (provenance.md §3). */
enum class Severity { FATAL, NOTE }

data class LintFinding(val severity: Severity, val message: String)

/** One row of the concordance table: a requirement and the scenarios that cover it. */
data class ConcordanceRow(val requirement: String, val sourceFile: String, val scenarios: List<String>) {
    val isGap: Boolean get() = scenarios.isEmpty()
}

data class ConcordanceReport(val rows: List<ConcordanceRow>, val findings: List<LintFinding>) {
    val fatalFindings: List<LintFinding> get() = findings.filter { it.severity == Severity.FATAL }
    val noteFindings: List<LintFinding> get() = findings.filter { it.severity == Severity.NOTE }
}

/**
 * T02-D (denominator honesty): the concordance table below only ever ranges
 * over chapters that carry at least one requirement id — a chapter with zero
 * ids has no rows and cannot appear as a gap, so it reads as silently passing
 * rather than as excluded. [withIds]/[withoutIds] are the normative chapter
 * files (00/10/20/30/40/50; 90-roadmap is not normative text) partitioned by
 * whether [ConcordanceScanner.scanRequirements] found any id in them, so the
 * coverage percentage is never read against a bare, unexplained denominator.
 */
data class ChapterDenominator(val withIds: List<String>, val withoutIds: List<String>) {
    val total: Int get() = withIds.size + withoutIds.size
}

fun computeChapterDenominator(
    chapterFiles: List<String>,
    requirements: List<ConcordanceScanner.Requirement>,
): ChapterDenominator {
    val filesWithIds = requirements.map { it.sourceFile }.toSet()
    val withIds = chapterFiles.filter { it in filesWithIds }
    val withoutIds = chapterFiles.filter { it !in filesWithIds }
    return ChapterDenominator(withIds, withoutIds)
}

/**
 * Builds the concordance from a scan of L0 requirements and L2 scenarios
 * (provenance.md §2/§3). Pure function — no I/O — so it is unit-testable
 * against fixtures without touching the real, evolving corpus.
 *
 * Ambiguity resolved here (see the ticket report): provenance.md's "coverage
 * gap" lint is defined as "a `Specified`-status requirement with no covering
 * scenario". There is no separate per-id status field in the id scheme
 * (§1.1) — CONCORD-PLAN §1.1 states an id is only ever assigned "when it is
 * checkable through the driver SPI", i.e. every declared L0 id is already a
 * specified, checkable requirement by construction. So every scanned
 * requirement id is eligible for the coverage-gap check; no separate status
 * filter is applied.
 *
 * [declarationProvenance] (computenet-7ei34; provenance.md §3, "Unestablished
 * ownership" / "Contested ownership") is optional and defaults to empty so
 * every existing caller and fixture-only test keeps working unchanged; the
 * `:concordance` CLI task below is the only caller that supplies a real scan.
 */
fun buildConcordance(
    requirements: List<ConcordanceScanner.Requirement>,
    scenarios: List<ConcordanceScanner.CorpusScenario>,
    declarationProvenance: ConcordanceScanner.DeclarationProvenance =
        ConcordanceScanner.DeclarationProvenance(emptySet(), emptyMap()),
): ConcordanceReport {
    val requirementIds = requirements.map { it.id }.toSet()
    val coverageOf = mutableMapOf<String, MutableList<String>>()
    val findings = mutableListOf<LintFinding>()

    scenarios.forEach { scenario ->
        val label = scenario.id ?: scenario.sourceFile
        if (scenario.covers.isEmpty()) {
            findings += LintFinding(
                Severity.FATAL,
                "Orphan scenario: '$label' (${scenario.sourceFile}) has an empty/absent covers: list",
            )
            return@forEach
        }
        scenario.covers.forEach { coveredId ->
            if (coveredId !in requirementIds) {
                findings += LintFinding(
                    Severity.FATAL,
                    "Dangling covers id: '$coveredId' in scenario '$label' (${scenario.sourceFile}) " +
                        "matches no declared L0 requirement",
                )
            } else {
                coverageOf.getOrPut(coveredId) { mutableListOf() }.add(label)
            }
        }
    }

    val rows = requirements.sortedBy { it.id }.map { req ->
        val covering = coverageOf[req.id]?.distinct()?.sorted().orEmpty()
        if (covering.isEmpty()) {
            findings += LintFinding(
                Severity.NOTE,
                "Coverage gap: requirement '${req.id}' (${req.sourceFile}) has no covering scenario",
            )
        }
        if (req.id in declarationProvenance.idsWithNoDeclaration) {
            findings += LintFinding(
                Severity.NOTE,
                "Unestablished ownership: requirement '${req.id}' (${req.sourceFile}) has no " +
                    "declaration-classified occurrence anywhere in doc/spec/**; attributed to " +
                    "its chapter by the first-sighting-by-path fallback only",
            )
        }
        declarationProvenance.idsWithMultipleNormativeDeclarations[req.id]?.let { chapters ->
            findings += LintFinding(
                Severity.NOTE,
                "Contested ownership: requirement '${req.id}' has declaration-classified " +
                    "occurrences in more than one normative chapter: ${chapters.joinToString(", ")}",
            )
        }
        ConcordanceRow(req.id, req.sourceFile, covering)
    }

    return ConcordanceReport(rows, findings)
}

/**
 * Renders [report] as the concordance table (provenance.md §2) plus a lint
 * findings section. [denominator], when supplied (T02-D), renders a preamble
 * naming which normative chapters carry no requirement id at all — those
 * chapters have no rows below and are invisible to the fatal/note lints, so
 * the coverage number that follows is never read bare.
 */
fun renderConcordanceMarkdown(report: ConcordanceReport, denominator: ChapterDenominator? = null): String = buildString {
    appendLine("# Concordance — L0 requirements × L2 scenarios")
    appendLine()
    appendLine(
        "Generated by `:concord:concordance` (Concord §1.5 / `concord/schema/provenance.md`). " +
            "Do not hand-edit — regenerate instead.",
    )
    appendLine()
    if (denominator != null) {
        appendLine("## Denominator honesty — normative chapters with vs without requirement ids")
        appendLine()
        appendLine(
            "The table below only ever ranges over requirement ids that exist. A chapter with " +
                "zero ids contributes no rows, cannot appear as a dangling/orphan/gap finding, and " +
                "so reads as silently clean rather than as structurally excluded. Read the coverage " +
                "count against this denominator, not against the row count alone.",
        )
        appendLine()
        appendLine(
            "**${denominator.withIds.size} of ${denominator.total}** normative chapters " +
                "(`doc/spec/{00,10,20,30,40,50}-*`) carry at least one `[NN-SLUG-nn]` id.",
        )
        appendLine()
        appendLine("With ids (${denominator.withIds.size}):")
        appendLine()
        if (denominator.withIds.isEmpty()) {
            appendLine("- none")
        } else {
            denominator.withIds.forEach { appendLine("- `$it`") }
        }
        appendLine()
        appendLine(
            "Without ids (${denominator.withoutIds.size}) — structurally excluded from the table " +
                "below; filed as a dispute, not silently passing (`concord/corpus/DISPUTES.md`):",
        )
        appendLine()
        if (denominator.withoutIds.isEmpty()) {
            appendLine("- none")
        } else {
            denominator.withoutIds.forEach { appendLine("- `$it`") }
        }
        appendLine()
    }
    appendLine("| Requirement | Scenarios | Status |")
    appendLine("|---|---|---|")
    if (report.rows.isEmpty()) {
        appendLine("| _(no L0 requirement ids declared yet in doc/spec/**)_ | | |")
    }
    report.rows.forEach { row ->
        val scenarios = if (row.scenarios.isEmpty()) "—" else row.scenarios.joinToString(", ")
        val status = if (row.isGap) "gap" else "covered"
        appendLine("| ${row.requirement} | $scenarios | $status |")
    }
    appendLine()
    appendLine("## Lint findings")
    appendLine()
    val fatal = report.fatalFindings
    val notes = report.noteFindings
    appendLine("### Fatal (dangling covers / orphan scenarios)")
    appendLine()
    if (fatal.isEmpty()) {
        appendLine("None.")
    } else {
        fatal.forEach { appendLine("- ${it.message}") }
    }
    appendLine()
    appendLine("### Notes (coverage gaps, unestablished/contested ownership — the testing agent's worklist)")
    appendLine()
    if (notes.isEmpty()) {
        appendLine("None.")
    } else {
        notes.forEach { appendLine("- ${it.message}") }
    }
}

/**
 * CLI entry point invoked by the `:concord:concordance` Gradle task (a
 * [org.gradle.api.tasks.JavaExec], not a custom task type, so this class
 * needs no Gradle API on its compile classpath).
 *
 * Args: `<specRoot> <corpusRoot> <outputFile> <fatal:true|false>`.
 */
fun main(args: Array<String>) {
    require(args.size == 4) {
        "usage: Concordance <specRoot> <corpusRoot> <outputFile> <fatal:true|false>"
    }
    val (specRootArg, corpusRootArg, outputArg, fatalArg) = args
    val fatalMode = fatalArg.toBooleanStrict()

    val specRoot = File(specRootArg)
    val requirements = ConcordanceScanner.scanRequirements(specRoot)
    val scenarios = ConcordanceScanner.scanScenarios(File(corpusRootArg))
    val declarationProvenance = ConcordanceScanner.scanDeclarationProvenance(specRoot)
    val report = buildConcordance(requirements, scenarios, declarationProvenance)
    val denominator = computeChapterDenominator(ConcordanceScanner.scanNormativeChapterFiles(specRoot), requirements)

    val outputFile = File(outputArg)
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(renderConcordanceMarkdown(report, denominator))

    println("Concordance written to ${outputFile.path}")
    println("Requirements: ${requirements.size}, scenarios: ${scenarios.size}")
    println(
        "Normative chapter denominator: ${denominator.withIds.size} of ${denominator.total} carry " +
            "at least one requirement id (${denominator.withoutIds.size} zero-id: " +
            "${denominator.withoutIds.joinToString(", ")})",
    )
    report.findings.forEach { println("[${it.severity}] ${it.message}") }
    println(
        "${report.fatalFindings.size} fatal finding(s), " +
            "${report.noteFindings.size} note(s) (coverage gaps, unestablished/contested ownership).",
    )

    if (report.fatalFindings.isNotEmpty() && fatalMode) {
        println("Fatal mode: failing build on ${report.fatalFindings.size} fatal lint finding(s).")
        exitProcess(1)
    }
}
