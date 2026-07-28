package civictech.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.IntakeClosedException
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The WebSocket transport driver (spec 41 point 4, M5.5): frames from a
 * [BridgeEgressCell] go out as binary messages; incoming binary messages are
 * handed — still encoded — to a bridge-hosted ingress, so IO threads never
 * run framework logic. The first message each way is a text hello carrying
 * the local mirror's ref; receiving it wires announcements ([Peering]) and
 * the peers become one graph. Disconnect unpublishes every ref learned
 * through this socket — senders park until a peer re-announces.
 */
object WsTransport {

    /**
     * The production reconnect backoff (M10.3): fixed doubling from 1s, capped at 30s,
     * retries forever. `attempt` is 0-based (the delay *before* the (attempt+1)-th
     * reconnect try). T12: pulled out to a default so tests can inject a near-zero
     * schedule instead of paying the real wall-clock delay.
     */
    val DEFAULT_RECONNECT_BACKOFF: (attempt: Int) -> Long = { attempt ->
        // guard overflow on a long-lived failing connection: cap the shift itself
        (1_000L shl attempt.coerceAtMost(20)).coerceAtMost(30_000L)
    }

    /** Serve peer connections on [port] (0 = ephemeral). Returns once accepting; `listener.port` is the bound port. */
    fun listen(port: Int, side: Peering.Side): WsListener {
        val listener = WsListener(port, side)
        listener.isReuseAddr = true
        listener.start()
        check(listener.awaitStart(10, TimeUnit.SECONDS)) { "WebSocket listener failed to start on port $port" }
        return listener
    }

    /**
     * Connect to a listening peer. Returns once the socket is open (hello exchange
     * proceeds asynchronously). [backoff] governs the delay before each reconnect
     * attempt after an unplanned close (default: [DEFAULT_RECONNECT_BACKOFF]) — tests
     * drive it down to make reconnect timing testable without wall-clock deadlines.
     *
     * The listener side of a fresh two-process pairing may simply not have bound
     * its port yet — an ordinary startup race, not a fatal one (found via CI's
     * demo:exchange peer log: a startup-race ECONNREFUSED killed `main()`). A bare
     * TCP probe retried on [backoff] absorbs that wait; java-websocket's own
     * `WebSocketClient` can't be retried before its first successful open (a
     * `connectReadThread`/`reset()` interaction it doesn't support), so the real
     * handshake below still runs exactly once, only after the port is reachable.
     */
    fun connect(uri: URI, side: Peering.Side, backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF): WsConnection {
        awaitReachable(uri, backoff)
        val connection = WsConnection(uri, side, backoff)
        check(connection.connectBlocking(10, TimeUnit.SECONDS)) { "could not connect to $uri" }
        return connection
    }

    private fun awaitReachable(uri: URI, backoff: (attempt: Int) -> Long) {
        var attempt = 0
        while (true) {
            try {
                Socket(uri.host, uri.port).close()
                return
            } catch (_: IOException) {
                Thread.sleep(backoff(attempt++))
            }
        }
    }

    private const val HELLO = "HELLO "

