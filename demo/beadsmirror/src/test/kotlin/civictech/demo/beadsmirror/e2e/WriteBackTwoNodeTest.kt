package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.writeback.WriteBackEvent
import civictech.demo.beadsmirror.writeback.WriteBackFailure
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-6wc.1.5: the mirror APP wired end to end with `--write-back`
 * — feature computenet-6wc.1 clause 1's R1 example, over a real
 * [TwoNodeRig] (real `bd`/`dolt`, a real `:wire` socket): the dialer's
 * write-back-enabled applier imposes the listener's dot-order-winning edit
 * onto the dialer's OWN `bd` workspace, and `bd show` on the dialer reports
 * it.
 *
 * **X is seeded independently on BOTH workspaces** before either node starts
 * (`--id <id> --force`, the `ScheduleStep.Create` idiom — see
 * [CrossWorkspaceResolutionTest]'s KDoc for why an explicit foreign-style id
 * is needed at all: every [civictech.demo.beadsmirror.BdScratchWorkspace]
 * copy mints its own auto-ids under the SAME template prefix) — real
 * wall-clock `created_at` values, milliseconds apart, on each workspace's own
 * copy of X. This used to make a clean `Imposed` for that row permanently
 * unreachable, because `created_at` was compared like any other field; fixed
 * by excluding it from comparison
 * ([civictech.demo.beadsmirror.writeback.ImposedFields.NON_COMPARABLE],
 * computenet-6wc.1.6). The tests below assert the exact
 * `Imposed`/import-invocation counts clause 1 and clause 4 ask for.
 *
 * Guarded exactly like [TwoNodeRigTest]: green-but-skipped where `bd`/`dolt`
 * are not on PATH.
 *
 * **Echo suppression is now wired** (feature computenet-6wc.3, task
 * computenet-6wc.3.3), so the two `dolt_log` growth-after-convergence
 * measurements below — which this file used to PRINT as the open boundary with
 * that feature — are assertions of zero. They are the single-sided case (write-
 * back on the dialer only); the both-sided case, and the classification itself,
 * are [EchoSuppressionTwoNodeTest]'s.
 *
 * **Non-goals** (feature computenet-6wc.1's / this task's own): no close/delete
 * semantics (computenet-6wc.2), no lease plane.
 */
class WriteBackTwoNodeTest {

