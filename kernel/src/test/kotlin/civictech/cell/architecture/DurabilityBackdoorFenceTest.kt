package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `[KBLK-03]`/`[KBLK-04]`: there is no durability back door. The ONLY way to
 * weaken a cell's durability is the [civictech.cell.durability.Journal]
 * instance `journalFor` (or the whole-host `journal` convenience) returns for
 * it, and the only "no durability" spelling is `journalFor(cellRef) == null`
 * ([ManagedHost] KDoc, `computenet-t6b.2-D3`'s
 * [ManagedHost.durabilityAccounting] rationale). Nothing may let an
 * environment variable, a system property, or a host constructor flag relax
 * (or otherwise second-guess) that per-cell decision — exactly the footgun
 * `computenet-t6b.2`'s design section warns against: a kernel that counts is
 * safe; a kernel with a second knob that silently overrides the [Journal]
 * instance is not.
 *
 * Half 1 scans [Journal]-implementing sources (everything under
 * `civictech.cell.durability`) plus the three call sites named in the ticket
 * (`ManagedHost.kt`, `HostDurability.kt`, `KeyedCells.kt`) for
 * `System.getenv`, `System.getProperty`, or `Runtime.getRuntime` — zero hits,
 * no allowlist (unlike [ExtractionFenceTest], a durability back door is never
 * a legitimate exception to grow). Half 2 asserts [ManagedHost]'s constructor
 * has no parameter whose name contains `durab`, `sync`, `wal` or `fsync` —
 * the cheapest durable form of "no host-level durability flag exists at all",
 * catching a second mechanism even if it is wired through in-memory rather
 * than through the environment.
 *
 * Mutation for the reviewer (per the ticket): adding
 * `private val fastMode = System.getProperty("cn.wal") != null` to
 * [ManagedHost], or a constructor parameter
 * `durabilityRelaxed: Boolean = false`, must each fail this test.
 */
class DurabilityBackdoorFenceTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private val forbiddenCalls = listOf("System.getenv", "System.getProperty", "Runtime.getRuntime")

    @Test
    fun `no durability source reads an environment variable, system property, or the runtime`() {
        val root = repoRoot()

        val durabilityDir = File(root, "kernel/src/main/kotlin/civictech/cell/durability")
        assertTrue(durabilityDir.isDirectory) { "Missing directory: ${durabilityDir.path}" }
        val durabilityFiles = durabilityDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(durabilityFiles.isNotEmpty()) {
            "no .kt files found under ${durabilityDir.path} — the scan is broken, not the fence"
        }

        val namedFiles = listOf(
            File(root, "kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt"),
            File(root, "kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt"),
            File(root, "kernel/src/main/kotlin/civictech/cell/host/KeyedCells.kt"),
        )
        namedFiles.forEach { f -> assertTrue(f.isFile) { "Missing source file: ${f.path}" } }

        val scanned = durabilityFiles + namedFiles
        val offenders = scanned.flatMap { file ->
            file.readLines().mapIndexedNotNull { idx, line ->
                val hit = forbiddenCalls.firstOrNull { line.contains(it) }
                if (hit != null) "${file.path}:${idx + 1}: $hit" else null
            }
        }
        assertTrue(offenders.isEmpty()) {
            "durability back door: ${offenders} — the only way to weaken a cell's durability is the " +
                "Journal instance journalFor returns; journalFor(cellRef) == null is the only " +
                "\"no durability\" spelling (KBLK-03/KBLK-04)"
        }
    }

    /**
     * Source-textual, not reflective: the JVM erases Kotlin primary-constructor
     * parameter names unless the module compiles with `-java-parameters` (it
     * does not — `kernel/build.gradle.kts` sets no such flag), and
     * `kotlin-reflect` is not on this module's classpath (the same reason
     * [ExtractionFenceTest] and its siblings scan source text rather than
     * reflect). This walks `ManagedHost.kt`'s primary constructor parameter
     * list textually, the same technique the rest of this package uses.
     */
    @Test
    fun `ManagedHost's constructor has no parameter whose name suggests a host-level durability flag`() {
        val root = repoRoot()
        val file = File(root, "kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt")
        assertTrue(file.isFile) { "Missing source file: ${file.path}" }

        val lines = file.readLines()
        val startIdx = lines.indexOfFirst { it.trimStart().startsWith("open class ManagedHost(") }
        assertTrue(startIdx >= 0) {
            "could not find 'open class ManagedHost(' — the scan is broken, not the fence"
        }
        val endIdx = lines.indexOfFirst { it.trim() == ") : Host {" }
        assertTrue(endIdx > startIdx) {
            "could not find the primary constructor's closing ') : Host {' — the scan is broken, not the fence"
        }

        val body = stripComments(lines.subList(startIdx, endIdx + 1))
        val paramNamePattern = Regex("""^\s*(?:override\s+)?(?:private\s+)?(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""")
        val names = body.mapNotNull { line -> paramNamePattern.find(line)?.groupValues?.get(1) }
            .filterNot { it == "ManagedHost" } // the class-declaration line itself matches loosely; excluded explicitly

        assertTrue(names.isNotEmpty()) {
            "found no constructor parameter names between lines $startIdx and $endIdx of ${file.path} — " +
                "the scan is broken, not the fence"
        }
        // sanity: known, legitimate parameters must be present, proving the scan reached them
        assertTrue(names.contains("journalFor") && names.contains("dispatchBatch")) {
            "expected scan to find known parameters journalFor and dispatchBatch, found: $names — " +
                "the scan is broken, not the fence"
        }

        val suspectSubstrings = listOf("durab", "sync", "wal", "fsync")
        val offending = names.filter { name -> suspectSubstrings.any { name.contains(it, ignoreCase = true) } }
        assertTrue(offending.isEmpty()) {
            "ManagedHost constructor parameter(s) look like a host-level durability flag: $offending — " +
                "the only durability control is the Journal instance journalFor(cellRef) returns (KBLK-03)"
        }
    }

    /** Drop KDoc/block comments and `//` tails, keeping only lines with code left on them. */
    private fun stripComments(lines: List<String>): List<String> {
        var inBlock = false
        val out = mutableListOf<String>()
        for (raw in lines) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) continue
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
            if (line.isNotBlank()) out += line
        }
        return out
    }
}
