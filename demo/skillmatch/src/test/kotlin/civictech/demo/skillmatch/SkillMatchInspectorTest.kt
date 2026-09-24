package civictech.demo.skillmatch

import civictech.inspect.InspectorServer
import civictech.inspect.edit.Capability
import civictech.inspect.edit.WritePlane
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

    /**
     * The M4 navigator's pilot: `main` runs the pipeline plus [SideGraph], so
     * `GET /graphs` has both the named and the unnamed case to answer with.
     */
    @Test
    fun `the pilot's two graphs list as one named and one unnamed component`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val port = app.startInspector(port = 0, withSideGraph = true).boundPort
            val probe = HttpProbe("http://localhost:$port")

            val graphs = probe.state("/api/inspect/graphs")

            // the pipeline's component is named; the side graph's is not, and
            // the inspector renders its generated id rather than inventing one
            assertTrue(""""name":"skillmatch"""" in graphs, "the pipeline's name: $graphs")
            assertTrue(""""name":null""" in graphs, "the side graph stays unnamed: $graphs")
            assertEquals(2, graphs.split("\"lifecycle\":\"hot\"").size - 1, "unexpected graph count: $graphs")
            // 16 pipeline cells (10 named + 6 observation sinks) and 2 side cells
            assertTrue(""""cells":16""" in graphs, "the pipeline's cell count: $graphs")
            assertTrue(""""cells":2""" in graphs, "the side graph's cell count: $graphs")

            // name search reaches into both components
            val hits = probe.state("/api/inspect/search?mode=name&q=saved")
            assertTrue(""""label":"savedSearches"""" in hits, "a cell hit in the side graph: $hits")

            // and the topology filter scopes the canvas to one of them
            val sideId = Regex(""""id":"(g-[^"]+)","name":null""").find(graphs)!!.groupValues[1]
            val scoped = probe.state("/api/inspect/topology?graph=$sideId")
            assertEquals(2, scoped.split("\"typeFqn\"").size - 1, "unexpected scoped node count: $scoped")
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

    /**
     * WKB2 F5 (`[WKB2-06]`): the write plane defaults to off, and `startInspector`
     * passing an [WritePlane.Enabled] plane is what advertises it — the demo-flag
     * half (`main`'s `--inspect-write`) is verified by compile plus the manual
     * run the epic prescribes, per this task's bead.
     */
    @Test
    fun `the write plane stays off unless asked for`() {
        val disabledApp = SkillMatchApp(port = 0).start()
        val enabledApp = SkillMatchApp(port = 0).start()
        try {
            val disabledPort = disabledApp.startInspector(port = 0).boundPort
            val enabledPort = enabledApp.startInspector(
                port = 0,
                writePlane = WritePlane.Enabled(Capability("t")),
            ).boundPort

            val disabledBody = HttpProbe("http://localhost:$disabledPort").state(InspectorServer.CAPABILITIES_PATH)
            val enabledBody = HttpProbe("http://localhost:$enabledPort").state(InspectorServer.CAPABILITIES_PATH)

            assertEquals("""{"writePlane":false}""", disabledBody, "disabled by default: $disabledBody")
            assertTrue(""""writePlane":true""" in enabledBody, "enabled when passed: $enabledBody")
        } finally {
            disabledApp.stop()
            enabledApp.stop()
        }
    }
}
