package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryAnnounce
import civictech.cell.wire.WireCodec
import civictech.nature.ContractRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-f7h.6.1` — the **reconciliation** half of F6 (epic
 * computenet-f7h / MEM1, feature computenet-f7h.6): what a split brain costs,
 * and what it must never cost.
 *
 * Three examples, one per epic section:
 *
 * - **§5.1 split brain** ([MEM1-12], [MEM1-21], [MEM1-31], and by consequence
 *   [MEM1-08]/[MEM1-13]) — two arms. Arm (i) is the full symmetric split
 *   `{A} | {B, C}`: both sides accept writes, and on the heal the lower epoch
 *   loses completely — one canonical mark at every peer, the ex-leader's state
 *   REPLACED by the winner's baseline, and every write it made under the stale
 *   epoch surfaced as a divergent write rather than merged. Arm (ii) is the
 *   *bridged* topology, which is the only one where the epoch fence itself is
 *   observable against a live delta.
 * - **§5.3 false-positive failover** ([MEM1-08], [MEM1-17], [MEM1-21],
 *   [MEM1-22]) — the leader never actually failed. The cost is one unnecessary
 *   failover; it is emphatically NOT a lost or duplicated write.
 * - **§5.4 epoch regression** ([MEM1-06], [MEM1-09], [MEM1-22]) — a long-
 *   partitioned peer returns holding a stale mark and re-announces it while a
 *   duplicated frame redelivers the canonical one. Nothing on the majority
 *   side moves; the returning peer folds exactly once.
 *
 * ## Fault models (f7h.6-D3 — each test's KDoc names its own)
 *
 * Two are used here and they are not interchangeable:
 *
 * - **Symmetric partition** — [Peering.Loopback.partition], which is
 *   `closeInstance()`: BOTH mirrors detach, so each side runs
 *   `unpublishRemotes` for the other. It is a *park*, not a loss: nothing
 *   in flight is destroyed, and [Peering.Loopback.heal] replays a full
 *   catch-up in both directions.
 * - **Asymmetric drop** — a hand-rolled [Peering.FrameInterpose] on ONE
 *   direction returning `emptyList()`. This one genuinely DESTROYS frames, so
 *   no test below issues a write across a dropping direction: a write lost
 *   that way is lost by the fault, not by the election, and would prove
 *   nothing about leadership.
 *
 * ## Determinism (f7h.6-D1)
 *
 * One unseeded [SimulationController] per test, i.e. a single deterministic
 * interleaving. The seeded dual-claim sweep is computenet-f7h.6.2's business,
 * not this file's. Every peer runs
 * `LeaderElection.EpochClaim(DetectionWindow(2))` except §5.4, which is
 * entirely `Manual` — nothing there elects, the property is the fold's.
 * With a two-observation window a partition is observation 1 on each survivor
 * (synchronous, inside `partition()`) and one explicit
 * [SingleWriterReplication.observe] is observation 2, so exactly the peer the
 * test calls `observe()` on claims.
 *
 * ## Convergence (f7h.6-D2)
 *
 * Direct state comparison across live replicas (`total`, `currentEpoch`) at
 * quiescence. No fold harness, and nothing is attached to any leader's
 * `deltaOutlet` through `linking` — that would be a second attachment, which
 * [ShippingLinkIdempotenceTest] pins at exactly one. The dead-letter recorder
 * below is a plain `subscribe(Use.fixed(...))` on the HOST's outlet and fires
 * no baseline (ParkedWriteReleaseTest's precedent).
 *
 * ## What this file deliberately does not re-pin
 *
 * The surfacing MECHANICS under a manual designation ([MEM1-13]/[MEM1-28]/
 * [MEM1-21] — [DivergentWriteSurfacingTest] examples 4-6), duplicate/reorder/
 * heal-replay inertness at one registry ([LeaderMarkAnnounceTest],
 * [LeaderMarkFoldTest]) and the step-down unlink ([StepDownTest]) are already
 * owned elsewhere. What is new here is the COMPOSITION across three or four
 * peers with the claim minted by the DETECTOR rather than by `designateLeader`.
 */
class SplitBrainReconciliationTest {

    // --------------------------------------------------------------- fixture

    /**
     * One peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Copied inline from [LeaderElectionTest]'s and
     * [DivergentWriteSurfacingTest]'s private `Peer` — the established idiom
     * in this package (every F3/F4/F5 test copied its own) rather than
     * widening a fixture another task owns.
     *
     * [election] is nullable and null means "construct the way every pre-F4
     * call site does" — `SingleWriterReplication(registry)` with no third
     * argument — which is what §5.4's Manual peers use.
     */
    private class Peer(
        controller: SimulationController,
        election: LeaderElection? = null,
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication =
            if (election == null) SingleWriterReplication(registry)
            else SingleWriterReplication(registry, election = election)

        /** Every adopted fold on this registry — local designation, claim, or mirrored announcement. */
        var leaderMarkFires = 0
            private set

        init {
            registry.onLeaderMark { leaderMarkFires++ }
        }

        fun replica(
            logicalId: UUID,
            instanceId: Long,
            mark: LeaderMark,
        ): SingleWriterReplicationTest.SwCounterCell =
            SingleWriterReplicationTest.SwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }

        fun ops(replica: SingleWriterReplicationTest.SwCounterCell): SingleWriterReplicationTest.SwCounterOps =
            (HostedCellProxy.create(
                replica.ref,
                registry,
                SingleWriterReplicationTest.WriteInletHolder::class.java,
            ) as SingleWriterReplicationTest.WriteInletHolder).writeInlet.call

        /**
         * The dead-letter idiom of [StepDownTest]'s `Rig.deadLetters` — a
         * plain subscription on the HOST's outlet, unrelated to any replica
         * port, so it cannot be the second attachment
         * [ShippingLinkIdempotenceTest] forbids.
         */
        fun deadLetters(): List<DeadLetter> {
            val seen = mutableListOf<DeadLetter>()
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            seen += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            return seen
        }
    }

    /** The divergent-write tuples an [SingleWriterReplication.onDivergentWrite] handler saw. */
    private data class Surfaced(val logicalId: UUID, val fenced: Long, val canonical: Long, val payload: Any?)

    /** Only the divergent-write records — the outlet also carries anything else the host reports. */
    private fun List<DeadLetter>.divergent(): List<DeadLetter> =
        filter { it.description.startsWith("single-writer divergent write:") }

    private fun DeadLetter.stamped(): Stamped<*> =
        (invocation as HostedPortInvocation).invocation.args.single() as Stamped<*>

    private val leaderMarkedId: Long =
        ContractRegistry.idsOf(RegistryAnnounce::class.java.getMethod("leaderMarked", LeaderMark::class.java))!!.second

    /** One frame's identity, as [Counting] records it. */
    private data class FrameId(val contractId: Long, val methodId: Long)

    /**
     * Records every frame that crosses one direction and passes it through
     * unchanged. Copied from [LeaderElectionTest], where it is private and in
     * another task's claim.
     */
    private open class Counting : Peering.FrameInterpose {
        protected val frames = CopyOnWriteArrayList<FrameId>()

        override fun apply(frame: ByteArray): List<ByteArray> {
            record(frame)
            return listOf(frame)
        }

        protected fun record(frame: ByteArray) {
            val decoded = WireCodec.decodeFrame(frame).frame
            frames += FrameId(decoded.contractId, decoded.methodId)
        }

        fun count(methodId: Long): Int = frames.count { it.methodId == methodId }
        fun reset() = frames.clear()
    }

    /**
     * A one-direction DROP that can be toggled between connection instances.
     *
     * The toggle survives [Peering.Loopback.heal] because `Peering.loopback`
     * bakes the interposers into the egress subscription once, at
     * construction, and `open()` reuses the SAME `aToB`/`bToA` sinks on every
     * heal — so this object stays in force across partition/heal cycles.
     */
    private class Gate : Counting() {
        var dropping = false

        override fun apply(frame: ByteArray): List<ByteArray> {
            record(frame)
            return if (dropping) emptyList() else listOf(frame)
        }
    }

    /**
     * A one-direction DUPLICATOR, toggled the same way and for the same
     * reason.
     *
     * It records once per **delivered copy**, not once per `apply` — the
     * quantity §5.4 needs is how many times the mark reached D, and a
     * send-counting version reports 1 for a frame delivered twice.
     */
    private class Duplicating : Counting() {
        var duplicating = false

        override fun apply(frame: ByteArray): List<ByteArray> {
            val out = if (duplicating) listOf(frame, frame.copyOf()) else listOf(frame)
            out.forEach { record(it) }
            return out
        }
    }

    // ============================================================ §5.1 arm (i)

    /**
     * **Fault model: symmetric partition** ([Peering.Loopback.partition] on
     * both of A's legs — a park, not a frame loss).
     *
     * The epic's §5.1 verbatim: three `EpochClaim(DetectionWindow(2))` peers,
     * A leading at `(1, aRef)`; a full split `{A} | {B, C}`; B's window closes
     * on one explicit `observe()` and it claims `(2, bRef)`; BOTH sides then
     * accept writes — A because it still believes it leads, B because it now
     * legitimately does. On the heal the lower epoch loses completely.
     *
     * ## Why the fence, not the unlink, is what protects C here
     *
     * The epic's clause reads "every delta A shipped under epoch 1 after the
     * split is absent from C's state". A's deltas 5 and 6 never REACH C at
     * all — A's outbound shipping links were unlinked by `onUnpublish` at the
     * partition and nothing rebuilt them while A led, so that half is true by
     * construction and measures nothing. What DOES cross under epoch 1 is the
     * stale BASELINE A ships during the heal: `Peering.announceTo` announces
     * refs BEFORE marks, so A's registry learns bRef/cRef one notification
     * ahead of the superseding mark, A — still believing it leads — runs
     * `onPeerPublished` → `shipTo`, and the `onLinked` hook emits
     * `Stamped(1, 12, baseline = true)`. Only then does A fold `(2, bRef)`.
     * The receiver is already at epoch 2, so `Stamped.applyTo` fences it.
     *
     * MEASURED (see the assertion's own comment): that happens exactly once,
     * at **B** — not once per rebuilt link. A folds and steps down while
     * draining B's catch-up, so by the time it learns cRef it no longer hosts
     * the leaderRef and never builds an A→C link at all. C therefore receives
     * no epoch-1 unit to fence in this arm; the fence against a live delta at
     * C is arm (ii)'s subject, which is why both arms exist.
     */
    @Test
    fun `5_1 split brain — the lower epoch loses completely and its writes surface as divergent`() {
        val controller = SimulationController()
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val p = Peer(controller, posture)
        val q = Peer(controller, posture)
        val r = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        // replicas FIRST, peering second: `replicate` registers the local
        // replica and THEN folds its mark, so a replica spawned after its peer
        // already mirrored the mark would fold a rejected duplicate and
        // silently get no role (LeaderMarkAnnounceTest's reason).
        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)

        val ab = Peering.loopback(p.side, q.side)
        val ac = Peering.loopback(p.side, r.side)
        Peering.loopback(q.side, r.side)
        controller.runToIdle()

        val letters = p.deadLetters()
        val surfaced = mutableListOf<Surfaced>()
        p.replication.onDivergentWrite { logicalId, fenced, canonical, payload ->
            surfaced += Surfaced(logicalId, fenced, canonical, payload)
        }

        // A pre-split write, so A's later baseline adoption is observable as a
        // REPLACEMENT rather than a coincidence with zero.
        p.ops(a).increment(1)
        controller.runToIdle()
        a.total shouldBe 1L
        b.total shouldBe 1L
        c.total shouldBe 1L

        // ---- the split. Observation 1 on each survivor, synchronously.
        ab.partition()
        ac.partition()
        controller.runToIdle()

        q.replication.missCount(id) shouldBe 1
        r.replication.missCount(id) shouldBe 1
        withClue("A witnessed its FOLLOWERS leave, not its leaderRef — that is not a leader failure") {
            p.replication.missCount(id) shouldBe 0
        }

        // ---- observation 2, on B only: the claim is folded before this returns
        q.replication.observe()
        q.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        controller.runToIdle()
        r.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        withClue("a mirrored mark is not relayed onward, so no path carries B's mark to A") {
            p.replication.leaderOf(id) shouldBe mark1
        }

        // ---- both sides accept writes
        p.ops(a).increment(5)
        p.ops(a).increment(6)
        q.ops(b).increment(7)
        controller.runToIdle()

        a.leading shouldBe true
        a.total shouldBe 12L
        a.realWrites shouldBe 3
        b.leading shouldBe true
        b.total shouldBe 8L
        c.total shouldBe 8L

        val cFencedBefore = c.fencedDeltas
        val cReceivedBefore = c.receivedDeltas
        val bFencedBefore = b.fencedDeltas
        val bRealWritesBefore = b.realWrites

        // ---- heal
        ab.heal()
        ac.heal()
        controller.runToIdle()

        // one canonical mark everywhere
        p.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        q.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        r.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)

        // A is a forwarding follower whose state was REPLACED by B's baseline
        a.leading shouldBe false
        a.becomeFollowerCalls shouldBe 1
        a.baselinesAdopted shouldBe 1
        a.total shouldBe 8L
        b.total shouldBe 8L
        c.total shouldBe 8L
        a.currentEpoch shouldBe 2L

        // A's post-split writes surfaced once each, in production order
        val divergent = letters.divergent()
        divergent shouldHaveSize 2
        divergent.map { it.stamped() } shouldBe listOf(Stamped(1L, 5L), Stamped(1L, 6L))
        divergent.forEach { dl ->
            withClue("dead letter: ${dl.description}") {
                dl.description shouldContain id.toString()
                dl.description shouldContain "fencedEpoch=1"
                dl.description shouldContain "canonicalEpoch=2"
                (dl.invocation as HostedPortInvocation).cellRef shouldBe aRef
                (dl.invocation as HostedPortInvocation).portName shouldBe "deltaInlet"
            }
        }
        surfaced shouldBe listOf(Surfaced(id, 1L, 2L, 5L), Surfaced(id, 1L, 2L, 6L))

        // surfacing is once-only: quiescing again adds nothing
        controller.runToIdle()
        letters.divergent() shouldHaveSize 2

        // The epoch-1 unit that DOES cross during the heal — A's stale
        // baseline, refs-before-marks — is fenced, and changes nothing.
        val cFencedDelta = c.fencedDeltas - cFencedBefore
        val bFencedDelta = b.fencedDeltas - bFencedBefore
        println(
            "5.1 arm (i) MEASURED: stale-baseline fences on heal — at C: $cFencedDelta, at B: $bFencedDelta",
        )
        // MEASURED 2026-09-10 on this base: 1 at B, 0 at C — NOT the "one per
        // rebuilt A→peer link" the breakdown predicted, and the difference is
        // the whole content of this pin.
        //
        // Under this interleaving A re-links only to B before it folds. Both
        // heals queue their catch-ups and one `runToIdle` drains them: A learns
        // bRef, still believes it leads, ships the stale
        // `Stamped(1, 12, baseline = true)` to B — which is at epoch 2 and
        // fences it — and then folds `(2, bRef)` and steps down. By the time
        // A's registry learns cRef, A is a follower: `onPeerPublished(cRef)`
        // finds the folded leaderRef is bRef, which A does not host, so no
        // A→C link is built and nothing epoch-1 is ever emitted at C.
        //
        // So the epic's §5.1 fence clause holds at C by CONSTRUCTION here
        // (nothing arrives), not by fencing. The fence itself is measured by
        // the bridged arm below, where A demonstrably still reaches C.
        withClue("the stale baseline A ships to B before folding is fenced there, changing nothing") {
            bFencedDelta shouldBe 1
        }
        withClue("A has already stepped down by the time it learns cRef, so C receives no epoch-1 unit at all") {
            cFencedDelta shouldBe 0
            (c.receivedDeltas - cReceivedBefore) shouldBe 0
        }

        // ---- and A now forwards: a write through the ex-leader lands ONCE at
        // the winner and ships to everyone.
        p.ops(a).increment(9)
        controller.runToIdle()
        (b.realWrites - bRealWritesBefore) shouldBe 1
        a.realWrites shouldBe 3
        a.total shouldBe 17L
        b.total shouldBe 17L
        c.total shouldBe 17L
    }

    // =========================================================== §5.1 arm (ii)

    /**
     * **Fault model: symmetric partition of ONE leg** (`A–B`), with C bridging
     * — A stays reachable from C throughout.
     *
     * This is the only topology in which the epoch fence is observable against
     * a live, non-baseline DELTA: A can still reach C after C has folded epoch
     * 2, because the A–C link was never torn down. It is
     * [DivergentWriteSurfacingTest] example 6's topology under a
     * DETECTOR-minted claim, plus the fence counts that example does not read.
     */
    @Test
    fun `5_1 bridged — a live epoch-1 delta reaching a folded peer is fenced, and surfaces on the heal`() {
        val controller = SimulationController()
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val p = Peer(controller, posture)
        val q = Peer(controller, posture)
        val r = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)

        val ab = Peering.loopback(p.side, q.side)
        Peering.loopback(p.side, r.side)
        Peering.loopback(q.side, r.side)
        controller.runToIdle()

        val letters = p.deadLetters()
        val surfaced = mutableListOf<Surfaced>()
        p.replication.onDivergentWrite { logicalId, fenced, canonical, payload ->
            surfaced += Surfaced(logicalId, fenced, canonical, payload)
        }

        p.ops(a).increment(1)
        controller.runToIdle()
        listOf(a, b, c).forEach { it.total shouldBe 1L }

        // ---- only the A–B leg goes down. Observation 1 at B.
        ab.partition()
        controller.runToIdle()
        q.replication.missCount(id) shouldBe 1

        // ---- observation 2 at B. reachable = {cRef}, so the claim is minted.
        q.replication.observe()
        q.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        controller.runToIdle()
        r.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        withClue("Peering announces on onLocalLeaderMark only, so C does not relay B's mark to A") {
            p.replication.leaderOf(id)!!.epoch shouldBe 1L
        }

        // ---- A, still believing it leads, writes over the LIVE A–C link
        val cReceivedBefore = c.receivedDeltas
        val cFencedBefore = c.fencedDeltas
        p.ops(a).increment(5)
        controller.runToIdle()

        a.total shouldBe 6L
        (c.receivedDeltas - cReceivedBefore) shouldBe 1
        withClue("C is at epoch 2; Stamped.applyTo refuses an epoch-1 delta") {
            (c.fencedDeltas - cFencedBefore) shouldBe 1
        }
        c.total shouldBe 1L
        c.total shouldBe b.total

        // ---- heal: A folds epoch 2, steps down, and surfaces exactly the
        // write it made while armed.
        ab.heal()
        controller.runToIdle()

        p.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        a.leading shouldBe false
        a.becomeFollowerCalls shouldBe 1
        letters.divergent().map { it.stamped() } shouldBe listOf(Stamped(1L, 5L))
        surfaced shouldBe listOf(Surfaced(id, 1L, 2L, 5L))
        a.total shouldBe b.total
        b.total shouldBe 1L
        c.total shouldBe 1L
    }

    // ================================================================== §5.3

    /**
     * **Fault model: asymmetric drop** — a toggleable one-direction
     * [Peering.FrameInterpose] on A→B returning `emptyList()`, layered over a
     * symmetric partition + heal. A stays alive and reachable from C
     * throughout; only B's *view* of A is broken.
     *
     * A one-direction drop ALONE arms nothing: arming needs an `onUnpublish`
     * naming the leaderRef, and a dropped frame unpublishes nobody. So the
     * asymmetry is constructed as partition → gate closed → heal: B never
     * learns aRef back (A's catch-up is dropped), while A does learn bRef back
     * (B's catch-up passes). That asymmetric membership is ASSERTED as a
     * precondition below — if the construction stops producing it, this test
     * fails loudly there rather than silently measuring something else.
     *
     * **No write is issued while the gate is dropping.** A frame drop is
     * destruction, so a write forwarded A→B during that window would be lost
     * by the FAULT, not by the election, and would prove nothing.
     *
     * **The cost of a false positive is a failover, not correctness.** This
     * test records the failover (one fold at each peer, one promotion) and
     * pins that no write is lost or duplicated across it. If a false positive
     * ever costs correctness, that is a `concord/corpus/DISPUTES.md` entry
     * (F7) — not a retuned detection window.
     */
    @Test
    fun `5_3 false-positive failover costs one failover and no write`() {
        val controller = SimulationController()
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val p = Peer(controller, posture)
        val q = Peer(controller, posture)
        val r = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)

        val gate = Gate()          // A → B, droppable
        val bToA = Counting()      // B → A, proves B's mark crossed
        val ab = Peering.loopback(p.side, q.side, interposeAToB = gate, interposeBToA = bToA)
        Peering.loopback(p.side, r.side)
        Peering.loopback(q.side, r.side)
        controller.runToIdle()

        val letters = p.deadLetters()

        // one write while the topology is whole
        p.ops(a).increment(1)
        controller.runToIdle()
        listOf(a, b, c).forEach { it.total shouldBe 1L }

        // ---- construct the asymmetry
        ab.partition()
        controller.runToIdle()
        q.replication.missCount(id) shouldBe 1

        gate.dropping = true
        ab.heal()
        controller.runToIdle()

        withClue("PRECONDITION: A's catch-up (published aRef, and its mark) was dropped on A→B") {
            (aRef in q.registry.replicasOf(id)) shouldBe false
        }
        withClue("PRECONDITION: B's catch-up reached A, so A's membership kept bRef") {
            (bRef in p.registry.replicasOf(id)) shouldBe true
        }
        withClue("bRef's return disarmed A's divergence record; nothing was recorded anyway") {
            q.replication.missCount(id) shouldBe 1
        }

        gate.reset()
        bToA.reset()
        val firesBefore = Triple(p.leaderMarkFires, q.leaderMarkFires, r.leaderMarkFires)

        // ---- B's window closes on a leader that never failed
        q.replication.observe()
        q.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        controller.runToIdle()

        p.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        r.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        withClue("B's mark crossed B→A, which the gate does not touch") {
            bToA.count(leaderMarkedId) shouldBeGreaterThanOrEqual 1
        }
        withClue("[MEM1-08]: a claim is never rolled back — A steps down on folding it") {
            a.leading shouldBe false
            a.becomeFollowerCalls shouldBe 1
        }
        b.leading shouldBe true
        b.becomeLeaderCalls shouldBe 1
        withClue("A made no write while armed, so there is nothing divergent to surface") {
            letters.divergent().shouldBeEmpty()
        }

        // ---- the cost, recorded rather than hidden: exactly one failover
        val aFires = p.leaderMarkFires - firesBefore.first
        val bFires = q.leaderMarkFires - firesBefore.second
        val cFires = r.leaderMarkFires - firesBefore.third
        aFires shouldBe 1
        bFires shouldBe 1
        cFires shouldBe 1

        // ---- reopen the gate: B learns aRef and ships it the baseline
        gate.dropping = false
        ab.heal()
        controller.runToIdle()

        (aRef in q.registry.replicasOf(id)) shouldBe true
        val bShips = q.replication.shipCountAmong(setOf(aRef, bRef, cRef))
        val aShips = p.replication.shipCountAmong(setOf(aRef, bRef, cRef))
        println("5.3 MEASURED: shipCountAmong at B = $bShips, at A = $aShips")
        bShips shouldBe 2
        aShips shouldBe 0

        val cReceivedBefore = c.receivedDeltas
        val aReceivedBefore = a.receivedDeltas

        // ---- three writes with power-of-two amounts. With the APPLIED count
        // fixed at three, the only multiset of three amounts drawn from
        // {1, 2, 4} that sums to 7 is {1, 2, 4} itself — so `total == 7`
        // everywhere IS "every write id applied exactly once". A lost write
        // changes the sum; a duplicated one changes the sum AND the count.
        r.ops(c).increment(2)
        p.ops(a).increment(4)
        controller.runToIdle()

        listOf(a, b, c).forEach { it.total shouldBe 7L }
        (a.realWrites + b.realWrites + c.realWrites) shouldBe 3
        a.realWrites shouldBe 1
        b.realWrites shouldBe 2
        c.realWrites shouldBe 0
        letters.divergent().shouldBeEmpty()

        val cReceivedDelta = c.receivedDeltas - cReceivedBefore
        val aReceivedDelta = a.receivedDeltas - aReceivedBefore
        println("5.3 MEASURED: follower shipments — C received $cReceivedDelta, A received $aReceivedDelta")
        // MEASURED 2026-09-10 on this base: two each — one shipment per write.
        // A's rebuild baseline is NOT among them: it was delivered during the
        // `heal()` + `runToIdle` above, i.e. before these counters were read.
        aReceivedDelta shouldBe 2
        cReceivedDelta shouldBe 2

        println("5.3 false positive: cost = 1 unnecessary failover, 0 lost, 0 duplicated")
    }

    // ================================================================== §5.4

    /**
     * **Fault model: symmetric partition** of D from all three of A/B/C, plus
     * a toggleable one-direction DUPLICATOR on B→D during the heal. Nothing
     * here elects — every peer is `LeaderElection.Manual` (the production
     * default, constructed with no `election` argument) and every mark is
     * driven by `designateLeader`. The property under test belongs to the
     * FOLD, not to the detector.
     *
     * D is partitioned while holding `(2, bRef)` and the majority side walks
     * on to `(5, bRef)`. On the heal D re-announces its stale mark to all
     * three peers — which the fold rejects as not strictly greater — while
     * `(5, bRef)` reaches D from A, from C, and TWICE from B. D folds it
     * exactly once.
     */
    @Test
    fun `5_4 a returning peer's stale mark is inert and the canonical mark folds exactly once`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller)
        val s = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val dRef = CellRef(id, 3)
        val abc = setOf(aRef, bRef, cRef)
        val all = setOf(aRef, bRef, cRef, dRef)
        val mark1 = LeaderMark(id, 1, aRef)

        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)
        val d = s.replica(id, 3, mark1)

        val bd = Duplicating()
        Peering.loopback(p.side, q.side)
        Peering.loopback(p.side, r.side)
        val ad = Peering.loopback(p.side, s.side)
        Peering.loopback(q.side, r.side)
        val bdLoop = Peering.loopback(q.side, s.side, interposeAToB = bd)
        val cd = Peering.loopback(r.side, s.side)
        controller.runToIdle()

        // W = B at epoch 2, known to all four
        q.replication.designateLeader(LeaderMark(id, 2, bRef))
        controller.runToIdle()
        listOf(p, q, r, s).forEach { it.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef) }

        // ---- D leaves
        ad.partition()
        bdLoop.partition()
        cd.partition()
        controller.runToIdle()

        // ---- the majority side walks on to (5, bRef) — X = B again
        r.replication.designateLeader(LeaderMark(id, 3, cRef))
        controller.runToIdle()
        p.replication.designateLeader(LeaderMark(id, 4, aRef))
        controller.runToIdle()
        q.replication.designateLeader(LeaderMark(id, 5, bRef))
        controller.runToIdle()
        listOf(p, q, r).forEach { it.replication.leaderOf(id) shouldBe LeaderMark(id, 5, bRef) }
        withClue("D is cut off and cannot learn: its fold is frozen at the mark it left with") {
            s.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        }

        // a write on the majority side, so D's convergence on the heal is
        // observable as something other than two zeroes
        q.ops(b).increment(3)
        controller.runToIdle()
        listOf(a, b, c).forEach { it.total shouldBe 3L }
        d.total shouldBe 0L

        // ---- the baseline every "unchanged" claim below is measured against
        val firesBefore = listOf(p, q, r, s).map { it.leaderMarkFires }
        val leaderCallsBefore = listOf(a, b, c, d).map { it.becomeLeaderCalls }
        val followerCallsBefore = listOf(a, b, c, d).map { it.becomeFollowerCalls }
        val shipsAbcBefore = listOf(p, q, r, s).map { it.replication.shipCountAmong(abc) }
        val shipsAllBefore = listOf(p, q, r, s).map { it.replication.shipCountAmong(all) }
        println(
            "5.4 MEASURED before heal: fires=$firesBefore L=$leaderCallsBefore F=$followerCallsBefore " +
                "shipsAbc=$shipsAbcBefore shipsAll=$shipsAllBefore",
        )
        withClue("B leads and ships to A and C among {a,b,c}") {
            shipsAbcBefore[1] shouldBe 2
        }

        // ---- D returns, with B→D duplicating every frame
        bd.duplicating = true
        bd.reset()
        ad.heal()
        bdLoop.heal()
        cd.heal()
        controller.runToIdle()
        bd.duplicating = false

        // one canonical mark at all FOUR peers
        listOf(p, q, r, s).forEach { it.replication.leaderOf(id) shouldBe LeaderMark(id, 5, bRef) }

        // nothing moved on the majority side — D's replayed (2, bRef) is inert
        withClue("D's stale mark is not strictly greater, so the fold rejects it at A, B and C") {
            listOf(p, q, r).forEachIndexed { i, peer -> (peer.leaderMarkFires - firesBefore[i]) shouldBe 0 }
        }
        listOf(a, b, c).forEachIndexed { i, cell ->
            (cell.becomeLeaderCalls - leaderCallsBefore[i]) shouldBe 0
            (cell.becomeFollowerCalls - followerCallsBefore[i]) shouldBe 0
        }
        listOf(p, q, r, s).forEachIndexed { i, peer ->
            withClue("no shipping link among {a,b,c} was added or dropped at peer $i") {
                peer.replication.shipCountAmong(abc) shouldBe shipsAbcBefore[i]
            }
        }
        withClue("D's return legitimately ADDS B→D at B's engine — asserted separately, so the claim above is honest") {
            (q.replication.shipCountAmong(all) - shipsAllBefore[1]) shouldBe 1
        }

        // D folded exactly once, although (5, bRef) reached it three ways
        withClue("(5, bRef) arrived from A, from C, and twice from B") {
            bd.count(leaderMarkedId) shouldBeGreaterThanOrEqual 2
        }
        (s.leaderMarkFires - firesBefore[3]) shouldBe 1
        (d.becomeFollowerCalls - followerCallsBefore[3]) shouldBe 1
        (d.becomeLeaderCalls - leaderCallsBefore[3]) shouldBe 0
        d.currentEpoch shouldBe 5L
        d.total shouldBe b.total
        d.total shouldBe 3L
        listOf(a, b, c).forEach { it.total shouldBe 3L }
    }
}
