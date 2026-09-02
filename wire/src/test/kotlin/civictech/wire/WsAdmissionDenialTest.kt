package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.UUID

/**
 * Wire half of seam 1's hello refusal (spec 40/43, `[SEC1-06]`/`[SEC1-07]`;
 * computenet-usd.4.2): a listener's `Peering.Side.allow` refuses a hello from
 * a peer it does not name, the same way `BridgeIngressCell` refuses a frame
 * from an unlisted peer (computenet-usd.4.1) — a typed `ADMISSION` denial,
 * never a thrown fault, with the connection closed before any announcement
 * or frame crosses.
 *
 * `:wire` carries no `:testkit` dependency
 * (`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:17-19`), so this hand-rolls
 * the same `Stack`/`await` scaffolding [WsReconnectRefusedTest] and
 * [WsPeerIdentityTest] already use rather than reaching for `SimWorld`.
 */
class WsAdmissionDenialTest {

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    private class Stack(name: String?, allow: Set<KeyId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) }, allow = allow)
    }

    // Same shape as WsReconnectRefusedTest.await / WsPeerIdentityTest.await:
    // every condition below returns the instant it holds on a healthy
    // machine, so a generous deadline only matters when the test is failing.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `a hello from a peer not on the allowlist is refused and accounted, an admitted peer still connects unchanged`() {
        val server = Stack(name = "server", allow = setOf(KeyId("good")))
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        val listener = WsTransport.listen(0, server.side)
        val port = listener.port
        try {
            // -- mallory is not on the allowlist: refused before anything crosses --
            val mallory = Stack(name = "mallory")
            // Fixed 5s backoff: any reconnect attempt after this test's own
            // shutdown() would be moot anyway, but there is no reason to pay
            // DEFAULT_RECONNECT_BACKOFF's doubling for it.
            val refused = WsTransport.connect(URI("ws://localhost:$port"), mallory.side) { 5_000L }
            try {
                await("the refused hello is accounted on the listener's admission sink") {
                    listener.admissionDenialCount >= 1L
                }
                // [SEC1-06]: refused before any announcement or frame reaches
                // the peer — the server never gets past the admits() check
                // to run its own announce, so this can never flip true later
                // either, not just "hasn't yet".
                mallory.registry.location(collector.ref).shouldBeNull()
            } finally {
                refused.shutdown()
            }

            val deniedBefore = listener.admissionDenialCount

            // -- an admitted peer connects and announces exactly as before --
            val good = Stack(name = "good")
            val connection = WsTransport.connect(URI("ws://localhost:$port"), good.side)
            try {
                await("the admitted peer's collector announcement arrives") {
                    good.registry.location(collector.ref) is LocationRegistry.Remote
                }
                // the admitted peer's hello never moves the counter
                listener.admissionDenialCount shouldBe deniedBefore
            } finally {
                connection.shutdown()
            }
        } finally {
            listener.stop(1000)
        }
    }
}
