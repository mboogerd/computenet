package civictech.demo.shell

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The JDK-httpserver + SSE shell duplicated byte-for-byte across the seven
 * demo mains (see doc/archive/runs/RESTRUCTURE-PLAN.md, session RS-9): request routing,
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
 *
 * [bindAddress] names the address to bind explicitly, and is honored as given.
 * T19 added the parameter so a caller with a reason not to accept connections
 * from the whole network — `InspectorServer`, which serves live topology and
 * cell state — can pass `InetAddress.getLoopbackAddress()` without every other
 * demo's `Main.kt` needing to change. With it left `null` the address is
 * chosen from [port]; see [endpoint].
 */
class DemoShell(port: Int, bindAddress: InetAddress? = null) {
    private val server: HttpServer = HttpServer.create(endpoint(port, bindAddress), 0)
    private val clients = CopyOnWriteArrayList<HttpExchange>()

    // Set by sse() for its one registration (no demo registers more than one
    // SSE endpoint). slotfinder is the one demo whose page JS relies on the
    // browser EventSource's onerror-reconnect, which only fires once the
    // connection is actually closed — so its failed sends must close the
    // exchange, not just drop it from the broadcast list. Every other demo
    // is content to drop silently, matching their original `send`.
    private var closeOnSendFailure = false

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
     * change. [closeOnFailure] preserves slotfinder's original behavior of
     * closing the exchange on a failed write (see [closeOnSendFailure]);
     * every other demo leaves it at the default `false`.
     */
    fun sse(path: String, closeOnFailure: Boolean = false, initialFrame: () -> String) {
        closeOnSendFailure = closeOnFailure
        server.createContext(path) { exchange ->
            exchange.beginSse()
            clients += exchange
            send(exchange, initialFrame())
        }
    }

    /** Compute [frame] once and push it to every connected SSE client. */
    fun broadcast(frame: () -> String) {
        val json = frame()
        clients.forEach { send(it, json) }
    }

    private fun send(exchange: HttpExchange, json: String) {
        try {
            exchange.sseFrame(json)
        } catch (_: Exception) {
            clients -= exchange
            if (closeOnSendFailure) try { exchange.close() } catch (_: Exception) {}
        }
    }

    fun start(): DemoShell = apply { server.start() }

    fun stop() = server.stop(0)

    // `internal`, not private, so `DemoShellBindTest` can pin the *named*-port
    // branch of [endpoint] without picking a port number to bind: choosing an
    // ephemeral port in the test and handing the bare number to a later bind is
    // exactly the race computenet-dqy.25 removed from this repo, and it is not
    // worth reintroducing in a test of the thing that fixed it. The ephemeral
    // branch is asserted behaviorally there, over a real socket.
    internal companion object {

        /**
         * The endpoint this shell binds.
         *
         * An explicit [bindAddress] wins, unchanged. Otherwise a **named** port
         * keeps the wildcard address — the shell's original
         * `InetSocketAddress(port)` — because a named port may be an endpoint
         * something off-box was told to dial, while an **ephemeral** port (0)
         * binds [LOOPBACK].
         *
         * That distinction is computenet-dqy.33, the residual of
         * computenet-dqy.28 outside `:wire`. A wildcard binding and another
         * process's binding of the *same* port on a *specific* address can
         * coexist on BSD/macOS, and the specific one wins the name `localhost`
         * — so a client that resolves `localhost` reaches the stranger.
         * Observed on macOS with several agent sessions on one machine:
         * `TwoJvmConvergenceTest` timed out awaiting "both peers serving HTTP"
         * twice in 20 runs with both peers alive and peer B having announced
         * `computenet-port http 52597`, while `lsof` showed a foreign JVM
         * holding `127.0.0.1:52597 (LISTEN)` — the probe's
         * `http://localhost:52597` reached that stranger and never got a 200.
         *
         * The shell cannot opt out of the reuse flag that permits the overlap:
         * `sun.net.httpserver.ServerImpl` calls `setReuseAddress(true)`
         * unconditionally off Windows. It can only choose the address, which is
         * what this does. Measured here, 20 trials each, macOS 26.6 (aarch64):
         * while a shell holds an ephemeral port, a foreign `ServerSocket` with
         * SO_REUSEADDR — Java's default — binding `127.0.0.1:<that port>`
         * succeeds **20/20 against a wildcard-bound shell** and **0/20 against
         * a loopback-bound one**, and the loopback-bound endpoint answers as
         * `localhost` 20/20. On Linux the overlap needs SO_REUSEPORT on both
         * sockets, so it is 0/20 in every shape and this changes nothing that
         * worked. Same measurement and reasoning as `WsTransport.listen`.
         */
        fun endpoint(port: Int, bindAddress: InetAddress?): InetSocketAddress = when {
            bindAddress != null -> InetSocketAddress(bindAddress, port)
            port == 0 -> InetSocketAddress(LOOPBACK, 0)
            else -> InetSocketAddress(port)
        }

        /**
         * The address `localhost` names here.
         *
         * `getByName` returns the *first* address `localhost` resolves to, which
         * is also the first address a client's `http://localhost:<port>` tries —
         * so binding it makes server and client agree by construction on
         * whichever family the host prefers (127.0.0.1 on macOS and on GitHub's
         * ubuntu runner image; `::1` on an image whose hosts file puts IPv6
         * first). Nothing here hard-codes 127.0.0.1, which is what makes the
         * loopback bind portable rather than a guess about `/etc/hosts`.
         *
         * `:wire`'s `WsTransport.loopback` resolves the same address for the
         * same reason and is deliberately *not* reused: `:demo:shell` has no
         * transport dependency, and keeping the WebSocket transport out of the
         * demo shell is worth three lines (see AGENTS.md).
         */
        val LOOPBACK: InetAddress = try {
            InetAddress.getByName("localhost")
        } catch (_: UnknownHostException) {
            InetAddress.getLoopbackAddress() // a hosts file with no `localhost` at all
        }
    }
}

