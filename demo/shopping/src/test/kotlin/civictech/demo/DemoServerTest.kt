package civictech.demo

import civictech.testkit.HttpProbe
import civictech.testkit.bounded
import civictech.testkit.boundedHttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoServerTest {

    @Test
    fun `an op lands in the SSE state stream`() {
        val app = DemoApp(port = 0).start()
        try {
            val client = boundedHttpClient()
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            assertEquals(200, probe.post("user=tester&action=add&item=apples"))

            val events = client.send(
                HttpRequest.newBuilder(URI("$base/events")).bounded().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            assertEquals(200, events.statusCode())

            // the op flows writer → union → hub asynchronously; read until it shows
            val deadline = System.currentTimeMillis() + 5_000
            var seen = false
            events.body().bufferedReader().use { reader ->
                while (!seen && System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:") && "apples" in line) seen = true
                }
            }
            assertTrue(seen, "SSE stream never delivered the added item")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `the wanted view is items intersect votes`() {
        val app = DemoApp(port = 0).start()
        try {
            val client = boundedHttpClient()
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            fun op(action: String, item: String) = probe.post("user=tester&action=$action&item=$item")

            op("add", "milk")   // on the list, not yet wanted
            op("vote", "milk")  // now in items ∩ votes

            // read data frames until one satisfies the predicate (whole "data:" line)
            val wanted = Regex(""""wanted":\[([^]]*)]""")
            fun wantedOf(line: String) = wanted.find(line)?.groupValues?.get(1).orEmpty()
            fun await(pred: (String) -> Boolean, why: String) {
                val events = client.send(
                    HttpRequest.newBuilder(URI("$base/events")).bounded().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                val deadline = System.currentTimeMillis() + 5_000
                events.body().bufferedReader().use { reader ->
                    while (System.currentTimeMillis() < deadline) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:") && pred(line)) return
                    }
                }
                throw AssertionError(why)
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
