package civictech.demo

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoServerTest {

    @Test
    fun `an op lands in the SSE state stream`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            assertEquals(200, probe.post("user=tester&action=add&item=apples"))

            // the op flows writer → union → hub asynchronously; read until it shows.
            // The read carries its own bound (computenet-o7c3): a stream that goes
            // silent after its headers fails here, not at JUnit's 300s timeout.
            val line = awaitSseData("$base/events", timeoutMs = 5_000) { "apples" in it }
            assertTrue("apples" in line, "SSE stream never delivered the added item: $line")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the wanted view is items intersect votes`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            fun op(action: String, item: String) = probe.post("user=tester&action=$action&item=$item")

            op("add", "milk")   // on the list, not yet wanted
            op("vote", "milk")  // now in items ∩ votes

            // read data frames until one satisfies the predicate (whole "data:" line)
            val wanted = Regex(""""wanted":\[([^]]*)]""")
            fun wantedOf(line: String) = wanted.find(line)?.groupValues?.get(1).orEmpty()
            fun await(pred: (String) -> Boolean, why: String) {
                // a fresh subscription per await; the read is bounded by its own
                // deadline rather than by the request (computenet-o7c3)
                try {
                    awaitSseData("$base/events", timeoutMs = 5_000, predicate = pred)
                } catch (failed: AssertionError) {
                    throw AssertionError("$why (${failed.message})", failed)
                }
            }

            // voted + listed → in the intersection, and the count tracks it
            await({ "milk" in wantedOf(it) && """"voteCount":1""" in it }, "voted-and-listed item never entered the wanted view / count")
            op("remove", "milk") // removed from the list → drops from the intersection
            await({ "milk" !in wantedOf(it) && """"voteCount":0""" in it }, "removed item stayed in the wanted view / count")
        } finally {
            app.stop()
        }
    }
}
