package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryAnnounce
import civictech.cell.wire.WireCodec
import civictech.nature.ContractRegistry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-f7h.4.3` — the **refusal** half of automatic leader election
 * ([MEM1-23], [MEM1-14]; spec 42 §Single-writer replication, decisions
 * f7h.4-D3 and f7h.4.1-D2).
 *
 * Two properties, each with a same-file control that differs in exactly one
 * condition and DOES claim:
 *
 * 1. **[MEM1-23]** — a sole survivor never claims. However far past its
 *    window B counts, an [LeaderElection.EpochClaim] engine with nobody
 *    reachable to lead mints nothing; the count is KEPT, not reset, and the
 *    claim fires on the first observation after somebody becomes reachable
 *    (f7h.4.1-D2's re-evaluation). Control: a third peer present from the
 *    start, same partition and same `observe()`, and B claims.
 * 2. **[MEM1-14]** — a supervised RESTART mints no claim. `ManagedHost`'s
 *    RESTART branch touches no registry, so the membership seam never sees
 *    it and an armed-window detector cannot arm. Control: `despawn` of the
 *    same ref, which DOES unpublish, and B claims on the unpublish itself.
 *
 * **Why not a totals-only assertion.** "Leadership did not move" passes for
 * the wrong reason in this subsystem: leadership also does not move when the
 * detector never ran at all. Every refusal below is therefore pinned on
 * [SingleWriterReplication.missCount] (the detector demonstrably ran and
 * counted) plus [Peer.leaderMarkFires] (no fold happened) plus a live-traffic
 * control — the parked write in test 1, the dead letter and restart counter in
 * test 2 — proving the situation the refusal is refusing in was genuinely
 * reached.
 *
 * **Spawn a peer's replica BEFORE loopbacking that peer**, for
 * [LeaderMarkAnnounceTest]'s reason: [SingleWriterReplication.replicate]
 * registers the local replica and *then* folds its mark, and a replica spawned
 * after its peer already mirrored the mark would fold a rejected duplicate and
 * silently get no role.
 *
 * Companion file: [LeaderElectionTest] owns the posture/default half
 * (computenet-f7h.4.1) and the claim-after-window and present-leader examples
 * (computenet-f7h.4.2). This file is separate so the two can be written
 * concurrently; a later task may fold them together.
 */
class LeaderElectionRefusalTest {

    // --------------------------------------------------------------- fixture

    /**
     * A peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Shaped after [LeaderElectionTest]'s private
     * `Peer` (copied rather than shared — it is private there, and that file
     * is a sibling task's claim).
     *
     * [election] is nullable and null means "construct the way every pre-F4
     * call site does" — `SingleWriterReplication(registry)` with no third
     * argument — for [LeaderElectionTest]'s measured reason: a fixture that
     * passes [LeaderElection.Manual] explicitly does not exercise the
     * production default.
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

        fun poisonReplica(
            logicalId: UUID,
            instanceId: Long,
            mark: LeaderMark,
        ): PoisonSwCounterCell =
            PoisonSwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }

        fun ops(replica: Cell): SingleWriterReplicationTest.SwCounterOps =
            (civictech.cell.host.HostedCellProxy
                .create(replica.ref, registry, SingleWriterReplicationTest.WriteInletHolder::class.java)
                    as SingleWriterReplicationTest.WriteInletHolder).writeInlet.call
    }

    /**
     * A copy of [SingleWriterReplicationTest.SwCounterCell] whose leader
     * `increment` throws on [POISON] before mutating anything. It exists for
     * one reason: to drive a `ManagedHost` [SupervisionPolicy.RESTART] on a
     * replication *leader*, which the shipped `SwCounterCell` cannot do
     * (its `realApi` never throws while leading).
     *
     * A copy rather than a subclass because `SwCounterCell` is final and its
     * `realApi` is private — the same reason [LeaderMarkAnnounceTest] copies a
     * private fixture rather than sharing it. It reuses
     * [SingleWriterReplicationTest.SwCounterOps] and
     * [SingleWriterReplicationTest.WriteInletHolder] so a proxy built for
     * either cell type works on the other.
     */
    class PoisonSwCounterCell(override val ref: CellRef) :
        SingleWriterReplicable<Long>, Cell, Stateful {

        val writeInlet = registerPort("writeInlet", FanInlet.create<SingleWriterReplicationTest.SwCounterOps>())
        override val deltaOutlet = registerPort("deltaOutlet", FanOutlet.create<Propagate<Stamped<Long>>>())
        private val deltaInletPort = registerPort("deltaInlet", FanInlet.create<Propagate<Stamped<Long>>>())
        override val deltaInlet: Use<Propagate<Stamped<Long>>> get() = deltaInletPort

        var total: Long = 0
            private set
        var leading: Boolean = false
            private set
        var currentEpoch: Long = -1
            private set

        private val realApi = object : SingleWriterReplicationTest.SwCounterOps {
            override fun increment(amount: Long) {
                check(leading) { "not the leader" }
                // BEFORE any mutation: the restart's checkpoint restore must be
                // observably a restore, not a coincidence of the poison being a no-op.
                if (amount == POISON) throw IllegalStateException("poison: $amount")
                total += amount
                if (amount != 0L) deltaOutlet.call.propagate(Stamped(currentEpoch, amount))
            }

            override fun mark(tag: civictech.cell.Leased<String>) {
                check(leading) { "not the leader" }
                tag.release()
            }
        }

        init {
            deltaInletPort.serve(object : Propagate<Stamped<Long>> {
                override fun propagate(value: Stamped<Long>) {
                    currentEpoch = value.applyTo(
                        currentEpoch,
                        onBaseline = { adoptState(it) },
                        onDelta = { total += it },
                    ) ?: currentEpoch
                }
            })
            deltaOutlet.linking.onLinked = { link ->
                if (leading) {
                    deltaOutlet.at(link.to).propagate(Stamped(currentEpoch, currentState(), baseline = true))
                }
            }
        }

        override fun becomeLeader(epoch: Long) {
            leading = true
            currentEpoch = epoch
            writeInlet.serve(realApi)
        }

        override fun becomeFollower(leaderRef: CellRef, epoch: Long, registry: LocationRegistry) {
            leading = false
            currentEpoch = epoch
            writeInlet.delegate(forwardWrites(writeInlet.clazz, "writeInlet", leaderRef, registry))
        }

        override fun currentState(): Long = total
        override fun adoptState(state: Long) {
            total = state
        }

        override fun snapshot(): Serializable = total
        override fun restore(state: Serializable) {
            total = state as Long
        }

        companion object {
            /** The one amount that fails. Chosen so no ordinary test write can collide with it. */
            const val POISON: Long = Long.MIN_VALUE
        }
    }

    // --------------------------------------------------- frame accounting

    private fun announceMethodId(name: String, param: Class<*>): Long =
        ContractRegistry.idsOf(RegistryAnnounce::class.java.getMethod(name, param))!!.second

    private val leaderMarkedId: Long = announceMethodId("leaderMarked", LeaderMark::class.java)

    private data class FrameId(val contractId: Long, val methodId: Long)

    /**
     * Records every frame that crosses one direction, in order, and passes it
     * through unchanged. Copied from [LeaderMarkAnnounceTest], where it is
     * private and in another file's claim.
     */
    private open class Counting : Peering.FrameInterpose {
        val frames = CopyOnWriteArrayList<FrameId>()

        override fun apply(frame: ByteArray): List<ByteArray> {
            val decoded = WireCodec.decodeFrame(frame).frame
            frames += FrameId(decoded.contractId, decoded.methodId)
            return listOf(frame)
        }

        fun count(methodId: Long): Int = frames.count { it.methodId == methodId }
        fun reset() = frames.clear()
    }

    /** Collects every [DeadLetter] a host emits. */
    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )
        return letters
    }

    // ------------------------------------------------ [MEM1-23] sole survivor

    /**
     * [MEM1-23] and f7h.4.1-D2 in one arc. B is the ONLY survivor of the
     * partition, so its window is satisfied — twice over — with nobody to
     * lead; the guard refuses and, crucially, KEEPS the count. When a third
     * peer C arrives, C's `published` announcement is itself the observation
     * that re-evaluates the kept count, and B claims on it.
     *
     * The counter assertions are the whole test. `leaderOf` alone cannot tell
     * "refused because nobody was reachable" from "the detector never ran" —
     * both leave `(1, aRef)` folded — and the second is what a broken detector
     * looks like. [SingleWriterReplication.missCount] separates them, and the
     * parked write proves B genuinely lost its leader.
     */
    @Test
    fun `a sole survivor refuses to claim, and claims on the first observation that makes somebody reachable`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(2)))
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val loop = Peering.loopback(a.side, b.side)
        controller.runToIdle()

        onB.leading shouldBe false
        val bFiresBefore = b.leaderMarkFires

        // ---- the leader departs: one observation, and B is alone
        loop.partition()
        controller.runToIdle()
        (aRef in b.registry.replicasOf(id)) shouldBe false
        b.replication.missCount(id) shouldBe 1

        // ---- window (2) reached, and REFUSED: reachable = replicasOf(id) - {bRef} = ∅
        b.replication.observe()
        controller.runToIdle()
        b.replication.leaderOf(id) shouldBe mark1
        b.leaderMarkFires shouldBe bFiresBefore
        // the refusal is not a reset: the count is kept so a later observation re-evaluates
        b.replication.missCount(id) shouldBe 2
        onB.leading shouldBe false

        // ---- and it stays refused however far past the window it counts
        b.replication.observe()
        controller.runToIdle()
        b.replication.leaderOf(id) shouldBe mark1
        b.leaderMarkFires shouldBe bFiresBefore
        b.replication.missCount(id) shouldBe 3
        onB.leading shouldBe false

        // ---- the shipped suspend-when-partitioned condition: a write through
        // the sole survivor forwards to the departed leader's ref and PARKS
        b.ops(onB).increment(7)
        controller.runToIdle()
        b.registry.parkedFor(aRef).size shouldBe 1
        onB.total shouldBe 0L
        b.replication.leaderOf(id) shouldBe mark1

        // ---- f7h.4.1-D2: somebody becomes reachable, and the KEPT count claims
        val c = Peer(controller)
        val cRef = CellRef(id, 2)
        val onC = c.replica(id, 2, mark1) // replica first, loopback second
        val cFiresBeforeLoopback = c.leaderMarkFires
        val bToC = Counting()
        val cToB = Counting()
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        val claimed = LeaderMark(id, 2, bRef)
        b.replication.leaderOf(id) shouldBe claimed
        c.replication.leaderOf(id) shouldBe claimed
        // the adopted mark disarms the window
        b.replication.missCount(id) shouldBe 0
        // exactly one NEW fold at C: the claim. C's catch-up also replays B's
        // folded (1, aRef), which is a rejected duplicate there and folds nothing.
        (c.leaderMarkFires - cFiresBeforeLoopback) shouldBe 1
        onB.leading shouldBe true
        onC.leading shouldBe false
        // both leaderMarked frames B→C cross inside the same runToIdle — the
        // catch-up replay of (1, aRef) and the claim — so the interposer cannot
        // be reset between them.
        bToC.count(leaderMarkedId) shouldBe 2
        (cRef in b.registry.replicasOf(id)) shouldBe true

        // F5 has landed (computenet-f7h.5.3, [MEM1-16]): the claim that
        // promotes B also RELEASES the write parked at the departed leader's
        // ref onto B, which applies it. Until then this asserted the write
        // "simply stays parked" and named the release as F5's work — that
        // boundary is now crossed, and this is the same assertion read from
        // the other side of it. What F4 still owns here is unchanged: the
        // refusal, the kept count, and the claim on the first reachable
        // observation.
        b.registry.parkedFor(aRef).shouldBeEmpty()
        onB.total shouldBe 7L
    }

    /**
     * The [MEM1-23] control. Identical partition and identical `observe()`
     * cadence, one condition changed: a third peer C is connected to B from
     * the start, so `reachable` is non-empty when the window is reached — and
     * B claims on the very observation the sole-survivor test refused.
     *
     * Without this, "no claim" above would be indistinguishable from a
     * detector that never elects at all.
     */
    @Test
    fun `control - the same partition and the same observe with one reachable peer does claim`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(2)))
        val c = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val onC = c.replica(id, 2, mark1)

        val bToC = Counting()
        val cToB = Counting()
        val abLoop = Peering.loopback(a.side, b.side)
        val acLoop = Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        onB.leading shouldBe false
        val bFiresBefore = b.leaderMarkFires
        val cFiresBefore = c.leaderMarkFires
        bToC.reset()
        cToB.reset()

        abLoop.partition()
        acLoop.partition()
        controller.runToIdle()
        b.replication.missCount(id) shouldBe 1
        b.replication.leaderOf(id) shouldBe mark1

        // second observation reaches the window, and C is reachable: claim,
        // synchronously on the observing caller's thread (f7h.4-D5)
        b.replication.observe()
        val claimed = LeaderMark(id, 2, bRef)
        b.replication.leaderOf(id) shouldBe claimed
        controller.runToIdle()

        c.replication.leaderOf(id) shouldBe claimed
        b.replication.missCount(id) shouldBe 0
        (b.leaderMarkFires - bFiresBefore) shouldBe 1
        (c.leaderMarkFires - cFiresBefore) shouldBe 1
        onB.leading shouldBe true
        onC.leading shouldBe false
        bToC.count(leaderMarkedId) shouldBe 1
        b.replication.shipCountAmong(setOf(aRef, bRef, cRef)) shouldBe 1
    }

    // -------------------------------------------- [MEM1-14] RESTART invisible

    /**
     * [MEM1-14]. `ManagedHost`'s [SupervisionPolicy.RESTART] branch
     * dead-letters, bumps the generation, bounces the cell and restores its
     * checkpoint — and touches **no registry**. The ref is never unpublished,
     * so no membership event exists for a detector to witness, and an
     * `EpochClaim` follower with a window of ONE (the tightest window the type
     * permits) still arms nothing.
     *
     * The dead letter and the host's restart counter are the non-vacuity
     * control: they prove the RESTART actually happened, so the zeros below
     * are statements about a detector that had a real leader failure in front
     * of it and correctly saw nothing.
     */
    @Test
    fun `a supervised RESTART of the leader is invisible to the detection window`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        val c = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val mark1 = LeaderMark(id, 1, aRef)

        val onA = a.poisonReplica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val onC = c.replica(id, 2, mark1)

        val aToB = Counting()
        val bToA = Counting()
        val aToC = Counting()
        val cToA = Counting()
        val bToC = Counting()
        val cToB = Counting()
        Peering.loopback(a.side, b.side, interposeAToB = aToB, interposeBToA = bToA)
        Peering.loopback(a.side, c.side, interposeAToB = aToC, interposeBToA = cToA)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        onA.leading shouldBe true
        onA.currentEpoch shouldBe 1L
        val bFiresBefore = b.leaderMarkFires
        val cFiresBefore = c.leaderMarkFires
        listOf(aToB, bToA, aToC, cToA, bToC, cToB).forEach { it.reset() }

        val letters = collectDeadLetters(a.host)
        a.host.managementInlet.call.supervise(aRef, SupervisionPolicy.RESTART)
        controller.runToIdle()

        // ---- the leader fails an invocation and is RESTARTed
        a.ops(onA).increment(PoisonSwCounterCell.POISON)
        controller.runToIdle()
        letters.size shouldBe 1
        a.host.supervisionAccounting().restarts shouldBe 1L

        // ---- [MEM1-14]: nothing on the membership seam, so nothing armed anywhere
        b.replication.missCount(id) shouldBe 0
        c.replication.missCount(id) shouldBe 0
        b.leaderMarkFires shouldBe bFiresBefore
        c.leaderMarkFires shouldBe cFiresBefore
        a.replication.leaderOf(id) shouldBe mark1
        b.replication.leaderOf(id) shouldBe mark1
        c.replication.leaderOf(id) shouldBe mark1
        onA.currentEpoch shouldBe 1L
        listOf(aToB, bToA, aToC, cToA, bToC, cToB).forEach { it.count(leaderMarkedId) shouldBe 0 }

        // ---- and the restarted leader is still the leader, still serving
        a.ops(onA).increment(4)
        controller.runToIdle()
        onA.total shouldBe 4L
        onB.total shouldBe 4L
        onC.total shouldBe 4L
        onB.leading shouldBe false
        onC.leading shouldBe false
    }

    /**
     * The [MEM1-14] control. Same three peers, same postures, one condition
     * changed: `despawn` instead of a supervised RESTART. `despawn` DOES call
     * `registry.unpublish(ref)`, the announcement reaches B, B arms on an
     * unpublish naming the folded leaderRef, its window of one is immediately
     * satisfied, C is reachable — and B claims.
     *
     * Three peers, not two. The feature's example 5 wrote this control with A
     * and B alone, which [MEM1-23] refuses by construction (`reachable` would
     * be empty): the two-peer control cannot pass without weakening the very
     * guard the test above pins, so it is corrected here rather than the guard
     * being relaxed.
     */
    @Test
    fun `control - despawning the leader instead is visible and does claim`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        val c = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val onC = c.replica(id, 2, mark1)

        val bToA = Counting()
        val bToC = Counting()
        Peering.loopback(a.side, b.side, interposeBToA = bToA)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side, interposeAToB = bToC)
        controller.runToIdle()

        onB.leading shouldBe false
        val bFiresBefore = b.leaderMarkFires
        val cFiresBefore = c.leaderMarkFires
        bToA.reset()
        bToC.reset()

        a.host.managementInlet.call.despawn(aRef)
        controller.runToIdle()

        val claimed = LeaderMark(id, 2, bRef)
        b.replication.leaderOf(id) shouldBe claimed
        c.replication.leaderOf(id) shouldBe claimed
        (b.leaderMarkFires - bFiresBefore) shouldBe 1
        (c.leaderMarkFires - cFiresBefore) shouldBe 1
        bToC.count(leaderMarkedId) shouldBe 1
        b.replication.missCount(id) shouldBe 0
        onB.leading shouldBe true
        onC.leading shouldBe false

        // A saw a LOCAL unpublish; its own engine is Manual and does nothing
        // with it, but B's claim is announced back over the still-connected
        // A–B loopback and folds there. A hosts no replica of the id any more,
        // so its role application finds nothing to demote.
        a.replication.leaderOf(id) shouldBe claimed
        bToA.count(leaderMarkedId) shouldBe 1
    }
}
