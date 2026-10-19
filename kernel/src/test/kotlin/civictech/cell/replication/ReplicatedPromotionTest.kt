package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.evolve.Promotion
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

/**
 * PN-14 (spec 53 §Replicated promotion): a **rolling** promotion across a
 * replicated instance set. `promote` is single-instance — gossip links are not
 * in `downstream`, a candidate's fresh [CellRef] re-mints the tag lane and the
 * delivered-watermark slot, and the retired incumbent's row holds frontiers
 * forever. The rolling form swaps one instance at a time behind a **reused
 * CellRef**: because every mesh identity (`SetCell.tagSource`, the watermark
 * row, port refs) derives from the ref, reusing it makes the swap
 * indistinguishable from crash-recovery — peers' inbound gossip keeps resolving
 * to the ref, this peer's delivered-watermark row is retained, and the
 * candidate re-syncs by the same anti-entropy catch-up a recovered replica uses.
 * The surviving replicas play the retained incumbent.
 *
 * Three-peer mesh; peers 1–2 keep writing while peer 0 is promoted, then the
 * roll continues to peer 1.
 *
 * **Invariant** (100 seeds): all replicas converge to the batch union across
 * both swaps, and [Replication.stableFrontier] never certifies a locally
 * minted tag before every open member has delivered its element.
 *
 * **Controls** (must diverge — the two halves of the one root cause the reused
 * CellRef prevents):
 *  - (a) `NO_TAG_CARRY` — the T2 fresh-epoch fallback: the candidate does not
 *    continue the incumbent's tag lane (tag counter restarts under the same
 *    ref-derived `tagSource`), so it re-mints counters at or below the
 *    high-water every peer's delivered row already holds. The stable frontier
 *    then certifies a tag before another member has delivered its element,
 *    violating the stability read that tombstone GC trusts.
 *  - (b) `FRESH_REF` — a candidate with a distinct `instanceId`: it re-mints
 *    the identity lanes and the set fails to converge.
 */
class ReplicatedPromotionTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    enum class Mode { REUSE_REF, NO_TAG_CARRY, FRESH_REF }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private data class Run(
        val memberships: List<Set<String>>,
        val universe: Set<String>,
        val overCertified: Int,
    )

    private fun runRoll(seed: Long, mode: Mode): Run {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(3) { Peer(controller) }
        Peering.loopback(peers[0].side, peers[1].side)
        Peering.loopback(peers[0].side, peers[2].side)
        Peering.loopback(peers[1].side, peers[2].side)

        val logicalId = UUID.randomUUID()
        // current[i] is the live replica object on peer i (swapped on promotion).
        val current = peers.mapIndexed { i, peer ->
            SetCell<String>(CellRef(logicalId, i.toLong())).also { peer.replication.replicate(it, peer.host) }
        }.toMutableList()
        controller.runToIdle()

        val ops = peers.mapIndexed { i, peer ->
            (HostedCellProxy.create(current[i].ref, peer.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        }

        // Promote one instance at a time behind a reused CellRef, rolling peer 0 then peer 1.
        fun promote(i: Int) {
            val incumbent = current[i]
            val candidateRef = when (mode) {
                Mode.FRESH_REF -> CellRef(logicalId, 100L + i) // distinct instanceId — the control
                else -> incumbent.ref                          // reuse the ref — the mechanism
            }
            val candidate = SetCell<String>(candidateRef)
            when (mode) {
                Mode.REUSE_REF ->
                    Promotion.promoteReplica(peers[i].host, peers[i].replication, incumbent, candidate)
                Mode.NO_TAG_CARRY ->
                    peers[i].replication.rebind(incumbent, candidate, peers[i].host, carryTagState = false)
                Mode.FRESH_REF -> {
                    // fresh-ref swap: retire the incumbent and join the candidate as a new instance.
                    candidate.restore(incumbent.snapshot())
                    peers[i].replication.replicate(candidate, peers[i].host)
                    peers[i].host.managementInlet.call.despawn(incumbent.ref)
                }
            }
            current[i] = candidate
        }

        val universe = mutableSetOf<String>()
        var overCertified = 0
        val alphabet = listOf("apple", "banana", "cherry", "date", "elder", "fig", "grape")
        val totalOps = 40
        for (op in 1..totalOps) {
            if (op == 12) promote(0)
            if (op == 26) promote(1)
            val who = rnd.nextInt(3)
            val element = "$op-" + alphabet[rnd.nextInt(alphabet.size)]
            // add-only: the batch union is exactly the added set, and "delivered"
            // == "present in membership" is a well-posed oracle.
            ops[who].add(element); universe += element
            var guardSteps = 0
            while (element !in current[who].membership() && guardSteps < 200) {
                controller.step()
                guardSteps++
            }
            val mintingReplicaHoldsElement = element in current[who].membership()
            val minted = current[who].tagLaneHighWater()
            val source = UUID.nameUUIDFromBytes(
                "set-tags:${current[who].ref.id}:${current[who].ref.instanceId}".toByteArray(),
            )
            val stable = peers[who].replication.stableFrontier(logicalId).perSource[source] ?: Long.MIN_VALUE
            if (
                mintingReplicaHoldsElement &&
                minted <= stable &&
                current.any { element !in it.membership() }
            ) {
                overCertified++
            }
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        return Run(current.map { it.membership() }, universe, overCertified)
    }

    @Test
    fun `rolling promotion across the replica set converges - 100 seeds`() {
        forEachSeed(0L until 100L) { seed ->
            val run = runRoll(seed, Mode.REUSE_REF)
            // convergence: every replica holds the full batch union of adds.
            run.memberships.forEach { it shouldBe run.universe }
            run.overCertified shouldBe 0
        }
    }

    /** A run diverges if membership is incomplete or stability certifies an undelivered tag. */
    private fun diverges(run: Run): Boolean =
        run.memberships.any { it != run.universe } || run.overCertified > 0

    // Both controls make an existential claim (SOME seed diverges), so the scans
    // stop at the first diverging witness; if no seed in the range diverges any
    // more, the control fails — the retune-the-harness signal.

    @Test
    fun `control a - no tag carry over-certifies the restarted tag lane`() {
        val seed = (0L until 100L).firstOrNull { diverges(runRoll(it, Mode.NO_TAG_CARRY)) }
        (seed != null).shouldBeTrue()
        println("ReplicatedPromotion control a divergent seed=$seed")
    }

    @Test
    fun `control b - fresh CellRef diverges on membership`() {
        val seed = (0L until 100L).firstOrNull { diverges(runRoll(it, Mode.FRESH_REF)) }
        (seed != null).shouldBeTrue()
        println("ReplicatedPromotion control b divergent seed=$seed")
    }

    @Test
    fun `PRECHECK refuses a candidate with a different ref`() {
        val controller = SimulationController(1)
        val peer = Peer(controller)
        val logicalId = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, 0)).also { peer.replication.replicate(it, peer.host) }
        controller.runToIdle()
        val candidate = SetCell<String>(CellRef(logicalId, 7)) // different instanceId
        val ex = runCatching {
            Promotion.promoteReplica(peer.host, peer.replication, incumbent, candidate)
        }.exceptionOrNull()
        (ex is Promotion.PromotionAborted).shouldBeTrue()
    }

    @Test
    fun `PRECHECK refuses a NonIdempotentCatchUp candidate (no sound T2 for a replicated cell)`() {
        val controller = SimulationController(1)
        val peer = Peer(controller)
        val logicalId = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, 0)).also { peer.replication.replicate(it, peer.host) }
        controller.runToIdle()
        val candidate = NonIdempotentReplica(CellRef(logicalId, 0))
        val ex = runCatching {
            Promotion.promoteReplica(peer.host, peer.replication, incumbent, candidate)
        }.exceptionOrNull()
        (ex is Promotion.PromotionAborted).shouldBeTrue()
    }

    /** A minimal [civictech.cell.data.Replicable] declaring [Promotion.NonIdempotentCatchUp] — PRECHECK must refuse it. */
    private class NonIdempotentReplica(override val ref: CellRef) :
        civictech.cell.Cell,
        civictech.cell.data.Replicable<SetDelta<String>>,
        Promotion.NonIdempotentCatchUp {
        override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        override val deltaInlet = registerPort("deltaInlet", civictech.cell.port.FanInlet.create<Propagate<SetDelta<String>>>())
    }
}
