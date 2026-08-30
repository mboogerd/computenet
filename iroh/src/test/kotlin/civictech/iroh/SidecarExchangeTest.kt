package civictech.iroh

import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * The avoidance path itself, asserted so that removing it turns this red.
     *
     * `PROTOCOL.md` §3's `LINK_UP` entry: on the accepting side `LINK_UP` is out
     * before the link's QUIC stream exists, and until the dialler's first frame
     * adopts that stream the link's send queue has no consumer at all, so §2's
     * 256-frame bound is an absolute count there. [SidecarLink.send] on an
     * INBOUND link therefore waits for the peer's first frame rather than
     * queueing against an unadopted stream — the one route `PROTOCOL.md`
     * sanctions while `computenet-ey4v` is open.
     *
     * The sibling test above exercises the wait only where it is already
     * satisfied (the dialler has spoken by the time the accepting side sends),
     * so it passes whether or not the wait exists. This one is the
     * discriminating case: it sends on the accepting side while the dialler is
     * still silent, where the wait is the ONLY thing that stops the frame going
     * out. Measured 2026-08-30: with the wait disabled the send succeeds and
     * this test fails at [assertFailsWith].
     */
    @Test
    fun `sending on an accepted link before the dialler speaks refuses rather than queueing`() {
        val binary = SidecarBinary.orSkip()

        SidecarProcess.spawn(binary).use { sidecarA ->
            SidecarProcess.spawn(binary).use { sidecarB ->
                sidecarA.connect().use { hostA ->
                    sidecarB.connect().use { hostB ->
                        val inboundLinks = LinkedBlockingQueue<Pair<SidecarLink, RecordingLinkListener>>()
                        hostA.onInboundLink { link ->
                            RecordingLinkListener("A/link${link.id}").also { inboundLinks.put(link to it) }
                        }
                        val addresses = hostA.listen()
                        val listenerB = RecordingLinkListener("B")
                        val linkB = hostB.let {
                            it.addPeer(sidecarA.nodeId, addresses)
                            it.dial(sidecarA.nodeId, listenerB)
                        }

                        val (linkA, listenerA) = inboundLinks.poll(30, TimeUnit.SECONDS)
                            ?: fail("A never saw LINK_UP for the inbound connection")
                        assertEquals(LinkDirection.INBOUND, linkA.direction)

                        // B has sent nothing, so A's side of the stream is not
                        // adopted and its send queue has no consumer.
                        assertFalse(linkA.peerHasSpoken, "the dialler spoke before the test could send")
                        val refused = assertFailsWith<SidecarException>(
                            "send on an unadopted inbound link returned instead of refusing",
                        ) {
                            linkA.send(byteArrayOf(7), awaitPeerFirstFrame = 300.milliseconds)
                        }
                        assertTrue(
                            refused.message!!.contains("has not spoken"),
                            "refused for the wrong reason: ${refused.message}",
                        )

                        // Once the dialler speaks the same send goes through, so
                        // the refusal above was the wait and not a dead link.
                        linkB.send(byteArrayOf(1))
                        assertContentEquals(byteArrayOf(1), listenerA.nextData())
                        assertTrue(linkA.peerHasSpoken)
                        linkA.send(byteArrayOf(7))
                        assertContentEquals(byteArrayOf(7), listenerB.nextData())

                        assertEquals(emptyList(), listenerA.errors.toList())
                        assertEquals(emptyList(), listenerB.errors.toList())
                    }
                }
            }
        }
    }
}
