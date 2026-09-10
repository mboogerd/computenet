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
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.Interest
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import civictech.gen.wire.Contract
import civictech.gen.wire.Key
import io.kotest.assertions.withClue
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

        /**
         * Role-application counters (f7h.1 F1): the fold must apply a role
         * exactly ONCE per adopted mark, which a boolean `leading` cannot
         * distinguish from applying it twice. [LeaderMarkFoldTest] pins the
         * count; nothing in this file reads them.
         */
        var becomeLeaderCalls = 0
            private set
        var becomeFollowerCalls = 0
            private set

        /**
         * Delta-inlet counters (f7h.3.1, same style as [becomeLeaderCalls]):
         * [receivedDeltas] counts every inlet invocation, [fencedDeltas] the
         * ones [applyTo] refused as below-epoch, [baselinesAdopted] the ones
         * routed to [adoptState]. computenet-f7h.3.2 and .3 read them to make
         * their step-down and mid-shipment interleaving tests non-vacuous;
         * only the baseline test below reads them in this file.
         */
        var receivedDeltas = 0
            private set
        var fencedDeltas = 0
            private set
        var baselinesAdopted = 0
            private set

        /**
         * Real-api write invocations applied by THIS replica (f7h.5.3, same
         * additive style as [becomeLeaderCalls]). [total] alone cannot tell
         * "the parked write was applied once, at the winner" from "it was
         * applied at the winner and again at the ex-leader after its ref
         * republished" — the two are the same number. `increment` only;
         * `mark` never reaches the real api in a released-write timeline.
         * Read by [ParkedWriteReleaseTest]; nothing in this file reads it.
         */
        var realWrites = 0
            private set

        private val realApi = object : SwCounterOps {
            override fun increment(amount: Long) {
                check(leading) { "not the leader" }
                realWrites++
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
                    // fencing + baseline routing through the ONE rule (f7h.3-D2,
                    // [MEM1-04]/[MEM1-31]/[MEM1-32]) — no local epoch comparison
                    receivedDeltas++
                    val next = value.applyTo(
                        currentEpoch,
                        onBaseline = { baselinesAdopted++; adoptState(it) },
                        onDelta = { total += it },
                    )
                    if (next == null) fencedDeltas++ else currentEpoch = next
                }
            })
            // late-join / re-announce catch-up ([MEM1-32], f7h.3-D6): the
            // leader's WHOLE state as a BASELINE, unconditionally while
            // leading. The old `total != 0L` guard is gone deliberately — it
            // was sound only while the catch-up was a from-zero delta (a
            // zero delta being a no-op); a baseline of the empty state is
            // exactly what makes a follower holding STALE state converge to
            // an empty winner.
            deltaOutlet.linking.onLinked = { link ->
                if (leading) {
                    deltaOutlet.at(link.to).propagate(Stamped(currentEpoch, currentState(), baseline = true))
                }
            }
        }

        override fun becomeLeader(epoch: Long) {
            becomeLeaderCalls++
            leading = true
            currentEpoch = epoch
            writeInlet.serve(realApi)
        }

        override fun becomeFollower(leaderRef: CellRef, epoch: Long, registry: LocationRegistry) {
            becomeFollowerCalls++
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
                    // the one rule (f7h.3-D2) — see [Stamped.applyTo]
                    currentEpoch = value.applyTo(
                        currentEpoch,
                        onBaseline = { adoptState(it) },
                        onDelta = { elements += it.adds.keys },
                    ) ?: currentEpoch
                }
            })
            // late-join / re-announce catch-up ([MEM1-32], f7h.3-D6): the
            // current membership as an explicit BASELINE, funneled through
            // the SAME per-target `at(link.to)` path shipTo's interest slice
            // wraps — so a partial-interest follower's catch-up is sliced
            // exactly like its live stream (CP-D2), baseline flag intact.
            // The old `elements.isNotEmpty()` guard is gone: see SwCounterCell.
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

        override fun currentState(): SetDelta<String> =
            SetDelta(adds = elements.associateWith { emptySet<civictech.cell.Timestamp>() })
        override fun adoptState(state: SetDelta<String>) {
            elements.clear()
            elements += state.adds.keys
        }
    }

    /**
     * [election] is additive and nullable, and null means "construct the way
     * every pre-F4 call site does" — `SingleWriterReplication(registry)` with
     * no third argument. That exact shape is load-bearing and was measured
     * ([LeaderElectionTest]'s own `Peer`): a fixture that always passes an
     * explicit engine never exercises the production default, so the nine
     * pre-existing tests in this file must keep going through the
     * two-argument constructor unchanged (f7h.6.3).
     */
    private class Peer(val controller: SimulationController, election: LeaderElection? = null) {
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

        fun replica(logicalId: UUID, instanceId: Long, mark: LeaderMark): SwCounterCell =
            SwCounterCell(CellRef(logicalId, instanceId)).also { replication.replicate(it, host, mark) }

        /**
         * A replica whose leader `increment` throws on
         * [LeaderElectionRefusalTest.PoisonSwCounterCell.POISON] before
         * mutating: the only way to drive a `ManagedHost`
         * [SupervisionPolicy.RESTART] on a replication *leader*. The class is
         * referenced, not copied — it is public in
         * [LeaderElectionRefusalTest] precisely so this file can reuse it.
         */
        fun poisonReplica(
            logicalId: UUID,
            instanceId: Long,
            mark: LeaderMark,
        ): LeaderElectionRefusalTest.PoisonSwCounterCell =
            LeaderElectionRefusalTest.PoisonSwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }

        fun ops(replica: Cell): SwCounterOps =
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

    /**
     * Fencing under the fold's TOTAL order over `(epoch,
     * leaderRef.instanceId)` (f7h.1-D2, [MEM1-02]) — not the epoch-only,
     * not-total order this test pinned before F1. The leader sits at
     * instance 5 so the equal-counter case has both directions to show: a
     * challenger BELOW it at the same counter loses the tiebreak, one ABOVE
     * it wins. A strictly greater counter still supersedes regardless of
     * instance id.
     */
    @Test
    fun `a fenced stale LeaderMark epoch is rejected`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 5)
        val challengerRef = CellRef(logicalId, 1)

        val mark2 = LeaderMark(logicalId, epoch = 2, leaderRef = leaderRef)
        p.replica(logicalId, 5, mark2)
        p.replication.leaderOf(logicalId) shouldBe mark2

        // a lower counter is fenced — inert, never adopted
        val staleLower = LeaderMark(logicalId, epoch = 1, leaderRef = challengerRef)
        p.replication.designateLeader(staleLower) shouldBe false
        p.replication.leaderOf(logicalId) shouldBe mark2

        // equal counter, LOWER instance id (1 < 5) — loses the tiebreak
        val staleEqual = LeaderMark(logicalId, epoch = 2, leaderRef = challengerRef)
        p.replication.designateLeader(staleEqual) shouldBe false
        p.replication.leaderOf(logicalId) shouldBe mark2

        // equal counter, HIGHER instance id (9 > 5) — strictly greater under
        // the total order, so it is adopted
        val equalHigherInstance = LeaderMark(logicalId, epoch = 2, leaderRef = CellRef(logicalId, 9))
        p.replication.designateLeader(equalHigherInstance) shouldBe true
        p.replication.leaderOf(logicalId) shouldBe equalHigherInstance

        // a strictly greater counter is adopted whatever the instance id — the winner supersedes
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

    @Test
    fun `a rebuilt link's catch-up REPLACES a follower's state instead of adding to it`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        Peering.loopback(p.side, q.side)
        val logicalId = UUID.randomUUID()
        val leaderRef = CellRef(logicalId, 0)
        val followerRef = CellRef(logicalId, 1)
        val mark = LeaderMark(logicalId, epoch = 0, leaderRef = leaderRef)

        val leader = p.replica(logicalId, 0, mark)
        val follower = q.replica(logicalId, 1, mark)
        controller.runToIdle()

        p.ops(leader).increment(5)
        controller.runToIdle()
        follower.total shouldBe 5

        // the follower departs (finding-1b idiom): the shipping link drops
        q.registry.unpublish(followerRef)
        controller.runToIdle()
        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 0

        // a write the departed follower never sees
        p.ops(leader).increment(3)
        controller.runToIdle()
        leader.total shouldBe 8
        follower.total shouldBe 5

        val adoptedBeforeRejoin = follower.baselinesAdopted

        // the follower re-announces: shipTo rebuilds the link, whose onLinked
        // catch-up is now a BASELINE ([MEM1-32], f7h.3-D6)
        q.registry.publish(followerRef, q.host)
        controller.runToIdle()

        p.replication.shipCountAmong(setOf(leaderRef, followerRef)) shouldBe 1
        withClue("replaced, not added: the rebuilt link's catch-up is a baseline, so the follower ADOPTS the leader's 8 rather than adding it onto the 5 it already held (which would read 13)") {
            follower.total shouldBe 8
        }
        // exactly one baseline crossed on the REJOIN itself. The absolute
        // count is deliberately measured as a delta rather than pinned at a
        // literal: the initial link also ships a baseline, so the total is
        // link-count-dependent and computenet-f7h.3.1's predicted `== 1`
        // for the absolute counter does not hold (see the bead comment).
        (follower.baselinesAdopted - adoptedBeforeRejoin) shouldBe 1
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

    // ------------------------------------------------------------------
    // f7h.6.3 — §5.5 RESTART of an ELECTED leader is not an election
    // ------------------------------------------------------------------

    /**
     * Records every frame that crosses one direction, in order, and passes it
     * through unchanged. Copied from [LeaderElectionRefusalTest], where it is
     * private and in another file's claim (the same reason that file copied it
     * from [LeaderMarkAnnounceTest]).
     */
    private open class Counting : Peering.FrameInterpose {
        val frames = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()

        override fun apply(frame: ByteArray): List<ByteArray> {
            val decoded = civictech.cell.wire.WireCodec.decodeFrame(frame).frame
            frames += decoded.contractId to decoded.methodId
            return listOf(frame)
        }

        fun count(methodId: Long): Int = frames.count { it.second == methodId }
        fun reset() = frames.clear()
    }

    private val leaderMarkedId: Long =
        civictech.nature.ContractRegistry.idsOf(
            civictech.cell.wire.RegistryAnnounce::class.java
                .getMethod("leaderMarked", LeaderMark::class.java),
        )!!.second

    /** Collects every [DeadLetter] a host emits (shape of [LeaderElectionRefusalTest]'s helper). */
    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                civictech.cell.port.PortRef.generate(),
            ),
        )
        return letters
    }

    /**
     * The three-peer elected-leader rig shared by the two arms below.
     *
     * A ([SwCounterCell], instance 0) leads at `(1, aRef)`; B
     * ([LeaderElectionRefusalTest.PoisonSwCounterCell], instance 1) and C
     * ([SwCounterCell], instance 2) follow. Every peer runs an
     * [LeaderElection.EpochClaim] engine — the acceptance's "every peer"
     * posture. Replicas are spawned BEFORE the loopbacks, for
     * [LeaderMarkAnnounceTest]'s reason (a replica spawned after its peer
     * mirrored the mark would fold a rejected duplicate and get no role).
     *
     * Despawning A elects B at epoch 2. **Winner determinism — corrected by
     * measurement.** The breakdown predicted that with A, B and C all at
     * `DetectionWindow(1)` the winner would depend on which host the
     * controller happened to run first. It does not. Measured here: with all
     * three at window 1, **C wins every time** — both B and C arm on the same
     * unpublish and both mint `(2, ownRef)`, and the fold's TOTAL order
     * `InstanceIndex.ORDER = compareBy(epoch, leaderRef.instanceId)` then
     * settles it for C's instance 2 over B's instance 1, at every peer,
     * regardless of delivery order (f7h.1-D2, [MEM1-02]). Delivery order is
     * not the deciding variable; the tiebreak is.
     *
     * So the winner is made deterministic **by construction** the way the
     * breakdown's fallback prescribed: C runs `DetectionWindow(2)`, so a
     * single unpublish does not satisfy its window and B claims alone. All
     * three peers still run an [LeaderElection.EpochClaim] engine — the
     * acceptance's "every peer" posture — only C's window differs, and it is
     * the *elected leader* at epoch >= 2 that the acceptance needs, which B
     * is. The rig asserts B won rather than routing through
     * `if (b.leading) b else c`, so a change of winner is a failure here and
     * not a silent re-route: the RESTART arms need the elected leader to be
     * the poison cell (only it can fail an invocation while leading).
     */
    private class ElectedRig(val controller: SimulationController) {
        val id: UUID = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        val p = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        val q = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        // window 2, not 1: see the class KDoc — at window 1 C wins the
        // election on the instance-id tiebreak, and the RESTART arms need B
        val r = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(2)))

        val a: SwCounterCell = p.replica(id, 0, mark1)
        val b: LeaderElectionRefusalTest.PoisonSwCounterCell = q.poisonReplica(id, 1, mark1)
        val c: SwCounterCell = r.replica(id, 2, mark1)

        /**
         * Frame counters on the Q–R pair, installed at construction. They
         * must be in place from the start rather than added later: tearing a
         * loopback down to re-interpose it is itself a membership departure,
         * which would arm the very detectors this test asserts stay silent.
         */
        val qToR = Counting()
        val rToQ = Counting()

        val pq: Peering.Loopback
        val pr: Peering.Loopback
        val qr: Peering.Loopback

        init {
            pq = Peering.loopback(p.side, q.side)
            pr = Peering.loopback(p.side, r.side)
            qr = Peering.loopback(q.side, r.side, interposeAToB = qToR, interposeBToA = rToQ)
            controller.runToIdle()
        }
    }

    /**
     * **Arm 1 — [MEM1-14] for an ELECTED leader, with donor catch-up.**
     *
     * [LeaderElectionRefusalTest.a supervised RESTART of the leader is
     * invisible to the detection window] already pins that a RESTART of a
     * *designated* leader mints no claim. The delta here is the three things
     * that only exist once the leader was ELECTED: it holds an epoch it
     * minted itself (2, not the seeded 1), it holds outbound shipping links
     * it formed on promotion, and it has served writes since. A RESTART must
     * leave all three standing — `ManagedHost`'s RESTART branch unpublishes
     * nothing, so `onMembershipDeparture` never runs and no window can arm —
     * and the recovery is donor catch-up, not the stale spawn-time
     * checkpoint (spec 42 §RESTART; 93 I-25 §4.4 steps 1-2).
     *
     * Fault model: a poison invocation at the leader under
     * [SupervisionPolicy.RESTART] — no partition in this arm.
     *
     * Non-vacuousness (route 2, in-test controls; this is a TEST-ONLY task, so
     * the executed production mutation belongs to the reviewer): the dead
     * letter plus `supervisionAccounting().restarts == 1` prove the RESTART
     * genuinely happened; `b.total == 0` before the catch-up proves the
     * restored checkpoint was really stale; the [Counting] zeros prove no
     * `leaderMarked` frame crossed in any direction. Trace: the no-claim
     * assertions bear on `SingleWriterReplication.onMembershipDeparture`,
     * which never runs because `ManagedHost`'s RESTART branch touches no
     * registry; the catch-up bears on `restartCatchUp`'s `donor == null`
     * early return and `adoptState`.
     */
    @Test
    fun `a RESTART of an ELECTED leader mints no claim and recovers by donor catch-up`() {
        val controller = SimulationController()
        val rig = ElectedRig(controller)
        val (p, q, r) = Triple(rig.p, rig.q, rig.r)
        val id = rig.id

        rig.a.leading shouldBe true
        rig.b.leading shouldBe false
        rig.c.leading shouldBe false

        // a write BEFORE the election, so the followers hold state to be elected over
        p.ops(rig.a).increment(10)
        controller.runToIdle()
        rig.b.total shouldBe 10L
        rig.c.total shouldBe 10L

        // ---- elect B: A's despawn unpublishes aRef, every window is 1
        p.host.managementInlet.call.despawn(rig.aRef)
        controller.runToIdle()

        val elected = LeaderMark(id, 2, rig.bRef)
        withClue("B, not C, must win the election — the RESTART arm needs the poison cell to be the leader") {
            rig.b.leading shouldBe true
        }
        rig.c.leading shouldBe false
        rig.b.currentEpoch shouldBe 2L
        q.replication.leaderOf(id) shouldBe elected
        r.replication.leaderOf(id) shouldBe elected
        // P hosts no replica of the id any more; it still folds the winner's announcement
        p.replication.leaderOf(id) shouldBe elected
        q.replication.shipCountAmong(setOf(rig.bRef, rig.cRef)) shouldBe 1

        // ---- writes served AFTER the election, so the restart has a real loss window
        q.ops(rig.b).increment(4)
        r.ops(rig.c).increment(5) // forwarded to the elected leader
        controller.runToIdle()
        rig.b.total shouldBe 19L
        rig.c.total shouldBe 19L

        // the surviving Q–R link is the one a claim would have to cross, so
        // it is the one that must stay silent across the RESTART
        rig.qToR.reset()
        rig.rToQ.reset()
        q.replication.missCount(id) shouldBe 0
        r.replication.missCount(id) shouldBe 0
        val qFiresBefore = q.leaderMarkFires
        val rFiresBefore = r.leaderMarkFires

        // ---- the elected leader fails an invocation and is RESTARTed
        val letters = collectDeadLetters(q.host)
        q.host.managementInlet.call.supervise(rig.bRef, SupervisionPolicy.RESTART)
        controller.runToIdle()
        q.ops(rig.b).increment(LeaderElectionRefusalTest.PoisonSwCounterCell.POISON)
        controller.runToIdle()
        letters.size shouldBe 1
        q.host.supervisionAccounting().restarts shouldBe 1L

        // ---- [MEM1-14]: no membership event, so no window armed anywhere
        q.replication.missCount(id) shouldBe 0
        r.replication.missCount(id) shouldBe 0
        p.replication.missCount(id) shouldBe 0
        q.leaderMarkFires shouldBe qFiresBefore
        r.leaderMarkFires shouldBe rFiresBefore
        rig.qToR.count(leaderMarkedId) shouldBe 0
        rig.rToQ.count(leaderMarkedId) shouldBe 0
        q.replication.leaderOf(id) shouldBe elected
        r.replication.leaderOf(id) shouldBe elected
        rig.b.currentEpoch shouldBe 2L
        rig.b.leading shouldBe true
        // the ref was never unpublished, so the promotion-time link survives
        q.replication.shipCountAmong(setOf(rig.bRef, rig.cRef)) shouldBe 1

        // ---- the stale checkpoint, made visible before it is repaired.
        // "Writes served but not shipped at failure are the loss window"
        // (spec 42 §RESTART); here everything WAS shipped, so the follower is
        // the warm copy and the spawn-time checkpoint is pure loss.
        rig.b.total shouldBe 0L
        rig.c.total shouldBe 19L

        // ---- donor catch-up: the most-advanced REACHABLE follower, chosen
        // explicitly by the caller (SingleWriterReplication.kt's F3
        // out-of-scope note: choosing the donor is the caller's job)
        val donor = listOf(rig.c).filter { it.ref in q.registry.replicasOf(id) }.maxByOrNull { it.total }
        donor shouldBe rig.c
        restartCatchUp(rig.b, donor)
        rig.b.total shouldBe 19L

        // ---- and the recovered leader serves on top of the restored state and ships it
        q.ops(rig.b).increment(1)
        controller.runToIdle()
        rig.b.total shouldBe 20L
        rig.c.total shouldBe 20L
        rig.b.leading shouldBe true
        rig.c.leading shouldBe false
    }

    /**
     * **Arm 2 — [MEM1-32] the SOLO fallback, and what it costs.**
     *
     * Same elected rig; this time B's only follower is partitioned away
     * (f7h.6-D3: a symmetric [Peering.Loopback.partition], both directions
     * down at once — not a one-way drop) before the RESTART. Two things
     * follow, and the second is the point of the arm.
     *
     * 1. C, whose folded leaderRef just left its index, DOES arm (window 1)
     *    and is REFUSED: its reachable membership is only itself. That is
     *    [MEM1-23], seen from the leader's side of the same partition; the
     *    counter arc it belongs to is owned by
     *    [LeaderElectionRefusalTest.a sole survivor refuses to claim, and
     *    claims on the first observation that makes somebody reachable] and
     *    is cited here, not re-pinned.
     * 2. With no donor reachable, `restartCatchUp(b, null)` is the explicit
     *    no-op and checkpoint restore IS the recovery. On the heal, the
     *    leader's first shipment on the rebuilt link is a BASELINE
     *    (f7h.3-D1), so the follower's served writes are REPLACED by the
     *    leader's checkpoint state — not added to, not kept.
     *
     * **That replacement is the stated cost of the solo fallback** (93 I-25
     * §4.4, "durability bound"), not a defect: the single-writer stream has
     * exactly one authority for state, and after a solo restart that
     * authority's state is the checkpoint. It must NOT be "fixed" by picking
     * a donor the fault model says is unreachable. The two numbers are
     * printed so the cost is legible in the test output.
     *
     * Fault model: symmetric partition of the Q–R loopback, then a poison
     * invocation at the leader under [SupervisionPolicy.RESTART].
     */
    @Test
    fun `with no follower reachable a RESTART falls back to the checkpoint and re-baselines the follower on heal`() {
        val controller = SimulationController()
        val rig = ElectedRig(controller)
        val (p, q, r) = Triple(rig.p, rig.q, rig.r)
        val id = rig.id

        p.ops(rig.a).increment(10)
        controller.runToIdle()

        p.host.managementInlet.call.despawn(rig.aRef)
        controller.runToIdle()
        rig.b.leading shouldBe true
        rig.b.currentEpoch shouldBe 2L
        val elected = LeaderMark(id, 2, rig.bRef)
        val n = rig.b.total
        n shouldBe 10L // N > 0, so a stale baseline would be visible
        rig.c.total shouldBe n

        val qFiresBefore = q.leaderMarkFires
        val rFiresBefore = r.leaderMarkFires

        // ---- 1. the leader's only follower is partitioned away (f7h.6-D3)
        rig.qr.partition()
        controller.runToIdle()
        q.registry.replicasOf(id) shouldBe setOf(rig.bRef)
        r.replication.missCount(id) shouldBe 1
        // C's window is 2 (see [ElectedRig]), so one departure does not yet
        // satisfy it. Push it over with an explicit observation, so what
        // refuses below is genuinely the [MEM1-23] guard — reachable at C is
        // empty — and not merely an unreached window. The counter arc itself
        // is LeaderElectionRefusalTest's, cited above, not re-pinned here.
        r.replication.observe()
        controller.runToIdle()
        r.replication.missCount(id) shouldBe 2 // refused, and the count is KEPT
        r.replication.leaderOf(id) shouldBe elected
        r.leaderMarkFires shouldBe rFiresBefore
        rig.c.leading shouldBe false

        // ---- 2. RESTART the elected leader with nobody to catch up from
        val letters = collectDeadLetters(q.host)
        q.host.managementInlet.call.supervise(rig.bRef, SupervisionPolicy.RESTART)
        controller.runToIdle()
        q.ops(rig.b).increment(LeaderElectionRefusalTest.PoisonSwCounterCell.POISON)
        controller.runToIdle()
        letters.size shouldBe 1
        q.host.supervisionAccounting().restarts shouldBe 1L
        rig.b.total shouldBe 0L // checkpoint restore, and there is no donor
        restartCatchUp(rig.b, donor = null) // the explicit solo no-op — same call path
        rig.b.total shouldBe 0L
        rig.b.leading shouldBe true
        rig.b.currentEpoch shouldBe 2L

        val baselinesBefore = rig.c.baselinesAdopted
        rig.c.total shouldBe n

        // ---- 3. heal: the rebuilt link's first shipment is a BASELINE
        rig.qr.heal()
        controller.runToIdle()

        q.replication.leaderOf(id) shouldBe elected
        r.replication.leaderOf(id) shouldBe elected
        r.replication.missCount(id) shouldBe 0 // disarmed by bRef's return
        q.leaderMarkFires shouldBe qFiresBefore
        r.leaderMarkFires shouldBe rFiresBefore

        val adopted = rig.c.baselinesAdopted - baselinesBefore
        println(
            "f7h.6.3 arm 2 — solo-fallback cost: follower held total=$n before the heal, " +
                "adopted $adopted baseline(s) from the restarted leader, and now holds " +
                "total=${rig.c.total} (leader total=${rig.b.total})",
        )
        adopted shouldBe 1
        rig.c.total shouldBe 0L
        rig.b.total shouldBe 0L
    }
}
