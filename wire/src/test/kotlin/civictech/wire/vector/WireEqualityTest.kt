package civictech.wire.vector

import civictech.cell.Borrowed
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.Protocols
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.cell.wire.WireCodec
import civictech.nature.Color
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.Ownership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class WireEqualityTest {

    private val seed: VectorDocument =
        VectorLoader.locate().documents().first { it.kind == VectorKind.FRAME && it.encoded?.utf8 != null }

    @Test
    fun `independent wrapper decodes compare structurally without consuming the sender's Owned value`() {
        val senderOwned = Owned("payload")
        val base = NeutralValues.frameOf(seed).invocation
        val invocation = base.copy(
            invocation = base.invocation.copy(
                args = listOf(
                    senderOwned,
                    Frozen(42L),
                    Borrowed(Owned("inner")),
                ),
            ),
        )

        val encoded = WireCodec.encode(invocation)
        val first = WireCodec.decodeFrame(encoded)
        val second = WireCodec.decodeFrame(encoded)

        assertNotEquals(first, second, "plain data-class equality must expose the wrappers' identity equality")
        assertEquals(
            WireEquality.normalize(first),
            WireEquality.normalize(second),
            "normalization must compare wrapper kind and recursively normalized contents",
        )
        assertNotEquals(WireEquality.normalize(Owned("x")), WireEquality.normalize(Owned("y")))
        assertNotEquals(WireEquality.normalize(Owned("x")), WireEquality.normalize(Frozen("x")))
        assertEquals(
            listOf(WireEquality.WrapperView("Owned", "x")),
            WireEquality.normalize(listOf(Owned("x"))),
        )
        assertEquals(
            listOf(
                WireEquality.WrapperView("Frozen", listOf(1L, 2L)),
                WireEquality.WrapperView(
                    "Borrowed",
                    linkedMapOf("k" to WireEquality.WrapperView("Owned", "inner")),
                ),
            ),
            WireEquality.normalize(
                listOf(
                    Frozen(listOf(1L, 2L)),
                    Borrowed(linkedMapOf("k" to Owned("inner"))),
                ),
            ),
            "normalization must recurse through List and Map values",
        )
        assertEquals("payload", senderOwned.take(), "normalization and codec encoding must not consume the sender's Owned value")
    }

    @Test
    fun `independent protocol decodes compare structurally through a wire link view`() {
        val producer = CellRef(UUID.fromString("00000000-0000-0000-0000-000000000042"))
        val consumer = CellRef(UUID.fromString("00000000-0000-0000-0000-000000000043"))
        val from = PortRef(UUID.fromString("00000000-0000-0000-0000-0000000000c1"), producer)
        val to = PortRef(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), consumer)
        val edge = WireEdgeLink(
            id = UUID.fromString("00000000-0000-0000-0000-00000000e001"),
            from = from,
            to = to,
            fromAddr = PortAddress(producer, "outlet"),
            toAddr = PortAddress(consumer, "inlet"),
            natures = NatureVector(
                mapOf(
                    NatureAxis.OWNERSHIP to Ownership.EXCLUSIVE,
                    NatureAxis.COLOR to Color.SUSPENDING,
                ),
            ),
        )
        val bytes = WireCodec.encode(
            HostedPortInvocation(
                cellRef = consumer,
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.TopologyOrder,
                protocolLink = edge,
                protocolMessage = EdgeOpen,
            ),
        )

        val first = WireCodec.decodeFrame(bytes)
        val second = WireCodec.decodeFrame(bytes)

        assertNotEquals(
            first.invocation,
            second.invocation,
            "raw protocol invocations must expose decoded WireEdgeLink identity equality",
        )
        assertEquals(
            WireEquality.normalize(first).invocation,
            WireEquality.normalize(second).invocation,
            "normalization must compare decoded protocol links structurally",
        )

        val link = WireEquality.normalize(first).invocation.protocolLink as WireEquality.LinkView
        assertEquals(edge.id, link.id)
        assertEquals(from, link.from)
        assertEquals(to, link.to)
        assertEquals(PortAddress(producer, "outlet"), link.fromAddr)
        assertEquals(PortAddress(consumer, "inlet"), link.toAddr)
        assertEquals(
            NatureVector(
                mapOf(
                    NatureAxis.OWNERSHIP to Ownership.EXCLUSIVE,
                    NatureAxis.COLOR to Color.SUSPENDING,
                ),
            ),
            link.natures,
        )

        val nonProtocol = WireCodec.decodeFrame(WireCodec.encode(NeutralValues.frameOf(seed).invocation))
        assertEquals(null, WireEquality.normalize(nonProtocol).invocation.protocolLink)
    }
}
