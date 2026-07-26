package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.evolve.Promotion
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

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
 * Three-peer mesh; a glitch-free consumer on peer 0 gates on the merged
 * replica frontier; peers 1–2 keep writing while peer 0 is promoted, then the
 * roll continues to peer 1.
 *
 * **Invariant** (100 seeds): all replicas converge to the batch union, and the
 * [GlitchFreeCell.useReplicaFrontier] consumer never surfaces an element some
 * current replica-set member has not delivered — across both swaps.
 *
 * **Controls** (must diverge — the two halves of the one root cause the reused
 * CellRef prevents):
 *  - (a) `NO_TAG_CARRY` — the T2 fresh-epoch fallback: the candidate does not
 *    continue the incumbent's tag lane (tag counter restarts under the same
 *    ref-derived `tagSource`), so a fresh mint **collides** with an
 *    already-emitted tag — the double-count PRECHECK refuses for a replicated
 *    cell.
 *  - (b) `FRESH_REF` — a candidate with a distinct `instanceId`: it re-mints
 *    the delivered-watermark slot, orphaning the incumbent's row, so the
 *    replica-frontier read tears (a member surfaces an undelivered element) or
 *    the set fails to converge.
 */
class ReplicatedPromotionTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    enum class Mode { REUSE_REF, NO_TAG_CARRY, FRESH_REF }

    private val propagateSetDelta =
        @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** The origin add/del tags a released [SetDelta] invocation depends on. */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    private fun reroute(
        outlet: FanOutlet<Propagate<SetDelta<String>>>,
        inletRef: PortRef,
        routed: Propagate<SetDelta<String>>,
    ) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** One released join output: the element and whether every current member had delivered it. */
    private data class Obs(val element: String, val allMembersDelivered: Boolean)

    private data class Run(
        val memberships: List<Set<String>>,
        val surfaced: List<Obs>,
        val universe: Set<String>,
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

        // glitch-free consumer on peer 0, drawing one arm from peer 0's replica.
        val gf = GlitchFreeCell(propagateSetDelta)
        peers[0].host.managementInlet.call.spawn(gf)
        val routedGf = peers[0].host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call

        fun linkArm(replica: SetCell<String>) {
            @Suppress("UNCHECKED_CAST")
            val out = replica.outlet as FanOutlet<Propagate<SetDelta<String>>>
            @Suppress("UNCHECKED_CAST")
            val gfInletFrom = gf.inlet as LinkFrom<Propagate<SetDelta<String>>>
            (out.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
            reroute(out, gf.inlet.ref, routedGf)
        }
        // Draw the (single) consumer arm from peer 2 — the peer that is NEVER
        // promoted — so the arm link is stable across both swaps and the frontier
        // still gates over the full three-member set. (Re-linking an arm to a
        // freshly-promoted candidate would surface its catch-up baseline, a
        // harness artifact unrelated to the property under test.)
        linkArm(current[2])

        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            val element = delta.adds.keys.firstOrNull() ?: return@Propagate
            val members = peers[0].registry.replicasOf(logicalId)
            // "delivered" == the element is live in every current member's replica object.
            val liveIn = current.associateBy { it.ref }
            val delivered = members.mapNotNull { liveIn[it] }.all { element in it.membership() }
            observations += Obs(element, delivered)
        }, PortRef.generate()))

        val frontier: ReplicaFrontier = peers[0].replication.replicaFrontier(logicalId)
        gf.useReplicaFrontier(frontier, originTags)
        peers[0].replication.onWatermarkAdvance(logicalId) { gf.recheck() }

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
        val alphabet = listOf("apple", "banana", "cherry", "date", "elder", "fig", "grape")
        val totalOps = 40
        for (op in 1..totalOps) {
            if (op == 12) promote(0)
            if (op == 26) promote(1)
            val who = rnd.nextInt(3)
            val element = "$op-" + alphabet[rnd.nextInt(alphabet.size)]
            // add-only: the batch union is exactly the added set, and "delivered"
            // == "present in membership" is a well-posed oracle (a removed element
            // would read as undelivered though it WAS delivered — the reason the
            // sibling GlitchFreeReplicaFrontierTest is add-only too).
            ops[who].add(element); universe += element
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        return Run(current.map { it.membership() }, observations, universe)
    }

    @Test
    fun `rolling promotion across the replica set converges and surfaces no undelivered element - 100 seeds`() {
        for (seed in 0L until 100L) {
            val run = runRoll(seed, Mode.REUSE_REF)
            // convergence: every replica holds the full batch union of adds.
            run.memberships.forEach { it shouldBe run.universe }
            // safety: nothing surfaced before every current member delivered it.
            run.surfaced.forEach { it.allMembersDelivered.shouldBeTrue() }
        }
    }

    /** A run diverges from the fix iff a replica lost part of the batch union or the frontier tore. */
    private fun diverges(run: Run): Boolean =
        run.memberships.any { it != run.universe } || run.surfaced.any { !it.allMembersDelivered }

    @Test
    fun `control a - T2 fresh-epoch (no tag carry) diverges - the restarted tag lane tears the frontier`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.NO_TAG_CARRY))) diverged++
        (diverged > 0).shouldBeTrue()
    }

    @Test
    fun `control b - fresh CellRef diverges - orphaned watermark tears the replica frontier`() {
        var diverged = 0
        for (seed in 0L until 100L) if (diverges(runRoll(seed, Mode.FRESH_REF))) diverged++
        (diverged > 0).shouldBeTrue()
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
