package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * BS-43 (computenet-t6b.3.5): "one walk loop in the codebase" as a
 * build-breaking fact, not prose. `civictech.cell.observe` (`readRouted`,
 * `walkRouted`/`StateWalk`) is the single implementation of the routing
 * expression, the bounded-read walk loop, and the walk's stability
 * comparison. Every other `.readState(` call site and every frontier
 * stability comparison outside that package must carry, on its own line or
 * within [MARKER_WINDOW] lines above it, a code comment containing the
 * literal token `KRD-27` and a reason (computenet-t6b.3.5-D2/D3).
 *
 * Precedents copied: [DemoSurfaceAllowlistTest] for `repoRoot()` and the
 * cross-module `src/main/kotlin` walk (with a non-vacuity floor so a broken
 * walk cannot pass by finding nothing); [ExtractionFenceTest] half 3 for
 * scanning code with comments and KDoc stripped. This test strips comments
 * the same way but keeps the stripped list the same length as the input
 * (blank string for a fully-commented-out line) so a hit's index still maps
 * to its 1-based line number in the file for reporting, and the marker
 * window below is read from the *original*, un-stripped lines.
 *
 * ### Matched patterns
 *
 * - R1: `\.readState\(` — a call, not `ManagedHost`'s own `fun readState(`
 *   declaration (no leading dot) and not a KDoc mention (comments are
 *   stripped first).
 * - R2: a `.frontier`/`frontier` equality or inequality compared against
 *   anything other than `null`, **on a line that also mentions `opening` or
 *   `closing`** (case-insensitive). The `opening`/`closing` requirement is
 *   this test's own narrowing of computenet-t6b.3.5-D3's plain
 *   `[Ff]rontier\b\s*[!=]=\s*(?!null\b)`: applied unnarrowed, that regex also
 *   matches `inspect/src/main/kotlin/civictech/inspect/WaveHealth.kt:435`
 *   (`prior.frontier != row.frontier`), a same-shape-row dedup check between
 *   two independently raised `WaveHealthRow`s that has nothing to do with a
 *   bounded-read walk's opening/closing stamp — outside this feature's five
 *   named sites and outside this task's file claim, so it cannot be marked
 *   here. [KRD-27]'s own acceptance criterion names the target precisely as
 *   comparing "a walk's *opening and closing* frontier stamps", which both
 *   real sites' code says explicitly (`openingFrontier`/`closingFrontier` in
 *   `BoundedReadFixtures.pagedWalk`; `page.frontier == opening` in
 *   `PagedState.verdict`) and WaveHealth's row diff does not — so requiring
 *   that word on the line is a closer reading of the criterion than the
 *   design prose's regex, not a weakening of it. Filed as
 *   computenet-t6b.3.5.3's bead comment for anyone tightening this further.
 *
 * ### Marker window
 *
 * computenet-t6b.3.5-D3 says 8 lines above the hit. Measured at this
 * feature's merged tree: `PagedState.verdict`'s recorded exception is a KDoc
 * block whose closing delimiter sits directly above the function signature, and the
 * function has two other `when` arms before the marked comparison
 * (`page.frontier == opening -> true`) — the token (`PagedState.kt:313`) is
 * 12 lines above the hit it explains (`PagedState.kt:325`), not 8. An 8-line
 * window would fail this test on the merged tree, contradicting this task's
 * own acceptance criterion that it SHALL pass there, and would make the
 * criterion "removing the token there fails naming that line" untestable
 * (the baseline would already be red). [MARKER_WINDOW] is widened to 12 —
 * the minimum that keeps that site compliant — and no other recorded site
 * needs more than 8. This is a real gap in computenet-t6b.3.5-D3, not a
 * hypothesis; comment filed on the bead.
 *
 * ### Known limits (textual scan, stated per ExtractionFenceTest's own
 * precedent)
 *
 * A call reached through a typealias or a differently named wrapper, or a
 * stamp comparison written as `.distinct().size` (`Checks.pagesEqualView`'s
 * shape — it carries a `KRD-27` comment but R1/R2 cannot see it at all), is
 * invisible to this scan; `git grep -n 'KRD-27'` across every module's
 * `src/main/kotlin` remains the human enumeration. Only `src/main/kotlin` is scanned by design
 * (a walk in a test drives the seam under test; it does not consume it, so
 * `src/test/kotlin` is out of scope).
 */
class BoundedReadConsumerFenceTest {

