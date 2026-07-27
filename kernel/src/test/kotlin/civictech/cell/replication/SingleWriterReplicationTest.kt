package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Stateful
import civictech.cell.Propagate
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * W4.3: single-writer replication core (G-44 core, spec 42 §Single-writer
 * replication). Leader = the single applying instance and single wave
 * source; followers command-forward writes to the leader (10/14 `delegate`)
 * and apply the leader's shipped log in per-link FIFO order — no merge
 * function, unlike the mergeable mesh ([ReplicationTest]).
 */
class SingleWriterReplicationTest {

    /** A tiny non-idempotent write API: increment is NOT safe to gossip symmetrically (plain addition double-counts echoes, spec 42) — exactly why this needs a leader instead of a mesh. [mark] exercises the Leased type-determined rejection (20/23). */
    @Contract
    interface SwCounterOps {
        fun increment(amount: Long)
        fun mark(@Key tag: Leased<String>)
    }

    interface WriteInletHolder {
        val writeInlet: Use<SwCounterOps>
    }

    /** Test fixture realizing [SingleWriterReplicable] over a plain running total. */
    class SwCounterCell(override val ref: CellRef) : SingleWriterReplicable<Long>, Cell, Stateful {

        val writeInlet = registerPort("writeInlet", FanInlet.create<SwCounterOps>())
        override val deltaOutlet = registerPort("deltaOutlet", FanOutlet.create<Propagate<Stamped<Long>>>())
        private val deltaInletPort = registerPort("deltaInlet", FanInlet.create<Propagate<Stamped<Long>>>())
        override val deltaInlet: Use<Propagate<Stamped<Long>>> get() = deltaInletPort

        var total: Long = 0
            private set
        var leading: Boolean = false
            private set
        var currentEpoch: Long = -1
            private set

        private val realApi = object : SwCounterOps {
            override fun increment(amount: Long) {
                check(leading) { "not the leader" }
                total += amount
                if (amount != 0L) deltaOutlet.call.propagate(Stamped(currentEpoch, amount))
            }

            override fun mark(tag: Leased<String>) {
                check(leading) { "not the leader" }
                tag.release()
            }
        }

