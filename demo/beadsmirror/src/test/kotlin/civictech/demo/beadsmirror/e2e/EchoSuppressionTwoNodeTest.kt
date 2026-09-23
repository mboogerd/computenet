package civictech.demo.beadsmirror.e2e

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.projector.Classification
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.writeback.Provenance
import civictech.demo.beadsmirror.writeback.WriteBackEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.util.UUID

/**
 * Task computenet-6wc.3.3: feature computenet-6wc.3's loop, closed and
 * measured on the **real** two-node rig — real `bd`, real `dolt`, a real
 * `:wire` socket, **write-back on BOTH nodes** (which no test before this one
 * ran: [WriteBackTwoNodeTest] enables it on the dialer only).
 *
 * The unit-level halves are already pinned elsewhere — the gate's rule by
 * `projector.EchoGateTest`, the stamp by `writeback.ProvenanceTest` /
 * `WriteBackApplierTest`, both against hand-built records. What neither can
 * reach is the thing this feature actually claims: that a row the mirror's own
 * applier writes into `bd` comes back through `dolt_diff_issues` carrying the
 * token the applier announced, is recognised as that announcement, and
 * therefore mints nothing and gossips nothing. That round trip needs the real
 * three-process path, so it is asserted here.
 *
 * ## What each test owns
 *
 * - "the dialer's own imposition is stamped, recognised and never re-minted"
 *   — clause 1's `dolt_diff_issues` half, clause 2 whole, clause 4 whole
 *   (6wc.3-D8's 8-poll-interval quiescence window, on both nodes). Clause 4's
 *   `dolt_log`/`PreFlight` half is a **weak** discriminator — see the caveat
 *   beside those assertions; the clause-2 block is what would go red if
 *   suppression stopped working.
 * - "a genuine dialer edit on a stamped row is local and converges the
 *   listener" — clause 3, the no-false-positive regression, deliberately run on
 *   a row the applier has ALREADY stamped. That is the case decision 6wc.3-D5
 *   removed the BDS1 `cn_dot` registry for: the stamp persists in `bd`'s
 *   `metadata`, so the later genuine edit carries it on **both** diff sides,
 *   and any rule keyed on "have I seen this provenance before" drops it
 *   forever.
 *
 * ## Divergence from the bead's clause 2, recorded here because the file is
 * what gets read later
 *
 * Clause 2 as written asks that the listener's live dots for X's `priority` key
 * "never hold a dialer-sourced dot". That is **not satisfiable by any
 * implementation** in this fixture and is not what echo suppression means: X is
 * seeded independently on both workspaces, so the dialer's own start-time
 * baseline mints a dialer-sourced dot for `priority` (value `3`) and gossips it
 * to the listener before any edit happens. The property that actually
 * discriminates a suppressed echo from a re-projected one is that the dialer
 * mints **no new** dot for that key when its own write lands — asserted here as
 * "the dialer-sourced high-water counter for `priority` does not advance, on
 * either node" plus "the winning dot for `priority` is the listener's". Under a
 * gate that classifies everything LOCAL both flip: the echo commit is
 * projected, a fresh dialer dot outranks the listener's on counter, and it
 * gossips across. Reported on the bead.
 *
 * ## The load-sensitivity this test was filed for (bug computenet-rl2qx)
 *
 * "a genuine dialer edit on a stamped row is local and converges the listener"
 * timed out under full-module load while passing in isolation. **Measured
 * rate, darwin/arm64 (16 cores), bd 1.1.2 / dolt 2.2.3, 2026-09-18:
 * 2 failures in 20 runs (10%)** of this method, against 0/20 for
 * [WriteBackTwoNodeTest]'s `R1` and 0/20 for [TwoNodeRigTest]'s late-join
 * case — so the two sibling classes do NOT share it at that sample size.
 * The harness stood in for 20 full-module gates (~140 min) in ~15: the three
 * methods looping concurrently in three JVMs plus 10 CPU burners, load
 * average 11 -> 44 across the sample (`scripts/flake-loop/SuiteLoop.java`,
 * `--method`, one run per iteration).
 *
 * **What the failures actually were, read off the rig's own progress dump
 * rather than inferred.** Not the starved poll loop the bead's description
 * guessed at. In BOTH reproductions — one at the first imposition await, one
 * at the genuine-edit classification await — the timing-out node had
 * `checkpoint == dolt_log head`, `0 commit(s) behind head`,
 * `pollerFailure == null` and **0 records classified across the whole 30 s**,
 * while its write-back scheduler kept ticking (+20 events). The poll loops
 * were idle because their feeds were empty: the `bd update` the test had just
 * run, and which had exited 0, **had not become a commit in its workspace's
 * `dolt_log`**. `quiesce()` cannot catch that — "my checkpoint is at my head"
 * is vacuously true of a head that never moved — so the test went on to await
 * a convergence nothing had been asked to carry.
 *
 * Hence [TwoNodeRig.mutate] at every `bd` mutation site below: it returns only
 * once the mutation is a commit the mirror's feed can see. No assertion, value
 * or await condition of clauses 2/3 changed; what changed is that their
 * precondition is now established instead of assumed.
 *
 * **Three samples, in order, same harness and machine.** 2/20 unfixed; 3/20
 * with `mutate` alone — and the failures had MOVED: two of the three were the
 * final `bd show` on the dialer reading 1 while everything upstream had
 * converged to 2, an instantaneous read of a store whose own applier was
 * still mid-pass, which is why that line is now a bounded await like the
 * listener's beside it; **0/12 with both changes.** 0/12 is not a proof of
 * absence — at the ~10-15% per-run rate measured above, a 12-run sample comes
 * back clean about a quarter of the time by chance — so this is recorded as a
 * rate that dropped below what the slot could resolve, not as a flake shown
 * to be gone. The residual shape to watch for is run 2's third failure: the
 * genuine-edit classification await timing out with the dialer's checkpoint
 * at head and 0 records classified, i.e. `mutate`'s head-advance satisfied by
 * the applier's own import commit rather than by the edit (see
 * [TwoNodeRig.mutate]'s stated limit).
 *
 * **That residual recurred on Linux CI and was the whole of bug
 * computenet-d9kmx** (six build-test-fast failures, 2026-09-21..22, at "the
 * dialer imposes X" and at the genuine-edit LOCAL await). It is not a slow
 * loop. The edit is LOST before any await starts: the node's own applier
 * reverts it and bd folds the two writes into a single commit. That
 * product-side lost update is filed as computenet-oagbm. Every edit in this
 * class therefore goes through [editPriority]. It checks for a commit
 * carrying the edit, which a head-advance check cannot do, and re-issues an
 * edit that got none. No await budget changed.
 *
 * Guarded exactly like [WriteBackTwoNodeTest] and [TwoNodeRigTest]:
 * green-but-**skipped** where `bd`/`dolt` are not on PATH.
 */
