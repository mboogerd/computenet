package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WatermarkDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-8 / BS-10 / BS-11 of `computenet-9sm.5` — the **causal-stability** read
 * ([Replication.stableFrontier], E3.5) across the three departure shapes, on
 * the three-peer gossip topology [MemberDepartureFrontierTest] pins the *wave*
 * frontier on. That test asks whether held waves are released; this one asks
 * only what the stability MIN reads, which is the [KE3-25] / [KE3-26] /
 * [KE3-29] half of the feature.
 *
 * **The rig.** A, B, C each host one replica of one logical [SetCell], meshed
 * by three [Peering.loopback]s whose handles are KEPT — BS-10 and BS-11 need
 * [Peering.Loopback.partition] and [Peering.Loopback.heal]. Nine `add`s on A
 * put every slot's row at `s→9` (`s` = A's per-origin tag source; each slot
 * additionally carries its own CP-B2 epoch column, present in exactly one row
 * and therefore never in the MIN — see `rig sits where this file claims`).
 * [Replication.stableFrontier] on A is then `{s→9}`, and every assertion below
 * is about that one number.
 *
 * **Non-vacuousness.** BS-8 carries its own red-if-wrong control *in the test*:
 * the production seam `evict(closeDepartedRow = false)` re-runs the identical
 * script with the row left open, and the frontier then demonstrably does NOT
 * advance. BS-10 and BS-11 have no such switch and are traced per assertion to
 * the production branch they pin: `evict`'s `partitionSuspended` branch (the
 * `false` return and the `suspend()`), `linkOut`'s `heal (G-45)` branch (the
 * `resume()` A observes), and [civictech.cell.consistency.CausalStability]'s
 * open-set predicate (`degrade` dropping an odd-epoch slot). Mutating any of
 * those needs a production file this task does not own; the reviewer runs it.
 *
 * **BS-10 asserts a limitation, not a workaround.** The feature's original
 * BS-10 example expected A's DEGRADE read to reach 12 while C is partitioned.
 * It cannot: [Peering.Loopback.partition] unpublishes on both sides, and
 * `Replication`'s `onUnpublish` reconciliation drops C's outbound gossip links,
 * so C's `suspend()` epoch never reaches A while the cut holds. That is
 * asserted here as its own fact (A's `suspended()` lacks C's slot), and the
 * DEGRADE read is pinned where the marker IS known — by injecting the suspend
 * epoch into A's companion through `deltaInlet`, the phantom idiom of
 * [StabilityAdvanceTest]. The correction is the feature's (2026-09-06); it is
 * re-verified here against the code at this commit.
 */
class DepartureStabilityPinTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** A, B, C converged on one logical [SetCell] with A's source at 9. */
    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)

        /** Kept, unlike [MemberDepartureFrontierTest]'s: BS-10/BS-11 cut and re-open them. */
        val ab = Peering.loopback(a.side, b.side)
        val bc = Peering.loopback(b.side, c.side)
        val ac = Peering.loopback(a.side, c.side)

        val logicalId: UUID = UUID.randomUUID()
        val ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
        val rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
        val rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }

        val opA: SetOps<String>
        val companionA: WatermarkCell
        val slotA: UUID
        val slotB: UUID
        val slotC: UUID

        /** A's CP-B2 per-outlet-epoch source: A-only, and therefore never in the MIN. */
        val epochSource: UUID

        /** A's per-origin tag source — the one source every assertion here is about. */
        val s: UUID

        /** Every WAIT (`degrade = false`) observation of A's stable frontier, in order. */
        val observations = mutableListOf<Long?>()

        init {
            controller.runToIdle()
            opA = (HostedCellProxy.create(ra.ref, a.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
            repeat(9) { i -> opA.add("e$i"); controller.runToIdle() }
            controller.runToIdle()
            companionA = a.replication.watermarkOf(logicalId)!!
            slotA = WatermarkCell.slotId(a.replication.watermarkRef(ra.ref))
            slotB = WatermarkCell.slotId(a.replication.watermarkRef(rb.ref))
            slotC = WatermarkCell.slotId(a.replication.watermarkRef(rc.ref))
            @Suppress("UNCHECKED_CAST")
            epochSource = (ra.outlet as FanOutlet<Propagate<SetDelta<String>>>).waveState().sourceId
            s = (companionA.rows().getValue(slotA).keys - epochSource).single()
        }

        /** Three further `add`s on A, each drained, reading A's WAIT frontier after each. */
        fun addThreeOnA() = repeat(3) { i ->
            opA.add("post$i")
            controller.runToIdle()
            stableOnA()
        }

        /**
         * A's stability read for [s]. Absent (bottom) reads as null. WAIT reads
         * are recorded in [observations]; a DEGRADE read is a different read and
         * is deliberately not part of the monotone sequence.
         */
        fun stableOnA(degrade: Boolean = false): Long? =
            a.replication.stableFrontier(logicalId, degrade).perSource[s]
                .also { if (!degrade) observations += it }

        /** B's stability read for the same source — the second survivor of BS-8. */
        fun stableOnB(): Long? = b.replication.stableFrontier(logicalId).perSource[s]

        /** Cut C off from both of its peers ([Peering.Loopback.partition] on each). */
        fun partitionC() {
            ac.partition()
            bc.partition()
            controller.runToIdle()
        }

        /** Re-open both of C's peerings as fresh instances ([Peering.Loopback.heal]). */
        fun healC() {
            ac.heal()
            bc.heal()
            controller.runToIdle()
        }
    }

    /** Bottom (absent) sorts below every counter for the monotonicity check. */
    private fun List<Long?>.assertNonDecreasing() {
        val asLevels = map { it ?: Long.MIN_VALUE }
        asLevels.zipWithNext().forEach { (earlier, later) -> (later >= earlier) shouldBe true }
    }

    @Test
    fun `rig sits where this file claims - every slot at 9 on one shared source, epoch columns excluded`() {
        val rig = Rig(0L)

        // Nine adds on A put A's own row at 9, and the gossip mesh carries the
        // same source into B's and C's rows: the MIN is 9 on exactly one source.
        rig.a.replication.stableFrontier(rig.logicalId).perSource shouldBe mapOf(rig.s to 9L)
        listOf(rig.slotA, rig.slotB, rig.slotC).forEach { slot ->
            rig.companionA.rows().getValue(slot)[rig.s] shouldBe 9L
        }
        // Each slot carries its OWN epoch column, present in exactly one row, so
        // no epoch source survives the pointwise MIN. Exact equality above is
        // what keeps that true; this states why.
        rig.companionA.rows().getValue(rig.slotA).keys shouldBe setOf(rig.s, rig.epochSource)
        rig.companionA.rows().getValue(rig.slotB).keys shouldNotContain rig.epochSource
        rig.companionA.members() shouldBe setOf(rig.slotA, rig.slotB, rig.slotC)
        rig.companionA.closed() shouldBe emptySet()
        rig.companionA.suspended() shouldBe emptySet()
    }

    @Test
    fun `KE3-25 BS-8 a clean evict closes C's row and the survivors' stability advances past it - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val rig = Rig(seed)
            rig.stableOnA() shouldBe 9L
            rig.stableOnB() shouldBe 9L

            // A reachable peer remains (A, B), so eviction drains and despawns for
            // real and closes C's row (`evict`'s `closeDepartedRow` branch).
            rig.c.replication.evict(rig.rc, rig.c.host, closeDepartedRow = true) shouldBe true
            rig.controller.runToIdle()

            // The `closed` marker converged over the same gossip mesh as the data,
            // so C's slot leaves CausalStability's open set on A.
            rig.companionA.closed() shouldContain rig.slotC

            rig.addThreeOnA()

            // C no longer constrains: the MIN over {A, B} is 12 on both survivors.
            rig.stableOnA() shouldBe 12L
            rig.stableOnB() shouldBe 12L
            rig.observations.assertNonDecreasing()
        }
    }

    @Test
    fun `KE3-25 BS-8 control - with the departed row left open the survivors stay frozen at 9 - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val rig = Rig(seed)
            rig.stableOnA() shouldBe 9L

            // The PN-0c control seam: identical script, row NOT closed.
            rig.c.replication.evict(rig.rc, rig.c.host, closeDepartedRow = false) shouldBe true
            rig.controller.runToIdle()
            rig.companionA.closed() shouldNotContain rig.slotC

            // C's row is still open and can never advance again, so every one of
            // the three post-departure writes leaves the MIN exactly where it was.
            rig.addThreeOnA()
            rig.observations.drop(1).forEach { it shouldBe 9L }
            rig.stableOnA() shouldBe 9L
            rig.stableOnB() shouldBe 9L
        }
    }

    @Test
    fun `KE3-26 BS-10 a partitioned evict suspends without closing and the WAIT read stays frozen - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val rig = Rig(seed)
            rig.stableOnA() shouldBe 9L
            rig.partitionC()

            // `evict`'s no-reachable-peer branch: suspend, do not despawn, return false.
            rig.c.replication.evict(rig.rc, rig.c.host) shouldBe false
            rig.controller.runToIdle()

            val companionC = rig.c.replication.watermarkOf(rig.logicalId)!!
            companionC.suspended() shouldContain rig.slotC
            companionC.closed() shouldNotContain rig.slotC

            rig.addThreeOnA()

            // [KE3-24]: A's topology view has already dropped C (the partition
            // unpublished it) while the grow-only announced member set still names
            // C's slot — so C stays in the open set and caps the MIN at 9.
            rig.a.registry.instances.instancesOf(rig.logicalId) shouldNotContain rig.rc.ref
            rig.companionA.members() shouldContain rig.slotC
            rig.stableOnA() shouldBe 9L

            // The limitation this example was corrected to assert: C's suspend
            // epoch cannot cross the cut, so DEGRADE on A is frozen at 9 too — the
            // partition is exactly why DEGRADE has nothing to drop.
            rig.companionA.suspended() shouldNotContain rig.slotC
            rig.stableOnA(degrade = true) shouldBe 9L

            // Pin the DEGRADE read where the marker IS known: inject C's suspend
            // epoch (odd) into A's companion, as a heal-time gossip would deliver it.
            rig.companionA.deltaInlet.call.propagate(WatermarkDelta(suspended = mapOf(rig.slotC to 1L)))
            rig.controller.runToIdle()
            rig.companionA.suspended() shouldContain rig.slotC

            // DEGRADE drops the odd-epoch slot and reads the survivors' MIN; WAIT
            // still holds on the suspended row, which is the whole point of PN-19.
            rig.stableOnA(degrade = true) shouldBe 12L
            rig.stableOnA() shouldBe 9L
            rig.observations.assertNonDecreasing()
        }
    }

    @Test
    fun `KE3-29 BS-11 a healed member rejoins and stability resumes, monotone through suspend and resume - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val rig = Rig(seed)
            rig.stableOnA() shouldBe 9L
            rig.partitionC()
            rig.c.replication.evict(rig.rc, rig.c.host) shouldBe false
            rig.controller.runToIdle()
            rig.addThreeOnA()
            rig.stableOnA() shouldBe 9L

            // `linkOut`'s `heal (G-45)` branch: the re-announced peers un-partition
            // C's local replica and `resume()` its row, and the fresh peering's
            // catch-up feeds C the three writes it missed.
            rig.healC()

            // Neither suspended nor closed on the OBSERVER: the even epoch reached A.
            rig.companionA.suspended() shouldNotContain rig.slotC
            rig.companionA.closed() shouldNotContain rig.slotC

            // C's row caught up by anti-entropy, so the MIN over all three open
            // slots advances — and never dipped below an earlier reading.
            rig.stableOnA() shouldBe 12L
            rig.stableOnA(degrade = true) shouldBe 12L
            rig.observations.assertNonDecreasing()
        }
    }
}
