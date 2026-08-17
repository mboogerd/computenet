package civictech.demo.beadsmirror.e2e

import civictech.cell.Propagate
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.baseline.ExportRow
import civictech.demo.beadsmirror.equality.Divergence
import civictech.demo.beadsmirror.equality.MirrorExportEquality
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Collections

/**
 * Task computenet-7em.1.3: feature computenet-7em.1's acceptance suite on the
 * **in-test** path — two [civictech.demo.beadsmirror.BeadsMirrorApp]s in one
 * JVM across a real `:wire` socket ([TwoNodeRig]), asserting feature rules 2
 * (cross-node visibility with no bd-level transfer), 4 (dot provenance, and no
 * re-minting on the gossip path) and 5 (a duplicate delivery changes nothing),
 * plus Design example 1 (wiring, baselines, and the late-join `pullServe`
 * catch-up).
 *
 * **One rig for the whole class, built once.** Each node costs a
 * `bd --sandbox init` plus a `dolt` start (seconds), so the four cases share
 * one rig — the bead's own cost note. That is affordable *because* nothing
 * here mutates shared state except the duplicate-delivery
 * case, whose entire claim is that it changes nothing; the cases are therefore order-independent
 * despite the shared fixture.
 *
 * **The fixture does the waiting, the tests do the asserting.** Everything
 * time-dependent — a baseline that is only observable before the peer exists,
 * a convergence — happens once in [setUp] and is *recorded*; each `@Test`
 * then states one rule against a recorded or a still-live value. A
 * convergence failure surfaces as a failed fixture naming both nodes'
 * diagnostics ([TwoNodeRig.await]), not as three unattributable timeouts.
 *
 * **Since task computenet-7em.2.1** it also carries the transport seam's smoke
 * case: the rig's wiring is injected ([TwoNodeRig]'s single
 * [civictech.demo.beadsmirror.MirrorTransport], shared by both nodes), and a
 * delta minted while [TwoNodeRig.partition] holds arrives once
 * [TwoNodeRig.heal] runs. Nothing in this file names a socket type.
 *
 * **Not here** (the task's non-goals): seeded two-sided mutation schedules and
 * the convergence assertions over partition/heal (computenet-7em.2's own
 * suite), pre-sync liveness framing
 * (computenet-7em.3), `bd dolt pull` re-baseline (computenet-7em.4), and the
 * two-**JVM** launch path, which is [TwoJvmMirrorTest] (computenet-7em.1.4).
 *
 * Guarded like every other real-`bd` test in this module: green-but-skipped
 * where `bd`/`dolt` are not on `PATH` (CI installs neither), a real gate on a
 * developer machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoNodeRigTest {

    private var rig: TwoNodeRig? = null

    /** The issue created in WL **before node L started** — so L folds it from its own `bd export` baseline. */
    private lateinit var idOnListener: String

    /** The issue created in WD **before node D started** — likewise, D's own baseline. */
    private lateinit var idOnDialer: String

    /** The issue created in WL **after both nodes were up and idle** — the live cross-node delta. */
    private lateinit var idAfterIdle: String

    /**
     * L's fold compared against WL's own `bd export` at the instant its
     * baseline completed and **before node D existed at all** — so no gossip
     * can have contributed to it. This is Design example 1's "each node serves
     * a fold equal to its own workspace's export", unrestricted.
     */
    private lateinit var listenerBaselineDivergences: List<Divergence>

    /**
     * D's fold compared against WD's own export, **restricted to WD's own
     * issue ids**.
     *
     * The restriction is not a weakening of convenience, and the difference
     * matters to whoever next reads this number: the later starter's baseline
     * completes *inside* `BeadsMirrorApp.start`, strictly before
     * `MirrorPeering.connect` opens its socket, so by the first instant a test
     * can read D's fold the peer's state may already have arrived. The
     * unrestricted comparison is therefore not observable on the second node —
     * only on the first, which is what [listenerBaselineDivergences] is. What
     * the restricted form still checks is the whole of D's own baseline: every
     * issue WD's export prints is in D's fold with every field equal.
     */
    private lateinit var dialerBaselineDivergences: List<Divergence>

    /** WD's `dolt_log` hashes captured while the rig was idle, before [idAfterIdle] was created. */
    private lateinit var dialerLogBeforeCreate: List<String>

    /** The issue created in WL **while the peering was partitioned** — the transport-seam smoke case. */
    private lateinit var idDuringPartition: String

    /**
     * Whether the partition actually held: `false` if D's fold had already
     * picked [idDuringPartition] up at the instant L finished folding it
     * locally, with the peering still severed.
     *
     * Recorded rather than asserted inline, per this class's fixture rule. It
     * is what makes the heal below evidence of anything — a partition that
     * never severed would let the delta through on its own and the arrival
     * after [TwoNodeRig.heal] would prove nothing about the transport seam.
     */
    private var partitionHeldTheDelta: Boolean = false

    /**
     * Every [TaggedMapDelta] node L's OR-map cell emitted from the moment the
     * rig went idle — captured by subscribing a plain observer to the cell's
     * own replication outlet, which is the delta the gossip path carries
     * verbatim.
     *
     * Synchronized: the poll thread propagates into it while the test thread
     * reads.
     */
    private val listenerDeltas: MutableList<TaggedMapDelta<MirrorKey, String>> =
        Collections.synchronizedList(mutableListOf())

    @BeforeAll
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")

        val rig = TwoNodeRig.create("bds2-in-test").also { this.rig = it }

        // --- node L, alone --------------------------------------------------
        // The issue exists in WL BEFORE L starts, so L folds it from its own
        // `bd export` baseline — and, crucially, before D exists, so the only
        // way it can ever reach D is the late-join pullServe catch-up rather
        // than a live delta.
        idOnListener = rig.listenerWorkspace.createIssue("created on the listener's workspace")
        val listener = rig.startListener()
        listener.quiesce()
        listenerBaselineDivergences =
            MirrorExportEquality.compare(listener.view(), listener.edgeView(), listener.exportNow())

        // --- node D joins ---------------------------------------------------
        idOnDialer = rig.dialerWorkspace.createIssue("created on the dialer's workspace")
        val dialer = rig.startDialer()
        dialer.quiesce()
        val dialerExport = dialer.exportNow()
        dialerBaselineDivergences = MirrorExportEquality.compare(
            dialer.view().restrictedTo(dialerExport),
            dialer.edgeView().filter { edge -> dialerExport.any { it.id == edge.issueId } }.toSet(),
            dialerExport,
        )

        // --- the mesh links, both ways --------------------------------------
        // Neither issue can reach the other node any way but the gossip mesh:
        // each was folded by its owner before the peer's socket existed, so it
        // predates every live delta and only the on-link catch-up can serve it.
        rig.await("the listener's pre-join issue reaches the dialer's fold") {
            dialer.view().containsKey(idOnListener)
        }
        rig.await("the dialer's pre-join issue reaches the listener's fold") {
            listener.view().containsKey(idOnDialer)
        }

        // --- the rig is now idle; observe L's outlet ------------------------
        // A plain observer on the replica's own outlet: this is the delta the
        // gossip path carries, before any peer sees it, which is what the
        // duplicate-delivery case has to replay.
        listener.projector.cell.outlet.subscribe(
            Use.fixed(Propagate<TaggedMapDelta<MirrorKey, String>> { listenerDeltas += it }, PortRef.generate()),
        )
        dialerLogBeforeCreate = dialer.logHead()

        // --- one live cross-node create -------------------------------------
        idAfterIdle = rig.listenerWorkspace.createIssue("created after the rig went idle")
        rig.await("the listener folds its own create") { listener.view().containsKey(idAfterIdle) }
        rig.await("the create gossips to the dialer's SERVED fold over the real socket") {
            dialer.servedStatus(idAfterIdle) == 200
        }

        // --- the transport seam: partition, mutate, heal ---------------------
        // Task computenet-7em.2.1's smoke case. Everything here goes through
        // TwoNodeRig's injected transport binding — the test names a partition,
        // never a socket — so the identical sequence runs over a future
        // transport with no edit to this file.
        rig.partition()
        idDuringPartition = rig.listenerWorkspace.createIssue("created while the peering was partitioned")
        // L folds its own create from its own feed; the peering is severed, so
        // nothing can have carried it to D at this instant.
        listener.quiesce()
        rig.await("the listener folds its own partitioned-window create") {
            listener.view().containsKey(idDuringPartition)
        }
        partitionHeldTheDelta = !dialer.view().containsKey(idDuringPartition)
        rig.heal()
        rig.await("the partitioned-window create reaches the dialer's SERVED fold after the heal") {
            dialer.servedStatus(idDuringPartition) == 200
        }
    }

    @AfterAll
    fun tearDown() {
        rig?.close()
    }

    private val rigOrFail: TwoNodeRig get() = checkNotNull(rig) { "the rig was never built" }

    /**
     * Design example 1: both nodes baseline to their own workspace's export,
     * and the mesh is linked **in both directions** — asserted behaviourally,
     * because `Replication`'s link bookkeeping is kernel-internal
     * (`Replication.linkCountAmong` is `internal` to `:kernel`) and a test in
     * this module cannot read it without a main-source change this task
     * forbids.
     *
     * The behaviour that stands in for it is strictly stronger than a link
     * count anyway: each node's pre-join issue is present on the *other* node.
     * Neither could have travelled as a live delta — both were folded by their
     * owner before the socket existed — so each arrival is the late-join
     * `pullServe` catch-up, once per direction, which is exactly "L's delta
     * outlet is linked to D's deltaInlet and vice versa".
     */
    @Test
    fun `both nodes baseline to their own export and the late-join catch-up links the mesh both ways`() {
        listenerBaselineDivergences shouldBe emptyList()
        dialerBaselineDivergences shouldBe emptyList()

        // each node still holds its own baseline, and now the peer's too
        // (plus idDuringPartition, which the fixture's partition/heal case adds
        // to WL and the heal carries to D — task computenet-7em.2.1)
        val everything = setOf(idOnListener, idOnDialer, idAfterIdle, idDuringPartition)
        rigOrFail.listener.view().keys shouldBe everything
        rigOrFail.dialer.view().keys shouldBe everything
    }

    /**
     * Task computenet-7em.2.1: the injected transport binding's
     * [TwoNodeRig.partition]/[TwoNodeRig.heal] work end to end — a delta minted
     * while the peering was severed is held back, and arrives once the peering
     * is healed, within the rig's bounded wait.
     *
     * Both halves are the fixture's ([partitionHeldTheDelta] and the `await`
     * that follows the heal); this states them. The negative half is the one
     * that makes the case evidence rather than decoration: without it, a
     * `partition()` that severed nothing would produce exactly the same green.
     * It is sound without a deadline of its own precisely because it is read
     * while severed — nothing can deliver the delta, so "not there yet" cannot
     * be a race with an in-flight arrival.
     *
     * What it deliberately does *not* assert is the convergence property
     * itself; equal folds under seeded two-sided schedules with partition are
     * feature computenet-7em.2's own suite.
     */
    @Test
    fun `a delta minted while the transport is partitioned arrives once it is healed`() {
        partitionHeldTheDelta shouldBe true

        rigOrFail.dialer.servedStatus(idDuringPartition) shouldBe 200
        rigOrFail.dialer.view().keys.contains(idDuringPartition) shouldBe true
        // and it crossed as a delta, not as bd-level state: WD never heard of it
        rigOrFail.dialer.exportNow().map { it.id }.contains(idDuringPartition) shouldBe false
    }

    /**
     * Feature rule 2: a `bd` mutation applied to node L's workspace reaches
     * node D's materialized map **with no bd-level transfer between the
     * workspaces**.
     *
     * The convergence half is awaited in the fixture, against D's *served*
     * HTTP fold — the rule says "materialized Map", and MirrorRoutes is where
     * a consumer reads it. The no-transfer half is asserted twice over, on
     * both `bd`-level surfaces of WD: its `dolt_log` is byte-identical to what
     * it was before the create, and its own `bd export` has never heard of the
     * issue. Only the cell delta crossed.
     */
    @Test
    fun `a create on the listener's workspace reaches the dialer's fold with no bd-level transfer`() {
        val dialer = rigOrFail.dialer

        dialer.servedStatus(idAfterIdle) shouldBe 200
        dialer.view().keys.contains(idAfterIdle) shouldBe true

        dialer.logHead() shouldBe dialerLogBeforeCreate
        dialer.exportNow().map { it.id }.contains(idAfterIdle) shouldBe false
    }

    /**
     * Feature rule 4 and `[24-TAG-01]`: a dot's `sourceId` names the Dolt
     * identity of the workspace whose feed minted it, and the gossip path
     * mints nothing.
     *
     * Stated on both nodes and in both directions, because a one-sided
     * assertion would also pass for a mirror that stamped *every* dot with one
     * constant source: [idAfterIdle] was minted by WL's feed and gossiped to
     * D, so its dots carry WL's source on **both** nodes; [idOnDialer] was
     * minted by WD's feed and gossiped to L, so its dots carry WD's on both.
     * A re-mint anywhere on the Replication/Peering/`:wire` path would show up
     * as the receiving node's own source on the receiving side.
     */
    @Test
    fun `every dot names the minting workspace's Dolt identity on both sides of the socket`() {
        val listener = rigOrFail.listener
        val dialer = rigOrFail.dialer
        listener.dotSourceId shouldNotBe dialer.dotSourceId

        val gossipedToDialer = dialer.dotsFor(idAfterIdle)
        gossipedToDialer.keys.shouldNotBeEmpty()
        gossipedToDialer.sourceIds() shouldBe setOf(listener.dotSourceId)

        val gossipedToListener = listener.dotsFor(idOnDialer)
        gossipedToListener.keys.shouldNotBeEmpty()
        gossipedToListener.sourceIds() shouldBe setOf(dialer.dotSourceId)

        // the owning side of each, for the same keys — identical dots, which is
        // what "verbatim" means
        listener.dotsFor(idAfterIdle) shouldBe gossipedToDialer
        dialer.dotsFor(idOnDialer) shouldBe gossipedToListener
    }

    /**
     * Feature rule 5: the delta node L emitted for [idAfterIdle] has already
     * arrived at D once over the socket; delivering it to D **again** — the
     * replay a reconnect produces — leaves D's fold unchanged.
     *
     * The re-delivery goes in at the `Replicable` delta seam
     * (`cell.deltaInlet`), which is the only door inbound gossip uses, so this
     * is the same arrival the socket would make and not a privileged shortcut.
     * "Unchanged" is asserted on the **served** fold byte-for-byte — the exact
     * response body of `GET /beads/issues`, string-equal before and after —
     * and on the edge `SetCell`'s membership.
     */
    @Test
    fun `a duplicate delivery of a gossiped delta leaves the peer's fold byte-identical`() {
        val dialer = rigOrFail.dialer

        val replayable = synchronized(listenerDeltas) {
            listenerDeltas.filter { delta -> delta.puts.keys.any { it.issueId == idAfterIdle } }
        }
        // the capture is part of the claim: replaying nothing would prove nothing
        replayable.shouldNotBeEmpty()

        val foldBefore: String = dialer.servedFold()
        val edgesBefore: Set<MirrorEdge> = dialer.edgeView()
        foldBefore.contains(idAfterIdle) shouldBe true

        replayable.forEach { dialer.projector.cell.deltaInlet.call.propagate(it) }

        dialer.servedFold() shouldBe foldBefore
        dialer.edgeView() shouldBe edgesBefore
    }

    /**
     * This view restricted to the issues [export] prints — see
     * [dialerBaselineDivergences] for why the later starter's baseline can
     * only be compared on its own ids.
     */
    private fun Map<String, Map<String, String>>.restrictedTo(export: List<ExportRow>):
        Map<String, Map<String, String>> {
        val own = export.map { it.id }.toSet()
        return filterKeys { it in own }
    }

    private fun Map<MirrorKey, Map<civictech.cell.Timestamp, String>>.sourceIds(): Set<java.util.UUID> =
        values.flatMap { dots -> dots.keys.map { it.sourceId } }.toSet()

    private companion object {
        fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
