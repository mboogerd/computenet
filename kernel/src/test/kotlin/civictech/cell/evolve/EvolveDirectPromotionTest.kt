package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

import civictech.cell.evolve.ShadowPromotionTest.CollectorCell
import civictech.cell.evolve.ShadowPromotionTest.GateProxy
import civictech.cell.evolve.ShadowPromotionTest.SourceCell
import civictech.cell.evolve.ShadowPromotionTest.SummerV1
import civictech.cell.evolve.ShadowPromotionTest.SummerV2

class EvolveDirectPromotionTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)

    @Test
    fun `promoteDirect swaps the incumbent and continues the downstream stream`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val candidate = SummerV2(CellRef(logicalId, instanceId = 1))
        val collector = CollectorCell()

        listOf<Cell>(source, gate, incumbent, candidate, collector).forEach {
            host.managementInlet.call.spawn(it)
        }
        controller.runToIdle()

        val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy)
            .dataInlet.call
        source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
        gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
        gate.dataOutlet.subscribe(candidate.inlet as Use<Consumer<Int>>)
        incumbent.outlet.subscribe(collector.inlet as Use<Consumer<Long>>)
        gate.controlInlet.call.setGreen()

        host.cellAt(incumbent.ref) shouldBe incumbent
        host.cellAt(CellRef(UUID.randomUUID())) shouldBe null
        source.emit(1)
        controller.runToIdle()
        collector.received shouldBe listOf(1L)

        Evolve.promoteDirect(
            host = host,
            gate = gate,
            incumbent = incumbent,
            candidate = candidate,
            outletName = "outlet",
            downstream = listOf(collector.inlet),
        )
        controller.runToIdle()

        host.cellAt(incumbent.ref) shouldBe null
        host.cellAt(candidate.ref) shouldBe candidate
        host.portAt(incumbent.ref, "outlet") shouldBe null

        source.emit(2)
        controller.runToIdle()
        collector.received shouldBe listOf(1L, 3L)
    }

    @Test
    fun `promoteDirect refuses remote and custom authorities before the gate changes`() {
        val run = directRun()
        run.source.emit(1)
        run.controller.runToIdle()
        run.collector.received shouldBe listOf(1L)

        val remote = CurrentPeer.with(PeerId("mallory")) {
            shouldThrow<Evolve.Refused> {
                Evolve.promoteDirect(
                    host = run.host,
                    gate = run.gate,
                    incumbent = run.incumbent,
                    candidate = run.candidate,
                    outletName = "outlet",
                    downstream = listOf(run.collector.inlet),
                )
            }
        }
        remote.message!!.shouldContain("authority")
        run.host.cellAt(run.candidate.ref) shouldBe run.candidate

        val custom = shouldThrow<Evolve.Refused> {
            Evolve.promoteDirect(
                host = run.host,
                gate = run.gate,
                incumbent = run.incumbent,
                candidate = run.candidate,
                outletName = "outlet",
                downstream = listOf(run.collector.inlet),
                authority = EvolutionAuthority { "nobody" },
            )
        }
        custom.message!!.shouldContain("authority")

        run.source.emit(2)
        run.controller.runToIdle()
        run.collector.received shouldBe listOf(1L, 3L)
        run.host.cellAt(run.incumbent.ref) shouldBe run.incumbent
        run.host.cellAt(run.candidate.ref) shouldBe run.candidate
    }

    @Test
    fun `promoteDirect rejects a gate that is not a live traffic light`() {
        val run = directRun()

        val aborted = shouldThrow<Promotion.PromotionAborted> {
            Evolve.promoteDirect(
                host = run.host,
                gate = run.collector,
                incumbent = run.incumbent,
                candidate = run.candidate,
                outletName = "outlet",
                downstream = listOf(run.collector.inlet),
            )
        }
        aborted.message!!.shouldContain("PRECHECK")
        aborted.message!!.shouldContain("is not a live TrafficLightApi")
    }

    @Test
    fun `promoteReplicaDirect keeps the authority gate and permits a local same-ref candidate`() {
        val controller = SimulationController(seed = 1)
        val peer = Peer(controller)
        val incumbent = SetCell<String>(CellRef(UUID.randomUUID()))
        peer.replication.replicate(incumbent, peer.host)
        controller.runToIdle()
        val candidate = SetCell<String>(incumbent.ref)

        val remote = CurrentPeer.with(PeerId("mallory")) {
            shouldThrow<Evolve.Refused> {
                Evolve.promoteReplicaDirect(
                    host = peer.host,
                    replication = peer.replication,
                    incumbent = incumbent,
                    candidate = candidate,
                )
            }
        }
        remote.message!!.shouldContain("authority")
        peer.host.cellAt(incumbent.ref) shouldBe incumbent

        Evolve.promoteReplicaDirect(
            host = peer.host,
            replication = peer.replication,
            incumbent = incumbent,
            candidate = candidate,
        )
        controller.runToIdle()
        peer.host.cellAt(incumbent.ref) shouldBe candidate
    }

    private data class DirectRun(
        val controller: SimulationController,
        val host: ManagedHost,
        val source: SourceCell,
        val gate: TrafficLightCell<Consumer<Int>>,
        val incumbent: SummerV1,
        val candidate: SummerV2,
        val collector: CollectorCell,
    )

    private fun directRun(): DirectRun {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val logicalId = UUID.randomUUID()
        val source = SourceCell(consumerInt)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val incumbent = SummerV1(CellRef(logicalId, instanceId = 0))
        val candidate = SummerV2(CellRef(logicalId, instanceId = 1))
        val collector = CollectorCell()

        listOf<Cell>(source, gate, incumbent, candidate, collector).forEach {
            host.managementInlet.call.spawn(it)
        }
        controller.runToIdle()
        val routedGate = (HostedCellProxy.create(gate.ref, host, GateProxy::class.java) as GateProxy)
            .dataInlet.call
        source.outlet.subscribe(Use.fixed(routedGate, PortRef.generate()))
        gate.dataOutlet.subscribe(incumbent.inlet as Use<Consumer<Int>>)
        gate.dataOutlet.subscribe(candidate.inlet as Use<Consumer<Int>>)
        incumbent.outlet.subscribe(collector.inlet as Use<Consumer<Long>>)
        gate.controlInlet.call.setGreen()
        return DirectRun(controller, host, source, gate, incumbent, candidate, collector)
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }
}
