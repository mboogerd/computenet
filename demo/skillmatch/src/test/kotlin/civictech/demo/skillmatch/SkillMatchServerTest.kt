package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMatchServerTest {

    @Test
    fun `qualification appears with the last matching skill and revokes on retraction`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            // backend requires kotlin + sql; ada has kotlin only → 1/2, not qualified
            probe.post("action=jskill&job=backend&skill=kotlin")
            probe.post("action=jskill&job=backend&skill=sql")
            probe.post("action=cskill&candidate=ada&skill=kotlin")

            // each derived view updates asynchronously (separate outlets, no
            // glitch-free join at the edge — see F-5 in doc/demo-findings.md),
            // so await the JOINT condition, not the first view to move
            var json = probe.await {
                """"matched":1,"required":2,"qualified":false""" in it &&
                        """"gap":[{"job":"backend","skill":"sql"}]""" in it
            }
            assertTrue(""""qualified":false""" in json, "partial match should not qualify: $json")
            assertTrue(""""gap":[{"job":"backend","skill":"sql"}]""" in json, "sql should be the gap: $json")

            // the last matching skill flips qualification and empties the gap
            probe.post("action=cskill&candidate=ada&skill=sql")
            json = probe.await {
                """"matched":2,"required":2,"qualified":true""" in it && """"gap":[]""" in it
            }
            assertTrue(""""qualified":true""" in json, "full match should qualify: $json")
            assertTrue(""""gap":[]""" in json, "gap should be empty: $json")

            // retraction revokes: removing ada's sql demotes to 1/2 and restores the gap
            probe.post("action=uncskill&candidate=ada&skill=sql")
            // the gate carries the whole asserted state, matched/required included
            // (computenet-i6vx): those counters are a different fold from `qualified`.
            json = probe.await {
                """"matched":1,"required":2,"qualified":false""" in it &&
                    """"skill":"sql"""" in it.substringAfter("\"gap\"")
            }
            assertTrue(""""matched":1,"required":2,"qualified":false""" in json, "retraction should demote: $json")

            // boundary validation
            assertEquals(400, probe.post("action=cskill&candidate=ada"))
            assertEquals(400, probe.post("action=cskill&skill=kotlin"))
            assertEquals(400, probe.post("action=nonsense&candidate=a&skill=b"))
        } finally {
            app.stop()
        }
    }
}
