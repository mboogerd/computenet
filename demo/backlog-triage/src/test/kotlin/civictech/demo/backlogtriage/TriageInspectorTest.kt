package civictech.demo.backlogtriage

import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorServer
import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `--inspect-port` opt-in (computenet-3iv0w rule R5, D2/D4), wired
 * for `:demo:backlog-triage`: one host (`backlog-triage`).
 */
class TriageInspectorTest {

    @Test
    fun `the opt-in inspector serves the pipeline's live graph`() {
        val app = TriageApp(port = 0, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val probe = HttpProbe("http://localhost:${app.inspector!!.boundPort}")

            val json = probe.state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"backlog-triage"""" in json, "missing host backlog-triage: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = TriageApp(port = 0).start()
        try {
            assertNull(app.inspector, "no --inspect-port ⇒ no inspector")
        } finally {
            app.stop()
        }
    }
}
