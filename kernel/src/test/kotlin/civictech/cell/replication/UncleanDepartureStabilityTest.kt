package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-9 of `computenet-9sm.5` — the **unclean departure**: a member whose host
 * is cut off and never `evict`ed. [KE3-27] (the notice is emitted, once, on
 * both carriers), [KE3-28] (nothing unfreezes the read but an operator) and
 * [KE3-24] (the observer's topology view and the gossiped member set diverge).
 *
 * **Why this file exists next to two siblings.** `StabilityFreezeNoticeTest`
 * pins [Replication.onStabilityStall]'s wiring against *phantom* rows injected
 * straight into one peer's companion — total control, no mesh.
 * [DepartureStabilityPinTest] pins the stability *read* across clean evict,
 * partitioned evict and heal. Neither exercises the shape BS-9 is about: a
 * three-peer gossip mesh in which C is severed and **no eviction is ever
 * called**, so nothing in the system is told the member is gone. That is the
 * only shape in which the freeze is a real freeze rather than an injected one.
 *
 * **The unclean teardown.** [Peering.Loopback.partition] on both of C's
 * peerings severs them and `unpublishRemotes` on both registries, so A's
 * [civictech.cell.host.InstanceIndex.instancesOf] drops C while the grow-only
 * announced `members()` still names C's slot (FU-2). From A and B that is a
 * host torn down without evict, and C's host is never touched again. (The
 * `testkit` churn rig's `DepartureMode.CRASH_UNCLEAN` is NOT usable for this:
 * `concord/corpus/DISPUTES.md` §`CHA3-42-stall-notice-unclean-departure`
 * records that its crashed cells keep relaying as zombies.)
 *
 * **Non-vacuousness: per-test tracing** (mutation-check.md's test-only route —
 * every production file this pins is outside this task's claim, so the
 * mutations belong to the reviewer). Each assertion below names the production
 * conditional it discriminates:
 *
 * - the arrival bound traces [civictech.cell.consistency.StabilityFreezeDetector]'s
 *   `count >= threshold` latch and its `lagWitness` / `unchanged` conjunction —
 *   a detector that fired eagerly would breach [MIN_STALL_EVALUATION], one that
 *   never fired would breach [MAX_STALL_EVALUATION];
 * - the frozen-read control traces [CausalStability]'s open-set predicate
 *   (C's slot stays in it, because `members()` is grow-only and C is neither
 *   closed nor suspended);
 * - the downstream copy traces `Replication.onStabilityStall`'s
 *   `localReplicas[logicalId]…notifyDownstream(replica, notice)` fan-out;
 * - the single Resume traces the detector's retraction branch
 *   (`rows[slot] != previousRows[slot] || slot in closed || slot !in open`);
 * - the [KE3-24] pin traces `Replication.evict`'s reachable-peer branch and
 *   `onUnpublish`'s instance reconciliation.
 *
 * **The negative property is not vacuous, and here is how that is
 * established.** "Nothing unfreezes it but the operator" would be worthless if
 * the retraction branch never ran. It runs on *every* evaluation while the
 * latch is held, and the test counts them: [Rig.deltas] taps the companion
 * outlet, so `stalledEvaluations` below is the measured number of times the
 * retraction branch was entered and declined to fire. The very same branch
 * demonstrably *can* fire in this same rig — it is what produces the single
 * `Resume` after the operator heals and evicts, in the last test.
 *
 * **The limit of that count, measured.** `stalledEvaluations` counts retraction
 * checks only while the slot is *actually* latched; on its own it is NOT a
 * discriminator against a detector that never latches at all, because
 * [Rig.writeThroughTheFreeze] then leaves its `evaluationsAtStall` at -1 and
 * returns the whole evaluation total, which still exceeds the bound. The task
 * review measured exactly that: with the detector's `count >= threshold` latch
 * raised so it never trips, the [KE3-28] control below stayed GREEN while the
 * [KE3-27] test went red. What pins the latch is `notices shouldBe
 * listOf(expected)` in the [KE3-27] test and `notices.size shouldBe 1` in the
 * unfreeze test; the control is read together with those, not alone. The
 * review's other two production mutations — the retraction guard forced false
 * (unfreeze test red at its `Resume` assertion) and the `notifyDownstream`
 * fan-out removed (the `downstream` assertions red ALONE, the `notices` ones
 * still green) — are what make the rest of this non-vacuous.
 */
class UncleanDepartureStabilityTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private companion object {
        /**
         * Write steps after the teardown. MEASURED, not the bead's suggested
         * 500: one step drains to exactly four companion deltas (four detector
         * evaluations), identically on every seed, so 40 steps are 160
         * evaluations. The latch trips at the 5th — inside the SECOND write
         * step — and 40 steps therefore hold it across 152 further evaluations:
         * two orders of magnitude more retraction checks than the threshold
         * needs, at a cost that keeps a 20-seed sweep well inside a minute.
         * 500 steps would buy no additional production branch: once a slot is
         * latched, [civictech.cell.consistency.StabilityFreezeDetector.evaluate]
         * runs only its retraction check for it, and that is already exercised
         * 152 times here.
         */
        const val STEPS = 40

        /**
         * A stall cannot arrive before the 4th evaluation after registration:
         * the detector's `previousRows` is empty at registration, so the first
         * evaluation reads every row as *changed* and cannot count, leaving
         * H = 3 consecutive qualifying evaluations to accumulate.
         */
        const val MIN_STALL_EVALUATION = 4

        /**
         * ...and it must arrive within three write steps' worth of evaluations
         * (3 x 4). Both bounds are the [KE3-27] "within a bounded number of
         * steps after the next stability listener evaluation" clause, made
         * checkable in both directions.
         */
        const val MAX_STALL_EVALUATION = 12
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** A, B, C converged on one logical [SetCell], A's per-origin source at 9. */
    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)

        /** Kept: BS-9 cuts C's two peerings and the unfreeze step re-opens them. */
        val ab = Peering.loopback(a.side, b.side)
        val bc = Peering.loopback(b.side, c.side)
        val ac = Peering.loopback(a.side, c.side)

        val logicalId: UUID = UUID.randomUUID()
        val ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
        val rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
        val rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }

        val opA: SetOps<String>
        val opB: SetOps<String>
        val companionA: WatermarkCell
        val slotA: UUID
        val slotB: UUID
        val slotC: UUID

        /** A's CP-B2 per-outlet-epoch source: A-only, so never in the MIN. */
        val epochSource: UUID

        /** A's per-origin tag source — the one source every assertion is about. */
        val s: UUID

        /**
         * Companion-outlet deltas so far. One delta is one
         * [civictech.cell.consistency.StabilityFreezeDetector] evaluation, so
         * this is the evaluation clock every timing assertion is stated in.
         */
        var deltas: Int = 0
            private set

        /** Notices handed to the app listener on [Replication.onStabilityStall]. */
        val notices = mutableListOf<StallNotice>()

        /** The same notices as seen by a real downstream Suspension edge (9sm.5-D7). */
        val downstream = mutableListOf<StallNotice>()

        /** [deltas] at the moment the first notice arrived; -1 if none has. */
        var stallEvaluation: Int = -1
            private set

        /** Every WAIT observation of A's, then of B's, stable frontier, in order. */
        val observationsA = mutableListOf<Long?>()
        val observationsB = mutableListOf<Long?>()

        init {
            controller.runToIdle()
            opA = (HostedCellProxy.create(ra.ref, a.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
            opB = (HostedCellProxy.create(rb.ref, b.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
            repeat(9) { i -> opA.add("e$i"); controller.runToIdle() }
            controller.runToIdle()
            companionA = a.replication.watermarkOf(logicalId)!!
            slotA = WatermarkCell.slotId(a.replication.watermarkRef(ra.ref))
            slotB = WatermarkCell.slotId(a.replication.watermarkRef(rb.ref))
            slotC = WatermarkCell.slotId(a.replication.watermarkRef(rc.ref))
            @Suppress("UNCHECKED_CAST")
            epochSource = (ra.outlet as FanOutlet<Propagate<SetDelta<String>>>).waveState().sourceId
            s = (companionA.rows().getValue(slotA).keys - epochSource).single()
            companionA.outlet.tap(Use.fixed(Propagate<WatermarkDelta> { deltas++ }, PortRef.generate()))
        }

        /**
         * Register both carriers of the notice: the app listener, and a REAL
         * downstream link off A's replica outlet whose inlet handles
         * `Protocols.Suspension` (the `FanOutletTest` idiom). It must be a real
         * `linkTo`, not a `Use.fixed` subscription: `notifyDownstream` walks
         * `linking.links`, so a subscriber that never handshook has no edge to
         * be told about and the downstream assertion would pass vacuously.
         *
         * The tap counter is installed in [init], BEFORE this, so the
         * evaluation clock and the detector's own clock start together.
         */
        fun arm() {
            a.replication.onStabilityStall(logicalId) {
                if (stallEvaluation < 0) stallEvaluation = deltas
                notices += it
            }
            val inlet = FanInlet.create<Propagate<SetDelta<String>>>().also {
                it.serve(object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) = Unit
                })
            }
            ProtocolSupport.of(inlet).handle(Protocols.Suspension) { _, m -> downstream += m as StallNotice }
            @Suppress("UNCHECKED_CAST")
            val result = (ra.outlet as FanOutlet<Propagate<SetDelta<String>>>)
                .linkTo(inlet as LinkFrom<Propagate<SetDelta<String>>>)
            check(result is LinkResult.Connected) { "the edge must really open: $result" }
        }

        /** A's stability read for [s]; absent (bottom) reads as null. */
        fun stableOnA(degrade: Boolean = false): Long? =
            a.replication.stableFrontier(logicalId, degrade).perSource[s]
                .also { if (!degrade) observationsA += it }

        /** B's stability read for the same source — the second survivor. */
        fun stableOnB(): Long? =
            b.replication.stableFrontier(logicalId).perSource[s].also { observationsB += it }

        /** The unclean teardown: cut both of C's peerings and never touch C again. */
        fun partitionC() {
            ac.partition()
            bc.partition()
            controller.runToIdle()
        }

        /** The operator's first action: re-open both of C's peerings. */
        fun healC() {
            ac.heal()
            bc.heal()
            controller.runToIdle()
        }

        /**
         * [STEPS] writes alternating A, B — each drained, each followed by a
         * recorded WAIT observation on both survivors. Returns the number of
         * evaluations that ran while the latch was already held: the measured
         * count of retraction checks that declined to fire.
         */
        fun writeThroughTheFreeze(): Int {
            var evaluationsAtStall = -1
            repeat(STEPS) { i ->
                if (i % 2 == 0) opA.add("post$i") else opB.add("post$i")
                controller.runToIdle()
                stableOnA()
                stableOnB()
                if (evaluationsAtStall < 0 && notices.isNotEmpty()) evaluationsAtStall = deltas
            }
            return deltas - evaluationsAtStall
        }
    }

    /** Bottom (absent) sorts below every counter for the monotonicity check. */
    private fun List<Long?>.assertNonDecreasing() {
        val asLevels = map { it ?: Long.MIN_VALUE }
        asLevels.zipWithNext().forEach { (earlier, later) -> (later >= earlier) shouldBe true }
    }

    @Test
    fun `rig sits where this file claims - three converged peers at 9 and a partition that unpublishes C`() {
        val rig = Rig(0L)

        rig.stableOnA() shouldBe 9L
        rig.stableOnB() shouldBe 9L
        rig.companionA.members() shouldBe setOf(rig.slotA, rig.slotB, rig.slotC)
        rig.companionA.closed().shouldBeEmpty()
        rig.companionA.suspended().shouldBeEmpty()
        rig.a.registry.instances.instancesOf(rig.logicalId) shouldContain rig.rc.ref

        rig.arm()
        rig.partitionC()

        // The teardown itself moves no companion lattice on A, so the detector
        // has not yet been asked anything: no notice can have been emitted.
        rig.deltas shouldBe 0
        rig.notices.shouldBeEmpty()
        rig.downstream.shouldBeEmpty()

        // ...and C's row is still there, at 9, on the source under test.
        rig.companionA.rows().getValue(rig.slotC)[rig.s] shouldBe 9L
    }

    @Test
    fun `KE3-27 BS-9 an unclean departure yields exactly one STABILITY_FROZEN Stall on both carriers, bounded - 20 seeds`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed)
            rig.stableOnA() shouldBe 9L
            rig.arm()
            rig.partitionC()
            rig.writeThroughTheFreeze()

            val expected = StallNotice.Stall(StallReason.STABILITY_FROZEN, Timestamp(rig.s, 9L), rig.slotC)

            // Exactly one, on the app listener AND on the downstream Suspension
            // edge: 9sm.5-D5's shape (the frozen slot named by `slot`, the wave
            // position by `timestamp`) and 9sm.5-D7's fan-out.
            rig.notices shouldBe listOf(expected)
            rig.downstream shouldBe listOf(expected)
            (rig.notices.single() as StallNotice.Stall).recoverable shouldBe true

            // Bounded in BOTH directions, which is what makes the timing a
            // discriminator: a detector that latched on the first qualifying
            // evaluation would breach the lower bound, one that never latched
            // (or latched too late) the upper.
            (rig.stallEvaluation >= MIN_STALL_EVALUATION) shouldBe true
            (rig.stallEvaluation <= MAX_STALL_EVALUATION) shouldBe true
        }
    }

    @Test
    fun `KE3-28 BS-9 control - the read stays pinned at C's last row and nothing retracts the latch - 20 seeds`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed)
            rig.arm()
            rig.partitionC()
            val stalledEvaluations = rig.writeThroughTheFreeze()

            // Every observation on BOTH survivors, for the whole budget, equals
            // C's last row value. C is still in CausalStability's open set, so
            // the pointwise MIN cannot move past it however much A and B write.
            rig.observationsA.forEach { it shouldBe 9L }
            rig.observationsB.forEach { it shouldBe 9L }

            // ...and it is genuinely frozen, not merely departed: C's slot is in
            // neither the closed nor the suspended set on A's companion. Nothing
            // in the kernel closed, suspended or evicted anything in response to
            // the notice ([KE3-28]).
            rig.companionA.closed() shouldNotContain rig.slotC
            rig.companionA.suspended() shouldNotContain rig.slotC

            // No Resume within the budget — and this is not vacuous: the
            // detector's retraction branch was entered on every one of these
            // evaluations while the latch was held, and declined each time.
            rig.notices.filterIsInstance<StallNotice.Resume>().shouldBeEmpty()
            rig.downstream.filterIsInstance<StallNotice.Resume>().shouldBeEmpty()
            (stalledEvaluations > 100) shouldBe true
        }
    }

    @Test
    fun `KE3-24 BS-9 the observer's instance view has dropped C while the gossiped member set still names it - 20 seeds`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed)
            rig.arm()
            rig.partitionC()
            rig.writeThroughTheFreeze()

            // The divergence [KE3-24] is about: `partition()` unpublished C from
            // A's registry, but `members()` is grow-only (FU-2) and no departure
            // was ever announced, so the union that forms the WAIT open set keeps
            // C open — which is exactly why the read above is frozen.
            rig.a.registry.instances.instancesOf(rig.logicalId) shouldNotContain rig.rc.ref
            rig.a.replication.watermarkOf(rig.logicalId)!!.members() shouldContain rig.slotC
        }
    }

    @Test
    fun `BS-9 unfreeze is manual - only heal plus evict yields one Resume and advances stability - 20 seeds`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed)
            rig.arm()
            rig.partitionC()
            rig.writeThroughTheFreeze()
            rig.notices.size shouldBe 1
            rig.stableOnA() shouldBe 9L

            // The operator acts, and only now. `heal()` re-opens C's peerings;
            // C then catches up by anti-entropy, and `evict` (reachable peers
            // exist again) drains, despawns and closes C's row.
            rig.healC()
            rig.c.replication.evict(rig.rc, rig.c.host) shouldBe true
            rig.controller.runToIdle()

            // EXACTLY one Resume, on both carriers. Which of the retraction
            // branches fired first — C's row advancing on heal, or the `closed`
            // marker arriving from the evict — is deliberately not asserted: the
            // latch clears once and the second branch then finds nothing latched.
            rig.notices.drop(1) shouldBe listOf(StallNotice.Resume)
            rig.downstream.drop(1) shouldBe listOf(StallNotice.Resume)

            // The read advances past C. A wrote on 20 of the 40 steps, so its own
            // source has moved from 9 to 29; >= 12 is the bead's floor.
            rig.companionA.closed() shouldContain rig.slotC
            val advanced = rig.stableOnA()!!
            (advanced >= 12L) shouldBe true
            advanced shouldBe 29L

            // ...and no observation of A's WAIT read ever went backwards, across
            // the freeze and out the other side.
            rig.observationsA.assertNonDecreasing()
        }
    }
}
