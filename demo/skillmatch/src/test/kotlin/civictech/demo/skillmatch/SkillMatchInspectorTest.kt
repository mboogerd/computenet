package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pilot wiring of the inspector (97-inspector-plan M0-BE §4): opt-in, on
 * its own port, serving *this* demo's live graph. Asserts what the manual
 * `--inspect-port 7071` check asserts, so the wiring cannot rot silently.
 */
class SkillMatchInspectorTest {

    @Test
    fun `the opt-in inspector serves the pipeline's topology`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.startInspector(port = 0).boundPort}")

            val json = probe.state("/api/inspect/topology")

            // the ten pipeline cells, under the handle names the app knows them by
            listOf(
                "candSkills", "jobSkills", "matches", "matchCounts", "required",
                "qualification", "gap", "supply", "demand", "market",
            ).forEach { name -> assertTrue(""""name":"$name"""" in json, "missing node $name: $json") }

            // plus the six observation sinks the app folds its views through,
            // which no one named
            assertEquals(16, json.split("\"typeFqn\"").size - 1, "unexpected node count: $json")
            assertEquals(18, json.split("\"role\":\"CONSUME\"").size - 1, "unexpected edge count: $json")

            assertTrue(""""typeFqn":"civictech.cell.data.op.CombineLatestCell"""" in json, "market's class: $json")
            assertTrue(""""host":"skillmatch"""" in json, "process host name: $json")
            assertTrue(""""net":"local"""" in json, "M0 is single-process: $json")
            assertTrue(""""lifecycle":"HOT"""" in json, "published cells are hot: $json")
            assertTrue(""""from":{"ref":""" in json, "edges carry endpoints: $json")
            assertTrue(""""port":"outlet"""" in json, "endpoints carry port names: $json")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the inspector stays off unless asked for`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            // the demo's own port serves the demo, and nothing else was bound
            assertTrue(""""candidates":{}""" in HttpProbe("http://localhost:${app.boundPort}").state())
        } finally {
            app.stop()
        }
    }
}
