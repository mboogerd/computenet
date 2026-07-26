package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-7 — interest-scoped settlement (plan §3 Rule of settlement, spec 22
 * §Interest-scoped settlement; resolves F2). A glitch-free board is fed by an
 * instance set that is **both sharded and replicated**: one logical [SetCell]
 * with two hash slots, two replica copies per slot (four instances total).
 * Slot-0 copies (interest `Slots({0}, 2)`) never deliver a slot-1 value and
 * vice-versa — disjoint-interest instances form no cross-slot gossip link, so a
 * cross-slot member's delivered-watermark row stays at bottom for the other
 * slot's sources forever.
 *
 * The board draws one arm from a slot-0 copy and one from a slot-1 copy, and
 * settles each wave on the **interest-scoped** [Replication.replicaFrontier]:
 * the quorum for a wave touching key `k` is the *covering subset* — the members
 * whose interest admits `k` — not every member.
 *
 * **Safety** (120 seeds): every value the board surfaces has been delivered by
 * every current covering member (both copies of its slot) — no uncovered value.
 * **Liveness**: every written value eventually surfaces.
 *
 * Controls, all diverging:
 *  - (a) today's `members.all` (unfiltered quorum over *every* member): a slot-0
 *    wave requires the slot-1 copies, whose rows never advance for a slot-0
 *    source — **the wave never releases** (F2, executable).
 *  - (b) trivial frontier (always complete): the board tears — it surfaces a
 *    value before the sibling copy of its slot has delivered it.
 *  - (c) creation fence off ([Replication.replicate] `creationFence = false`):
 *    a joining copy's watermark companion is established only *after* its data
 *    cell publishes, so on some seed the board releases a wave before the joining
 *    covering member's row exists — a premature (mixed-frontier) release.
 */
class ShardedReplicaFrontierTest {

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

    /** Route an already-handshaken outlet→inlet delivery through the board host's queue. */
    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** One released board value + whether every current covering member had delivered it. */
    private data class Obs(val value: String, val allCoveringDelivered: Boolean)

    private enum class Variant { REAL, MEMBERS_ALL, TRIVIAL }

    private fun run(seed: Long, variant: Variant): List<Obs> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        // four peers, full loopback mesh: two slot-0 copies (p0, p1), two slot-1 (p2, p3).
        val peers = List(4) { Peer(controller) }
        for (i in peers.indices) for (j in i + 1 until peers.size) Peering.loopback(peers[i].side, peers[j].side)
        val logicalId = UUID.randomUUID()

        val refs = List(4) { CellRef(logicalId, it.toLong()) }
        val slotOfInstance = { instanceId: Long -> if (instanceId < 2) 0 else 1 }
        // interests are known in EVERY registry before the mesh forms so the gossip
        // linker gates data links by slot (disjoint slots form no link) — companions
        // stay Total-interest and mesh fully.
        peers.forEach { peer -> refs.forEach { ref -> peer.registry.setInterest(ref, interestForSlot(slotOfInstance(ref.instanceId))) } }

        val cells = refs.mapIndexed { i, ref -> SetCell<String>(ref).also { peers[i].replication.replicate(it, peers[i].host) } }
        controller.runToIdle()
        val byRef = refs.zip(cells).toMap()

