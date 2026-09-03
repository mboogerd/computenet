package civictech.iroh

import civictech.iroh.SidecarProtocol.CONTROL_LINK
import civictech.iroh.SidecarProtocol.DIRECTION_INBOUND
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.Kind
import civictech.iroh.SidecarProtocol.MAX_MESSAGE_LEN
import civictech.iroh.SidecarProtocol.MSG_HEADER_LEN
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The codec against `iroh/sidecar/PROTOCOL.md` §2 and §3. Pure — no sidecar, no
 * socket — so these run on every lane, flag or no flag.
 */
class SidecarCodecTest {

    private val peerId = ByteArray(NODE_ID_LEN) { (it + 1).toByte() }
    private val otherId = ByteArray(NODE_ID_LEN) { (0xa0 + it).toByte() }

    private fun roundTripHost(message: HostMessage): HostMessage {
        val wire = SidecarCodec.encode(message)
        val frame = assertIs<Decoded.Ok<Frame>>(SidecarCodec.decodeFrame(wire)).message
        return assertIs<Decoded.Ok<HostMessage>>(SidecarCodec.asHostMessage(frame)).message
    }

    private fun roundTripSidecar(message: SidecarMessage): SidecarMessage {
        val wire = SidecarCodec.encode(message)
        val frame = assertIs<Decoded.Ok<Frame>>(SidecarCodec.decodeFrame(wire)).message
        return assertIs<Decoded.Ok<SidecarMessage>>(SidecarCodec.asSidecarMessage(frame)).message
    }

    @Test
    fun `every host to sidecar kind round-trips`() {
        assertIs<HostMessage.GetId>(roundTripHost(HostMessage.GetId))
        assertIs<HostMessage.Listen>(roundTripHost(HostMessage.Listen))

        val addPeer = assertIs<HostMessage.AddPeer>(
            roundTripHost(HostMessage.AddPeer(peerId, listOf("127.0.0.1:41001", "[::1]:41001"))),
        )
        assertContentEquals(peerId, addPeer.nodeId)
        assertEquals(listOf("127.0.0.1:41001", "[::1]:41001"), addPeer.addresses)

        val emptyAddrs = assertIs<HostMessage.AddPeer>(roundTripHost(HostMessage.AddPeer(peerId, emptyList())))
        assertEquals(emptyList(), emptyAddrs.addresses)

        val dial = assertIs<HostMessage.Dial>(roundTripHost(HostMessage.Dial(7L, peerId)))
        assertEquals(7L, dial.link)
        assertContentEquals(peerId, dial.peerId)

        val data = assertIs<HostMessage.Data>(roundTripHost(HostMessage.Data(3L, byteArrayOf(1, 2, 3))))
        assertEquals(3L, data.link)
        assertContentEquals(byteArrayOf(1, 2, 3), data.payload)

        assertEquals(HostMessage.CloseLink(9L), roundTripHost(HostMessage.CloseLink(9L)))
        assertIs<HostMessage.Shutdown>(roundTripHost(HostMessage.Shutdown))
    }

    @Test
    fun `every sidecar to host kind round-trips`() {
        assertContentEquals(peerId, assertIs<SidecarMessage.Id>(roundTripSidecar(SidecarMessage.Id(peerId))).nodeId)

        assertEquals(
            listOf("127.0.0.1:49812", "[::1]:49812"),
            assertIs<SidecarMessage.Listening>(
                roundTripSidecar(SidecarMessage.Listening(listOf("127.0.0.1:49812", "[::1]:49812"))),
            ).addresses,
        )

        assertContentEquals(
            otherId,
            assertIs<SidecarMessage.PeerAdded>(roundTripSidecar(SidecarMessage.PeerAdded(otherId))).nodeId,
        )

        val up = assertIs<SidecarMessage.LinkUp>(roundTripSidecar(SidecarMessage.LinkUp(2L, otherId, DIRECTION_INBOUND)))
        assertEquals(2L, up.link)
        assertContentEquals(otherId, up.remoteNodeId)
        assertEquals(DIRECTION_INBOUND, up.direction)
        assertEquals(LinkDirection.INBOUND, LinkDirection.of(up.direction))
        assertEquals(
            LinkDirection.OUTBOUND,
            LinkDirection.of(
                assertIs<SidecarMessage.LinkUp>(
                    roundTripSidecar(SidecarMessage.LinkUp(1L, otherId, DIRECTION_OUTBOUND)),
                ).direction,
            ),
        )

        assertEquals(SidecarMessage.LinkDown(2L, "link closed"), roundTripSidecar(SidecarMessage.LinkDown(2L, "link closed")))
        assertEquals(SidecarMessage.Failure(0L, "bad payload"), roundTripSidecar(SidecarMessage.Failure(0L, "bad payload")))
        assertEquals(SidecarMessage.Failure(4L, "link queue full"), roundTripSidecar(SidecarMessage.Failure(4L, "link queue full")))

        val data = assertIs<SidecarMessage.Data>(roundTripSidecar(SidecarMessage.Data(2L, byteArrayOf(9, 8))))
        assertEquals(2L, data.link)
        assertContentEquals(byteArrayOf(9, 8), data.payload)
    }

