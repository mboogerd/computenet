package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryAnnounce
import civictech.cell.wire.WireCodec
import civictech.nature.ContractRegistry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-f7h.2.3` — the **engine-level** peering examples of the
 * single-writer leadership mark ([MEM1-11], [MEM1-06], [MEM1-22]; spec 42
 * §Single-writer replication).
 *
 * Where [civictech.cell.wire.LeaderMarkWireTest] drives
 * [LocationRegistry.markLeader] directly and owns "a mark crosses a bridge and
 * folds once on the far side", this file owns what the *engine* does with a
 * mark that arrived over the wire: a peer applies leader/follower roles from a
 * mirrored mark exactly as it would from a local designation, a late joiner
 * converges from catch-up alone, and a duplicated, reordered or heal-replayed
 * mark changes nothing.
 *
 * **Spawn a peer's replica BEFORE loopbacking that peer.**
 * [SingleWriterReplication.replicate] registers the local replica and *then*
 * folds its mark, and applies no role when the fold rejects an equal or lower
 * mark (f7h.1-D3). A replica spawned after its peer already mirrored the
 * epoch-0 mark would fold a rejected duplicate and silently get no role — so
 * every example below spawns first and peers second.
 */
class LeaderMarkAnnounceTest {

    // --------------------------------------------------------------- fixture

    /**
     * A peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Shaped after `SingleWriterReplicationTest`'s
     * private `Peer` (copied rather than shared — it is private there, and
     * that file is another task's claim), plus [leaderMarkFires]: the
     * ANY-scope fold count, which is the discriminator for "the replayed mark
     * did nothing" that a shipping-link count alone cannot give.
     */
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = SingleWriterReplication(registry)

        /** Every adopted fold on this registry — local designation or mirrored announcement. */
        var leaderMarkFires = 0
            private set

        init {
            registry.onLeaderMark { leaderMarkFires++ }
        }

        fun replica(logicalId: UUID, instanceId: Long, mark: LeaderMark): SingleWriterReplicationTest.SwCounterCell =
            SingleWriterReplicationTest.SwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }
    }

    private val announceContractId: Long =
        ContractRegistry.descriptor(RegistryAnnounce::class.java)!!.contractId

    private fun announceMethodId(name: String, param: Class<*>): Long =
        ContractRegistry.idsOf(RegistryAnnounce::class.java.getMethod(name, param))!!.second

    private val leaderMarkedId: Long = announceMethodId("leaderMarked", LeaderMark::class.java)
    private val publishedId: Long = announceMethodId("published", CellRef::class.java)
    private val linkedId: Long = announceMethodId("linked", civictech.cell.host.TopologyLink::class.java)

    /** One frame's identity, as the interposers record it. */
    private data class FrameId(val contractId: Long, val methodId: Long)

    /**
     * Records every frame that crosses one direction, in order, and passes it
     * through unchanged.
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

    /**
     * Emits every `leaderMarked` frame twice once [armed].
     *
     * Armed *after* the initial catch-up rather than from construction: a
     * loopback's own catch-up replays the epoch-0 mark, so an
     * always-duplicating interposer would duplicate that too and the control
     * ("it duplicated exactly one frame") would not name the frame under test.
     */
    private class Duplicating(private val leaderMarkedId: Long) : Counting() {
        var armed = false
        var duplicated = 0
            private set

        override fun apply(frame: ByteArray): List<ByteArray> {
            val passed = super.apply(frame)
            if (!armed || frames.last().methodId != leaderMarkedId) return passed
            duplicated++
            return listOf(frame, frame.copyOf())
        }
    }

    /**
     * Holds the first `leaderMarked` frame seen once [armed] and, on the next
     * one, emits `[next, held]` — the receiver sees the later mark first.
     * Armed after catch-up, for [Duplicating]'s reason.
     */
    private class HoldOneSwap(private val leaderMarkedId: Long) : Counting() {
        var armed = false
        var swapped = 0
            private set
        private var held: ByteArray? = null

        override fun apply(frame: ByteArray): List<ByteArray> {
            val passed = super.apply(frame)
            if (!armed || frames.last().methodId != leaderMarkedId) return passed
            val pending = held
            if (pending == null) {
                held = frame
                return emptyList()
            }
            held = null
            swapped++
            return listOf(frame, pending)
        }
    }

    // -------------------------- [MEM1-11] adoption announces once per direction

    @Test
    fun `adoption announces once per direction and peers apply roles from the mirrored mark`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark0 = LeaderMark(id, 0, aRef)

        // replicas FIRST (each folds its own epoch-0 mark locally), peering second
        val onA = a.replica(id, 0, mark0)
        val onB = b.replica(id, 1, mark0)
        val onC = c.replica(id, 2, mark0)

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

        listOf(aToB, bToA, aToC, cToA, bToC, cToB).forEach { it.reset() }
        val firesBefore = listOf(a.leaderMarkFires, b.leaderMarkFires, c.leaderMarkFires)
        val leaderCallsBefore = listOf(onA.becomeLeaderCalls, onB.becomeLeaderCalls, onC.becomeLeaderCalls)
        val followerCallsBefore = listOf(onA.becomeFollowerCalls, onB.becomeFollowerCalls, onC.becomeFollowerCalls)

        val m1 = LeaderMark(id, 1, bRef)
        a.replication.designateLeader(m1) shouldBe true
        controller.runToIdle()

        // the fold converged everywhere
        a.replication.leaderOf(id) shouldBe m1
        b.replication.leaderOf(id) shouldBe m1
        c.replication.leaderOf(id) shouldBe m1

        // and the roles followed it — B promoted, A and C demoted
        onB.leading shouldBe true
        onA.leading shouldBe false
        onC.leading shouldBe false
        (onB.becomeLeaderCalls - leaderCallsBefore[1]) shouldBe 1
        (onB.becomeFollowerCalls - followerCallsBefore[1]) shouldBe 0
        (onA.becomeLeaderCalls - leaderCallsBefore[0]) shouldBe 0
        (onA.becomeFollowerCalls - followerCallsBefore[0]) shouldBe 1
        (onC.becomeLeaderCalls - leaderCallsBefore[2]) shouldBe 0
        (onC.becomeFollowerCalls - followerCallsBefore[2]) shouldBe 1

        // announced once per outbound direction from the adopting peer, and
        // never re-announced onward by a mirror (f7h.2-D4)
        aToB.count(leaderMarkedId) shouldBe 1
        aToC.count(leaderMarkedId) shouldBe 1
        bToA.count(leaderMarkedId) shouldBe 0
        bToC.count(leaderMarkedId) shouldBe 0
        cToA.count(leaderMarkedId) shouldBe 0
        cToB.count(leaderMarkedId) shouldBe 0

        // exactly one fold each on the two mirroring peers
        (b.leaderMarkFires - firesBefore[1]) shouldBe 1
        (c.leaderMarkFires - firesBefore[2]) shouldBe 1
        (a.leaderMarkFires - firesBefore[0]) shouldBe 1 // its own local designation

        // the new leader ships to both other replicas. Two, not one: nothing
        // unlinks the outgoing leader's stale outbound link — step-down unlink
        // is F3's work and out of scope here — but that stale link lives on A,
        // not on B, so B's count is simply "leader ships to its two followers".
        b.replication.shipCountAmong(setOf(aRef, bRef, cRef)) shouldBe 2
    }

    // ------------------------- [MEM1-06] a late joiner converges from catch-up

    @Test
    fun `a late joiner converges from catch-up alone and its stale mark changes nothing`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val cRef = CellRef(id, 2)
        val mark0 = LeaderMark(id, 0, aRef)

        val onA = a.replica(id, 0, mark0)
        b.replica(id, 1, mark0)
        Peering.loopback(a.side, b.side)
        controller.runToIdle()

        val m3 = LeaderMark(id, 3, aRef)
        a.replication.designateLeader(m3) shouldBe true
        controller.runToIdle()
        onA.leading shouldBe true

        // the joiner: replica first (its fresh registry folds epoch 0 and makes
        // it a follower of A at epoch 0), peering second
        val c = Peer(controller)
        val onC = c.replica(id, 2, mark0)
        val cFollowerCallsBefore = onC.becomeFollowerCalls
        val cLeaderCallsBefore = onC.becomeLeaderCalls
        val aFiresBefore = a.leaderMarkFires
        val aFollowerCallsBefore = onA.becomeFollowerCalls

        val aToC = Counting()
        val cToA = Counting()
        Peering.loopback(a.side, c.side, interposeAToB = aToC, interposeBToA = cToA)
        controller.runToIdle()

        // converged from the catch-up replay alone — no designation on C
        c.replication.leaderOf(id) shouldBe m3
        onC.leading shouldBe false
        (onC.becomeFollowerCalls - cFollowerCallsBefore) shouldBe 1
        (onC.becomeLeaderCalls - cLeaderCallsBefore) shouldBe 0
        aToC.count(leaderMarkedId) shouldBe 1

        // and every announcement that crossed to it was one of the three kinds
        // catch-up replays — convergence came from announcements, nothing else
        aToC.frames.filter { it.contractId == announceContractId }
            .map { it.methodId }
            .filterNot { it in setOf(publishedId, linkedId, leaderMarkedId) } shouldBe emptyList()

        // A ships to the joiner (its onPeerPublished path)
        a.replication.shipCountAmong(setOf(aRef, cRef)) shouldBe 1

        // the stale half of [MEM1-22]: C's catch-up replays ITS epoch-0 mark to
        // A, which crosses once and is inert there
        cToA.count(leaderMarkedId) shouldBe 1
        a.leaderMarkFires shouldBe aFiresBefore
        a.replication.leaderOf(id) shouldBe m3
        onA.leading shouldBe true
        onA.becomeFollowerCalls shouldBe aFollowerCallsBefore
    }

    // ------------------------------------------ [MEM1-22] a duplicate is inert

    @Test
    fun `a duplicated leaderMarked announcement folds once`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark0 = LeaderMark(id, 0, aRef)

        a.replica(id, 0, mark0)
        val onB = b.replica(id, 1, mark0)
        val duplicating = Duplicating(leaderMarkedId)
        Peering.loopback(a.side, b.side, interposeAToB = duplicating)
        controller.runToIdle()

        duplicating.armed = true
        val bFiresBefore = b.leaderMarkFires

        val m1 = LeaderMark(id, 1, bRef)
        a.replication.designateLeader(m1) shouldBe true
        controller.runToIdle()

        duplicating.duplicated shouldBe 1 // control: the frame under test was doubled
        (b.leaderMarkFires - bFiresBefore) shouldBe 1 // the second copy was rejected
        b.replication.leaderOf(id) shouldBe m1
        onB.becomeLeaderCalls shouldBe 1
        onB.leading shouldBe true
        b.replication.shipCountAmong(setOf(aRef, bRef)) shouldBe 1 // one shipping link, not two
    }

    // ------------------------- [MEM1-22] a mark reordered behind a later one is inert

    @Test
    fun `a leaderMarked announcement delivered behind a later mark is inert`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark0 = LeaderMark(id, 0, aRef)

        a.replica(id, 0, mark0)
        val onB = b.replica(id, 1, mark0)
        val swapping = HoldOneSwap(leaderMarkedId)
        Peering.loopback(a.side, b.side, interposeAToB = swapping)
        controller.runToIdle()

        swapping.armed = true
        val bFiresBefore = b.leaderMarkFires
        val bLeaderCallsBefore = onB.becomeLeaderCalls
        val bFollowerCallsBefore = onB.becomeFollowerCalls

        // both designations before any delivery, so both frames are in flight
        // and the interposer can reverse them
        val m2 = LeaderMark(id, 2, aRef)
        val m3 = LeaderMark(id, 3, bRef)
        a.replication.designateLeader(m2) shouldBe true
        a.replication.designateLeader(m3) shouldBe true
        controller.runToIdle()

        swapping.swapped shouldBe 1 // control: the pair really was reversed
        b.replication.leaderOf(id) shouldBe m3
        (b.leaderMarkFires - bFiresBefore) shouldBe 1 // m3 adopted, then m2 fenced
        // had m2 arrived first, B would have demoted under it and then promoted
        // under m3 — the follower delta is what shows the order was reversed and
        // the late lower mark truly inert
        (onB.becomeLeaderCalls - bLeaderCallsBefore) shouldBe 1
        (onB.becomeFollowerCalls - bFollowerCallsBefore) shouldBe 0
    }

    // ------------------------------------- [MEM1-22] a heal's replay is inert

    @Test
    fun `a heal replays the folded mark and it applies no role`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark0 = LeaderMark(id, 0, aRef)

        a.replica(id, 0, mark0)
        val onB = b.replica(id, 1, mark0)
        val aToB = Counting()
        val loopback = Peering.loopback(a.side, b.side, interposeAToB = aToB)
        controller.runToIdle()

        val m3 = LeaderMark(id, 3, bRef)
        a.replication.designateLeader(m3) shouldBe true
        controller.runToIdle()
        onB.leading shouldBe true

        val marksBefore = aToB.count(leaderMarkedId)
        val firesBefore = b.leaderMarkFires
        val leaderCallsBefore = onB.becomeLeaderCalls
        val followerCallsBefore = onB.becomeFollowerCalls
        val leaderOfBefore = b.replication.leaderOf(id)
        val shipsBefore = b.replication.shipCountAmong(setOf(aRef, bRef))

        loopback.heal()
        controller.runToIdle()

        // control: the replay really crossed
        aToB.count(leaderMarkedId) shouldBe marksBefore + 1
        // and it did nothing — the shipping link count is equal because the
        // heal's own unpublish/republish rebuilds it to the same count, while
        // the unchanged fold count and role counters are what prove the mark
        // replay itself applied no role
        b.leaderMarkFires shouldBe firesBefore
        onB.becomeLeaderCalls shouldBe leaderCallsBefore
        onB.becomeFollowerCalls shouldBe followerCallsBefore
        b.replication.leaderOf(id) shouldBe leaderOfBefore
        b.replication.shipCountAmong(setOf(aRef, bRef)) shouldBe shipsBefore
    }
}
