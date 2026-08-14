package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.demo.shell.DemoShell
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID

/**
 * T19 acceptance — the inspector's socket is loopback-only.
 *
 * Neither [InspectorServer] nor `DemoShell` exposes its bind address (only
 * `boundPort`), so this asserts the property the bind actually buys instead of
 * the field: a connect to *this machine's own non-loopback address* on the
 * inspector's port is refused, while the same port answers on loopback. That
 * is the same probe an operator would run by hand, and it fails if
 * `InspectorServer.kt`'s `DemoShell(...)` construction ever binds an address
 * the network can reach.
 *
 * **What it does and does not pin, measured under computenet-dqy.36.** With
 * `InetAddress.getByName("0.0.0.0")` substituted for `getLoopbackAddress()`
 * there, this file fails. *Deleting* the argument outright does not fail it,
 * and that is not a hole in the probe: since computenet-dqy.33
 * `DemoShell.endpoint` binds loopback for an **ephemeral** port on its own, so
 * `DemoShell(0)` is loopback-bound too and there is no observable difference
 * left to catch. What the argument still buys by itself is the **named** port
 * an operator passes as `--inspect-port`, which keeps the wildcard without it —
 * and a test cannot bind a named port without choosing a number now and binding
 * it later, which is computenet-dqy.25's race. So the named-port half stays
 * pinned by `DemoShell.endpoint`'s structural assertions in
 * `DemoShellBindTest`, and this file pins the property over a real socket for
 * the port it can safely bind.
 *
 * The two halves are separate tests deliberately: the external half can be
 * skipped on a host where it proves nothing (see below), and the loopback half
 * has to keep pinning that the server is reachable at all when it is — which a
 * mid-method `assumeTrue` would abort along with it.
 *
 * **computenet-lxq closes the named-port hole the paragraph above records.** The
 * third test below asserts the *decision* rather than a socket, through
 * [InspectorServer.Shells] — so deleting `InspectorServer`'s
 * `getLoopbackAddress()` argument now fails a test here, while still no named
 * port is ever bound.
 */
class InspectorBindTest {

    private val registry = LocationRegistry()

    /**
     * The host's scheduler, owned here rather than left to [ManagedHost]'s own
     * default, purely so [tearDown] can stop it (computenet-4vh) — see
     * `InspectorErrorsTest` for the full rationale.
     */
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)

    @AfterEach
    fun tearDown() {
        hostScheduler.shutdown()
    }

    @Test
    fun `the inspector is reachable on loopback`() {
        InspectorServer(registry, setOf(host), port = 0).start().use { server ->
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort), TIMEOUT_MS) }
        }
    }

    @Test
    fun `the inspector refuses this machine's own network address`() {
        // The address is chosen by what the probe can *prove* here, not by
        // interface order: a wildcard-bound control server must actually answer
        // at it, or the refusal below would hold whatever `InspectorServer`
        // bound and the green tick would mean nothing.
        //
        // Interface order alone is not enough, measured on this dev macOS box
        // (computenet-dqy.36): the first non-loopback IPv4 is a VPN tunnel's
        // `utun40` 198.19.254.2, where an inbound connect to a deliberately
        // WILDCARD-bound server times out 0/20 — while `en0`'s 192.168.2.12,
        // the next candidate, answers 20/20. Taking the first address
        // unconditionally therefore passed vacuously here: the
        // `shouldThrow<IOException>` was satisfied by the tunnel's
        // `SocketTimeoutException`, and the pre-fix probe was measured green
        // against an inspector deliberately bound to `0.0.0.0` — the exact
        // regression this file claims to catch, sailing straight through.
        //
        // Only a host where *no* address answers — an offline or
        // network-isolated container — skips, and it says so. Same selection as
        // `DemoShellBindTest`'s probe (computenet-dqy.33), where it was first
        // proven, and while reviewing which this defect was found.
        val external = firstExternallyReachableIpv4()
        assumeTrue(
            external != null,
            "no interface on this host answers an inbound connect to its own address even for a " +
                "wildcard-bound server, so this probe cannot distinguish a wildcard bind from a loopback one",
        )
        InspectorServer(registry, setOf(host), port = 0).start().use { server ->
            shouldThrow<IOException> {
                Socket().use { it.connect(InetSocketAddress(external, server.boundPort), TIMEOUT_MS) }
            }
        }
    }

    @Test
    fun `a named inspector port is still handed a loopback bind address`() {
        // Structural, deliberately, and the reason this test exists at all
        // (computenet-lxq): the two probes above can only bind port 0, where
        // `DemoShell` binds loopback by itself since computenet-dqy.33, so they
        // cannot see whether the inspector *asked* for loopback. The path that
        // needs the asking is the operator's `--inspect-port 9000`, and binding a
        // named port in a test means choosing a number now and binding it later
        // — computenet-dqy.25's race. So the decision is read off the seam and
        // NOTHING here binds NAMED_PORT: the stand-in shell below is ephemeral.
        //
        // The stand-in is passed to one constructor rather than installed on a
        // shared field, so there is nothing for this test to restore and nothing
        // another test in this JVM can observe (see InspectorServer.Shells).
        val asked = mutableListOf<Pair<Int, InetAddress?>>()
        val recording = object : InspectorServer.Shells {
            override fun open(port: Int, bindAddress: InetAddress?): DemoShell {
                asked += port to bindAddress
                return DemoShell(0)
            }
        }
        InspectorServer(
            registry,
            hosts = mapOf("test-host" to host),
            port = NAMED_PORT,
            shells = recording,
        ).start().use { }

        val (port, bindAddress) = asked.single()
        port shouldBe NAMED_PORT
        // null is what an omitted argument leaves, and for a named port
        // `DemoShell.endpoint` answers that with the wildcard — the exposure T19
        // forbids, and the one this assertion is here to catch.
        bindAddress.shouldNotBeNull()
        bindAddress.isLoopbackAddress shouldBe true
        bindAddress.isAnyLocalAddress shouldBe false
    }

    /**
     * Whether a deliberately **wildcard**-bound server — the inspector's
     * pre-T19 shape, and what its shell must no longer be — can actually be
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

        /**
         * A plausible `--inspect-port`, and never bound by anything here — see
         * the named-port test for why it must not be.
         */
        const val NAMED_PORT = 9000

        /** How many of this host's addresses the control probe is willing to try. */
        const val MAX_CANDIDATES = 4
    }
}