class EchoSuppressionTwoNodeTest {

    private var rig: TwoNodeRig? = null
    private lateinit var x: String

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        rig = TwoNodeRig.create("bds4-echo")
        x = "echo-shared-${System.nanoTime()}"
    }

    @AfterEach
    fun tearDown() {
        rig?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /**
     * Clause 1's `dolt_diff_issues` half, clause 2, and clause 4.
     *
     * One edit, on the listener. The dialer's applier imposes it onto the
     * dialer's own `bd`; that import is a Dolt commit on the dialer's
     * workspace, and this asserts, in order: the commit carries the stamp the
     * applier said it wrote (clause 1); the dialer's gate recognised that exact
     * commit as an echo and nothing minted a dot for it on either node
     * (clause 2); and both stores, both folds and both `dolt_log`s then sit
     * still for 8 poll intervals (clause 4 / 6wc.3-D8).
     */
    @Test
    fun `the dialer's own imposition is stamped, recognised and never re-minted`() {
        seedOnBoth(priority = "3")
        val listener = rigOrFail.startListener(writeBack = true)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        rigOrFail.await("both nodes agree on X before the edit") { listener.view()[x] == dialer.view()[x] }
        rigOrFail.await("write-back has processed X at least once on both nodes") {
            dialer.writeBackEvents().any { it.issueId == x } && listener.writeBackEvents().any { it.issueId == x }
        }
        // Both were seeded at equal priority, so those first passes settle as
        // NoOps; let them, so what is measured below is attributable to the
        // deliberate edit and not to start-up noise.
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        // The dialer-sourced high-water mark for `priority` BEFORE anything is
        // imposed — its own baseline dot for the seeded value. See the class
        // KDoc: "no dialer-sourced dot at all" is unreachable here, "no NEWER
        // dialer-sourced dot" is the property.
        val dialerHighWaterOnDialer = highWaterCounter(dialer, dialer.dotSourceId, PRIORITY)
        val dialerHighWaterOnListener = highWaterCounter(listener, dialer.dotSourceId, PRIORITY)
        dialerHighWaterOnDialer.shouldNotBeNull()

        editPriority(listener, 1)

        rigOrFail.await("the dialer imposes X") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == x }
        }
        val imposed = dialer.writeBackEvents()
            .filterIsInstance<WriteBackEvent.Imposed>()
            .single { it.issueId == x }

        // ---- clause 1: the stamp is readable from dolt_diff_issues ----------
        // Located by the applier's OWN token rather than by the gate's verdict,
        // so this clause stands even if the gate is wrong: the token is unique
        // to one import invocation, so at most one row in the whole table can
        // carry it.
        rigOrFail.await("the dialer's dolt_diff_issues carries the imposed row") {
            dialer.diffRowsFor(x).any { stamp(it, TO_METADATA, Provenance.CN_ECHO) == imposed.cnEcho }
        }
        val rows = dialer.diffRowsFor(x)
        val stamped = rows.filter { stamp(it, TO_METADATA, Provenance.CN_ECHO) == imposed.cnEcho }
        stamped shouldHaveSize 1
        // Newest-first (TwoNodeRig.Node.diffRowsFor orders by dolt_log): the
        // imposition is the last thing that touched X on this workspace, which
        // is itself part of the quiescence claim.
        stamped.single() shouldBe rows.first()

        val stampedRow = stamped.single()
        // Structural reads of a JSON object Dolt re-orders the keys of — never
        // a comparison of rendered text (breakdown probe, 2026-09-18).
        stamp(stampedRow, TO_METADATA, Provenance.CN_DOT) shouldBe imposed.cnDot
        // …and that provenance names the fold state the row was imposed FROM:
        // the DOT_ORDER-max live dot over every key X holds on the dialer,
        // which — the echo having been suppressed — is still the winner now.
        imposed.cnDot shouldBe Provenance.render(winningDotAcrossKeys(dialer).shouldNotBeNull())
        // The commit WROTE the token: it was not already sitting on the row.
        stamp(stampedRow, FROM_METADATA, Provenance.CN_ECHO) shouldBe null

        // ---- clause 2: that commit is an echo, and mints nothing ------------
        val stampedCommit = (stampedRow.getValue("to_commit") as JsonPrimitive).content
        rigOrFail.await("the dialer classified its own imposition") {
            dialer.classifications().any { it.commitHash == stampedCommit && it.issueId == x }
        }
        val verdicts = dialer.classifications().filter { it.commitHash == stampedCommit && it.issueId == x }
        verdicts shouldHaveSize 1
        verdicts.single().classification shouldBe Classification.ECHO
        verdicts.single().cnEcho shouldBe imposed.cnEcho
        (dialer.echoCounts().first >= 1) shouldBe true

        rigOrFail.await("the dialer's fold reports the listener's value") {
            dialer.view()[x]?.get("priority")?.contains("1") == true
        }
        // The winning dot for `priority` is the LISTENER's on both nodes: the
        // dialer wrote the value into its own bd without ever re-minting it.
        winningDot(dialer, PRIORITY).shouldNotBeNull().sourceId shouldBe listener.dotSourceId
        winningDot(listener, PRIORITY).shouldNotBeNull().sourceId shouldBe listener.dotSourceId
        // …and no NEWER dialer-sourced dot exists for that key, on either node.
        highWaterCounter(dialer, dialer.dotSourceId, PRIORITY).atMost(dialerHighWaterOnDialer)
        highWaterCounter(listener, dialer.dotSourceId, PRIORITY).atMost(dialerHighWaterOnListener)

        // ---- clause 4: both stores agree, then everything sits still --------
        rigOrFail.await("both bd stores report the listener's value") {
            bdShowPriority(listener, x) == 1 && bdShowPriority(dialer, x) == 1
        }
        rigOrFail.await("both folds agree") { listener.view()[x] == dialer.view()[x] }
        // Let the last write-back pass on either node settle before the
        // baseline, so the negative window below measures quiescence rather
        // than the tail of convergence.
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        val listenerLog = listener.logHead()
        val dialerLog = dialer.logHead()
        val listenerPreFlights = preFlightCount(listener)
        val dialerPreFlights = preFlightCount(dialer)

        // A bounded sleep, not an await: this is a NEGATIVE assertion (nothing
        // further happens), which awaitUntil cannot express. 8 poll intervals
        // is decision 6wc.3-D8's constant.
        //
        // CAVEAT — this half is a WEAK discriminator, and nothing below should
        // be read as evidence that suppression is what keeps the logs still.
        // An un-suppressed echo re-mints a dot carrying the SAME value, so the
        // next planner pass still finds fold == export, emits NoOp, and runs no
        // import: no PreFlight, no Dolt commit. So these four assertions hold
        // with the gate disabled too (verified by review, 2026-09-18: under
        // `EchoGate.classify -> always LOCAL` the earlier dot assertions fire
        // first and these are never reached). What actually discriminates
        // suppression is the clause-2 block above — the classification, the
        // winning dot's source and the un-advanced high-water counters. These
        // are kept because clause 4 states quiescence as an observable the
        // feature must not break, not because they detect its absence.
        Thread.sleep(rigOrFail.pollIntervalMs() * QUIESCENT_POLL_INTERVALS)

        listener.logHead() shouldBe listenerLog
        dialer.logHead() shouldBe dialerLog
        preFlightCount(listener) shouldBe listenerPreFlights
        preFlightCount(dialer) shouldBe dialerPreFlights
        bdShowPriority(listener, x) shouldBe 1
        bdShowPriority(dialer, x) shouldBe 1
    }

    /**
     * Clause 3: after the dialer's row has been stamped by its own applier, a
     * genuine `bd update` on the dialer is classified LOCAL, mints a
     * dialer-sourced dot, and carries all the way to the listener's fold **and
     * its `bd` store** (the listener runs write-back too).
     *
     * The record this asserts on carries `cn_echo` on **both** diff sides — the
     * stamp the earlier imposition left behind — which is exactly the shape
     * decision 6wc.3-D5 says a provenance-memory rule misclassifies. The
     * assertion is written to require that shape rather than to tolerate it.
     */
    @Test
    fun `a genuine dialer edit on a stamped row is local and converges the listener`() {
        seedOnBoth(priority = "3")
        val listener = rigOrFail.startListener(writeBack = true)
        listener.quiesce()
        val dialer = rigOrFail.startDialer(writeBack = true)
        dialer.quiesce()

        rigOrFail.await("both nodes agree on X before the edit") { listener.view()[x] == dialer.view()[x] }
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        // Get the dialer's row stamped first — that is the precondition this
        // test is about.
        editPriority(listener, 1)
        rigOrFail.await("the dialer imposes X") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == x }
        }
        rigOrFail.await("bd show on the dialer reports the imposed value") { bdShowPriority(dialer, x) == 1 }
        padDialerPastListenersDot(listener, dialer)
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        val dialerHighWater = highWaterCounter(dialer, dialer.dotSourceId, PRIORITY)
        val commitsBefore = dialer.logHead().toSet()

        // The DIALER's own workspace this time — a human `bd update`, not an
        // imposition.
        editPriority(dialer, 2)

        // The genuine edit's record: LOCAL, and carrying the stamp the earlier
        // imposition left on the row (`cnEcho != null`). The dialer's own
        // echo commits are the only other new records, and those are ECHO, so
        // this filter cannot name one of them.
        rigOrFail.await("the dialer classifies its own genuine edit as LOCAL") {
            dialer.classifications().any {
                it.issueId == x && it.commitHash !in commitsBefore &&
                    it.classification == Classification.LOCAL && it.cnEcho != null
            }
        }
        val genuine = dialer.classifications().single {
            it.issueId == x && it.commitHash !in commitsBefore &&
                it.classification == Classification.LOCAL && it.cnEcho != null
        }

        // The stamp really is on BOTH sides of that commit's diff — i.e. this
        // commit did not write it, which is the whole discriminator.
        val row = dialer.diffRowsFor(x).single { commitOf(it) == genuine.commitHash }
        stamp(row, FROM_METADATA, Provenance.CN_ECHO).shouldNotBeNull() shouldBe
            stamp(row, TO_METADATA, Provenance.CN_ECHO).shouldNotBeNull()

        // It minted a dialer-sourced dot, which now wins the key.
        val winner = winningDot(dialer, PRIORITY).shouldNotBeNull()
        winner.sourceId shouldBe dialer.dotSourceId
        (winner.counter > (dialerHighWater ?: Long.MIN_VALUE)) shouldBe true

        // …and it reached the listener's fold AND its bd store (write-back on
        // the listener imposes it there).
        rigOrFail.await("the listener's fold converges to 2") {
            listener.view()[x]?.get("priority")?.contains("2") == true
        }
        rigOrFail.await("the listener's bd store follows") { bdShowPriority(listener, x) == 2 }
        // Bounded, like the listener's line above, rather than an instantaneous
        // read (bug computenet-rl2qx): the dialer's own applier can still be
        // mid-pass with the pre-edit winner when this line is reached — two of
        // the three failures measured on this method after the `mutate` fix
        // were exactly this read returning 1 while everything upstream of it
        // had already converged to 2. The asserted property is unchanged: the
        // dialer's OWN bd store must hold its own edit. Only the instant it is
        // sampled at became a window.
        rigOrFail.await("the dialer's bd store still holds its own edit") { bdShowPriority(dialer, x) == 2 }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * `bd update x --priority <priority>` on [node]'s own workspace. Returns
     * only once a commit **that carries the edit as a non-echo change** is in
     * that workspace's `dolt_log` and [node]'s poller has passed it. That is
     * the precondition every later await in this class relies on. When the
     * edit got no such commit (see below), it re-issues the edit, at most
     * [MAX_EDIT_ATTEMPTS] times.
     *
     * **Why [TwoNodeRig.mutate] alone was not enough (bug computenet-d9kmx).**
     * `mutate` returns once the head moves. On a write-back node the head
     * can move because of the node's OWN applier import, even when the edit
     * never became a commit at all. [TwoNodeRig.mutate] states that as its
     * limit. The CI failures this bug was filed for were that case. Traced
     * (computenet-btt30's feature reviewer, 2026-09-22): after the seed the
     * listener's `dolt_log` gained ONE commit, an ECHO record from its own
     * write-back import. There was no commit for the update and the fold
     * stayed at the seeded value. No loop was starved. The edit was gone
     * before any await began, so "the dialer imposes X" waited for a change
     * nobody had made.
     *
     * **The mechanism is a product-side lost update, filed as
     * computenet-oagbm and NOT fixed here.** The node's applier sees the
     * edit in `bd export` before its poller has ingested it, and imposes the
     * stale fold value over it. Meanwhile bd 1.1.2's auto-commit commits the
     * whole shared working set, so whichever of the two writers commits
     * first sweeps up the other's pending write. The edit then has no commit
     * of its own, and the only commit that touched the row wrote a fresh
     * `cn_echo`, which the gate correctly calls an echo. Direct probe,
     * darwin/arm64, bd 1.1.2 / dolt 2.2.3, load 7-12, reader loops running:
     * the edit was lost in 2 of 32 update-vs-import races, and one
     * committer absorbed the other's write in 3 of 42 races where the
     * import touched a different issue (scripts on computenet-oagbm).
     *
     * **What counts as carrying the edit:** a commit that is new since this
     * attempt began, whose `dolt_diff_issues` row for `x` has `to_priority
     * == priority`, and which left `cn_echo` unchanged (from side == to
     * side). The token rule matters. An import can commit the edit's value
     * together with a token it wrote itself, and the gate then correctly
     * drops that commit as an echo, so the edit is lost just the same.
     * Commit messages are deliberately NOT used: the probe showed the edit
     * arriving inside a `bd import` commit AND the import's revert arriving
     * inside a `bd: update` commit.
     *
     * **Why the retry does not hide anything this class tests.** Suppression
     * and the LOCAL verdict are still asserted on the commit that carries
     * the edit. A gate that misclassified such a commit would still fail
     * the awaits downstream, because a carrying commit ends the loop here
     * whatever the gate made of it. Re-issuing is idempotent, since the
     * value lost has always been the one being written. Every lost attempt
     * is printed to stderr with the node, attempt number and new commits.
     * That line lands in the JUnit XML's `system-err`, not in the Gradle
     * console (testLogging shows only PASSED/FAILED/SKIPPED events), and CI
     * uploads that XML only for failed or cancelled runs. So a GREEN CI run
     * does not show whether computenet-oagbm fired. Locally, `-i` or the XML
     * under `build/test-results/test/` does.
     *
     * The single read after [TwoNodeRig.Node.quiesce] is enough to decide
     * "no carrying commit". `bd` commits before it exits (measured on
     * computenet-3zr5k, recorded on [TwoNodeRig.mutate]), so every read that
     * starts after [TwoNodeRig.mutate] has returned already sees the edit's
     * commit, if there is one.
     */
    private fun editPriority(node: TwoNodeRig.Node, priority: Int) {
        val lost = mutableListOf<String>()
        repeat(MAX_EDIT_ATTEMPTS) { attempt ->
            val before = node.logHead().toSet()
            rigOrFail.mutate(node, "update", x, "--priority", priority.toString())
            node.quiesce()
            val newRows = priorityDiffRows(node).filter { commitOf(it) !in before }
            val carrying = newRows.filter {
                (it["to_priority"] as? JsonPrimitive)?.contentOrNull == priority.toString() &&
                    stamp(it, FROM_METADATA, Provenance.CN_ECHO) == stamp(it, TO_METADATA, Provenance.CN_ECHO)
            }
            if (carrying.isNotEmpty()) return
            val report = "attempt ${attempt + 1}/$MAX_EDIT_ATTEMPTS: `bd update $x --priority $priority` on the " +
                "${node.role} left no commit carrying it as a non-echo change (computenet-oagbm); new rows for " +
                "$x: ${newRows.map { "${commitOf(it)} priority->${it["to_priority"]}" }}"
            System.err.println("EchoSuppressionTwoNodeTest: $report")
            lost += report
        }
        throw AssertionFailedError(
            "the ${node.role}'s edit of $x to priority $priority was lost $MAX_EDIT_ATTEMPTS times running:\n" +
                lost.joinToString("\n") + "\n" + node.progressReport(null, TwoNodeRig.AWAIT_CONVERGENCE_MS),
        )
    }

    /**
     * Restores the commit-height lead that clause 3's "the dialer's genuine
     * edit wins the key" depends on, in case [editPriority] had to re-issue
     * the listener's edit.
     *
     * A dot's counter is its workspace's commit height (the high bits of
     * [DotMinter.counter]). [TaggedMapDelta.DOT_ORDER] compares counters
     * before sources. In the symmetric fixture the listener's edit mints at
     * height 9 and the dialer's genuine edit at height 10 (seed at 8, then
     * the imposition at 9), so the dialer wins. A lost first attempt costs the
     * listener one extra commit, the applier's revert. The retry then mints
     * at height 10, which ties the dialer's genuine edit, and the tie goes to
     * whichever source id sorts higher. Each id derives from its run's temp
     * workspace name, so that is the listener's in some runs (forced re-issue
     * with the padding disabled: 2 red of 5). Measured: 1 red in 32 loaded iterations,
     * `winner.sourceId` was the listener's, and it was the iteration whose
     * listener edit had been re-issued.
     *
     * The padding is idempotent `bd update x --priority 1`. It goes on the
     * dialer, which already holds 1, until the dialer's head is at least as
     * high as the listener's winning dot. Each pad commit changes only
     * `updated_at`, and it lands before `commitsBefore` is read, so no
     * assertion below can pick it up. In the unlost case the loop does not
     * run at all.
     */
    private fun padDialerPastListenersDot(listener: TwoNodeRig.Node, dialer: TwoNodeRig.Node) {
        val listenerDotHeight = winningDot(listener, PRIORITY).shouldNotBeNull().counter ushr
            (DotMinter.KEY_INDEX_BITS + DotMinter.ORDINAL_BITS)
        while (dialer.logHead().size - 1L < listenerDotHeight) {
            rigOrFail.mutate(dialer, "update", x, "--priority", "1")
        }
        dialer.quiesce()
    }

    /** `dolt_diff_issues` rows for `x` on [node]'s workspace, with the priority column [editPriority] needs. */
    private fun priorityDiffRows(node: TwoNodeRig.Node): List<Map<String, JsonElement>> {
        val quoted = "'" + x.replace("'", "''") + "'"
        return DoltSql(node.workspace.doltRoot).query(
            "select to_commit, to_priority, from_metadata, to_metadata from dolt_diff_issues where to_id = $quoted",
        )
    }

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

    /** Every actual `bd import` invocation is preceded by exactly one `PreFlight`, whatever its outcome. */
    private fun preFlightCount(node: TwoNodeRig.Node): Int =
        node.writeBackEvents().count { it is WriteBackEvent.PreFlight && it.issueId == x }

    private fun commitOf(row: Map<String, JsonElement>): String =
        (row.getValue("to_commit") as JsonPrimitive).content

    /**
     * [field] of the [side] (`from_metadata` / `to_metadata`) metadata object
     * of one `dolt_diff_issues` row, when it is present and a JSON string.
     *
     * Structural throughout: the row's metadata comes back as a [JsonObject]
     * whose key ORDER Dolt chooses (alphabetical, whatever was written), so
     * nothing here may compare rendered text.
     */
    private fun stamp(row: Map<String, JsonElement>, side: String, field: String): String? =
        ((row[side] as? JsonObject)?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The live dots this node holds for `x`'s [field] key. */
    private fun dots(node: TwoNodeRig.Node, field: String): Map<Timestamp, String> =
        node.dotsFor(x)[MirrorKey(x, field)].orEmpty()

    /** The [TaggedMapDelta.DOT_ORDER]-winning dot of `x`'s [field] key on [node]. */
    private fun winningDot(node: TwoNodeRig.Node, field: String): Timestamp? =
        dots(node, field).keys.maxWithOrNull(TaggedMapDelta.DOT_ORDER)

    /**
     * The DOT_ORDER-max live dot across EVERY key [node] holds for `x` — the
     * same quantity [Provenance.cnDotOf] stamps, recomputed from the cell's own
     * dot metadata rather than from anything the test bookkeeps.
     */
    private fun winningDotAcrossKeys(node: TwoNodeRig.Node): Timestamp? =
        node.dotsFor(x).values.flatMap { it.keys }.maxWithOrNull(TaggedMapDelta.DOT_ORDER)

    /** The highest counter [source] has minted for `x`'s [field] key as [node] sees it, or `null` if it holds none. */
    private fun highWaterCounter(node: TwoNodeRig.Node, source: UUID, field: String): Long? =
        dots(node, field).keys.filter { it.sourceId == source }.maxOfOrNull { it.counter }

    /**
     * "This high-water counter did not advance past [before]" — with `null` on
     * either side meaning "that source holds no dot for the key", which a
     * suppressed echo satisfies just as well as an unchanged counter does (a
     * later put by another source covers the older dot, so the dialer's
     * baseline dot legitimately disappears from the key).
     */
    private fun Long?.atMost(before: Long?) {
        if (this == null) return
        check(before != null) { "a dialer-sourced dot for the key appeared where there was none: $this" }
        (this <= before) shouldBe true
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

    private companion object {
        const val PRIORITY: String = "priority"
        const val FROM_METADATA: String = "from_metadata"
        const val TO_METADATA: String = "to_metadata"

        /** Decision 6wc.3-D8: the quiescence window, in this rig's poll intervals. */
        const val QUIESCENT_POLL_INTERVALS: Long = 8

        /**
         * [editPriority]'s attempt cap. The lost-edit rate measured on
         * computenet-oagbm was a few percent per race, in a probe that raced
         * on purpose. Three consecutive losses therefore mean something other
         * than that race, and the test fails naming each attempt.
         */
        const val MAX_EDIT_ATTEMPTS: Int = 3
    }
}
