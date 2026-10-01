package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.Progress
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.InvocationSink
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-1mbp, executed by computenet-gyvli.5: a bridged reconnect over
 * ONE `PortAddress` pair used to leave both [WireEdgeLink] records live.
 * computenet-5nw9 decided the generic `(from, to, role)` triple cannot key
 * supersession on the bridged path — `bridgeTo`/`bridgeFrom` each mint a
 * fresh surrogate `PortRef` per call, so that triple is a per-call nonce
 * (`BridgedLinkSupersessionTest`, `civictech.cell.link`). The identity that
 * IS stable across calls is the address pair itself, and this is where 1mbp
 * said the guard belongs: two live records over one address pair means
 * anything that fans a message over `linking.links` — `absorbAck`
 * (`civictech.cell.control.AbsorbAck`), `Attention.emitUpstream`
 * (`civictech.cell.control.Attention`) — relays it to the SAME destination
 * address twice. Both tests below reproduce that fan-out directly (the same
 * `linking.links.forEach { Protocols.sendX(it, ...) }` shape those two
 * production call sites use) rather than driving a full absorbing cell or
 * Attention aggregator, since the defect is in the link bookkeeping, not in
 * either caller.
 */
class BridgedReconnectSupersessionTest {

    private class ProducerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
    }

    private class ConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
    }

    /** One registry, one host — the same "bridge across a host cut" shape :oracle's `bridgeAcrossCut` uses. */
    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val producer = ProducerCell()
        val consumer = ConsumerCell()
        val sink = InvocationSink(registry::deliver)
        val fromAddr = PortAddress(producer.ref, "outlet")
        val toAddr = PortAddress(consumer.ref, "inlet")

        init {
            host.managementInlet.call.spawn(producer)
            host.managementInlet.call.spawn(consumer)
            controller.runToIdle()
        }
    }

    @Test
    fun `a second bridgeTo over the same address pair supersedes the first - one Progress reaches the consumer, not two`() {
        val rig = Rig(seed = 1)

        val progressSeen = mutableListOf<Progress>()
        ProtocolSupport.of(rig.consumer.inlet).handle(Protocols.Progress) { _, message ->
            progressSeen += message as Progress
        }

        val first = (rig.producer.outlet.bridgeTo(selfAddr = rig.fromAddr, toAddr = rig.toAddr, sink = rig.sink)
            as LinkResult.Connected).link
        val firstClosed = mutableListOf<Link>()
        first.onUnlink { firstClosed += it }

        val second = (rig.producer.outlet.bridgeTo(selfAddr = rig.fromAddr, toAddr = rig.toAddr, sink = rig.sink)
            as LinkResult.Connected).link
        rig.controller.runToIdle()

        // the first half-link is superseded: closed (EdgeClose observed via its
        // own unlink listener) and evicted from the outlet's link bookkeeping —
        // only the second remains.
        firstClosed shouldContainExactly listOf(first)
        rig.producer.outlet.linking.links shouldContainExactly listOf(second)

        // one emission from the producer, fanned over its OWN links exactly as
        // `absorbAck` does (`civictech.cell.control.AbsorbAck`).
        rig.producer.outlet.linking.links.forEach { link ->
            Protocols.sendDownstream(link, Protocols.Progress, Progress(UUID.randomUUID(), 1))
        }
        rig.controller.runToIdle()

        // pre-fix: two live records for the same destination address deliver
        // this fan-out twice; post-fix, exactly one record remains.
        progressSeen.size shouldBe 1
    }

    @Test
    fun `a second bridgeFrom over the same address pair supersedes the first - one Attention reaches the producer, not two`() {
        val rig = Rig(seed = 2)

        val attentionSeen = mutableListOf<Attention>()
        ProtocolSupport.of(rig.producer.outlet).handle(Protocols.Attention) { _, message ->
            attentionSeen += message as Attention
        }

        val first = (rig.consumer.inlet.bridgeFrom(selfAddr = rig.toAddr, fromAddr = rig.fromAddr, sink = rig.sink)
            as LinkResult.Connected).link
        val firstClosed = mutableListOf<Link>()
        first.onUnlink { firstClosed += it }

        val second = (rig.consumer.inlet.bridgeFrom(selfAddr = rig.toAddr, fromAddr = rig.fromAddr, sink = rig.sink)
            as LinkResult.Connected).link
        rig.controller.runToIdle()

        firstClosed shouldContainExactly listOf(first)
        rig.consumer.inlet.linking.links shouldContainExactly listOf(second)

        // one emission from the consumer side, fanned upstream over its OWN
        // links exactly as `Attention.emitUpstream` does.
        rig.consumer.inlet.linking.links.forEach { link ->
            Protocols.sendUpstream(link, Protocols.Attention, Attention(0.9f, 1))
        }
        rig.controller.runToIdle()

        attentionSeen.size shouldBe 1
    }
}
