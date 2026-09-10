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
 * computenet-f7h.3.3 then added the mid-shipment interleaving — a write
 * straddling the fold, in both of the two branches it can take — and the
 * exact boundary of the fence itself; see the three tests at the end of this
 * file.
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

    // ------------------------------------------------------------------
    // computenet-f7h.3.3 — the mid-shipment interleaving ([MEM1-31],
    // [MEM1-04], [MEM1-18]'s mid-shipment half) and the fence's exact
    // boundary.
    //
    // A write "at the old leader" can straddle the fold in exactly two ways,
    // and they end in DIFFERENT branches. F3's example 2 states the
    // invariant that covers both — `B.total in {prior, prior + 7}` and
    // `A.total == B.total` — and both are CONSTRUCTIBLE, so both are
    // constructed here rather than left to whichever the scheduler happens
    // to pick: each arm asserts the invariant AND its own exact branch.
    // ------------------------------------------------------------------

    /**
     * One peer, A leading at epoch 1 over B, both at `prior = 10`. Two
     * locals on one Peer is enough for every interleaving here: the fence
     * lives at B's own inlet and the queue is the host scheduler, so a
     * second peer would add a wire hop and nothing to any assertion.
     */
    private fun rigWithPrior10(): Triple<Rig, SingleWriterReplicationTest.SwCounterCell, SingleWriterReplicationTest.SwCounterCell> {
        val r = Rig()
        val id = UUID.randomUUID()
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = CellRef(id, 0))
        val a = r.replica(id, 0, mark1) // leads at epoch 1
        val b = r.replica(id, 1, mark1)
        r.controller.runToIdle()
        r.ops(a).increment(10)
        r.controller.runToIdle()
        a.total shouldBe 10
        b.total shouldBe 10
        return Triple(r, a, b)
    }

    /**
     * **Redirected branch** ([MEM1-10]'s in-flight half seen from the
     * follower side): a write that was QUEUED AT THE PROXY but not yet
     * applied when the mark lands is not a stale delta at all — by the time
     * the scheduler dequeues it, the ex-leader's write inlet already
     * delegates to the winner, so it is applied ONCE, under the NEW epoch,
     * by the new leader.
     *
     * Observed queue order, drained by the single `runToIdle` below (recorded
     * rather than inferred — the whole point of this arm is which invocation
     * ran first):
     *
     * 1. `A.writeInlet.increment(7)`, enqueued by the proxy BEFORE the fold;
     *    at dequeue A is already a follower, so it forwards to B (enqueue);
     * 2. `Stamped(2, 10, baseline = true)`, enqueued by the winner's
     *    `onLinked` during the fold → A adopts 10;
     * 3. B applies 7 → 17, and ships `Stamped(2, 7)` (enqueue);
     * 4. A applies it under epoch 2 → 17.
     *
     * Nothing is ever fenced on this path: B's `fencedDeltas` is the counter
     * that says so, and it is what distinguishes this arm from the next one.
     */
    @Test
    fun `a write queued at the ex-leader's proxy before the fold lands once, under the new epoch`() {
        val (r, a, b) = rigWithPrior10()
        val prior = 10L
        val bFencedBefore = b.fencedDeltas
        val aBaselinesBefore = a.baselinesAdopted

        // queued at A's proxy; NOT drained — the fold runs first.
        r.ops(a).increment(7)
        r.replication.designateLeader(LeaderMark(a.ref.id, epoch = 2, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()

        // F3 example 2's invariant, which holds in either branch...
        b.total shouldBe (prior + 7)
        a.total shouldBe b.total
        (b.total in setOf(prior, prior + 7)) shouldBe true
        // ...and this arm's exact branch: applied once by the winner, under
        // epoch 2 — so no unit was ever below B's folded epoch.
        b.fencedDeltas shouldBe bFencedBefore
        b.currentEpoch shouldBe 2L
        // A adopted the winner's baseline exactly once on the new link. A
        // DELTA across the transition, not an absolute count, because the
        // absolute number is link-count-dependent (T1's clause-6 precedent);
        // A held no inbound link at all while it led, so here the delta
        // happens to be the whole history.
        (a.baselinesAdopted - aBaselinesBefore) shouldBe 1
    }

    /**
     * **Fenced branch** ([MEM1-31], [MEM1-04], [MEM1-18]'s mid-shipment
     * half): a delta APPLIED at A under epoch 1, whose shipment is already
     * on the scheduler when the mark lands, must not survive in the
     * follower's state. B's history after the fold is B's alone — never a
     * mix of epoch 1's and epoch 2's applies.
     *
     * Observed queue order, drained by the single `runToIdle` below:
     *
     * 1. `A.writeInlet.call.increment(7)` runs SYNCHRONOUSLY on this thread
     *    (`FanInlet.call` dispatches straight to the served handler — no
     *    policy is installed on this inlet), so A's own total is 17 before
     *    the fold and `Stamped(1, 7)` is already enqueued for B;
     * 2. the fold: T2's demotion pass unlinks A→B — which does NOT recall
     *    the invocation already sitting in the scheduler — and the promotion
     *    pass sets B's epoch to 2 and enqueues `Stamped(2, 10, baseline)`
     *    for A;
     * 3. `Stamped(1, 7)` reaches B's inlet: 1 < 2, so `applyTo` returns null
     *    and the unit is inert;
     * 4. the baseline reaches A: A adopts 10, discarding its own 17.
     *
     * The `receivedDeltas` assertion is the non-vacuousness evidence and is
     * not decoration: a fenced unit is INVISIBLE in totals, so `b.total ==
     * prior` alone passes just as well when the stale delta never arrived —
     * e.g. if the unlink had recalled it, or if the interleaving had not
     * been constructed at all. Only `receivedDeltas` +1 shows the delta
     * ARRIVED, and only `fencedDeltas` +1 shows the fence is what stopped
     * it. (Measured: deleting step 1 leaves every totals assertion in this
     * test green and reddens exactly these two — see the bead.)
     *
     * A's epoch-1 write of 7 is a DIVERGENT write. Surfacing it is
     * [MEM1-13]'s, owned by F5; all this test says is that it does not
     * survive as a mixed history at either replica.
     */
    @Test
    fun `a delta applied under the old epoch and in flight at the fold never reaches the follower's state`() {
        val (r, a, b) = rigWithPrior10()
        val prior = 10L
        val bReceivedBefore = b.receivedDeltas
        val bFencedBefore = b.fencedDeltas
        val aBaselinesBefore = a.baselinesAdopted

        // applied AT A, under epoch 1; the shipment is now queued.
        a.writeInlet.call.increment(7)
        a.total shouldBe (prior + 7) // synchronous apply — step 1 really happened

        r.replication.designateLeader(LeaderMark(a.ref.id, epoch = 2, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()

        // Non-vacuousness: the stale delta ARRIVED at B and was FENCED.
        (b.receivedDeltas - bReceivedBefore) shouldBe 1
        (b.fencedDeltas - bFencedBefore) shouldBe 1

        // The winner's history alone — F3 example 2's invariant, then this
        // arm's exact branch.
        (b.total in setOf(prior, prior + 7)) shouldBe true
        b.total shouldBe prior
        a.total shouldBe b.total // A adopted the winner's baseline; its 7 did not survive
        b.currentEpoch shouldBe 2L // the fenced unit did NOT move B's epoch back to 1

        // [MEM1-18], mid-shipment half: the ex-leader received the winner's
        // baseline over EXACTLY ONE link — one baseline, one link, not one
        // per stale link. Delta rather than absolute, per T1 clause 6.
        (a.baselinesAdopted - aBaselinesBefore) shouldBe 1
        r.replication.shipCountAmong(setOf(a.ref, b.ref)) shouldBe 1
        r.replication.shippedPairs(setOf(a.ref, b.ref)) shouldBe setOf(b.ref to a.ref)
    }

    /**
     * [MEM1-04]'s "at or above", driven straight at a follower's public
     * `deltaInlet` with no fold in between — the direct discriminator
     * between `applyTo`'s `<` and a `<=` that would fence a unit stamped at
     * the replica's own epoch.
     *
     * A leads at epoch 1, B supersedes it at epoch 2, so A is a follower
     * holding `currentEpoch == 2`. A unit at epoch 2 applies; one at epoch 1
     * is inert — no total movement, and the epoch does not travel backwards.
     */
    @Test
    fun `the fence admits a unit at the replica's own epoch and refuses one below it`() {
        val (r, a, b) = rigWithPrior10()
        r.replication.designateLeader(LeaderMark(a.ref.id, epoch = 2, leaderRef = b.ref)) shouldBe true
        r.controller.runToIdle()
        a.leading shouldBe false
        a.currentEpoch shouldBe 2L
        a.total shouldBe 10L
        val fencedBefore = a.fencedDeltas

        // AT the folded epoch: applies.
        a.deltaInlet.call.propagate(Stamped(2L, 1L))
        a.total shouldBe 11L
        a.fencedDeltas shouldBe fencedBefore

        // BELOW it: inert.
        a.deltaInlet.call.propagate(Stamped(1L, 1L))
        a.total shouldBe 11L
        (a.fencedDeltas - fencedBefore) shouldBe 1
        a.currentEpoch shouldBe 2L
    }
}
