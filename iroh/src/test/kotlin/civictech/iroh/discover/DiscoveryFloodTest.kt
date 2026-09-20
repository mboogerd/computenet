package civictech.iroh.discover

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.iroh.FakeSidecar
import civictech.iroh.HostMessage
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.SidecarClient
import civictech.iroh.SidecarMessage
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * BS-07, [DSC2-MDNS-05], [DSC2-NEU-04]: ten thousand discovery events arrive
 * and the node stays bounded — in entries, in dials, and in whose threads run
 * the policy (DSC2 feature `computenet-ktn1l`, task `.3`).
 *
 * This is the adversarial shape of the LAN: `PEER_DISCOVERED` is unsolicited
 * input that costs a sender nothing, so every bound it can push against has to
 * be a number rather than an assumption. The bounds under test are
 * `maxRetained` (the table), `maxInFlightDials` (the dial pool, and therefore
 * the `DIAL` frames on the wire) and the thread ownership.
 *
 * **No key here is ever fingerprinted.** The ids are 32 distinct bytes each,
 * not Ed25519 public keys, and that is sound because no dial is ever answered:
 * a key reaches `civictech.identity` only at a hello, and no link comes up.
 * Ten thousand real keypairs would make this test a benchmark of Ed25519.
 */
class DiscoveryFloodTest {

    private class FixedSidecar(override val nodeId: ByteArray) : IrohTransport.Sidecar {
        override fun close() = Unit
    }

    private fun side(): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = PeerId("node"))
    }

    private fun <T> settle(what: String, call: () -> T): () -> T {
        val done = ArrayBlockingQueue<Result<T>>(1)
        Thread({ done.put(runCatching { call() }) }, what).apply { isDaemon = true }.start()
        return { (done.poll(60, TimeUnit.SECONDS) ?: fail("$what did not settle within 60s")).getOrThrow() }
    }

    /** A distinct, valid-length endpoint id for [index]. Distinct bytes are all this test needs; see the class KDoc. */
    private fun floodId(index: Int): ByteArray = ByteArray(NODE_ID_LEN).also {
        it[0] = (index and 0xFF).toByte()
        it[1] = ((index shr 8) and 0xFF).toByte()
        it[2] = ((index shr 16) and 0xFF).toByte()
        // A tail that can never collide with the node's own id below.
        it[NODE_ID_LEN - 1] = 0x5A
    }

    @Test
    fun `ten thousand sightings stay within maxRetained, maxInFlightDials and this package's own threads`() {
        val policy = DialPolicy(maxRetained = 64, maxInFlightDials = 3, schedule = { 60_000L })
        val own = ByteArray(NODE_ID_LEN) { 0x11 }
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val node = IrohNode(FixedSidecar(own), client, side())
                val started = settle("node start") { node.start(30.seconds) }
                assertEquals(HostMessage.Listen, fake.nextHostMessage())
                fake.send(SidecarMessage.Listening(listOf("127.0.0.1:1")))
                started()

                val starting = settle("peering start") {
                    DiscoveredPeering.start(node, policy, clock = { 0L }, timer = ManualTimer { 0L })
                }
                assertEquals(HostMessage.WatchPeers, fake.nextHostMessage())
                fake.send(SidecarMessage.Watching)
                val peering = starting()

                peering.use {
                    repeat(FLOOD) { index ->
                        fake.send(SidecarMessage.PeerDiscovered(floodId(index), listOf("127.0.0.1:${index % 60_000}")))
                    }

                    await("every sighting to be counted", timeoutMs = 120_000) {
                        peering.counters.eventsReceived.count == FLOOD.toLong()
                    }

                    val snapshot = peering.snapshot()
                    assertTrue(snapshot.size <= 64, "retained ${snapshot.size} entries, bound is 64")
                    assertTrue(
                        snapshot.count { it.state.startsWith("Dialling") } <= 3,
                        "dialling ${snapshot.count { it.state.startsWith("Dialling") }} keys, bound is 3",
                    )
                    val dials = quiesced { fake.dials.get() }
                    assertTrue(dials <= 3, "sent $dials DIALs, bound is 3 — none is answered, so none can complete")

                    // Every key that did not end up retained or in flight was
                    // evicted, and each is counted once.
                    assertTrue(
                        peering.counters.evicted.count >= FLOOD - 64L - 3L,
                        "evicted ${peering.counters.evicted.count}, expected at least ${FLOOD - 64 - 3}",
                    )
                    assertEquals(0L, peering.counters.selfDropped.count, "no flood id is this node's own")

                    // [DSC2-NEU-04]: still only this package's threads, under load.
                    val offenders = Thread.getAllStackTraces()
                        .filterValues { frames -> frames.any { it.className.startsWith("civictech.iroh.discover") } }
                        .keys
                        .map { it.name }
                        .filterNot { it == "iroh-discover-policy" || it.startsWith("iroh-discover-dial-") }
                        .filterNot { it == Thread.currentThread().name }
                    assertEquals(emptyList(), offenders, "policy code ran on a thread this package does not own")
                }
            }
        }
    }

    private companion object {
        const val FLOOD = 10_000
    }
}
