package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.BeadsMirrorApp
import civictech.demo.beadsmirror.BeadsMirrorConfig
import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.ready.ReadyPredicate
import civictech.demo.beadsmirror.resolve.EdgeResolution
import civictech.demo.beadsmirror.resolve.EdgeResolver
import civictech.demo.beadsmirror.sanitizedDoltDatabaseName
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Task computenet-3bso.2.2: an end-to-end proof of feature computenet-3bso.2
 * acceptance rule 5, over two real [BdScratchWorkspace]s hosted by ONE
 * [BeadsMirrorApp] — the [MultiWorkspaceMirrorTest] idiom, driving the
 * [EdgeResolver] built by computenet-3bso.2.1 against real `bd`-produced
 * external dependency edges rather than in-process fixtures.
 *
 * **Why the target must be seeded with `--id <foreign-prefix> --force`.**
 * Every [BdScratchWorkspace] copy mints ids under the SAME template prefix
 * (`BdScratchWorkspace`'s `TEMPLATE_NAME`, `"beadsmirror"`), and a live probe
 * of this task (recorded on this task's own comment thread, corroborating the
 * feature's breakdown transcript) confirmed `bd dep add` onto a SAME-prefix id
 * absent from the local database is refused ("no issue found matching"),
 * while a DIFFERENT-prefix absent id lands verbatim as `depends_on_external`.
 * So [wsB]'s target issue is minted with an explicit foreign-prefix id via
 * `--id ... --force` (the [ScheduleStep.Create] idiom) — a `dep add` onto one
 * of [wsB]'s own default `beadsmirror`-prefixed ids could never produce an
 * external edge at all.
 *
 * Re-demonstrates feature rules 1 and 3 end to end and covers rule 5 (its own
 * job) plus rule 2's negative half (an id no hosted workspace holds stays
 * unresolved, verbatim):
 *
 * 1. **R1**: a real `bd dep add` in wsA onto wsB's seeded foreign-prefix
 *    target resolves, through [EdgeResolver], to wsB's identity with the
 *    target's mirrored `status` field readable as `"open"`.
 * 2. **R2**: a `bd dep add` in wsA onto an id no hosted workspace holds
 *    resolves to [EdgeResolution.Unresolved] carrying the verbatim id.
 * 3. **R3**: `bd close` of R1's target, landing through wsB's own feed, is
 *    visible on a later read of the SAME [MirrorEdge] via the SAME
 *    [EdgeResolver] instance (`status` now `"closed"`) — and wsA's
 *    [MirrorState.rebaselineCount], captured right after start-up, is
 *    unchanged: the flip is observed purely through wsB's incremental fold,
 *    never a rebaseline of either side.
 *
 * **Non-vacuousness by per-test tracing** (this task's declared route,
 * `mutation-check.md`'s production-mutation route being forbidden by the
 * bead's test-only declaration): every negative/steady-state assertion below
 * is paired, in the same run and through the same [EdgeResolver]/[mirrorState]
 * accessors, with a positive observation proving those accessors are live —
 * R1's own resolution is the positive trace for R2's "no hosted workspace"
 * case (same resolver, same call shape, a *different* outcome type), and R1's
 * `"open"` read is the positive trace for R3's steady `rebaselineCount`
 * assertion (the same edge, read again, through the same resolver, after a
 * real mutation actually landed).
 *
 * **Non-goals**: no production source changes; no readiness assertions
 * (computenet-3bso.3); no write-back (computenet-3bso.4); no ambiguity case
 * (R4 of the feature) — that is unit-covered in computenet-3bso.2.1's
 * `EdgeResolutionTest`, and minting the same id in two live scratch folds
 * without export-seeding is out of this task's scope.
 */
class CrossWorkspaceResolutionTest {

    private lateinit var wsA: BdScratchWorkspace
    private lateinit var wsB: BdScratchWorkspace
    private lateinit var identityA: String
    private lateinit var identityB: String
    private lateinit var runDir: Path
    private lateinit var isolatedSearchRoot: Path
    private var app: BeadsMirrorApp? = null

    /** Every [MirrorEvent] the running coordinator produced — diagnostic only; no assertion reads it directly. */
    private val events: MutableList<MirrorEvent> = java.util.Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        events.clear()
        wsA = BdScratchWorkspace.create()
        wsB = BdScratchWorkspace.create()
        identityA = sanitizedDoltDatabaseName(wsA.root)
        identityB = sanitizedDoltDatabaseName(wsB.root)
        runDir = Files.createTempDirectory("beadsmirror-crossresolve-e2e-run-")
        // An ancestor tree with no .beads of its own, same idiom as
        // MultiWorkspaceMirrorTest/BeadsMirrorAppTest.AgainstAScratchWorkspace.
        isolatedSearchRoot = Files.createTempDirectory("beadsmirror-crossresolve-e2e-searchroot-")
    }

    @AfterEach
    fun tearDown() {
        app?.stop()
        if (::wsA.isInitialized) wsA.close()
        if (::wsB.isInitialized) wsB.close()
        if (::runDir.isInitialized) runDir.toFile().deleteRecursively()
        if (::isolatedSearchRoot.isInitialized) isolatedSearchRoot.toFile().deleteRecursively()
    }

    /** Starts one [BeadsMirrorApp] mirroring both [wsA] and [wsB]. */
    private fun start(): BeadsMirrorApp {
        val started = BeadsMirrorApp.start(
            BeadsMirrorConfig(
                workspaces = listOf(wsA.root, wsB.root),
                pollInterval = Duration.ofMillis(100),
                runDir = runDir,
                repoSearchRoot = isolatedSearchRoot,
                onEvent = events::add,
            ),
        )
        app = started
        return started
    }

    /** [identity]'s live [MirrorState] handle, looked up by [civictech.demo.beadsmirror.WorkspaceMirror.identity]. */
    private fun mirrorState(identity: String): MirrorState =
        app!!.mirrors.single { it.identity == identity }.state

    private fun foldContainsIssue(identity: String, issueId: String): Boolean =
        issueId in mirrorState(identity).current.view()

    private fun edgePresent(identity: String, edge: MirrorEdge): Boolean =
        mirrorState(identity).current.edgeView().contains(edge)

    private fun BdScratchWorkspace.createIssue(title: String): String {
        val output = run("create", title, "--json")
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
    }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }

    @Test
    fun `a real cross-workspace bd dep add resolves against the sibling fold and tracks its close`() {
        val idA0 = wsA.createIssue("A seed")
        val sibId = "sib-3bso222"
        // The foreign-prefix, --id --force seed this task's diverges premise
        // depends on — a dep add onto one of wsB's own default-prefixed ids
        // could never produce an external edge (see class KDoc).
        wsB.run("create", "sib target", "--id", sibId, "--force")
        start()

        awaitUntil("wsA's seed issue appears on its fold") { foldContainsIssue(identityA, idA0) }
        awaitUntil("wsB's seeded foreign-prefix target appears on its fold") { foldContainsIssue(identityB, sibId) }

        // Captured right after the start-time baseline of both mirrors —
        // R3's "unchanged" assertion below reads against this, not against 0.
        // Asserted here (not just captured) so the steady-state check below is
        // paired, through this SAME accessor, with a positive observation that
        // it actually moved: every fresh BdScratchWorkspace has no persisted
        // checkpoint, so BeadsMirrorApp.start triggers exactly one
        // RebaselineReason.FirstStart swap per mirror (Rebaseline.run) before
        // this line runs. Without this assertion, a wsA wired to never
        // increment the counter at all would make the later "unchanged" check
        // pass just as easily as a correct implementation would.
        val rebaselineCountAfterStart = mirrorState(identityA).rebaselineCount
        rebaselineCountAfterStart shouldBe 1

        val resolver = EdgeResolver.forMirrors(app!!.mirrors)

        // --- R1: a real bd dep add across workspaces produces a real
        // depends_on_external edge that resolves to wsB, fields readable. ---
        wsA.run("dep", "add", idA0, sibId, "--type", "blocks")
        val edgeToSib = MirrorEdge(issueId = idA0, dependsOnIssueId = sibId, type = "blocks")
        awaitUntil("wsA's fold carries the real external edge onto wsB's seeded target") {
            edgePresent(identityA, edgeToSib)
        }

        val r1 = resolver.resolve(edgeToSib)
        val resolvedR1 = r1 as? EdgeResolution.Resolved
        checkNotNull(resolvedR1) { "expected Resolved, got $r1" }
        resolvedR1.workspaceIdentity shouldBe identityB
        ReadyPredicate.stringField(resolvedR1.fields, "status") shouldBe "open"

        // --- R2: a dep add onto an id no hosted workspace holds resolves to
        // Unresolved, carrying the verbatim id — same resolver, same call
        // shape as R1's positive trace, a different (and correct) outcome. ---
        val ghostId = "ghost-nobody-hosts-404"
        val idA1 = wsA.createIssue("A second, targets nobody")
        awaitUntil("wsA's second seed issue appears on its fold") { foldContainsIssue(identityA, idA1) }
        wsA.run("dep", "add", idA1, ghostId, "--type", "blocks")
        val edgeToGhost = MirrorEdge(issueId = idA1, dependsOnIssueId = ghostId, type = "blocks")
        awaitUntil("wsA's fold carries the unresolvable edge") { edgePresent(identityA, edgeToGhost) }

        val r2 = resolver.resolve(edgeToGhost)
        val unresolvedR2 = r2 as? EdgeResolution.Unresolved
        checkNotNull(unresolvedR2) { "expected Unresolved, got $r2" }
        unresolvedR2.issueId shouldBe ghostId

        // --- R3: bd close of R1's target lands through wsB's own feed; a
        // later read of the SAME edge, through the SAME resolver, sees the
        // flip — without a rebaseline of wsA. ---
        wsB.run("close", sibId, "--force")
        awaitUntil("the resolved edge's target reads closed after wsB's close lands") {
            val r = resolver.resolve(edgeToSib)
            (r as? EdgeResolution.Resolved)?.let { ReadyPredicate.stringField(it.fields, "status") == "closed" } ?: false
        }

        // The negative half of R3, paired with the positive close-observed
        // above through the same rebaselineCount accessor: wsA's own fold was
        // never rebaselined by wsB's flip — only wsB's incremental feed moved.
        mirrorState(identityA).rebaselineCount shouldBe rebaselineCountAfterStart
    }
}
