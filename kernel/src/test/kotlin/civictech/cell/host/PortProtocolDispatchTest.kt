package civictech.cell.host

import civictech.cell.HostTest
import civictech.cell.control.Attention
import civictech.cell.control.AttentionProtocol
import civictech.cell.control.SuspensionProtocol
import civictech.cell.host.SaturationProtocol
import civictech.cell.link.Link
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.nature.ProtocolCardinality
import civictech.nature.ProtocolDirection
import civictech.nature.ProtocolRegistry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class PortProtocolDispatchTest {
    @Test
    fun `generated descriptors record the shipped protocol semantics`() {
        val attention = ProtocolRegistry.protocol(Protocols.Attention.name)!!
        attention.contractId shouldBe civictech.nature.ContractRegistry.descriptor(AttentionProtocol::class.java)!!.contractId
        attention.direction shouldBe ProtocolDirection.UPSTREAM
        attention.band shouldBe 0
        attention.lane shouldBe "attention"
        attention.cardinality shouldBe ProtocolCardinality.FAN_IN_MERGE

        val suspension = ProtocolRegistry.protocol(Protocols.Suspension.name)!!
        suspension.contractId shouldBe civictech.nature.ContractRegistry.descriptor(SuspensionProtocol::class.java)!!.contractId
        suspension.direction shouldBe ProtocolDirection.DOWNSTREAM
        suspension.cardinality shouldBe ProtocolCardinality.FAN_OUT_BROADCAST

        val saturation = ProtocolRegistry.protocol(Protocols.Saturation.name)!!
        saturation.contractId shouldBe civictech.nature.ContractRegistry.descriptor(SaturationProtocol::class.java)!!.contractId
        saturation.direction shouldBe ProtocolDirection.UPSTREAM
        saturation.cardinality shouldBe ProtocolCardinality.FAN_IN_MERGE
    }

    @Test
    fun `PORT_PROTOCOL dispatches through ProtocolSupport while data intake is closed`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = HostTest.CollectingConsumerCell()
        host.managementInlet.call.spawn(cell)
        val link = object : Link {
            override val id = UUID.randomUUID()
            override val from = cell.inlet.ref
            override val to = cell.inlet.ref
            override fun unlink() = Unit
        }
        val received = mutableListOf<Attention>()
        ProtocolSupport.of(cell.inlet).handle(Protocols.Attention) { actualLink, message ->
            actualLink shouldBe link
            received += message as Attention
        }

        host.closeIntake()
        host.enqueueHostedInvocation(protocolInvocation(cell, link, Attention(.75f)))
        controller.runToIdle()

        received shouldBe listOf(Attention(.75f))
    }

    @Test
    fun `PORT_PROTOCOL rejects wave context`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = HostTest.CollectingConsumerCell()
        host.managementInlet.call.spawn(cell)
        val link = object : Link {
            override val id = UUID.randomUUID()
            override val from = cell.inlet.ref
            override val to = cell.inlet.ref
            override fun unlink() = Unit
        }
        val context = civictech.cell.MessageContext(
            civictech.cell.Timestamp(UUID.randomUUID(), 1), cell.inlet.ref
        )

        assertThrows<IllegalArgumentException> {
            host.enqueueHostedInvocation(protocolInvocation(cell, link, Attention(1f)).copy(
                invocation = Invocation("", emptyList(), emptyList(), context)
            ))
        }
    }

    private fun protocolInvocation(cell: HostTest.CollectingConsumerCell, link: Link, message: Any) =
        HostedPortInvocation(
            cell.ref, "inlet", HostedPortInvocation.Type.PORT_PROTOCOL,
            Invocation("", emptyList(), emptyList()),
            protocolId = Protocols.Attention,
            protocolLink = link,
            protocolMessage = message,
        )
}
