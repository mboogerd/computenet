package civictech.demo.slotfinder

import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorServer
import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `--inspect-port` opt-in (computenet-3iv0w rule R5, D2/D4), wired
 * for `:demo:slotfinder`: one host (`slotfinder`).
 */
class SlotFinderInspectorTest {

    @Test
    fun `the opt-in inspector serves the pipeline's live graph`() {
        val app = SlotFinderApp(port = 0, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val probe = HttpProbe("http://localhost:${app.inspector!!.boundPort}")

            val json = probe.state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"slotfinder"""" in json, "missing host slotfinder: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            assertNull(app.inspector, "no --inspect-port ⇒ no inspector")
        } finally {
            app.stop()
        }
    }
}
