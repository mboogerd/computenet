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
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * F5 of MEM1 (epic computenet-f7h, feature computenet-f7h.5, task
 * computenet-f7h.5.2): the **divergent-write** half — a leader records the
 * deltas it produces after it witnessed a member depart, and surfaces them
 * once each at its own step-down (F3's seam), on the host's dead-letter outlet
 * and to every [SingleWriterReplication.onDivergentWrite] handler.
 *
 * Owns feature rules [MEM1-13], [MEM1-28] and [MEM1-21], feature examples 4, 5
 * and 6, and decisions f7h.5-D3 (as refined: the buffer is RETAINED until
 * step-down, never cleared when the member returns) and f7h.5-D4.
 *
 * Nothing here touches parked writes, `unpark` or `forwardWrites` — that is
 * the same feature's other half (computenet-f7h.5.1 / .3).
 *
 * ## Two premises this file makes visible rather than assuming
 *
 * - **A mirrored mark does not relay onward.** In a triangle P–Q–R with P–Q
 *   partitioned, a mark minted on Q reaches R but does NOT reach P through R
 *   (`Peering` announces on `onLocalLeaderMark` only), so P folds it and steps
 *   its leader down only when P–Q heals. Every example below asserts
 *   `leaderOf(id)` is still at epoch 1 on P before the heal, so a base on
 *   which R *did* relay would fail here loudly instead of silently changing
 *   what the examples measure.
 * - **The heal announces refs BEFORE marks.** The departed member's `publish`
 *   therefore reaches the ex-leader one notification ahead of the superseding
 *   mark. That ordering is exactly why f7h.5-D3 was refined to retain the
 *   buffer: a clear-on-return would empty it one notification before the
 *   step-down that has to surface it. `example 4` is the direct pin — it would
 *   report zero dead letters under the unrefined rule.
 */
class DivergentWriteSurfacingTest {

    // --------------------------------------------------------------- fixture

    /**
     * One peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Copied inline from [LeaderElectionTest]'s
     * private `Peer` — the established idiom here ([StepDownTest.Rig],
     * `LeaderMarkFoldTest.Rig`) rather than widening a fixture another task
     * owns. Manual election posture throughout: F4's `EpochClaim` is not
     * needed, every mark below is driven by `designateLeader` on the
     * claimant's own peer.
     */
    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = SingleWriterReplication(registry)

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

        /** The dead-letter idiom of [StepDownTest]'s `Rig.deadLetters`. */
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
     * Three peers P/Q/R with pairwise loopbacks and one single-writer logical
     * cell replicated on each — A on P (instance 0) and leading at epoch 1, B
     * on Q (instance 1), C on R (instance 2).
     *
     * **Replicas before peering**, for [LeaderMarkAnnounceTest]'s reason:
     * `replicate` registers the local replica and *then* folds its mark, so a
     * replica spawned after its peer already mirrored the mark would fold a
     * rejected duplicate and silently get no role.
     */
    private class Triangle {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val r = Peer(controller)
        val id: UUID = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = aRef)

        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = r.replica(id, 2, mark1)

        val pq = Peering.loopback(p.side, q.side)
        val pr = Peering.loopback(p.side, r.side)
        val qr = Peering.loopback(q.side, r.side)

        init {
            controller.runToIdle()
        }
    }

    /** The divergent-write tuples an [SingleWriterReplication.onDivergentWrite] handler saw. */
    private data class Surfaced(val logicalId: UUID, val fenced: Long, val canonical: Long, val payload: Any?)

    /** Only the divergent-write records — the outlet also carries anything else the host reports. */
    private fun List<DeadLetter>.divergent(): List<DeadLetter> =
        filter { it.description.startsWith("single-writer divergent write:") }

    private fun List<DeadLetter>.handlerFailures(): List<DeadLetter> =
        filter { it.description.startsWith("divergent-write handler failed:") }

    private fun DeadLetter.stamped(): Stamped<*> =
        (invocation as HostedPortInvocation).invocation.args.single() as Stamped<*>

    // ------------------------------------------ [MEM1-13] example 4

    @Test
    fun `divergent writes are surfaced once each on the dead-letter outlet and to every handler`() {
        val t = Triangle()
        val letters = t.p.deadLetters()

        // P is cut off from BOTH peers: A keeps leading, and every write it
        // makes from here reaches nobody.
        t.pq.partition()
        t.pr.partition()
        t.controller.runToIdle()

        t.p.ops(t.a).increment(5)
        t.p.ops(t.a).increment(6)
        t.controller.runToIdle()
        t.a.total shouldBe 11L

        val surfaced = mutableListOf<Surfaced>()
        t.p.replication.onDivergentWrite { logicalId, fenced, canonical, payload ->
            surfaced += Surfaced(logicalId, fenced, canonical, payload)
        }

        // Q mints the superseding mark. R folds it over the surviving Q–R
        // loopback; P does not — a mirrored mark is not re-announced onward.
        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        withClue("the mark must NOT relay to P through R — that premise is what makes this example measure a step-down ON THE HEAL") {
            t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
        }
        t.r.replication.leaderOf(t.id)!!.epoch shouldBe 2L
        letters.divergent().shouldBeEmpty()

        t.pq.heal()
        t.pr.heal()
        t.controller.runToIdle()

        // A stepped down on the P–Q heal, and surfaced both writes exactly once.
        val divergent = letters.divergent()
        divergent shouldHaveSize 2
        divergent.forEachIndexed { i, dl ->
            withClue("dead letter $i: ${dl.description}") {
                dl.description shouldContain t.id.toString()
                dl.description shouldContain "fencedEpoch=1"
                dl.description shouldContain "canonicalEpoch=2"
                dl.cause.shouldBeNull()
                val hosted = dl.invocation as HostedPortInvocation
                hosted.cellRef shouldBe t.aRef
                hosted.portName shouldBe "deltaInlet"
            }
        }
        withClue("production order, and the RAW delta inside the Stamped envelope") {
            divergent[0].stamped() shouldBe Stamped(1L, 5L)
            divergent[1].stamped() shouldBe Stamped(1L, 6L)
        }
        withClue("the handler receives the payload, not the envelope") {
            surfaced shouldBe listOf(
                Surfaced(t.id, 1L, 2L, 5L),
                Surfaced(t.id, 1L, 2L, 6L),
            )
        }

        // The winner's baseline replaced A's divergent 11 — B never received
        // either write, so both replicas settle on B's own history.
        t.b.total shouldBe 0L
        t.a.total shouldBe t.b.total
        t.a.baselinesAdopted shouldBe 1
        val demotions = t.a.becomeFollowerCalls
        demotions shouldBe 1

        // A second step-down surfaces nothing: the buffer was cleared by the
        // flush, and A produced no writes while armed under epoch 3.
        t.p.replication.designateLeader(LeaderMark(t.id, epoch = 3, leaderRef = t.aRef))
        t.controller.runToIdle()
        t.a.leading shouldBe true
        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 4, leaderRef = t.bRef))
        t.controller.runToIdle()
        t.a.leading shouldBe false
        withClue("nothing new to surface at the second step-down") {
            letters.divergent() shouldHaveSize 2
            surfaced shouldHaveSize 2
        }
        t.a.becomeFollowerCalls shouldBe demotions + 1
    }

    // ------------------------------------------ [MEM1-28] example 5

    @Test
    fun `the dead-letter path stands alone, and a closed handle stops only its own handler`() {
        // (a) no handler at all — the records still reach the outlet, and
        // nothing escapes the scheduler.
        run {
            val t = Triangle()
            val letters = t.p.deadLetters()
            t.pq.partition()
            t.pr.partition()
            t.controller.runToIdle()
            t.p.ops(t.a).increment(5)
            t.p.ops(t.a).increment(6)
            t.controller.runToIdle()
            t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
            t.controller.runToIdle()
            t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
            t.pq.heal()
            t.pr.heal()
            t.controller.runToIdle()
            letters.divergent() shouldHaveSize 2
        }

        // (b) one handler closed before the heal, a second left open: the
        // closed one is deregistered, the list is not.
        run {
            val t = Triangle()
            val letters = t.p.deadLetters()
            val closed = mutableListOf<Surfaced>()
            val open = mutableListOf<Surfaced>()
            val handle = t.p.replication.onDivergentWrite { i, f, c, pay -> closed += Surfaced(i, f, c, pay) }
            t.p.replication.onDivergentWrite { i, f, c, pay -> open += Surfaced(i, f, c, pay) }

            t.pq.partition()
            t.pr.partition()
            t.controller.runToIdle()
            t.p.ops(t.a).increment(5)
            t.p.ops(t.a).increment(6)
            t.controller.runToIdle()
            t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
            t.controller.runToIdle()
            t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L

            handle.close()
            t.pq.heal()
            t.pr.heal()
            t.controller.runToIdle()

            letters.divergent() shouldHaveSize 2
            closed.shouldBeEmpty()
            open shouldBe listOf(Surfaced(t.id, 1L, 2L, 5L), Surfaced(t.id, 1L, 2L, 6L))
        }
    }

    // ------------------------------------------ [MEM1-21] example 6

    @Test
    fun `a write the departed member already received is not counted as divergent`() {
        val t = Triangle()
        val letters = t.p.deadLetters()

        // Before any partition, so B and C both receive it.
        t.p.ops(t.a).increment(1)
        t.controller.runToIdle()
        t.b.total shouldBe 1L

        // ONLY P–Q is cut. R still reaches both, which is what makes this a
        // false-positive probe rather than example 4 again.
        t.pq.partition()
        t.controller.runToIdle()

        t.p.ops(t.a).increment(2)
        t.controller.runToIdle()

        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        withClue("A is stepped down by the mark P folds on the P–Q HEAL: Q's mark reaches R, and R does not relay it onward to P") {
            t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
        }

        withClue("each side is individually well-formed before the heal — its own history, nothing merged") {
            t.a.total shouldBe 3L
            t.b.total shouldBe 1L
        }

        t.pq.heal()
        t.controller.runToIdle()

        val divergent = letters.divergent()
        withClue("only the write made while B was away; the pre-partition increment(1) is not divergent") {
            divergent shouldHaveSize 1
            divergent.single().stamped() shouldBe Stamped(1L, 2L)
        }
        t.a.total shouldBe 1L
        t.b.total shouldBe 1L
    }

    /**
     * The closing half of the recording window, and the one the retained
     * buffer makes easy to get wrong: **recording stops when the departed
     * member returns**, even though what was already recorded is kept.
     *
     * Example 6 covers the opening half (a write made BEFORE the departure is
     * not divergent, because the tap does not exist yet). Neither of the two
     * mechanisms that close the window — the tap being unlinked on the disarm,
     * and the sink's `departed.isNotEmpty()` gate — is observable without a
     * write made in the gap between a return and the next supersession, which
     * no other example here has. Measured: with BOTH removed, this test
     * reddens and no other does.
     */
    @Test
    fun `a write made after the departed member returned is not divergent`() {
        val t = Triangle()
        val letters = t.p.deadLetters()

        // Arm: B leaves, A writes 5 while it is away.
        t.pq.partition()
        t.controller.runToIdle()
        t.p.ops(t.a).increment(5)
        t.controller.runToIdle()

        // Disarm: B is back, and A keeps leading and keeps writing.
        t.pq.heal()
        t.controller.runToIdle()
        t.a.leading shouldBe true
        t.p.ops(t.a).increment(9)
        t.controller.runToIdle()
        t.b.total shouldBe t.a.total

        // Only NOW is A superseded — over the R leg, so the mark reaches P by
        // the same partition/heal route the other examples use.
        t.pr.partition()
        t.controller.runToIdle()
        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        t.controller.runToIdle()
        t.a.leading shouldBe false

        val divergent = letters.divergent()
        withClue("the 5 written while B was away is divergent; the 9 written after B returned is not") {
            divergent shouldHaveSize 1
            divergent.single().stamped() shouldBe Stamped(1L, 5L)
        }
    }

    // ------------------------------------------ a throwing handler is caught

    @Test
    fun `a throwing divergent-write handler is dead-lettered and never stops the flush`() {
        val t = Triangle()
        val letters = t.p.deadLetters()
        val second = mutableListOf<Surfaced>()

        var seen = 0
        t.p.replication.onDivergentWrite { _, _, _, _ ->
            seen++
            if (seen == 1) throw IllegalStateException("handler boom")
        }
        t.p.replication.onDivergentWrite { i, f, c, pay -> second += Surfaced(i, f, c, pay) }

        t.pq.partition()
        t.pr.partition()
        t.controller.runToIdle()
        t.p.ops(t.a).increment(5)
        t.p.ops(t.a).increment(6)
        t.controller.runToIdle()
        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
        t.pq.heal()
        t.pr.heal()
        t.controller.runToIdle()

        letters.divergent() shouldHaveSize 2
        val failure = letters.handlerFailures()
        failure shouldHaveSize 1
        failure.single().description shouldContain "handler boom"
        withClue("the second tuple still reached the second handler") {
            second shouldBe listOf(Surfaced(t.id, 1L, 2L, 5L), Surfaced(t.id, 1L, 2L, 6L))
        }
        t.a.leading shouldBe false
    }

    // ------------------------------------------ nothing to surface

    /** A leader that never witnessed a departure has no tap at all, and nothing is divergent. */
    @Test
    fun `a leader that witnessed no departure surfaces nothing at its step-down`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = SingleWriterReplication(registry)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val a = SingleWriterReplicationTest.SwCounterCell(aRef).also { replication.replicate(it, host, LeaderMark(id, 1, aRef)) }
        SingleWriterReplicationTest.SwCounterCell(bRef).also { replication.replicate(it, host, LeaderMark(id, 1, aRef)) }
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
        controller.runToIdle()

        // A leads, writes, and ships — no departure was ever witnessed.
        (HostedCellProxy.create(aRef, registry, SingleWriterReplicationTest.WriteInletHolder::class.java)
            as SingleWriterReplicationTest.WriteInletHolder).writeInlet.call.increment(7)
        controller.runToIdle()
        a.total shouldBe 7L

        replication.designateLeader(LeaderMark(id, epoch = 2, leaderRef = bRef))
        controller.runToIdle()

        a.leading shouldBe false
        withClue("no departure, so nothing is divergent — neither the writes nor the tap's own baseline") {
            letters.divergent().shouldBeEmpty()
        }
    }

    /**
     * The armed half, and the `!baseline` guard's own pin.
     *
     * The tap is installed on the first departure, and
     * [SingleWriterReplicable]'s catch-up contract emits the leader's WHOLE
     * state as a `baseline` unit the instant any link forms while it leads —
     * so A's total of 7 arrives at the tap immediately, INSIDE the armed
     * window. Without the guard it is recorded and surfaced as a divergent
     * write of 7 that A never made. A's non-zero state before the partition is
     * therefore load-bearing here, not incidental.
     */
    @Test
    fun `a leader that witnessed a departure but produced no writes surfaces nothing`() {
        val t = Triangle()
        val letters = t.p.deadLetters()
        val surfaced = mutableListOf<Surfaced>()
        t.p.replication.onDivergentWrite { i, f, c, pay -> surfaced += Surfaced(i, f, c, pay) }

        // Non-zero state BEFORE the partition, so the tap's formation baseline
        // is a value that would be visible if it were ever recorded.
        t.p.ops(t.a).increment(7)
        t.controller.runToIdle()
        t.a.total shouldBe 7L

        t.pq.partition()
        t.pr.partition()
        t.controller.runToIdle()
        // ... and no writes at all while armed.
        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
        t.pq.heal()
        t.pr.heal()
        t.controller.runToIdle()

        t.a.leading shouldBe false
        letters.divergent().shouldBeEmpty()
        surfaced.shouldBeEmpty()
    }

    // ------------------------------------------ ordering against onStepDown

    /**
     * f7h.5-D4's ordering half: surfacing runs BEFORE `notifyStepDown`, so an
     * `onStepDown` listener already observes the records.
     *
     * Asserted on `supervisionAccounting().deadLetters`, which
     * `DeadLetters.deadLetter` increments SYNCHRONOUSLY — the outlet emission
     * is scheduler-deferred (`ManagedHost`'s `deadLetters` collaborator), so
     * the outlet itself would still be empty inside the listener however the
     * order ran, and asserting on it would be vacuous.
     */
    @Test
    fun `onStepDown fires exactly once per demotion and after the surfacing`() {
        val t = Triangle()
        val letters = t.p.deadLetters()

        t.pq.partition()
        t.pr.partition()
        t.controller.runToIdle()
        t.p.ops(t.a).increment(5)
        t.p.ops(t.a).increment(6)
        t.controller.runToIdle()

        val before = t.p.host.supervisionAccounting().deadLetters
        var fires = 0
        var countInsideListener = -1L
        t.p.replication.onStepDown {
            fires++
            countInsideListener = t.p.host.supervisionAccounting().deadLetters
        }

        t.q.replication.designateLeader(LeaderMark(t.id, epoch = 2, leaderRef = t.bRef))
        t.controller.runToIdle()
        t.p.replication.leaderOf(t.id)!!.epoch shouldBe 1L
        t.pq.heal()
        t.pr.heal()
        t.controller.runToIdle()

        fires shouldBe 1
        withClue("both divergent writes were already counted when the step-down listener ran") {
            countInsideListener shouldBe before + 2
        }
        letters.divergent() shouldHaveSize 2
    }
}
