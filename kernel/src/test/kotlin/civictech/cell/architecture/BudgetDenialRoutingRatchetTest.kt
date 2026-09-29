package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Feature `computenet-2zasa` (F4 of epic `computenet-66m`, ECO1), decisions
 * `2zasa-D1`/`2zasa-D3`, `66m-D10`, `[ECO1-DEN-12]`. One source ratchet
 * carries three structural rules over `kernel/src/main/kotlin` only (the
 * budget seam is kernel-only by `66m-D4`; `:economy` holds no
 * `BoundaryDenialSink`). Same style as [ArchitectureRatchetTest]: a plain-file
 * line scan, no Kotlin parser, no new dependency — but with the expected sets
 * spelled out inline rather than a checked-in baseline resource, because all
 * three sites are small enough to name directly and a fourth site must fail
 * loudly (`2zasa-D3`).
 *
 * All paths below are relative to `kernel/src/main/kotlin/civictech/cell`
 * (the `cellRoot`), matching the feature's own notation
 * (`host/ManagedHost.kt`, `membrane/CompositeCell.kt`, ...).
 *
 * (a) **`66m-D10` routing.** Every `BoundaryDenialSink.deny(...)` call site
 *     outside [scanDenyBudgetCallSites]'s helper (`BoundaryDenialSink.denyBudget`
 *     in `BudgetDenial.kt`) is checked: a bare `deny(` call (excluding the
 *     `fun deny(` declaration in `BoundaryDenials.kt` and any `denyBudget(`
 *     call) whose following 8 lines name `BUDGET_EXHAUSTED`,
 *     `BUDGET_NOT_GRANTED` or `LEDGER_FAILURE` would be a hand-built budget
 *     refusal bypassing the helper — [scanMisroutedDenials] must find none,
 *     everywhere except `BudgetDenial.kt` itself (excluded, since its own
 *     `deny(` call inside `denyBudget` is the one legitimate site). The
 *     complementary half, [scanBudgetReasonLiteralSites]: a code-line mention
 *     of one of those three reasons, outside `BoundaryDenials.kt` (the enum's
 *     own declaration file), occurs only in `BudgetDenial.kt` — a site that
 *     built a `Refused`/denial by hand elsewhere would show up here even if
 *     it never called `deny(` directly.
 * (b) **helper call sites.** [scanDenyBudgetCallSites] — the multiset of
 *     files containing a `denyBudget(` CALL (excluding the declaration line)
 *     — equals exactly `{host/ManagedHost.kt: 1, membrane/CompositeCell.kt: 2}`.
 * (c) **claim constructions — BS-05's premise.** [scanBudgetClaimConstructions]
 *     — the multiset of files with a `BudgetClaim(` CONSTRUCTION (excluding
 *     `data class BudgetClaim(`) — equals exactly the same
 *     `{host/ManagedHost.kt: 1, membrane/CompositeCell.kt: 2}`; asserted
 *     again, as its own assertion with its own message, that none of those
 *     sites is `membrane/MediateProxy.kt`, under `port/`, or in a file whose
 *     name contains `Propagate` — the delta-delivery paths BS-05 depends on
 *     staying unbudgeted.
 * (d) **`[ECO1-DEN-12]`.** [scanControlDenialCounterReads] — no code line
 *     under `control/` matches `boundaryDenialCount`, `denialCount` or
 *     `DenialCount`. Observed 2026-09-29 (tree `195d92ad`), every file under
 *     `kernel/src/main/kotlin/civictech/cell/control`:
 *     `git grep -n -E 'boundaryDenialCount|denialCount'` -> 0 hits (exit 1);
 *     `git grep -n -E 'DenialCount|denial'` -> 0 hits (exit 1) (both scoped to
 *     that directory).
 *
 * A fourth site tripping any of these is not a bug in the ratchet: widen the
 * expected set in this file WITH a comment naming the reason and the
 * BS-06-shaped discharge test the epic's `93-feature-interactions.md` §9
 * risk 7 demands for every new budget-denial site.
 *
 * Mutation route (manual, not run by this class — [evidence.md] "Mutation
 * checks"): add `val probe = BudgetClaim(CurrentPeer.stamp()!!, ClaimClass.Attention)`
 * inside `MediateProxy.invoke`; (c)'s forbidden-site assertion reddens;
 * capture `git diff HEAD -- kernel/src/main/kotlin/civictech/cell/membrane/MediateProxy.kt`,
 * run, then `git checkout -- <file>` — never commit the probe.
 */
class BudgetDenialRoutingRatchetTest {

    // ---- scanning, parameterised over a cell root so the fixture self-check
    // can exercise the exact same logic over a synthetic tree (same pattern
    // as ArchitectureRatchetTest / IdentityDerivationRatchetTest). ----

    private val denyDeclaration = Regex("""^\s*fun\s+deny\(""")
    private val denyBudgetCall = Regex("""\bdenyBudget\(""")
    private val denyBudgetDeclaration = Regex("""\bfun\b[^(]*\bdenyBudget\(""")
    private val denyCall = Regex("""\bdeny\(""")
    private val budgetClaimDeclaration = Regex("""\bdata\s+class\s+BudgetClaim\(""")
    private val budgetClaimConstruction = Regex("""\bBudgetClaim\(""")
    private val budgetReasonLiteral = Regex("""\b(?:BUDGET_EXHAUSTED|BUDGET_NOT_GRANTED|LEDGER_FAILURE)\b""")
    private val denialCounterRead = Regex("""boundaryDenialCount|denialCount|DenialCount""")

    /** Non-blank, non-comment "content" form of a line: `//`-comments stripped, or null for a KDoc line. */
    private fun contentOrNull(line: String): String? {
        if (line.trim().startsWith("*")) return null
        return line.substringBefore("//")
    }

    private fun eachKotlinFile(cellRoot: File, action: (relativePath: String, lines: List<String>) -> Unit) {
        cellRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            action(file.relativeTo(cellRoot).path.replace(File.separatorChar, '/'), file.readLines())
        }
    }

    /**
     * Files (outside `BudgetDenial.kt`) carrying a bare `deny(` call — not the
     * `fun deny(` declaration, not a `denyBudget(` call — whose following 8
     * lines name a budget-denial reason: a hand-built budget refusal that
     * bypasses the helper.
     */
    fun scanMisroutedDenials(cellRoot: File): Set<String> {
        val offenders = mutableSetOf<String>()
        eachKotlinFile(cellRoot) { relativePath, lines ->
            if (relativePath == "BudgetDenial.kt") return@eachKotlinFile
            lines.forEachIndexed { index, rawLine ->
                val content = contentOrNull(rawLine) ?: return@forEachIndexed
                if (denyDeclaration.containsMatchIn(content)) return@forEachIndexed
                if (denyBudgetCall.containsMatchIn(content)) return@forEachIndexed
                if (!denyCall.containsMatchIn(content)) return@forEachIndexed
                val window = lines.subList(index, minOf(lines.size, index + 9))
                val windowHasBudgetReason = window.any { windowLine ->
                    val windowContent = contentOrNull(windowLine) ?: return@any false
                    budgetReasonLiteral.containsMatchIn(windowContent)
                }
                if (windowHasBudgetReason) offenders += relativePath
            }
        }
        return offenders
    }

    /**
     * Files, outside `BoundaryDenials.kt` (the enum's own declaration file),
     * with a code-line mention of `BUDGET_EXHAUSTED`, `BUDGET_NOT_GRANTED` or
     * `LEDGER_FAILURE`.
     */
    fun scanBudgetReasonLiteralSites(cellRoot: File): Set<String> {
        val paths = mutableSetOf<String>()
        eachKotlinFile(cellRoot) { relativePath, lines ->
            if (relativePath == "BoundaryDenials.kt") return@eachKotlinFile
            lines.forEach { rawLine ->
                val content = contentOrNull(rawLine) ?: return@forEach
                if (budgetReasonLiteral.containsMatchIn(content)) paths += relativePath
            }
        }
        return paths
    }

    /** File -> count of `denyBudget(` CALL sites (excluding the declaration). */
    fun scanDenyBudgetCallSites(cellRoot: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        eachKotlinFile(cellRoot) { relativePath, lines ->
            lines.forEach { rawLine ->
                val content = contentOrNull(rawLine) ?: return@forEach
                if (denyBudgetDeclaration.containsMatchIn(content)) return@forEach
                if (denyBudgetCall.containsMatchIn(content)) counts.merge(relativePath, 1, Int::plus)
            }
        }
        return counts
    }

    /** File -> count of `BudgetClaim(` CONSTRUCTIONS (excluding `data class BudgetClaim(`). */
    fun scanBudgetClaimConstructions(cellRoot: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        eachKotlinFile(cellRoot) { relativePath, lines ->
            lines.forEach { rawLine ->
                val content = contentOrNull(rawLine) ?: return@forEach
                if (budgetClaimDeclaration.containsMatchIn(content)) return@forEach
                if (budgetClaimConstruction.containsMatchIn(content)) counts.merge(relativePath, 1, Int::plus)
            }
        }
        return counts
    }

    /** Repo-relative (to `cellRoot`) paths under `control/` containing a denial-counter read. */
    fun scanControlDenialCounterReads(cellRoot: File): Set<String> {
        val offenders = mutableSetOf<String>()
        eachKotlinFile(cellRoot) { relativePath, lines ->
            if (!relativePath.startsWith("control/")) return@eachKotlinFile
            lines.forEach { rawLine ->
                val content = contentOrNull(rawLine) ?: return@forEach
                if (denialCounterRead.containsMatchIn(content)) offenders += relativePath
            }
        }
        return offenders
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `every budget denial routes through denyBudget, only three sites construct a BudgetClaim, no scheduling code reads a denial counter`() {
        val root = repoRoot()
        val cellRoot = File(root, "kernel/src/main/kotlin/civictech/cell")
        assertTrue(cellRoot.isDirectory) { "missing $cellRoot - wrong working directory?" }

        // Non-vacuity: a scan of an empty or near-empty tree (moved sources, a
        // changed working directory) would pass every assertion below while
        // enforcing nothing. The real tree has ~190 files under civictech/cell.
        val fileCount = cellRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.count()
        assertTrue(fileCount > 50) {
            "scanned only $fileCount .kt files under $cellRoot - the scan is broken (wrong root?), not the ratchet"
        }

        // (a) 66m-D10 routing: no bare deny( call outside BudgetDenial.kt is
        // followed within 8 lines by a budget-denial reason literal.
        val misrouted = scanMisroutedDenials(cellRoot)
        assertTrue(misrouted.isEmpty()) {
            "found a deny( call (outside BudgetDenial.kt's own denyBudget helper) whose surrounding lines name " +
                "a budget-denial reason (BUDGET_EXHAUSTED / BUDGET_NOT_GRANTED / LEDGER_FAILURE) - route it " +
                "through BoundaryDenialSink.denyBudget instead (civictech/cell/BudgetDenial.kt), the one " +
                "helper [ECO1-DEN-02] requires: $misrouted"
        }

        // Complementary half: those three reasons are named in code, outside
        // the enum's own file, only in BudgetDenial.kt.
        val reasonSites = scanBudgetReasonLiteralSites(cellRoot)
        assertEquals(setOf("BudgetDenial.kt"), reasonSites) {
            "BUDGET_EXHAUSTED / BUDGET_NOT_GRANTED / LEDGER_FAILURE must be named in code, outside " +
                "BoundaryDenials.kt (the enum declaration), only inside BudgetDenial.kt - a hand-built " +
                "Refused/denial elsewhere bypasses the one accounting helper [ECO1-DEN-02] requires; " +
                "found: $reasonSites"
        }

        // (b) helper call sites.
        val denyBudgetSites = scanDenyBudgetCallSites(cellRoot)
        assertEquals(
            mapOf("host/ManagedHost.kt" to 1, "membrane/CompositeCell.kt" to 2),
            denyBudgetSites,
        ) {
            "denyBudget( call sites drifted from the three landed sites (66m-D10). A new site here needs its " +
                "own BS-06-shaped discharge test (93-feature-interactions.md §9 risk 7) before this set widens; " +
                "found: $denyBudgetSites"
        }

        // (c) claim constructions - BS-05's premise that no BudgetClaim is
        // built on a delta-delivery path.
        val claimSites = scanBudgetClaimConstructions(cellRoot)
        assertEquals(
            mapOf("host/ManagedHost.kt" to 1, "membrane/CompositeCell.kt" to 2),
            claimSites,
        ) {
            "BudgetClaim( construction sites drifted from the three landed sites. BS-05's re-scoped form " +
                "(2zasa-D1) depends on this set: a fourth site is either an accident to revert, or a genuine " +
                "new budget seam that needs its own BS-06-shaped discharge test and, if it sits on a delta " +
                "path, reopens BS-05's premise; found: $claimSites"
        }
        assertTrue(
            claimSites.keys.none { path ->
                path == "membrane/MediateProxy.kt" ||
                    path.startsWith("port/") ||
                    path.substringAfterLast('/').contains("Propagate")
            },
        ) {
            "a BudgetClaim( construction on a delta-delivery path (MediateProxy.kt, under port/, or a " +
                "Propagate-named file) would falsify BS-05's re-scoped premise (2zasa-D1): mid-wave exhaustion " +
                "cannot starve a data edge because no budget site sits on one; found: $claimSites"
        }

        // (d) [ECO1-DEN-12]: no scheduling code reads a denial counter.
        val controlHits = scanControlDenialCounterReads(cellRoot)
        assertTrue(controlHits.isEmpty()) {
            "found a denial-counter read (boundaryDenialCount / denialCount / DenialCount) under " +
                "civictech/cell/control/ - band assignment and FIFO order must stay decided by the scheduler " +
                "alone, never by how often a boundary has refused ([ECO1-DEN-12]); found: $controlHits"
        }
    }

    /**
     * Non-vacuousness route (test-only task - no production edit is in this
     * claim to prove discrimination against, so the test carries its own
     * fixture, same pattern as [IdentityDerivationRatchetTest]'s fixture
     * self-checks). One synthetic tree with:
     *  - the three legitimate sites (b)/(c) expect, so the exact-match scans
     *    are proven to find real sites, not just to report empty;
     *  - the `data class BudgetClaim(` and `fun ... denyBudget(` declaration
     *    lines, proving they are excluded rather than double-counted;
     *  - one violation per rule: (a) a hand-built budget refusal outside the
     *    helper, (c) a `BudgetClaim(` in `membrane/MediateProxy.kt`, (d) a
     *    `denialCount` read under `control/`;
     *  - one innocent `deny(` call with an unrelated reason, proving (a) does
     *    not flag every `deny(` call, only ones near a budget reason;
     *  - one comment-only and one KDoc-only mention of `denialCount` under
     *    `control/`, proving comment/KDoc stripping keeps (d) from crying
     *    wolf on prose that merely discusses the forbidden read.
     */
    @Test
    fun `fixture self-check - each rule reddens on its synthetic violation and ignores comments`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "Innocent.kt").writeText(
            """
            package civictech.cell

            class Innocent {
                fun doThing(): Int = 1
            }
            """.trimIndent(),
        )

        // The enum's own file: carries the three reason literals as
        // declarations, and the `fun deny(` declaration - both excluded from
        // their respective scans.
        File(tempDir, "BoundaryDenials.kt").writeText(
            """
            package civictech.cell

            enum class DenialReason {
                BUDGET_EXHAUSTED,
                BUDGET_NOT_GRANTED,
                LEDGER_FAILURE,
                NOT_ADMITTED,
            }

            class BoundaryDenialSink {
                fun deny(seam: BoundarySeam, reason: DenialReason): BoundaryDenial = TODO()
            }
            """.trimIndent(),
        )

        // The one legitimate helper: its own bare `deny(` call is excluded
        // from (a) by file name, and its `internal fun ... denyBudget(`
        // declaration line is excluded from (b)'s count. Carries one
        // legitimate code-line mention of a budget reason.
        File(tempDir, "BudgetDenial.kt").writeText(
            """
            package civictech.cell

            internal fun BoundaryDenialSink.denyBudget(refused: BudgetOutcome.Refused, claim: BudgetClaim): BoundaryDenial =
                deny(
                    seam = BoundarySeam.LINK_AUTHORITY,
                    reason = refused.reason,
                )

            private val fallbackReason = DenialReason.LEDGER_FAILURE
            """.trimIndent(),
        )

        File(tempDir, "Budget.kt").writeText(
            """
            package civictech.cell

            data class BudgetClaim(val stamp: PeerStamp, val claimClass: ClaimClass, val key: Any? = null)
            """.trimIndent(),
        )

        val hostDir = File(tempDir, "host").apply { mkdirs() }
        File(hostDir, "ManagedHost.kt").writeText(
            """
            package civictech.cell.host

            class ManagedHost {
                fun spawn(): BudgetOutcome {
                    val claim = BudgetClaim(stamp, ClaimClass.Spawn)
                    return hostDenials.sinkFor("host").denyBudget(
                        claim, BoundarySeam.HOST_ADMISSION,
                    )
                }
            }
            """.trimIndent(),
        )

        // Innocent deny( call, unrelated reason - (a) must not flag this one.
        File(hostDir, "Safe.kt").writeText(
            """
            package civictech.cell.host

            class Safe {
                fun refuse(sink: BoundaryDenialSink) {
                    sink.deny(
                        seam = BoundarySeam.ADMISSION,
                        reason = DenialReason.NOT_ADMITTED,
                    )
                }
            }
            """.trimIndent(),
        )

        // Violation (a): a hand-built budget refusal outside the helper.
        File(hostDir, "Misrouted.kt").writeText(
            """
            package civictech.cell.host

            class Misrouted {
                fun refuse(sink: BoundaryDenialSink) {
                    sink.deny(
                        seam = BoundarySeam.HOST_ADMISSION,
                        reason = DenialReason.BUDGET_EXHAUSTED,
                    )
                }
            }
            """.trimIndent(),
        )

        val membraneDir = File(tempDir, "membrane").apply { mkdirs() }
        File(membraneDir, "CompositeCell.kt").writeText(
            """
            package civictech.cell.membrane

            class CompositeCell {
                fun link(): LinkResult {
                    val claim = BudgetClaim(stamp, ClaimClass.Link, key = request)
                    denials.denyBudget(claim, BoundarySeam.LINK_AUTHORITY, subject = null)
                    return LinkResult.Rejected("x")
                }

                fun attend() {
                    val claim = BudgetClaim(CurrentPeer.stamp()!!, ClaimClass.Attention, key = id to version)
                    denials.denyBudget(
                        claim,
                        BoundarySeam.PROTOCOL_AUTHORITY,
                    )
                }
            }
            """.trimIndent(),
        )

        // Violation (c): BudgetClaim( constructed on a delta-delivery path.
        File(membraneDir, "MediateProxy.kt").writeText(
            """
            package civictech.cell.membrane

            class MediateProxy {
                fun invoke() {
                    val probe = BudgetClaim(CurrentPeer.stamp()!!, ClaimClass.Attention)
                }
            }
            """.trimIndent(),
        )

        val controlDir = File(tempDir, "control").apply { mkdirs() }
        // Violation (d): scheduling code reading a denial counter.
        File(controlDir, "Scheduler.kt").writeText(
            """
            package civictech.cell.control

            class Scheduler {
                fun shouldThrottle(sink: BoundaryDenialSink): Boolean = sink.denialCount > 3
            }
            """.trimIndent(),
        )

        // Comment-only and KDoc-only mentions under control/ - must NOT be
        // flagged, proving (d) strips comments the same way (a) does.
        File(controlDir, "Notes.kt").writeText(
            """
            package civictech.cell.control

            /**
             * Do not read denialCount here - see BudgetDenial.kt instead.
             */
            class Notes // never reads denialCount, this is just a comment
            """.trimIndent(),
        )

        assertEquals(setOf("host/Misrouted.kt"), scanMisroutedDenials(tempDir)) {
            "the misrouted-deny scan must flag exactly the hand-built budget refusal, never the innocent " +
                "deny( call in host/Safe.kt nor anything in BudgetDenial.kt itself"
        }

        assertEquals(setOf("BudgetDenial.kt", "host/Misrouted.kt"), scanBudgetReasonLiteralSites(tempDir)) {
            "the reason-literal scan must find the legitimate mention in BudgetDenial.kt and the stray one in " +
                "host/Misrouted.kt, but never the enum's own declarations in BoundaryDenials.kt"
        }

        assertEquals(
            mapOf("host/ManagedHost.kt" to 1, "membrane/CompositeCell.kt" to 2),
            scanDenyBudgetCallSites(tempDir),
        ) {
            "the denyBudget( call-site scan must count the two real call sites and exclude the declaration " +
                "line in BudgetDenial.kt"
        }

        assertEquals(
            mapOf(
                "host/ManagedHost.kt" to 1,
                "membrane/CompositeCell.kt" to 2,
                "membrane/MediateProxy.kt" to 1,
            ),
            scanBudgetClaimConstructions(tempDir),
        ) {
            "the BudgetClaim( construction scan must count the three legitimate sites AND the stray probe in " +
                "MediateProxy.kt, and must exclude the data class declaration in Budget.kt"
        }

        assertEquals(setOf("control/Scheduler.kt"), scanControlDenialCounterReads(tempDir)) {
            "the control/ denial-counter scan must flag the real read in Scheduler.kt but never the " +
                "comment/KDoc-only mentions in Notes.kt"
        }
    }
}