        // board on peer 0: one arm from a slot-0 copy (r0, local) and one from a
        // slot-1 copy (r2, remote-routed), so it must see both slots' values.
        val p0 = peers[0]
        val gf = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call
        val gfInletFrom = @Suppress("UNCHECKED_CAST") (gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        for (armCell in listOf(cells[0], cells[2])) {
            val out = @Suppress("UNCHECKED_CAST") (armCell.outlet as FanOutlet<Propagate<SetDelta<String>>>)
            (out.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
            reroute(out, gf.inlet.ref, routedGf)
        }

        val frontier: ReplicaFrontier = when (variant) {
            Variant.REAL -> p0.replication.replicaFrontier(logicalId)
            Variant.TRIVIAL -> ReplicaFrontier { _, _, _ -> true }
            // pre-PN-7: quantify over EVERY member, ignoring the key — the F2 conflict.
            Variant.MEMBERS_ALL -> ReplicaFrontier { source, counter, _ ->
                val companion = p0.replication.watermarkOf(logicalId) ?: return@ReplicaFrontier false
                val rows = companion.rows()
                val closed = companion.closed()
                val members = p0.registry.instancesOf(logicalId)
                members.isNotEmpty() && members.all { ref ->
                    val slot = WatermarkCell.slotId(p0.replication.watermarkRef(ref))
                    slot in closed || (rows[slot]?.get(source) ?: Long.MIN_VALUE) >= counter
                }
            }
        }
        // The interest-scoped read needs the key extractor; the controls exercise the
        // unfiltered (originTags-only) path — exactly as a pre-PN-7 graph would.
        if (variant == Variant.REAL) gf.useReplicaFrontier(frontier, originTags, originKeys)
        else gf.useReplicaFrontier(frontier, originTags)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { value ->
                val covering = p0.registry.instancesOf(logicalId)
                    .filter { interestForSlot(slotOfInstance(it.instanceId)).admits(value) }
                val allHave = covering.isNotEmpty() && covering.all { byRef[it]?.membership()?.contains(value) ?: false }
                observations += Obs(value, allHave)
            }
        }, PortRef.generate()))

        // write each value at a copy of ITS OWN slot (a slot-0 value at r0, slot-1 at r2);
        // the sibling copy of that slot picks it up by gossip.
        val opForSlot = mapOf(
            0 to (HostedCellProxy.create(refs[0], p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
            1 to (HostedCellProxy.create(refs[2], peers[2].registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
        )
        val written = mutableListOf<String>()
        for (op in 1..30) {
            val value = "v$op"
            written += value
            opForSlot.getValue(slotOf(value)).add(value)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        gf.recheck()
        controller.runToIdle()

        return observations
    }

    @Test
    fun `board over a sharded-and-replicated instance set never surfaces an uncovered value - 120 seeds`() {
        for (seed in 0L until 120L) {
            val obs = run(seed, Variant.REAL)
            // safety: never a value a covering member has not delivered.
            obs.forEach { it.allCoveringDelivered.shouldBeTrue() }
            // liveness: every written value surfaced.
            obs.map { it.value }.toSet() shouldBe (1..30).map { "v$it" }.toSet()
        }
    }

    @Test
    fun `control a - the pre-PN-7 members-all quorum never releases a sharded wave`() {
        // Under the unfiltered quorum a slot-0 wave waits on the slot-1 copies (and
        // vice-versa), whose rows never advance for the other slot's sources: F2.
        var everReleased = 0
        for (seed in 0L until 120L) {
            if (run(seed, Variant.MEMBERS_ALL).isNotEmpty()) everReleased++
        }
        everReleased shouldBe 0
    }

    @Test
    fun `control b - a trivial frontier tears the board on some seed`() {
        var torn = 0
        for (seed in 0L until 120L) {
            if (run(seed, Variant.TRIVIAL).any { !it.allCoveringDelivered }) torn++
        }
        (torn > 0).shouldBeTrue()
    }

    /**
     * Control (c): a new slot-0 copy joins mid-run. Coverage is measured against
     * the board's own membership view ([LocationRegistry.instancesOf]) — the same
     * view the safety test uses — so a release that skips a *known* covering
     * member (one the board lists but whose watermark row has not yet advanced) is
     * caught as a premature, mixed-frontier release. With the creation fence ON
     * such a member holds the wave; with it OFF the frontier skips it.
     */
    private fun runWithJoin(seed: Long, creationFence: Boolean): List<Obs> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(5) { Peer(controller) } // p4 hosts the joining slot-0 copy
        for (i in peers.indices) for (j in i + 1 until peers.size) Peering.loopback(peers[i].side, peers[j].side)
        val logicalId = UUID.randomUUID()

        // instances 0,1,4 -> slot 0; instances 2,3 -> slot 1.
        val refs = List(5) { CellRef(logicalId, it.toLong()) }
        val slotOfInstance = { instanceId: Long -> if (instanceId == 2L || instanceId == 3L) 1 else 0 }
        peers.forEach { peer -> refs.forEach { ref -> peer.registry.setInterest(ref, interestForSlot(slotOfInstance(ref.instanceId))) } }

        // the original four (p4's copy joins later).
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
        gf.useReplicaFrontier(p0.replication.replicaFrontier(logicalId, creationFence = creationFence), originTags, originKeys)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { value ->
                // the board's own membership view (same as the safety test).
                val covering = p0.registry.instancesOf(logicalId)
                    .filter { interestForSlot(slotOfInstance(it.instanceId)).admits(value) }
                val allHave = covering.isNotEmpty() && covering.all { cells[it]?.membership()?.contains(value) ?: false }
                observations += Obs(value, allHave)
            }
        }, PortRef.generate()))

        val opForSlot = mutableMapOf(
            0 to (HostedCellProxy.create(refs[0], p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
            1 to (HostedCellProxy.create(refs[2], peers[2].registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
        )

        for (op in 1..30) {
            if (op == 10) {
                // slot-0 copy joins on p4 mid-run (a lagging covering member).
                cells[refs[4]] = SetCell<String>(refs[4]).also { peers[4].replication.replicate(it, peers[4].host) }
            }
            val value = "v$op"
            opForSlot.getValue(slotOf(value)).add(value)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        gf.recheck()
        controller.runToIdle()
        return observations
    }

    @Test
    fun `control c - the creation fence off releases early for a joining covering member on some seed`() {
        var prematureOn = 0
        var prematureOff = 0
        for (seed in 0L until 120L) {
            if (runWithJoin(seed, creationFence = true).any { !it.allCoveringDelivered }) prematureOn++
            if (runWithJoin(seed, creationFence = false).any { !it.allCoveringDelivered }) prematureOff++
        }
        // The fence (default, on) never releases a wave for a known covering member
        // that has not delivered it; turning it off does, on some seed.
        prematureOn shouldBe 0
        (prematureOff > 0).shouldBeTrue()
    }
}
