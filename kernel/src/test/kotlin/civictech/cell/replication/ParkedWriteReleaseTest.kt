package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * F5 of MEM1 (epic computenet-f7h, feature computenet-f7h.5, task
 * computenet-f7h.5.3): the **parked-write release** half — a write a follower
 * command-forwarded to a leader that then went away sits parked at that dead
 * ref, and is re-addressed to the winner and applied exactly once when the
 * fold names one.
 *
 * Owns feature rules [MEM1-16] (both clauses — the release, and the
 * mistimed-release control) and [MEM1-24], feature examples 1, 2, 3 and 7, and
 * decisions f7h.5-D2 (as refined: no `replicasOf` gate at release; see
 * `SingleWriterReplication.releaseParked`), D5 and D6.
 *
 * The divergent-write half — recording a *leader's own* deltas produced while a
 * member was away — is [DivergentWriteSurfacingTest], and nothing here touches
 * it except to read its dead-letter output in the mistimed-release control.
 *
 * ## Two premises this file depends on, asserted rather than assumed
 *
 * - **A partition parks, it does not drop.** `Loopback.partition()` runs
 *   `unpublishRemotes`, so the far leader's ref loses its location on this
 *   registry and `LocationRegistry.deliver` parks in order. Every example below
 *   asserts `parkedFor(aRef)` before it asserts anything about the release, so
 *   a base on which the write was dropped (or delivered anyway) fails loudly
 *   here instead of silently making the release look unnecessary.
 * - **Nothing else moves a parked write to a DIFFERENT ref.** The registry's
 *   own `install` drain releases into whatever location the SAME ref next gets
 *   — right for a RESTART preserving `instanceId`, useless for a new leader.
 *   Example 2 is the direct pin: without a designation the write stays parked
 *   however often the scheduler runs to idle.
 */
class ParkedWriteReleaseTest {

    // --------------------------------------------------------------- fixture

    /**
     * One peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Copied inline from [SingleWriterReplicationTest]'s
     * private `Peer` — the established idiom here ([StepDownTest]'s `Rig`,
     * [DivergentWriteSurfacingTest]'s `Peer`) rather than widening a fixture
     * another task owns. Manual election posture throughout: nothing below
     * needs a claim, every mark is driven by `designateLeader`.
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
                Use.fixed(Propagate<DeadLetter> { seen += it }, PortRef.generate()),
            )
            return seen
        }
    }

    /**
     * Two peers P and Q over one loopback, and one single-writer logical cell:
     * A on P (instance 0) leading at epoch 1, B on Q (instance 1) following.
     * With [withC], Q additionally hosts C (instance 2) — the third replica the
     * mistimed-release control designates.
     *
     * **Replicas before peering**, for [LeaderMarkAnnounceTest]'s reason:
     * `replicate` registers the local replica and *then* folds its mark, so a
     * replica spawned after its peer already mirrored the mark would fold a
     * rejected duplicate and silently get no role. C is the deliberate
     * exception — it is a second replica on a registry that has ALREADY folded
     * `mark1`, so its own `designateLeader(mark1)` is fenced and it holds no
     * role until a later mark names it. That is exactly the state the control
     * needs, and it is what a late-spawned sibling looks like anyway.
     */
    private class Rig(withC: Boolean = false) {
        val controller = SimulationController()
        val p = Peer(controller)
        val q = Peer(controller)
        val id: UUID = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, epoch = 1, leaderRef = aRef)

        val a = p.replica(id, 0, mark1)
        val b = q.replica(id, 1, mark1)
        val c = if (withC) q.replica(id, 2, mark1) else null

        val loop = Peering.loopback(p.side, q.side)

        init {
            controller.runToIdle()
        }

