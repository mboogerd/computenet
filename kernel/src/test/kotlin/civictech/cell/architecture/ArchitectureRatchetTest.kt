package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T10-C: a **ratchet**, not a layering. `90/91` gap `G-63` records that the
 * layering `02-design-principles.md` claims ("kernel (logic) -> host (queues,
 * colors) -> distribution (proxies, replication)") does not physically exist:
 * all 20 non-leaf `civictech.cell.*` packages form one strongly-connected
 * component, and several of the cycles are mutually recursive ADTs that a
 * redesign would not remove. Collapsing the SCC to a DAG is out of scope here
 * (deferred to `95-research-plan.md`); what this test buys instead is turning
 * an unbounded problem into a bounded one — pin the *current* edge set and
 * fail the build the moment a new cross-package edge appears, so growth in
 * the entanglement is a conscious, reviewed decision rather than an accident
 * nobody notices.
 *
 * An edge `from -> to` exists when code in `civictech.cell.<from>` imports or
 * fully qualifies a declaration from `civictech.cell.<to>` (`from`/`to` = the
 * first path segment after `cell`; root-level files under `civictech.cell`
 * itself count as package `cell`). Intra-package references are not edges.
 *
 * The baseline lives at `kernel/src/test/resources/architecture/package-edges.txt`
 * (one sorted `from -> to` per line, generated from the current tree). Two
 * asserted directions:
 *  - a **new** edge (in code, not in the baseline) fails the build — either
 *    revert the change, or add the edge to the baseline in the same PR with
 *    a header entry citing why;
 *  - a **stale** baseline edge (in the baseline, no longer in code) is
 *    warn-only: printed, not failed. This test does not rewrite the baseline
 *    itself — delete the stale line by hand so the ratchet only ever
 *    tightens, never silently loosens by drifting out of sync in the
 *    generous direction.
 */
class ArchitectureRatchetTest {

    data class Edge(val from: String, val to: String) {
        override fun toString() = "$from -> $to"
    }

    private val packageImport = Regex("""(?m)^\s*import\s+civictech\.cell\.([\w.]+)""")
    private val packageDirective = Regex("""(?m)^[\t ]*(?:package|import)\b[^\r\n]*""")
    private val qualifiedPackageReference = Regex(
        """\bcivictech\s*\.\s*cell\s*\.\s*([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)""",
    )
    private val whitespace = Regex("""\s+""")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /** Package label for a file living at `.../civictech/cell/<dirParts>/File.kt`. */
    private fun packageOf(file: File, cellRoot: File): String {
        val relativeDir = file.parentFile.relativeTo(cellRoot).path
        return if (relativeDir.isEmpty()) "cell" else relativeDir.substringBefore(File.separatorChar)
    }

    /** Package label for a `civictech.cell.<rest>` target; root declarations map to `cell`. */
    private fun targetPackageOf(referenceRest: String, packageLabels: Set<String>): String =
        referenceRest.substringBefore('.').takeIf { it in packageLabels } ?: "cell"

    /**
     * Retain Kotlin code while blanking comments, character literals and string
     * contents. Code inside `${'$'}{...}` string templates is retained: it is an
     * executable reference even though the surrounding literal is not. Newlines
     * stay in place so declaration-line filtering cannot join unrelated tokens.
     */
    private fun codeOnly(source: String): String {
        val code = CharArray(source.length) { index -> if (source[index] == '\n') '\n' else ' ' }

        fun scanCode(start: Int, stopAtTemplateEnd: Boolean): Int {
            var index = start
            var nestedBraces = 0

            fun skipBlockComment(commentStart: Int): Int {
                var cursor = commentStart + 2
                var depth = 1
                while (cursor < source.length && depth > 0) {
                    when {
                        source.startsWith("/*", cursor) -> {
                            depth++
                            cursor += 2
                        }
                        source.startsWith("*/", cursor) -> {
                            depth--
                            cursor += 2
                        }
                        else -> cursor++
                    }
                }
                return cursor
            }

            fun skipCharacter(characterStart: Int): Int {
                var cursor = characterStart + 1
                while (cursor < source.length) {
                    when (source[cursor]) {
                        '\\' -> cursor = (cursor + 2).coerceAtMost(source.length)
                        '\'' -> return cursor + 1
                        else -> cursor++
                    }
                }
                return cursor
            }

            fun skipString(stringStart: Int, raw: Boolean): Int {
                var cursor = stringStart + if (raw) 3 else 1
                while (cursor < source.length) {
                    when {
                        source.startsWith("${'$'}{", cursor) -> cursor = scanCode(cursor + 2, true)
                        raw && source.startsWith("\"\"\"", cursor) -> return cursor + 3
                        !raw && source[cursor] == '\\' -> cursor = (cursor + 2).coerceAtMost(source.length)
                        !raw && source[cursor] == '"' -> return cursor + 1
                        else -> cursor++
                    }
                }
                return cursor
            }

            while (index < source.length) {
                when {
                    source.startsWith("//", index) -> {
                        val newline = source.indexOf('\n', index + 2)
                        index = if (newline == -1) source.length else newline
                    }
                    source.startsWith("/*", index) -> index = skipBlockComment(index)
                    source.startsWith("\"\"\"", index) -> index = skipString(index, raw = true)
                    source[index] == '"' -> index = skipString(index, raw = false)
                    source[index] == '\'' -> index = skipCharacter(index)
                    stopAtTemplateEnd && source[index] == '}' && nestedBraces == 0 -> return index + 1
                    else -> {
                        if (stopAtTemplateEnd) {
                            when (source[index]) {
                                '{' -> nestedBraces++
                                '}' -> nestedBraces--
                            }
                        }
                        code[index] = source[index]
                        index++
                    }
                }
            }
            return index
        }

        scanCode(0, stopAtTemplateEnd = false)
        return code.concatToString()
    }

