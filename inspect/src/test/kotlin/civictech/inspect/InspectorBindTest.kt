package civictech.inspect

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * T19 acceptance — the inspector's socket is loopback-only.
 *
 * Neither [InspectorServer] nor `DemoShell` exposes its bind address (only
 * `boundPort`), so this asserts the property the bind actually buys instead of
 * the field: a connect to *this machine's own non-loopback address* on the
 * inspector's port is refused, while the same port answers on loopback. That
 * is the same probe an operator would run by hand, and it fails if the
 * `InetAddress.getLoopbackAddress()` argument at `InspectorServer.kt`'s
 * `DemoShell(...)` construction is ever dropped.
 *
 * The non-loopback half is skipped, not failed, on a machine with no
 * non-loopback IPv4 interface (an offline container); the loopback half still
 * pins that the server is reachable at all.
 */
class InspectorBindTest {

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)

    @Test
    fun `the inspector is reachable on loopback and refuses this machine's own network address`() {
        InspectorServer(registry, setOf(host), port = 0).start().use { server ->
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort), TIMEOUT_MS) }

            val external = firstNonLoopbackIpv4()
            assumeTrue(external != null, "no non-loopback IPv4 interface to probe from")
            shouldThrow<IOException> {
                Socket().use { it.connect(InetSocketAddress(external, server.boundPort), TIMEOUT_MS) }
            }
        }
    }

    private fun firstNonLoopbackIpv4(): InetAddress? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}
