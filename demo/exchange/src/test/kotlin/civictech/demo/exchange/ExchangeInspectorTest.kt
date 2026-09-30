package civictech.demo.exchange

import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorServer
import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `--inspect-port` opt-in (computenet-3iv0w rule R4, D2/D4), wired
 * for `:demo:exchange` — the region-partitioned composition probe (CP-E2):
 * three hosts in solo mode (the app host plus its two aggregation shards,
 * `shardCount = 2`), a fourth when peered.
 */
class ExchangeInspectorTest {

    @Test
    fun `the opt-in inspector serves every host's live graph in solo mode`() {
        val app = ExchangeApp(port = 0, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val probe = HttpProbe("http://localhost:${app.inspector!!.boundPort}")

            val json = probe.state(InspectorServer.TOPOLOGY_PATH)

            listOf("exchange", "exchange-shard-0", "exchange-shard-1").forEach { host ->
                assertTrue(""""host":"$host"""" in json, "missing host $host: $json")
            }
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
            // solo mode: no peering bridge host
            assertTrue(""""host":"exchange-bridge"""" !in json, "solo mode has no bridge host: $json")
        } finally {
            app.stop()
        }
    }

    /**
     * A peered app also names its bridge host, once an actual peer has
     * connected: the bridge host holds the ingress/mirror cells `Peering`/
     * `WsTransport` spawn on the socket handshake, so it shows up empty (no
     * `typeFqn` under it) until a real peer dials in — a real loopback
     * socket, in-process (no extra JVM, no `@Tag("multi-jvm")`), the same
     * shape `TieringServerTest`'s wire-mode smoke test uses.
     */
    @Test
    fun `a peered app also names its bridge host once a peer has connected`() {
        val listener = ExchangeApp(
            port = 0,
            wire = ExchangeApp.Wire.Listen(0),
            inspector = InspectorFlag.Options(port = 0),
        ).start()
        var dialer: ExchangeApp? = null
        try {
            val wsPort = checkNotNull(listener.boundWsPort) { "a listening peer must have a bound ws port" }
            dialer = ExchangeApp(port = 0, wire = ExchangeApp.Wire.Dial("ws://localhost:$wsPort")).start()

            val probe = HttpProbe("http://localhost:${listener.inspector!!.boundPort}")
            val json = probe.await(path = InspectorServer.TOPOLOGY_PATH) { """"host":"exchange-bridge"""" in it }

            assertTrue(""""host":"exchange-bridge"""" in json, "expected the bridge host: $json")
        } finally {
            dialer?.stop()
            listener.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = ExchangeApp(port = 0).start()
        try {
            assertNull(app.inspector, "no --inspect-port ⇒ no inspector")
        } finally {
            app.stop()
        }
    }
}
