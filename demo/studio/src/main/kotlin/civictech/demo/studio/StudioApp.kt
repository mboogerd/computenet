package civictech.demo.studio

import civictech.demo.shell.*
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Local command service: the guards apply to API, static assets AND SSE. */
class StudioServer(val workspace: StudioWorkspace, port: Int, private val ui: Path? = null) : AutoCloseable {
    private val shell = DemoShell(port, InetAddress.getLoopbackAddress())
    private val streams = ConcurrentHashMap.newKeySet<Thread>()
    @Volatile private var closed = false
    val boundPort: Int get() = shell.boundPort

    init { shell.route("/") { exchange -> handle(exchange) } }
    fun start(): StudioServer = apply { shell.start() }

    private fun handle(x: HttpExchange) {
        try {
            val host = x.requestHeaders["Host"]?.singleOrNull()
            if (host == null || !Regex("(?:localhost|127\\.0\\.0\\.1|\\[::1])(?::[0-9]+)?", RegexOption.IGNORE_CASE).matches(host))
                throw StudioException(403, "Host must be localhost, 127.0.0.1 or [::1]")
            val method = x.requestMethod
            if (method in setOf("POST", "PATCH") && x.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                throw StudioException(415, "Content-Type must be application/json")
            val path = x.requestURI.path
            if (path.startsWith("/api/")) api(x, method, path)
            else static(x, path)
        } catch (e: StudioException) { respond(x, e.status, obj("error" to JsonPrimitive(e.message ?: "request failed")).toString()) }
        catch (e: IllegalArgumentException) { respond(x, 400, obj("error" to JsonPrimitive(e.message ?: "invalid request")).toString()) }
        catch (e: Exception) {
            e.printStackTrace(System.err)
            respond(x, 500, obj("error" to JsonPrimitive(e.message ?: "request failed")).toString())
        }
    }

    private fun api(x: HttpExchange, method: String, path: String) {
        val parts = path.trim('/').split('/')
        fun body(): JsonObject = json.parseToJsonElement(x.requestBody.bufferedReader().use { it.readText() }).jsonObject
        fun ok(value: String) = respond(x, 200, value)
        fun empty() { x.sendResponseHeaders(204, -1); x.close() }
        if (parts == listOf("api", "workflows")) {
            when (method) {
                "GET" -> ok(json.encodeToString(workspace.list()))
                "POST" -> {
                    val name = (body()["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: bad("name must be a string")
                    ok(json.encodeToString(workspace.create(name)))
                }
                else -> throw StudioException(404, "route not found")
            }
            return
        }
        if (parts.size < 3 || parts[1] != "workflows") throw StudioException(404, "route not found")
        val id = parts[2]
        if (parts.size == 3 && method == "DELETE") { workspace.remove(id); empty(); return }
        workspace.withRuntime(id) { runtime ->
            val p = listOf("api") + parts.drop(3)
            when {
                method == "GET" && p == listOf("api", "events") -> events(x, id, runtime)
                method == "GET" && p == listOf("api", "graph") -> ok(json.encodeToString(runtime.graph()))
                method == "POST" && p == listOf("api", "nodes") -> ok(json.encodeToString(runtime.addNode(body())))
                method == "POST" && p == listOf("api", "edges") -> {
                    val b = body(); ok(json.encodeToString(runtime.addEdge(b.string("from"), b.string("to"), b.string("port"))))
                }
                p.size == 3 && p[1] == "edges" && method == "DELETE" -> { runtime.deleteEdge(p[2]); empty() }
                p.size == 3 && p[1] == "nodes" && method == "PATCH" -> ok(json.encodeToString(runtime.patchNode(p[2], body())))
                p.size == 3 && p[1] == "nodes" && method == "DELETE" -> { runtime.deleteNode(p[2]); empty() }
                p.size == 4 && p[1] == "nodes" && method == "POST" -> {
                    val b = body()
                    when (p[3]) {
                        "submit" -> runtime.submit(p[2], b.string("text"))
                        "fire" -> runtime.fire(p[2])
                        "run" -> runtime.run(p[2])
                        "generate" -> runtime.generate(p[2])
                        "approve" -> runtime.approve(p[2], b.string("hash"))
                        else -> throw StudioException(404, "route not found")
                    }
                    empty()
                }
                p.size == 4 && p[1] == "nodes" && p[3] == "runs" && method == "GET" -> ok(json.encodeToString(runtime.runs(p[2])))
                p.size == 5 && p[1] == "nodes" && p[3] == "runs" && method == "GET" -> ok(json.encodeToString(runtime.runDetail(p[2], p[4])))
                else -> throw StudioException(404, "route not found")
            }
        }
    }

    private fun static(x: HttpExchange, requestPath: String) {
        if (x.requestMethod != "GET") throw StudioException(404, "route not found")
        val root = ui?.toRealPath() ?: throw StudioException(404, "UI directory not configured")
        if (requestPath.split('/').any { it == ".." } || requestPath.contains('\\')) throw StudioException(403, "invalid path")
        val file = root.resolve(if (requestPath == "/") "index.html" else requestPath.removePrefix("/")).normalize()
        if (!file.startsWith(root)) throw StudioException(403, "invalid path")
        if (!Files.isRegularFile(file)) throw StudioException(404, "file not found")
        val real = file.toRealPath()
        if (!real.startsWith(root)) throw StudioException(403, "invalid path")
        val type = when (file.fileName.toString().substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            else -> Files.probeContentType(real) ?: "application/octet-stream"
        }
        val bytes = Files.readAllBytes(real)
        x.responseHeaders.set("Content-Type", type)
        x.responseHeaders.set("Cache-Control", if (root.relativize(file).startsWith("assets"))
            "public, max-age=31536000, immutable" else "no-cache")
        x.sendResponseHeaders(200, bytes.size.toLong())
        x.responseBody.use { it.write(bytes) }
    }

    /** DemoShell.sse lacks a request filter seam. Use its framing helpers after our guard.
     * Each subscriber has a bounded queue; slow readers disconnect and reconnect with a snapshot.
     */
    private fun events(x: HttpExchange, workflowId: String, runtime: StudioRuntime) {
        x.beginSse()
        val queue = ArrayBlockingQueue<String>(256)
        val overflow = AtomicBoolean()
        val subscription = runtime.listen { frame ->
            if (!overflow.get() && !queue.offer(frame)) {
                overflow.set(true)
                queue.clear()
                queue.offer("") // Wake the writer to disconnect outside the state lock.
            }
        }
        lateinit var removal: AutoCloseable
        val thread = Thread.ofVirtual().name("studio-sse-$workflowId").unstarted {
            try {
                while (!closed) {
                    val frame = queue.take()
                    if (overflow.get()) break
                    x.sseFrame(frame)
                }
            } catch (_: Exception) { /* disconnect */ }
            finally { subscription.close(); removal.close(); x.close(); streams.remove(Thread.currentThread()) }
        }
        removal = workspace.onRemoval(workflowId) { thread.interrupt(); x.close() }
        streams += thread
        thread.start()
    }

    override fun close() {
        closed = true
        streams.forEach { it.interrupt() }
        shell.stop()
    }
}

private fun respond(x: HttpExchange, status: Int, body: String) = x.respond(status, body, "application/json; charset=utf-8")

fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] in setOf("--data", "--ui")) i += 2 else { positional += args[i]; i++ }
    }
    val portArgs = if (positional.isEmpty() && System.getenv("PORT") == null) arrayOf("18440") else positional.toTypedArray()
    val data = args.value("--data")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".local/share/computenet/studio/default")
    val workspace = StudioWorkspace(data.toAbsolutePath())
    val server = StudioServer(workspace, demoPort(portArgs), args.value("--ui")?.let(Path::of)).start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close(); workspace.close() })
    announcePort("http", server.boundPort)
    println("Studio: http://localhost:${server.boundPort}")
    CountDownLatch(1).await()
}
