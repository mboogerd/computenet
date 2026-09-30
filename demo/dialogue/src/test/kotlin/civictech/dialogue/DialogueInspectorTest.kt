package civictech.dialogue

import civictech.dialogue.extract.RuleExtractor
import civictech.inspect.InspectorServer
import civictech.inspect.InspectorFlag
import civictech.testkit.HttpProbe
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * computenet-3iv0w.3 (3iv0w-D4): dialogue serves its live graph under the
 * shared `--inspect-port` opt-in (`InspectorFlag`), on its own port, labelled
 * `"dialogue"`.
 */
class DialogueInspectorTest {

    @Test
    fun `the opt-in inspector serves dialogue's live graph`() {
        val app = DialogueApp(port = 0, extractor = RuleExtractor, inspector = InspectorFlag.Options(port = 0)).start()
        try {
            val server = app.inspector
            requireNotNull(server) { "app.inspector must be non-null once started with an inspector option" }
            val json = HttpProbe("http://localhost:${server.boundPort}").state(InspectorServer.TOPOLOGY_PATH)

            assertTrue(""""host":"dialogue"""" in json, "process host name: $json")
            assertTrue(""""typeFqn"""" in json, "expected at least one node: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = DialogueApp(port = 0, extractor = RuleExtractor).start()
        try {
            assertNull(app.inspector)
        } finally {
            app.stop()
        }
    }
}
