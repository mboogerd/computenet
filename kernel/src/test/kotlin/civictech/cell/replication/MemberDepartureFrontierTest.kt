package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.data.Propagate
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
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-0c (plan §2 F3, spec 40/42 §Delivered watermarks): a departing replica must
 * close its delivered-watermark row so a downstream replica-fed frontier stops
 * waiting on a row that can never advance again. Membership
 * ([LocationRegistry.replicasOf]) is only eventually consistent — the R13 caveat
 * the [Replication.replicaFrontier] doc names — so a glitch-free consumer whose
 * membership view still lists a cleanly departed member would otherwise wedge
 * forever. The reliable release signal is [WatermarkCell.close]'s `closed`
 * marker, which rides the idempotent watermark mesh (converging even where the
 * topology unpublish is lost), not the best-effort topology drop.
 *
 * Three replicas of one logical [SetCell] mesh over gossip; a glitch-free
 * consumer on peer 0 draws its arm from peer 0's replica and settles waves on a
 * replica frontier over the *original* member set (the honest maximal-lag view:
 * the consumer has not learned of the departure). One member is
 * [Replication.evict]ed mid-run; peer 0 keeps writing.
 *
 * **Invariant** (100 seeds): every post-departure write still surfaces on the
 * consumer — the frontier releases because the departed member's row is `closed`.
 * **Control**: with the close call removed the departed member's frozen row is
 * still required, so no post-departure wave ever settles — the consumer stops
 * producing, on every seed.
 */
class MemberDepartureFrontierTest {

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

    /** The origin add/del tags a released [SetDelta] invocation depends on. */
    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    /** Route an already-handshaken outlet→inlet delivery through the target host's queue. */
    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** The elements the consumer released, in order. */
    private fun run(seed: Long, closeOnEvict: Boolean): List<String> {
        val controller = SimulationController(seed)
        val p0 = Peer(controller)
        val p1 = Peer(controller)
        val p2 = Peer(controller)
        Peering.loopback(p0.side, p1.side)
        Peering.loopback(p1.side, p2.side)
        Peering.loopback(p0.side, p2.side)
        val logicalId = UUID.randomUUID()

        val r0 = SetCell<String>(CellRef(logicalId, 0)).also { p0.replication.replicate(it, p0.host) }
        val r1 = SetCell<String>(CellRef(logicalId, 1)).also { p1.replication.replicate(it, p1.host) }
        val r2 = SetCell<String>(CellRef(logicalId, 2)).also { p2.replication.replicate(it, p2.host) }
        controller.runToIdle()

        // Glitch-free consumer on peer 0, arm drawn from peer 0's own replica.
        val gf = GlitchFreeCell(propagateSetDelta)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call

        @Suppress("UNCHECKED_CAST")
        val r0Out = r0.outlet as FanOutlet<Propagate<SetDelta<String>>>
        @Suppress("UNCHECKED_CAST")
        val gfInletFrom = gf.inlet as LinkFrom<Propagate<SetDelta<String>>>
        (r0Out.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        reroute(r0Out, gf.inlet.ref, routedGf)

        // Replica frontier over the ORIGINAL member set — the maximal-lag view in
        // which the consumer has not yet learned of the departure. Reads the real
        // gossiped companion (rows + closed), exactly as Replication.replicaFrontier
        // does, so close()'s converged `closed` marker is what releases held waves.
        val members = listOf(r0.ref, r1.ref, r2.ref)
        val frontier = ReplicaFrontier { source, counter ->
            val companion = p0.replication.watermarkOf(logicalId) ?: return@ReplicaFrontier false
            val rows = companion.rows()
            val closed = companion.closed()
            members.all { ref ->
                val slot = WatermarkCell.slotId(p0.replication.watermarkRef(ref))
                slot in closed || (rows[slot]?.get(source) ?: Long.MIN_VALUE) >= counter
            }
        }
        gf.useReplicaFrontier(frontier, originTags)
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val released = mutableListOf<String>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { released += it }
        }, PortRef.generate()))

        val op0 = (HostedCellProxy.create(r0.ref, p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op1 = (HostedCellProxy.create(r1.ref, p1.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
        val op2 = (HostedCellProxy.create(r2.ref, p2.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        // Pre-departure: all three members participate, so waves settle and the
        // consumer is demonstrably producing.
        listOf(op0, op1, op2).forEachIndexed { i, op -> op.add("pre$i") }
        controller.runToIdle()

        // A reachable peer remains (r0, r1), so eviction despawns for real (true).
        p2.replication.evict(r2, p2.host, closeDepartedRow = closeOnEvict).shouldBeTrue()
        controller.runToIdle()

        // Post-departure writes on the surviving origin. Each carries origin
        // (peer0, counter) tags the frontier requires every member to have
        // delivered; r2 never will — only its `closed` row releases them.
        val postElements = (0 until 5).map { "post$it" }
        postElements.forEach { op0.add(it); controller.runToIdle() }
        controller.runToIdle()

        return released
    }

    @Test
    fun `post-departure waves keep settling once the evicted member's row is closed - 100 seeds`() {
        for (seed in 0L until 100L) {
            val released = run(seed, closeOnEvict = true)
            released shouldContainAll (0 until 3).map { "pre$it" }
            released shouldContainAll (0 until 5).map { "post$it" }
        }
    }

    @Test
    fun `control - without the close call every post-departure wave wedges the consumer, on every seed`() {
        for (seed in 0L until 100L) {
            val released = run(seed, closeOnEvict = false)
            // The consumer was producing before the departure …
            released shouldContainAll (0 until 3).map { "pre$it" }
            // … and stops the moment the frozen row of the departed member is
            // required forever: no post-departure element ever surfaces.
            val leaked = (0 until 5).map { "post$it" }.filter { it in released }
            leaked.isEmpty().shouldBeTrue()
        }
    }
}
