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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * F3 of MEM1 (epic computenet-f7h, feature computenet-f7h.3, task
 * computenet-f7h.3.2): a superseded leader steps down COMPLETELY.
 *
 * Before this task the demotion half of
 * [SingleWriterReplication.applyRoles] re-pointed the ex-leader's write inlet
 * at the new leader and stopped there: its outbound entries in `shipped` were
 * neither unlinked nor dropped, so its `deltaOutlet` kept every follower
 * subscribed. Nothing was observably wrong while it stayed quiet — but a
 * single-writer follower's apply is explicitly non-idempotent, so any emission
 * down a stale link double-applies.
 *
 * What is pinned here:
 *
 * - [MEM1-08] the demoted replica gets exactly one `becomeFollower`, its
 *   subsequent writes reach the new leader, and it converges to the winner;
 * - [MEM1-15] every `shipped` entry sourced at the ex-leader is `unlink()`ed
 *   and removed, and a `Stamped` the ex-leader emits afterwards reaches
 *   nobody;
 * - [MEM1-10]'s SYNCHRONOUS half (f7h.3-D5) — `becomeFollower` runs on the
 *   folding thread inside `designateLeader`, so a write issued after
 *   `designateLeader` returns already targets the new leader and produces no
 *   dead letter. The PARKED-write half — a write already in flight at the old
 *   ref when the mark lands — is deliberately NOT built here: **F5 owns it**;
 * - [MEM1-18]'s steady state: after the step-down and re-link, every
 *   surviving link's first element is the current `leaderRef`;
 * - f7h.3-D3's ordering and the `onStepDown` seam.
 *
 * The mid-shipment interleaving (a delta emitted under the old epoch racing
 * the mark) is computenet-f7h.3.3's, not this file's.
 */
class StepDownTest {

    /**
     * [SingleWriterReplicationTest]'s `Peer`, rebuilt inline — that one is
     * private, and a local copy is the established idiom here (see
     * [LeaderMarkFoldTest.Rig]) rather than widening a fixture other tasks own.
     * Single-peer: every example drives the fold through `designateLeader` on
     * one registry, so nothing needs peering.
     */
    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = SingleWriterReplication(registry)

        fun replica(logicalId: UUID, instanceId: Long, mark: LeaderMark): SingleWriterReplicationTest.SwCounterCell =
            SingleWriterReplicationTest.SwCounterCell(CellRef(logicalId, instanceId))
                .also { replication.replicate(it, host, mark) }

        fun ops(replica: SingleWriterReplicationTest.SwCounterCell): SingleWriterReplicationTest.SwCounterOps =
            (HostedCellProxy.create(
                replica.ref,
                registry,
                SingleWriterReplicationTest.WriteInletHolder::class.java,
            ) as SingleWriterReplicationTest.WriteInletHolder).writeInlet.call

        /** The dead-letter idiom of `a follower rejects a Leased write`. */
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

