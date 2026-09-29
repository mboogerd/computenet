package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T10 (fourth ratchet): demo `src/main` sources cannot add a new raw
 * `subscribe`/`streamTo`/`routeTo` call, and the allowlist that names today's
 * offenders can only ever shrink.
 *
 * Why this exists (`doc/integration/2026-09-29/01-programming-model.md`
 * findings F1-F4): the routed/raw wiring paths — `FanOutlet.streamTo`
 * (`kernel/src/main/kotlin/civictech/cell/port/StreamTo.kt`), `FanOutlet.routeTo`
 * (`kernel/src/main/kotlin/civictech/cell/host/RoutedInlet.kt`, itself
 * `streamTo(registry.inlet(..))`) and raw `Subscribe.subscribe(Use)`
 * (`kernel/src/main/kotlin/civictech/cell/port/Subscribe.kt`) — skip
 * `LinkAdmission` (cycle admission, `TopologyIndex` recording, `EdgeClose`)
 * and, as `LinkRole.Observe` links, are excluded from the glitch-free wave
 * frontier; raw `subscribe` also skips on-link multicast catch-up. Migrating
 * today's users onto the routed/glitch-free path is separate work (INT1
 * 1.1); this ratchet only stops the *set of offenders from growing* and
 * forces the allowlist to shrink as each migration lands.
 *
 * Fingerprint: comment-strip (`/* */` then `//`, the technique of
 * `demo/social/src/test/kotlin/civictech/demo/social/ModuleDependencyTest.kt`)
 * each `.kt` file under `demo/<module>/src/main/kotlin`, then count matches
 * of `\.(subscribe|streamTo|routeTo)\s*\(` per file. An `import
 * civictech.cell.port.streamTo` line alone is not a call and is not counted;
 * `.unsubscribe(` does not match (leading `\.`, trailing `\(` anchor the
 * name exactly).
 *
 * Both directions fail (stricter than `ArchitectureRatchetTest`, whose stale
 * direction is warn-only): a scanned file with >= 1 match not on the
 * allowlist fails naming the path and its per-kind counts; an allowlisted
 * path with 0 matches fails naming it as stale; an allowlisted path that is
 * not a file fails naming it. The allowlist
 * (`kernel/src/test/resources/architecture/demo-bypass-allowlist.txt`) may
 * only ever have lines removed, never added — see its own header.
 *
 * Limit: the granularity is the FILE. A listed file that gains additional
 * calls does not fail this test; per-file counts are the natural next
 * ratchet step, not built here. Private journals or structure logs may also
 * warrant fingerprinting alongside these three call kinds in a future step.
 */
class DemoBypassRatchetTest {

