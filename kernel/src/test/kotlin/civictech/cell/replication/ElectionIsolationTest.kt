package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.ActorIngress
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.wire.Peering
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `computenet-f7h.6.4` — **isolation** of an automatic election: it touches
 * only its own shard, keeps the effect authority exactly-once across a
 * *claimed* handoff, and leaves a co-hosted mergeable quorum read unchanged.
 *
 * Three examples, one per rule: [MEM1-27] (per-shard leadership), [MEM1-29]
 * (effect authority across a claimed handoff, spec 31 §Effects on instance
 * sets / PN-17), and [MEM1-19]/[MEM1-33] (quorum non-interference).
 *
 * ## Why every rig here is multi-peer
 *
 * `SingleWriterReplication.evaluateClaim` computes
 * `reachable = replicasOf(id) − this engine's published locals` and refuses
 * ([MEM1-23]) when it is empty, so **a single-registry rig can never claim**:
 * every replica is local. This was verified on this branch, not inherited —
 * see the KDoc of [a single-registry rig cannot elect - the premise every rig
 * in this file rests on], which is the executable form of the premise. The
 * feature's own design example ("`ReplicatedEffectTest`'s fixture with
 * `EpochClaim`, despawn the leader, observe(), idle") is therefore wrong: that
 * fixture has one registry. Every scenario below uses three peers over
 * `Peering.loopback` (decision f7h.4.1-D2).
 *
 * ## Fault model (f7h.6-D3)
 *
 * `despawn` — a real departure that unpublishes the ref — in all three
 * examples. Never `Loopback.partition()`: a partition also shrinks a co-hosted
 * mesh's `instancesOf`, which would move the quorum reading in (e) for a
 * reason that is not the election.
 *
 * ## Non-vacuousness (route 2 — in-test controls)
 *
 * (c) the s1 election is the control for s2's zeros; (d)
 * [ReplicatedEffectTest.control a - authority off - every replica fires the
 * effect N times] shows what N firings look like, and the stale
 * re-designation arm is this test's own control; (e) the `true`/`false`
 * completeAt pair is what makes "identical" non-vacuous, and
 * `leaderOf(swId).epoch == 2` proves the election ran. Production mutations
 * are the reviewer's (test-only task).
 *
 * **Every count below is measured on this branch, not predicted.** Each test
 * prints its measurements before asserting them, so a base whose behaviour
 * differs reports the new numbers rather than only the first mismatch.
 *
 * f7h.6-D5: [MEM1-26] and [MEM1-30] are deliberately NOT tested here; F7
 * records them.
 */
class ElectionIsolationTest {

    // --------------------------------------------------------------- fixture

    /**
     * A peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Copied inline from [LeaderElectionRefusalTest]'s
     * private `Peer` — the established idiom in this package ([StepDownTest]'s
     * `Rig`, [ParkedWriteReleaseTest]'s `Peer`) rather than widening a fixture
     * a sibling task owns.
     */
    private class Peer(
        controller: SimulationController,
        election: LeaderElection = LeaderElection.EpochClaim(DetectionWindow(1)),
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = SingleWriterReplication(registry, election = election)

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
            (
                HostedCellProxy.create(
                    replica.ref,
                    registry,
                    SingleWriterReplicationTest.WriteInletHolder::class.java,
                ) as SingleWriterReplicationTest.WriteInletHolder
                ).writeInlet.call
    }

