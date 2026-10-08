package civictech.inspect.edit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Textual guardrail for `[WKB2-01]`: every call that mutates host state or its
 * live cell graph — `spawn`, `spawnBound`, `despawn`, `connect`, `applyRemote`,
 * `applyTo`, `resume`, `resumeHost`, `supervise`, `suspend`, `drainHost`,
 * `migrate`, `declareWrite` — or references `Promotion.` lives inside
 * `civictech.inspect.edit`, with exactly two named, per-verb exceptions:
 *
 * - `Observations.kt` × {`spawn`, `connect`, `despawn`} — the inspector's own
 *   `ObserveCell` instrument sinks (M1), pre-existing and independent of the
 *   write plane.
 * - `Cold.kt` × {`resume`, `resumeHost`} — the `Waker` behind
 *   `POST /graph/{id}/wake` (M5-COLD), likewise pre-existing.
 *
 * The verb list is hand-maintained, not derived: it is wczst-D7's decided list
 * plus the host-state mutators `HostManagementApi` (kernel `Host.kt`) declares
 * beyond it (`supervise`, `suspend`, `drainHost`, `migrate`, `declareWrite`). It is NOT
 * guaranteed complete against that interface: a new mutating member must be
 * added to [verbRegex] by hand. Read-only members (`lookup`, `inspectTopology`,
 * `declaredWrite`, `upstreamConsumeAncestors`) are deliberately absent.
 * `HostRoutingApi.route` is not a `HostManagementApi` member and is not
 * read-only: it dispatches an `Invocation` to a hosted inlet. It is deliberately
 * not in this textual regex because `.route(` would also match DemoShell's HTTP
 * route declarations.
 *
 * The check is deliberately textual and cheap: it answers "which files can
 * mutate host state?" with one directory plus two named exceptions, not a
 * structural analysis of call targets. Comments (`//` to end of line, and
 * `/* ... */` blocks including KDoc) are stripped before matching so KDoc
 * prose that merely names a verb — e.g. `Cold.kt`'s own table describing
 * `HostManagementApi.resume(ref)` — does not produce a false hit; string
 * literals containing `//` are not a concern in the files this test scans.
 *
 * The allowlist is asserted non-vacuous: if an allowlisted (file, verb) pair
 * matches nothing, that entry is stale and the test fails naming it, so the
 * allowlist cannot silently rot into over-permission.
 */
class WritePlaneBoundaryTest {

    companion object {
        private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

        private val inspectMainDir = File(repoRoot, "inspect/src/main/kotlin/civictech/inspect")

        private val verbRegex = Regex("""\.(spawn|spawnBound|despawn|connect|applyRemote|applyTo|resume|resumeHost|supervise|suspend|drainHost|migrate|declareWrite)\(""")
        private val promotionRegex = Regex("""\bPromotion\.""")

        /** file name (not path) -> allowed verbs for that file, exactly. */
        private val allowlist: Map<String, Set<String>> = mapOf(
            "Observations.kt" to setOf("spawn", "connect", "despawn"),
            "Cold.kt" to setOf("resume", "resumeHost"),
        )
    }

    private data class Hit(val relativePath: String, val line: Int, val verb: String)

    /**
     * Strips `//` line comments and `/* ... */` block comments (including
     * KDoc) from Kotlin source text, keeping line numbers stable: a comment's
     * characters are blanked out rather than removed, so every remaining
     * character stays on its original line.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                // Line comment: blank until (not including) the next newline.
                while (i < n && text[i] != '\n') {
                    out.append(' ')
                    i++
                }
            } else if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                // Block comment (including KDoc /** ... */): blank until the closing */,
                // preserving newlines so line numbers stay correct.
                out.append(' ')
                out.append(' ')
                i += 2
                while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                    out.append(if (text[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i < n) {
                    // Consume the closing "*/"
                    out.append(' ')
                    out.append(' ')
                    i += 2
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    @Test
    fun `only edit dot mutates host state, and the allowlist stays honest`() {
        val scannedFiles = inspectMainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val relative = file.relativeTo(inspectMainDir).path
                !relative.startsWith("edit${File.separatorChar}") && relative != "edit"
            }
            .sortedBy { it.path }
            .toList()

        val violations = mutableListOf<Hit>()
        val allowlistHitCounts = mutableMapOf<Pair<String, String>, Int>()
        for ((file, verbs) in allowlist) {
            for (verb in verbs) allowlistHitCounts[file to verb] = 0
        }

        for (file in scannedFiles) {
            val fileName = file.name
            val stripped = stripComments(file.readText())
            val lines = stripped.split("\n")
            for ((idx, line) in lines.withIndex()) {
                val lineNumber = idx + 1

                for (match in verbRegex.findAll(line)) {
                    val verb = match.groupValues[1]
                    recordMatch(fileName, verb, lineNumber, file, inspectMainDir, violations, allowlistHitCounts)
                }
                if (promotionRegex.containsMatchIn(line)) {
                    recordMatch(fileName, "Promotion", lineNumber, file, inspectMainDir, violations, allowlistHitCounts)
                }
            }
        }

        val staleAllowlistEntries = allowlistHitCounts.filterValues { it == 0 }.keys
        val violationMessages = violations.map { hit ->
            "${hit.relativePath}:${hit.line}: ${hit.verb} — host mutation outside civictech.inspect.edit ([WKB2-01])"
        }
        val staleMessages = staleAllowlistEntries.map { (file, verb) ->
            "stale allowlist entry: $file × $verb matched nothing"
        }

        val failures = violationMessages + staleMessages
        failures.joinToString("\n").shouldBe("")
    }

    private fun recordMatch(
        fileName: String,
        verb: String,
        lineNumber: Int,
        file: File,
        baseDir: File,
        violations: MutableList<Hit>,
        allowlistHitCounts: MutableMap<Pair<String, String>, Int>,
    ) {
        val allowedVerbs = allowlist[fileName]
        if (allowedVerbs != null && verb in allowedVerbs) {
            val key = fileName to verb
            allowlistHitCounts[key] = (allowlistHitCounts[key] ?: 0) + 1
        } else {
            violations += Hit(file.relativeTo(baseDir).path, lineNumber, verb)
        }
    }
}
