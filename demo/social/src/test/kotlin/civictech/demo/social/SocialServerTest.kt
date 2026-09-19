package civictech.demo.social

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-surface tests for [SocialApp] (feature `computenet-jo2jk`, task
 * `computenet-jo2jk.3`, design jo2jk-D9): one test per [SOC1-HTTP-01..04]
 * rule, plus the happy path pinning `/state`'s exact jo2jk-D6 shape.
 */
class SocialServerTest {

    // --- SOC1-HTTP-01 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-01 serves slash, state and op through DemoShell`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            val index = probe.get("/")
            assertEquals(200, index.statusCode())
            assertTrue("EventSource('/events')" in index.body(), "index should embed the SSE subscription: ${index.body()}")

            val state = probe.get("/state")
            assertEquals(200, state.statusCode())
            assertTrue(state.body().startsWith("{"), "/state should be JSON: ${state.body()}")

            val op = probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            assertEquals(200, op)
        } finally {
            app.stop()
        }
    }

    // --- SOC1-HTTP-02 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-02 state is byte-identical across repeated fetches and across two apps given the same ops`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            val first = probe.await { """"knows":[2]""" in it }
            val second = probe.state()
            assertEquals(first, second, "/state fetched twice with no intervening op should be byte-identical")
        } finally {
            app.stop()
        }

        val appA = SocialApp(port = 0).start()
        val appB = SocialApp(port = 0).start()
        try {
            val probeA = HttpProbe("http://localhost:${appA.boundPort}")
            val probeB = HttpProbe("http://localhost:${appB.boundPort}")
            listOf(probeA, probeB).forEach { probe ->
                probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
                probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
                probe.post("action=knows&a=1&b=2")
            }
            val stateA = probeA.await { """"knows":[2]""" in it }
            val stateB = probeB.await { """"knows":[2]""" in it }
            assertEquals(stateA, stateB, "two fresh apps given the same ops should agree on /state")
        } finally {
            appA.stop()
            appB.stop()
        }
    }

    // --- SOC1-HTTP-03 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-03 the first SSE frame carries current state before any change frame`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            probe.await { """"knows":[2]""" in it }

            val expected = probe.state()
            val frame = awaitSseData("http://localhost:${app.boundPort}/events")
            val payload = frame.removePrefix("data: ")
            assertEquals(expected, payload, "the first SSE frame should equal /state fetched with no op in flight")
        } finally {
            app.stop()
        }
    }

    // --- SOC1-HTTP-04 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-04 a missing param, non-numeric id, unknown id or unknown action is 400 and never mutates state`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            fun assertNoMutation(op: String) {
                val before = probe.state()
                assertEquals(400, probe.post(op), "expected 400 for '$op'")
                assertEquals(before, probe.state(), "/state must be unchanged after a rejected '$op'")
            }

            assertNoMutation("action=knows&a=1") // missing b
            assertNoMutation("action=knows&a=1&b=999") // unknown person 999
            assertNoMutation("action=knows&a=x&b=1") // non-numeric a
            assertNoMutation("action=nonsense") // unknown action
        } finally {
            app.stop()
        }
    }

    // --- SOC1-SCHEMA-02 (state half, pinned in jo2jk-D6) -------------------

    @Test
    fun `SOC1-SCHEMA-02 a big SNB id appears verbatim in state`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=8796093022390&firstName=Big&lastName=Id")
            val json = probe.await { """"id":8796093022390""" in it }
            assertTrue(""""id":8796093022390""" in json, "big SNB id should be preserved verbatim in /state: $json")
        } finally {
            app.stop()
        }
    }

    // --- happy path: /state's exact pinned shape (jo2jk-D6) ----------------

    @Test
    fun `happy path pins the exact state shape from jo2jk-D6`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            probe.post("action=forum&id=100&title=f&moderator=1")
            probe.post("action=join&person=1&forum=100")
            probe.post("action=post&id=10&author=1&forum=100&content=hi")
            probe.post("action=comment&id=11&author=2&replyOf=10&content=reply")
            probe.post("action=like&person=2&message=10")

            val json = probe.await {
                """"counts":{"persons":2,"knows":2,"forums":1,"messages":2,"likes":1,"tags":0}""" in it
            }

            assertTrue(""""knows":[2]""" in json, "person 1 should know person 2: $json")
            assertTrue(""""members":[1]""" in json, "forum 100 should have member 1: $json")
            assertTrue(""""contains":[10]""" in json, "forum 100 should contain message 10: $json")
            assertTrue(""""replies":[11]""" in json, "message 10 should have reply 11: $json")
            assertTrue(""""likes":[2]""" in json, "message 10 should be liked by person 2: $json")
        } finally {
            app.stop()
        }
    }
}
