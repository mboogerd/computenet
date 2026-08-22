package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.BeadsMirrorApp
import civictech.demo.beadsmirror.BeadsMirrorConfig
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.PollLoopDied
import civictech.demo.beadsmirror.sanitizedDoltDatabaseName
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Task computenet-3bso.1.3: an end-to-end demonstration, over TWO real
 * [BdScratchWorkspace]s hosted by ONE [BeadsMirrorApp] coordinator, of the
 * two cross-workspace rules feature computenet-3bso.1 makes true —
 * [WorkspaceMirror][civictech.demo.beadsmirror.WorkspaceMirror] and
 * [MirrorRoutes][civictech.demo.beadsmirror.http.MirrorRoutes] carry the
 * mechanism (tasks computenet-3bso.1.1/.1.2); this class is the live proof
 * that two independent mirrors, driven by real `bd` mutations and read back
 * over real HTTP, actually behave that way together:
 *
 * 1. **Fold isolation** (feature rule 2/R2): a `bd create` in workspace A
 *    shows up in A's fold, and workspace B's fold — read through the exact
 *    same HTTP route family — is byte-identically unaffected.
 * 2. **Failure isolation** (feature rule 3/R3): deleting workspace B's Dolt
 *    root mid-run kills only B's poll loop; a [PollLoopDied] naming B arrives
 *    on the shared event sink; A keeps folding further mutations; A's routes
 *    answer `200` while B's answer `503`.
 *
 * **Non-vacuousness by per-test tracing** (this task's declared route,
 * replacing a source mutation): every negative assertion below — "B's fold
 * is unchanged", "B's routes are frozen" — is paired, in the same run and
 * through the same [foldBody]/[HttpProbe] accessor, with a positive
 * observation proving that accessor is live: the awaited appearance of A's
 * own new issue on its own route.
 *
 * **Non-goals**: no production source changes (this class's package is the
 * whole diff); the existing [civictech.demo.beadsmirror.e2e.TwoNodeRigTest]
 * family (feature R4) is proven by the full-module run passing unmodified,
 * not repeated here; no cross-workspace join/read (computenet-3bso.2/.3).
 */
class MultiWorkspaceMirrorTest {

    private lateinit var wsA: BdScratchWorkspace
    private lateinit var wsB: BdScratchWorkspace
    private lateinit var identityA: String
    private lateinit var identityB: String
    private lateinit var runDir: Path
    private lateinit var isolatedSearchRoot: Path
    private var app: BeadsMirrorApp? = null
    private var probe: HttpProbe? = null

