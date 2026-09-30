package civictech.demo.tiering

import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorServer
import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `--inspect-port` opt-in (computenet-3iv0w rule R4, D2/D4), wired
 * for `:demo:tiering`: one host (`tiering`) in solo mode, a second
 * (`tiering-bridge`) when peered.
 */
class TieringInspectorTest {

    @Test
    fun `the opt-in inspector serves the pipeline's live graph in solo mode`() {
        val app = TieringApp(port = 0, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val probe = HttpProbe("http://localhost:${app.inspector!!.boundPort}")

            val json = probe.state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"tiering"""" in json, "missing host tiering: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
            // solo mode: no peering bridge host
            assertTrue(""""host":"tiering-bridge"""" !in json, "solo mode has no bridge host: $json")
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
     * shape this suite's own wire-mode smoke test (`TieringServerTest`) uses.
     */
    @Test
    fun `a peered app also names its bridge host once a peer has connected`() {
        val listener = TieringApp(
            port = 0,
            wire = TieringApp.Wire.Listen(0),
            inspector = InspectorFlag.Options(port = 0),
        ).start()
        var dialer: TieringApp? = null
        try {
            val wsPort = checkNotNull(listener.boundWsPort) { "a listening peer must have a bound ws port" }
            dialer = TieringApp(port = 0, wire = TieringApp.Wire.Dial("ws://localhost:$wsPort")).start()

            val probe = HttpProbe("http://localhost:${listener.inspector!!.boundPort}")
            val json = probe.await(path = InspectorServer.TOPOLOGY_PATH) { """"host":"tiering-bridge"""" in it }

            assertTrue(""""host":"tiering-bridge"""" in json, "expected the bridge host: $json")
        } finally {
            dialer?.stop()
            listener.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = TieringApp(port = 0).start()
        try {
            assertNull(app.inspector, "no --inspect-port ⇒ no inspector")
        } finally {
            app.stop()
        }
    }
}