        init {
            deltaInletPort.serve(object : Propagate<Stamped<Long>> {
                override fun propagate(value: Stamped<Long>) {
                    // fencing (spec 42): a delta stamped below the current epoch is inert
                    if (value.epoch < currentEpoch) return
                    currentEpoch = maxOf(currentEpoch, value.epoch)
                    total += value.delta
                }
            })
            // late-join catch-up (G-22 idiom, mirrors CounterCell): current
            // total as a from-zero delta for a freshly-linked follower
            deltaOutlet.linking.onLinked = { link ->
                if (leading && total != 0L) deltaOutlet.at(link.to).propagate(Stamped(currentEpoch, total))
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
    }

    /**
     * A single-writer replicated SET (T07 finding 1, Divergence A test
     * fixture): unlike [SwCounterCell]'s `Long` (not [civictech.cell.link.Scoped]),
     * the delta type here is [SetDelta] — the same [civictech.cell.link.Scoped]
     * carrier `Replication`'s own interest tests use — so a partial-interest
     * follower genuinely receives a *slice*, not an all-or-nothing refusal
     * (see [civictech.cell.link.sliceTo]'s refusal path for the non-Scoped
     * case, already pinned by [civictech.cell.link.SliceToRefusalTest]).
     * Apply is idempotent (plain set union), so re-applying a resent catch-up
     * baseline after a rebuilt link is harmless — deliberately unlike
     * [SwCounterCell], which stays reserved for tests that need a genuinely
     * non-idempotent single-writer stream.
     */
    @Contract
    interface SwSetOps {
        fun add(element: String)
    }

    interface WriteSetInletHolder {
        val writeInlet: Use<SwSetOps>
    }

    class SwSetCell(override val ref: CellRef) : SingleWriterReplicable<SetDelta<String>>, Cell {
        val writeInlet = registerPort("writeInlet", FanInlet.create<SwSetOps>())
        override val deltaOutlet = registerPort("deltaOutlet", FanOutlet.create<Propagate<Stamped<SetDelta<String>>>>())
        private val deltaInletPort = registerPort("deltaInlet", FanInlet.create<Propagate<Stamped<SetDelta<String>>>>())
        override val deltaInlet: Use<Propagate<Stamped<SetDelta<String>>>> get() = deltaInletPort

        private val elements = mutableSetOf<String>()
        val membership: Set<String> get() = elements.toSet()
        var leading: Boolean = false
            private set
        var currentEpoch: Long = -1
            private set

        private val realApi = object : SwSetOps {
            override fun add(element: String) {
                check(leading) { "not the leader" }
                if (elements.add(element)) {
                    deltaOutlet.call.propagate(
                        Stamped(currentEpoch, SetDelta(adds = mapOf(element to emptySet<civictech.cell.Timestamp>()))),
                    )
                }
            }
        }

        init {
            deltaInletPort.serve(object : Propagate<Stamped<SetDelta<String>>> {
                override fun propagate(value: Stamped<SetDelta<String>>) {
                    if (value.epoch < currentEpoch) return
                    currentEpoch = maxOf(currentEpoch, value.epoch)
                    elements += value.delta.adds.keys
                }
            })
            // late-join / re-announce catch-up (G-22 idiom): the current
            // membership as a from-empty baseline, funneled through the SAME
            // per-target `at(link.to)` path shipTo's interest slice wraps —
            // so a partial-interest follower's catch-up is sliced exactly
            // like its live stream (CP-D2).
            deltaOutlet.linking.onLinked = { link ->
                if (leading && elements.isNotEmpty()) {
                    deltaOutlet.at(link.to)
                        .propagate(
                            Stamped(
                                currentEpoch,
                                SetDelta(adds = elements.associateWith { emptySet<civictech.cell.Timestamp>() }),
                            ),
                        )
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

        override fun currentState(): SetDelta<String> =
            SetDelta(adds = elements.associateWith { emptySet<civictech.cell.Timestamp>() })
        override fun adoptState(state: SetDelta<String>) {
            elements.clear()
            elements += state.adds.keys
        }
    }

    private class Peer(val controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = SingleWriterReplication(registry)

        fun replica(logicalId: UUID, instanceId: Long, mark: LeaderMark): SwCounterCell =
            SwCounterCell(CellRef(logicalId, instanceId)).also { replication.replicate(it, host, mark) }

        fun ops(replica: SwCounterCell): SwCounterOps =
            (civictech.cell.host.HostedCellProxy.create(replica.ref, registry, WriteInletHolder::class.java)
                    as WriteInletHolder).writeInlet.call

        fun setReplica(logicalId: UUID, instanceId: Long, mark: LeaderMark): SwSetCell =
            SwSetCell(CellRef(logicalId, instanceId)).also { replication.replicate(it, host, mark) }

        fun setOps(replica: SwSetCell): SwSetOps =
            (civictech.cell.host.HostedCellProxy.create(replica.ref, registry, WriteSetInletHolder::class.java)
                    as WriteSetInletHolder).writeInlet.call
    }

    @Test
    fun `the leader applies writes and ships them to followers`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val onP = p.replica(logicalId, 0, mark) // leader
        val onQ = q.replica(logicalId, 1, mark) // follower
        controller.runToIdle()

        onP.leading shouldBe true
        onQ.leading shouldBe false

        p.ops(onP).increment(5)
        p.ops(onP).increment(3)
        controller.runToIdle()

        onP.total shouldBe 8
        onQ.total shouldBe 8 // shipped one-direction, applied FIFO
    }

    @Test
    fun `a follower redirects an ordinary write to the leader`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val onP = p.replica(logicalId, 0, mark) // leader
        val onQ = q.replica(logicalId, 1, mark) // follower
        controller.runToIdle()

        // write lands on the FOLLOWER — command-forward, not applied locally
        q.ops(onQ).increment(10)
        controller.runToIdle()

        onP.total shouldBe 10 // applied at the leader
        onQ.total shouldBe 10 // and shipped back down to the follower
    }

    @Test
    fun `a follower rejects a Leased write instead of forwarding it`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        p.replica(logicalId, 0, mark) // leader
        val onQ = q.replica(logicalId, 1, mark) // follower
        controller.runToIdle()

        // the write API is fire-and-forget (spec 41): rejection surfaces
        // where every undeliverable/failed invocation does — the host's
        // dead-letter outlet (G-26) — rather than as a synchronous throw
        val rejections = mutableListOf<DeadLetter>()
        q.host.deadLetterOutlet.subscribe(Use.fixed(
            object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    rejections += value
                }
            },
            civictech.cell.port.PortRef.generate(),
        ))

        q.ops(onQ).mark(Leased("cannot cross"))
        controller.runToIdle()

        rejections shouldHaveSize 1
        val cause = rejections.first().cause
        cause.shouldBeInstanceOf<IllegalStateException>()
        (cause as IllegalStateException).message shouldContain "Rejected"
    }

    @Test
    fun `a fenced stale LeaderMark epoch is rejected`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val challengerRef = CellRef(logicalId, 1)

        val mark2 = LeaderMark(logicalId, epoch = 2, leaderRef = leaderRef)
        p.replica(logicalId, 0, mark2)
        p.replication.leaderOf(logicalId) shouldBe mark2

        // a stale (lower/equal) epoch is fenced — inert, never adopted
        val staleLower = LeaderMark(logicalId, epoch = 1, leaderRef = challengerRef)
        p.replication.designateLeader(staleLower) shouldBe false
        p.replication.leaderOf(logicalId) shouldBe mark2

        val staleEqual = LeaderMark(logicalId, epoch = 2, leaderRef = challengerRef)
        p.replication.designateLeader(staleEqual) shouldBe false
        p.replication.leaderOf(logicalId) shouldBe mark2

