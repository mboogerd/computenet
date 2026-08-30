package civictech.iroh

import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `PROTOCOL.md` §4's complete exchange, driven end to end through
 * [SidecarClient] against two real sidecar processes: A accepts, B dials.
 *
 * Skip-gated: without `-Piroh.enabled=true` this reports SKIPPED, never failed —
 * see [SidecarBinary].
 */
class SidecarExchangeTest {

    @Test
    fun `two sidecars peer, exchange frames both ways, and close exactly once per side`() {
        val binary = SidecarBinary.orSkip()

        SidecarProcess.spawn(binary).use { sidecarA ->
            SidecarProcess.spawn(binary).use { sidecarB ->
                sidecarA.connect().use { hostA ->
                    sidecarB.connect().use { hostB ->
                        // ---- GET_ID on both sides (§4, first two lines) -----
                        assertContentEquals(sidecarA.nodeId, hostA.getId())
                        assertContentEquals(sidecarB.nodeId, hostB.getId())

                        // ---- A: inbound handler, then LISTEN -> LISTENING ----
                        val inboundLinks = LinkedBlockingQueue<Pair<SidecarLink, RecordingLinkListener>>()
                        hostA.onInboundLink { link ->
                            RecordingLinkListener("A/link${link.id}").also { inboundLinks.put(link to it) }
                        }
                        val addresses = hostA.listen()
                        assertTrue(addresses.isNotEmpty(), "LISTENING carried no addresses")

                        // ---- B: ADD_PEER -> PEER_ADDED ----------------------
                        val added = hostB.addPeer(sidecarA.nodeId, addresses)
                        assertContentEquals(sidecarA.nodeId, added)

                        // ---- B: DIAL -> LINK_UP on the host's odd id --------
                        val listenerB = RecordingLinkListener("B")
                        val linkB = hostB.dial(sidecarA.nodeId, listenerB)
                        assertTrue(SidecarProtocol.isHostLink(linkB.id), "dialled link ${linkB.id} is not odd/non-zero")
                        assertEquals(LinkDirection.OUTBOUND, linkB.direction)
                        assertContentEquals(sidecarA.nodeId, linkB.remoteNodeId)

                        // ---- A: LINK_UP on a sidecar-allocated even id ------
                        val (linkA, listenerA) = inboundLinks.poll(30, TimeUnit.SECONDS)
                            ?: fail("A never saw LINK_UP for the inbound connection")
                        assertTrue(SidecarProtocol.isSidecarLink(linkA.id), "accepted link ${linkA.id} is not even/non-zero")
                        assertEquals(LinkDirection.INBOUND, linkA.direction)
                        assertContentEquals(sidecarB.nodeId, linkA.remoteNodeId)

                        // The direction byte differs between the two sides.
                        assertNotEquals(linkA.direction, linkB.direction)

                        // ---- DATA B -> A, in order, including > 64 KiB ------
                        val small = byteArrayOf(1, 2, 3)
                        val large = Random(20260830).nextBytes(70_000)
                        linkB.send(small)
                        linkB.send(large)
                        assertContentEquals(small, listenerA.nextData(), "first frame at A was not the first sent")
                        assertContentEquals(large, listenerA.nextData(), "second frame at A was not the second sent")

                        // ---- DATA A -> B ------------------------------------
                        // linkA is INBOUND; send() has already waited for the
                        // dialler's first frame (PROTOCOL.md §3, LINK_UP), which
                        // arrived above, so nothing queues against an unadopted
                        // stream. That wait IS the computenet-ey4v avoidance path.
                        assertTrue(linkA.peerHasSpoken)
                        val reply = Random(1234).nextBytes(70_001)
                        linkA.send(reply)
                        linkA.send(small)
                        assertContentEquals(reply, listenerB.nextData(), "first frame at B was not the first sent")
                        assertContentEquals(small, listenerB.nextData(), "second frame at B was not the second sent")

                        // No frame was refused on either side.
                        assertEquals(emptyList(), listenerA.errors.toList())
                        assertEquals(emptyList(), listenerB.errors.toList())

                        // ---- CLOSE_LINK -> LINK_DOWN, exactly once per side --
                        linkB.close()
                        listenerB.nextDown()
                        listenerA.nextDown()
                        assertTrue(listenerB.noFurtherDown(), "B saw more than one LINK_DOWN")
                        assertTrue(listenerA.noFurtherDown(), "A saw more than one LINK_DOWN")
                        assertEquals(1, listenerB.downCount.get())
                        assertEquals(1, listenerA.downCount.get())

                        // The host connection is still answering after the link went down.
                        assertContentEquals(sidecarA.nodeId, hostA.getId())
                        assertContentEquals(sidecarB.nodeId, hostB.getId())
                    }
                }
            }
        }
    }
}