    companion object {
        private val bypassCall = Regex("""\.(subscribe|streamTo|routeTo)\s*\(""")
        private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val lineComment = Regex("""//[^\n]*""")

        /** Strip block then line comments, the same order `ModuleDependencyTest` uses. */
        fun stripComments(source: String): String =
            source.replace(blockComment, "").replace(lineComment, "")

        /**
         * Per-kind counts of `.subscribe(`/`.streamTo(`/`.routeTo(` calls in [source],
         * outside comments. Absent keys mean zero; the map is empty when there are no
         * bypass calls at all.
         */
        fun countBypasses(source: String): Map<String, Int> {
            val stripped = stripComments(source)
            val counts = mutableMapOf<String, Int>()
            bypassCall.findAll(stripped).forEach { match ->
                val kind = match.groupValues[1]
                counts[kind] = (counts[kind] ?: 0) + 1
            }
            return counts
        }

        private fun formatCounts(counts: Map<String, Int>): String =
            counts.toSortedMap().entries.joinToString(", ") { (k, v) -> "$k=$v" }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun demoMainKotlinFiles(root: File): List<File> {
        val demoDir = File(root, "demo")
        if (!demoDir.isDirectory) return emptyList()
        return demoDir.listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { moduleDir ->
                val srcRoot = File(moduleDir, "src/main/kotlin")
                if (!srcRoot.isDirectory) emptyList()
                else srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            .sortedBy { it.path }
    }

    private fun parseAllowlist(resource: File): List<String> =
        resource.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `demo main sources add no new raw subscribe streamTo or routeTo call, and the allowlist only shrinks`() {
        val root = repoRoot()
        val allowlistFile = File(root, "kernel/src/test/resources/architecture/demo-bypass-allowlist.txt")
        assertTrue(allowlistFile.isFile) { "Missing allowlist resource: ${allowlistFile.path}" }

        val demoFiles = demoMainKotlinFiles(root)
        // Non-vacuity: a walk that silently finds nothing (moved sources, a changed test
        // working directory) would pass forever while enforcing nothing. 143 files exist
        // today; any collapse toward zero means the scan broke, not that demos vanished.
        assertTrue(demoFiles.size >= 50) {
            "scanned only ${demoFiles.size} demo main sources under ${root.path}/demo — the walk is " +
                "broken (wrong repo root?), not the demo tree"
        }

        val allowlist = parseAllowlist(allowlistFile).toSet()

        val scannedByRelativePath = demoFiles.associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val offendersByPath = scannedByRelativePath.mapValues { (_, file) -> countBypasses(file.readText()) }
            .filterValues { it.isNotEmpty() }

        val violations = mutableListOf<String>()

        // (a) scanned file with >= 1 bypass call not on the allowlist.
        offendersByPath.forEach { (path, counts) ->
            if (path !in allowlist) {
                violations += "unlisted offender $path: ${formatCounts(counts)} — add it to " +
                    "kernel/src/test/resources/architecture/demo-bypass-allowlist.txt, or revert the call"
            }
        }

        // (b) and (c): every allowlisted path must be a file that still has >= 1 bypass call.
        allowlist.forEach { path ->
            val file = File(root, path)
            if (!file.isFile) {
                violations += "allowlist entry $path is not a file — remove the line (shrink-only)"
            } else if (path !in offendersByPath) {
                violations += "allowlist entry $path has no subscribe/streamTo/routeTo call outside " +
                    "comments any more — remove the line (shrink-only)"
            }
        }

        assertTrue(violations.isEmpty()) {
            "demo-bypass ratchet violated (kernel/src/test/kotlin/civictech/cell/architecture/" +
                "DemoBypassRatchetTest.kt; allowlist kernel/src/test/resources/architecture/" +
                "demo-bypass-allowlist.txt):\n" +
                violations.sorted().joinToString("\n") { "  $it" }
        }
    }

    // Sanity checks on the pure classifier, independent of the real tree.
    @Test
    fun `classifier counts code calls, not comments, imports or unsubscribe`() {
        assertEquals(mapOf("streamTo" to 1), countBypasses("outlet.streamTo(sink)"))
        assertEquals(mapOf("subscribe" to 1), countBypasses("host.deadLetterOutlet.subscribe(use)"))
        assertEquals(mapOf("routeTo" to 1), countBypasses("outlet.routeTo(registry.inlet(x))"))
        assertEquals(
            mapOf("streamTo" to 2, "routeTo" to 1),
            countBypasses("a.streamTo(b)\nc.streamTo(d)\ne.routeTo(f)"),
        )

        assertEquals(emptyMap<String, Int>(), countBypasses("// outlet.streamTo(sink)"))
        assertEquals(emptyMap<String, Int>(), countBypasses("/** outlet.streamTo(sink) */"))
        assertEquals(emptyMap<String, Int>(), countBypasses("/*\nmultiline\nouter.subscribe(x)\n*/"))
        assertEquals(emptyMap<String, Int>(), countBypasses("import civictech.cell.port.streamTo"))
        assertEquals(emptyMap<String, Int>(), countBypasses("outlet.unsubscribe(handle)"))
    }
}
