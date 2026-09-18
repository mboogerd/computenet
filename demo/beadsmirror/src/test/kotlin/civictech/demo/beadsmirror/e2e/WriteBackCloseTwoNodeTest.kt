package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.writeback.WriteBackEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-khqek: feature computenet-6wc.2's two REMOVAL-BOUNDARY
 * properties at the level only the FOLD can trigger — a real [TwoNodeRig]
 * (real `bd`/`dolt`, a real `:wire` socket), write-back on the dialer only.
 *
 * ## What each test owns
 *
 * - `R1 - …the listener's close…` — feature computenet-6wc.2 R1's **trigger**
 *   half: the fold adopts the listener's `bd close` as the dot-order winner
 *   and the dialer's applier lands it in the dialer's OWN `bd`, exactly once,
 *   with `closed_at`/`close_reason` byte-identical to the listener's export
 *   row. R1's **mechanism** half — that the imposition is ordinary field
 *   imposition through one `bd import` and never a `bd close` invocation — is
 *   the sibling applier-level task's
 *   (`writeback.WriteBackCloseTest`, computenet-b7my1); nothing here re-states
 *   it, because this rig has no command trace to read (feature breakdown,
 *   "Drift").
 * - `R5 - a listener hard delete…` — R5 (*absence in an incoming delta is not
 *   a removal signal*) at the level where the "incoming delta" is a real
 *   gossiped delta rather than a hand-built one. The unit halves — the
 *   planner never visiting an issue the fold lacks
 *   (`writeback.WriteBackPlannerTest`) and the bystander row surviving an
 *   import (`writeback.RemovalBoundaryTest`, computenet-vfp27) — are cited,
 *   not duplicated.
 *
 * ## Why `bd delete` appears in a test that exists to bar it
 *
 * Feature computenet-6wc.2 R4 bars `bd delete` from the module's MAIN sources
 * (the applier must never express a removal as one). It says nothing about
 * test sources, which is where a peer's hard delete has to come FROM in order
 * to be a stimulus at all — the same role `bd delete` already plays in
 * [ReadyDifferentialHarness]. The property under test is precisely that this
 * deletion does **not** propagate into the dialer's store.
 *
 * ## Two premises this file turns from inference into observation
 *
 * The breakdown marked both NOT VERIFIED; the first is now observed here, the
 * second is recorded beside the assertion that depends on it.
 *
 * 1. That `closed_at`/`close_reason` reach `projector.view()` at all was
 *    inferred from [civictech.demo.beadsmirror.feed.DoltCommitFeed]'s
 *    all-columns mapping. Observed: see the fold assertion in the R1 test.
 * 2. Whether the dialer's fold DROPS a listener-deleted row whose every dot is
 *    listener-minted (echo suppression having left the dialer's own import
 *    commit dot-less) was unknown. Observed: see the note beside
 *    `the dialer's fold drops Z` below.
 *
 * ## Mutation used on the listener, never a bare `workspace.run`
 *
 * Every `bd` mutation below goes through [TwoNodeRig.mutate], which returns
 * only once the mutation is a commit the mirror's feed can see. `bd` exits 0
 * before its Dolt commit is necessarily visible, and [TwoNodeRig.Node.quiesce]
 * is then VACUOUSLY satisfied — "my checkpoint is at my head" is true of a head
 * that never moved (open bug computenet-3zr5k, measured on computenet-rl2qx).
 *
 * Guarded exactly like [WriteBackTwoNodeTest] and [EchoSuppressionTwoNodeTest]:
 * green-but-**skipped** where `bd`/`dolt` are not on PATH.
 *
 * **Non-goals** (feature computenet-6wc.2's, and this task's): no guard-fixture
 * close (R2/R3 — applier level, sibling), no `bd delete` source scan (R4,
 * sibling), no both-sided write-back, no partition, no multi-hop, no reopen,
 * and deliberately nothing about the listener RE-ACQUIRING the deleted row from
 * the dialer (the B->A resurrection `doc/spike/bds0/claim-c-close-replication.md`
 * measured; the epic excludes replicating hard deletes, and the listener runs
 * write-back off here anyway).
 */
class WriteBackCloseTwoNodeTest {

    private var rig: TwoNodeRig? = null