    /**
     * One peer socket: bridge cells + mirroring on the local side, bytes on
     * the wire. The hello carries the local mirror ref and, since M8.2, the
     * local peer name; a listener with an allowlist refuses unlisted peers at
     * hello time (M8.3) — the connection closes before any announcement or
     * frame is accepted. The ingress exists only after an admitted hello, and
     * stamps every delivery with the peer's identity.
     */
    internal class Session(
        private val side: Peering.Side,
        send: (ByteArray) -> Unit,
        private val refuse: () -> Unit,
    ) {
        val egress = BridgeEgressCell()
        private val mirrorRef = Peering.spawnMirror(side, toPeer = egress)

        @Volatile
        private var ingress: Propagate<ByteArray>? = null

        /**
         * T05 finding 7: binary frames arriving before an admitted hello are
         * correctly refused (nowhere to route them yet), but were previously
         * unaccounted. Counted here so a peer sending data ahead of its hello
         * — a protocol violation or a race on reconnect — is observable
         * without changing the drop itself.
         */
        private val preHelloDropCount = AtomicLong()
        val preHelloDrops: Long get() = preHelloDropCount.get()

        /** The current announcement hook — replaced on every (re)hello so reconnects don't leak stale announcers (M10.3). */
        @Volatile
        private var announcement: AutoCloseable? = null

        init {
            egress.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) =
                    try {
                        send(value)
                    } catch (e: Exception) {
                        // dead socket noticed before the close event (M10.4):
                        // unpublish now so later sends take the park fast path,
                        // and signal "destination unavailable" the way a closed
                        // intake does — the registry parks THIS invocation too
                        side.registry.unpublishRemotes(via = egress)
                        throw IntakeClosedException(egress.ref)
                    }
            }, PortRef.generate()))
        }

        fun hello(): String = HELLO + mirrorRef.id + (side.peer?.let { " ${it.name}" } ?: "")

        fun onText(message: String) {
            require(message.startsWith(HELLO)) { "unexpected text message: $message" }
            val parts = message.removePrefix(HELLO).trim().split(" ", limit = 2)
            val peer = parts.getOrNull(1)?.let { PeerId(it) }
            if (!side.admits(peer)) {
                System.err.println("[WsTransport] refusing peer $peer: not on the allowlist (spec 43)")
                refuse()
                return
            }
            ingress = Peering.hostIngress(side, fromPeer = peer)
            announcement?.close() // a re-hello (reconnect) supersedes the previous announcer
            announcement = Peering.announceTo(side, CellRef(UUID.fromString(parts[0])), via = egress)
        }

        fun onFrame(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            // enqueue only — decoding happens on the bridge host; frames
            // before an admitted hello have nowhere to go and drop (T05
            // finding 7: now counted via preHelloDropCount)
            val current = ingress
            if (current != null) current.propagate(bytes) else preHelloDropCount.incrementAndGet()
        }

        fun onClose() {
            // the announcer dies with the session — a stale hook would try the
            // dead socket on every future local publish (listener sessions are
            // per-connection, so replace-on-rehello never fires for them)
            announcement?.close()
            announcement = null
            side.registry.unpublishRemotes(via = egress)
        }
    }

    class WsListener internal constructor(port: Int, private val side: Peering.Side) :
        WebSocketServer(InetSocketAddress(port)) {

        private val sessions = ConcurrentHashMap<WebSocket, Session>()
        private val started = CountDownLatch(1)

        internal fun awaitStart(timeout: Long, unit: TimeUnit): Boolean = started.await(timeout, unit)

        override fun onStart() = started.countDown()

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val session = Session(side, { conn.send(it) }, { conn.close() })
            sessions[conn] = session
            conn.send(session.hello())
        }

        override fun onMessage(conn: WebSocket, message: String) {
            sessions[conn]?.onText(message)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            sessions[conn]?.onFrame(message)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            sessions.remove(conn)?.onClose()
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            System.err.println("[WsListener] $ex")
            ex.printStackTrace()
        }
    }

    class WsConnection internal constructor(
        uri: URI,
        side: Peering.Side,
        private val backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF,
    ) : WebSocketClient(uri) {

        private val session = Session(side, { send(it) }, { shutdown() })

        /** False once [shutdown] is called — the only way a client stays down (M10.3). */
        @Volatile
        private var reconnect = true

        /** Deliberate close: stop reconnecting, then close the socket. */
        fun shutdown() {
            reconnect = false
            close()
        }

        override fun onOpen(handshake: ServerHandshake) {
            send(session.hello())
        }

        override fun onMessage(message: String) = session.onText(message)

        override fun onMessage(bytes: ByteBuffer) = session.onFrame(bytes)

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            session.onClose() // unpublish: senders park until the re-hello re-announces
            if (!reconnect) return
            // Reconnect on [backoff] (M10.3, injectable since T12): the re-hello
            // re-runs the announcement catch-up on both sides, parked traffic
            // replays, and replicas anti-entropy through the ordinary catch-up
            // path. ponytail: retries forever — jitter and liveness probing when
            // real networks demand them.
            Thread {
                var attempt = 0
                while (reconnect && !isOpen) {
                    try {
                        Thread.sleep(backoff(attempt))
                        attempt++
                        if (reconnect && reconnectBlocking()) break
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        System.err.println("[WsConnection] reconnect attempt failed: $e")
                    }
                }
            }.apply { isDaemon = true; name = "ws-reconnect-${getURI()}" }.start()
        }

        override fun onError(ex: Exception) {
            System.err.println("[WsConnection] $ex")
        }
    }
}
