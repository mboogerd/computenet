package civictech.demo.shell

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The JDK-httpserver + SSE shell duplicated byte-for-byte across the seven
 * demo mains (see doc/RESTRUCTURE-PLAN.md, session RS-9): request routing,
 * one SSE client list with framed `data: ...\n\n` broadcast, and start/stop.
 *
 * Deliberately generic: [route] takes a raw `(HttpExchange) -> Unit` handler
 * rather than method-shaped `get`/`post` helpers, because the demos
 * themselves dispatch on `exchange.requestMethod` and sub-paths inside one
 * handler (backlog-triage's `/features` does GET/POST/DELETE on one
 * context). A method-shaped API would not cover that demo without
 * redesigning it — this shell only extracts what's actually duplicated.
 * Likewise [sse] covers every demo's single `/events` endpoint; none
 * register more than one SSE stream.
 */
class DemoShell(port: Int) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val clients = CopyOnWriteArrayList<OutputStream>()

    val boundPort: Int get() = server.address.port

    init {
        server.executor = null
    }

    /** Register an arbitrary HTTP route; the handler dispatches on method/path itself. */
    fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { handler(it) }
    }

    /**
     * Register an SSE endpoint at [path]. Each connecting client is added to
     * the broadcast list and immediately sent [initialFrame] (computed at
     * connect time) so a fresh tab catches up without waiting for the next
     * change.
     */
    fun sse(path: String, initialFrame: () -> String) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            clients += out
            send(out, initialFrame())
        }
    }

    /** Compute [frame] once and push it to every connected SSE client. */
    fun broadcast(frame: () -> String) {
        val json = frame()
        clients.forEach { send(it, json) }
    }

    private fun send(out: OutputStream, json: String) {
        try {
            // per-stream lock: concurrent broadcasts (hubs fire on virtual
            // threads) must not interleave bytes into one SSE frame
            synchronized(out) {
                out.write("data: $json\n\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            clients -= out
        }
    }

    fun start(): DemoShell = apply { server.start() }

    fun stop() = server.stop(0)
}

/** The byte-identical `respond` extension every demo hand-rolled privately. */
fun HttpExchange.respond(status: Int, body: String, contentType: String = "text/plain") {
    responseHeaders.add("Content-Type", contentType)
    val bytes = body.toByteArray()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/** The byte-identical `main(args)` port resolution every demo hand-rolled privately. */
fun demoPort(args: Array<String>): Int =
    args.firstOrNull { !it.startsWith("--") }?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
