package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T10-B: with `:kernel` at 323 public / 20 internal declarations
 * (`doc/remediation/tickets/T10-architecture-ratchets.md` problem statement),
 * nothing physically stops a demo from reaching past the intended
 * application surface into scheduling/protocol/proxy internals. Demos are
 * meant to sit on the outward-facing vocabulary — `civictech.cell` root
 * types, `.host`, `.port`, `.graph`, `.data*`, `.observe`, `.link`, `.wire`
 * (the bridge cells `Peering` et al.), plus `.consistency` (`GlitchFreeCell`,
 * used by `:demo:exchange`), `.control` (`Magnitude`, used by `:demo:agora`),
 * and `.durability` (`FileJournal`, used by `:demo:agora`) — plus
 * `civictech.testkit`.
 *
 * The allowlist below is seeded from the actual current state of every
 * every `.kt` file under each demo module's `src/main` (verified by running this test); it is not a
 * design aspiration independent of the code. A new demo reaching into an
 * unlisted package (e.g. `.protocol`, `.proxy`) fails here, naming the file,
 * the import, and this allowlist so the fix is either to route through an
 * already-allowed package or to extend [allowedCellPrefixes] in the same PR.
 */
class DemoSurfaceAllowlistTest {

    companion object {
        /**
         * First segment after `civictech.cell.` that a demo main source may import.
         * Anything imported directly from the bare `civictech.cell` root package
         * (no further segment, e.g. `civictech.cell.CellRef`) is always allowed —
         * that is the root vocabulary the whole model is built on.
         */
        val allowedCellPrefixes = setOf(
            "host",
            "port",
            "graph",
            "data",
            "observe",
            "link",
            "wire",
            "consistency",
            "control",
            "durability",
        )
    }

    private val cellImport = Regex("""^\s*import\s+(civictech\.cell(?:\.[\w*]+)*)""")

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
            .map { File(it, "src/main/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .sortedBy { it.path }
    }

    /** Returns null (in scope, allowed) or the disallowed import path. */
    private fun disallowedImport(importPath: String): Boolean {
        val rest = importPath.removePrefix("civictech.cell.")
        if (rest == importPath) return false // not a civictech.cell import at all — out of scope
        if (!rest.contains('.')) return false // direct root member (e.g. CellRef, onEach, *) — always allowed
        val firstSegment = rest.substringBefore('.')
        return firstSegment !in allowedCellPrefixes
    }

    @Test
    fun `demo main sources only import the allowed civictech-cell surface`() {
        val root = repoRoot()
        val violations = mutableListOf<String>()

        val scanned = demoMainKotlinFiles(root)
        // Non-vacuity: `demoMainKotlinFiles` degrades to an empty list when the
        // demo tree cannot be located, which would make this gate pass forever
        // while checking nothing.
        assertTrue(scanned.size >= 10) {
            "scanned only ${scanned.size} demo main sources under ${root.path}/demo — the walk is " +
                "broken (wrong repo root?), not the demo tree"
        }

        scanned.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val match = cellImport.find(line) ?: return@forEachIndexed
                val importPath = match.groupValues[1]
                if (disallowedImport(importPath)) {
                    violations += "${file.relativeTo(root).path}:${index + 1}: import $importPath"
                }
            }
        }

        assertTrue(violations.isEmpty()) {
            "demo main source imports fall outside the allowed civictech.cell surface " +
                "(allowedCellPrefixes in " +
                "kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt):\n" +
                violations.joinToString("\n")
        }
    }

    // Sanity check on the matcher itself, independent of the real demo tree.
    @Test
    fun `allowlist matcher accepts root members and listed prefixes, rejects others`() {
        assertTrue(!disallowedImport("civictech.cell.CellRef"))
        assertTrue(!disallowedImport("civictech.cell.data.op.UnionSetCell"))
        assertTrue(!disallowedImport("civictech.cell.consistency.GlitchFreeCell"))
        assertTrue(disallowedImport("civictech.cell.protocol.Protocols"))
        assertTrue(disallowedImport("civictech.cell.proxy.Invocation"))
    }
}