/**
 * Open [this] exchange as an SSE stream: the `text/event-stream` headers and
 * the open-ended 200 every SSE endpoint here starts with. Split out of [DemoShell.sse]
 * (verbatim) so a server with its own per-client delivery policy — the inspector's
 * bounded, drop-oldest queues — reuses the same framing instead of duplicating it.
 */
fun HttpExchange.beginSse() {
    responseHeaders.add("Content-Type", "text/event-stream")
    responseHeaders.add("Cache-Control", "no-cache")
    sendResponseHeaders(200, 0)
}

/**
 * Write one `data: <json>\n\n` frame. Per-exchange lock: concurrent broadcasts
 * (hubs fire on virtual threads) must not interleave bytes into one SSE frame.
 * Throws when the client is gone — the caller decides what that means.
 */
fun HttpExchange.sseFrame(json: String) {
    synchronized(this) {
        responseBody.write("data: $json\n\n".toByteArray())
        responseBody.flush()
    }
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

/**
 * Print the port this process **actually bound** for [name], machine-readably:
 * `computenet-port <name> <port>`.
 *
 * A demo launched with `0` for a port is the only party that can say which port it
 * got — and the only one that should choose it. computenet-dqy.25's defect was a
 * supervising test picking an ephemeral port, closing it, and handing the bare
 * number to a child JVM to bind later: a window in which any other `bind(0)` on the
 * machine could take it, which is how a `:demo:shopping` peer died of
 * `BindException` on CI. With this line the supervisor never guesses.
 *
 * The reading side is `civictech.testkit.JvmPeer.Peer.port`, whose KDoc carries the
 * rest of the reasoning; `JvmPeer.PORT_LINE_PREFIX` is this prefix. The names in use
 * are `http` (the demo's own [DemoShell]), `ws` (a `--listen` peering port) and
 * `inspect` (an `--inspect-port` inspector). The human-readable line each demo
 * already prints stays — this one is additional, not a replacement.
 */
fun announcePort(name: String, port: Int) {
    println("computenet-port $name $port")
}

/**
 * T12 finding 5: a correct JSON string escaper, replacing the byte-identical
 * hand-rolled `esc` in `tiering`/`skillmatch` (backslash + quote only — no
 * control-char/newline handling, a latent bug for any title/text containing
 * one). `JsonPrimitive` already gets this right, and is what backlog-triage's
 * `TriageApp.esc` used privately before this extraction.
 */
fun esc(s: String): String = JsonPrimitive(s).toString()

/**
 * `--name value` command-line pair lookup, hand-rolled per demo `main` (see
 * `agora`, `backlog-triage`, and — pending T07's peering-scaffold merge —
 * `shopping`/`exchange`). [flag] is the same lookup under the name that
 * reads better where the call site is really asking "is this flag present,
 * and with what value" (backlog-triage's `--seed`).
 */
fun Array<String>.value(name: String): String? {
    val i = indexOf(name)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}

/** Alias for [value] — see its doc. */
fun Array<String>.flag(name: String): String? = value(name)
