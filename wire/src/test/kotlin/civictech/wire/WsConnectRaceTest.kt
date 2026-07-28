package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI

/**
 * A two-process startup is an ordinary race: whichever peer's `main()` calls
 * `connect()` may run before the other peer has bound its listener yet. Found
 * via CI's demo:exchange peer log — a refused first attempt used to crash the
 * whole process instead of retrying like a post-connect drop does.
 */
class WsConnectRaceTest {

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `connect retries past a not-yet-bound listener instead of throwing`() {
        val port = ServerSocket(0).use { it.localPort }
        val client = Stack()
        val server = Stack()
        var listener: WsTransport.WsListener? = null
        val starter = Thread {
            Thread.sleep(300) // the listener deliberately lags behind the connect attempt
            listener = WsTransport.listen(port, server.side)
        }.apply { isDaemon = true; start() }

        // near-zero backoff: bounded by retry scheduling, not sleep
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 10L }
        try {
            connection.isOpen shouldBe true
        } finally {
            connection.shutdown()
            starter.join()
            runCatching { listener?.stop(1000) }
        }
    }
}
