package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.attention.Attention
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeEvent
import civictech.cell.protocol.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.protocol.ProtocolId
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W3.2 (G-35/G-39 phase B, spec 41 point 4): generic protocols and topology
 * edge events crossing a bridge between two hosted registries — the wire
 * realization of W1.5 (in-process PORT_PROTOCOL dispatch) and W1.7 (in-process
 * EdgeOpen/EdgeClose). Two `ManagedHost`s under one `SimulationController`
 * model two processes; a hand-built full-duplex bridge (one egress/ingress
 * pair per direction, mirroring `Peering`) carries both data and protocol
 * frames — `Attention` travels upstream (consumer → producer) over the
 * reverse leg, `EdgeOpen`/`EdgeClose` travel downstream over the forward leg.
 */
class ProtocolBridgeTest {

    interface DataInletProxy {
        val inlet: Use<Propagate<Int>>
    }

    class ProducerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
    }

    class ConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())

        init {
            inlet.serve(object : Propagate<Int> {
                override fun propagate(value: Int) {
                    arrivals += value
                }
            })
        }
    }

    /** Two hosts, two registries, a full-duplex bridge — everything needed to establish bridged links. */
    private class TwoHosts(seed: Long = 0) {
        val controller = SimulationController(seed)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)

        lateinit var egressAtoB: BridgeEgressCell
        lateinit var egressBtoA: BridgeEgressCell

        init {
            val ingressAtoB = BridgeIngressCell(
                deliverTo = InvocationSink(registryB::deliver),
                replySink = InvocationSink { egressBtoA.deliver(it) },
            )
            val ingressBtoA = BridgeIngressCell(
                deliverTo = InvocationSink(registryA::deliver),
                replySink = InvocationSink { egressAtoB.deliver(it) },
            )
            hostB.managementInlet.call.spawn(ingressAtoB)
            hostA.managementInlet.call.spawn(ingressBtoA)

            egressAtoB = BridgeEgressCell()
            egressBtoA = BridgeEgressCell()

            val ingressAtoBApi = (HostedCellProxy.create(ingressAtoB.ref, registryB, FrameInletProxy::class.java)
                    as FrameInletProxy).inlet.call
            egressAtoB.outlet.subscribe(Use.fixed(ingressAtoBApi, civictech.cell.port.PortRef.generate()))

            val ingressBtoAApi = (HostedCellProxy.create(ingressBtoA.ref, registryA, FrameInletProxy::class.java)
                    as FrameInletProxy).inlet.call
            egressBtoA.outlet.subscribe(Use.fixed(ingressBtoAApi, civictech.cell.port.PortRef.generate()))
        }
    }

    interface FrameInletProxy {
        val inlet: Use<Propagate<ByteArray>>
    }

    @Test
    fun `attention crosses upstream, EdgeOpen crosses downstream, unlink fires EdgeClose`() {
        val net = TwoHosts()
        val producer = ProducerCell()
        net.hostA.managementInlet.call.spawn(producer)
        val consumer = ConsumerCell()
        net.hostB.managementInlet.call.spawn(consumer)
        net.controller.runToIdle()

        val attentionSeen = mutableListOf<Attention>()
        ProtocolSupport.of(producer.outlet).handle(Protocols.Attention) { _, message ->
            attentionSeen += message as Attention
        }
        val topologySeen = mutableListOf<EdgeEvent>()
        ProtocolSupport.of(consumer.inlet).handle(Protocols.TopologyOrder) { _, event ->
            topologySeen += event as EdgeEvent
        }

        // ordinary data path (unaffected by the protocol machinery)
        val dataApi = (HostedCellProxy.create(consumer.ref, InvocationSink(net.egressAtoB::deliver), DataInletProxy::class.java)
                as DataInletProxy).inlet.call
        val dataRef = civictech.cell.port.PortRef.generate()
        producer.outlet.subscribe(Use.fixed(dataApi, dataRef))

        // establish the bridged link: producer fires EdgeOpen downstream, crossing the wire
        val producerSide = (producer.outlet.bridgeTo(
            selfAddr = PortAddress(producer.ref, "outlet"),
            toAddr = PortAddress(consumer.ref, "inlet"),
            sink = InvocationSink(net.egressAtoB::deliver),
        ) as LinkResult.Connected).link
        val consumerSide = (consumer.inlet.bridgeFrom(
            selfAddr = PortAddress(consumer.ref, "inlet"),
            fromAddr = PortAddress(producer.ref, "outlet"),
            sink = InvocationSink(net.egressBtoA::deliver),
        ) as LinkResult.Connected).link
        net.controller.runToIdle()

        topologySeen shouldContainExactly listOf(EdgeOpen)

        // data flows normally alongside the protocol plane
        producer.outlet.call.propagate(1)
        producer.outlet.call.propagate(2)
        net.controller.runToIdle()
        consumer.arrivals shouldContainExactly listOf(1, 2)

        // attention travels upstream (consumer -> producer) over the reverse leg
        Protocols.sendUpstream(consumerSide, Protocols.Attention, Attention(0.9f))
        net.controller.runToIdle()
        attentionSeen shouldContainExactly listOf(Attention(0.9f))

        // late-join: a second consumer joins mid-stream, sees exactly one EdgeOpen, no stale replay
        val consumer2 = ConsumerCell()
        net.hostB.managementInlet.call.spawn(consumer2)
        net.controller.runToIdle()
        val topology2Seen = mutableListOf<EdgeEvent>()
        ProtocolSupport.of(consumer2.inlet).handle(Protocols.TopologyOrder) { _, event ->
            topology2Seen += event as EdgeEvent
        }
        val dataApi2 = (HostedCellProxy.create(consumer2.ref, InvocationSink(net.egressAtoB::deliver), DataInletProxy::class.java)
                as DataInletProxy).inlet.call
        val dataRef2 = civictech.cell.port.PortRef.generate()
        producer.outlet.subscribe(Use.fixed(dataApi2, dataRef2))
        val producerSide2 = (producer.outlet.bridgeTo(
            selfAddr = PortAddress(producer.ref, "outlet"),
            toAddr = PortAddress(consumer2.ref, "inlet"),
            sink = InvocationSink(net.egressAtoB::deliver),
        ) as LinkResult.Connected).link
        net.controller.runToIdle()
        topology2Seen shouldContainExactly listOf(EdgeOpen)
        consumer2.arrivals.shouldNotContain(1) // no pre-join replay

        producer.outlet.call.propagate(3)
        net.controller.runToIdle()
        consumer.arrivals shouldContainExactly listOf(1, 2, 3)
        consumer2.arrivals shouldContainExactly listOf(3)

        // unlink mid-stream: the first edge tears down, EdgeClose crosses, data for it stops
        unlinkBridge(producerSide, downstream = true)
        producer.outlet.unsubscribe(dataRef)
        net.controller.runToIdle()
        topologySeen shouldContainExactly listOf(EdgeOpen, EdgeClose)

        producer.outlet.call.propagate(4)
        net.controller.runToIdle()
        consumer.arrivals shouldContainExactly listOf(1, 2, 3) // unlinked: no further data
        consumer2.arrivals shouldContainExactly listOf(3, 4) // second edge unaffected
    }
}
