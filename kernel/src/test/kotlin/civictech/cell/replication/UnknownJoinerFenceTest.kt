package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * FU-2 — converged-membership barrier: the unknown-joiner half of the
 * premature-release race (PN-7 residual; PN-19 closed only the known-suspended
 * half). [Replication.replicaFrontier] gates a glitch-free release on the
 * *covering quorum* = the members whose interest admits the key, read off
 * [LocationRegistry.instancesOf]. That membership view is **eventually
 * consistent**: a covering member the settling node has not learned of *at all*
 * is simply absent from the quorum, so the wave can release before that member's
 * data for the key has arrived — a torn / non-glitch-free read, in the window
 * between a covering instance joining and its existence gossiping to this node.
 * The R13 creation fence only holds on members the node already *knows* (rowless
 * covering members); it cannot wait on a member it does not know exists.
 *
 * The barrier closes that half by an asymmetry identical to the creation fence's,
 * extended to *unknown* members: a covering member announces its existence over
 * the delivered-watermark companion — a **transitively-gossiped CRDT** that
 * converges more completely than the point-to-point topology announcements that
 * feed `instancesOf` (it merges over the whole mesh and self-heals). A settling
 * node whose companion knows of a covering member slot it has *not* accounted for
 * in its own membership view holds every keyed wave (a conservative hold), never
 * releases early, and releases the moment its view converges.
 *
 * **Topology.** Two hash slots, one logical [SetCell]. Slot-0 copies r0 (peer 0,
 * the board's local arm), r1 (peer 1); slot-1 copies r2 (peer 2, the board's
 * remote arm), r3 (peer 3). A **third slot-0 copy r4 joins mid-run on peer 4**.
 * During the convergence window peer 4 is peered with peers 1/2/3 but **not peer
 * 0**, so peer 0's `instancesOf` never lists r4 (the unknown joiner) — yet r4's
 * companion membership marker reaches peer 0's companion *transitively* through
 * peer 1. Slot-0 values are written at r0 (a *known* origin); r4 is a lagging
 * covering member of that slot. Coverage is measured against **ground truth** —
 * every copy that actually covers the value, r4 included — not peer 0's lagging
 * view, so a release that skips the unknown r4 is caught.
 *
 * **Safety** (100 seeds): with the barrier on, the board never surfaces a slot-0
 * value r4 has not delivered, across the whole convergence window.
 * **Liveness**: once peer 0 is peered with peer 4 (membership converges) every
 * written value still surfaces — the barrier releases, it does not wedge.
 *
 * Controls, both diverging:
 *  - (a) barrier off ([Replication.replicaFrontier] `membershipBarrier = false`,
 *    today's behavior): on some seed the board releases a slot-0 value before the
 *    unknown covering r4 has delivered it — the executable bug.
 *  - (b) fully-converged membership (peer 0 peered with peer 4 *before* any
 *    write): barrier-on output is **byte-identical** to barrier-off, and every
 *    value still surfaces — no extra holding once everyone is known, and no wedge.
 */
class UnknownJoinerFenceTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private val propagateSetDelta =
        @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private val slots = 2
    private fun interestForSlot(slot: Int): Interest = Interest.Slots(setOf(slot), slots)
    private fun slotOf(value: String): Int = Interest.Slots.slotOf(value, slots)

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** The origin add/del tags a released [SetDelta] invocation carries, flat (the unfiltered read). */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    /** Each key a released [SetDelta] touches → the origin waves attached to it (the interest-scoped read). */
    private val originKeys: (Invocation) -> Map<Any?, Collection<Timestamp>> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { d ->
            (d.adds.keys + d.dels.keys).associate { k ->
                (k as Any?) to (d.adds[k].orEmpty() + d.dels[k].orEmpty())
            }
        } ?: emptyMap()
    }

    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /**
     * One released board value + whether every *ground-truth* covering copy had
     * delivered it. [postJoin] is true iff the value was written at or after the
     * joiner appeared ([JOIN_OP]) — the barrier's responsibility. Pre-join values
     * are the joiner's own anti-entropy back-fill (a separate concern), so tear
     * detection is scoped to the post-join era.
     */
    private data class Obs(val value: String, val allCoveringDelivered: Boolean, val postJoin: Boolean)

    private fun opOf(value: String): Int = value.removePrefix("v").toInt()

    /**
     * @param converged  peer 0 peered with peer 4 (the joiner's host) *before* the
     *   writes — a fully-known membership. When false, peer 0 learns r4 only after
     *   the write loop (the announcement-lag window).
     * @param membershipBarrier  the barrier under test, threaded into the real
     *   [Replication.replicaFrontier].
     */
    private fun run(seed: Long, converged: Boolean, membershipBarrier: Boolean): List<Obs> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(5) { Peer(controller) } // peer 4 hosts the joining slot-0 copy r4
        val logicalId = UUID.randomUUID()

        // instances 0,1,4 -> slot 0; 2,3 -> slot 1.
        val refs = List(5) { CellRef(logicalId, it.toLong()) }
        val slotOfInstance = { instanceId: Long -> if (instanceId == 2L || instanceId == 3L) 1 else 0 }
        peers.forEach { peer -> refs.forEach { ref -> peer.registry.setInterest(ref, interestForSlot(slotOfInstance(ref.instanceId))) } }

        // Mesh peers 0..3 fully. Peer 4 is peered with 1/2/3 always; with peer 0
        // only when we want convergence up front (control b). Otherwise peer 0
        // learns r4 only via the deferred heal after the write loop.
        for (i in 0..3) for (j in i + 1..3) Peering.loopback(peers[i].side, peers[j].side)
        listOf(1, 2, 3).forEach { Peering.loopback(peers[4].side, peers[it].side) }
        if (converged) Peering.loopback(peers[0].side, peers[4].side)

        // The original four copies (r4 joins mid-run).
        val cells = HashMap<CellRef, SetCell<String>>()
        listOf(0, 1, 2, 3).forEach { i -> cells[refs[i]] = SetCell<String>(refs[i]).also { peers[i].replication.replicate(it, peers[i].host) } }
        controller.runToIdle()

        val p0 = peers[0]
        val gf = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call
        val gfInletFrom = @Suppress("UNCHECKED_CAST") (gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        for (armCell in listOf(cells.getValue(refs[0]), cells.getValue(refs[2]))) {
            val out = @Suppress("UNCHECKED_CAST") (armCell.outlet as FanOutlet<Propagate<SetDelta<String>>>)
            (out.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
            reroute(out, gf.inlet.ref, routedGf)
        }
        val frontier: ReplicaFrontier =
            p0.replication.replicaFrontier(logicalId, membershipBarrier = membershipBarrier)
        gf.useReplicaFrontier(frontier, originTags, originKeys)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { value ->
                // GROUND TRUTH: every copy that actually covers this value, r4 included,
                // regardless of peer 0's (lagging) membership view.
                val covering = refs.filter { interestForSlot(slotOfInstance(it.instanceId)).admits(value) && cells.containsKey(it) }
                val allHave = covering.isNotEmpty() && covering.all { cells[it]?.membership()?.contains(value) ?: false }
                observations += Obs(value, allHave, postJoin = opOf(value) >= JOIN_OP)
            }
        }, PortRef.generate()))

        val opForSlot = mutableMapOf(
            0 to (HostedCellProxy.create(refs[0], p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
            1 to (HostedCellProxy.create(refs[2], peers[2].registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
        )

        for (op in 1..30) {
            if (op == JOIN_OP) {
                // r4 joins on peer 4 mid-run: a covering member of slot 0 that peer 0
                // does not yet know exists (unless converged), yet whose companion
                // membership marker reaches peer 0 transitively through peer 1.
                cells[refs[4]] = SetCell<String>(refs[4]).also { peers[4].replication.replicate(it, peers[4].host) }
                // Pin the announcement lag deterministically: let r4's membership marker
                // fully propagate to peer 0's companion (the transitively-gossiped CRDT),
                // while peer 0's `instancesOf` still lacks r4 (peer 0 is not peered with
                // peer 4 until the heal). This is the vulnerable state — a covering member
                // known to exist yet absent from the local quorum view — and subsequent
                // writes at r0 race r4's lagging delivery of them.
                controller.runToIdle()
            }
            val value = "v$op"
            // Slot-0 values are written at r0 (a KNOWN origin) so the tear is a
            // premature release skipping the unknown covering r4 — not r4 as origin.
            opForSlot.getValue(slotOf(value)).add(value)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        // Convergence: peer 0 now learns r4 (if it did not already). The barrier must
        // release everything it held — liveness, not a permanent wedge.
        if (!converged) Peering.loopback(peers[0].side, peers[4].side)
        controller.runToIdle()
        gf.recheck()
        controller.runToIdle()

        return observations
    }

    @Test
    fun `board never surfaces a value an unknown covering joiner has not delivered - 100 seeds`() {
        for (seed in 0L until 100L) {
            val obs = run(seed, converged = false, membershipBarrier = true)
            // Safety: across the convergence window, never a post-join value a
            // ground-truth covering member — including the unknown joiner r4 — lacks.
            obs.filter { it.postJoin }.forEach { it.allCoveringDelivered.shouldBeTrue() }
            // Liveness: once membership converges, every written value surfaces — the
            // barrier releases what it held, it does not wedge.
            obs.map { it.value }.toSet() shouldBe (1..30).map { "v$it" }.toSet()
        }
    }

    @Test
    fun `control a - the barrier off releases early for an unknown covering joiner on some seed`() {
        var tornOn = 0
        var tornOff = 0
        for (seed in 0L until 100L) {
            if (run(seed, converged = false, membershipBarrier = true).any { it.postJoin && !it.allCoveringDelivered }) tornOn++
            if (run(seed, converged = false, membershipBarrier = false).any { it.postJoin && !it.allCoveringDelivered }) tornOff++
        }
        // The barrier (default, on) never surfaces an uncovered post-join value; off
        // does, on some seed — the executable unknown-joiner premature-release bug.
        tornOn shouldBe 0
        (tornOff > 0).shouldBeTrue()
    }

    @Test
    fun `control b - fully-converged membership adds no holding and stays safe with the barrier on or off`() {
        for (seed in 0L until 100L) {
            val on = run(seed, converged = true, membershipBarrier = true)
            val off = run(seed, converged = true, membershipBarrier = false)
            // No extra holding once everyone is known: the released value set is
            // identical (barrier vs no barrier) and complete — same outcome as today.
            on.map { it.value }.toSet() shouldBe (1..30).map { "v$it" }.toSet()
            off.map { it.value }.toSet() shouldBe (1..30).map { "v$it" }.toSet()
            // And both are safe: a fully-known membership never surfaces an uncovered
            // post-join value with or without the barrier (the barrier is a no-op here).
            on.filter { it.postJoin }.forEach { it.allCoveringDelivered.shouldBeTrue() }
            off.filter { it.postJoin }.forEach { it.allCoveringDelivered.shouldBeTrue() }
        }
    }

    companion object {
        /** The op at which the third slot-0 copy (r4) joins mid-run. */
        private const val JOIN_OP = 5
    }
}
