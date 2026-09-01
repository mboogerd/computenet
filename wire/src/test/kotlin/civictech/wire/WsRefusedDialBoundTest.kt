package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI

/**
 * computenet-4gzr: a dialler refused at the listener's allowlist must stop
 * re-dialling, because nothing on the wire ever tells it that it was refused.
 *
 * ## The defect this pins
 *
 * A refusal is not a failed dial. The TCP connect succeeds, the WebSocket
 * upgrade succeeds, `onOpen` fires and the hello goes out — and only *then*
 * does the listener refuse it and close. `WsConnection.onClose` cannot see the
 * difference, so it called `scheduleReconnect()` unconditionally, and because
 * the re-dial loop terminates the moment `reconnectBlocking()` returns true,
 * every refusal cycle started a **fresh** loop at `attempt = 0`. The backoff
 * therefore never escalated: a refused peer re-dialled at a fixed
 * `backoff(0)` — one second on [WsTransport.DEFAULT_RECONNECT_BACKOFF] —
 * forever, charging the refusing listener one accept plus one hello parse
 * every second, from a peer it had already decided it would not talk to.
 *
 * ## What is deliberately NOT this test
 *
 * - [WsReconnectLoopBoundTest] (computenet-8ru) bounds the number of
 *   concurrent retry *threads* during an outage. One thread re-dialling
 *   forever satisfies it completely.
 * - [WsReconnectRefusedTest] (computenet-dqy.27) reconnects into an **unbound
 *   port** — `ECONNREFUSED` at the socket, before any link exists. That case
 *   still retries forever on purpose (a listener that is down is expected
 *   back), and this fix does not touch it: the bound counts only opens that
 *   came *up* and were closed without ever being admitted.
 *
 * ## What is asserted
 *
 * The acceptance criterion is the *listener's* cost, so that is what is
 * measured: `admissionDenialCount` is one accept-plus-hello-parse charged to
 * the refusing side. It must stay bounded, and it must stop growing.
 */
class WsRefusedDialBoundTest {

