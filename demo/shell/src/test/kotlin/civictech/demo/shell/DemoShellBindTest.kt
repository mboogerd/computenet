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
        val external = firstNonLoopbackIpv4()
        assumeTrue(external != null, "no non-loopback IPv4 interface to probe from")
        // Added in review: prove the probe can tell the two binds apart HERE
        // before trusting its refusal. On a host that refuses an inbound connect
        // to its own address for reasons of its own — this dev macOS box does,
        // measured 0/20 reachable with a deliberately WILDCARD-bound server —
        // the assertion below holds no matter what `DemoShell` bound, so a green
        // tick there would mean nothing. An honest skip, naming why, is worth
        // more than an assertion that cannot fail. On GitHub's ubuntu runner the
        // control server does answer at `external`, so this stays a real
        // assertion exactly where it discriminates. This is the Linux-meaningful
        // half of the pair, as the hijack test above is the macOS-meaningful one.
        assumeTrue(
            wildcardIsReachableAt(external!!),
            "this host refuses inbound connects to its own $external even for a wildcard-bound server, " +
                "so this probe cannot distinguish a wildcard bind from a loopback one",
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

    private fun firstNonLoopbackIpv4(): InetAddress? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}
