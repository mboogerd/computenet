package civictech.demo.shell

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * computenet-dqy.33 acceptance: an **ephemeral** demo endpoint binds the address
 * `localhost` names, so no other process can take the port the demo announced;
 * a **named** port keeps the wildcard.
 *
 * The defect this pins (see [DemoShell]'s companion for the full account): on
 * BSD/macOS a wildcard binding and another process's binding of the same port on
 * a *specific* address coexist, and the specific one wins the name `localhost`,
 * so a client resolving `localhost` reaches the stranger. `TwoJvmConvergenceTest`
 * died of it twice in 20 runs — both peers alive, both having announced their
 * ports, and a foreign JVM holding `127.0.0.1:<peer B's http port>`.
 */
class DemoShellBindTest {

    @Test
    fun `a foreign SO_REUSEADDR bind cannot take an ephemeral endpoint's port, which still answers as localhost`() {
        withEphemeralShell { shell ->
            // The address whose name is worth arguing about: whatever `localhost`
            // resolves to here, which is also what a client's URL resolves to.
            // Asserting a literal 127.0.0.1 would pass vacuously on a host whose
            // hosts file puts ::1 first — a different address, so a bind of it
            // could not overlap ours there whichever address we had chosen.
            val contested = InetAddress.getByName("localhost")
            shouldThrow<IOException> {
                ServerSocket().use { foreign ->
                    // Java's default off Windows, and the flag the JDK's own
                    // HttpServer forces on itself, so a stranger arriving with it
                    // is the realistic case rather than a contrived one.
                    foreign.reuseAddress = true
                    foreign.bind(InetSocketAddress(contested, shell.boundPort))
                }
            }

            get("http://localhost:${shell.boundPort}/probe") shouldBe 200
        }
    }

    @Test
    fun `an ephemeral endpoint is not reachable at this machine's own network address`() {
        // The address is chosen by what the probe can *prove* here, not by
        // interface order: a wildcard-bound control server must actually answer
        // at it, or the refusal below would hold whatever `DemoShell` bound and
        // the green tick would mean nothing.
        //
        // Interface order alone is not enough, measured on this dev macOS box:
        // the first non-loopback IPv4 is a VPN tunnel's `utun40` 198.19.254.2,
        // where an inbound connect to a deliberately WILDCARD-bound server times
        // out 0/20 — while `en0`'s 192.168.2.12, the next candidate, answers
        // 20/20. Taking the first address unconditionally therefore skipped (or,
        // before this control existed, passed vacuously) on a host where the
        // probe discriminates perfectly well one interface further down.
        //
        // Only a host where *no* address answers — an offline or network-isolated
        // container — skips, and it says so. This is the wildcard-vs-loopback
        // half of the pair that is meaningful on Linux too, as the hijack test
        // above is the half that only bites on BSD/macOS.
        val external = firstExternallyReachableIpv4()
        assumeTrue(
            external != null,
            "no interface on this host answers an inbound connect to its own address even for a " +
                "wildcard-bound server, so this probe cannot distinguish a wildcard bind from a loopback one",
        )
        withEphemeralShell { shell ->
            shouldThrow<IOException> {
                Socket().use { it.connect(InetSocketAddress(external, shell.boundPort), TIMEOUT_MS) }
            }
        }
    }

    @Test
    fun `a named port keeps the wildcard, and an explicit bind address is honored as given`() {
        // Structural, deliberately: binding a named port in a test means choosing
        // a number, and a number chosen now and bound later is exactly
        // computenet-dqy.25's race. See DemoShell's companion for why `endpoint`
        // is internal rather than private.
        DemoShell.endpoint(8080, null).address.isAnyLocalAddress shouldBe true
        DemoShell.endpoint(8080, null).port shouldBe 8080

        DemoShell.endpoint(0, null).address shouldBe InetAddress.getByName("localhost")

        val explicit = InetAddress.getLoopbackAddress()
        DemoShell.endpoint(0, explicit).address shouldBe explicit
        DemoShell.endpoint(8080, explicit).address shouldBe explicit
    }

    /** A started ephemeral shell serving `/probe`, stopped again whatever [body] does. */
    private fun withEphemeralShell(body: (DemoShell) -> Unit) {
        val shell = DemoShell(0)
        shell.route("/probe") { it.respond(200, "ok") }
        shell.start()
        try {
            body(shell)
        } finally {
            shell.stop()
        }
    }

    private fun get(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        val code = connection.responseCode
        connection.inputStream.use { it.readAllBytes() }
        connection.disconnect()
        return code
    }

    /**
     * Whether a deliberately **wildcard**-bound server — the shell's pre-fix
     * shape, and what the ephemeral branch must no longer be — can actually be
     * reached at [external] on this host. This is the control that decides
     * whether the external-address probe means anything here.
     */
    private fun wildcardIsReachableAt(external: InetAddress): Boolean {
        val control = HttpServer.create(InetSocketAddress(0), 0)
        control.executor = null
        control.start()
        return try {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(external, control.address.port), TIMEOUT_MS) }
            }.isSuccess
        } finally {
            control.stop(0)
        }
    }

    /**
     * The first of this machine's own non-loopback IPv4 addresses at which a
     * wildcard-bound server can actually be reached from here — i.e. the first
     * address at which the external-address probe discriminates. `null` when no
     * address does, which is the only honest skip.
     *
     * Bounded at [MAX_CANDIDATES] so a host full of filtered tunnel interfaces
     * costs a bounded number of [TIMEOUT_MS] waits rather than one per interface.
     */
    private fun firstExternallyReachableIpv4(): InetAddress? =
        nonLoopbackIpv4().take(MAX_CANDIDATES).firstOrNull { wildcardIsReachableAt(it) }

    private fun nonLoopbackIpv4(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private companion object {
        const val TIMEOUT_MS = 2_000

        /** How many of this host's addresses the control probe is willing to try. */
        const val MAX_CANDIDATES = 4
    }
}
