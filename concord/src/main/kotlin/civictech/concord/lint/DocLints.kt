package civictech.concord.lint

import java.io.File
import kotlin.system.exitProcess

/**
 * T02-C: three small doc-integrity lints wired into `:concord:check` beside
 * the existing `concordanceGate` (`civictech.concord.provenance.Concordance`)
 * — same idiom: a plain Gradle/Kotlin `JavaExec` main(), no Gradle API on the
 * compile classpath, textual scanning over every markdown file under `doc/spec`.
 *
 * 1. **Package-pointer resolution** (fatal): every backticked `cell.<pkg>.<Type>`
 *    reference must resolve, at the directory level, to a real
 *    `kernel/src/main/kotlin/civictech/cell/<pkg>/` package. Catches drift
 *    like a header citing `cell.attention.*`, a package that was never real.
 * 2. **Requirement-id density** (report-only): ids-per-chapter under the
 *    normative chapter directories `doc/spec/{10,20,30,40,50}-*`; a zero-id
 *    chapter is a NOTE, not a failure — visibility, not a forcing function.
 * 3. **Status-header vocabulary** (fatal): every markdown chapter under
 *    `doc/spec` (excluding `README.md`/`CONCORDANCE.md`) must carry exactly one
 *    `**Status**:` line, in the header block before the first `## ` section,
 *    beginning with one of `Specified|Partial|Implemented|Exploratory|
 *    Historical|Living`.
 */

enum class Severity { FATAL, NOTE }

data class Finding(val severity: Severity, val message: String)

data class ChapterDensity(val relativePath: String, val idCount: Int)

object DocLints {

    /** Same id shape as [civictech.concord.provenance.ConcordanceScanner.idPattern]. */
    private val idPattern = Regex("""\[(\d{2}-[A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*-\d{2})]""")

    /** A backtick-delimited inline-code span, e.g. `` `cell.control.AttentionSupport` ``. */
    private val backtickSpan = Regex("""`([^`\n]*)`""")

    /**
     * A `cell.<pkg>.<Type>` reference inside a backtick span: one-or-more
     * lowercase (package-style) dotted segments, followed directly by an
     * uppercase-led (type-style) segment. Deliberately stops at the first
     * uppercase segment, so `cell.host.ManagedHost.spawn` is read as a
     * pointer into `cell.host` (the `.spawn` suffix is a method, not part of
     * the package path) and `civictech.cell.evolve.PromotionPolicy` matches
     * from the embedded `cell.` onward. Two-segment forms with no package
     * component (`cell.Handle`) intentionally do not match — nothing to
     * resolve at the directory level.
     */
    private val packagePointer = Regex("""cell\.((?:[a-z][a-zA-Z0-9_]*\.)+)[A-Z][A-Za-z0-9_]*""")

    private val chapterHeading = Regex("""^##\s""")
    private val statusLine = Regex("""^\s*>?\s*\*\*Status\*\*:\s*(.*)$""")

    val allowedStatusWords = listOf("Specified", "Partial", "Implemented", "Exploratory", "Historical", "Living")

    private val chapterDirPrefixes = listOf("10-", "20-", "30-", "40-", "50-")

    fun specMarkdownFiles(specRoot: File): List<File> {
        if (!specRoot.exists()) return emptyList()
        return specRoot.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .toList()
    }

    // --- 1. Package-pointer resolution --------------------------------------------------

    fun checkPackagePointers(specRoot: File, kernelCellRoot: File): List<Finding> {
        val findings = mutableListOf<Finding>()
        specMarkdownFiles(specRoot).forEach { file ->
            val relative = file.relativeTo(specRoot).path
            val reportedPerFile = mutableSetOf<String>()
            backtickSpan.findAll(file.readText()).forEach { span ->
                packagePointer.findAll(span.groupValues[1]).forEach { m ->
                    val pkgDotted = m.groupValues[1].removeSuffix(".")
                    val pkgPath = pkgDotted.replace('.', '/')
                    val dir = File(kernelCellRoot, pkgPath)
                    if (!dir.isDirectory && reportedPerFile.add(pkgDotted)) {
                        findings += Finding(
                            Severity.FATAL,
                            "Unresolved package pointer: `${m.value}` in $relative — no directory " +
                                "kernel/src/main/kotlin/civictech/cell/$pkgPath/",
                        )
                    }
                }
            }
        }
        return findings
    }

    // --- 2. Requirement-id density ---------------------------------------------------------

