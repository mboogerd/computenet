package civictech.demo.beadsmirror.e2e

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.projector.Classification
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

        rigOrFail.mutate(listener, "update", x, "--priority", "1")
        listener.quiesce()

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
        rigOrFail.mutate(listener, "update", x, "--priority", "1")
        listener.quiesce()
        rigOrFail.await("the dialer imposes X") {
            dialer.writeBackEvents().any { it is WriteBackEvent.Imposed && it.issueId == x }
        }
        rigOrFail.await("bd show on the dialer reports the imposed value") { bdShowPriority(dialer, x) == 1 }
        Thread.sleep(rigOrFail.pollIntervalMs() * 3)

        val dialerHighWater = highWaterCounter(dialer, dialer.dotSourceId, PRIORITY)
        val commitsBefore = dialer.logHead().toSet()

        // The DIALER's own workspace this time — a human `bd update`, not an
        // imposition.
        rigOrFail.mutate(dialer, "update", x, "--priority", "2")
        dialer.quiesce()

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
        bdShowPriority(dialer, x) shouldBe 2
    }

    // ---------------------------------------------------------------- helpers

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
    }
}
