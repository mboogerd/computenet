package civictech.cell.attention

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.ReplicaFrontier
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.host.AttentionPolicy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.Invocation
import civictech.cell.replication.Interest
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-19 (plan §3b, spec 34 decisions 3/5) — attention scatter by interest, the
 * per-instance park, and the `Stall` family closing PN-7's documented DEGRADE
 * covering-quorum gap.
 *
 * **Scenario 1 — interest scatter parks only the non-covering shards.** A
 * consumer declares interest in one key range and scatters its attention by the
 * data-plane overlap rule ([Interest.overlaps]): a covering shard is attended
 * (HIGH), a non-covering shard is left explicitly unattended (NONE) and parks
 * like any cell. The control (broadcast — no scatter) attends both, so the
 * non-covering shard never parks.
 *
 * **Scenario 2 — a DEGRADE board keeps producing across a covering instance's
 * park/resume.** A glitch-free board settles each wave on the interest-scoped
 * covering quorum ([Replication.replicaFrontier]). A covering replica parks and
 * publishes the recoverable `Stall` on the watermark mesh
 * ([civictech.cell.data.WatermarkCell.suspend]); DEGRADE removes it from the
 * quorum so the board keeps producing (no torn value — every surfaced value is
 * delivered by every current covering member), and restores it on `Resume`
 * (its frozen row catches up as a PN-2 baseline). Controls, both diverging:
 * (a) the WAIT variant holds the wave through the whole park; (b) the same
 * DEGRADE board with the `Stall` suppressed wedges exactly like WAIT — the
 * notice is load-bearing.
 */
class PartitionedAttentionTest {

    // ---------- Scenario 1: attention scatter parks non-covering shards ----------

    /** A hosted shard: its inlet traffic parks when its band falls to NONE (per-cell park). */
    private class ShardStage(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        @Suppress("unused")
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init { inlet.serve(Propagate<String> { received += it }) }
    }