    /** The issue the R1 test closes — seeded on BOTH workspaces. */
    private lateinit var x: String

    /** The issue the R5 test deletes — seeded on the LISTENER only. */
    private lateinit var z: String

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        rig = TwoNodeRig.create("bds2-close")
        x = "close-shared-${System.nanoTime()}"
        z = "close-listener-only-${System.nanoTime()}"
    }

    @AfterEach
    fun tearDown() {
        rig?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /**
     * Feature computenet-6wc.2 R1, trigger half (this task's clauses 1 and 2).
     *
     * The listener closes X with a reason; the dialer's fold adopts that as the
     * dot-order winner; the dialer's applier imposes it onto the dialer's own
     * `bd` — ONE `Imposed(X)`, ONE import — and `bd show` on the dialer reports
     * `status closed` with `closed_at`/`close_reason` byte-identical to the
     * LISTENER's export row. Then nothing further happens for eight poll
     * intervals: echo suppression holds for a close-carrying import exactly as
     * it does for [WriteBackTwoNodeTest]'s priority edit.
     */
    @Test
    fun `R1 - the dialer lands the listener's close exactly once and bd show reports it`() {
        seedOnBoth(x, priority = "3")
        val listener = rigOrFail.startListener(writeBack = false)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        rigOrFail.await("both nodes agree on X before the close") { listener.view()[x] == dialer.view()[x] }
        // X was seeded independently on both workspaces at equal priority, so
        // write-back's first pass(es) settle as NoOp (created_at/updated_at are
        // the only fields that can differ, and both are NON_COMPARABLE —
        // computenet-6wc.1.6). Let that settle, so the counts taken below are
        // attributable to the close and not to start-up noise. The template
        // ([WriteBackTwoNodeTest]) uses the same three-interval settle.
        rigOrFail.await("write-back has processed X at least once") {
            dialer.writeBackEvents().any { it.issueId == x }
        }
        Thread.sleep(rigOrFail.pollIntervalMs() * SETTLE_POLL_INTERVALS)
        val dialerLogBeforeClose = dialer.logHead()

        rigOrFail.mutate(listener, "close", x, "--reason", CLOSE_REASON)
        listener.quiesce()

        // Premise 1, observed rather than inferred: the close's fields reach
        // the FOLD. If DoltCommitFeed did not carry them, this await — not one
        // of the bd-level assertions below — is what would time out, which is
        // why it is a separate, named step.
        rigOrFail.await("the dialer's fold carries the listener's close fields for X") {
            val folded = dialer.view()[x].orEmpty()
            folded["status"]?.contains("closed") == true &&
                folded["close_reason"]?.contains(CLOSE_REASON) == true &&
                folded["closed_at"] != null
        }

        rigOrFail.await("the dialer imposes the close on X") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == x }
        }
        // Settle before counting, so a repeated-imposition regression shows up
        // in the counts here and not only in the negative window below.
        Thread.sleep(rigOrFail.pollIntervalMs() * SETTLE_POLL_INTERVALS)

        val imposedForX = dialer.writeBackEvents().filterIsInstance<WriteBackEvent.Imposed>().filter { it.issueId == x }
        imposedForX shouldHaveSize 1
        // Every actual `bd import` invocation is preceded by exactly one
        // PreFlight (WriteBackApplier.applyOnce's KDoc), so the PreFlight count
        // is the import count — the same proxy [WriteBackTwoNodeTest] uses.
        preFlightCountFor(dialer, x) shouldBe 1

        val observed = imposedForX.single().observed
        string(observed, "status") shouldBe "closed"
        string(observed, "close_reason") shouldBe CLOSE_REASON

        // ---- clause 1: the dialer's OWN bd store, against the LISTENER's export ----
        val listenerRow = exportRowFor(listener, x).shouldNotBeNull().json
        val dialerShown = bdShow(dialer, x).shouldNotBeNull()
        string(dialerShown, "status") shouldBe "closed"
        // Byte-identity across the two nodes, on the two fields a close adds.
        // `bd show --json` and `bd export` render a timestamp identically
        // (RFC3339 `Z`, second granularity — probed on this host, bd 1.1.2,
        // 2026-09-18), so this is a like-for-like string comparison and not a
        // comparison of two renderings that merely happen to agree today. It is
        // stated as the string it is; a future divergence in either renderer is
        // a real finding here, not noise to normalize away.
        string(dialerShown, "closed_at").shouldNotBeNull() shouldBe string(listenerRow, "closed_at")
        string(dialerShown, "close_reason") shouldBe string(listenerRow, "close_reason")

        val dialerLogAfterClose = dialer.logHead()
        // The imposition IS a Dolt commit on the dialer's workspace: the write
        // landed, and this is not a test that agrees with itself about nothing.
        (dialerLogAfterClose.size > dialerLogBeforeClose.size) shouldBe true

        // ---- clause 2: nothing further, over eight poll intervals ----
        // A bounded sleep, not an await: this is a NEGATIVE assertion, which
        // awaitUntil cannot express. Eight intervals is the module's constant
        // (decision 6wc.3-D8).
        //
        // CAVEAT, stated where it is asserted: a stable `dolt_log` is a WEAK
        // discriminator for echo suppression — an UNsuppressed echo re-mints
        // the same values, so the next planner pass is a NoOp and commits
        // nothing either. What this window does establish is the clause as
        // written: a close-carrying import does not start a loop. The
        // discriminating evidence for suppression itself is
        // [EchoSuppressionTwoNodeTest]'s classification and dot assertions.
        Thread.sleep(rigOrFail.pollIntervalMs() * QUIESCENT_POLL_INTERVALS)

        dialer.writeBackEvents().filterIsInstance<WriteBackEvent.Imposed>().filter { it.issueId == x } shouldHaveSize 1
        preFlightCountFor(dialer, x) shouldBe 1
        dialer.logHead() shouldBe dialerLogAfterClose
        string(bdShow(dialer, x).shouldNotBeNull(), "status") shouldBe "closed"
    }

    /**
     * Feature computenet-6wc.2 R5, at the gossip level (this task's clauses 3
     * and 4).
     *
     * Z is seeded on the LISTENER ONLY, so the dialer's copy is born entirely
     * by write-back and — echo suppression having made the dialer's own import
     * commit mint nothing — every dot Z carries anywhere is listener-minted.
     * The listener then hard-deletes Z. That reaches the fold as a REMOVED diff
     * and is expressed on the wire as ABSENCE, which is exactly the signal R5
     * says must mutate nothing: the dialer's row stays, byte-identical, and no
     * import is ever run for it again.
     */
    @Test
    fun `R5 - a listener hard delete leaves the dialer's bd row intact and runs no import for it`() {
        rigOrFail.listenerWorkspace.run("create", "listener-only issue $z", "--id", z, "--force", "-p", "2")
        val listener = rigOrFail.startListener(writeBack = false)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        // Z exists only on the listener, so the dialer's planner sees a fold
        // row with no export row and imposes it: `bd import` upserts, so the
        // row is CREATED in the dialer's own store (WriteBackPlanner.plan's
        // KDoc). That is the C1a create shape the removal boundary is about.
        rigOrFail.await("the dialer imposes Z, creating it in its own bd") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == z }
        }
        rigOrFail.await("bd show of Z succeeds on the dialer") { bdShow(dialer, z) != null }
        // Settle, so the pre-delete capture is of a store that has stopped
        // moving rather than of one mid-pass.
        Thread.sleep(rigOrFail.pollIntervalMs() * SETTLE_POLL_INTERVALS)

        val dialerRowBeforeDelete = exportRowFor(dialer, z).shouldNotBeNull().json.toString()
        val dialerPreFlightsBeforeDelete = preFlightCountFor(dialer, z)
        val dialerLogBeforeDelete = dialer.logHead()

        rigOrFail.mutate(listener, "delete", z, "--force")
        listener.quiesce()

        // On the LISTENER every dot Z holds is its own, and a REMOVED diff row
        // tombstones the dots THAT SOURCE minted
        // (projector.WinnerDerivationTest), so the listener's fold drops Z
        // outright.
        rigOrFail.await("the listener's fold drops Z") { listener.view()[z] == null }
        // Premise 2, observed rather than inferred (the breakdown marked it
        // NOT VERIFIED): the DIALER's fold drops Z too. That is the expected
        // consequence of echo suppression — the dialer's own import commit for
        // Z is classified ECHO and mints no dot, so Z carries only
        // listener-minted dots and the listener's tombstones cover all of them.
        // It holds within the rig's ordinary await budget on this host; if it
        // ever stops holding, the clause-3 assertions below are unaffected (they
        // are about the dialer's bd STORE, not its fold) and the fold state
        // plus `dialer.dotsFor(z)` is the finding to record on feature
        // computenet-6wc.2.
        rigOrFail.await("the dialer's fold drops Z") { dialer.view()[z] == null }

        // A bounded sleep, not an await: clause 3 is a NEGATIVE assertion
        // (nothing happens to the dialer's row), and the window has to be long
        // enough for several further write-back passes to have run.
        Thread.sleep(rigOrFail.pollIntervalMs() * QUIESCENT_POLL_INTERVALS)

        // ---- clause 3: the dialer's row is untouched ----
        exportRowFor(dialer, z).shouldNotBeNull().json.toString() shouldBe dialerRowBeforeDelete
        bdShow(dialer, z).shouldNotBeNull().let { string(it, "status") shouldBe "open" }
        // No import was RUN for Z after the delete (PreFlight is the import
        // proxy), and nothing failed trying.
        preFlightCountFor(dialer, z) shouldBe dialerPreFlightsBeforeDelete
        dialer.writeBackEvents().filterIsInstance<WriteBackEvent.Failed>().filter { it.issueId == z } shouldHaveSize 0
        // …and no Dolt commit touched the dialer's workspace at all: absence is
        // not a removal signal, and not a re-write either.
        dialer.logHead() shouldBe dialerLogBeforeDelete

        // The delete really was a hard delete on the originating node — without
        // this, the whole test could pass against a `bd delete` that did
        // nothing (doc/spike/bds0/claim-c-close-replication.md C4d).
        listener.exportNow().none { row -> row.id == z } shouldBe true
        bdShow(listener, z) shouldBe null
    }

    /** Seeds [issueId] independently on BOTH workspaces, at the same [priority], BEFORE either node starts. */
    private fun seedOnBoth(issueId: String, priority: String) {
        rigOrFail.listenerWorkspace.run("create", "shared issue $issueId", "--id", issueId, "--force", "-p", priority)
        rigOrFail.dialerWorkspace.run("create", "shared issue $issueId", "--id", issueId, "--force", "-p", priority)
    }

    private fun exportRowFor(node: TwoNodeRig.Node, issueId: String): ExportRow? =
        node.exportNow().singleOrNull { it.id == issueId }

    /**
     * `bd show <id> --json` on [node], or `null` when `bd show` exits non-zero
     * — which is what a missing issue looks like, so the caller can assert
     * either presence or absence without a throwing `run` turning the verdict
     * into a harness failure ([civictech.demo.beadsmirror.BdScratchWorkspace.runAllowingFailure]'s
     * reason for existing).
     *
     * `bd show --json` prints a one-element ARRAY, not a bare object —
     * measured live, 2026-09-13, and re-observed on this host 2026-09-18.
     */
    private fun bdShow(node: TwoNodeRig.Node, issueId: String): JsonObject? {
        val invocation = node.workspace.runAllowingFailure("show", issueId, "--json")
        if (!invocation.succeeded) return null
        val start = invocation.output.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val parsed = Json.parseToJsonElement(invocation.output.substring(start))
        return if (parsed is JsonArray) parsed.jsonArray.singleOrNull()?.jsonObject else parsed.jsonObject
    }

    /** [field]'s value in [row] as the string bd rendered, or `null` when absent or not a JSON string. */
    private fun string(row: JsonObject, field: String): String? =
        (row[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun preFlightCountFor(node: TwoNodeRig.Node, issueId: String): Int =
        node.writeBackEvents().count { it is WriteBackEvent.PreFlight && it.issueId == issueId }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }

    private companion object {

        /** The reason the R1 close carries — asserted verbatim on both nodes. */
        const val CLOSE_REASON: String = "done"

        /** Ticks to let a pass settle before a count or capture is taken (the module's template idiom). */
        const val SETTLE_POLL_INTERVALS: Long = 3

        /** Decision 6wc.3-D8: the negative-assertion window, in this rig's poll intervals. */
        const val QUIESCENT_POLL_INTERVALS: Long = 8
    }
}
