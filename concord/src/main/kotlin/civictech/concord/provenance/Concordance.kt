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
     * Scans every `.md` file under [specRoot] for inline `[NN-SLUG-nn]` tags.
     * A given id may be declared (and re-referenced) in more than one place in
     * a chapter; only the first sighting is kept as the id's [Requirement]
     * record, but duplicates never produce a second requirement row.
     */
    fun scanRequirements(specRoot: File): List<Requirement> {
        if (!specRoot.exists()) return emptyList()
        val seen = LinkedHashMap<String, Requirement>()
        specRoot.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .forEach { file ->
                val relative = file.relativeTo(specRoot).path
                idPattern.findAll(file.readText()).forEach { m ->
                    val id = m.groupValues[1]
                    seen.putIfAbsent(id, Requirement(id, relative))
                }
            }
        return seen.values.toList()
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
 */
fun buildConcordance(
    requirements: List<ConcordanceScanner.Requirement>,
    scenarios: List<ConcordanceScanner.CorpusScenario>,
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
        ConcordanceRow(req.id, req.sourceFile, covering)
    }

    return ConcordanceReport(rows, findings)
}

/** Renders [report] as the concordance table (provenance.md §2) plus a lint findings section. */
fun renderConcordanceMarkdown(report: ConcordanceReport): String = buildString {
    appendLine("# Concordance — L0 requirements × L2 scenarios")
    appendLine()
    appendLine(
        "Generated by `:concord:concordance` (Concord §1.5 / `concord/schema/provenance.md`). " +
            "Do not hand-edit — regenerate instead.",
    )
    appendLine()
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
    appendLine("### Notes (coverage gaps — the testing agent's worklist)")
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

    val requirements = ConcordanceScanner.scanRequirements(File(specRootArg))
    val scenarios = ConcordanceScanner.scanScenarios(File(corpusRootArg))
    val report = buildConcordance(requirements, scenarios)

    val outputFile = File(outputArg)
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(renderConcordanceMarkdown(report))

    println("Concordance written to ${outputFile.path}")
    println("Requirements: ${requirements.size}, scenarios: ${scenarios.size}")
    report.findings.forEach { println("[${it.severity}] ${it.message}") }
    println(
        "${report.fatalFindings.size} fatal finding(s), " +
            "${report.noteFindings.size} coverage-gap note(s).",
    )

    if (report.fatalFindings.isNotEmpty() && fatalMode) {
        println("Fatal mode: failing build on ${report.fatalFindings.size} fatal lint finding(s).")
        exitProcess(1)
    }
}