    /**
     * Feature example 1, end to end: [MEM1-08], [MEM1-15], [MEM1-10] and
     * [MEM1-18]'s steady state on one timeline.
     */
    @Test
    fun `a superseded leader unlinks its shipping, catches up, and forwards its writes`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 0))
        val a = r.replica(id, 0, mark1) // leads at epoch 1
        val b = r.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)
        val rejections = r.deadLetters()
        r.controller.runToIdle()

        r.ops(a).increment(10)
        r.controller.runToIdle()
        a.total shouldBe 10
        b.total shouldBe 10
        c.total shouldBe 10

        // ---- the handoff: b supersedes a at epoch 2 ----
        r.replication.designateLeader(LeaderMark(id, epoch = 2, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()

        a.leading shouldBe false
        b.leading shouldBe true
        a.becomeFollowerCalls shouldBe 1 // [MEM1-08]: exactly one, not one per pass

        // [MEM1-15] + [MEM1-18] steady state. Two links, and — the part a bare
        // count cannot say — BOTH sourced at the new leader: a's outbound
        // entries were unlinked and dropped in the demotion pass. Measured,
        // not predicted: 2 is `replicasOf(id) - {b}` = {a, c}, one link each.
        r.replication.shipCountAmong(setOf(a.ref, b.ref, c.ref)) shouldBe 2
        r.replication.shippedPairs(setOf(a.ref, b.ref, c.ref)) shouldBe setOf(b.ref to a.ref, b.ref to c.ref)

        // The winner's baseline REPLACED a's equal state rather than adding to
        // it (T1's `Stamped.baseline`): 10, not 20. One adoption — a held no
        // link as leader, so this is a's first baseline ever.
        a.total shouldBe 10
        a.baselinesAdopted shouldBe 1

        // ---- [MEM1-10], synchronous half: a write through the EX-LEADER'S
        // proxy lands on the winner exactly once, and nothing dead-letters
        // (a's real api would have thrown `not the leader`).
        r.ops(a).increment(5)
        r.controller.runToIdle()
        b.total shouldBe 15
        a.total shouldBe 15
        c.total shouldBe 15 // not 25: applied once, by b, shipped once to each
        rejections.shouldBeEmpty()

        // ---- [MEM1-15]'s control: what the ex-leader WOULD emit down a stale
        // link reaches nobody, because it has no consumer left. Without the
        // unlink this propagate would add 99 to b and c.
        val bReceived = b.receivedDeltas
        val cReceived = c.receivedDeltas
        a.deltaOutlet.call.propagate(Stamped(1L, 99L))
        r.controller.runToIdle()
        b.receivedDeltas shouldBe bReceived
        c.receivedDeltas shouldBe cReceived
        b.total shouldBe 15
        c.total shouldBe 15
        a.total shouldBe 15
    }

    /**
     * The empty-`shipped` branch of the demotion pass: a leader that never had
     * a follower still steps down cleanly. The successor named here has no
     * local cell at all, so the promotion pass finds nothing either — the
     * whole fold has to survive both halves being empty.
     */
    @Test
    fun `a leader with no followers steps down cleanly`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val a = r.replica(id, 0, LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 0)))
        r.controller.runToIdle()

        a.leading shouldBe true
        r.replication.shipCountAmong(setOf(a.ref)) shouldBe 0

        val elsewhere = CellRef(id, 9) // designated, but not a local replica
        r.replication.designateLeader(LeaderMark(id, epoch = 2, leaderRef = elsewhere)) shouldBe true
        r.controller.runToIdle()

        a.leading shouldBe false
        a.becomeFollowerCalls shouldBe 1
        a.becomeLeaderCalls shouldBe 1 // its own epoch-1 promotion, still once
        r.replication.shippedPairs(setOf(a.ref, elsewhere)).shouldBeEmpty()
    }

    /**
     * f7h.3-D3's seam: one fire per demoted ex-leader, carrying the old and
     * new marks and the replica, AFTER the demotion — the listener observes a
     * replica that already forwards writes and already has no outbound link.
     * F5 (divergent-write surfacing) is what will subscribe; nothing in F3
     * does.
     */
    @Test
    fun `onStepDown fires once, after demotion, with the old and new marks`() {
        val r = Rig()
        val id = UUID.randomUUID()
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 0))
        val a = r.replica(id, 0, mark1)
        val b = r.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)
        r.controller.runToIdle()

        val fires = mutableListOf<SingleWriterReplication.StepDown>()
        val leadingAtFire = mutableListOf<Boolean>()
        val exLeaderLinksAtFire = mutableListOf<Int>()
        val handle = r.replication.onStepDown { event ->
            fires += event
            // ordering, recorded rather than inferred: by the time the seam
            // fires, (1) the unlink and (2) becomeFollower have both run.
            leadingAtFire += (event.replica as SingleWriterReplicationTest.SwCounterCell).leading
            exLeaderLinksAtFire +=
                r.replication.shippedPairs(setOf(a.ref, b.ref, c.ref)).count { it.first == event.replica.ref }
        }

        val mark2 = LeaderMark(id, epoch = 2, leaderRef = b.ref)
        r.replication.designateLeader(mark2) shouldBe true
        r.controller.runToIdle()

        fires shouldHaveSize 1 // c demoted too, but c was never the leader
        fires[0].logicalId shouldBe id
        fires[0].from shouldBeSameInstanceAs mark1
        fires[0].to shouldBeSameInstanceAs mark2
        fires[0].replica shouldBeSameInstanceAs a
        leadingAtFire shouldBe listOf(false)
        exLeaderLinksAtFire shouldBe listOf(0)

        // the handle detaches (LocationRegistry.onPublish's contract)
        handle.close()
        r.replication.designateLeader(LeaderMark(id, epoch = 3, leaderRef = c.ref)) shouldBe true
        r.controller.runToIdle()
        c.leading shouldBe true
        b.leading shouldBe false
        fires shouldHaveSize 1
    }
}
