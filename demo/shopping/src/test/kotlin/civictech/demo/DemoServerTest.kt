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
}
