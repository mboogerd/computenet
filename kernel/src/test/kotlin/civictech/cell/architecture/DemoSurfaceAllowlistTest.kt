package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Architecture guardrail (T10-B, widened by the 2026-07-28 audit).
 *
 * Enforces: every application-surface module (each demo leaf, `inspect`)
 * imports only its allowed slice of `civictech.cell`.
 *
 * Amendment policy: adding a module row or tightening a set is always
 * allowed. Widening a set, relaxing, or deleting a rule requires human
 * approval and a note in `doc/architecture-decisions.md`. A failing guardrail
 * is evidence the change is wrong until a human says otherwise — do not edit
 * this file to make a build pass.
 *
 * T10-B's original problem statement: with `:kernel` at 323 public / 20
 * internal declarations, nothing physically stops an out-of-kernel consumer
 * from reaching past the intended application surface into
 * scheduling/protocol/proxy internals. The original gate walked the demo
 * tree only; `:inspect` was created 53 minutes after the gate landed and sat
 * outside it (audit 2026-07-28, finding B1). The walk is now driven by a
 * per-module table so the next consumer module must claim a surface here on
 * the day it is created.
 *
 * Each allowlist is seeded from the actual current state of the module's
 * `src/main` sources (verified by running this test), not a design
 * aspiration. Notes on `inspect`'s two extra prefixes:
 *
 * - `proxy`: forced by kernel API shape — `FanOutlet.tap` takes a `Use<Api>`,
 *   so a payload-agnostic observer must synthesize a dynamic proxy
 *   (`Flow.kt`), and `LocationRegistry.Remote.sink` is declared as
 *   `civictech.cell.proxy.InvocationSink` (`Peers.kt`). Shrinking this to a
 *   kernel-owned observe seam is the tracked end state (audit finding B2).
 * - `partition`: forced by the absence of an Api marker — `ShardCell` is a
 *   plain `Cell` with no generated `@CellBase` `ShardApi` to key on, so the
 *   inspector's fold table can only name the concrete class
 *   (`Observations.kt`, `SET_OUTLETS`). The reference is a bare `Class`
 *   literal compared with `isAssignableFrom`; no `civictech.cell.partition`
 *   behavior is called. Deliberate widening for T20 / guardrail G3 — see
 *   `doc/architecture-decisions.md` "Guardrails › Amended". Shrinks the day
 *   `ShardCell` gains an Api marker interface.
 */
class DemoSurfaceAllowlistTest {

    companion object {
        /**
         * First segment after `civictech.cell.` that each module's main
         * sources may import, keyed by the source root that the segment
         * applies to. Anything imported directly from the bare
         * `civictech.cell` root package (no further segment, e.g.
         * `civictech.cell.CellRef`) is always allowed — that is the root
         * vocabulary the whole model is built on.
         */
        val demoCellPrefixes = setOf(
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

        val inspectCellPrefixes = setOf(
            "host",
            "port",
            "data",
            "observe",
            "link",
            "wire",
            "proxy", // see class KDoc — forced by FanOutlet.tap / Remote.sink shapes
            "partition", // see class KDoc — ShardCell has no Api marker; Class literal only (T20)
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

    private fun mainKotlinFiles(sourceRoot: File): List<File> =
        if (!sourceRoot.isDirectory) emptyList()
        else sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun demoMainKotlinFiles(root: File): List<File> {
        val demoDir = File(root, "demo")
        if (!demoDir.isDirectory) return emptyList()
        return demoDir.listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { mainKotlinFiles(File(it, "src/main/kotlin")) }
    }

    /** True when the import path falls outside the module's allowed surface. */
    private fun disallowedImport(importPath: String, allowedCellPrefixes: Set<String>): Boolean {
        val rest = importPath.removePrefix("civictech.cell.")
        if (rest == importPath) return false // not a civictech.cell import at all — out of scope
        if (!rest.contains('.')) return false // direct root member (e.g. CellRef, onEach, *) — always allowed
        val firstSegment = rest.substringBefore('.')
        return firstSegment !in allowedCellPrefixes
    }

    private fun scan(files: List<File>, allowed: Set<String>, root: File, violations: MutableList<String>) {
        files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val match = cellImport.find(line) ?: return@forEachIndexed
                val importPath = match.groupValues[1]
                if (disallowedImport(importPath, allowed)) {
                    violations += "${file.relativeTo(root).path}:${index + 1}: import $importPath"
                }
            }
        }
    }

    @Test
    fun `application-surface main sources only import their allowed civictech-cell slice`() {
        val root = repoRoot()
        val violations = mutableListOf<String>()

        val demoFiles = demoMainKotlinFiles(root)
        val inspectFiles = mainKotlinFiles(File(root, "inspect/src/main/kotlin"))
        // Non-vacuity: both walks degrade to an empty list when the tree
        // cannot be located, which would make this gate pass forever while
        // checking nothing.
        assertTrue(demoFiles.size >= 10) {
            "scanned only ${demoFiles.size} demo main sources under ${root.path}/demo — the walk is " +
                "broken (wrong repo root?), not the demo tree"
        }
        assertTrue(inspectFiles.size >= 5) {
            "scanned only ${inspectFiles.size} inspect main sources under ${root.path}/inspect — the walk " +
                "is broken (wrong repo root?), not the inspect tree"
        }

        scan(demoFiles, demoCellPrefixes, root, violations)
        scan(inspectFiles, inspectCellPrefixes, root, violations)

        assertTrue(violations.isEmpty()) {
            "application-surface imports fall outside the allowed civictech.cell slice " +
                "(per-module sets in " +
                "kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt):\n" +
                violations.joinToString("\n")
        }
    }

    // Sanity check on the matcher itself, independent of the real trees.
    @Test
    fun `allowlist matcher accepts root members and listed prefixes, rejects others`() {
        assertTrue(!disallowedImport("civictech.cell.CellRef", demoCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.data.op.UnionSetCell", demoCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.consistency.GlitchFreeCell", demoCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.protocol.Protocols", demoCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.proxy.Invocation", demoCellPrefixes))
        // inspect may reach .proxy and .partition (documented above) but not .protocol or .evolve
        assertTrue(!disallowedImport("civictech.cell.proxy.Proxy", inspectCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.partition.ShardCell", inspectCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.partition.ShardCell", demoCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.protocol.Protocols", inspectCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.evolve.Shadow", inspectCellPrefixes))
    }
}
