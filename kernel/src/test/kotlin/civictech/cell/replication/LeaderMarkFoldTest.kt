package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * F1 of MEM1 (epic computenet-f7h, feature computenet-f7h.1): the
 * [SingleWriterReplication] engine keeps no [LeaderMark] map of its own. The
 * fold lives on [civictech.cell.host.InstanceIndex] and has exactly one
 * writer, [LocationRegistry.markLeader]; the engine reads it back
 * ([MEM1-03]) and applies roles from ONE
 * [LocationRegistry.onLeaderMark] subscriber, so a mark folded by any path —
 * the engine's own [SingleWriterReplication.designateLeader] or F2's
 * [LocationRegistry.mirrorLeaderMark] — produces the identical role
 * application, exactly once (f7h.1-D4).
 *
 * Companion to [SingleWriterReplicationTest], which keeps the end-to-end
 * leader/follower behaviour; this file pins the fold itself.
 */
class LeaderMarkFoldTest {

    /**
     * The [SingleWriterReplicationTest.Peer] shape, rebuilt inline (that
     * `Peer` is private) and without the wire side — every example here is
     * single-peer, so nothing needs peering.
     */
    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = SingleWriterReplication(registry)

        fun replica(
            logicalId: UUID,
            instanceId: Long,
            mark: LeaderMark,
        ): SingleWriterReplicationTest.SwCounterCell =
            SingleWriterReplicationTest.SwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }
    }

    /**
     * [MEM1-03]: `SingleWriterReplication.leaderOf` is a delegation, not a
     * copy — the very same object the membership index holds, after every
     * fold and after every rejected fold. An engine-side map could satisfy
     * `shouldBe` (data equality) while drifting; identity cannot.
     */
    @Test
    fun `leaderOf is the membership index's own mark, not an engine-side copy`() {
        val r = Rig()
        val id = UUID.randomUUID()

        fun agree() = r.replication.leaderOf(id) shouldBeSameInstanceAs r.registry.instances.leaderOf(id)

        r.replication.leaderOf(id) shouldBe null
        agree() // both null

        val a = LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0))
        r.replication.designateLeader(a) shouldBe true
        r.replication.leaderOf(id) shouldBeSameInstanceAs a
        agree()

        // a rejected fold leaves the index — and therefore the engine — alone
        r.replication.designateLeader(LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0))) shouldBe false
        r.replication.leaderOf(id) shouldBeSameInstanceAs a
        agree()

        val b = LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 3))
        r.registry.mirrorLeaderMark(b) shouldBe true
        r.replication.leaderOf(id) shouldBeSameInstanceAs b
        agree()
    }

    /**
     * [MEM1-09]/[MEM1-22], role half: a regression (lower counter) and a
     * duplicate (the identical mark refolded) are BOTH inert — not merely
     * "return false", but no role re-application, no re-shipping, no change
     * to the folded mark. The call counters on the fixture cell are what
     * distinguish "applied once" from "applied again with the same values",
     * which `leading` alone cannot.
     */
    @Test
    fun `regression is inert`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val mark5 = LeaderMark(id, epoch = 5, leaderRef = CellRef(id, 0))

        val x = r.replica(id, 0, mark5) // the designated leader
        val w = r.replica(id, 1, mark5) // a second local replica under the same mark
        r.controller.runToIdle()

        val refs = setOf(x.ref, w.ref)
        val leaderBefore = r.replication.leaderOf(id)
        val shipsBefore = r.replication.shipCountAmong(refs)
        val xLeaderCallsBefore = x.becomeLeaderCalls
        val wFollowerCallsBefore = w.becomeFollowerCalls

        // a regression: strictly lower counter
        r.replication.designateLeader(LeaderMark(id, epoch = 2, leaderRef = w.ref)) shouldBe false
        r.controller.runToIdle()
        r.replication.leaderOf(id) shouldBeSameInstanceAs leaderBefore
        r.replication.shipCountAmong(refs) shouldBe shipsBefore
        x.becomeLeaderCalls shouldBe xLeaderCallsBefore
        w.becomeFollowerCalls shouldBe wFollowerCallsBefore

        // a duplicate: the identical mark, refolded — equal is not strictly
        // greater, so it is rejected exactly like the regression
        r.replication.designateLeader(LeaderMark(id, epoch = 5, leaderRef = x.ref)) shouldBe false
        r.controller.runToIdle()
        r.replication.leaderOf(id) shouldBeSameInstanceAs leaderBefore
        r.replication.shipCountAmong(refs) shouldBe shipsBefore
        x.becomeLeaderCalls shouldBe xLeaderCallsBefore
        w.becomeFollowerCalls shouldBe wFollowerCallsBefore
    }

    /**
     * Feature rule 6, the mirrored path (f7h.1-D4): a mark folded through
     * [LocationRegistry.mirrorLeaderMark] — F2's announcement-fed path, which
     * never touches [SingleWriterReplication.designateLeader] — applies roles
     * exactly as a manual designation does, once. `onLocalLeaderMark` stays
     * silent because a mirrored mark is not re-announced onward (f7h.1-D3).
     */
    @Test
    fun `one fold, one role application - mirrored`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val a = r.replica(id, 0, LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0)))
        val b = r.replica(id, 1, LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0)))
        r.controller.runToIdle()

        a.leading shouldBe true
        b.leading shouldBe false

        var anyFires = 0
        var localFires = 0
        r.registry.onLeaderMark { anyFires++ }
        r.registry.onLocalLeaderMark { localFires++ }

        r.registry.mirrorLeaderMark(LeaderMark(id, epoch = 1, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()

        assertHandoff(r, a, b)
        anyFires shouldBe 1
        localFires shouldBe 0 // a mirrored mark is not re-announced onward
    }

    /**
     * The same transition driven the local way — identical role and shipping
     * outcome, the only difference being that a local designation also fires
     * [LocationRegistry.onLocalLeaderMark] (the hook F2's announcer will hang
     * off). Read side by side with the mirrored test above: that pair IS
     * f7h.1-D4.
     */
    @Test
    fun `one fold, one role application - designated locally`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val a = r.replica(id, 0, LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0)))
        val b = r.replica(id, 1, LeaderMark(id, epoch = 0, leaderRef = CellRef(id, 0)))
        r.controller.runToIdle()

        var anyFires = 0
        var localFires = 0
        r.registry.onLeaderMark { anyFires++ }
        r.registry.onLocalLeaderMark { localFires++ }

        r.replication.designateLeader(LeaderMark(id, epoch = 1, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()

        assertHandoff(r, a, b)
        anyFires shouldBe 1
        localFires shouldBe 1
    }

    /**
     * The outcome both fold paths must produce, asserted identically for
     * each: [b] leads, [a] has stepped down, and each cell's role was applied
     * exactly once by the single subscriber.
     */
    private fun assertHandoff(
        r: Rig,
        a: SingleWriterReplicationTest.SwCounterCell,
        b: SingleWriterReplicationTest.SwCounterCell,
    ) {
        b.leading shouldBe true
        a.leading shouldBe false
        b.becomeLeaderCalls shouldBe 1
        a.becomeFollowerCalls shouldBe 1
        // a's own promotion at epoch 0, and b's demotion, each still exactly one
        a.becomeLeaderCalls shouldBe 1
        b.becomeFollowerCalls shouldBe 0 // b never demoted: its epoch-0 replicate refolded a duplicate

        // ONE shipping link, b→a. The a→b link built when b published under
        // a's epoch-0 leadership is gone: F3 (computenet-f7h.3.2, [MEM1-15])
        // made a step-down unlink and drop every `shipped` entry whose SOURCE
        // is the demoted replica, in `applyRoles`' demotion pass. Until F3 it
        // survived as an inert stale entry and the count here was 2.
        r.replication.shipCountAmong(setOf(a.ref, b.ref)) shouldBe 1
        r.replication.shippedPairs(setOf(a.ref, b.ref)) shouldBe setOf(b.ref to a.ref)
    }

    /**
     * [MEM1-02] at engine level: the fold's order is TOTAL over `(epoch,
     * leaderRef.instanceId)`, so two marks minted at the SAME counter still
     * have a winner, and the winner is the same one whichever order they
     * arrive in. Before F1 the engine's own epoch-only comparison made this
     * order-dependent — the split-brain the total order closes.
     */
    @Test
    fun `total order, same counter`() {
        val id = UUID.randomUUID()
        val lower = LeaderMark(id, epoch = 2, leaderRef = CellRef(id, 7))
        val higher = LeaderMark(id, epoch = 2, leaderRef = CellRef(id, 9))

        val forward = Rig()
        forward.registry.markLeader(lower) shouldBe true
        forward.registry.markLeader(higher) shouldBe true
        forward.replication.leaderOf(id)!!.leaderRef.instanceId shouldBe 9L

        val reverse = Rig()
        reverse.registry.markLeader(higher) shouldBe true
        reverse.registry.markLeader(lower) shouldBe false
        reverse.replication.leaderOf(id)!!.leaderRef.instanceId shouldBe 9L
    }
}