    @Test
    fun `the minimum message is thirteen bytes on the wire`() {
        // PROTOCOL.md §2: length counts kind + link + payload, minimum 9, so the
        // smallest message is 4 + 9 = 13 bytes.
        val wire = SidecarCodec.encode(HostMessage.GetId)
        assertEquals(13, wire.size)
        assertEquals(MSG_HEADER_LEN, Frame.readInt(wire, 0))
        assertEquals(Kind.GET_ID, wire[4])
        assertEquals(CONTROL_LINK, Frame.readLong(wire, 5))
        assertIs<HostMessage.GetId>(roundTripHost(HostMessage.GetId))
    }

    @Test
    fun `a DATA payload larger than 64 KiB round-trips both ways`() {
        val big = Random(20260830).nextBytes(70_000)

        val host = assertIs<HostMessage.Data>(roundTripHost(HostMessage.Data(1L, big)))
        assertContentEquals(big, host.payload)

        val fromSidecar = assertIs<SidecarMessage.Data>(roundTripSidecar(SidecarMessage.Data(2L, big)))
        assertContentEquals(big, fromSidecar.payload)

        // Both directions build the header at the same site, so their wire forms
        // differ in nothing but the link id.
        assertEquals(
            SidecarCodec.encode(HostMessage.Data(1L, big)).size,
            SidecarCodec.encode(SidecarMessage.Data(1L, big)).size,
        )
        assertContentEquals(
            SidecarCodec.encode(HostMessage.Data(1L, big)),
            SidecarCodec.encode(SidecarMessage.Data(1L, big)),
        )
    }

    @Test
    fun `a length below nine is a typed decode failure`() {
        val wire = ByteArray(4 + 8)
        Frame.writeInt(wire, 0, 8)
        val decoded = assertIs<Decoded.Malformed>(SidecarCodec.decodeFrame(wire))
        assertEquals(DecodeProblem.LENGTH_BELOW_MINIMUM, decoded.problem)
        assertTrue(decoded.detail.contains("8"), decoded.detail)
    }

    @Test
    fun `a length above the maximum is a typed decode failure`() {
        val wire = ByteArray(8)
        Frame.writeInt(wire, 0, MAX_MESSAGE_LEN + 1)
        assertEquals(
            DecodeProblem.LENGTH_ABOVE_MAXIMUM,
            assertIs<Decoded.Malformed>(SidecarCodec.decodeFrame(wire)).problem,
        )
    }

    @Test
    fun `a truncated body is a typed decode failure`() {
        val wire = SidecarCodec.encode(HostMessage.Data(1L, byteArrayOf(1, 2, 3, 4)))
        assertEquals(
            DecodeProblem.TRUNCATED,
            assertIs<Decoded.Malformed>(SidecarCodec.decodeFrame(wire.copyOf(wire.size - 2))).problem,
        )
    }

    @Test
    fun `an unknown kind is a typed decode failure in both directions`() {
        val frame = Frame(0x7f, 0L, ByteArray(0))
        assertEquals(DecodeProblem.UNKNOWN_KIND, assertIs<Decoded.Malformed>(SidecarCodec.asHostMessage(frame)).problem)
        assertEquals(DecodeProblem.UNKNOWN_KIND, assertIs<Decoded.Malformed>(SidecarCodec.asSidecarMessage(frame)).problem)
    }

    @Test
    fun `a kind decoded against the wrong direction is a typed decode failure`() {
        val id = SidecarCodec.frameOf(SidecarMessage.Id(peerId))
        assertEquals(DecodeProblem.WRONG_DIRECTION, assertIs<Decoded.Malformed>(SidecarCodec.asHostMessage(id)).problem)

        val getId = SidecarCodec.frameOf(HostMessage.GetId)
        assertEquals(DecodeProblem.WRONG_DIRECTION, assertIs<Decoded.Malformed>(SidecarCodec.asSidecarMessage(getId)).problem)
    }

    @Test
    fun `a short payload for a fixed-width kind is a typed decode failure`() {
        assertEquals(
            DecodeProblem.MALFORMED_PAYLOAD,
            assertIs<Decoded.Malformed>(SidecarCodec.asSidecarMessage(Frame(Kind.ID, 0L, ByteArray(31)))).problem,
        )
        assertEquals(
            DecodeProblem.MALFORMED_PAYLOAD,
            assertIs<Decoded.Malformed>(SidecarCodec.asSidecarMessage(Frame(Kind.LINK_UP, 2L, ByteArray(NODE_ID_LEN)))).problem,
        )
        assertEquals(
            DecodeProblem.MALFORMED_PAYLOAD,
            assertIs<Decoded.Malformed>(SidecarCodec.asHostMessage(Frame(Kind.DIAL, 1L, ByteArray(4)))).problem,
        )
    }

    @Test
    fun `link id parity follows PROTOCOL section 2`() {
        assertTrue(SidecarProtocol.isHostLink(1L))
        assertTrue(SidecarProtocol.isSidecarLink(2L))
        assertTrue(!SidecarProtocol.isHostLink(0L))
        assertTrue(!SidecarProtocol.isSidecarLink(0L))
        // DIAL on an even id is not a host link, and the codec says so.
        assertEquals(
            DecodeProblem.WRONG_LINK,
            assertIs<Decoded.Malformed>(SidecarCodec.asHostMessage(Frame(Kind.DIAL, 2L, peerId))).problem,
        )
    }

    @Test
    fun `hex helpers round-trip a node id`() {
        assertEquals(64, peerId.toHex().length)
        assertContentEquals(peerId, peerId.toHex().hexToBytesOrNull())
        assertEquals(null, "zz".hexToBytesOrNull())
        assertEquals(null, "abc".hexToBytesOrNull())
    }
}
