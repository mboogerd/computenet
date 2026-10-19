package civictech.demo.studio

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URI
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

    @Test fun `registry routes validate names list counts remove and reject unscoped or unknown routes`() {
        StudioWorkspace(temp).use { workspace ->
            StudioServer(workspace, 0).start().use { server ->
                val port = server.boundPort
                assertEquals("[]", request(port, "GET", "/api/workflows").second)
                assertEquals(415, request(port, "POST", "/api/workflows", body = "{}").first)
                assertEquals(403, request(port, "GET", "/api/workflows", host = "evil.example").first)
                for (name in listOf("", "x".repeat(81))) assertEquals(400, request(port, "POST", "/api/workflows",
                    contentType = "application/json", body = obj("name" to JsonPrimitive(name)).toString()).first)
                assertEquals(400, request(port, "POST", "/api/workflows", contentType = "application/json", body = """{"name":123}""").first)
                val created = request(port, "POST", "/api/workflows", contentType = "application/json", body = """{"name":"  First  "}""")
                assertEquals(200, created.first)
                val workflow = json.decodeFromString<Workflow>(created.second)
                assertEquals("First", workflow.name)
                assertEquals(409, request(port, "POST", "/api/workflows", contentType = "application/json", body = """{"name":"FIRST"}""").first)
                assertEquals(200, request(port, "POST", "/api/workflows", contentType = "application/json", body = """{"name":"Second"}""").first)
                request(port, "POST", "/api/workflows/${workflow.id}/nodes", contentType = "application/json", body = """{"kind":"text"}""")
                val listed = json.decodeFromString<List<Workflow>>(request(port, "GET", "/api/workflows").second)
                assertEquals(listOf(1, 0), listed.map { it.nodeCount })
                for (path in listOf("/api/graph", "/api/nodes", "/api/edges", "/events", "/api/workflows/missing/graph", "/api/workflows/missing/events"))
                    assertEquals(404, request(port, "GET", path).first, path)
                assertEquals(404, request(port, "POST", "/api/workflows/missing/nodes", contentType = "application/json", body = "{}").first)
                assertEquals(404, request(port, "DELETE", "/api/workflows/missing").first)
                assertEquals(204, request(port, "DELETE", "/api/workflows/${workflow.id}").first)
                assertEquals(404, request(port, "GET", "/api/workflows/${workflow.id}/graph").first)
                assertEquals(1, workspace.list().size)
            }
        }
    }

    @Test fun `failed workflow routes report conflict while other workflows remain accessible`() {
        lateinit var broken: Workflow
        lateinit var healthy: Workflow
        StudioWorkspace(temp).use { workspace ->
            broken = workspace.create("Broken"); healthy = workspace.create("Healthy")
        }
        Files.write(temp.resolve("workflows/${broken.id}/studio.journal"),
            java.nio.ByteBuffer.allocate(8).put(byteArrayOf(0x43, 0x4e, 0x4a, 0x4c)).putInt(Int.MAX_VALUE).array())
        StudioWorkspace(temp).use { workspace ->
            StudioServer(workspace, 0).start().use { server ->
                val failed = workspace.list().first()
                val routes = listOf("graph", "nodes", "nodes/n", "nodes/n/submit", "nodes/n/fire", "nodes/n/run",
                    "nodes/n/generate", "nodes/n/approve", "nodes/n/runs", "nodes/n/runs/r", "edges", "edges/e", "events")
                for (route in routes) {
                    val response = request(server.boundPort, "GET", "/api/workflows/${broken.id}/$route")
                    assertEquals(409, response.first, route)
                    assertEquals(failed.error, json.parseToJsonElement(response.second).jsonObject.string("error"))
                }
                assertEquals(200, request(server.boundPort, "GET", "/api/workflows/${healthy.id}/graph").first)
                assertEquals(204, request(server.boundPort, "DELETE", "/api/workflows/${broken.id}").first)
            }
        }
    }

    @Test fun `removing workflow closes its SSE stream while another stream remains live`() {
        StudioWorkspace(temp).use { workspace ->
            val a = workspace.create("A"); val b = workspace.create("B")
            StudioServer(workspace, 0).start().use { server ->
                fun stream(id: String): Pair<Socket, java.io.BufferedReader> {
                    val socket = Socket(InetAddress.getLoopbackAddress(), server.boundPort)
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /api/workflows/$id/events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    val reader = socket.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().contains("200"))
                    while (reader.readLine().isNotEmpty()) { /* headers */ }
                    return socket to reader
                }
                fun frame(reader: java.io.BufferedReader): JsonObject {
                    while (true) {
                        val line = reader.readLine() ?: error("stream closed")
                        if (line.startsWith("data: ")) return json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    }
                }
                val (socketA, readerA) = stream(a.id)
                socketA.use {
                    val (socketB, readerB) = stream(b.id)
                    socketB.use {
                        assertEquals("snapshot", frame(readerA).string("type"))
                        assertEquals("snapshot", frame(readerB).string("type"))
                        assertEquals(204, request(server.boundPort, "DELETE", "/api/workflows/${a.id}").first)
                        while (readerA.readLine() != null) { /* chunk terminator then EOF, never timeout */ }
                        workspace.withRuntime(b.id) { it.addNode(obj("kind" to JsonPrimitive("text"))) }
                        assertEquals("graph", frame(readerB).string("type"))
                    }
                }
            }
        }
    }

    @Test fun `host and JSON guards cover API static and events`() {
        StudioWorkspace(temp.resolve("data")).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            StudioServer(workspace, 0).start().use { server ->
                val port = server.boundPort
                for (path in listOf("$api/graph", "$api/events", "/")) assertEquals(403, request(port, "GET", path, "evil.example").first)
                assertEquals(415, request(port, "POST", "$api/nodes", body = "{}").first)
                assertEquals(415, request(port, "PATCH", "$api/nodes/missing", contentType = "text/plain", body = "{}").first)
                assertEquals(200, request(port, "GET", "$api/graph", "[::1]:$port").first)
                assertEquals(404, request(port, "OPTIONS", "$api/nodes").first)
                val created = request(port, "POST", "$api/nodes", contentType = "application/json; charset=utf-8", body = """{"kind":"text","x":2,"y":3}""")
                assertEquals(200, created.first)
                val id = json.parseToJsonElement(created.second).jsonObject.string("id")
                assertEquals(204, request(port, "POST", "$api/nodes/$id/submit", contentType = "application/json", body = """{"text":"hello"}""").first)
                val graph = request(port, "GET", "$api/graph")
                assertEquals("hello", json.parseToJsonElement(graph.second).jsonObject["state"]!!.jsonObject[id]!!.jsonObject.string("text"))
                assertEquals(204, request(port, "DELETE", "$api/nodes/$id").first)
            }
        }
    }

    @Test fun `HTTP reports cycle and invalid input conflicts`() {
        StudioWorkspace(temp).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            val a = r.addNode(obj("kind" to JsonPrimitive("exec")))
            val b = r.addNode(obj("kind" to JsonPrimitive("exec")))
            r.addEdge(a.id, b.id, "in")
            StudioServer(workspace, 0).start().use { s ->
                val response = request(s.boundPort, "POST", "$api/edges", contentType = "application/json",
                    body = """{"from":"${b.id}","to":"${a.id}","port":"in"}""")
                assertEquals(409, response.first); assertTrue(response.second.contains("CycleWithoutHead"))
                assertEquals(409, request(s.boundPort, "POST", "$api/nodes/${a.id}/run", contentType = "application/json", body = "{}").first)
            }
        }
    }

    @Test fun `HTTP approval rejects an unreviewed hash and promotes the matching candidate`() {
        StudioWorkspace(temp.resolve("approval")).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            val p = r.addNode(obj("kind" to JsonPrimitive("program")))
            r.installCode(p.id, "print('candidate')") // no held input, so no process starts
            StudioServer(workspace, 0).start().use { s ->
                val path = "$api/nodes/${p.id}/approve"
                assertEquals(409, request(s.boundPort, "POST", path, contentType = "application/json", body = """{"hash":"wrong"}""").first)
                assertNull(r.state(p.id).approvedHash)
                val hash = r.state(p.id).codeHash!!
                assertEquals(204, request(s.boundPort, "POST", path, contentType = "application/json", body = """{"hash":"$hash"}""").first)
                assertEquals(hash, r.state(p.id).approvedHash)
                assertNull(r.state(p.id).preview)
            }
        }
    }

    @Test fun `unexpected storage failure returns 500 and logs to stderr while malformed JSON returns 400`() {
        val data = temp.resolve("data")
        StudioWorkspace(data).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            StudioServer(workspace, 0).start().use { s ->
                assertEquals(400, request(s.boundPort, "POST", "$api/nodes", contentType = "application/json", body = "{").first)
                Files.createDirectory(r.data.resolve("graph.json"))
                Files.writeString(r.data.resolve("graph.json/blocker"), "prevent replacement")
                val previous = System.err
                val log = java.io.ByteArrayOutputStream()
                try {
                    System.setErr(java.io.PrintStream(log, true, Charsets.UTF_8))
                    val response = request(s.boundPort, "POST", "$api/nodes", contentType = "application/json", body = """{"kind":"text"}""")
                    assertEquals(500, response.first)
                    assertTrue(json.parseToJsonElement(response.second).jsonObject.string("error").isNotBlank())
                    assertTrue(log.toString(Charsets.UTF_8).contains("graph.json"))
                    assertTrue(log.toString(Charsets.UTF_8).contains("StudioServer"))
                } finally { System.setErr(previous) }
            }
        }
    }

    @Test fun `static HTML and unhashed files revalidate while Vite assets are immutable`() {
        val ui = Files.createDirectories(temp.resolve("ui"))
        Files.createDirectories(ui.resolve("assets"))
        val files = mapOf(
            "/" to ("index.html" to "no-cache"),
            "/index.html" to ("index.html" to "no-cache"),
            "/manifest.json" to ("manifest.json" to "no-cache"),
            "/assets-other.js" to ("assets-other.js" to "no-cache"),
            "/assets/index-abc123.js" to ("assets/index-abc123.js" to "public, max-age=31536000, immutable"),
            "/assets/index-def456.css" to ("assets/index-def456.css" to "public, max-age=31536000, immutable"),
        )
        files.values.forEach { (file, _) -> Files.writeString(ui.resolve(file), "fixture:$file") }
        StudioWorkspace(temp.resolve("data")).use { workspace ->
            StudioServer(workspace, 0, ui).start().use { server ->
                for ((path, expected) in files) {
                    val connection = URI("http://localhost:${server.boundPort}$path").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    try {
                        assertEquals(200, connection.responseCode, path)
                        assertEquals(expected.second, connection.getHeaderField("Cache-Control"), path)
                        assertEquals("fixture:${expected.first}", connection.inputStream.bufferedReader().use { it.readText() }, path)
                    } finally { connection.disconnect() }
                }
            }
        }
    }

    @Test fun `static files reject traversal and symlink escape`() {
        val ui = Files.createDirectory(temp.resolve("assets"))
        Files.writeString(ui.resolve("index.html"), "studio")
        Files.writeString(temp.resolve("secret"), "secret")
        Files.createSymbolicLink(ui.resolve("escape"), temp.resolve("secret"))
        StudioWorkspace(temp.resolve("data")).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            StudioServer(workspace, 0, ui).start().use { s ->
                assertEquals("studio", request(s.boundPort, "GET", "/").second)
                assertEquals(403, request(s.boundPort, "GET", "/%2e%2e/secret").first)
                assertEquals(403, request(s.boundPort, "GET", "/escape").first)
            }
        }
    }

    @Test fun `SSE initial snapshot precedes graph and state frames`() {
        StudioWorkspace(temp).use { workspace ->
            val workflow = workspace.create("Test")
            val r = workspace.withRuntime(workflow.id) { it }
            val api = "/api/workflows/${workflow.id}"
            StudioServer(workspace, 0).start().use { s ->
                Socket(InetAddress.getLoopbackAddress(), s.boundPort).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET $api/events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
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