    private fun targetPackagesIn(source: String, packageLabels: Set<String>): Set<String> {
        val code = codeOnly(source)
        val imports = packageImport.findAll(code)
            .map { targetPackageOf(it.groupValues[1], packageLabels) }
        val codeWithoutDirectives = packageDirective.replace(code) { " ".repeat(it.value.length) }
        val qualified = qualifiedPackageReference.findAll(codeWithoutDirectives)
            .map { match -> targetPackageOf(match.groupValues[1].replace(whitespace, ""), packageLabels) }
        return (imports + qualified).toSet()
    }

    fun scanKernelPackageEdges(kernelMainRoot: File): Set<Edge> {
        val cellRoot = File(kernelMainRoot, "civictech/cell")
        val packageLabels = cellRoot.listFiles { file -> file.isDirectory }.orEmpty()
            .mapTo(mutableSetOf()) { it.name }
        val edges = mutableSetOf<Edge>()
        cellRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val fromPackage = packageOf(file, cellRoot)
            targetPackagesIn(file.readText(), packageLabels).forEach { toPackage ->
                if (toPackage != fromPackage) edges += Edge(fromPackage, toPackage)
            }
        }
        return edges
    }

    private fun parseBaseline(resource: File): Set<Edge> =
        resource.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (from, to) = line.split(" -> ").also {
                    require(it.size == 2) { "Malformed baseline line: $line" }
                }
                Edge(from, to)
            }
            .toSet()

    @Test
    fun `kernel package edges do not exceed the checked-in baseline`() {
        val root = repoRoot()
        val kernelMainRoot = File(root, "kernel/src/main/kotlin")
        val baselineFile = File(root, "kernel/src/test/resources/architecture/package-edges.txt")
        assertTrue(baselineFile.isFile) { "Missing baseline resource: ${baselineFile.path}" }

        val actual = scanKernelPackageEdges(kernelMainRoot)
        val baseline = parseBaseline(baselineFile)

        // Non-vacuity: a ratchet that silently scans nothing (moved sources, a
        // changed test working directory) would pass forever while enforcing
        // nothing. The real tree has ~100 edges; any collapse toward zero means
        // the scan broke, not that the kernel was untangled.
        assertTrue(actual.size > baseline.size / 2) {
            "scanned only ${actual.size} package edges under ${kernelMainRoot.path} against a " +
                "${baseline.size}-edge baseline — the scan is broken (wrong root?), not the ratchet"
        }

        val newEdges = (actual - baseline).sortedBy { it.toString() }
        val staleEdges = (baseline - actual).sortedBy { it.toString() }

        if (staleEdges.isNotEmpty()) {
            println(
                "ArchitectureRatchetTest: ${staleEdges.size} baseline edge(s) no longer present in code " +
                    "(warn-only — the ratchet only tightens, so delete these lines from " +
                    "kernel/src/test/resources/architecture/package-edges.txt by hand):\n" +
                    staleEdges.joinToString("\n") { "  $it" },
            )
        }

        assertTrue(newEdges.isEmpty()) {
            "new cross-package dependency — either revert it or consciously add it to the baseline " +
                "in the same PR with a header entry citing why " +
                "(kernel/src/test/resources/architecture/package-edges.txt):\n" +
                newEdges.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `qualified-reference classifier counts code and templates but not declarations comments or literal text`() {
        val packages = setOf("data", "evolve", "partition", "replication")
        assertEquals(
            setOf("partition"),
            targetPackagesIn("val type = civictech.cell.partition.RoutedCommand::class", packages),
        )
        assertEquals(
            setOf("replication"),
            targetPackagesIn("val type = civictech\n  .cell\n  .replication\n  .Replication::class", packages),
        )
        assertEquals(
            setOf("evolve"),
            targetPackagesIn("val text = \"effectful=${'$'}{civictech.cell.evolve.Effectful::class}\"", packages),
        )
        assertEquals(
            setOf("evolve"),
            targetPackagesIn("val text = \"\"\"effectful=${'$'}{civictech.cell.evolve.Effectful::class}\"\"\"", packages),
        )
        assertEquals(setOf("data"), targetPackagesIn("import civictech.cell.data.Replicable", packages))
        assertEquals(setOf("cell"), targetPackagesIn("civictech.cell.ReplayProvenance.get()", packages))

        val ignored = listOf(
            "package civictech.cell.partition",
            "// civictech.cell.partition.RoutedCommand",
            "/** civictech.cell.partition.RoutedCommand */",
            "/* outer /* civictech.cell.partition.RoutedCommand */ still comment */",
            "val text = \"civictech.cell.partition.RoutedCommand\"",
            "val text = \"\"\"civictech.cell.partition.RoutedCommand\"\"\"",
            "val text = \"${'$'}civictech.cell.partition.RoutedCommand\"",
        )
        ignored.forEach { source -> assertEquals(emptySet<String>(), targetPackagesIn(source, packages), source) }
    }
}