    /** The downstream consumer whose scattered attention drives the shards' bands. */
    private class ConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        init { inlet.serve(Propagate<String> { /* attention source only */ }) }
    }

    private interface ShardFeed {
        val inlet: Use<Propagate<String>>
    }

    private class ScatterFixture(scatter: Boolean) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), attention = AttentionPolicy(suspendAfter = 3))
        val covering = ShardStage()
        val nonCovering = ShardStage()
        val consumer = ConsumerCell()

        // scope = the consumer's declared interest; the shards' assigned interests.
        private val scope: Interest = Interest.Ranges(listOf(Interest.Ranges.Range(0, 10)))
        private val shardInterest: Map<Any?, Interest> = mapOf(
            covering.outlet to Interest.Ranges(listOf(Interest.Ranges.Range(0, 10))),
            nonCovering.outlet to Interest.Ranges(listOf(Interest.Ranges.Range(10, 20))),
        )

        init {
            host.managementInlet.call.spawn(covering)
            host.managementInlet.call.spawn(nonCovering)
            controller.runToIdle()

            val consumerAttention = AttentionSupport.of(consumer)
            if (scatter) {
                // the metadata plane reuses the data plane's overlap rule: attend only
                // links whose upstream shard interest overlaps the consumer's scope.
                consumerAttention.scatter = { link -> (shardInterest[link.fromPort] ?: Interest.Total).overlaps(scope) }
            }
            AttentionSupport.of(covering)
            AttentionSupport.of(nonCovering)

            for (shard in listOf(covering, nonCovering)) {
                // link the consumer inlet under each shard outlet so attention travels upstream.
                (shard.outlet.linkTo(consumer.inlet as LinkFrom<Propagate<String>>) is LinkResult.Connected).shouldBeTrue()
            }
            consumerAttention.attend(1f)
        }

        fun feed(count: Int) {
            val cov = host.lookup<ShardFeed>(covering.ref)!!.inlet.call
            val non = host.lookup<ShardFeed>(nonCovering.ref)!!.inlet.call
            repeat(count) { i ->
                cov.propagate("hot-$i")
                non.propagate("cold-$i")
            }
            controller.runToIdle()
        }
    }

    @Test
    fun `interest scatter attends the covering shard and parks the non-covering one`() {
        val f = ScatterFixture(scatter = true)

        AttentionSupport.of(f.covering).band shouldBe AttentionBand.HIGH
        AttentionSupport.of(f.nonCovering).band shouldBe AttentionBand.NONE

        f.feed(8)
        // covering shard runs hot: every message delivered; non-covering parked some.
        f.covering.received.size shouldBe 8
        (f.nonCovering.received.size < 8).shouldBeTrue()

        // widening the scope (accept every link) re-attends the non-covering shard;
        // the park replays in order — nothing lost.
        AttentionSupport.of(f.consumer).scatter = { true }
        f.controller.runToIdle()
        f.nonCovering.received shouldBe (0 until 8).map { "cold-$it" }
    }

    @Test
    fun `control - without scatter the broadcast attends both shards and neither parks`() {
        val f = ScatterFixture(scatter = false)

        AttentionSupport.of(f.covering).band shouldBe AttentionBand.HIGH
        AttentionSupport.of(f.nonCovering).band shouldBe AttentionBand.HIGH

        f.feed(8)
        f.covering.received.size shouldBe 8
        f.nonCovering.received.size shouldBe 8 // no park: divergence from the scattered case
    }

    // ---------- Scenario 2: DEGRADE covering-quorum shrink across park/resume ----------

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private interface SetDeltaInletProxy {
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

    private val originTags: (Invocation) -> Collection<Timestamp> = { inv ->
        (inv.args.firstOrNull() as? SetDelta<*>)?.let { it.adds.values.flatten() + it.dels.values.flatten() }
            ?: emptyList()
    }

    private fun reroute(outlet: FanOutlet<Propagate<SetDelta<String>>>, inletRef: PortRef, routed: Propagate<SetDelta<String>>) {
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    /** One released board value + whether every CURRENT (non-suspended) covering member had delivered it. */
    private data class Obs(val element: String, val allCurrentDelivered: Boolean)

    private data class BoardRun(val observations: List<Obs>, val producedDuringPark: Int)

    /**
     * Three Total-interest replicas of one logical set; a board on p0 reads arm
     * `a` and gates each wave on the covering quorum. Covering replica `c` parks
     * mid-run; [publishStall] controls whether it publishes the recoverable Stall.
     */
    private fun runBoard(seed: Long, mode: GlitchFreeCell.WaveMode, publishStall: Boolean): BoardRun {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val peers = List(3) { Peer(controller) }
        for (i in peers.indices) for (j in i + 1 until peers.size) Peering.loopback(peers[i].side, peers[j].side)
        val logicalId = UUID.randomUUID()

        val refs = List(3) { CellRef(logicalId, it.toLong()) }
        val cells = refs.mapIndexed { i, ref -> SetCell<String>(ref).also { peers[i].replication.replicate(it, peers[i].host) } }
        controller.runToIdle()
        val byRef = refs.zip(cells).toMap()

        val p0 = peers[0]
        val gf = GlitchFreeCell(propagateSetDelta, mode = mode)
        p0.host.managementInlet.call.spawn(gf)
        val routedGf = p0.host.lookup<SetDeltaInletProxy>(gf.ref)!!.inlet.call
        val gfInletFrom = @Suppress("UNCHECKED_CAST") (gf.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        val aOut = @Suppress("UNCHECKED_CAST") (cells[0].outlet as FanOutlet<Propagate<SetDelta<String>>>)
        (aOut.linkTo(gfInletFrom) is LinkResult.Connected).shouldBeTrue()
        reroute(aOut, gf.inlet.ref, routedGf)

        val p0Companion = p0.replication.watermarkOf(logicalId)!!
        val observations = mutableListOf<Obs>()
        gf.outlet.subscribe(Use.fixed(Propagate<SetDelta<String>> { delta ->
            delta.adds.keys.forEach { value ->
                val suspended = p0Companion.suspended()
                val currentCovering = p0.registry.instancesOf(logicalId)
                    .filter { civictech.cell.data.WatermarkCell.slotId(p0.replication.watermarkRef(it)) !in suspended }
                val allHave = currentCovering.isNotEmpty() && currentCovering.all { byRef[it]?.membership()?.contains(value) ?: false }
                observations += Obs(value, allHave)
            }
        }, PortRef.generate()))

        gf.useReplicaFrontier(
            p0.replication.replicaFrontier(logicalId, degrade = (mode == GlitchFreeCell.WaveMode.DEGRADE)),
            originTags,
        )
        p0.replication.onWatermarkAdvance(logicalId) { gf.recheck() }

        val aInlet = (HostedCellProxy.create(refs[0], p0.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        // phase 1 — all three up: every wave completes on the full quorum.
        for (op in 1..10) {
            aInlet.add("w$op")
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        gf.recheck(); controller.runToIdle()
        val countBeforePark = observations.size

        // covering replica c parks; publishes the recoverable Stall unless suppressed.
        peers[2].host.managementInlet.call.suspend(refs[2])
        if (publishStall) peers[2].replication.watermarkOf(logicalId)?.suspend()
        repeat(4) { controller.step() }

        // phase 2 — c frozen: DEGRADE keeps producing (c dropped), WAIT/suppressed hold.
        for (op in 11..20) {
            aInlet.add("w$op")
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        gf.recheck(); controller.runToIdle()
        val producedDuringPark = observations.size - countBeforePark

        // resume c: retract the Stall; its frozen row catches up (PN-2 baseline).
        peers[2].host.managementInlet.call.resume(refs[2])
        if (publishStall) peers[2].replication.watermarkOf(logicalId)?.resume()
        controller.runToIdle()
        gf.recheck(); controller.runToIdle()

        return BoardRun(observations, producedDuringPark)
    }

    @Test
    fun `DEGRADE board keeps producing across a covering instance's park and resume - 100 seeds`() {
        for (seed in 0L until 100L) {
            val run = runBoard(seed, GlitchFreeCell.WaveMode.DEGRADE, publishStall = true)
            // safety: never a value a current covering member has not delivered (no torn value).
            run.observations.forEach { it.allCurrentDelivered.shouldBeTrue() }
            // liveness: every write eventually surfaces.
            run.observations.map { it.element }.toSet() shouldBe (1..20).map { "w$it" }.toSet()
            // the board kept producing while the covering member was parked.
            run.producedDuringPark shouldBeGreaterThan 0
        }
    }

    @Test
    fun `control a - the WAIT board stalls during the covering instance's park`() {
        // WAIT holds every wave through the whole park (correct, documented); the
        // held waves complete only after resume.
        for (seed in 0L until 100L) {
            val run = runBoard(seed, GlitchFreeCell.WaveMode.WAIT, publishStall = true)
            run.producedDuringPark shouldBe 0
            run.observations.map { it.element }.toSet() shouldBe (1..20).map { "w$it" }.toSet()
        }
    }

    @Test
    fun `control b - a DEGRADE board with the Stall suppressed wedges like WAIT during the park`() {
        // Without the published Stall the DEGRADE quorum cannot shrink, so the
        // parked covering member wedges the board exactly as WAIT does.
        for (seed in 0L until 100L) {
            val run = runBoard(seed, GlitchFreeCell.WaveMode.DEGRADE, publishStall = false)
            run.producedDuringPark shouldBe 0
        }
    }
}
