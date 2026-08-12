package civictech.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.IntakeClosedException
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryMirrorCell
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
import java.nio.channels.ServerSocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Serve peer connections on a socket the caller has **already bound**.
     *
     * The port therefore never passes back through the OS between one listener
     * and the next: a caller that must keep an endpoint reachable across a
     * listener's death binds the socket itself and hands it over, instead of
     * closing a port and asking for that exact number again (computenet-dqy.22
     * — a bind of a specific port loses to whoever holds it, and inside the OS
     * ephemeral range that is a live race no retry can win; `HeldPort` in
     * `:wire`'s tests is the shape this exists for).
     *
     * [channel] must be bound; whatever socket options it carries are the ones
     * the listener serves with — this path does not set [WsListener.isReuseAddr]
     * (java-websocket calls `setReuseAddress` on the already-bound socket, where
     * it has no effect on the existing binding). Ownership transfers with the
     * call: `listener.stop()` closes the channel.
     */
    fun listen(channel: ServerSocketChannel, side: Peering.Side): WsListener {
        require(channel.localAddress != null) { "listen(channel) needs an already-bound channel" }
        val listener = WsListener(channel, side)
        listener.start()
        check(listener.awaitStart(10, TimeUnit.SECONDS)) {
            "WebSocket listener failed to start on ${channel.localAddress}"
        }
        return listener
    }

    /** Serve peer connections on [port] (0 = ephemeral). Returns once accepting; `listener.port` is the bound port. */
    fun listen(port: Int, side: Peering.Side): WsListener {
        val listener = WsListener(port, side)
        // SO_REUSEADDR exists here so a restart can re-bind a named port whose old
        // connections are still in TIME_WAIT. An *ephemeral* bind has no port to
        // re-bind, and asking for reuse there is actively harmful (computenet-8ru):
        // `WebSocketServer` binds the wildcard address, and on BSD/macOS SO_REUSEADDR
        // lets a wildcard bind take a port another process already holds on a
        // *specific* address. Observed: `listen(0)` returned 52337 while the Gradle
        // daemon held 127.0.0.1:52337, so `ws://localhost:52337` resolved to the more
        // specific binding and the dialer handshook with Gradle until it timed out
        // ("could not connect to ws://localhost:52337" in WsTransportSmokeTest).
        listener.isReuseAddr = port != 0
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
     * stamps every delivery with the peer's identity; since V4-PEERID the
     * registry mirror is bound to that same identity at the same point, so the
     * `LocationRegistry.Remote` locations this connection installs name the
     * peer rather than only the (per-connection, reconnect-fresh) egress.
     *
     * A Session outlives its socket on the client path — [WsConnection] keeps
     * one across every reconnect — so the per-connection state that the
     * disconnect fence depends on is held per *open* rather than per Session:
     * see [mirror] and [hello] (computenet-dqy.14).
     */
    internal class Session(
        private val side: Peering.Side,
        send: (ByteArray) -> Unit,
        private val refuse: () -> Unit,
    ) {
        val egress = BridgeEgressCell()

        /**
         * The registry mirror of the *current connection instance* — minted by
         * [hello], retired by [onClose], never re-opened (computenet-dqy.14).
         *
         * **Why per open and not per [Session].** A listener builds a Session
         * per socket, so "one mirror per Session" was already one mirror per
         * connection instance there. A client does not: [WsConnection] keeps
         * one Session — hence one egress, and formerly one mirror — across
         * every reconnect. Re-opening that single mirror's gate on the re-hello
         * left a window in which a frame the *previous* connection had already
         * staged on the bridge host could be applied as though the returning
         * peer had announced it, re-installing a `LocationRegistry.Remote` for
         * a ref the peer had since dropped.
         *
         * Minting the mirror per open closes that window without a parallel
         * epoch counter, because the mirror's [CellRef] *is* the connection
         * instance's identity and the wire already carries it: an announcement
         * is addressed to the mirror ref this side put in its hello, so every
         * frame is attributed to the instance that offered that ref. A frame
         * from a superseded instance names a retired mirror whose gate is shut
         * for good, and both scheduler hops behind the socket — the ingress
         * decode and the mirror delivery — are covered by that one fact.
         *
         * **A retired mirror stays spawned, deliberately.** Despawning it would
         * turn the fence's *drop* into a *park*: `LocationRegistry.deliver`
         * parks an invocation whose target ref has no location, so a stale
         * announcement addressed to a despawned mirror would sit in the park
         * queue instead of being refused at the gate. The cost is one detached
         * cell per reconnect — measured, alongside the `BridgeIngressCell` that
         * every re-hello already leaves behind, in computenet-vzb, which owns
         * retiring a whole connection instance's cells safely.
         *
         * `@Volatile` because [hello]/[onText]/[onClose] all run on the
         * socket's IO thread while `RegistryMirrorCell.peer` is read on the
         * bridge host's scheduler thread; the reference itself must be visible
         * to whichever IO thread java-websocket hands the next callback to.
         */
        @Volatile
        private var mirror: RegistryMirrorCell? = null

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

        /**
         * Open a connection instance and return the hello that names it: the
         * fresh [mirror]'s ref plus, since M8.2, this side's peer name.
         *
         * Called from `onOpen` on both paths — once for a listener session,
         * once per (re)connect for a client one. Retiring the previous mirror
         * here as well as in [onClose] is deliberate: the fence must not depend
         * on the close callback having run, and detaching an already-detached
         * mirror is a no-op.
         *
         * The mirror is spawned before any peer name exists, because the hello
         * must carry its ref; its peer is therefore late-bound in [onText]
         * (V4-PEERID) — `RegistryMirrorCell.peer` carries the happens-before
         * argument that makes that safe.
         */
        fun hello(): String {
            mirror?.detach() // this open supersedes whatever instance came before it
            val fresh = Peering.spawnMirror(side, toPeer = egress)
            mirror = fresh
            return HELLO + fresh.ref.id + (side.peer?.let { " ${it.name}" } ?: "")
        }

        fun onText(message: String) {
            require(message.startsWith(HELLO)) { "unexpected text message: $message" }
            val parts = message.removePrefix(HELLO).trim().split(" ", limit = 2)
            val peer = parts.getOrNull(1)?.let { PeerId(it) }
            if (!side.admits(peer)) {
                System.err.println("[WsTransport] refusing peer $peer: not on the allowlist (spec 43)")
                refuse()
                return
            }
            // V4-PEERID: bind the mirror's peer BEFORE announcing, so every
            // Remote location this connection installs — including the peer's
            // own catch-up burst, which cannot start before it has seen our
            // hello — records the peer's name. Both paths now mint a fresh
            // egress-plus-mirror per connection instance (a listener a whole
            // fresh Session, a client a fresh mirror in `hello`), so the name
            // is the only part of a peer's identity that survives a reconnect.
            val instance = checkNotNull(mirror) { "onText before hello opened a connection instance" }
            instance.peer = peer
            // No re-attach: this mirror was minted by *this* connection's
            // `hello` and starts attached. That is the whole disconnect fence
            // (computenet-dqy.14). A frame the PREVIOUS connection staged on
            // the bridge host — whether still undecoded, or already decoded and
            // queued for delivery — is addressed to the previous mirror's ref,
            // which `hello`/`onClose` detached for good, so it is dropped at
            // the gate instead of re-installing a Remote for a ref the peer may
            // since have dropped. The peer's re-announcement below is a full
            // catch-up, so nothing it still holds is lost by that drop.
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
            // The peer's refs go through the mirror's own fence rather than a
            // bare `registry.unpublishRemotes(via = egress)`: this runs on the
            // socket's IO thread, while the announcements it is retracting are
            // applied two scheduler hops later on the bridge host, so an
            // announcement decoded before this close can be applied after it.
            // `detach` shuts the gate and retracts in one step, so a late
            // announcement can no longer resurrect a departed peer's locations
            // behind a dead egress (`RegistryMirrorCell.detach`). The gate
            // stays shut: the next `hello` mints a new mirror rather than
            // re-opening this one, so this connection instance is fenced off
            // permanently on the client path exactly as it is on a listener.
            mirror?.detach()
        }
    }

    class WsListener : WebSocketServer {

        private val side: Peering.Side

        internal constructor(port: Int, side: Peering.Side) : super(InetSocketAddress(port)) {
            this.side = side
        }

        /**
         * Serve on an already-bound channel — see [listen]. java-websocket 1.6.0
         * keeps a channel handed to this constructor (`doSetupSelectorAndServerThread`
         * opens one only `if (server == null)` and binds only `if (!socket.isBound())`),
         * so the caller's binding, and its socket options, survive.
         */
        internal constructor(channel: ServerSocketChannel, side: Peering.Side) : super(channel) {
            this.side = side
        }

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

        /**
         * False once [shutdown] is called (M10.3). Together with an interrupt of the
         * retry thread — which nothing actually delivers, see [scheduleReconnect] — this
         * is what keeps a client down.
         */
        @Volatile
        private var reconnect = true

        /**
         * Single-flight guard on the retry loop below (computenet-8ru).
         *
         * java-websocket reports a *failed* connect as a close: `WebSocketClient.run`
         * catches the `ConnectException` and drives `closeConnection`, so every
         * unsuccessful reconnect attempt calls [onClose] again. Spawning a retry
         * thread per close therefore multiplied loops without bound — each live loop
         * produced another loop on each of its own failures — and the loops then
         * fought over the one client: concurrent `reconnectBlocking` calls raced
         * `reset()`/`connect()` on the shared `connectReadThread` field, which threw
         * `IllegalStateException: WebSocketClient objects are not reuseable` and NPEs,
         * and a straggler's `reset()` could tear down a connection another loop had
         * just established.
         *
         * Measured before this guard: ~950 live `ws-reconnect-*` threads after 250ms
         * of listener downtime, ~2700 after 1s, and after 3s the JVM was so starved
         * that `WsTransport.listen`'s own 10s start latch expired — the transport
         * could no longer re-bind at all. That is the shape both `:wire` reconnect
         * tests were failing with in CI on a 2-core runner.
         *
         * One loop retries; concurrent closes it caused simply return. Released in a
         * `finally`, with a re-arm check for a close that arrived while the loop was
         * winding down and so found the guard still held.
         */
        private val reconnecting = AtomicBoolean(false)

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
            scheduleReconnect()
        }

        /**
         * Reconnect on [backoff] (M10.3, injectable since T12): the re-hello
         * re-runs the announcement catch-up on both sides, parked traffic
         * replays, and replicas anti-entropy through the ordinary catch-up
         * path. ponytail: retries forever — jitter and liveness probing when
         * real networks demand them.
         *
         * At most one loop runs at a time — see [reconnecting] for why that is a
         * correctness property and not an optimisation.
         */
        private fun scheduleReconnect() {
            // The `isOpen` test is what terminates the re-arm below, and it cannot swallow
            // a close: java-websocket assigns `readyState = CLOSED` only *after* it has
            // called `onClose`, so this reads the state as of the close event rather than
            // after it — and every path that reaches `onClose` has already left OPEN.
            // `WebSocketImpl.closeConnection` flips OPEN to CLOSING itself for the abnormal
            // (1006) close a dropped socket takes; a close handshake sets CLOSING before
            // the read thread's `eot()` gets there; a failed connect is still
            // NOT_YET_CONNECTED; and the one remaining OPEN-capable caller,
            // `WebSocketClient.reset()`, only ever runs on a retry thread that already
            // holds the guard, so its close is covered by that loop's own re-arm.
            // Re-check this against java-websocket's close ordering on any upgrade.
            if (!reconnect || isOpen) return
            if (!reconnecting.compareAndSet(false, true)) return // a loop is already retrying
            Thread {
                var interrupted = false
                try {
                    var attempt = 0
                    while (reconnect && !isOpen) {
                        try {
                            Thread.sleep(backoff(attempt))
                            attempt++
                            if (reconnect && reconnectBlocking()) break
                        } catch (_: InterruptedException) {
                            interrupted = true
                            break
                        } catch (e: Exception) {
                            System.err.println("[WsConnection] reconnect attempt failed: $e")
                        }
                    }
                } finally {
                    reconnecting.set(false)
                }
                if (interrupted) {
                    // Not re-armed — the behaviour M10.3 already had, kept because an
                    // interrupt can only arrive from outside this class: nothing in this
                    // repository and nothing in java-websocket 1.6.0 interrupts this
                    // thread (`onWebsocketClose` and `reset()` interrupt only the client's
                    // own write/read threads, never the retry thread). Announced rather
                    // than silent, so a dialer that has stopped retrying is never
                    // invisible — in-process and remote paths owe the same observable
                    // semantics, and a quiet give-up would break that quietly.
                    System.err.println("[WsConnection] reconnect loop interrupted; ${getURI()} will not retry")
                } else {
                    // a close that landed while this loop was winding down found the
                    // guard held and returned; nothing else will retry for it
                    scheduleReconnect()
                }
            }.apply { isDaemon = true; name = "ws-reconnect-${getURI()}" }.start()
        }

        override fun onError(ex: Exception) {
            System.err.println("[WsConnection] $ex")
        }
    }
}