    /**
     * Every [MirrorEvent] the running coordinator produced. Synchronized
     * because a [PollLoopDied] is reported from the poller thread whose loop
     * died while the test reads from its own.
     */
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
        runDir = Files.createTempDirectory("beadsmirror-multi-e2e-run-")
        // An ancestor tree with no .beads of its own, so refuseIfLiveBeads's
        // walk-up finds nothing and the app starts normally — same idiom as
        // BeadsMirrorAppTest.AgainstAScratchWorkspace.
        isolatedSearchRoot = Files.createTempDirectory("beadsmirror-multi-e2e-searchroot-")
    }

    @AfterEach
    fun tearDown() {
        probe?.close()
        app?.stop()
        if (::wsA.isInitialized) wsA.close()
        if (::wsB.isInitialized) wsB.close()
        if (::runDir.isInitialized) runDir.toFile().deleteRecursively()
        if (::isolatedSearchRoot.isInitialized) isolatedSearchRoot.toFile().deleteRecursively()
    }

    /** Starts one [BeadsMirrorApp] mirroring both [wsA] and [wsB], and an [HttpProbe] against it. */
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
        probe = HttpProbe("http://localhost:${started.boundPort}")
        return started
    }

    /** [identity]'s issue-list route body, verbatim — the one accessor every assertion below reads through. */
    private fun foldBody(identity: String): String = probe!!.get("/workspaces/$identity/beads/issues").body()

    private fun foldContainsIssue(identity: String, issueId: String): Boolean =
        Json.parseToJsonElement(foldBody(identity)).jsonObject.keys.contains(issueId)

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

    /**
     * Feature rule 2 (R2): `bd create` in A lands in A's fold; B's fold,
     * read through the identical `/workspaces/{identity}/beads/issues`
     * route, is byte-identically unaffected.
     *
     * Both workspaces are seeded and baselined before the mutation, and both
     * seeds are awaited over HTTP first — establishing the harness is live
     * for BOTH folds through the same accessor the isolation check reads —
     * before B's pre-mutation snapshot is captured.
     */
    @Test
    fun `a mutation in workspace A lands in A's fold and leaves B's byte-identical`() {
        val idA0 = wsA.createIssue("A seed")
        val idB0 = wsB.createIssue("B seed")
        start()

        awaitUntil("wsA's seed issue appears on its route") { foldContainsIssue(identityA, idA0) }
        awaitUntil("wsB's seed issue appears on its route") { foldContainsIssue(identityB, idB0) }

        val beforeB = foldBody(identityB)

        val idA1 = wsA.createIssue("A mutation")

        // The paired positive trace: the mutation is observed landing in A's
        // fold, through foldBody/probe — the same accessor the negative
        // assertion below reads B through.
        awaitUntil("wsA's new issue appears on its route") { foldContainsIssue(identityA, idA1) }

        // The negative this test exists to prove: B's fold, re-read through
        // that live accessor, is byte-identical to its pre-mutation capture.
        val afterB = foldBody(identityB)
        afterB shouldBe beforeB
        afterB.toByteArray(Charsets.UTF_8) shouldBe beforeB.toByteArray(Charsets.UTF_8)
        // B never gained A's issue, or any issue of its own beyond the seed.
        foldContainsIssue(identityB, idA1) shouldBe false
    }

    /**
     * Feature rule 3 (R3): wsB's Dolt root is deleted mid-run (the
     * CheckpointGone/PollLoopDied family's own trigger, per this task's
     * description) — its poll loop dies, a [PollLoopDied] naming wsB's
     * identity is observed arriving on the shared event sink, wsA keeps
     * folding further mutations, and the two workspaces' routes diverge:
     * wsA answers `200`, wsB answers `503`.
     */
    @Test
    fun `wsB's poll loop dying is reported with its identity, freezes only its routes, and wsA keeps folding`() {
        val idA0 = wsA.createIssue("A seed")
        start()

        awaitUntil("wsA's seed issue appears on its route") { foldContainsIssue(identityA, idA0) }
        // Positive trace that wsB's own route is live and 200 BEFORE it is
        // killed — the same route family the 503 assertion below reads.
        probe!!.get("/workspaces/$identityB/beads/issues").statusCode() shouldBe 200

        // The R3 trigger: wsB's Dolt root — not merely `.dolt` — is deleted
        // out from under the running poller.
        wsB.doltRoot.toFile().deleteRecursively()

        // Paired positive: a PollLoopDied naming wsB is actually observed
        // arriving, read through the same `events` sink the "wsA never gets
        // one" assertion below reads.
        awaitUntil("a PollLoopDied naming wsB arrives") {
            events.filterIsInstance<PollLoopDied>().any { it.workspaceIdentity == identityB }
        }

        val idA1 = wsA.createIssue("A after B's death")
        awaitUntil("wsA folds a mutation made after wsB's loop died") { foldContainsIssue(identityA, idA1) }

        probe!!.get("/workspaces/$identityA/beads/issues").statusCode() shouldBe 200
        probe!!.get("/workspaces/$identityB/beads/issues").statusCode() shouldBe 503

        // The negative half of failure isolation: wsA's sibling loop never
        // died — no PollLoopDied ever named it, over the whole run.
        events.filterIsInstance<PollLoopDied>().none { it.workspaceIdentity == identityA } shouldBe true
    }
}
