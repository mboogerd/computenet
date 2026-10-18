package civictech.demo.studio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

@Timeout(30)
class StudioHttpTest {
    @TempDir lateinit var temp: Path

    private fun request(port: Int, method: String, path: String, host: String = "localhost:$port", contentType: String? = null, body: String = ""): Pair<Int, String> {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5000
            val bytes = body.toByteArray()
            val header = "$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n" +
                (contentType?.let { "Content-Type: $it\r\n" } ?: "") + "\r\n"
            s.getOutputStream().write(header.toByteArray() + bytes)
            val response = s.getInputStream().bufferedReader().readText()
            return response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
        }
    }

    @Test fun `host and JSON guards cover API static and events`() {
        StudioRuntime(temp.resolve("data")).use { r ->
            StudioServer(r, 0).start().use { server ->
                val port = server.boundPort
                for (path in listOf("/api/graph", "/events", "/")) assertEquals(403, request(port, "GET", path, "evil.example").first)
                assertEquals(415, request(port, "POST", "/api/nodes", body = "{}").first)
                assertEquals(415, request(port, "PATCH", "/api/nodes/missing", contentType = "text/plain", body = "{}").first)
                assertEquals(200, request(port, "GET", "/api/graph", "[::1]:$port").first)
                assertEquals(404, request(port, "OPTIONS", "/api/nodes").first)
                val created = request(port, "POST", "/api/nodes", contentType = "application/json; charset=utf-8", body = """{"kind":"text","x":2,"y":3}""")
                assertEquals(200, created.first)
                val id = json.parseToJsonElement(created.second).jsonObject.string("id")
                assertEquals(204, request(port, "POST", "/api/nodes/$id/submit", contentType = "application/json", body = """{"text":"hello"}""").first)
                val graph = request(port, "GET", "/api/graph")
                assertEquals("hello", json.parseToJsonElement(graph.second).jsonObject["state"]!!.jsonObject[id]!!.jsonObject.string("text"))
                assertEquals(204, request(port, "DELETE", "/api/nodes/$id").first)
            }
        }
    }

    @Test fun `HTTP reports cycle and invalid input conflicts`() {
        StudioRuntime(temp).use { r ->
            val a = r.addNode(obj("kind" to JsonPrimitive("exec")))
            val b = r.addNode(obj("kind" to JsonPrimitive("exec")))
            r.addEdge(a.id, b.id, "in")
            StudioServer(r, 0).start().use { s ->
                val response = request(s.boundPort, "POST", "/api/edges", contentType = "application/json",
                    body = """{"from":"${b.id}","to":"${a.id}","port":"in"}""")
                assertEquals(409, response.first); assertTrue(response.second.contains("CycleWithoutHead"))
                assertEquals(409, request(s.boundPort, "POST", "/api/nodes/${a.id}/run", contentType = "application/json", body = "{}").first)
            }
        }
    }

    @Test fun `unexpected storage failure returns 500 and logs to stderr while malformed JSON returns 400`() {
        val data = temp.resolve("data")
        StudioRuntime(data).use { r ->
            StudioServer(r, 0).start().use { s ->
                assertEquals(400, request(s.boundPort, "POST", "/api/nodes", contentType = "application/json", body = "{").first)
                Files.createDirectory(data.resolve("graph.json"))
                Files.writeString(data.resolve("graph.json/blocker"), "prevent replacement")
                val previous = System.err
                val log = java.io.ByteArrayOutputStream()
                try {
                    System.setErr(java.io.PrintStream(log, true, Charsets.UTF_8))
                    val response = request(s.boundPort, "POST", "/api/nodes", contentType = "application/json", body = """{"kind":"text"}""")
                    assertEquals(500, response.first)
                    assertTrue(json.parseToJsonElement(response.second).jsonObject.string("error").isNotBlank())
                    assertTrue(log.toString(Charsets.UTF_8).contains("graph.json"))
                    assertTrue(log.toString(Charsets.UTF_8).contains("StudioServer"))
                } finally { System.setErr(previous) }
            }
        }
    }

    @Test fun `static files reject traversal and symlink escape`() {
        val ui = Files.createDirectory(temp.resolve("assets"))
        Files.writeString(ui.resolve("index.html"), "studio")
        Files.writeString(temp.resolve("secret"), "secret")
        Files.createSymbolicLink(ui.resolve("escape"), temp.resolve("secret"))
        StudioRuntime(temp.resolve("data")).use { r ->
            StudioServer(r, 0, ui).start().use { s ->
                assertEquals("studio", request(s.boundPort, "GET", "/").second)
                assertEquals(403, request(s.boundPort, "GET", "/%2e%2e/secret").first)
                assertEquals(403, request(s.boundPort, "GET", "/escape").first)
            }
        }
    }

    @Test fun `SSE initial snapshot precedes graph and state frames`() {
        StudioRuntime(temp).use { r ->
            StudioServer(r, 0).start().use { s ->
                Socket(InetAddress.getLoopbackAddress(), s.boundPort).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    val reader = socket.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().contains("200"))
                    var header: String
                    do { header = reader.readLine() } while (header.isNotEmpty())
                    // HttpServer uses chunked encoding; find the data line within the chunks.
                    fun frame(): JsonObject {
                        while (true) {
                            val line = reader.readLine() ?: error("SSE closed")
                            if (line.startsWith("data: ")) return json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                        }
                    }
                    assertEquals("snapshot", frame().string("type"))
                    val node = r.addNode(obj("kind" to JsonPrimitive("text")))
                    assertEquals("graph", frame().string("type"))
                    assertEquals(node.id, frame()["node"]!!.jsonObject.string("id"))
                    r.submit(node.id, "live")
                    assertEquals("live", frame()["node"]!!.jsonObject.string("text"))
                }
            }
        }
    }
}
