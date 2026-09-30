package civictech.agora

import civictech.inspect.InspectorServer
import civictech.inspect.InspectorFlag
import civictech.testkit.HttpProbe
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * computenet-3iv0w.3 (3iv0w-D4): agora serves its live graph under the
 * shared `--inspect-port` opt-in (`InspectorFlag`), on its own port, labelled
 * `"agora"`.
 */
class AgoraInspectorTest {

    @Test
    fun `the opt-in inspector serves agora's live graph`() {
        val app = AgoraApp(port = 0, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val server = app.inspector
            requireNotNull(server) { "app.inspector must be non-null once started with an inspector option" }
            val json = HttpProbe("http://localhost:${server.boundPort}").state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"agora"""" in json, "process host name: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = AgoraApp(port = 0).start()
        try {
            assertNull(app.inspector)
        } finally {
            app.stop()
        }
    }
}
