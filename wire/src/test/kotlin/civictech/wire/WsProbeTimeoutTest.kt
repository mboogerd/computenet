package civictech.wire

import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.channels.ServerSocketChannel

/**
 * computenet-auq: [WsTransport.awaitReachable]'s probe must cost one *backoff
 * interval* against an address that drops SYNs, not one *OS connect timeout*.
 *
 * The untimed `Socket(host, port)` this replaced did not merely delay the
 * schedule, it bypassed it: every attempt cost whatever the OS decided, so an
 * injected schedule became irrelevant. Measured on the unfixed code, macOS 15 /
 * JDK 21, against the fixture below: **7788 / 7857 / 7858 ms** per probe, and
 * 8802ms for this test's own first probe. On a real network the same dial
 * reaches 75s+.
 *
 * **The black hole is a full accept queue**, not an unbound port. A port
 * nothing is bound to *refuses* — an immediate RST, sub-millisecond — which is
 * the only case the rest of `:wire` exercises and the case this change must
 * leave untouched — the second test below. To drop SYNs
 * instead, bind a listening socket with a backlog of 1 and never accept from
 * it: once the queue is full the kernel discards further SYNs, so the dialer
 * gets no answer at all and waits out its own timeout.
 *
 * That the fixture really is black-holing is *checked* rather than assumed
 * ([fillUntilBlackHoled]) — a fixture that quietly went back to answering would
 * leave the timing assertion trivially satisfied and this test covering
 * nothing. Filling the queue is a kernel-behaviour bet (macOS discards the
 * excess SYN; Linux discards it once more than one request is young), so a
 * platform that declines to black-hole at all is reported as a skip rather than
 * asserting a vacuous bound.
 *
 * The bound is deliberately loose. It has to separate ~1s intended from ~7.8s
 * observed, so there is room for a very slow shared runner without ever
 * admitting the defect. Nothing here asserts a *lower* bound: how close to its
 * timeout a dial lands is the OS's business.
 */
class WsProbeTimeoutTest {

    /**
     * The probe timeout the injected schedule produces.
     *
     * [measureOneProbe] injects `{ 0L }` — the schedule every reconnect test in
     * `:wire` already uses — for two reasons. It is the hazard the floor exists
     * for: `Socket.connect(addr, 0)` means *no timeout*, so a schedule read
     * verbatim would reproduce the very defect. And a zero interval means zero
     * sleep between attempts, which is what lets the two lambda calls in
     * [measureOneProbe] bracket a probe and nothing else.
     */
    private val timeout = WsTransport.MIN_PROBE_TIMEOUT_MS

    /**
     * Generous multiple of [timeout] a black-holed probe must stay under: 5x the
     * intended cost, and still comfortably under the 7.8s the untimed dial
     * measured, so a loaded 4-core runner cannot push a correct probe over it
     * and a regression cannot duck under it.
     */
    private val bound = timeout * 5

    @Test
    fun `a probe against a black hole costs a backoff interval, not the OS connect timeout`() {
        blackHole { uri ->
            val probeMs = measureOneProbe(uri)
            withClue(
                "a probe of a SYN-dropping address took ${probeMs}ms against a ${timeout}ms probe timeout. " +
                    "The untimed Socket(host, port) this replaced measured ~7800ms here (computenet-auq): " +
                    "the connect timeout is not being derived from the backoff schedule.",
            ) { probeMs shouldBeLessThan bound }
        }
    }

    /**
     * The case every current caller depends on, asserted so the new timeout
     * cannot regress it: a *refused* port still returns at once. The timeout is
     * an upper bound the RST path never reaches, so this stays an order of
     * magnitude below it.
     */
    @Test
    fun `a refused probe still returns on the RST`() {
        val port = abandonedPort()
        val probeMs = measureOneProbe(URI("ws://localhost:$port"))
        withClue(
            "a refused probe took ${probeMs}ms; it must return on the RST, well inside the " +
                "${timeout}ms connect timeout, exactly as the untimed dial did",
        ) { probeMs shouldBeLessThan timeout }
    }

    /**
     * The cost of a single failing probe, in milliseconds.
     *
     * The schedule lambda is the clock: `awaitReachable` calls it once per
     * attempt, immediately *before* that attempt's dial, and the interval it
     * returns is also what the loop sleeps on failure. Returning **0** therefore
     * makes two consecutive calls bracket exactly one probe and no sleep, which
     * is what makes this a measurement of the dial rather than of
     * dial-plus-backoff — an earlier draft returned a real interval and measured
     * a refused probe at 1010ms, all of it sleep. Throwing on the second call is
     * how we leave a loop that is unbounded by design.
     */
    private fun measureOneProbe(uri: URI): Long {
        var start = 0L
        var end = 0L
        try {
            WsTransport.awaitReachable(uri) { attempt ->
                if (attempt == 0) start = System.nanoTime() else end = System.nanoTime()
                if (attempt >= 1) throw ProbeObserved()
                0L
            }
            error("awaitReachable returned: $uri answered, so nothing was measured")
        } catch (_: ProbeObserved) {
            // expected: the retry loop never terminates on its own
        }
        return (end - start) / 1_000_000
    }

    private class ProbeObserved : RuntimeException()

    /** A port bound once and released, so nothing is listening and a dial is refused. */
    private fun abandonedPort(): Int =
        ServerSocketChannel.open().use { channel ->
            channel.bind(WsTransport.loopback(0))
            (channel.localAddress as InetSocketAddress).port
        }

    /**
     * Bind a loopback socket with a backlog of 1, fill its accept queue until a
     * dial gets no answer, and run [block] against the resulting black hole.
     * Every socket opened here is closed on the way out, fillers included —
     * leaking them would leave half-open connections on the runner.
     */
    private fun blackHole(block: (URI) -> Unit) {
        val fillers = mutableListOf<Socket>()
        ServerSocketChannel.open().use { channel ->
            channel.bind(WsTransport.loopback(0), 1)
            val port = (channel.localAddress as InetSocketAddress).port
            try {
                assumeTrue(
                    fillUntilBlackHoled(port, fillers),
                    "this platform kept answering a never-accepted socket with a full backlog, so no " +
                        "SYN-dropping address could be built here; the computenet-auq bound is " +
                        "unverified on this host rather than satisfied",
                )
                block(URI("ws://localhost:$port"))
            } finally {
                fillers.forEach { runCatching { it.close() } }
            }
        }
    }

    /**
     * Dial [port] until one dial times out — proof the queue is full and further
     * SYNs are being discarded rather than answered or refused. Connections that
     * do get through are kept in [fillers]; closing one would free a queue slot.
     *
     * The confirming dial uses a *short* timeout deliberately: it only has to
     * establish "no answer", and paying [timeout] per attempt would make a
     * platform that never black-holes expensive to rule out.
     */
    private fun fillUntilBlackHoled(port: Int, fillers: MutableList<Socket>): Boolean {
        val address = InetSocketAddress(InetAddress.getByName("localhost"), port)
        repeat(MAX_FILL_ATTEMPTS) {
            val socket = Socket()
            try {
                socket.connect(address, FILL_PROBE_TIMEOUT_MS)
                fillers += socket
            } catch (_: SocketTimeoutException) {
                runCatching { socket.close() }
                return true // no answer at all: SYNs are being dropped
            } catch (_: IOException) {
                runCatching { socket.close() }
                return false // refused or otherwise answered; this is not a black hole
            }
        }
        return false
    }

    private companion object {
        const val MAX_FILL_ATTEMPTS = 32
        const val FILL_PROBE_TIMEOUT_MS = 300
    }
}
