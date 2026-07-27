package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

/**
 * CP-B3 / milestone E3.4 (spec 20/22 §Completeness — cross-replica extension):
 * the wave-settlement predicate of a glitch-free consumer gains a
 * **replica-frontier read**. A glitch-free join drawing from *different
 * replicas* of one logical [SetCell] must not treat "my replica delivered it"
 * as "the wave is complete": a wave settles only once the **merged watermark
 * across the replica set** (not just the local delivery) has reached the
 * write's origin wave.
 *
 * Two peers each host a replica of one logical set; a glitch-free join on peer 0
 * draws one arm from each replica (arm A local, arm B linked from peer 1). Both
 * replicas gossip their data AND their per-origin delivered watermark over the
 * same mesh. The join reads peer 0's merged [civictech.cell.data.WatermarkCell]
 * before releasing any wave.
 *
 * **Invariant** (100 seeds, mid-run partition + heal): every released join
 * output carries an origin write that *every current replica-set member* has
 * already delivered — no mixed-frontier composite.
 *
 * **Control**: with the replica-frontier read OFF (settle on local delivery
 * only) the join releases an arm's wave the moment that arm's replica delivered
 * it — before the peer replica has — so on some seed an output names a write a
 * replica-set member has not yet delivered.
 */
class GlitchFreeReplicaFrontierTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private val propagateSetDelta =
        @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /** One released join output: the origin write's element, and whether every current member had delivered it. */
    private data class Obs(val element: String, val allMembersDelivered: Boolean)

    /** The origin add-tags a released [SetDelta] invocation depends on. */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    /** Route an already-handshaken outlet→inlet delivery through the target host's queue (mirrors the diamond harness). */
    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    private fun runJoin(seed: Long, replicaFrontierOn: Boolean): List<Obs> {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val p0 = Peer(controller)
        val p1 = Peer(controller)
        val loop = Peering.loopback(p0.side, p1.side)
        val logicalId = UUID.randomUUID()

        val a = SetCell<String>(CellRef(logicalId, 0)).also { p0.replication.replicate(it, p0.host) }
        val b = SetCell<String>(CellRef(logicalId, 1)).also { p1.replication.replicate(it, p1.host) }
        controller.runToIdle()

        val byInstance = mapOf(0L to a, 1L to b)

        // The glitch-free join lives on peer 0, drawing one arm from each replica.
        val gf = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call

        @Suppress("UNCHECKED_CAST")
        val aOut = a.outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST")
        val bOut = b.outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST")
        val gfInletFrom = gf.inlet as LinkFrom<Propagate<SetDelta<String>>>
        (aOut.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        (bOut.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        reroute(aOut, gf.inlet.ref, routedGf)
        reroute(bOut, gf.inlet.ref, routedGf)

        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            val element = delta.adds.keys.firstOrNull() ?: return@Propagate
            val members = p0.registry.replicasOf(logicalId).mapNotNull { byInstance[it.instanceId] }
            observations += Obs(element, members.all { element in it.membership() })
        }, PortRef.generate()))

        // The replica-frontier read (E3.4): ON installs the real merged-watermark read;
        // OFF installs an always-true frontier — "settle on local delivery only" — the control.
        val frontier: ReplicaFrontier =
            if (replicaFrontierOn) p0.replication.replicaFrontier(logicalId)
            else ReplicaFrontier { _, _, _ -> true }
        gf.useReplicaFrontier(frontier, originTags)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val ops = listOf(
            (HostedCellProxy.create(a.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
            (HostedCellProxy.create(b.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call,
        )

        val totalOps = 30
        for (op in 1..totalOps) {
            if (op == 12) { loop.partition() }
            if (op == 20) { loop.heal() }
            ops[rnd.nextInt(2)].add("w$op")
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        // liveness / convergence: no permanent stall — every write is delivered everywhere,
        // and (with the read ON) every write eventually surfaces on the join.
        val universe = (1..totalOps).map { "w$it" }.toSet()
        a.membership() shouldBe universe
        b.membership() shouldBe universe

        return observations
    }

    @Test
    fun `no join output at a wave a replica-set member has not delivered - 100 seeds with partition and heal`() {
        forEachSeed(0L until 100L) { seed ->
            val observations = runJoin(seed, replicaFrontierOn = true)
            // every write surfaced on the join (liveness) …
            observations.map { it.element }.toSet() shouldBe (1..30).map { "w$it" }.toSet()
            // … and never before every current replica-set member had delivered it (safety).
            observations.forEach { it.allMembersDelivered.shouldBeTrue() }
        }
    }

    @Test
    fun `control - settling on local delivery only emits a mixed frontier on some seed`() {
        var mixed = 0
        for (seed in 0L until 100L) {
            val observations = runJoin(seed, replicaFrontierOn = false)
            if (observations.any { !it.allMembersDelivered }) mixed++
        }
        // if this fails the harness is too weak to detect a mixed-frontier release — tune interleaving
        (mixed > 0).shouldBeTrue()
    }
}
