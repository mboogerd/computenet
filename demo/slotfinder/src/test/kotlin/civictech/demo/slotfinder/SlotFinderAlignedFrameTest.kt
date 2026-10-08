package civictech.demo.slotfinder

import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotFinderAlignedFrameTest {

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
    fun `canonical observation partitions slotfinder views and drains every group at idle`() {
        val app = SlotFinderApp(port = 0).start()
        try {
            assertEquals(
                mapOf(
                    "alice" to "alice",
                    "bob" to "bob",
                    "carol" to "carol",
                    "nearMiss" to "nearMiss+common+filtered+byDay+late",
                    "common" to "nearMiss+common+filtered+byDay+late",
                    "filtered" to "nearMiss+common+filtered+byDay+late",
                    "byDay" to "nearMiss+common+filtered+byDay+late",
                    "late" to "nearMiss+common+filtered+byDay+late",
                ),
                app.observationGroups,
            )

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            for (user in PARTICIPANTS) {
                assertEquals(200, probe.post("action=add&user=$user&day=Tue&hour=14"))
            }
            probe.await { "\"common\":[\"Tue-14\"]" in it }

            try {
                awaitUntil("slotfinder observation groups drain") {
                    app.observationGroupBufferedWaves.values.all { it == 0 }
                }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "slotfinder observation groups remained buffered: ${app.observationGroupBufferedWaves}",
                    failure,
                )
            }
            assertEquals(
                mapOf("alice" to 0, "bob" to 0, "carol" to 0, "nearMiss+common+filtered+byDay+late" to 0),
                app.observationGroupBufferedWaves,
            )
        } finally {
            app.stop()
        }
    }
}