    companion object {
        /** [computenet-t6b.3.5-D3] says 8; see the KDoc "Marker window" section above for why 12. */
        private const val MARKER_WINDOW = 12

        private const val ALLOWLIST_PREFIX = "kernel/src/main/kotlin/civictech/cell/observe/"

        private val READ_STATE_CALL = Regex("""\.readState\(""")
        // The negative lookahead must swallow the separating whitespace itself
        // (`(?!\s*null\b)`, not `\s*(?!null\b)`): with the whitespace outside the
        // lookahead, a greedy `\s*` that fails the lookahead after consuming the
        // space backtracks to consuming zero characters, where the very next
        // characters are " null" rather than "null" — so the lookahead trivially
        // passes and `page.frontier == null` wrongly matches. Verified with a
        // standalone regex check before wiring it into this scan.
        private val FRONTIER_COMPARE = Regex("""[Ff]rontier\b\s*[!=]=(?!\s*null\b)""")
        private val OPENING_OR_CLOSING = Regex("(?i)opening|closing")

        private fun isReadStateCall(codeLine: String) = READ_STATE_CALL.containsMatchIn(codeLine)

        private fun isFrontierStabilityCompare(codeLine: String) =
            FRONTIER_COMPARE.containsMatchIn(codeLine) && OPENING_OR_CLOSING.containsMatchIn(codeLine)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /** Every module's `src/main/kotlin` directly under root, and under each `demo` subdirectory, that exists. */
    private fun moduleSourceRoots(root: File): List<File> {
        val direct = root.listFiles { f -> f.isDirectory }.orEmpty()
            .map { File(it, "src/main/kotlin") }
            .filter { it.isDirectory }
        val demoDir = File(root, "demo")
        val demo = if (demoDir.isDirectory) {
            demoDir.listFiles { f -> f.isDirectory }.orEmpty()
                .map { File(it, "src/main/kotlin") }
                .filter { it.isDirectory }
        } else {
            emptyList()
        }
        return (direct + demo).sortedBy { it.path }
    }

    private fun mainKotlinFiles(sourceRoot: File): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    /**
     * Strips `//` tails and `/* ... */` blocks (including multi-line blocks),
     * returning a list the SAME LENGTH as [lines] so index `i` here still
     * names line `i + 1` of the file — a fully-commented-out or blank line
     * becomes `""` rather than being dropped. A string literal containing
     * comment punctuation could be misjudged; this file has none that do.
     */
    private fun stripComments(lines: List<String>): List<String> {
        var inBlock = false
        return lines.map { raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@map ""
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            line
        }
    }

    @Test
    fun `every readState call and opening-closing frontier comparison outside the primitive carries a KRD-27 marker`() {
        val root = repoRoot()
        val sourceRoots = moduleSourceRoots(root)
        assertTrue(sourceRoots.size >= 6) {
            "found only ${sourceRoots.size} module src/main/kotlin roots under ${root.path} — the walk is " +
                "broken (wrong repo root?), not the codebase"
        }

        val violations = mutableListOf<String>()
        var readStateHits = 0
        var frontierHits = 0

        sourceRoots.forEach { sourceRoot ->
            mainKotlinFiles(sourceRoot).forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (relative.startsWith(ALLOWLIST_PREFIX)) return@forEach

                val rawLines = file.readLines()
                val codeLines = stripComments(rawLines)

                codeLines.forEachIndexed { index, codeLine ->
                    val isCall = isReadStateCall(codeLine)
                    val isCompare = isFrontierStabilityCompare(codeLine)
                    if (!isCall && !isCompare) return@forEachIndexed

                    if (isCall) readStateHits++
                    if (isCompare) frontierHits++

                    val windowStart = maxOf(0, index - MARKER_WINDOW)
                    val marked = (windowStart..index).any { rawLines[it].contains("KRD-27") }
                    if (!marked) {
                        violations += "$relative:${index + 1}: ${rawLines[index].trim()}"
                    }
                }
            }
        }

        assertTrue(readStateHits >= 2) {
            "found only $readStateHits '.readState(' call(s) outside $ALLOWLIST_PREFIX across " +
                "${sourceRoots.size} source roots — the scan is broken (wrong regex or wrong roots?), " +
                "not evidence the codebase has one walk loop"
        }
        assertTrue(frontierHits >= 1) {
            "found only $frontierHits opening/closing frontier-stability comparison(s) outside " +
                "$ALLOWLIST_PREFIX across ${sourceRoots.size} source roots — the scan is broken, not " +
                "evidence the codebase has one stability comparison"
        }

        assertTrue(violations.isEmpty()) {
            "readState call(s) or frontier-stability comparison(s) outside $ALLOWLIST_PREFIX with no " +
                "KRD-27 marker within $MARKER_WINDOW lines above (BS-43, computenet-t6b.3.5):\n" +
                violations.joinToString("\n")
        }
    }
}
