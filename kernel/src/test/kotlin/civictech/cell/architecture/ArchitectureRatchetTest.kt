package civictech.cell.architecture

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
 * An edge `from -> to` exists when a file in `civictech.cell.<from>` imports
 * a declaration from `civictech.cell.<to>` (`from`/`to` = the first path
 * segment after `cell`; root-level files under `civictech.cell` itself count
 * as package `cell`). Intra-package imports are not edges.
 *
 * The baseline lives at `kernel/src/test/resources/architecture/package-edges.txt`
 * (one sorted `from -> to` per line, generated from the current tree). Two
 * asserted directions:
 *  - a **new** edge (in code, not in the baseline) fails the build — either
 *    revert the change, or add the edge to the baseline in the same PR,
 *    citing why in the PR description;
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

    private val packageImport = Regex("""^\s*import\s+civictech\.cell\.([\w.]+)""")

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

    /** Package label for an import target `civictech.cell.<rest>`; bare root members map to `cell`. */
    private fun targetPackageOf(importRest: String): String {
        val segments = importRest.split('.')
        return if (segments.size == 1) "cell" else segments[0]
    }

    fun scanKernelPackageEdges(kernelMainRoot: File): Set<Edge> {
        val cellRoot = File(kernelMainRoot, "civictech/cell")
        val edges = mutableSetOf<Edge>()
        cellRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val fromPackage = packageOf(file, cellRoot)
            file.forEachLine { line ->
                val match = packageImport.find(line) ?: return@forEachLine
                val toPackage = targetPackageOf(match.groupValues[1])
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
                "in the same PR, citing why (kernel/src/test/resources/architecture/package-edges.txt):\n" +
                newEdges.joinToString("\n") { "  $it" }
        }
    }
}
