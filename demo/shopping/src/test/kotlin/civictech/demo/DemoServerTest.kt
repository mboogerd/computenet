package civictech.demo

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
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"

            val post = HttpRequest.newBuilder(URI("$base/op"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("user=tester&action=add&item=apples"))
                .build()
            assertEquals(200, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode())

            val events = client.send(
                HttpRequest.newBuilder(URI("$base/events")).build(),
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
            val client = HttpClient.newHttpClient()
            val base = "http://localhost:${app.boundPort}"
            fun op(action: String, item: String) = client.send(
                HttpRequest.newBuilder(URI("$base/op"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("user=tester&action=$action&item=$item"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            op("add", "milk")   // on the list, not yet wanted
            op("vote", "milk")  // now in items ∩ votes

            // read the "wanted" field until milk shows up, then goes away on remove
            val wanted = Regex(""""wanted":\[([^]]*)]""")
            fun await(pred: (String) -> Boolean, why: String) {
                val events = client.send(
                    HttpRequest.newBuilder(URI("$base/events")).build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                val deadline = System.currentTimeMillis() + 5_000
                events.body().bufferedReader().use { reader ->
                    while (System.currentTimeMillis() < deadline) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:")) {
                            val w = wanted.find(line)?.groupValues?.get(1).orEmpty()
                            if (pred(w)) return
                        }
                    }
                }
                throw AssertionError(why)
            }

            await({ "milk" in it }, "voted-and-listed item never entered the wanted view")
            op("remove", "milk") // removed from the list → drops from the intersection
            await({ "milk" !in it }, "removed item stayed in the wanted view")
        } finally {
            app.stop()
        }
    }
}