        /**
         * Partition P from Q and park one forwarded `increment` at the now
         * location-less leader ref on Q's registry — the opening move of every
         * example here. Returns the parked queue for the caller to assert on.
         */
        fun partitionAndPark(vararg amounts: Long): List<HostedPortInvocation> {
            loop.partition()
            controller.runToIdle()
            amounts.forEach { q.ops(b).increment(it) }
            controller.runToIdle()
            return q.registry.parkedFor(aRef)
        }
    }

    /** Only the divergent-write records — the outlet also carries anything else the host reports. */
    private fun List<DeadLetter>.divergent(): List<DeadLetter> =
        filter { it.description.startsWith("single-writer divergent write:") }

    /**
     * A non-write invocation aimed at the ex-leader's own delta inlet, hand-built
     * as `surfaceDivergentWrites` builds one — with one deliberate difference.
     *
     * That one declares `parameterTypes = [Stamped]`, which is the *source*
     * signature and not the erased one: `Propagate.propagate` erases to
     * `(Object)`, so `Invocation.invoke`'s method lookup finds nothing and the
     * unit dead-letters instead of landing. Harmless there — nothing redelivers
     * a surfaced record — and fatal here, because it would make this example's
     * "and it never reached B" half pass for the wrong reason: a misrouted unit
     * would fail to apply anyway. Measured (computenet-f7h.5.3 mutation M2b):
     * with `[Stamped]` and the release's port filter dropped, the
     * `receivedDeltas` assertion stayed GREEN. `[Object]` is what
     * `HostedCellProxy` actually puts on the wire for this port, so a unit that
     * this task's filter wrongly re-addressed WOULD be applied at B.
     */
    private fun deltaInletInvocation(target: CellRef, unit: Stamped<Long>) = HostedPortInvocation(
        cellRef = target,
        portName = "deltaInlet",
        type = HostedPortInvocation.Type.PORT_API,
        invocation = Invocation(
            methodName = "propagate",
            parameterTypes = listOf(Any::class.java.name),
            args = listOf(unit),
            context = null,
        ),
    )

    // ------------------------------------------------ [MEM1-16] example 1

    @Test
    fun `a write parked at a superseded leaderRef is released onto the winner exactly once`() {
        val r = Rig()
        val parked = r.partitionAndPark(3)

        parked shouldHaveSize 1
        parked.single().portName shouldBe "writeInlet"
        r.b.total shouldBe 0L

        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 2, leaderRef = r.bRef)) shouldBe true
        r.controller.runToIdle()

        withClue("the release drains the old ref's queue rather than copying out of it") {
            r.q.registry.parkedFor(r.aRef).shouldBeEmpty()
        }
        r.b.total shouldBe 3L
        withClue("applied by B's REAL api, once — `total` alone cannot say which replica applied it") {
            r.b.realWrites shouldBe 1
        }

        // The heal is where an unreleased write would finally reach A, and
        // where a released-but-not-drained one would be replayed a second time.
        r.loop.heal()
        r.controller.runToIdle()

        r.a.total shouldBe 3L
        withClue("A caught up by BASELINE from the winner; it never applied the 3 itself") {
            r.a.realWrites shouldBe 0
            r.a.baselinesAdopted shouldBeGreaterThanOrEqual 1
        }
        withClue("A.ref's republish must not resurrect anything — the queue was drained, not snapshotted") {
            r.q.registry.parkedFor(r.aRef).shouldBeEmpty()
        }
        r.b.realWrites shouldBe 1
    }

    // ------------------------------------------------ [MEM1-16] example 2

    @Test
    fun `without a superseding mark the parked write stays where it is`() {
        val r = Rig()
        r.partitionAndPark(3) shouldHaveSize 1

        // The registry's own drain fires on a publish of the SAME ref; nothing
        // in an idle scheduler moves a write to a different one.
        r.controller.runToIdle()
        r.controller.runToIdle()

        r.q.registry.parkedFor(r.aRef) shouldHaveSize 1
        r.q.registry.parkedFor(r.aRef).single().portName shouldBe "writeInlet"
        r.b.total shouldBe 0L
        r.b.realWrites shouldBe 0
    }

    // ------------------------------------------------ [MEM1-16] the port filter

    @Test
    fun `a parked invocation that is not a forwarded write port stays parked at the old ref`() {
        val r = Rig()
        r.partitionAndPark(3) shouldHaveSize 1

        // Aimed at the ex-leader's own delta inlet — the same shape
        // `surfaceDivergentWrites` builds — and NOT a command-forwarded write.
        r.q.registry.deliver(deltaInletInvocation(r.aRef, Stamped(1L, 99L)))
        r.controller.runToIdle()
        r.q.registry.parkedFor(r.aRef) shouldHaveSize 2

        val deltasBefore = r.b.receivedDeltas
        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 2, leaderRef = r.bRef)) shouldBe true
        r.controller.runToIdle()

        val stillParked = r.q.registry.parkedFor(r.aRef)
        withClue("only the write is re-addressed; the delta-inlet unit is redelivered unchanged and re-parks") {
            stillParked shouldHaveSize 1
            stillParked.single().portName shouldBe "deltaInlet"
        }
        r.b.total shouldBe 3L
        withClue("re-addressing a unit aimed at the old INSTANCE would land it on B's delta inlet") {
            r.b.receivedDeltas shouldBe deltasBefore
        }
    }

    // ------------------------------------------------ [MEM1-16] park order

    @Test
    fun `parked writes are released in park order and applied in that order`() {
        val r = Rig()
        r.partitionAndPark(1, 2, 4) shouldHaveSize 3

        // Recorded straight off B's outlet: a plain `subscribe` adds a consumer
        // without going through `linking`, so no catch-up baseline is fired at
        // it and the record is exactly what B produced.
        val emitted = mutableListOf<Stamped<Long>>()
        r.b.deltaOutlet.subscribe(Use.fixed(Propagate<Stamped<Long>> { emitted += it }, PortRef.generate()))

        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 2, leaderRef = r.bRef)) shouldBe true
        r.controller.runToIdle()

        r.b.total shouldBe 7L
        r.b.realWrites shouldBe 3
        withClue("park order is delivery order, and delivery order is apply order") {
            emitted shouldBe listOf(Stamped(2L, 1L), Stamped(2L, 2L), Stamped(2L, 4L))
        }
    }

    // ------------------------------------------------ [MEM1-16] example 3, f7h.5-D6

    /**
     * The **mistimed-release control** (f7h.5-D6). Two marks are folded
     * back-to-back with no scheduler turn between them, so the release onto B
     * at epoch 2 is already in flight when C supersedes B at epoch 3. Which
     * branch the scheduler takes is not asserted — the invariant is asserted
     * for whichever one it takes, and the branch is printed:
     *
     * - **B never applies it.** B was demoted before the enqueued invocation
     *   ran, so its write inlet forwards to C and B's real api is untouched.
     * - **No value below the canonical epoch survives anywhere.** Whatever B
     *   emitted at epoch 2 (0 in the expected branch) is exactly what the
     *   dead-letter outlet must account for as divergent — asserted against
     *   B's own emission record, not against a constant.
     */
    @Test
    fun `a release that is superseded before it lands is inert at the demoted leader`() {
        val r = Rig(withC = true)
        val c = r.c!!
        r.partitionAndPark(3) shouldHaveSize 1

        val letters = r.q.deadLetters()
        val emittedByB = mutableListOf<Stamped<Long>>()
        r.b.deltaOutlet.subscribe(Use.fixed(Propagate<Stamped<Long>> { emittedByB += it }, PortRef.generate()))

        // Both folds, with NO runToIdle between them: the epoch-2 release is
        // enqueued and the epoch-3 mark lands before it is executed.
        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 2, leaderRef = r.bRef)) shouldBe true
        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 3, leaderRef = r.cRef)) shouldBe true
        r.controller.runToIdle()

        println("mistimed-release branch: C.total=${c.total} B.total=${r.b.total} B.realWrites=${r.b.realWrites}")

        withClue("B was demoted before the released write ran; its real api must never see it") {
            r.b.realWrites shouldBe 0
        }
        withClue("either the write reached C or it did not; both are admissible, nothing below epoch 3 is") {
            (c.total in setOf(0L, 3L)) shouldBe true
        }
        if (c.total == 3L) c.realWrites shouldBe 1

        withClue("no replica holds a value stamped below the canonical epoch 3") {
            emittedByB.none { !it.baseline && it.epoch < 3L } shouldBe true
            r.b.currentEpoch shouldBe 3L
            c.currentEpoch shouldBe 3L
        }
        withClue(
            "the epoch fence is what makes the mistimed release inert: B's promotion at epoch 2 " +
                "shipped C a baseline, and C — already at epoch 3 by the time the scheduler ran — " +
                "fenced it rather than adopting an epoch-2 state",
        ) {
            c.fencedDeltas shouldBe 1
        }
        withClue("a divergent letter for B iff B produced an epoch-2 delta after a departure") {
            letters.divergent().filter { (it.invocation as HostedPortInvocation).cellRef == r.bRef } shouldHaveSize
                emittedByB.count { !it.baseline && it.epoch == 2L }
        }
    }

    // ------------------------------------------------ [MEM1-24] example 7

    /**
     * [MEM1-24], f7h.5-D5: a `Leased` write during the election window is
     * Rejected at the follower, exactly as at any other time — it is neither
     * forwarded nor parked, so it cannot be released onto the winner later. The
     * `Leased` check in `forwardWrites` is untouched by this task; this pins
     * that the parked-write lane did not open a second door around it.
     */
    @Test
    fun `a Leased write mid-election is rejected, never parked and never released`() {
        val r = Rig()
        r.partitionAndPark(3) shouldHaveSize 1

        val rejections = r.q.deadLetters()
        r.q.ops(r.b).mark(Leased("x"))
        r.controller.runToIdle()

        withClue("the Leased write must not join the parked queue") {
            r.q.registry.parkedFor(r.aRef) shouldHaveSize 1
            r.q.registry.parkedFor(r.aRef).single().portName shouldBe "writeInlet"
        }
        rejections shouldHaveSize 1
        val cause = rejections.single().cause
        cause.shouldBeInstanceOf<IllegalStateException>()
        (cause as IllegalStateException).message shouldContain "Rejected"

        // ...and the designation still releases exactly the one real write.
        r.q.replication.designateLeader(LeaderMark(r.id, epoch = 2, leaderRef = r.bRef)) shouldBe true
        r.controller.runToIdle()

        r.q.registry.parkedFor(r.aRef).shouldBeEmpty()
        r.b.total shouldBe 3L
        r.b.realWrites shouldBe 1
    }
}
