package civictech.wire

import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** Serve peer connections on [port] (0 = ephemeral). Returns once accepting; `listener.port` is the bound port. */
    fun listen(port: Int, side: Peering.Side): WsListener {
        val listener = WsListener(port, side)
        listener.isReuseAddr = true
        listener.start()
        check(listener.awaitStart(10, TimeUnit.SECONDS)) { "WebSocket listener failed to start on port $port" }
        return listener
    }

    /** Connect to a listening peer. Returns once the socket is open (hello exchange proceeds asynchronously). */
    fun connect(uri: URI, side: Peering.Side): WsConnection {
        val connection = WsConnection(uri, side)
        check(connection.connectBlocking(10, TimeUnit.SECONDS)) { "could not connect to $uri" }
        return connection
    }

    private const val HELLO = "HELLO "

    /** One peer socket: bridge cells + mirroring on the local side, bytes on the wire. */
    internal class Session(private val side: Peering.Side, send: (ByteArray) -> Unit) {
        val egress = BridgeEgressCell()
        private val ingress = Peering.hostIngress(side)
        private val mirrorRef = Peering.spawnMirror(side, toPeer = egress)

        init {
            egress.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) = send(value)
            }, PortRef.generate()))
        }

        fun hello(): String = HELLO + mirrorRef.id

        fun onText(message: String) {
            require(message.startsWith(HELLO)) { "unexpected text message: $message" }
            val peerMirror = CellRef(UUID.fromString(message.removePrefix(HELLO).trim()))
            Peering.announceTo(side, peerMirror, via = egress)
        }

        fun onFrame(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            ingress.propagate(bytes) // enqueue only — decoding happens on the bridge host
        }

        fun onClose() = side.registry.unpublishRemotes(via = egress)
    }

    class WsListener internal constructor(port: Int, private val side: Peering.Side) :
        WebSocketServer(InetSocketAddress(port)) {

        private val sessions = ConcurrentHashMap<WebSocket, Session>()
        private val started = CountDownLatch(1)

        internal fun awaitStart(timeout: Long, unit: TimeUnit): Boolean = started.await(timeout, unit)

        override fun onStart() = started.countDown()

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val session = Session(side) { conn.send(it) }
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
        }
    }

    class WsConnection internal constructor(uri: URI, side: Peering.Side) : WebSocketClient(uri) {

        private val session = Session(side) { send(it) }

        override fun onOpen(handshake: ServerHandshake) {
            send(session.hello())
        }

        override fun onMessage(message: String) = session.onText(message)

        override fun onMessage(bytes: ByteBuffer) = session.onFrame(bytes)

        override fun onClose(code: Int, reason: String?, remote: Boolean) = session.onClose()

        override fun onError(ex: Exception) {
            System.err.println("[WsConnection] $ex")
        }
    }
}
