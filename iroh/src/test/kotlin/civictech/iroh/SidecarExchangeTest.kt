package civictech.iroh

import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * `PROTOCOL.md` §4's complete exchange, driven end to end through
 * [SidecarClient] against two real sidecar processes: A accepts, B dials.
 *
 * Skip-gated: without `-Piroh.enabled=true` this reports SKIPPED, never failed —
 * see [SidecarBinary]. The exception is the last section, an abandoned `DIAL`
 * (computenet-r2zhu, computenet-r3301): its answers have to arrive at moments
 * a real sidecar cannot be made to pick, so those tests drive a [FakeSidecar]
 * and always run.
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
     * queueing against an unadopted stream.
     *
     * That wait is `PROTOCOL.md`'s **avoidance** path, and avoidance is what a
     * host is advised to do rather than all it may do: §2's Backpressure section
     * now also states the recovery rule for a refusal that happens anyway — an
     * `ERROR` on an established link is terminal for that link and the host
     * closes it (`computenet-ey4v`, settled; pinned by [SidecarBackpressureTest]).
     * The two are complementary, and this test is about the first: a host that
     * waits here never reaches the second.
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

    // ------------------------------------ an abandoned DIAL (r2zhu, r3301)

    /** A listener that only counts: an abandoned dial's link must reach no listener at all. */
    private class CountingListener : LinkListener {
        val events = AtomicInteger()
        override fun onData(link: SidecarLink, payload: ByteArray) { events.incrementAndGet() }
        override fun onDown(link: SidecarLink, reason: String) { events.incrementAndGet() }
        override fun onError(link: SidecarLink, reason: String) { events.incrementAndGet() }
    }

    private fun dialOnThread(
        client: SidecarClient,
        timeoutMs: Long = 30_000,
        listener: LinkListener = CountingListener(),
    ): ArrayBlockingQueue<Result<SidecarLink>> {
        val outcome = ArrayBlockingQueue<Result<SidecarLink>>(1)
        Thread({
            outcome.put(runCatching { client.dial(ByteArray(NODE_ID_LEN) { 7 }, listener, timeoutMs.milliseconds) })
        }, "dial").apply { isDaemon = true }.start()
        return outcome
    }

    /**
     * computenet-r2zhu. `onLinkUp` registers the link and counts the latch
     * down; an interrupt that reaches the dialling thread before it leaves
     * `CountDownLatch.await` still makes `await` throw, because it checks the
     * interrupt flag before the count. The link is then up at the sidecar and
     * registered here, and no caller holds it. The client must close it.
     *
     * The interrupt is placed by [SidecarClient.beforeDialAwait], and only
     * after the reader has finished `onLinkUp`: the fake sends a
     * `PEER_DISCOVERED` right behind the `LINK_UP`, the reader dispatches in
     * order, so its arrival at the watch listener says the settlement is done.
     */
    @Test
    fun `a dial interrupted after its LINK_UP settled closes the link before it throws`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val settled = CountDownLatch(1)
                val watching = Thread {
                    client.watchPeers(object : PeerWatchListener {
                        override fun onDiscovered(nodeId: ByteArray, addresses: List<String>) = settled.countDown()
                        override fun onExpired(nodeId: ByteArray) = Unit
                    })
                }.apply { start() }
                assertEquals(HostMessage.WatchPeers, fake.nextHostMessage())
                fake.send(SidecarMessage.Watching)
                watching.join(30_000)

                client.beforeDialAwait = { _ ->
                    check(settled.await(30, TimeUnit.SECONDS)) { "the reader never finished onLinkUp" }
                    Thread.currentThread().interrupt()
                }
                val outcome = dialOnThread(client)
                val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                fake.send(SidecarMessage.LinkUp(dial.link, dial.peerId, DIRECTION_OUTBOUND))
                fake.send(SidecarMessage.PeerDiscovered(ByteArray(NODE_ID_LEN) { 1 }, listOf("127.0.0.1:1")))

                val result = outcome.poll(30, TimeUnit.SECONDS) ?: fail("dial did not return within 30s")
                assertIs<InterruptedException>(result.exceptionOrNull(), "the interrupted dial throws, as before: $result")
                assertEquals(
                    HostMessage.CloseLink(dial.link),
                    fake.pollHostMessage(5_000),
                    "the abandoned dial's settled link is closed (CLOSE_LINK) — nobody else can close it",
                )
                assertNull(client.link(dial.link), "and no longer registered: no caller holds it")
                assertEquals(emptyList(), client.openLinks, "no link left registered at all")
            }
        }
    }

    /**
     * computenet-c45fr. The window the test above leaves open: the reader has
     * settled the dial, and before the dialling thread gets to abandon it the
     * reader dispatches a `LINK_DOWN` for the link. That frame must not reach
     * the abandoned dial's listener — in `IrohConnection.openLink` it would
     * `retire` a Session the connection never installed.
     *
     * The dialling thread is held in [SidecarClient.beforeDialAwait] until the
     * reader has settled the dial (first marker), the fake then sends the
     * `LINK_DOWN` and a second marker behind it. Without the gate the reader
     * delivers the `LINK_DOWN` and the second marker arrives at once; with
     * it the reader holds the frame until the dial decides, so the second
     * marker only arrives after the interrupt — the hook's bounded wait for
     * it is what lets both codes reach the assertion.
     *
     * Limit: the discrimination rests on that 1 s bound. If the ungated reader
     * took longer than 1 s to dispatch two already-buffered frames, the dial
     * would abandon first and this test would pass without the gate. It never
     * fails spuriously in the other direction; it costs the gated run 1 s.
     */
    @Test
    fun `a LINK_DOWN dispatched between a dial's settlement and its abandonment reaches no listener`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val markers = LinkedBlockingQueue<Byte>()
                val watching = Thread {
                    client.watchPeers(object : PeerWatchListener {
                        override fun onDiscovered(nodeId: ByteArray, addresses: List<String>) = markers.put(nodeId[0])
                        override fun onExpired(nodeId: ByteArray) = Unit
                    })
                }.apply { start() }
                assertEquals(HostMessage.WatchPeers, fake.nextHostMessage())
                fake.send(SidecarMessage.Watching)
                watching.join(30_000)

                val downSent = CountDownLatch(1)
                client.beforeDialAwait = { _ ->
                    check(markers.poll(30, TimeUnit.SECONDS) == 1.toByte()) { "the reader never finished onLinkUp" }
                    check(downSent.await(30, TimeUnit.SECONDS)) { "the test never sent the LINK_DOWN" }
                    // Gated, the reader is holding the LINK_DOWN and this marker behind it.
                    markers.poll(1, TimeUnit.SECONDS)
                    Thread.currentThread().interrupt()
                }
                val listener = CountingListener()
                val outcome = dialOnThread(client, listener = listener)
                val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                fake.send(SidecarMessage.LinkUp(dial.link, dial.peerId, DIRECTION_OUTBOUND))
                fake.send(SidecarMessage.PeerDiscovered(ByteArray(NODE_ID_LEN) { 1 }, listOf("127.0.0.1:1")))
                fake.send(SidecarMessage.LinkDown(dial.link, "peer went away"))
                fake.send(SidecarMessage.PeerDiscovered(ByteArray(NODE_ID_LEN) { 2 }, listOf("127.0.0.1:1")))
                downSent.countDown()

                val result = outcome.poll(30, TimeUnit.SECONDS) ?: fail("dial did not return within 30s")
                assertIs<InterruptedException>(result.exceptionOrNull(), "the interrupted dial throws, as before: $result")
                // The reader has dispatched the LINK_DOWN once the marker behind it is in (or was taken by the hook).
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (client.link(dial.link) != null && System.nanoTime() < deadline) Thread.sleep(1)
                markers.poll(1, TimeUnit.SECONDS)
                assertEquals(0, listener.events.get(), "the abandoned dial's listener saw the LINK_DOWN")
                assertEquals(HostMessage.CloseLink(dial.link), fake.pollHostMessage(5_000))
                assertEquals(emptyList(), client.openLinks, "no link left registered at all")
            }
        }
    }

    /**
     * computenet-r3301. A `DIAL` that timed out has already gone out; a
     * `LINK_UP` for it that arrives later finds no pending dial. It is the
     * host's own abandoned dial (direction OUTBOUND), not an accepted link,
     * so the client closes it and the inbound handler never sees it.
     */
    @Test
    fun `a LINK_UP answering a timed-out DIAL is closed, not handed to the inbound handler`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val accepted = LinkedBlockingQueue<SidecarLink>()
                client.onInboundLink { link -> accepted.put(link); CountingListener() }

                val outcome = dialOnThread(client, timeoutMs = 200)
                val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                val result = outcome.poll(30, TimeUnit.SECONDS) ?: fail("dial did not time out within 30s")
                assertIs<SidecarException>(result.exceptionOrNull(), "the unanswered dial times out: $result")

                fake.send(SidecarMessage.LinkUp(dial.link, dial.peerId, DIRECTION_OUTBOUND))
                assertEquals(
                    HostMessage.CloseLink(dial.link),
                    fake.pollHostMessage(5_000),
                    "the late answer to the abandoned DIAL is closed",
                )
                assertNull(accepted.poll(300, TimeUnit.MILLISECONDS), "the inbound handler never saw it")
                assertEquals(emptyList(), client.openLinks, "and nothing is left registered")
            }
        }
    }

    /**
     * computenet-r2zhu, every ordering. The two tests above each place one
     * moment; this one races the `LINK_UP` against the dial's abandonment
     * (a 3 ms timeout on even iterations, an interrupt on odd ones) at
     * seeded-random offsets, so the reader also meets a dial that has
     * already marked itself abandoned but not yet dropped its pending entry
     * — the arm where the reader loses the compare-and-set and must close
     * the link itself, which no single-moment test can place.
     *
     * Whatever the ordering, the invariant is one: the dial's link is closed
     * exactly once (by the caller if the dial returned it, by the client if
     * not) and nothing stays registered. The assertion holds for every
     * interleaving, so the randomness can make it catch a defect, never fail
     * a correct client.
     */
    @Test
    fun `an abandoned DIAL racing its LINK_UP never leaves a registered link, in any ordering`() {
        val random = Random(42)
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                repeat(1000) { i ->
                    val interrupt = i % 2 == 1
                    val outcome = ArrayBlockingQueue<Result<SidecarLink>>(1)
                    val dialling = Thread({
                        // offer, not put: an interrupt that lands after dial returned would make put throw.
                        outcome.offer(
                            runCatching {
                                client.dial(ByteArray(NODE_ID_LEN) { 7 }, CountingListener(), (if (interrupt) 30_000L else 3L).milliseconds)
                            },
                        )
                    }, "dial").apply { isDaemon = true; start() }
                    val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                    if (interrupt) {
                        val answering = Thread { fake.send(SidecarMessage.LinkUp(dial.link, dial.peerId, DIRECTION_OUTBOUND)) }
                        answering.start()
                        spin(random.nextLong(0, 400_000))
                        dialling.interrupt()
                        answering.join()
                    } else {
                        spin(random.nextLong(1_500_000, 4_500_000))
                        fake.send(SidecarMessage.LinkUp(dial.link, dial.peerId, DIRECTION_OUTBOUND))
                    }
                    val result = outcome.poll(30, TimeUnit.SECONDS) ?: fail("iteration $i: dial did not return within 30s")
                    result.getOrNull()?.close()
                    assertEquals(
                        HostMessage.CloseLink(dial.link),
                        fake.pollHostMessage(5_000),
                        "iteration $i ($result): the link is closed, by the caller or by the client",
                    )
                    fake.send(SidecarMessage.LinkDown(dial.link, "closed"))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (client.link(dial.link) != null && System.nanoTime() < deadline) Thread.sleep(1)
                    assertNull(client.link(dial.link), "iteration $i ($result): nothing left registered")
                }
            }
        }
    }

    private fun spin(nanos: Long) {
        val end = System.nanoTime() + nanos
        while (System.nanoTime() < end) Thread.onSpinWait()
    }
}
