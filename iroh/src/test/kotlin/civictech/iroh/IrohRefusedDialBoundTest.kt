package civictech.iroh

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * computenet-4gzr, the iroh half of [civictech.wire.WsRefusedDialBoundTest]:
 * a dialler refused at the listening side's allowlist must stop re-dialling.
 *
 * ## Why the dialler cannot simply be told
 *
 * `iroh/sidecar/PROTOCOL.md` carries no refusal reason. The listener refuses a
 * hello by closing the link, and `PROTOCOL.md` §3 renders that as a plain
 * `LINK_DOWN` — byte-identical to the one a transport drop produces. So
 * `IrohConnection.retire` had nothing to key on and started
 * `scheduleReconnect()` for both, and because the *dial itself succeeds* (the
 * refusal is a subsequent close), each re-dial loop terminated at once and the
 * next refusal started a fresh loop at `attempt = 0`. The backoff never
 * escalated past its first delay: a fixed ~1s re-dial forever on
 * [IrohTransport.DEFAULT_RECONNECT_BACKOFF], costing the refusing side one link
 * accept plus one hello parse per second, indefinitely.
 *
 * Carrying a refusal reason on the wire is the other shape the acceptance
 * allows, and it is deliberately not taken here: it is a `PROTOCOL.md` change,
 * which belongs to the open sibling `computenet-ey4v`. The case is
 * distinguishable *locally* — `LINK_UP` followed by `LINK_DOWN` with
 * [IrohTransport.Session.peered] never true — so the bound needs no new frame
 * and no wire-compatibility break (AGENTS.md, "Preserve binary/wire
 * compatibility").
 *
 * ## What this is NOT
 *
 * `IrohReconnectTest`'s unplanned-drop case: a link that came up, *was
 * admitted*, and then dropped is a partition to recover from and still retries
 * forever. Only an open that was never admitted counts against the bound, and
 * an admitted link resets the count to zero.
 *
 * Skip-gated on the sidecar binary, like every other sidecar-backed test here.
 */
class IrohRefusedDialBoundTest {

    private class Stack(name: String?, allow: Set<KeyId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    /**
     * The bound the first test holds the transport to — deliberately a literal
     * larger than [IrohTransport.REFUSED_DIAL_LIMIT], so it is an independent
     * upper bound rather than the production constant agreeing with itself. It
     * is what the reproduction was run against before the fix existed: the
     * unfixed transport charged the listening side 9 refusals inside the settle
     * window and failed here. The second test pins the limit itself, by
     * injecting one.
     */
    private val generousBound = 8L

    private fun stderrSink(label: String): (String) -> Unit = { line -> println("[iroh-stderr $label] $line") }

    @Test
    fun `a dialler refused at the listening side's allowlist stops re-dialling instead of looping forever`() {
        val binary = SidecarBinary.orSkip()
        val server = Stack(name = "server", allow = setOf(KeyId("good")))

        IrohTransport.listen(server.side, binary, stderrSink = stderrSink("server-listener")).use { listener ->
            val mallory = Stack(name = "mallory")
            IrohTransport.connect(
                mallory.side,
                listener.nodeId,
                listener.addresses,
                binary,
                stderrSink = stderrSink("mallory-dialler"),
                // A near-zero schedule, so an UNBOUNDED dialler blows past the
                // bound in the settle window below rather than in minutes.
                backoff = { 10L },
            ).use { refused ->
                // The loop is live: refused once, and re-dialled at least once
                // after that. Without this the bound below could pass vacuously
                // against a transport that never retried at all.
                await("the listener records a refusal and at least one refused re-dial after it") {
                    listener.admissionDenialCount >= 2L
                }

                // Now watch it stop. Each cycle costs the listener one link
                // accept plus one hello parse.
                val deadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < deadline) {
                    val seen = listener.admissionDenialCount
                    if (seen > generousBound) {
                        fail(
                            "the refused dialler is re-dialling without bound: the listening side has now paid " +
                                "$seen link-accept+hello refusals for one peer it already refused, which is more " +
                                "than the $generousBound this transport may cost it. At the production schedule " +
                                "this is ~1 accept/second/refused-peer, forever.",
                        )
                    }
                    Thread.sleep(50)
                }

                // ...and it really has stopped, not merely slowed.
                val quiescent = listener.admissionDenialCount
                Thread.sleep(1_000)
                val after = listener.admissionDenialCount
                if (after != quiescent) {
                    fail(
                        "the refused dialler never stopped: the listening side paid $quiescent refusals, then " +
                            "${after - quiescent} more in the next second. A refused dialler must give up on its " +
                            "own, because PROTOCOL.md will never tell it that it was refused.",
                    )
                }

                // The DEFAULT limit is the constant, not an independent literal
                // (to within the one re-dial that can be in flight when the run
                // completes — see IrohTransport.REFUSED_DIAL_LIMIT).
                if (refused.unadmittedOpens !in
                    IrohTransport.REFUSED_DIAL_LIMIT..(IrohTransport.REFUSED_DIAL_LIMIT + 1)
                ) {
                    fail(
                        "a dialler built with the default limit gave up after ${refused.unadmittedOpens} " +
                            "unadmitted links rather than IrohTransport.REFUSED_DIAL_LIMIT " +
                            "(${IrohTransport.REFUSED_DIAL_LIMIT})",
                    )
                }
                if (!refused.abandonedAfterRefusals) {
                    fail(
                        "the dialler stopped charging the listening side but does not report having given up; " +
                            "a dialler that has stopped retrying must never be invisible",
                    )
                }

                // The give-up is not permanent policy: an explicit heal() is an
                // operator's decision to try again, and it clears the run.
                // Only the give-up flag is read: `unadmittedOpens` is racy here
                // by construction — the healed link is refused again within
                // milliseconds on this schedule, which starts a fresh run.
                refused.heal()
                if (refused.abandonedAfterRefusals) {
                    fail("heal() must clear the give-up, so an operator can retry a peer that may now be listed")
                }
            }
        }
    }

    /**
     * The limit is the thing that governs, not a number the code happens to
     * agree with: injected as 2, the listening side pays two link accepts and
     * two hello parses for this peer — plus at most the one re-dial that can
     * already be in flight (see [IrohTransport.REFUSED_DIAL_LIMIT]).
     */
    @Test
    fun `the refused-dial limit governs exactly`() {
        val binary = SidecarBinary.orSkip()
        val server = Stack(name = "server", allow = setOf(KeyId("good")))

        IrohTransport.listen(server.side, binary, stderrSink = stderrSink("server-listener")).use { listener ->
            val mallory = Stack(name = "mallory")
            IrohTransport.connect(
                mallory.side,
                listener.nodeId,
                listener.addresses,
                binary,
                stderrSink = stderrSink("mallory-dialler"),
                backoff = { 10L },
                refusedDialLimit = 2,
            ).use { refused ->
                await("the refused dialler gives up") { refused.abandonedAfterRefusals }
                // The limit governs to within the one re-dial that can be in
                // flight when the run completes — see
                // IrohTransport.REFUSED_DIAL_LIMIT's "plus at most one". The
                // point is that TWO governs and five does not: an off-by-one
                // window is a bound, an unbounded loop is not.
                Thread.sleep(1_000)
                if (refused.unadmittedOpens !in 2..3) {
                    fail(
                        "gave up after ${refused.unadmittedOpens} unadmitted links; a dialler limited to 2 may " +
                            "reach 3 only through the single re-dial that can already be in flight",
                    )
                }
                if (listener.admissionDenialCount !in 2L..3L) {
                    fail("the listening side paid ${listener.admissionDenialCount} refusals for a dialler limited to 2")
                }
            }
        }
    }
}
