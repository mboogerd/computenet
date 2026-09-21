package civictech.demo.shell

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val clients = CopyOnWriteArrayList<Client>()
    private val dropped = AtomicLong()

    // computenet-tp93v: guards [sse]'s "compute the initial frame, register,
    // enqueue it" sequence against [broadcast]'s "compute, enqueue to every
    // registered client" so the two can never interleave.
    // The bug this closes: [sse] used to register the exchange into [clients]
    // BEFORE computing and writing its initial frame. A [broadcast] landing in
    // that window would find the exchange already registered and win the race
    // to write to it — `HttpExchange.sseFrame`'s own per-exchange lock only
    // stops two writes from tearing one frame, not from landing in the wrong
    // ORDER, because it says nothing about which of two contending writers
    // acquires it first. A client could then receive a newer broadcast frame
    // (e.g. allocator-observe's terminal `frozenJson` envelope) followed by the
    // stale frame its own connection had already computed — exactly the
    // "frozen fold looks live again" confusion computenet-w20a4 removed for
    // every non-racing case. A single lock shared between registration and
    // broadcast — rather than only a per-client one — is what the fix needs:
    // it is the only thing that can serialize "a client is joining" against
    // "a broadcast is going out to whoever has joined so far" (a broadcast
    // touches every client, not just the one that is connecting). With it,
    // [sse] either finishes computing-registering-and-enqueueing before a
    // racing [broadcast] can even see the new client (so that client's queue
    // holds its own initial frame first — and if the loop had already died by
    // then, that computation itself observes the frozen state and labels it
    // correctly), or the racing [broadcast] blocks until [sse] releases the
    // lock and its frame is enqueued after it.
    //
    // **Why the order survives the write leaving the lock (computenet-t9kpr).**
    // The lock covers only frame COMPUTATION and a NON-BLOCKING hand-off to
    // each client's own queue ([Client.offer]); the socket write happens on
    // that client's own pump thread, outside the lock. Ordering still holds
    // because (a) every enqueue to every client happens under this lock, so
    // each queue receives frames in the order they were computed; (b) the
    // queue is FIFO and drop-oldest evicts only from its head, so what the
    // pump takes is a subsequence of that order; and (c) one pump per client
    // is the only writer to its exchange. So the newest frame a client has
    // been sent is never followed by an older one — the same guarantee, now
    // without holding a lock across a socket write.
    //
    // **What that buys.** Before, this lock was held across an unbounded
    // blocking write to EVERY client, so one client that was still connected
    // but had stopped reading (a throttled tab, a paused debugger) stalled
    // both the broadcasting thread — for every demo here the host's single
    // scheduler thread, so the whole dataflow — and, because
    // `server.executor = null` runs all handlers on one dispatcher thread
    // that then blocked in [sse] waiting for this lock, every new connection
    // and every other route. Now a stalled client stalls only its own pump;
    // its queue fills and drops its oldest frames ([dropped]). That loss is
    // benign for every caller here: each demo's frame is a whole-state
    // snapshot, so the newest frame alone is the current state. A caller that
    // ever broadcasts DELTAS must not rely on every frame arriving — that is
    // the inspector's `SseBroadcaster` shape (gap-detecting `seq` per frame),
    // which this mirrors but cannot import (`:inspect` depends on this module).
    //
    // **No lock-order inversion exists today, and it is not free.** The only
    // lock taken *under* this one is each demo's own `state` monitor (inside
    // the `frame()`/`initialFrame()` lambda); the per-exchange write monitor
    // is now taken only on pump threads, never under this lock. No path takes
    // `state` and *then* takes this one: every hub/observe callback releases
    // `state` before it calls broadcast, and an `inlet.call` mutation made
    // while holding `state` only enqueues onto the host scheduler rather than
    // running the callback inline. A demo that calls [broadcast] while
    // holding a lock its own frame computation takes would deadlock against a
    // concurrently connecting client.
    private val clientsLock = Any()

    // Set by sse() for its one registration (no demo registers more than one
    // SSE endpoint). slotfinder is the one demo whose page JS relies on the
    // browser EventSource's onerror-reconnect, which only fires once the
    // connection is actually closed — so its failed sends must close the
    // exchange, not just drop it from the broadcast list. Every other demo
    // is content to drop silently, matching their original `send`.
    @Volatile private var closeOnSendFailure = false

    val boundPort: Int get() = server.address.port

    /** Registered SSE clients — tests and diagnostics. */
    internal val clientCount: Int get() = clients.size

    /** Frames discarded by a full client queue's drop-oldest policy — tests and diagnostics. */
    internal val droppedFrames: Long get() = dropped.get()

    init {
        server.executor = null
    }

    /** Register an arbitrary HTTP route; the handler dispatches on method/path itself. */
    fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { handler(it) }
    }

    /**
     * Register an SSE endpoint at [path]. Each connecting client is
     * immediately queued [initialFrame] (computed at connect time, under
     * [clientsLock]) and only then added to the broadcast list, so a fresh
     * tab catches up without waiting for the next change, and — the
     * computenet-tp93v guarantee — never receives that catch-up frame AFTER
     * a broadcast that raced its connection (see [clientsLock]).
     * [closeOnFailure] preserves slotfinder's original behavior of closing
     * the exchange on a failed write (see [closeOnSendFailure]); every other
     * demo leaves it at the default `false`.
     */
    fun sse(path: String, closeOnFailure: Boolean = false, initialFrame: () -> String) {
        closeOnSendFailure = closeOnFailure
        server.createContext(path) { exchange ->
            exchange.beginSse()
            synchronized(clientsLock) {
                val frame = initialFrame()
                val client = Client(exchange)
                client.offer(frame)
                clients += client
                client.start()
            }
        }
    }

    /**
     * Compute [frame] once and hand it to every connected SSE client's queue,
     * under [clientsLock] so a client that is mid-registration in [sse] either
     * completes first (and receives this broadcast afterward, correctly) or
     * is not registered yet (and simply misses this one broadcast, having
     * computed its own initial frame fresh) — never both registered and
     * still short of its own initial frame when this runs. Never blocks on a
     * client: the writes happen on each client's own pump thread.
     */
    fun broadcast(frame: () -> String) {
        synchronized(clientsLock) {
            val json = frame()
            clients.forEach { it.offer(json) }
        }
    }

    /**
     * One SSE subscriber: a bounded, drop-oldest frame queue and the one
     * virtual thread that writes it to [exchange]. A blocked write blocks only
     * this pump. Same shape as `:inspect`'s `SseBroadcaster.Client`.
     */
    private inner class Client(private val exchange: HttpExchange) {
        private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

        // Frames enqueued and not yet written (or dropped). Decremented only
        // after a write RETURNS, so zero means the socket has everything this
        // client was ever handed — what [awaitDrained] waits for.
        private val pending = AtomicInteger()

        private val pump: Thread = Thread.ofVirtual().name("demo-shell-sse-client").unstarted {
            try {
                while (true) {
                    val frame = queue.take()
                    try {
                        exchange.sseFrame(frame)
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            } catch (_: InterruptedException) {
                // stop() — an orderly shutdown
            } catch (_: Exception) {
                // the client is gone
                if (closeOnSendFailure) try { exchange.close() } catch (_: Exception) {}
            } finally {
                clients.remove(this)
                pending.set(0) // nothing more will be written; do not make stop() wait on it
            }
        }

        fun start() = pump.start()

        fun stop() = pump.interrupt()

        /** Wait, until [deadlineNanos] at most, for every frame handed to this client to be written. */
        fun awaitDrained(deadlineNanos: Long) {
            while (pending.get() > 0 && pump.isAlive && System.nanoTime() < deadlineNanos) Thread.sleep(1)
        }

        /**
         * Enqueue [frame], evicting the oldest pending frames until it fits.
         * Callers hold [clientsLock], so there is one producer at a time and
         * only the pump removes concurrently: an eviction cannot be undone
         * faster than it is made, and the loop cannot spin.
         */
        fun offer(frame: String) {
            repeat(QUEUE_CAPACITY + 1) {
                pending.incrementAndGet()
                if (queue.offer(frame)) return
                pending.decrementAndGet()
                if (queue.poll() != null) {
                    pending.decrementAndGet()
                    dropped.incrementAndGet()
                }
            }
            dropped.incrementAndGet() // unreachable with a single producer; counted rather than hidden
        }
    }

    fun start(): DemoShell = apply { server.start() }

    /**
     * Stop serving. Frames already handed to a client by [broadcast] are
     * flushed first — writes used to happen inside [broadcast] itself, so a
     * caller that broadcasts and then stops (a demo shutting down, a test) is
     * owed its last frame — but only for [STOP_DRAIN_MS] in total: a client
     * that stopped reading must not hold shutdown hostage any more than it
     * may hold [broadcast]. Then the server stops (joining the dispatcher, so
     * an in-flight handler still completes) and every pump is interrupted.
     */
    fun stop() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_DRAIN_MS)
        clients.toList().forEach { it.awaitDrained(deadline) }
        server.stop(0)
        clients.toList().forEach { it.stop() }
    }

    // `internal`, not private, so `DemoShellBindTest` can pin the *named*-port
    // branch of [endpoint] without picking a port number to bind: choosing an
    // ephemeral port in the test and handing the bare number to a later bind is
    // exactly the race computenet-dqy.25 removed from this repo, and it is not
    // worth reintroducing in a test of the thing that fixed it. The ephemeral
    // branch is asserted behaviorally there, over a real socket.
    internal companion object {

        /**
         * Frames one SSE client may fall behind by before its oldest pending
         * ones are dropped. Every demo frame is a whole-state snapshot, so a
         * client this far behind loses nothing but superseded states; the
         * bound exists to cap the memory a client that stopped reading can
         * pin (at most this many frames), not to tune throughput.
         */
        const val QUEUE_CAPACITY = 64

        /**
         * The most [stop] waits, across all clients together, for queued
         * frames to reach their sockets. A healthy loopback client drains in
         * well under a millisecond; this bounds only the stalled-client case.
         */
        const val STOP_DRAIN_MS = 2_000L

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
         * `:wire`'s `WsTransport.LOOPBACK` resolves the same address for the same
         * reason and is deliberately *not* reused. `:demo:shell` depends on
         * nothing but kotlinx-serialization — not even `:kernel`, deliberately,
         * as this module's own `build.gradle.kts` records — while `:wire` is the
         * WebSocket transport and pulls `:kernel` in behind it. Importing it here
         * to save four lines would invert the demo module graph; a shared home in
         * `:kernel` would cost `:demo:shell` that same dependency for a rule that
         * is about sockets, not the cell model. So: two copies, each pointing at
         * the other. Change one and change the other.
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
