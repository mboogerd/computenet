package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Bridged frontier markers are protocol bookkeeping, like the in-process
 * [ManagedHost.stageBehindData] EdgeClose marker, and are not payload traffic
 * when attention parking is torn down.
 */
class BridgedMarkerTeardownTest {

    private class Probe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
    }

    @Test
    fun `despawning a cell with a parked bridged frontier marker does not count or dead-letter it`() {
        val controller = SimulationController(seed = 0)
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = LocationRegistry(),
            attention = AttentionPolicy(suspendAfter = 0),
        )
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )

        val probe = Probe()
        host.managementInlet.call.spawn(probe)
        controller.runToIdle()
        AttentionSupport.of(probe).attend(0f)
        val seen = mutableListOf<StallNotice>()
        ProtocolSupport.of(probe.inlet).handle(Protocols.Suspension) { _, message ->
            seen += message as StallNotice
        }

        val link = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(probe.ref),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "outlet"),
            toAddr = PortAddress(probe.ref, "inlet"),
        )
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = probe.ref,
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Suspension,
                protocolLink = link,
                protocolMessage = StallNotice.Stall(StallReason.SUSPENDED),
            ),
        )
        controller.runToIdle()

        // The marker was staged and attention-parked; it was not delivered.
        seen shouldBe emptyList()

        host.managementInlet.call.despawn(probe.ref)
        controller.runToIdle()

        host.supervisionAccounting().parkedDrainedOnTeardown shouldBe 0L
        letters shouldBe emptyList()
    }
}