    private class Stack(name: String?, allow: Set<PeerId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(20)
        }
    }

    /**
     * The bound the first test holds the transport to.
     *
     * Deliberately a literal rather than [WsTransport.REFUSED_DIAL_LIMIT], and
     * deliberately larger than it: an independent upper bound cannot be
     * satisfied by the production constant merely agreeing with itself. It is
     * also what the reproduction was run against before the fix existed — the
     * unfixed transport charged the listener 10 refusals inside the settle
     * window and failed here. The second test below pins the limit itself, by
     * injecting one.
     */
    private val generousBound = 8L

    @Test
    fun `a dialler refused at the listener's allowlist stops re-dialling instead of looping forever`() {
        val server = Stack(name = "server", allow = setOf(PeerId("good")))
        val listener = WsTransport.listen(0, server.side)
        try {
            val mallory = Stack(name = "mallory")
            // A near-zero schedule so an UNBOUNDED dialler blows past the bound
            // in milliseconds rather than in minutes: at 20ms the settle window
            // below is room for ~100 further re-dials.
            val refused = WsTransport.connect(URI("ws://localhost:${listener.port}"), mallory.side) { 20L }
            try {
                // The loop is live: the first refusal happened and at least one
                // re-dial followed it. Without this the assertion below would
                // pass vacuously against a transport that never retried at all.
                await("the listener records a refusal and at least one refused re-dial after it") {
                    listener.admissionDenialCount >= 2L
                }

                // Now watch it stop. An unbounded dialler keeps charging the
                // listener one accept + one hello parse every 20ms.
                val settleWindowMs = 2_000L
                val deadline = System.currentTimeMillis() + settleWindowMs
                while (System.currentTimeMillis() < deadline) {
                    val seen = listener.admissionDenialCount
                    if (seen > generousBound) {
                        throw AssertionFailedError(
                            "the refused dialler is re-dialling without bound: the listener has now paid " +
                                "$seen accept+hello refusals for one peer it already refused, which is more than " +
                                "the $generousBound this transport may cost it. At a 20ms schedule that is " +
                                "unbounded in practice; at the production schedule it is ~1/second forever.",
                        )
                    }
                    Thread.sleep(25)
                }

                // ...and it really has stopped, not merely slowed: no further
                // refusal at all across a second window.
                val quiescent = listener.admissionDenialCount
                Thread.sleep(500)
                val after = listener.admissionDenialCount
                if (after != quiescent) {
                    throw AssertionFailedError(
                        "the refused dialler never stopped: the listener paid $quiescent refusals, then " +
                            "${after - quiescent} more in the next 500ms. A refused dialler must give up, " +
                            "because nothing on this wire will ever tell it that it was refused.",
                    )
                }
                // The DEFAULT limit is the constant, not an independent literal
                // (to within the one in-flight re-dial, as above).
                if (refused.unadmittedOpens !in
                    WsTransport.REFUSED_DIAL_LIMIT..(WsTransport.REFUSED_DIAL_LIMIT + 1)
                ) {
                    throw AssertionFailedError(
                        "a dialler built with the default limit gave up after ${refused.unadmittedOpens} " +
                            "unadmitted opens rather than WsTransport.REFUSED_DIAL_LIMIT " +
                            "(${WsTransport.REFUSED_DIAL_LIMIT})",
                    )
                }
                if (!refused.abandonedAfterRefusals) {
                    throw AssertionFailedError(
                        "the dialler stopped charging the listener but does not report having given up; a client " +
                            "that has stopped retrying must never be invisible (WsConnection.scheduleReconnect's " +
                            "own rule for an interrupted retry loop)",
                    )
                }
            } finally {
                refused.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }

    /**
     * The limit is the thing that governs, and the bound is per-connection.
     *
     * Injecting a limit of 2 pins that [WsTransport.REFUSED_DIAL_LIMIT] is a
     * real parameter rather than a number the code happens to agree with: the
     * listener's cost tracks the limit it was given, to within the one re-dial
     * that can be in flight when the run completes.
     */
    @Test
    fun `the refused-dial limit governs exactly, and does not close the listener to anyone else`() {
        val server = Stack(name = "server", allow = setOf(PeerId("good")))
        val listener = WsTransport.listen(0, server.side)
        val uri = URI("ws://localhost:${listener.port}")
        try {
            val mallory = Stack(name = "mallory")
            val refused = WsTransport.connect(uri, mallory.side, backoff = { 20L }, refusedDialLimit = 2)
            try {
                await("the refused dialler gives up") { refused.abandonedAfterRefusals }
                // The limit governs to within the one re-dial that can be in
                // flight when the run completes — see
                // [WsTransport.REFUSED_DIAL_LIMIT]'s "plus at most one". The
                // point is that TWO governs and five does not: an off-by-one
                // window is a bound, an unbounded loop is not.
                Thread.sleep(500)
                if (refused.unadmittedOpens !in 2..3) {
                    throw AssertionFailedError(
                        "gave up after ${refused.unadmittedOpens} unadmitted opens; a dialler limited to 2 may " +
                            "reach 3 only through the single re-dial that can already be in flight",
                    )
                }
                if (listener.admissionDenialCount !in 2L..3L) {
                    throw AssertionFailedError(
                        "the listener paid ${listener.admissionDenialCount} refusals for a dialler limited to 2",
                    )
                }
            } finally {
                refused.shutdown()
            }

            // The give-up belongs to the refused connection, not to the
            // listener: an allowlisted peer still peers on the same socket.
            val good = Stack(name = "good")
            val connection = WsTransport.connect(uri, good.side)
            try {
                await("the admitted peer reaches its auth level") { connection.achievedAuthLevel != null }
                if (connection.abandonedAfterRefusals || connection.unadmittedOpens != 0) {
                    throw AssertionFailedError("an admitted peering must charge nothing against the refusal bound")
                }
                if (listener.admissionDenialCount !in 2L..3L) {
                    throw AssertionFailedError("admitting a peer moved the listener's denial count")
                }
            } finally {
                connection.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }
}
