package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Demo main sources use `observation {}` as their app-edge read API. This
 * ratchet keeps the old single-purpose observation and materialization seams
 * from returning to demo code as migrations land.
 *
 * The scan strips comments before matching. Unlike the raw-link ratchet, this
 * has no allowlist. The one remaining journal-addressed cell is carved out by
 * both its path and its exact construction: it is not an app-edge read.
 */
class DemoObservationRatchetTest {

    companion object {
        private val observationCall = Regex("""\.(observe|observeAll|observeAligned)\s*[({]""")
        private val observeCell = Regex("""\bObserveCell\s*[<(]""")
        private val hubCell = Regex("""\b(Set|Map)HubCell\s*[<(]""")
        // The deliberate journal/replay address is the sole carve-out; see computenet-t9tms.
        private val journaledConstruction = Regex("""ObserveCell\(MetaView\(\), ref = REF\)""")
        private const val journaledPath =
            "demo/deliberate/src/main/kotlin/civictech/deliberate/Durability.kt"

        /**
         * Count forbidden observation seams outside comments. The deliberate
         * journal cell's one exact construction is excluded only at its
         * declared path; a second or changed construction is still counted.
         */
        fun countObservations(source: String, relativePath: String? = null): Map<String, Int> {
            val stripped = DemoBypassRatchetTest.stripComments(source)
            val counts = mutableMapOf<String, Int>()

            observationCall.findAll(stripped).forEach { match ->
                val kind = match.groupValues[1]
                counts[".$kind"] = (counts[".$kind"] ?: 0) + 1
            }

            val observeCellCount = observeCell.findAll(stripped).count()
            val carvedOut = if (relativePath == journaledPath) {
                journaledConstruction.findAll(stripped).take(1).count()
            } else {
                0
            }
            if (observeCellCount > carvedOut) {
                counts["ObserveCell"] = observeCellCount - carvedOut
            }

            hubCell.findAll(stripped).forEach { match ->
                val kind = "${match.groupValues[1]}HubCell"
                counts[kind] = (counts[kind] ?: 0) + 1
            }
            return counts
        }
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

    @Test
    fun `demo main sources use observation and contain no forbidden observation seams`() {
        val root = repoRoot()
        val demoFiles = demoMainKotlinFiles(root)
        assertTrue(demoFiles.size >= 50) {
            "scanned only ${demoFiles.size} demo main sources under ${root.path}/demo — the walk is " +
                "broken (wrong repo root?), not the demo tree"
        }

        val scannedByRelativePath = demoFiles.associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val offendersByPath = scannedByRelativePath.mapValues { (path, file) ->
            countObservations(file.readText(), path)
        }.filterValues { it.isNotEmpty() }

        val violations = offendersByPath.map { (path, counts) ->
            "$path: ${counts.toSortedMap().entries.joinToString(", ") { (pattern, count) -> "$pattern=$count" }}"
        }
        assertTrue(violations.isEmpty()) {
            "demo-observation ratchet violated (patterns " +
                "\\.(observe|observeAll|observeAligned)\\s*[({], " +
                "\\bObserveCell\\s*[<(], \\b(Set|Map)HubCell\\s*[<(]):\n" +
                violations.sorted().joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `classifier counts code calls and constructions but not comments or observation`() {
        assertEquals(mapOf(".observe" to 1), countObservations("host.observe(ref, View.set<String>())"))
        assertEquals(mapOf(".observeAll" to 1), countObservations("host.observeAll { }"))
        assertEquals(mapOf(".observeAligned" to 1), countObservations("host.observeAligned { }"))
        assertEquals(mapOf("ObserveCell" to 1), countObservations("ObserveCell(MetaView(), ref = OTHER)"))
        assertEquals(mapOf("SetHubCell" to 1, "MapHubCell" to 1), countObservations("SetHubCell<Int>(); MapHubCell<String>()"))
        assertEquals(emptyMap<String, Int>(), countObservations("host.observation { }"))
        assertEquals(
            emptyMap<String, Int>(),
            countObservations(
                "// host.observe(ref, View.set<String>())\n" +
                    "/* ObserveCell(MetaView(), ref = REF) */\n" +
                    "host.observation { }",
            ),
        )
    }

    @Test
    fun `only the exact deliberate journal construction is carved out`() {
        val exact = "val cell = ObserveCell(MetaView(), ref = REF)"
        assertEquals(emptyMap<String, Int>(), countObservations(exact, journaledPath))
        assertEquals(
            mapOf("ObserveCell" to 1),
            countObservations("$exact\nval second = ObserveCell(OtherView(), ref = REF)", journaledPath),
        )
        assertEquals(
            mapOf("ObserveCell" to 1),
            countObservations(exact, "demo/deliberate/src/main/kotlin/civictech/deliberate/Other.kt"),
        )
    }
}
