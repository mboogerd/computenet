package civictech.iroh

import civictech.iroh.SidecarProtocol.Kind
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * `SidecarClient.watchPeers` and its discovery events (DSC2, aas-D8), driven
 * against [FakeSidecar] — no real sidecar binary needed, so these run on the
 * default lane. `SidecarCodecTest` pins the codec side; this pins the client's
 * registration order, thread confinement, and BS-10's malformed-discovery
 * continuation ([DSC2-MDNS-06]).
 */
class SidecarWatchPeersTest {

    private val peerId = ByteArray(NODE_ID_LEN) { (it + 1).toByte() }

    private class RecordingWatchListener : PeerWatchListener {
        val discovered = Collections.synchronizedList(mutableListOf<Pair<ByteArray, List<String>>>())
        val expired = Collections.synchronizedList(mutableListOf<ByteArray>())
        @Volatile var lastThreadName: String? = null

        override fun onDiscovered(nodeId: ByteArray, addresses: List<String>) {
            lastThreadName = Thread.currentThread().name
            discovered.add(nodeId to addresses)
        }

        override fun onExpired(nodeId: ByteArray) {
            lastThreadName = Thread.currentThread().name
            expired.add(nodeId)
        }
    }

    @Test
    fun `watchPeers writes WATCH_PEERS and returns once WATCHING arrives`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingWatchListener()
                val settled = ArrayBlockingQueue<Result<Unit>>(1)
                Thread({ settled.put(runCatching { client.watchPeers(listener) }) }, "watch-peers")
                    .apply { isDaemon = true }
                    .start()

                assertIs<HostMessage.WatchPeers>(fake.nextHostMessage())
                fake.send(SidecarMessage.Watching)

                (settled.poll(30, TimeUnit.SECONDS) ?: fail("watchPeers did not settle within 30s")).getOrThrow()
            }
        }
    }

    @Test
    fun `PEER_DISCOVERED then PEER_EXPIRED are delivered in order on the reader thread`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingWatchListener()
                startWatch(client, fake, listener)

                fake.send(SidecarMessage.PeerDiscovered(peerId, listOf("127.0.0.1:41001")))
                fake.send(SidecarMessage.PeerExpired(peerId))

                await("listener to record both events") { listener.discovered.size == 1 && listener.expired.size == 1 }

                val (id, addrs) = listener.discovered[0]
                assertContentEquals(peerId, id)
                assertEquals(listOf("127.0.0.1:41001"), addrs)
                assertContentEquals(peerId, listener.expired[0])
                assertEquals("iroh-sidecar-reader", listener.lastThreadName)
            }
        }
    }

    @Test
    fun `a malformed PEER_DISCOVERED is counted and the reader keeps dispatching (BS-10, DSC2-MDNS-06)`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingWatchListener()
                startWatch(client, fake, listener)

                // A 5-byte payload is short of NODE_ID_LEN (32) -> MALFORMED_PAYLOAD.
                fake.sendRaw(Frame(Kind.PEER_DISCOVERED, 0L, ByteArray(5)))
                await("malformedDiscoveryEvents to reach 1") { client.malformedDiscoveryEvents == 1L }

                // The reader continued: a later, valid discovery event still lands.
                fake.send(SidecarMessage.PeerExpired(peerId))
                await("the reader to keep dispatching after the malformed frame") { listener.expired.size == 1 }
                assertContentEquals(peerId, listener.expired[0])

                // And a control round-trip still works: the reader is not dead.
                val getIdResult = ArrayBlockingQueue<Result<ByteArray>>(1)
                Thread({ getIdResult.put(runCatching { client.getId() }) }, "get-id").apply { isDaemon = true }.start()
                assertIs<HostMessage.GetId>(fake.nextHostMessage())
                fake.send(SidecarMessage.Id(peerId))
                val id = (getIdResult.poll(30, TimeUnit.SECONDS) ?: fail("GET_ID did not settle within 30s")).getOrThrow()
                assertContentEquals(peerId, id)

                assertEquals(1L, client.malformedDiscoveryEvents, "only the one malformed discovery frame is counted")
            }
        }
    }

    @Test
    fun `a malformed frame of a non-discovery kind still ends the reader, scoped continuation only`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingWatchListener()
                startWatch(client, fake, listener)

                // LINK_UP needs NODE_ID_LEN + 1 bytes; 3 bytes on a non-zero link is
                // malformed for a kind OUTSIDE the discovery pair, so the reader must
                // still end as before this task widened nothing else.
                fake.sendRaw(Frame(Kind.LINK_UP, 2L, ByteArray(3)))

                await("the reader to stop after a non-discovery malformed frame") {
                    runCatching { client.getId(timeout = 200.milliseconds) }.isFailure
                }
                assertFailsWith<SidecarException> { client.getId(timeout = 200.milliseconds) }
                assertEquals(0L, client.malformedDiscoveryEvents, "the non-discovery malformed frame is not counted here")
            }
        }
    }

    /** Register [listener] via `watchPeers` and drain the WATCH_PEERS/WATCHING handshake. */
    private fun startWatch(client: SidecarClient, fake: FakeSidecar, listener: PeerWatchListener) {
        val settled = ArrayBlockingQueue<Result<Unit>>(1)
        Thread({ settled.put(runCatching { client.watchPeers(listener) }) }, "watch-peers")
            .apply { isDaemon = true }
            .start()
        assertIs<HostMessage.WatchPeers>(fake.nextHostMessage())
        fake.send(SidecarMessage.Watching)
        (settled.poll(30, TimeUnit.SECONDS) ?: fail("watchPeers did not settle within 30s")).getOrThrow()
    }
}
