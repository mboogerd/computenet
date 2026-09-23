package civictech.iroh

import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BS-01's JVM half (feature `computenet-ne2oh`, DSC2, [DSC2-REC-02]): two
 * `--offline --mdns` sidecars find each other over real mDNS on the local
 * segment, and a **bare** `dial` — no `addPeer` anywhere in this test — comes
 * up, because the sidecar fed the discovered address into its own lookup
 * before it announced the peer (`PROTOCOL.md` §3, `PEER_DISCOVERED`).
 *
 * `iroh/sidecar/tests/mdns.rs`'s
 * `two_mdns_sidecars_discover_each_other_and_dial_without_add_peer` is this
 * test's Rust twin; the shape here mirrors it end to end through
 * [SidecarClient], including the skip discipline: a discovery timeout is a
 * SKIP only when the sidecar's own stderr said mDNS could not bind
 * ([MulticastGate.reasonFromStderr]), and a hard FAIL otherwise
 * (`[DSC2-NV-01]`) — an unexplained timeout must never read as a quiet pass.
 *
 * Both sidecars run `--offline`, so nothing else leaves the host: this proves
 * flag wiring, the discovery event path and the bare dial on one host,
 * nothing about a real LAN (`[DSC2-NV-01]`).
 *
 * Skip-gated twice: [SidecarBinary.orSkip] (no `-Piroh.enabled=true`, so this
 * reports SKIPPED on the default lane) and [MulticastGate.deliveryOrSkip]
 * (this host does not deliver multicast — true on MacBoo, `ne2oh-B6`).
 * Executed evidence for this test's body comes from CI's `iroh-sidecar` lane.
 */
class SidecarMdnsDiscoveryTest {

    private class RecordingPeerWatchListener : PeerWatchListener {
        private val discoveredList = Collections.synchronizedList(mutableListOf<Pair<ByteArray, List<String>>>())
        private val expiredList = Collections.synchronizedList(mutableListOf<ByteArray>())

        override fun onDiscovered(nodeId: ByteArray, addresses: List<String>) {
            discoveredList.add(nodeId to addresses)
        }

        override fun onExpired(nodeId: ByteArray) {
            expiredList.add(nodeId)
        }

        /** A point-in-time snapshot, safe to iterate after the caller stops polling. */
        fun discovered(): List<Pair<ByteArray, List<String>>> = synchronized(discoveredList) { discoveredList.toList() }
    }