    private var rig: TwoNodeRig? = null
    private lateinit var x: String

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        rig = TwoNodeRig.create("bds1-wb")
        x = "wb-shared-${System.nanoTime()}"
    }

    @AfterEach
    fun tearDown() {
        rig?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /** Seeds [x] independently on both workspaces, at the same [priority], BEFORE either node starts. */
    private fun seedOnBoth(priority: String = "3") {
        rigOrFail.listenerWorkspace.run("create", "shared issue $x", "--id", x, "--force", "-p", priority)
        rigOrFail.dialerWorkspace.run("create", "shared issue $x", "--id", x, "--force", "-p", priority)
    }

    /** `bd show <id> --json` prints a one-element ARRAY, not a bare object — measured live, 2026-09-13. */
    private fun bdShowPriority(node: TwoNodeRig.Node, issueId: String): Int {
        val output = node.workspace.run("show", issueId, "--json")
        val start = output.indexOfFirst { it == '{' || it == '[' }
        check(start >= 0) { "bd show --json printed no JSON at all:\n$output" }
        val parsed = Json.parseToJsonElement(output.substring(start))
        val row = if (parsed is JsonArray) parsed.jsonArray.single().jsonObject else parsed.jsonObject
        return row.getValue("priority").jsonPrimitive.int
    }

    /** Whether any write-back event for [issueId] names [field] — as a planned loss, or as the reason a re-read disagreed. */
    private fun anyEventNamesField(node: TwoNodeRig.Node, issueId: String, field: String): Boolean =
        node.writeBackEvents().any { event ->
            event.issueId == issueId && when (event) {
                is WriteBackEvent.PreFlight -> event.losses.any { it.field == field }
                is WriteBackEvent.Failed -> (event.failure as? WriteBackFailure.ReadBackMismatch)?.fields?.any { it.field == field } == true
                else -> false
            }
        }

    private fun importedCountFor(node: TwoNodeRig.Node, issueId: String): Int =
        // Every actual `bd import` invocation is preceded by exactly one
        // PreFlight (WriteBackApplier.applyOnce's KDoc), regardless of the
        // eventual outcome, so it is the right proxy for "an import ran".
        node.writeBackEvents().count { it is WriteBackEvent.PreFlight && it.issueId == issueId }

    /**
     * Clause 1's R1, end to end: the dialer (write-back ON) imposes the
     * listener's priority edit onto its OWN `bd` data — exactly ONE
     * `Imposed(X)` and exactly ONE importer invocation (computenet-6wc.1.6
     * clause 4) — and `bd show` on the dialer reports it. No further
     * `Imposed(X)` fires once the two nodes have converged.
     */
    @Test
    fun `R1 - the dialer imposes the listener's edit exactly once and bd show reports it`() {
        seedOnBoth(priority = "3")
        val listener = rigOrFail.startListener(writeBack = false)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        rigOrFail.await("both nodes agree on X before the edit") {
            listener.view()[x] == dialer.view()[x]
        }
        // X was seeded independently on both workspaces at equal priority, so
        // write-back's own first pass(es) settle as a NoOp (created_at is the
        // only field that could differ, and it is excluded from comparison —
        // computenet-6wc.1.6). Give that a few ticks before taking the
        // "before" baseline, so the growth measured below is attributable to
        // the deliberate edit rather than to startup noise.
        rigOrFail.await("write-back has processed X at least once") {
            dialer.writeBackEvents().any { it.issueId == x }
        }
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)
        val dialerLogBeforeEdit = dialer.logHead()

        rigOrFail.listenerWorkspace.run("update", x, "--priority", "1")
        listener.quiesce()

        rigOrFail.await("the dialer imposes X") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == x }
        }
        // Let the outcome settle for a few more ticks before counting, so a
        // repeated-imposition regression has a chance to show up here rather
        // than only in the separate clause-5 test below.
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        val imposedForX = dialer.writeBackEvents().filterIsInstance<WriteBackEvent.Imposed>().filter { it.issueId == x }
        imposedForX shouldHaveSize 1
        importedCountFor(dialer, x) shouldBe 1

        // `bd show` on the dialer genuinely reports the imposed value.
        bdShowPriority(dialer, x) shouldBe 1
        imposedForX.single().observed["priority"]?.jsonPrimitive?.int shouldBe 1

        val dialerLogAfterConvergence = dialer.logHead()
        // The imposition itself IS a Dolt commit on the dialer's workspace, so
        // the edit window must grow the log — that is the write actually
        // landing, not an echo.
        (dialerLogAfterConvergence.size - dialerLogBeforeEdit.size >= 1) shouldBe true

        // No further Imposed(X) after convergence, over several more ticks —
        // and, since computenet-6wc.3.3 wired the echo gate, no further Dolt
        // commit either: the commit the import produced is recognised as this
        // mirror's own and never re-projected.
        Thread.sleep(rigOrFail.pollIntervalMs() * 8)
        dialer.writeBackEvents().filterIsInstance<WriteBackEvent.Imposed>().filter { it.issueId == x } shouldHaveSize 1
        dialer.logHead() shouldBe dialerLogAfterConvergence
    }

    /**
     * Clause 5 at app level ("no repeated imposition once values agree"),
     * quiescence sanity ONLY — not feature computenet-6wc.3's echo
     * suppression clause, which owns whether a self-imposed dolt commit
     * re-triggers anything. This asserts the narrower, decided property:
     * once the applier's own bookkeeping (agreement, OR the `previouslyFailed`
     * cache for a row it cannot fully reconcile — see
     * [civictech.demo.beadsmirror.writeback.WriteBackApplier]'s class KDoc)
     * has settled for a given winner, further poll ticks touch NEITHER the
     * dialer's write-back event count NOR its `dolt_log` for X. If
     * `dolt_log` is NOT stable across those same ticks, that is exactly the
     * echo the sibling feature exists to close — reported here with counts,
     * not fixed and not asserted away.
     */
    @Test
    fun `clause 5 - no repeated imposition once the dialer agrees with the listener`() {
        seedOnBoth(priority = "3")
        val listener = rigOrFail.startListener(writeBack = false)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        rigOrFail.await("write-back has processed X at least once") {
            dialer.writeBackEvents().any { it.issueId == x }
        }
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        rigOrFail.listenerWorkspace.run("update", x, "--priority", "1")
        listener.quiesce()
        rigOrFail.await("bd show on the dialer reports priority 1") {
            bdShowPriority(dialer, x) == 1
        }
        rigOrFail.await("a write-back event for X named the priority field") {
            anyEventNamesField(dialer, x, "priority")
        }
        // Give the SAME winner (unchanged since the update above) a few more
        // ticks to have produced a stable outcome (Imposed, or the
        // previously-failed skip) before taking the convergence baseline.
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        // NOT the raw event count: every tick emits SOMETHING for X even once
        // stable (Skipped(Equal) on agreement, or Skipped(PreviouslyFailed)),
        // so a raw count grows forever by design and is
        // not evidence of repeated IMPOSITION. importedCountFor (one per
        // actual `bd import` attempt, via its PreFlight) is the property
        // clause 5 actually constrains: it must stop growing once the winner
        // stops changing.
        val importedCountAtConvergence = importedCountFor(dialer, x)
        val logAtConvergence = dialer.logHead()
        // Bounded sleep, not an await: this is a NEGATIVE assertion (nothing
        // further happens), which awaitUntil cannot express. Several poll
        // intervals' worth is enough for the applier to have run again if it
        // were going to.
        Thread.sleep(rigOrFail.pollIntervalMs() * 8)

        importedCountFor(dialer, x) shouldBe importedCountAtConvergence

        // Asserted since computenet-6wc.3.3, where this used to be a printed
        // report of "0 means no echo observed in this run": with the echo gate
        // wired, the dialer's own import commit is classified ECHO, mints
        // nothing, gossips nothing, and therefore provokes no further write —
        // the dialer's `dolt_log` is identical, commit for commit, across the
        // quiescent window.
        //
        // CAVEAT: this asserts that suppression did not BREAK quiescence; it is
        // not evidence that suppression is what produces it. An un-suppressed
        // echo re-mints the same value, so the planner's next pass is a NoOp and
        // adds no commit either. The discriminating evidence for suppression is
        // [EchoSuppressionTwoNodeTest]'s dot and classification assertions.
        dialer.logHead() shouldBe logAtConvergence
    }

    /** Feature computenet-6wc.1.5 clause 2: off by default, nothing about the mirror's existing behaviour changes. */
    @Test
    fun `off by default - no applier is built and the dialer keeps its own value after a peer edit`() {
        seedOnBoth(priority = "3")
        val listener = rigOrFail.startListener(writeBack = false)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = false)
        dialer.quiesce()

        dialer.app.mirrors.single().writeBackApplier shouldBe null

        rigOrFail.listenerWorkspace.run("update", x, "--priority", "1")
        listener.quiesce()
        rigOrFail.await("the dialer's fold picks up the listener's edit") {
            dialer.view()[x]?.get("priority")?.contains("1") == true
        }

        // The fold converged (gossip is unconditional); the dialer's OWN bd
        // data did not, because no applier ever ran.
        bdShowPriority(dialer, x) shouldBe 3
        dialer.writeBackEvents() shouldBe emptyList()
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
}
