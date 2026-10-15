package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Architecture guardrail (T10-B, widened by the 2026-07-28 audit).
 *
 * Enforces: the `:inspect` application-surface module imports only its
 * allowed slice of `civictech.cell`. Demo modules may import any kernel
 * package; the separate guardrail for demo-to-demo imports is tracked in
 * computenet-yvzfh.
 *
 * This demo policy follows the maintainer answer on computenet-7afo4.5
 * (2026-10-14): "The demo may use any kernel packages. The main limitation
 * should be that it doesn't use other demo packages."
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
 * - `graph`: the WKB2 write plane's construction surface —
 *   `GraphSpec`/`Precheck`/`applyRemote`, used by F2/F12/F3/F6
 *   (`inspect/src/main`'s `Catalogue.kt`, `DraftCompiler.kt`, and F3's
 *   `Draft.kt`/`StagedApplier.kt`). Maintainer decision, computenet-va0c4.2,
 *   2026-09-27 — see `doc/architecture-decisions.md` "Guardrails › Amended".
 * - `membrane`: the `TrafficLightCell` catalogue entry
 *   (`inspect/src/main`'s `KernelEntries.kt`), the one journal-free
 *   replicable kernel cell whose `replicas` lowering passes
 *   `InstanceSetStep.validate`. Maintainer decision, computenet-va0c4.2,
 *   2026-09-27 — see `doc/architecture-decisions.md` "Guardrails › Amended".
 */
class DemoSurfaceAllowlistTest {

    companion object {
        /**
         * First segment after `civictech.cell.` that `:inspect` main sources
         * may import. Anything imported directly from the bare
         * `civictech.cell` root package (no further segment, e.g.
         * `civictech.cell.CellRef`) is always allowed — that is the root
         * vocabulary the whole model is built on. Demo sources have no
         * kernel-package allowlist.
         */
        val inspectCellPrefixes = setOf(
            "host",
            "port",
            "data",
            "observe",
            "link",
            "wire",
            "proxy", // see class KDoc — forced by FanOutlet.tap / Remote.sink shapes
            "partition", // see class KDoc — ShardCell has no Api marker; Class literal only (T20)
            "graph", // see class KDoc — WKB2 write plane's construction surface (computenet-va0c4.2)
            "membrane", // see class KDoc — TrafficLightCell catalogue entry (computenet-va0c4.2)
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
    fun `inspect main sources only import their allowed civictech-cell slice`() {
        val root = repoRoot()
        val violations = mutableListOf<String>()

        val inspectFiles = mainKotlinFiles(File(root, "inspect/src/main/kotlin"))
        // Non-vacuity: the walk degrades to an empty list when the tree
        // cannot be located, which would make this gate pass forever while
        // checking nothing.
        assertTrue(inspectFiles.size >= 5) {
            "scanned only ${inspectFiles.size} inspect main sources under ${root.path}/inspect — the walk " +
                "is broken (wrong repo root?), not the inspect tree"
        }

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
        // inspect may reach .proxy, .partition, .graph and .membrane (documented above)
        // but not .protocol or .evolve
        assertTrue(!disallowedImport("civictech.cell.proxy.Proxy", inspectCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.partition.ShardCell", inspectCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.graph.GraphSpec", inspectCellPrefixes))
        assertTrue(!disallowedImport("civictech.cell.membrane.TrafficLightCell", inspectCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.protocol.Protocols", inspectCellPrefixes))
        assertTrue(disallowedImport("civictech.cell.evolve.Shadow", inspectCellPrefixes))
    }
}