    /** Fully connect three peers — every pair loopbacked, replicas spawned first. */
    private fun mesh(a: Peer, b: Peer, c: Peer) {
        Peering.loopback(a.side, b.side)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side)
    }

    // ------------------------------------------------- the shared premise

    /**
     * The premise every rig in this file rests on, asserted rather than
     * inherited: **a single-registry rig cannot elect**.
     * `SingleWriterReplication.evaluateClaim` computes `reachable =
     * replicasOf(id) − local` and [MEM1-23] refuses an empty one, so a
     * fixture shaped like [ReplicatedEffectTest]'s `Peer` (one registry,
     * three replicas) despawns its leader, arms, counts past its window and
     * mints nothing.
     *
     * The control is the very next line: the identical despawn in the
     * three-peer rig of the per-shard example below DOES elect, which is what
     * makes this refusal a property of the topology rather than of a detector
     * that never runs. `missCount` separates "refused" from "never counted".
     */
    @Test
    fun `a single-registry rig cannot elect - the premise every rig in this file rests on`() {
        val controller = SimulationController()
        val p = Peer(controller)
        val id = UUID.randomUUID()
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 0))
        p.replica(id, 0, mark1)
        p.replica(id, 1, mark1)
        p.replica(id, 2, mark1)
        controller.runToIdle()

        p.host.managementInlet.call.despawn(CellRef(id, 0))
        controller.runToIdle()
        p.replication.observe()
        p.replication.observe()
        controller.runToIdle()

        println(
            "[f7h.6.4 (premise)] missCount=${p.replication.missCount(id)} " +
                "leaderOf=${p.replication.leaderOf(id)}",
        )
        withClue("the detector must have RUN — a zero count would make the refusal vacuous") {
            (p.replication.missCount(id) >= 1) shouldBe true
        }
        withClue("[MEM1-23]: reachable = replicasOf(id) - local = empty on one registry, so no claim") {
            p.replication.leaderOf(id) shouldBe mark1
        }
    }

    // ------------------------------------------- (c) per-shard — [MEM1-27]

    /**
     * [MEM1-27]: an election is scoped to the logical id whose leader
     * departed. Two logical ids `s1` and `s2` — "shards" in 93 I-25 §6's
     * sense, where "a partitioned SW structure's partitions are ordinary
     * cells, each replicating SW per its own `replicasOf(partitionRef)`", so
     * a shard IS its own logical id — three replicas each, both led from peer
     * A. Despawning s1's leader elects an s1 successor and moves nothing that
     * belongs to s2: not its mark, not its role counters, not its shipping
     * links, not its writes.
     *
     * **For F7, and deliberately untested here** (f7h.6-D5's neighbourhood):
     * a SINGLE logical id split by disjoint [civictech.cell.link.Interest] has
     * ONE `LeaderMark` and ONE leader under today's fold —
     * `InstanceIndex.leaderMarks` is keyed by `logicalId` alone, so interest
     * slices a single mark's shipping, never the mark itself. There is no
     * sharded single-writer fixture in the tree and this task builds none;
     * [MEM1-27] is exercised as two logical ids each carrying its own mark.
     */
    @Test
    fun `an election on one shard leaves the other shard's leadership, links and writes untouched`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)

        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        val a1Ref = CellRef(s1, 0)
        val b1Ref = CellRef(s1, 1)
        val c1Ref = CellRef(s1, 2)
        val a2Ref = CellRef(s2, 0)
        val b2Ref = CellRef(s2, 1)
        val c2Ref = CellRef(s2, 2)
        val s1Mark1 = LeaderMark(s1, epoch = 1, leaderRef = a1Ref)
        val s2Mark1 = LeaderMark(s2, epoch = 1, leaderRef = a2Ref)

        // Replicas BEFORE the loopbacks, for [LeaderMarkAnnounceTest]'s reason:
        // `replicate` registers the local replica and THEN folds its mark, so a
        // replica spawned after its peer mirrored the mark folds a rejected
        // duplicate and silently gets no role.
        val a1 = a.replica(s1, 0, s1Mark1)
        val b1 = b.replica(s1, 1, s1Mark1)
        val c1 = c.replica(s1, 2, s1Mark1)
        val a2 = a.replica(s2, 0, s2Mark1)
        val b2 = b.replica(s2, 1, s2Mark1)
        val c2 = c.replica(s2, 2, s2Mark1)
        mesh(a, b, c)
        controller.runToIdle()

        // shipping live on BOTH shards before the election
        a.ops(a1).increment(3)
        a.ops(a2).increment(5)
        controller.runToIdle()

        val s1Refs = setOf(a1Ref, b1Ref, c1Ref)
        val s2Refs = setOf(a2Ref, b2Ref, c2Ref)
        val allRefs = s1Refs + s2Refs
        val engines = listOf("A" to a, "B" to b, "C" to c)

        val s2MarkBefore = a.replication.leaderOf(s2)
        val s2ShipBefore = a.replication.shipCountAmong(s2Refs)
        fun s2Roles() = listOf(a2, b2, c2).map { it.becomeLeaderCalls to it.becomeFollowerCalls }
        val s2RolesBefore = s2Roles()
        val s2TotalsBefore = listOf(a2.total, b2.total, c2.total)
        val a2WritesBefore = a2.realWrites
        val firesBefore = engines.associate { (n, p) -> n to p.leaderMarkFires }

        println(
            "[f7h.6.4 (c)] before: s2Mark=$s2MarkBefore shipS2@A=$s2ShipBefore " +
                "s2Roles=$s2RolesBefore s2Totals=$s2TotalsBefore fires=$firesBefore " +
                "shipS1@A=${a.replication.shipCountAmong(s1Refs)}",
        )
        withClue("both shards must be led from A and shipping before the election") {
            b.replication.leaderOf(s1) shouldBe s1Mark1
            b.replication.leaderOf(s2) shouldBe s2Mark1
            b1.total shouldBe 3L
            b2.total shouldBe 5L
        }

        // ---- kill shard 1's leader ONLY (f7h.6-D3: despawn, a real departure)
        a.host.managementInlet.call.despawn(a1Ref)
        controller.runToIdle()

        val s1After = b.replication.leaderOf(s1)!!
        val firesAfter = engines.associate { (n, p) -> n to p.leaderMarkFires }
        println(
            "[f7h.6.4 (c)] after: s1Mark=$s1After s2Mark@A=${a.replication.leaderOf(s2)} " +
                "s2Mark@B=${b.replication.leaderOf(s2)} s2Mark@C=${c.replication.leaderOf(s2)} " +
                "shipS2@A=${a.replication.shipCountAmong(s2Refs)} s2Roles=${s2Roles()} " +
                "firesDelta=${firesAfter.mapValues { (n, v) -> v - firesBefore[n]!! }} " +
                "leading=(b1=${b1.leading}, c1=${c1.leading}) " +
                "pairs@A=${a.replication.shippedPairs(allRefs)} " +
                "pairs@B=${b.replication.shippedPairs(allRefs)} " +
                "pairs@C=${c.replication.shippedPairs(allRefs)}",
        )

        // ---- shard 1 elected a successor at epoch 2
        withClue("the s1 election must have run: epoch 2, claimed by a survivor") {
            s1After.epoch shouldBe 2L
            (s1After.leaderRef in setOf(b1Ref, c1Ref)) shouldBe true
        }
        withClue("all three peers converge on the same s1 mark") {
            a.replication.leaderOf(s1) shouldBe s1After
            c.replication.leaderOf(s1) shouldBe s1After
        }
        // MEASURED on this branch: C wins. Both survivors run DetectionWindow(1)
        // and both mint epoch 2, so the winner is decided by the fold's total
        // order over `(epoch, leaderRef.instanceId)` (`InstanceIndex.ORDER`) —
        // the higher instanceId, c1 — and not by delivery order. The task's
        // breakdown flagged this `unverified:` as "delivery-order dependent";
        // it is not, because the loser's mark is fenced wherever it lands.
        withClue("measured winner: the higher instanceId under InstanceIndex.ORDER") {
            s1After.leaderRef shouldBe c1Ref
        }
        withClue("exactly one leader among the survivors") {
            c1.leading shouldBe true
            b1.leading shouldBe false
        }

        // ---- shard 2 is untouched
        withClue("[MEM1-27]: the s2 mark is identical on every peer") {
            a.replication.leaderOf(s2) shouldBe s2MarkBefore
            b.replication.leaderOf(s2) shouldBe s2MarkBefore
            c.replication.leaderOf(s2) shouldBe s2MarkBefore
        }
        withClue("no s2 replica re-applied a role") { s2Roles() shouldBe s2RolesBefore }
        withClue("no s2 state moved") { listOf(a2.total, b2.total, c2.total) shouldBe s2TotalsBefore }
        withClue("no s2 shipping link was torn down or rebuilt") {
            a.replication.shipCountAmong(s2Refs) shouldBe s2ShipBefore
        }
        // MEASURED: 2 — a2 -> b2 and a2 -> c2, one per reachable replica.
        s2ShipBefore shouldBe 2

        // ---- no cross-shard link anywhere, and shipping is additive over shards
        engines.forEach { (name, p) ->
            withClue("$name: every shipping link stays inside one logical id") {
                p.replication.shippedPairs(allRefs).all { it.first.id == it.second.id } shouldBe true
            }
            withClue("$name: shipCountAmong(s1 u s2) == shipCountAmong(s1) + shipCountAmong(s2)") {
                p.replication.shipCountAmong(allRefs) shouldBe
                    p.replication.shipCountAmong(s1Refs) + p.replication.shipCountAmong(s2Refs)
            }
        }

        // ---- MEASURED fold accounting. `leaderMarkFires` is per REGISTRY, not
        // per logical id, so "moved by exactly the s1 fold" is read as: the
        // delta equals the number of s1 marks this registry ADOPTED, and the
        // s2 assertions above (identical mark, unmoved role counters) are what
        // says none of the movement was s2's.
        val delta = firesAfter.mapValues { (n, v) -> v - firesBefore[n]!! }
        withClue("every peer folded at least the winning s1 claim") {
            delta.values.all { it >= 1 } shouldBe true
        }
        withClue(
            "measured fold deltas: B mints its own losing (2, b1) and then adopts (2, c1); C mints " +
                "(2, c1) and fences B's; A, whose loopbacks to both survivors are still open, " +
                "mirrors BOTH epoch-2 marks in ORDER and adopts each",
        ) {
            delta shouldBe mapOf("A" to 2, "B" to 2, "C" to 1)
        }

        // ---- writes still land on the right shard's leader
        a.ops(a2).increment(7)
        controller.runToIdle()
        withClue("an s2 write still lands at A2, the untouched s2 leader") {
            a2.realWrites shouldBe a2WritesBefore + 1
            a2.total shouldBe 12L
            b2.total shouldBe 12L
            c2.total shouldBe 12L
        }

        c.ops(c1).increment(4)
        controller.runToIdle()
        withClue("an s1 write lands at the NEW s1 leader and ships") {
            c1.realWrites shouldBe 1
            c1.total shouldBe 7L
            b1.total shouldBe 7L
        }
    }

    // ------------------- (d) effect authority across a CLAIMED handoff

    /**
     * [MEM1-29] (spec 31 §Effects on instance sets, PN-17): the effect
     * authority survives an *elected* handoff — every logical delta fires
     * exactly once across it, the winner firing and the other survivor
     * suppressed.
     *
     * [ReplicatedEffectTest]'s own handoff test drives the mark by
     * `designateLeader`; this one is the same property under a CLAIM, which is
     * why it is here and multi-peer: a single registry cannot elect (see the
     * premise test above).
     *
     * `broadcast` emits to every LIVE replica, one `ActorIngress` per peer —
     * an `Effectful` inlet refuses a frame with no `MessageContext`
     * (`[24-DUR-06]`), and these deltas are driven straight from the test.
     * A despawned replica is dropped from the broadcast deliberately:
     * [ReplicatedEffectTest.EffectfulSinkCell] does not `forwardWrites` on
     * `becomeFollower`, so nothing here re-addresses a write aimed at a dead
     * ref.
     */
    @Test
    fun `the effect authority stays exactly-once across a claimed handoff`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)
        val effectLog = mutableListOf<Long>()
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = aRef)

        fun sink(peer: Peer, ref: CellRef): ReplicatedEffectTest.SinkOps =
            (
                HostedCellProxy.create(
                    ref,
                    peer.registry,
                    ReplicatedEffectTest.SinkWriteInletHolder::class.java,
                ) as ReplicatedEffectTest.SinkWriteInletHolder
                ).writeInlet.call

        val onA = ReplicatedEffectTest.EffectfulSinkCell(aRef, effectLog)
            .also { a.replication.replicate(it, a.host, mark1) }
        val onB = ReplicatedEffectTest.EffectfulSinkCell(bRef, effectLog)
            .also { b.replication.replicate(it, b.host, mark1) }
        val onC = ReplicatedEffectTest.EffectfulSinkCell(cRef, effectLog)
            .also { c.replication.replicate(it, c.host, mark1) }
        mesh(a, b, c)
        controller.runToIdle()

        val drivers = mapOf(a to ActorIngress(UUID.randomUUID()), b to ActorIngress(UUID.randomUUID()), c to ActorIngress(UUID.randomUUID()))
        var live = listOf(a to aRef, b to bRef, c to cRef)
        var deltas = 0
        fun broadcast(delta: Long) {
            deltas++
            live.forEach { (peer, ref) -> drivers.getValue(peer).drive { sink(peer, ref).emit(delta) } }
            controller.runToIdle()
        }

        onA.leading shouldBe true
        onB.leading shouldBe false
        onC.leading shouldBe false

        broadcast(1)
        broadcast(2)
        withClue("before the handoff only the leader fires") { effectLog shouldBe listOf(1L, 2L) }

        // ---- the leader departs; B and C both run DetectionWindow(1)
        a.host.managementInlet.call.despawn(aRef)
        controller.runToIdle()
        live = listOf(b to bRef, c to cRef)

        val elected = b.replication.leaderOf(id)!!
        println(
            "[f7h.6.4 (d)] elected=$elected leading=(b=${onB.leading}, c=${onC.leading}) " +
                "log=$effectLog",
        )
        withClue("a CLAIM, not a designation: epoch 2 minted by a survivor") {
            elected.epoch shouldBe 2L
            c.replication.leaderOf(id) shouldBe elected
        }
        // MEASURED, same mechanism as (c): the fold's total order over
        // `(epoch, leaderRef.instanceId)` decides between two epoch-2 claims,
        // so the higher instanceId wins regardless of delivery order. No
        // asymmetric DetectionWindow is needed to make this deterministic.
        withClue("measured winner: the higher instanceId under InstanceIndex.ORDER") {
            elected.leaderRef shouldBe cRef
        }
        withClue("the winner re-serves the real api; the loser stays suppressed") {
            onC.leading shouldBe true
            onB.leading shouldBe false
        }

        broadcast(3)
        broadcast(4)
        withClue("[MEM1-29]: exactly once per logical delta across the CLAIMED handoff") {
            effectLog shouldBe listOf(1L, 2L, 3L, 4L)
            effectLog.size shouldBe deltas
        }

        // ---- control: a stale re-designation at the old epoch is inert
        withClue("a mark not strictly greater under the fold's order is fenced") {
            c.replication.designateLeader(LeaderMark(id, epoch = 1, leaderRef = aRef)) shouldBe false
        }
        controller.runToIdle()
        broadcast(5)
        withClue("the deposed leader does not resurrect and double-fire") {
            effectLog shouldBe listOf(1L, 2L, 3L, 4L, 5L)
            effectLog.size shouldBe deltas
        }
    }

    // ------------------ (e) quorum non-interference — [MEM1-19], [MEM1-33]

    /**
     * [MEM1-19] and [MEM1-33] (96 §E3.4 PN-7 amendment, §E3.6): a
     * single-writer election does not disturb a mergeable replica set that
     * happens to share the registry. `SingleWriterReplication` has no
     * watermark, frontier or quorum coupling at all — `grep -inE
     * 'watermark|frontier|quorum' SingleWriterReplication.kt` returns exactly
     * ONE hit on this branch (`-E` is load-bearing: basic `grep` reads `|` as
     * a literal and answers a false ZERO, which is how the original "0 hits"
     * claim arose), and it is a KDoc sentence about an unrelated
     * per-inlet *processed*-frontier (L1057); no declaration, call or import
     * in the file names any of the three — so the properties hold by
     * construction; what is pinned
     * here is that the identical covering-quorum read survives an election
     * driven on the same registries, read at three moments including one
     * *mid-flight*.
     *
     * The fault is a `despawn` of the SW leader, never a `partition()`: a
     * partition shrinks the mesh's own `instancesOf` and would move the quorum
     * for a reason that is not the election.
     */
    @Test
    fun `an election on a co-hosted single-writer set leaves the mergeable covering quorum unmoved`() {
        val controller = SimulationController(17L)
        val p0 = Peer(controller)
        val p1 = Peer(controller)
        val p2 = Peer(controller)
        val mergeable = listOf(p0, p1, p2).associateWith { Replication(it.registry) }

        val meshId = UUID.randomUUID()
        val swId = UUID.randomUUID()
        val s0Ref = CellRef(swId, 0)
        val swMark1 = LeaderMark(swId, epoch = 1, leaderRef = s0Ref)

        SetCell<String>(CellRef(meshId, 0)).also { mergeable.getValue(p0).replicate(it, p0.host) }
        SetCell<String>(CellRef(meshId, 1)).also { mergeable.getValue(p1).replicate(it, p1.host) }
        SetCell<String>(CellRef(meshId, 2)).also { mergeable.getValue(p2).replicate(it, p2.host) }
        p0.replica(swId, 0, swMark1)
        p1.replica(swId, 1, swMark1)
        p2.replica(swId, 2, swMark1)
        mesh(p0, p1, p2)
        controller.runToIdle()

        val wave = UUID.randomUUID()
        listOf(p0, p1, p2).forEach { mergeable.getValue(it).watermarkOf(meshId)!!.advance(wave, 5L) }
        controller.runToIdle()

        /**
         * The two readings that must DIFFER — that difference is what makes
         * "identical across the election" non-vacuous. Measured on this branch:
         * every covering member has delivered the wave through 5, so the wave
         * completes at 5 and holds at 6.
         */
        fun pair(p: Peer): Pair<Boolean, Boolean> {
            val f = mergeable.getValue(p).replicaFrontier(meshId)
            return f.completeAt(wave, 5L, null) to f.completeAt(wave, 6L, null)
        }
        fun readAll() = listOf(pair(p0), pair(p1), pair(p2))

        val before = readAll()
        val slotsBefore = listOf(p0, p1, p2).map { mergeable.getValue(it).openSlots(meshId) }
        println("[f7h.6.4 (e)] before=$before slots=$slotsBefore")
        withClue("the pair must differ, or 'identical across the election' says nothing") {
            before.forEach { it.first shouldBe true }
            before.forEach { it.second shouldBe false }
        }

        // ---- trigger the election WITHOUT touching the mesh's membership
        p0.host.managementInlet.call.despawn(s0Ref)

        // ---- one task at a time, stopping the moment a claim is folded but the
        // simulation is NOT idle: the mid-flight reading.
        var steps = 0
        while (controller.step()) {
            steps++
            if (p1.replication.leaderOf(swId)?.epoch == 2L || p2.replication.leaderOf(swId)?.epoch == 2L) break
        }
        val during = readAll()
        println("[f7h.6.4 (e)] during=$during afterSteps=$steps sw@p1=${p1.replication.leaderOf(swId)}")

        controller.runToIdle()
        val after = readAll()
        val elected = p1.replication.leaderOf(swId)!!
        println("[f7h.6.4 (e)] after=$after elected=$elected")

        withClue("the election really happened — otherwise 'unmoved' is vacuous") {
            elected.epoch shouldBe 2L
            p2.replication.leaderOf(swId) shouldBe elected
        }
        withClue("[MEM1-19]: the covering quorum reading is unmoved, mid-election and after") {
            during shouldBe before
            after shouldBe before
        }
        withClue(
            "[MEM1-33]: the held wave is still held through the claimant's promotion — the new " +
                "leader's first shipment releases nothing the covering quorum has not permitted",
        ) {
            after.forEach { it.second shouldBe false }
        }

        // The breakdown's `Counting`-on-the-watermark-method clause is DROPPED,
        // and here is why: `RegistryAnnounce` (Peering.kt) declares exactly five
        // methods — `published`, `linked`, `unlinked`, `unpublished`,
        // `leaderMarked` — and none of them carries a watermark. Watermark
        // gossip rides ordinary delta links between `WatermarkCell`s, not a
        // `RegistryAnnounce` frame, so there is no method id to count and the
        // clause's own escape hatch applies. The substitute asserted instead:
        // each peer's whole `OpenSlots` view is identical across the election —
        // and that carries `rows`, the per-slot per-source watermark positions
        // actually consulted for the MIN, not just membership — so no watermark
        // row was created, closed or MOVED by it.
        //
        // Its one limit, next to the claim: this is a read of STATE, not of
        // FRAME TRAFFIC. A `WatermarkDelta` emitted by the election that
        // re-sent a value already held would leave both this view and the
        // `completeAt` pair above unchanged and would go unseen here. The
        // dropped `Counting` clause would have seen it; nothing reachable on
        // this branch can, because watermark gossip rides ordinary delta links
        // between `WatermarkCell`s and carries no countable method id.
        withClue("the mesh's own membership view is untouched by the SW election") {
            listOf(p0, p1, p2).map { mergeable.getValue(it).openSlots(meshId) } shouldBe slotsBefore
        }
    }
}