    @Test
    fun `two --offline --mdns sidecars discover each other and a bare dial comes up`() {
        val binary = SidecarBinary.orSkip()
        MulticastGate.deliveryOrSkip()

        val stderrA = CopyOnWriteArrayList<String>()
        val stderrB = CopyOnWriteArrayList<String>()

        SidecarProcess.spawn(binary, stderrSink = stderrA::add, args = listOf("--offline", "--mdns")).use { sidecarA ->
            SidecarProcess.spawn(binary, stderrSink = stderrB::add, args = listOf("--offline", "--mdns")).use { sidecarB ->
                sidecarA.connect().use { hostA ->
                    sidecarB.connect().use { hostB ->
                        val inboundOnA = LinkedBlockingQueue<Pair<SidecarLink, RecordingLinkListener>>()
                        hostA.onInboundLink { link ->
                            RecordingLinkListener("A/link${link.id}").also { inboundOnA.put(link to it) }
                        }

                        // A accepts; NO addPeer is ever sent by either side in this
                        // test. B's later bare dial can resolve only through what
                        // PEER_DISCOVERED already fed the sidecar's own lookup.
                        val aAddresses = hostA.listen()
                        assertTrue(aAddresses.isNotEmpty(), "A's LISTENING carried no addresses")

                        val listenerA = RecordingPeerWatchListener()
                        val listenerB = RecordingPeerWatchListener()
                        hostB.watchPeers(listenerB)
                        hostA.watchPeers(listenerA)

                        val discoveredOnB = awaitDiscoveryOrSkip(
                            listener = listenerB,
                            expectedId = sidecarA.nodeId,
                            stderrLines = stderrA + stderrB,
                            what = "B never read a PEER_DISCOVERED naming A",
                        )
                        assertTrue(
                            discoveredOnB.second.any { it in aAddresses },
                            "B's discovered addresses ${discoveredOnB.second} should carry one of A's bound sockets $aAddresses",
                        )

                        val discoveredOnA = awaitDiscoveryOrSkip(
                            listener = listenerA,
                            expectedId = sidecarB.nodeId,
                            stderrLines = stderrA + stderrB,
                            what = "A never read a PEER_DISCOVERED naming B",
                        )
                        assertTrue(discoveredOnA.second.isNotEmpty(), "A's discovered addresses for B should not be empty")

                        // Neither sidecar ever announced ITSELF.
                        assertFalse(
                            listenerA.discovered().any { it.first.contentEquals(sidecarA.nodeId) },
                            "A announced its own id",
                        )
                        assertFalse(
                            listenerB.discovered().any { it.first.contentEquals(sidecarB.nodeId) },
                            "B announced its own id",
                        )

                        // [DSC2-REC-02]: every PEER_DISCOVERED payload is a 32-byte
                        // id plus host:port strings only -- no key material, no
                        // statement bytes.
                        (listenerA.discovered() + listenerB.discovered()).forEach { (id, addresses) ->
                            assertEquals(NODE_ID_LEN, id.size, "a discovered id is not $NODE_ID_LEN bytes")
                            addresses.forEach { address ->
                                val parts = address.split(":")
                                assertTrue(parts.size >= 2, "'$address' does not parse as host:port")
                                assertTrue(
                                    parts.last().toIntOrNull() in 1..65535,
                                    "'$address' does not carry a valid port",
                                )
                            }
                        }

                        // The claim: B sends no ADD_PEER anywhere, and the bare
                        // dial resolves anyway.
                        val listenerLinkB = RecordingLinkListener("B")
                        val linkB = hostB.dial(sidecarA.nodeId, listenerLinkB)
                        assertEquals(LinkDirection.OUTBOUND, linkB.direction)
                        assertContentEquals(sidecarA.nodeId, linkB.remoteNodeId)

                        val (linkA, listenerLinkA) = inboundOnA.poll(30, TimeUnit.SECONDS)
                            ?: fail("A never saw an inbound link from B's bare dial")
                        assertEquals(LinkDirection.INBOUND, linkA.direction)
                        assertContentEquals(sidecarB.nodeId, linkA.remoteNodeId)

                        // A frame flows each way, to show the link is real and not
                        // merely reported up.
                        linkB.send(byteArrayOf(9))
                        assertContentEquals(byteArrayOf(9), listenerLinkA.nextData())
                        assertTrue(linkA.peerHasSpoken)
                        linkA.send(byteArrayOf(4))
                        assertContentEquals(byteArrayOf(4), listenerLinkB.nextData())

                        linkB.close()
                        listenerLinkB.nextDown()
                        listenerLinkA.nextDown()
                    }
                }
            }
        }
    }

    /**
     * Poll [listener] for a discovery naming [expectedId] within 30 s.
     *
     * On timeout: a stderr line among [stderrLines] carrying the sidecar's own
     * `mdns unavailable` report ([MulticastGate.reasonFromStderr]) turns the
     * timeout into a named SKIP -- the sidecar itself said nothing would ever
     * arrive. Otherwise the timeout is a hard FAIL naming [what]: an
     * unexplained timeout is never allowed to read as a pass
     * (`[DSC2-NV-01]`).
     */
    private fun awaitDiscoveryOrSkip(
        listener: RecordingPeerWatchListener,
        expectedId: ByteArray,
        stderrLines: List<String>,
        what: String,
    ): Pair<ByteArray, List<String>> {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val match = listener.discovered().firstOrNull { it.first.contentEquals(expectedId) }
            if (match != null) return match
            if (System.currentTimeMillis() >= deadline) {
                val reason = MulticastGate.reasonFromStderr(stderrLines)
                if (reason != null) {
                    assumeTrue(false) { reason }
                }
                fail("$what within 30 s and the sidecar reported no mDNS problem")
            }
            Thread.sleep(50)
        }
    }
}
