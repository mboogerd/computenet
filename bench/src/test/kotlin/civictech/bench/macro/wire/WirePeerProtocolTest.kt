package civictech.bench.macro.wire

import civictech.bench.macro.SweepPlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WirePeerProtocolTest {
    @Test
    fun `argv round trips a coordinate index and lower peer ports`() {
        val coordinate = SweepPlan.BASELINE.copy(hostCount = 3)
        val peers = linkedMapOf(0 to 51_234, 1 to 51_240)

        val parsed = WirePeerProtocol.parseArgv(
            WirePeerProtocol.argv(coordinate, index = 2, peerPorts = peers).toTypedArray(),
        )

        assertEquals(coordinate, parsed.coordinate)
        assertEquals(2, parsed.index)
        assertEquals(peers, parsed.peerPorts)
    }

    @Test
    fun `ready counts and failed lines round trip`() {
        val counts = PeerCounts(sent = 31, received = 29, enqueued = 29, bytes = 8_192)

        assertEquals(2, WirePeerProtocol.parseReady(WirePeerProtocol.ready(2)))
        assertEquals(
            WirePeerProtocol.IndexedCounts(2, counts),
            WirePeerProtocol.parseCounts(WirePeerProtocol.counts(2, counts)),
        )
        assertEquals(
            WirePeerProtocol.PeerFailure(2, "dead letter: first failure"),
            WirePeerProtocol.parseFailed(WirePeerProtocol.failed(2, "dead letter: first failure")),
        )
        assertEquals("go", WirePeerProtocol.GO)
        assertEquals("read", WirePeerProtocol.READ)
        assertEquals("stop", WirePeerProtocol.STOP)
        assertEquals(1_000, WirePeerProtocol.QUIET_MILLIS)
    }

    @Test
    fun `malformed counts line parses to null`() {
        assertNull(WirePeerProtocol.parseCounts("computenet-counts 2 sent=3 received=nope enqueued=3 bytes=10"))
        assertNull(WirePeerProtocol.parseCounts("computenet-counts 2 sent=3 received=3 bytes=10"))
        assertNull(WirePeerProtocol.parseCounts("noise computenet-counts 2 sent=3 received=3 enqueued=3 bytes=10"))
    }

    @Test
    fun `replication true is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            WirePeerProtocol.argv(
                SweepPlan.BASELINE.copy(hostCount = 2, replication = true),
                index = 1,
                peerPorts = mapOf(0 to 51_234),
            )
        }

        assertTrue(failure.message.orEmpty().contains("replication=true"))
    }
}