    /** Ids-per-chapter under `doc/spec/{10,20,30,40,50}-*` (the normative chapter dirs). */
    fun chapterIdDensity(specRoot: File): List<ChapterDensity> {
        val chapterDirs = specRoot.listFiles { f -> f.isDirectory && chapterDirPrefixes.any { f.name.startsWith(it) } }
            ?.sortedBy { it.name }
            ?: emptyList()
        return chapterDirs.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .sortedBy { it.path }
                .map { file ->
                    val count = idPattern.findAll(file.readText()).map { it.groupValues[1] }.distinct().count()
                    ChapterDensity(file.relativeTo(specRoot).path, count)
                }
        }
    }

    fun densityFindings(densities: List<ChapterDensity>): List<Finding> =
        densities.filter { it.idCount == 0 }.map {
            Finding(Severity.NOTE, "Zero-id chapter: ${it.relativePath} carries no [NN-SLUG-nn] requirement ids")
        }

    // --- 3. Status-header vocabulary --------------------------------------------------

    private val excludedBasenames = setOf("README.md", "CONCORDANCE.md")

    fun checkStatusHeaders(specRoot: File): List<Finding> {
        val findings = mutableListOf<Finding>()
        specMarkdownFiles(specRoot).forEach { file ->
            if (file.name in excludedBasenames) return@forEach
            val relative = file.relativeTo(specRoot).path
            val lines = file.readLines()
            val headerEnd = lines.indexOfFirst { chapterHeading.containsMatchIn(it) }.let { if (it == -1) lines.size else it }
            val statusMatches = lines.subList(0, headerEnd).mapNotNull { statusLine.matchEntire(it) }
            when {
                statusMatches.isEmpty() -> findings += Finding(
                    Severity.FATAL,
                    "Missing Status header: $relative carries no `**Status**:` line before its first `## ` section",
                )
                statusMatches.size > 1 -> findings += Finding(
                    Severity.FATAL,
                    "Multiple Status headers: $relative carries ${statusMatches.size} `**Status**:` lines " +
                        "before its first `## ` section (want exactly one)",
                )
                else -> {
                    val remainder = statusMatches.single().groupValues[1].trim()
                    val ok = allowedStatusWords.any { word ->
                        remainder.startsWith(word) &&
                            (remainder.length == word.length || !remainder[word.length].isLetterOrDigit())
                    }
                    if (!ok) {
                        findings += Finding(
                            Severity.FATAL,
                            "Status vocabulary violation: $relative's Status line does not begin with one of " +
                                "${allowedStatusWords.joinToString("|")} — found: \"$remainder\"",
                        )
                    }
                }
            }
        }
        return findings
    }
}

/**
 * CLI entry point invoked by the `:concord:docLints` Gradle task (a
 * [org.gradle.api.tasks.JavaExec], not a custom task type).
 *
 * Args: `<specRoot> <kernelCellRoot> <fatal:true|false>`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) {
        "usage: DocLints <specRoot> <kernelCellRoot> <fatal:true|false>"
    }
    val (specRootArg, kernelCellRootArg, fatalArg) = args
    val fatalMode = fatalArg.toBooleanStrict()

    val specRoot = File(specRootArg)
    val kernelCellRoot = File(kernelCellRootArg)

    val packageFindings = DocLints.checkPackagePointers(specRoot, kernelCellRoot)
    val densities = DocLints.chapterIdDensity(specRoot)
    val densityNotes = DocLints.densityFindings(densities)
    val statusFindings = DocLints.checkStatusHeaders(specRoot)

    println("Doc lints: ${DocLints.specMarkdownFiles(specRoot).size} markdown file(s) scanned under $specRootArg")
    println("-- 1. Package-pointer resolution: ${packageFindings.size} unresolved reference(s)")
    packageFindings.forEach { println("   [${it.severity}] ${it.message}") }
    println(
        "-- 2. Requirement-id density: ${densities.size} chapter(s) under doc/spec/{10,20,30,40,50}-*, " +
            "${densityNotes.size} carry zero ids",
    )
    densityNotes.forEach { println("   [${it.severity}] ${it.message}") }
    println("-- 3. Status-header vocabulary: ${statusFindings.size} violation(s)")
    statusFindings.forEach { println("   [${it.severity}] ${it.message}") }

    val fatal = (packageFindings + statusFindings).filter { it.severity == Severity.FATAL }
    println("${fatal.size} fatal finding(s), ${densityNotes.size} density note(s).")

    if (fatal.isNotEmpty() && fatalMode) {
        println("Fatal mode: failing build on ${fatal.size} fatal doc-lint finding(s).")
        exitProcess(1)
    }
}