        // a strictly greater epoch is adopted — the winner supersedes
        val higher = LeaderMark(logicalId, epoch = 3, leaderRef = challengerRef)
        p.replication.designateLeader(higher) shouldBe true
        p.replication.leaderOf(logicalId) shouldBe higher
    }

    @Test
    fun `RESTART recovers by peer catch-up rather than trusting a stale checkpoint`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val onP = p.replica(logicalId, 0, mark) // leader
        val onQ = q.replica(logicalId, 1, mark) // follower
        controller.runToIdle()

        p.ops(onP).increment(42)
        controller.runToIdle()
        onQ.total shouldBe 42

        // the leader crashes and restarts from a STALE spawn-time checkpoint
        // (total = 0) — writes served but not yet shipped at failure are the
        // stated async primary-backup loss window; here nothing was lost in
        // flight, but the checkpoint itself is stale by construction
        val restarted = SwCounterCell(leaderRef)
        restarted.becomeLeader(epoch = 0)
        restarted.total shouldBe 0 // stale checkpoint, not yet caught up

        restartCatchUp(restarted, donor = onQ)
        restarted.total shouldBe 42 // peer catch-up wins over the stale checkpoint
    }

    // ---- T07 finding 1: SWR minimal-correctness patch (Divergence A + B) ----

    @Test
    fun `finding 1a - a disjoint-interest follower forms no shipping link and receives nothing`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        // disjoint slot-interest (CP-D2, mirrors InterestScopedGossipTest's
        // Replication-side control) — set on the LEADER's own registry, which
        // is what `shipTo`'s gate consults (interest is not wire-propagated;
        // each peer's registry independently holds the assignment table, the
        // same setup ShardedReplicationTest uses).
        p.registry.setInterest(leaderRef, Interest.Slots(setOf(0), 2))
        p.registry.setInterest(followerRef, Interest.Slots(setOf(1), 2))

        val leader = SwCounterCell(leaderRef).also { p.replication.replicate(it, p.host, mark) }
        val follower = SwCounterCell(followerRef).also { q.replication.replicate(it, q.host, mark) }
        controller.runToIdle()

        p.ops(leader).increment(7)
        controller.runToIdle()

        leader.total shouldBe 7
        follower.total shouldBe 0 // never shipped — the disjoint gate refuses the link entirely
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 0
    }

    @Test
    fun `finding 1a - a partial-interest follower receives only its admitted slice`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)
        val totalSlots = 4

        // overlapping partial interest: the follower wants only slot 0 (CP-D2)
        p.registry.setInterest(followerRef, Interest.Slots(setOf(0), totalSlots))

        val leader = SwSetCell(leaderRef).also { p.replication.replicate(it, p.host, mark) }
        val follower = SwSetCell(followerRef).also { q.replication.replicate(it, q.host, mark) }
        controller.runToIdle()

        val inSlot0 = generateSequence(0) { it + 1 }.map { "e$it" }
            .first { Interest.Slots.slotOf(it, totalSlots) == 0 }
        val inSlot1 = generateSequence(0) { it + 1 }.map { "e$it" }
            .first { Interest.Slots.slotOf(it, totalSlots) == 1 }

        val ops = p.setOps(leader)
        ops.add(inSlot0)
        ops.add(inSlot1)
        controller.runToIdle()

        leader.membership shouldBe setOf(inSlot0, inSlot1)
        // the follower's own slice: slot-0 element ships, slot-1 element is
        // filtered before it ever crosses (Divergence A — was previously
        // shipped WHOLE since SWR had zero interest handling)
        follower.membership shouldBe setOf(inSlot0)
    }

    @Test
    fun `finding 1b - a departed follower's re-announce rebuilds the shipping link, catch-up converges`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val leader = SwSetCell(leaderRef).also { p.replication.replicate(it, p.host, mark) }
        val follower = SwSetCell(followerRef).also { q.replication.replicate(it, q.host, mark) }
        controller.runToIdle()

        p.setOps(leader).add("a")
        controller.runToIdle()
        follower.membership shouldBe setOf("a")
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1

        // the follower departs (G-45-style eviction/crash): its own local
        // unpublish relays over the wire to the leader's mirror, firing
        // onUnpublish there — Divergence B's fix drops the now-stale shipping
        // link instead of leaving it dangling (SWR previously had NO
        // onUnpublish handler at all, unlike Replication.kt:227).
        q.registry.unpublish(followerRef)
        controller.runToIdle()
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 0

        // a write while the follower is departed — nothing is subscribed to
        // ship it to right now, so it simply isn't shipped until the link rebuilds
        p.setOps(leader).add("b")
        controller.runToIdle()

        // the follower re-announces (a returning peer) — shipTo rebuilds a fresh link
        q.registry.publish(followerRef, q.host)
        controller.runToIdle()

        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1
        follower.membership shouldBe setOf("a", "b") // the rebuilt link's catch-up delivers both
    }
}
