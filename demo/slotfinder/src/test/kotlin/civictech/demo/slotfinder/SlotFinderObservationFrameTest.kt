package civictech.demo.slotfinder

import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotFinderObservationFrameTest {

    @Test
    fun `state payload reads every panel from one observation frame`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            // `view.onChange` queues an asynchronous late-join catch-up broadcast
            // during startup. Its stateJson() read must happen before this window;
            // there are no other broadcasts before the first /state request.
            awaitUntil("slotfinder startup observation broadcast") {
                app.observationCurrentReads.get() >= 1L
            }
            val before = app.observationCurrentReads.get()
            val response = probe.get()

            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"byDay\":"))
            assertEquals(
                before + 1,
                app.observationCurrentReads.get(),
                "one /state payload must read one Observation.current() frame",
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `canonical observation exposes every slotfinder view`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            assertEquals(
                setOf("alice", "bob", "carol", "nearMiss", "common", "filtered", "byDay", "late"),
                app.observationViews,
            )
            assertEquals(
                listOf("alice", "bob", "carol", "nearMiss", "common", "filtered", "byDay", "late"),
                app.observationViews.toList(),
            )

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            for (user in PARTICIPANTS) {
                assertEquals(200, probe.post("action=add&user=$user&day=Tue&hour=14"))
            }
            probe.await { "\"common\":[\"Tue-14\"]" in it }
        } finally {
            app.stop()
        }
    }
}
