package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.URI

/**
 * computenet-bybk: **a clean stop must not look like a failure.**
 *
 * `WsListenerAcceptorStopSeamTest` (computenet-dqy.56) gave a stopped acceptor
 * a programmatic seam, `WsListener.acceptorStopped`, precisely so a health
 * check could poll it instead of scraping stderr — which means a false
 * positive on that seam is no longer a stderr line a human ignores, it is
 * something a health check acts on. The guard that is supposed to prevent a
 * false positive is `acceptLoop`'s `finally`:
 *
 * ```kotlin
 * if (!stopRequested && server.isOpen) reportAcceptorStopped(cause)
 * ```
 *
 * `!stopRequested` is what tells an ordinary `stop()` apart from the loop
 * dying on its own. It is pre-existing and correct by reading — the
 * independent review of computenet-dqy.56 (PR #95) measured it green on three
 * paths with a throwaway probe (idle listener `stop(1000)`; stop after a real
 * WebSocket connection; `HeldPort.release`, the demos' path) and then deleted
 * the probe rather than committing it, because writing the assertion that
 * decides the verdict is authoring the verdict. Nothing in `wire/src/test`
 * asserted it afterwards. This file is that standing assertion, covering the
 * same three paths.
 *
 * ## What it discriminates
 *
 * Measured by hand against a mutated `acceptLoop` with the `!stopRequested`
 * term dropped from the `finally` guard (`if (server.isOpen) ...`), never
 * committed: `stop after a real WebSocket connection has been made` fails
 * with `listener.acceptorStopped` non-null — a spurious `AcceptorStoppedException`
 * — where the unmutated guard leaves it null. See the bead comment for the
 * full run.
 */
class WsListenerCleanStopIsSilentTest {

    private fun side() = Peering.Side(LocationRegistry(), ManagedHost())

    private fun assertClean(listener: WsTransport.WsListener, label: String) {
        // acceptLoop's finally runs on its own daemon thread, asynchronously
        // with respect to stop() returning. Rather than a bare sleep, poll in
        // POLL_INTERVAL_MILLIS steps up to GRACE_MILLIS, breaking out early the
        // moment either seam is set — the same total margin
        // WsListenerAcceptorSurvivalTest gives its own asynchronous residue
        // check, but bounded with an early exit instead of an unconditional wait.
        val deadline = System.currentTimeMillis() + GRACE_MILLIS
        while (System.currentTimeMillis() < deadline &&
            listener.acceptorStopped == null &&
            listener.listeningSocketLoss == null
        ) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        withClue("$label: a clean stop must not report a spurious acceptor stop") {
            listener.acceptorStopped.shouldBeNull()
        }
        withClue("$label: a clean stop must not report a spurious listening-socket loss") {
            listener.listeningSocketLoss.shouldBeNull()
        }
    }

    @Test
    @Timeout(30)
    fun `an idle listener that is stopped reports neither seam`() {
        val listener = WsTransport.listen(0, side())
        listener.stop(1_000)
        assertClean(listener, "idle listener")
    }

    @Test
    @Timeout(30)
    fun `a listener that served a connection and is then stopped reports neither seam`() {
        val listener = WsTransport.listen(0, side())
        val port = listener.port
        val connection = WsTransport.connect(URI("ws://localhost:$port"), side()) { 20L }
        try {
            // `connect` blocks until the handshake completes or fails, so by
            // the time it returns the connection is open or connect() itself
            // threw.
            connection.isOpen shouldBe true
        } finally {
            // shutdown(), not closeBlocking() (computenet-sumi): closeBlocking
            // leaves `reconnect` true, so the resulting onClose arms a
            // ws-reconnect-* daemon thread that redials this listener on the
            // 20ms backoff above for the rest of the JVM.
            runCatching { connection.shutdown() }
            listener.stop(1_000)
        }
        assertClean(listener, "listener after a served connection")
    }

    @Test
    @Timeout(30)
    fun `HeldPort release stops the listener cleanly`() {
        HeldPort().use { heldPort ->
            val listener = heldPort.serve(side())
            heldPort.release(listener, timeoutMs = 1_000)
            assertClean(listener, "HeldPort.release")
        }
    }

    private companion object {
        /** @see assertClean */
        const val GRACE_MILLIS = 250L

        /** @see assertClean */
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
