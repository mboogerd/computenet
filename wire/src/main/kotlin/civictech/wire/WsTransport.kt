package civictech.wire

import civictech.cell.BoundaryDenial
import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.link.PeerId
import civictech.cell.membrane.AuthLevel
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.IntakeClosedException
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryMirrorCell
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.WebSocketServerFactory
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * How many consecutive connection instances may **open** and then close
     * **without ever being admitted** before a [WsConnection] stops
     * reconnecting (computenet-4gzr).
     *
     * ## The case it bounds
     *
     * A hello refused at the listener's allowlist is not a failed dial: the TCP
     * connect succeeds, the upgrade succeeds, `onOpen` fires and the hello goes
     * out — and only then does the listener refuse it and close.
     * [WsConnection.onClose] could not see the difference and called
     * `scheduleReconnect()` unconditionally; and because the retry loop
     * terminates the moment `reconnectBlocking()` returns true, every refusal
     * started a **fresh** loop at `attempt = 0`. The schedule therefore never
     * escalated past its first delay: a refused peer redialled at a fixed ~1s
     * forever, charging the refusing listener one accept plus one hello parse
     * per second from a peer it had already decided it would not talk to.
     *
     * Nothing on this wire tells the dialler it was refused — the refusal is a
     * close, and `[SEC1-06]` requires it to happen before anything crosses — so
     * the dialler has to conclude it locally, from a run of opens that never
     * reached an admitted hello ([Session.peered]).
     *
     * ## Why five, and what it does not bound
     *
     * The number is a cost ceiling rather than a semantic threshold: it is the
     * total accept-plus-hello-parse work one refused peer may charge a listener
     * per [WsConnection]. Five leaves room for the handful of unadmitted opens a
     * *transient* fault produces — a socket torn down mid-handshake, a listener
     * restarting between the upgrade and the hello — before the client concludes
     * it is being refused rather than unlucky. An **admitted** connection resets
     * the run to zero ([WsConnection.unadmittedOpens]).
     *
     * ## What the bound is, exactly, and where it is soft
     *
     * A refused peer costs a listener a handful of accepts rather than one per
     * second forever. It is **not** exactly `limit`, and the softness is worth
     * stating precisely because it is structural, not an oversight:
     *
     * **What is counted is an open that did not last.** `Session.peered` looks
     * like the signal and is not: it means "this side admitted the PEER's
     * hello", not "the peer admitted mine", and on `:wire` a refused dialler
     * routinely admits the listener's hello anyway, because [WsListener] sends
     * its hello from `onOpen`, before it has seen the peer's. A bound built on
     * it reset its own run on connections that had in fact been refused, and
     * measurably failed to bound: at `refusedDialLimit = 5` a listener still
     * paid 9 accepts and climbing. `:iroh` has no such gap — its accepting side
     * sends nothing until it has admitted the dialler's hello — which is why
     * `IrohTransport.REFUSED_DIAL_LIMIT` keys on `peered` and this does not.
     *
     * So this side keys on the one difference that is real and local: **a
     * refusal is decided inside one round trip, and an admitted peering
     * persists.** An open that closes within [REFUSAL_WINDOW_MS] counts; an open
     * that outlives it clears the run. A refused connection closes in single
     * -digit milliseconds, so the two are three orders of magnitude apart.
     *
     * **Its failure mode, stated rather than hidden:** a peering that genuinely
     * cannot stay up — five reconnects in a row each dying inside the window —
     * is abandoned as though it were refused. That is a judgement, not an
     * accident: a dialler that has opened five connections in a row and held
     * none of them for two seconds is not going to be helped by a sixth, and
     * the failure is announced ([WsConnection.abandonedAfterRefusals]) rather
     * than silent.
     *
     * **An exact signal would need the listener to say it was a refusal, and
     * neither available channel delivers reliably.** Measured over 318 refusals
     * driven at a 20ms schedule: an RFC 6455 application close code reached the
     * dialler 54 times and an abnormal `1006` the other 264, and a text line
     * sent immediately before the close was lost with it — the server's close
     * handshake does not complete before the connection drops. Making that
     * reliable is `computenet-egl.7`, and it is what would replace the window
     * below with a fact.
     *
     * It does **not** bound a dial that never opens at all. java-websocket
     * reports a failed connect as a close too, but that close is not preceded by
     * `onOpen`, costs the peer nothing, and still retries forever on purpose — a
     * listener that is down is expected back (`WsReconnectRefusedTest`,
     * computenet-dqy.27). The same distinction is drawn on the iroh transport,
     * where an unestablished dial throws inside the re-dial loop and produces no
     * link at all (`IrohTransport.REFUSED_DIAL_LIMIT`).
     */
    const val REFUSED_DIAL_LIMIT: Int = 5

    /**
     * How long an opened connection must last to clear the refused-dial run
     * (computenet-4gzr) — see [REFUSED_DIAL_LIMIT] for why a duration is the
     * discriminator here and not on `:iroh`.
     *
     * Two seconds is three orders of magnitude above what it separates: a hello
     * refused at the allowlist is closed within one round trip of being sent,
     * single-digit milliseconds on loopback and bounded by the peer's RTT
     * anywhere else. It is not a timeout anyone waits on — nothing sleeps for
     * it, it is only subtracted at close — so a loaded machine cannot turn it
     * into a delay, only into a *longer* apparent lifetime, which errs towards
     * clearing the run rather than towards abandoning a live peer.
     */
    const val REFUSAL_WINDOW_MS: Long = 2_000

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
     * A caller binding an *ephemeral* channel here owes the same address choice
     * [listen] makes for `listen(0, …)`: bind [loopback], not the wildcard, or
     * another process's specific-address binding of the same port can still take
     * the name `localhost` away from it (computenet-dqy.28).
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

    /**
     * The loopback endpoint an *ephemeral* listener binds: [port] on the address
     * the name `localhost` resolves to in this JVM, rather than the wildcard
     * (computenet-dqy.28). Public because a test fixture that binds the endpoint
     * itself — `HeldPort` in `:wire`'s tests — owes the same choice; see
     * [listen] for the measurements.
     */
    fun loopback(port: Int): InetSocketAddress = InetSocketAddress(LOOPBACK, port)

    /**
     * The address `localhost` names here.
     *
     * `getByName` returns the *first* address `localhost` resolves to, which is
     * also the first address a dialer's `Socket("localhost", port)` tries — so
     * binding it makes listener and dialer agree by construction on whichever
     * family the host prefers (127.0.0.1 on macOS and on GitHub's ubuntu runner
     * image; ::1 on an image whose hosts file puts IPv6 first). That is what
     * makes a loopback bind portable instead of a guess about `/etc/hosts`:
     * nothing here hard-codes 127.0.0.1.
     *
     * `DemoShell.LOOPBACK` in `:demo:shell` is the one other copy of this rule
     * (computenet-dqy.33, the same residual outside `:wire`). It cannot import
     * this one: `:demo:shell` depends on nothing but kotlinx-serialization by
     * design — see its `build.gradle.kts` — and `:wire` drags in `:kernel`.
     * Change one and change the other.
     */
    private val LOOPBACK: InetAddress = try {
        InetAddress.getByName("localhost")
    } catch (_: UnknownHostException) {
        InetAddress.getLoopbackAddress() // a hosts file with no `localhost` at all
    }

    /**
     * Serve peer connections on [port] (0 = ephemeral). Returns once accepting;
     * `listener.port` is the bound port.
     *
     * **An ephemeral listener binds loopback, not the wildcard**
     * (computenet-dqy.28, the residual of computenet-8ru). A wildcard binding
     * and another process's binding of the *same* port on a *specific* address
     * can coexist, and the specific one wins the name `localhost` — so a dialer
     * that resolves `localhost` reaches the stranger. Observed: `listen(0)`
     * returned 52337 while the Gradle daemon held 127.0.0.1:52337, and the
     * dialer handshook with Gradle until it timed out ("could not connect to
     * ws://localhost:52337"). Binding [loopback] instead removes the ambiguity:
     * a second binding of the same address *and* port needs SO_REUSEPORT on
     * both sockets, which no unrelated process sets.
     *
     * Measured, 20 trials each, on **both** platforms — macOS 26.6 (aarch64) and
     * Linux 6.12 (Ubuntu 26.04 container, aarch64): while our socket holds an
     * ephemeral port, a foreign `ServerSocket` with SO_REUSEADDR — Java's default
     * — binding `127.0.0.1:<that port>` succeeds **20/20 against a wildcard
     * holder on macOS**, with SO_REUSEADDR *or* SO_REUSEPORT on our side, so no
     * reuse flag ever fixed it; and **0/20 against a loopback holder**. On Linux
     * it is 0/20 in every shape — an overlapping bind there needs SO_REUSEPORT on
     * both sockets — so the defect is BSD-specific and the fix changes nothing
     * that worked. Ephemeral *selection* was ruled out separately (a loopback
     * `bind(0)` landed on one of 300 held ports 0/3000 times), the SO_REUSEPORT
     * handover `HeldPort` depends on still works on a loopback bind (20/20), and
     * the loopback endpoint is reachable as `localhost` 20/20 — all on both
     * platforms.
     *
     * A *named* port keeps the wildcard: it is an endpoint someone off-box may
     * have been told to dial, and only an ephemeral port is machine-local by
     * construction (nothing can learn the number before this process announces
     * it). SO_REUSEADDR follows the same line — it lets a restart re-bind a named
     * port whose old connections are still in TIME_WAIT, while an ephemeral bind
     * has no port to re-bind and asking for reuse there only widens what may
     * overlap it (computenet-8ru).
     */
    fun listen(port: Int, side: Peering.Side): WsListener {
        val listener = WsListener(if (port == 0) loopback(0) else InetSocketAddress(port), side)
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
     *
     * **What a give-up says** (computenet-dqy.41). `connectBlocking` returning
     * false is the only signal the caller ever gets — the [WsConnection] never
     * escapes this method — so the exception carries what that connection saw:
     * how far the dial got ([WsConnection.dialDiagnosis]'s `readyState`) and the
     * close code, reason and origin the client observed, or that it observed no
     * close at all. Without it the message named the URI and nothing else, and a
     * dial that was answered-then-dropped read exactly like one that timed out
     * mid-handshake. Diagnostics only: the retry, the timeout and the give-up
     * condition are unchanged, and the diagnosis is built inside `check`'s lazy
     * message, so a successful connect never pays for it.
     */
    fun connect(
        uri: URI,
        side: Peering.Side,
        backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF,
    ): WsConnection = connect(uri, side, backoff, REFUSED_DIAL_LIMIT)

    /**
     * [connect] with the refused-dial bound injected — see
     * [REFUSED_DIAL_LIMIT] for what it bounds.
     *
     * **A separate overload rather than a fourth defaulted parameter**, because
     * a parameter after [backoff] would silently capture every trailing-lambda
     * call site in the repository: `connect(uri, side) { 5_000L }` would bind
     * the schedule to an `Int`. Twenty-three of them exist across `:wire`'s own
     * suite, and each would have failed to compile. This overload takes no
     * defaults, so a two- or three-argument call — including a trailing lambda —
     * still resolves unambiguously to the one above.
     */
    fun connect(
        uri: URI,
        side: Peering.Side,
        backoff: (attempt: Int) -> Long,
        refusedDialLimit: Int,
    ): WsConnection {
        awaitReachable(uri, backoff)
        val connection = WsConnection(uri, side, backoff, refusedDialLimit)
        check(connection.connectBlocking(10, TimeUnit.SECONDS)) {
            "could not connect to $uri — ${connection.dialDiagnosis()}"
        }
        return connection
    }

    /**
     * The floor under a probe's connect timeout (computenet-auq).
     *
     * Two reasons it cannot simply be `backoff(attempt)`. A schedule may return
     * **0**, which `Socket.connect` reads as *no timeout* — precisely the defect
     * this bounds — and every reconnect test here injects exactly that. And a
     * legitimate loopback connect is not free: 8ms measured for a first dial on
     * this host, so a 10ms schedule used verbatim would time out a peer that is
     * up and answering, turning the probe into an unterminating loop. One second
     * is the production schedule's own first interval, so nothing about
     * [DEFAULT_RECONNECT_BACKOFF] is changed by the floor.
     */
    internal const val MIN_PROBE_TIMEOUT_MS = 1_000L

    /**
     * The connect timeout [WsConnection]'s own dial carries — the second untimed
     * site the computenet-auq audit found in this file.
     *
     * `WebSocketClient`'s single-argument constructor leaves `connectTimeout` at
     * **0**, and 1.6.0's `WebSocketClient.run` dials `socket.connect(addr,
     * connectTimeout)` with it, so its socket blocks for the OS timeout on a
     * black-holed peer exactly as the probe did. [connect]'s
     * `connectBlocking(10, SECONDS)` bounds only the *caller's* wait, not that
     * socket — and the reconnect loop's `reconnectBlocking()` (see
     * [WsConnection.scheduleReconnect]) is untimed, so there the OS timeout
     * displaced the reconnect backoff outright. Matching the existing 10s
     * give-up keeps every bound in this file the same number; a refused or
     * answered dial never reaches it.
     */
    private const val DIAL_TIMEOUT_MS = 10_000

    /**
     * Retry a bare TCP probe on [backoff] until [uri] answers.
     *
     * **The dial is timed** (computenet-auq). An untimed `Socket(host, port)`
     * blocks for the *OS* connect timeout against an address that drops SYNs
     * rather than refusing — measured 7.79/7.83/7.86s against a bound socket
     * with a full accept queue on macOS 15 / JDK 21, and 75s+ is reachable on a
     * real network. That does not delay the backoff schedule, it **bypasses**
     * it: every attempt costs the OS timeout no matter what the schedule says.
     * Deriving the timeout from the interval the schedule is about to sleep
     * makes a dropped SYN cost one interval instead (floored by
     * [MIN_PROBE_TIMEOUT_MS]).
     *
     * A *refused* connection is unaffected — a RST comes back in well under a
     * millisecond and no timeout is ever reached — which is what every loopback
     * caller here exercises.
     *
     * **[backoff] is consulted once per attempt, immediately *before* that
     * attempt's dial** — it has to be, since it supplies the dial's timeout —
     * and the value it returns is also what the loop sleeps if that dial fails.
     * So `backoff(0)` is now called even when the very first probe succeeds, and
     * an invocation with `attempt == 0` says nothing about whether the port
     * answers; only `attempt >= 1` reports a failed probe. A schedule with side
     * effects must key on the attempt number rather than on being called at all
     * (see `WsConnectRaceTest`). The sleeps themselves are unchanged: attempt
     * *n*'s failure still sleeps `backoff(n)`.
     *
     * `internal` only so [WsProbeTimeoutTest] can dial a black hole directly;
     * [connect] is the production entry.
     */
    internal fun awaitReachable(uri: URI, backoff: (attempt: Int) -> Long) {
        var attempt = 0
        while (true) {
            val interval = backoff(attempt++)
            try {
                Socket().use { probe ->
                    probe.connect(
                        InetSocketAddress(uri.host, uri.port),
                        interval.coerceAtLeast(MIN_PROBE_TIMEOUT_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
                return
            } catch (_: IOException) {
                Thread.sleep(interval)
            }
        }
    }

    /**
     * The legacy hello's prefix, **trailing space included** — frozen bytes
     * (`[DSC1-HELLO-10]`).
     *
     * `HelloProtocol.LEGACY_HELLO_PREFIX` is a deliberate mirror of this
     * constant, documented there as safe only because these bytes never change.
     * The two are **not** unified here: `[DSC1-HELLO-10]`'s whole content is
     * that the legacy line is untouched, and a constant that two files agree on
     * by construction would be a change to the very declaration this
     * requirement freezes. The mirror carries the argument; this stays private
     * to the transport that owns the legacy grammar.
     */
    private const val HELLO = "HELLO "

    /**
     * The replay memory a peering side gets: the window its own
     * [PeerAuthPolicy.RequireAuthenticated] configured, or the default window
     * for an [PeerAuthPolicy.Open] side that will never consult it
     * (`[DSC1-HELLO-11]`, epic §9.8).
     *
     * A **listener** shares one guard across every connection it accepts —
     * that is not an optimization but the requirement: a replayed hello arrives
     * on a *new* socket, so per-`Session` state could never see the acceptance
     * it is replaying (the same reason `WsListener.admissionSink` is shared).
     * A [WsConnection] keeps one Session for its whole life, so a per-Session
     * guard is already durable there.
     */
    private fun replayGuardFor(side: Peering.Side): HelloReplayGuard =
        (side.auth as? PeerAuthPolicy.RequireAuthenticated)
            ?.let { HelloReplayGuard(retentionMillis = it.nonceRetentionMillis) }
            ?: HelloReplayGuard()

    /**
     * The Ed25519 public key a `HELLO2` presented, or null when those bytes are
     * not an Ed25519 X.509/SubjectPublicKeyInfo encoding at all.
     *
     * Total over hostile input — every `GeneralSecurityException` and every
     * `IllegalArgumentException` the key factory can raise becomes null — for
     * the reason `civictech.identity.Ed25519.verify` is total: the presented key
     * is an attacker-chosen byte string arriving at an ingress seam, so it must
     * not be able to raise anything at the admission point.
     *
     * The **wide** `EdDSA` key factory parses it and [Ed25519.isEd25519] then
     * rejects the wrong curve, so an Ed448 key is a distinguishable refusal
     * rather than an indistinguishable parse failure (`Ed25519.KEY_FACTORY`'s
     * own KDoc states that discipline; this follows it rather than inventing a
     * second one).
     */
    private fun decodeEd25519PublicKey(spki: ByteArray): PublicKey? {
        val key = try {
            KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePublic(X509EncodedKeySpec(spki))
        } catch (_: InvalidKeySpecException) {
            return null
        } catch (_: GeneralSecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return key.takeIf { Ed25519.isEd25519(it) }
    }

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
        private val socketBuffered: () -> Boolean = { false },
        /**
         * Seam-1 accounting sink for a refused hello (spec 40/43 seam 1,
         * `[SEC1-07]`; computenet-usd.4.2), the wire half of the
         * accounted-refusal pattern `BridgeIngressCell` established for a
         * refused frame (computenet-usd.4.1).
         *
         * Supplied by the caller rather than allocated here, because a
         * listener's Session does not outlive the refusal it records: a
         * refused hello's [refuse] closes the connection, and the listener
         * removes this very Session from its `sessions` map on the resulting
         * `onClose` — see [WsListener.admissionDenials]'s KDoc. A sink owned
         * by this Session would be discarded with it, losing exactly the
         * count this seam exists to keep. [WsListener] therefore allocates
         * one sink and hands it to every Session it opens, so the count
         * survives every individual connection's removal. A [WsConnection]
         * keeps one Session for its whole life (see [mirror]'s KDoc), so the
         * default here — a private, freshly allocated sink — is already
         * durable for that path.
         */
        private val admissionSink: BoundaryDenialSink = BoundaryDenials().sinkFor("hello"),
        /**
         * How this Session writes a **text** message of its own accord —
         * needed by exactly one message, the `PROOF` that answers a peer's
         * `HELLO2` challenge (`[DSC1-HELLO-04]`).
         *
         * The hello itself does not need this: [hello] *returns* its line and
         * the caller sends it from `onOpen`. A `PROOF`, by contrast, is produced
         * while handling an inbound message, so the state machine has to be able
         * to write one without its caller knowing the protocol — which is what
         * keeps the whole trust decision inside [onText] rather than smeared
         * across two `onMessage` overrides.
         *
         * The default **throws** rather than dropping the line. A Session with
         * no text sender cannot complete an authenticated handshake, and a
         * silent no-op would present that as a peer that never answered — the
         * hardest possible reading of a wiring mistake. Both production paths
         * ([WsListener.onOpen], [WsConnection]) pass a real sender; the default
         * exists for the tests that construct a Session directly to drive the
         * legacy path, which never reaches it.
         */
        private val sendText: (String) -> Unit = {
            throw IllegalStateException(
                "this Session was built without a text sender, so it cannot answer a HELLO2 challenge: " +
                    "pass sendText when the side holds credentials",
            )
        },
        /**
         * The hellos this side has already accepted, as a bounded replay memory
         * (`[DSC1-HELLO-11]`) — see [replayGuardFor] for why a listener shares
         * one across every connection it accepts while a [WsConnection] owns its
         * own.
         */
        private val replayGuard: HelloReplayGuard = replayGuardFor(side),
    ) {
        init {
            // The :wire enforcement point for "RequireAuthenticated implies
            // signed AND verified announcements" — see requireAnnouncementIdentity.
            requireAnnouncementIdentity(side)
        }

        /**
         * This side's announcement signer, borrowed from the `Peering.Side` so
         * the per-peer-identity counter survives the fresh egress a reconnect
         * mints ([DSC1-ANN-04], epic §9.3). Null on a side with no signing
         * configuration, which encodes byte-identically to before the feature.
         */
        val egress = BridgeEgressCell(signer = side.announcementSigner)

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
         * Whether the **current connection instance** admitted a peer
         * (computenet-4gzr).
         *
         * **It is NOT what the refused-dial bound counts**, and the distinction
         * is the whole reason [WsTransport.REFUSAL_WINDOW_MS] exists: this flag
         * says "*this* side admitted the PEER's hello", which on `:wire` is
         * routinely true for a dialler that was itself refused, because
         * [WsListener] sends its hello from `onOpen` before it has seen the
         * peer's. [WsConnection] therefore counts opens that did not outlive
         * [WsTransport.REFUSAL_WINDOW_MS], not opens where this reads false —
         * see [WsTransport.REFUSED_DIAL_LIMIT] for the measurement that settled
         * it. Exposed as [peered] for parity with
         * `IrohTransport.Session.peered`, where the same question *is* the
         * refusal signal; nothing on this transport reads it yet.
         *
         * Per *open*, like [mirror], [pending], [achieved] and [localNonce], and
         * for the same reason: a client keeps one Session across every
         * reconnect, so a lifetime flag would say nothing about the instance
         * that just closed. [hello] clears it; [bindAndAnnounce] — the one place
         * an ingress is ever installed, on all three admission paths — sets it.
         *
         * Deliberately not derived from `ingress != null`: [onClose] leaves
         * `ingress` alone on the client path, so it would still read true for a
         * connection instance that is already dead. Nor from `achieved != null`,
         * which happens to hold the same value today but means something else
         * ("which auth level this instance reached") and is free to stop.
         */
        @Volatile
        private var admitted = false

        /** @see admitted */
        val peered: Boolean get() = admitted

        /**
         * The nonce this side put in the `HELLO2` of the **current** connection
         * instance; null when this side sent a legacy hello (it holds no
         * credentials).
         *
         * Per *open*, exactly like [mirror] and for a second reason on top of
         * the fence: `[DSC1-HELLO-02]` requires a distinct freshly generated
         * nonce for every hello, *including each re-hello after a reconnect*.
         * [hello] draws one from `generateHelloNonce` and overwrites this on
         * every open, and there is no other writer — so the challenge a
         * returning peer must answer is never the one it answered before, and
         * a `PROOF` captured from the previous connection cannot verify against
         * this one.
         */
        @Volatile
        private var localNonce: ByteArray? = null

        /**
         * A peer's `HELLO2` that parsed and whose claimed id matched the key it
         * presented, held between `HELLO2` receipt and `PROOF` receipt.
         *
         * Everything here is **derived, not claimed**: [derivedId] is
         * `fingerprint(publicKey)` and [publicKey] is the key the `HELLO2`
         * actually carried, so the `PROOF` is verified under the key whose
         * fingerprint the peer is being held to — never under a key chosen
         * later, and never against the id the hello merely asserted
         * (`[DSC1-HELLO-06..07]`).
         */
        private class PendingHello(val hello: Hello2, val derivedId: PeerId, val publicKey: PublicKey)

        /**
         * The peer's accepted `HELLO2` for the current open, or null before one
         * arrives. Also the **out-of-order detector**: a second `HELLO2` finds
         * it non-null, and a `PROOF` with no preceding `HELLO2` finds it null.
         */
        @Volatile
        private var pending: PendingHello? = null

        /**
         * The authentication level this connection instance actually reached, or
         * null while no peer has been admitted on it — the wire-level
         * observation of `[DSC1-HELLO-04]`/`[DSC1-HELLO-09]`.
         *
         * `AuthLevel.Authenticated` means a `PROOF` verified under the key whose
         * fingerprint this side bound; `AuthLevel.TransportVouched` means the
         * peer asserted a name (legacy) or presented a key this side could not
         * challenge (it holds no credentials), and the transport vouched for it.
         *
         * **Read-only here, and carried elsewhere by value, not by reading
         * this field.** Since `computenet-ssa.3.2` the achieved level *is*
         * plumbed into `currentPrincipal()` — a crossing's `Principal.Peer`
         * reports `Authenticated` (`[DSC1-HELLO-05]`) — but the carrier is
         * [bindAndAnnounce]'s `achieved` parameter, decided at the admission
         * row and handed to `Peering.hostIngress` as an immutable stamp. This
         * property is written from the same value, in the same call, purely so
         * the handshake's outcome stays *observable* at the transport. Nothing
         * downstream reads it, and nothing should: a mutable field read after
         * installation could report a level a delivery never saw, which is
         * exactly the happens-before `[DSC1-HELLO-13]` rules out.
         */
        @Volatile
        private var achieved: AuthLevel? = null

        /** @see achieved */
        val achievedAuthLevel: AuthLevel? get() = achieved

        /**
         * The last hello refusal this connection instance recorded — its
         * [DenialReason], the peer id it was attributed to, and its detail
         * string (`[DSC1-OBS-01]`, `[DSC1-OBS-04]`).
         *
         * **Why the record is kept here at all.** [admissionSink] is
         * deliberately reporter-less on this seam (see [admissionDenialCount]),
         * and `BoundaryDenials.attachReporter` is `internal` to `:kernel`, so
         * without this the *reason* a hello was refused would be observable
         * nowhere outside the kernel — only the count would be, and a count
         * cannot show that an `AUTH_REQUIRED` downgrade attempt is
         * distinguishable from a `NOT_ADMITTED` allowlist refusal, which is
         * exactly what `[DSC1-HELLO-09]` requires of it.
         *
         * **One record, not a log.** Every refusal calls [refuse], which closes
         * the connection, so a connection instance has at most one refusal worth
         * reading — and holding a list would give a hostile peer a way to grow
         * this side's memory one reconnect at a time. The monotonic
         * [admissionDenialCount] is what accumulates.
         *
         * Contains no nonce, no signature byte and no key material, because the
         * detail strings it holds do not — see [refuseHello].
         */
        @Volatile
        var lastAdmissionDenial: BoundaryDenial? = null
            private set

        /**
         * T05 finding 7: binary frames arriving before an admitted hello are
         * correctly refused (nowhere to route them yet), but were previously
         * unaccounted. Counted here so a peer sending data ahead of its hello
         * — a protocol violation or a race on reconnect — is observable
         * without changing the drop itself.
         */
        private val preHelloDropCount = AtomicLong()
        val preHelloDrops: Long get() = preHelloDropCount.get()

        /**
         * Announcements this connection instance's mirror refused at a shut
         * gate (computenet-dqy.40) — see [RegistryMirrorCell.refusedAnnouncements].
         *
         * Reads the *current* instance only, which is the whole point: a
         * superseded mirror's refusals belong to the connection that was
         * fenced off, and a diagnosis asks what the live connection is doing.
         */
        val refusedAnnouncements: Long get() = mirror?.refusedAnnouncements ?: 0L

        /**
         * Monotonic count of hellos refused at [side]'s allowlist, read
         * through [admissionSink] (spec 40/43 seam 1, `[SEC1-07]`) — the
         * hello-half counterpart of `BridgeIngressCell.boundaryDenials`'s
         * frame-half counter (computenet-usd.4.1). Reporter-less
         * deliberately: a refused hello has no hosted cell to attach a
         * [civictech.cell.BoundaryDenialReporter] to, and `BoundaryDenials.kt`'s
         * own contract says what is lost host-free is the dead-letter
         * *record*, not the count — see [BoundaryDenials.attachReporter].
         * Spawning a cell just to get a reporter would be disproportionate to
         * an audit counter.
         *
         * Read directly on [WsConnection.admissionDenialCount] (one Session
         * for the connection's whole life, like [preHelloDrops]); read
         * directly on [WsListener.admissionDenialCount] too — **not** summed
         * over `sessions` the way [preHelloDrops] is, because [admissionSink]
         * is the *same shared sink* on every Session this listener opens (see
         * its constructor parameter KDoc), so summing would multiply-count a
         * refusal once per still-live session sharing it.
         */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /** The current announcement hook — replaced on every (re)hello so reconnects don't leak stale announcers (M10.3). */
        @Volatile
        private var announcement: AutoCloseable? = null

        /**
         * computenet-dqy.68's fifth instrument: the announcement channel's two
         * ends, counted where the previous four could not see.
         *
         * The nine occurrences in run 31756952711 read zero on *every* existing
         * instrument — nothing parked, nothing staged on either bridge host, no
         * pre-hello drop, no gate refusal, `stderr <silent>` — while the client
         * held a strict PREFIX of the announcing side's `localRefs()` order in
         * all nine (re-derived from the retained artifacts in review; three of
         * the nine lost every ref and so are suffixes trivially, the other six
         * join at p = (1/3)^5 x 1/4 ~ 1.0e-3 under an order-blind null), so
         * whatever this is, it is ordered rather than per-ref. Read that as "a
         * contiguous run of announcements stopped", not as "the sweep truncated":
         * the printed order is the registry's iteration order *at report time*,
         * and it coincides with `announceTo`'s send order only for refs already
         * published when the sweep ran — the mirror and the ingress race it and
         * may travel the `onLocalPublish` hook instead (see
         * `WsAnnouncementStressTest.diagnose`). Zero everywhere is compatible
         * with three different truncation points and the artifacts cannot
         * separate them:
         *
         * 1. **above the socket** — the sweep, the proxy hop, or the bridge
         *    host's dispatch of [egress] stopped producing frames;
         * 2. **at the socket** — frames were handed to java-websocket and never
         *    reached the peer's `onMessage`;
         * 3. **below the peer's socket** — frames arrived at [onFrame] and were
         *    lost between the bridge ingress and the registry mirror.
         *
         * [framesSent] is incremented once per frame this side handed to the
         * transport *without the write throwing*; [framesReceived] once per
         * binary frame this side routed into its ingress. Together they cut the
         * three apart with no attribution and no reasoning: in the stress probe's
         * shape a healthy iteration is server `framesSent=3` / client
         * `framesReceived=3`, so `sent=3 received=2` is case 2, `sent=2` is case 1,
         * and `sent=3 received=3` with an empty mirror is case 3.
         *
         * [socketBuffered] is the same question one layer lower and is the
         * socket-level analogue of the `staged` depth: java-websocket accepts a
         * frame into its per-connection out-queue and writes it from another
         * thread, so a write demand that is lost leaves the frame queued with
         * `send` having returned normally — [framesSent] counts it, the peer
         * never sees it, and nothing anywhere throws.
         *
         * THE READING CAME BACK, and it is unanimous. Run 31770947583 (500
         * fresh-JVM ubuntu `:wire` iterations at 3dd7e0e) reproduced this bead's
         * signature nine times, and all nine read **case 2**:
         *
         * ```
         * frames: server->client sent=3 received=K / client->server sent=2 received=2;
         *         socket out-queue non-empty: listener=true client=false
         * ```
         *
         * with K = 0 (x3), 1 (x2), 2 (x4), equal in every one of the nine to the
         * client's `remoteRefs` count. So the announcer produced every frame
         * (kills case 1), every frame that arrived was applied (kills case 3),
         * and the missing ones are still sitting in the LISTENER's out-queue 15
         * seconds later while the reverse direction delivers 2/2 on the same
         * connection — they were never written to the wire.
         *
         * The lost write demand is java-websocket 1.6.0's, and the race is
         * readable in its source. `WebSocketImpl.write` does
         * `outQueue.add(buf); wsl.onWriteDemand(this)` on the CALLING thread;
         * `WebSocketServer.onWriteDemand` sets `interestOps(OP_READ|OP_WRITE)`
         * and wakes the selector; the selector thread's `doWrite` does
         * `if (batch(conn, channel) && key.isValid()) key.interestOps(OP_READ)`.
         * A sender that enqueues and arms OP_WRITE in the window after `batch`
         * drained the queue and returned true, but before `doWrite` clears the
         * interest, loses: the frame stays in `outQueue`, nothing ever registers
         * write interest for it again, the `wakeup` only releases a `select`
         * with nothing to write, and the connection stays open and fully
         * READABLE. Everything queued afterwards stays queued too — exactly the
         * contiguous-tail shape, cut at whichever announcement lost the race.
         *
         * It is one-directional by construction, and the artifacts agree:
         * `WebSocketClient.onWriteDemand` is `// nothing to do` because the
         * dialer writes from a dedicated thread blocking on `outQueue.take()`,
         * so the dialer cannot lose a demand. All nineteen observed occurrences
         * (nine here, nine on run 31756952711, one local) lose server->client.
         *
         * Naming this was computenet-dqy.68's job. **The repair is
         * computenet-dqy.69 and it lives in [WsListener.sweepWriteDemand]**: the
         * listener re-arms write interest whenever it finds a connection holding
         * queued bytes with no `OP_WRITE` registered. These counters stay,
         * because they are what a future occurrence would be read with.
         */
        private val framesSentCount = AtomicLong()
        val framesSent: Long get() = framesSentCount.get()

        /** @see framesSent */
        private val framesReceivedCount = AtomicLong()
        val framesReceived: Long get() = framesReceivedCount.get()

        /** @see framesSent */
        val socketHasBufferedData: Boolean get() = socketBuffered()

        init {
            egress.outlet.subscribe(Use.fixed(object : Propagate<ByteArray> {
                override fun propagate(value: ByteArray) {
                    try {
                        send(value)
                        framesSentCount.incrementAndGet()
                    } catch (e: Exception) {
                        // dead socket noticed before the close event (M10.4):
                        // unpublish now so later sends take the park fast path,
                        // and signal "destination unavailable" the way a closed
                        // intake does — the registry parks THIS invocation too
                        side.registry.unpublishRemotes(via = egress)
                        throw IntakeClosedException(egress.ref)
                    }
                }
            }, PortRef.generate()))
        }

        /**
         * Open a connection instance and return the hello that names it.
         *
         * **Which of the two hellos this is depends on one thing only: whether
         * this side holds credentials** (`Peering.Side.credentials`).
         *
         * - No credentials: the legacy `HELLO <mirrorRef>[ <peerName>]` line,
         *   byte for byte as before (`[DSC1-HELLO-10]`). A side that never
         *   mentions authentication cannot get any, and cannot emit a byte of a
         *   new grammar.
         * - Credentials: `HELLO2 <mirrorRef> <derivedId> <key> <nonce>` — the
         *   id it claims is `credentials.peerId`, i.e. the fingerprint of the
         *   very key it presents, because that is the only claim a peer can
         *   hold it to (`[DSC1-HELLO-03]`, `[DSC1-HELLO-06]`). `Side.peer` is
         *   deliberately *not* used here: a name nothing derives from a key
         *   cannot be proven, so an authenticated peering names peers by
         *   fingerprint and by nothing else.
         *
         * The policy — `PeerAuthPolicy.Open` vs `RequireAuthenticated` — does
         * not enter here. It governs what this side *accepts*, not what it
         * sends; a side may hold a keypair and still admit unauthenticated
         * peers (`Side.credentials`' own KDoc).
         *
         * A fresh nonce is drawn on **every** call, hence on every re-hello
         * after a reconnect (`[DSC1-HELLO-02]`) — see [localNonce].
         *
         * Called from `onOpen` on both paths — once for a listener session,
         * once per (re)connect for a client one. Retiring the previous mirror
         * here as well as in [onClose] is deliberate: the fence must not depend
         * on the close callback having run, and detaching an already-detached
         * mirror is a no-op. The per-open handshake state ([pending],
         * [achieved], [localNonce]) is reset here for the same reason the mirror
         * is minted here: nothing a superseded connection instance proved may
         * carry into this one.
         *
         * The mirror is spawned before any peer name exists, because the hello
         * must carry its ref; its peer is therefore late-bound in [onText]
         * (V4-PEERID) — `RegistryMirrorCell.peer` carries the happens-before
         * argument that makes that safe, and [bindAndAnnounce] below re-derives
         * it for the authenticated path, where the binding moves one message
         * later.
         */
        fun hello(): String {
            mirror?.detach() // this open supersedes whatever instance came before it
            val fresh = Peering.spawnMirror(side, toPeer = egress)
            mirror = fresh
            pending = null
            achieved = null
            admitted = false
            val credentials = side.credentials
            if (credentials == null) {
                localNonce = null
                return HELLO + fresh.ref.id + (side.peer?.let { " ${it.name}" } ?: "")
            }
            val nonce = generateHelloNonce()
            localNonce = nonce
            return encodeHello2(Hello2(fresh.ref.id, credentials.peerId, credentials.publicKey, nonce))
        }

        /**
         * **The single admission point.** Every trust decision this transport
         * makes about a peer is taken here, between parsing a text message and
         * `Peering.Side.admits` — and no ingress, no bound mirror and no
         * announcer exists on any path that does not reach the end of one of the
         * three handlers below (`[DSC1-HELLO-13]`).
         *
         * The dispatch is by prefix, and the prefixes cannot collide: the legacy
         * one is `"HELLO "` *with* a trailing space, so `"HELLO2 …"` is never a
         * legacy hello and can never have a token absorbed into a peer *name*
         * (epic §9.1 — see `HelloProtocol`'s file KDoc for the whole argument).
         *
         * A text message that is none of the three keeps its previous
         * behaviour exactly — the `require` in [onLegacyHello] throws
         * "unexpected text message", reported by the listener's `onError` with
         * the socket left open (`WsAnnouncementSilenceInventoryTest` pins it).
         * That is deliberately *not* converted into a `MALFORMED_HELLO` denial:
         * the decided matrix classifies out-of-order and duplicate
         * `HELLO2`/`PROOF` messages, not junk that was never in either grammar,
         * and rewriting the junk path would change `Open`-side behaviour that
         * `[DSC1-HELLO-10]` freezes.
         */
        fun onText(message: String) = when {
            message.startsWith(HELLO2_PREFIX) -> onAuthenticatedHello(message)
            message.startsWith(PROOF_PREFIX) -> onProof(message)
            else -> onLegacyHello(message)
        }

        /**
         * A legacy name-only hello: today's path under `PeerAuthPolicy.Open`,
         * an `AUTH_REQUIRED` refusal under `RequireAuthenticated`.
         *
         * The refusal is what `[DSC1-HELLO-08..09]` demands, and its *reason* is
         * the substance: a downgrade attempt is `AUTH_REQUIRED`, machine-
         * distinguishable from the `NOT_ADMITTED` of a peer that is simply not
         * on the allowlist. No peer is admitted at `TransportVouched` on this
         * path while the policy stands, which is that requirement's WHILE
         * clause — there is no branch below the check that could.
         */
        private fun onLegacyHello(message: String) {
            require(message.startsWith(HELLO)) { "unexpected text message: $message" }
            val parts = message.removePrefix(HELLO).trim().split(" ", limit = 2)
            val peer = parts.getOrNull(1)?.let { PeerId(it) }
            if (side.auth !is PeerAuthPolicy.Open) {
                refuseHello(
                    DenialReason.AUTH_REQUIRED,
                    peer,
                    "legacy name-only hello from $peer refused: this side requires an authenticated hello",
                )
                return
            }
            if (!admitted(peer)) return
            bindAndAnnounce(peer, UUID.fromString(parts[0]), AuthLevel.TransportVouched)
        }

        /**
         * A `HELLO2`: parse strictly, hold the peer to the key it presented,
         * and — when this side can answer a challenge — send the `PROOF` that
         * lets the peer hold *us* to ours. **Nothing is admitted here on a
         * credentialed side**; admission waits for the peer's `PROOF` in
         * [onProof].
         *
         * The step order is the decided one and each step's refusal reason is
         * load-bearing:
         *
         * 1. **Parse** (`MALFORMED_HELLO`) — a malformed line never yields a
         *    `PeerId`, so nothing downstream can be reached with a half-read
         *    hello.
         * 2. **Derive and compare** (`ID_MISMATCH`, recording *both* ids) —
         *    `[DSC1-HELLO-06]`. From here on only the derived id is used; the
         *    claimed string is kept for the refusal record and for nothing else.
         * 3. **Replay** (`REPLAY`) — `[DSC1-HELLO-11]`, and it is checked
         *    **before any signature work**, ours or the peer's. A replayed
         *    `HELLO2` carries the nonce this side already accepted, and the
         *    `PROOF` that follows it could only ever fail verification (our
         *    challenge nonce is fresh per open) — so a replay reached through a
         *    signature check would be classified `BAD_SIGNATURE`, naming the
         *    wrong defect. Checking the nonce first is what makes a replay
         *    *legible as* a replay.
         * 4. **Answer** — sign the challenge with this side as signer and write
         *    the `PROOF`. No admission, no ingress, no announcement.
         */
        private fun onAuthenticatedHello(message: String) {
            val hello = when (val parse = parseHello2(message)) {
                is HelloParse.Ok -> parse.message
                is HelloParse.Malformed -> {
                    refuseHello(parse.reason, null, "malformed HELLO2 refused (${parse.kind}): ${parse.detail}")
                    return
                }
            }
            if (pending != null || achieved != null) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    hello.claimedPeerId,
                    "a second HELLO2 on one connection instance refused as out of order",
                )
                return
            }
            val key = decodeEd25519PublicKey(hello.publicKeySpki)
            if (key == null) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    hello.claimedPeerId,
                    "HELLO2 claiming ${hello.claimedPeerId.name} refused: the presented bytes are not an " +
                        "Ed25519 X.509 public key",
                )
                return
            }
            val derived = fingerprint(key)
            if (derived != hello.claimedPeerId) {
                refuseHello(
                    DenialReason.ID_MISMATCH,
                    hello.claimedPeerId,
                    "HELLO2 claims ${hello.claimedPeerId.name} but the presented key derives ${derived.name}",
                )
                return
            }
            if (replayGuard.hasSeenNonce(derived, hello.nonce)) {
                refuseHello(
                    DenialReason.REPLAY,
                    derived,
                    "HELLO2 from ${derived.name} refused: it replays a hello this side already accepted " +
                        "inside the retention window",
                )
                return
            }
            val credentials = side.credentials
            if (credentials == null) {
                // The uncredentialed row. The policy is necessarily Open —
                // `Peering.Side`'s init require makes RequireAuthenticated
                // without credentials unconstructable — so this is a peer that
                // authenticates itself to a side which cannot reciprocate.
                //
                // What it gets is the *derived* id at TransportVouched: the
                // claim was checked against the key (step 2 above), which is
                // strictly more than a legacy hello proves, but no challenge was
                // answered because this side never sent one. No PROOF is
                // possible and none is claimed.
                //
                // What the PEER does in return is decided by *its* policy, not
                // by this row: the hello this side sent it was the legacy line,
                // so a peer never waits on a PROOF from an unkeyed side — it
                // was never offered a HELLO2 to answer. Both outcomes were
                // measured in review (2026-08-19, darwin/arm64):
                //
                // - a credentialed `Open` peer reads that legacy line on its
                //   own legacy row and admits this side at TransportVouched, so
                //   the peering is symmetric at TransportVouched and
                //   announcements flow BOTH ways;
                // - a `RequireAuthenticated` peer refuses it AUTH_REQUIRED
                //   (`[DSC1-HELLO-08]`) and closes, so nothing is peered in
                //   either direction.
                //
                // Neither outcome is a half-open peering, and neither is a
                // defect of this path: an unkeyed side cannot prove anything,
                // which is exactly what its configuration says. The
                // mixed-version proofs are a sibling item's.
                if (!admitted(derived)) return
                pending = PendingHello(hello, derived, key)
                bindAndAnnounce(derived, hello.mirrorRef, AuthLevel.TransportVouched)
                return
            }
            // Held BEFORE the PROOF is written: the peer's answer arrives on the
            // same socket and is dispatched to this Session after this call
            // returns, so `pending` is always visible to the [onProof] that
            // answers this exchange.
            pending = PendingHello(hello, derived, key)
            sendText(encodeProof(Proof(credentials.sign(helloChallengeBytes(ourProofChallenge(credentials))))))
        }

        /**
         * A `PROOF`: the peer's answer to the challenge this side sent, and the
         * **admission point for an authenticated peering**.
         *
         * Order, again decided rather than chosen here:
         *
         * 1. **State** (`MALFORMED_HELLO`) — a `PROOF` with no preceding
         *    `HELLO2`, a second `PROOF`, or a `PROOF` at a side that holds no
         *    credentials (and therefore issued no challenge) is an out-of-order
         *    protocol message.
         * 2. **Parse** (`MALFORMED_HELLO`).
         * 3. **Replay** (`REPLAY`) — before verification, so a signature this
         *    side has already accepted is named a replay rather than reaching
         *    the verifier at all.
         * 4. **Verify** (`BAD_SIGNATURE`) — under the key the `HELLO2`
         *    presented, over the challenge with the **peer** as signer
         *    ([ourProofChallenge]`.mirrored()`), which is the same byte string
         *    the peer signed and a *different* one from what this side signs.
         *    Reflecting our own `PROOF` back therefore cannot verify
         *    (`[DSC1-HELLO-07]`, `HelloProtocol.helloChallengeBytes`).
         * 5. **Admit** (`NOT_ADMITTED`) — `Side.admits` evaluated on the
         *    **derived** id, never the claimed string (`[DSC1-HELLO-12]`). The
         *    allowlist check is unchanged code doing a strictly stronger check:
         *    the id it tests is now one the peer had to prove.
         * 6. **Record, bind, install, announce** — in that order, see
         *    [bindAndAnnounce].
         */
        private fun onProof(message: String) {
            val credentials = side.credentials
            val awaiting = pending
            if (credentials == null || awaiting == null || achieved != null) {
                refuseHello(
                    DenialReason.MALFORMED_HELLO,
                    awaiting?.derivedId,
                    "PROOF refused as out of order: " + when {
                        credentials == null -> "this side issued no challenge (it holds no credentials)"
                        awaiting == null -> "no HELLO2 preceded it on this connection instance"
                        else -> "this connection instance has already admitted a peer"
                    },
                )
                return
            }
            val proof = when (val parse = parseProof(message)) {
                is HelloParse.Ok -> parse.message
                is HelloParse.Malformed -> {
                    refuseHello(
                        parse.reason,
                        awaiting.derivedId,
                        "malformed PROOF from ${awaiting.derivedId.name} refused (${parse.kind}): ${parse.detail}",
                    )
                    return
                }
            }
            if (replayGuard.hasSeenSignature(awaiting.derivedId, proof.signature)) {
                refuseHello(
                    DenialReason.REPLAY,
                    awaiting.derivedId,
                    "PROOF from ${awaiting.derivedId.name} refused: it replays a proof this side already " +
                        "accepted inside the retention window",
                )
                return
            }
            val challenge = helloChallengeBytes(ourProofChallenge(credentials).mirrored())
            if (!Ed25519.verify(awaiting.publicKey, challenge, proof.signature)) {
                refuseHello(
                    DenialReason.BAD_SIGNATURE,
                    awaiting.derivedId,
                    "PROOF from ${awaiting.derivedId.name} refused: the signature does not verify under the " +
                        "key its HELLO2 presented",
                )
                return
            }
            if (!admitted(awaiting.derivedId)) return
            replayGuard.recordAccepted(awaiting.derivedId, awaiting.hello.nonce, proof.signature)
            bindAndAnnounce(awaiting.derivedId, awaiting.hello.mirrorRef, AuthLevel.Authenticated)
        }

        /**
         * The challenge **this side signs** for its own `PROOF`: this side as
         * signer, the peer as verifier. `mirrored()` gives the one the peer
         * signs, which is what [onProof] verifies against — role asymmetry is
         * the property that makes a reflected proof worthless
         * (`HelloProtocol.helloChallengeBytes`).
         *
         * The two mirror refs are the session-binding value `[DSC1-HELLO-03]`
         * asks for, and both are per connection *instance*: this side's is the
         * mirror [hello] just minted, the peer's is the one its `HELLO2`
         * offered. So a challenge — and any proof over it — is bound to one
         * connection instance and is worthless on the next.
         */
        private fun ourProofChallenge(credentials: PeerCredentials): HelloChallenge {
            val awaiting = checkNotNull(pending) { "no HELLO2 to build a challenge from" }
            return HelloChallenge(
                signerPeerId = credentials.peerId,
                verifierPeerId = awaiting.derivedId,
                verifierNonce = awaiting.hello.nonce,
                signerNonce = checkNotNull(localNonce) { "a credentialed side always sends a nonce in hello()" },
                signerMirrorRef = checkNotNull(mirror) { "onText before hello opened a connection instance" }.ref.id,
                verifierMirrorRef = awaiting.hello.mirrorRef,
            )
        }

        /**
         * `Peering.Side.admits`, with the refusal accounted — unchanged
         * behaviour on the legacy path (the stderr line and the denial detail
         * are the ones this transport has always written) and unchanged *code*
         * on the authenticated ones, where the only difference is that [peer]
         * is a derived id rather than a claimed name (`[DSC1-HELLO-12]`).
         *
         * @return true when the peer is admitted; false after refusing it, in
         *   which case the caller must return without binding anything.
         */
        private fun admitted(peer: PeerId?): Boolean {
            if (side.admits(peer)) return true
            System.err.println("[WsTransport] refusing peer $peer: not on the allowlist (spec 43)")
            // Seam 1 (spec 40/43, [SEC1-07]): accounted before the
            // connection is refused. A hello is a text frame with no
            // exclusives, so there is no deniedArgs payload to carry —
            // unlike BridgeIngressCell's frame-half refusal
            // (computenet-usd.4.1), which denies the raw bytes.
            // Nothing throws — a denial is not a cell fault (BS-14) —
            // so this never reaches supervision.
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = DenialReason.NOT_ADMITTED,
                principal = peer,
                subject = null,
                detail = "hello from $peer refused: not on the allowlist (spec 43)",
            )
            refuse()
            return false
        }

        /**
         * Account a refused hello on the seam-1 sink, then close the connection
         * — every refusal class on every hello path goes through here or through
         * [admitted], so `admissionDenialCount` counts all of them and none can
         * close a connection unaccounted (`[DSC1-OBS-01]`, `[DSC1-OBS-04]`).
         *
         * **[detail] names ids, reasons and shapes only.** No nonce, no
         * signature byte, no key material, ever — `[DSC1-OBS-05]`. That is
         * cheap to hold here because the pieces a refusal wants to name are
         * already safe by construction: a `PeerId` is public, a
         * `HelloParse.Malformed.detail` names only the shape of what arrived
         * (see its KDoc), and the raw line is never interpolated.
         *
         * The `principal` is the *claimed* id where that is all this side knows
         * and the *derived* id once one exists — which is why an `ID_MISMATCH`
         * detail carries both: the claim identifies who was talking, the
         * derivation identifies who they actually were.
         */
        private fun refuseHello(reason: DenialReason, principal: PeerId?, detail: String) {
            System.err.println("[WsTransport] refusing hello: $detail")
            lastAdmissionDenial = admissionSink.deny(
                seam = BoundarySeam.ADMISSION,
                reason = reason,
                principal = principal,
                subject = null,
                detail = detail,
            )
            refuse()
        }

        /**
         * Bind the mirror's peer, install the ingress, announce — in that order,
         * and only ever from a path that has already admitted [peer].
         *
         * ## The ordering argument, re-derived for a trigger that moved
         *
         * `RegistryMirrorCell.peer` (kernel `Peering.kt`) argues the late bind
         * safe against a specific trigger: *our* `onText` handling the peer's
         * hello, one message into the connection. On an authenticated peering
         * the trigger moves one message later — to the peer's `PROOF` — so that
         * argument cannot simply be inherited. `[DSC1-HELLO-13]` is exactly this
         * clause, and here is its re-derivation for the new trigger, for a
         * peering where both sides hold credentials:
         *
         * 1. Our `HELLO2` — carrying this connection instance's mirror ref and
         *    our fresh nonce — is written from `onOpen`, before this Session
         *    processes anything at all.
         * 2. The peer cannot address this mirror before it has read that
         *    `HELLO2`, so its `Peering.announceTo`, which sends to the ref we
         *    offered, cannot have run earlier.
         * 3. On the peer's side, its `PROOF` is written while it handles our
         *    `HELLO2`, and its announcing begins only while it handles our
         *    `PROOF` — a strictly later message in the us->peer direction, hence
         *    a strictly later handler on its socket thread. So in the peer->us
         *    direction the peer writes its `PROOF` **before** its first
         *    announcement frame.
         * 4. A WebSocket preserves message order per connection direction — the
         *    same premise the kernel's original argument rests on, unchanged —
         *    and java-websocket dispatches one connection's messages in order.
         *    So this Session's [onProof] runs, and returns, before [onFrame]
         *    sees any frame from that peer. [onProof] is where all three steps
         *    below happen, in this order.
         * 5. Independently of every step above, [onFrame] drops (and counts on
         *    `preHelloDrops`) every binary frame that arrives while [ingress] is
         *    still null. So a **refused** hello — every path through
         *    [refuseHello] or a false [admitted] — leaves no ingress at all:
         *    nothing the peer sends can be routed anywhere, and the admission
         *    decision cannot be raced by a frame that beats it.
         *
         * The invariant the feature names — hello parse -> verify -> admit ->
         * bind mirror -> install ingress -> announce — therefore holds with the
         * trigger moved. Only the *message* that carries the trigger changed;
         * steps 3 and 4 pin the new one to the same position relative to the
         * peer's own traffic that the old one held.
         *
         * The two other rows reduce to the original argument rather than needing
         * a new one: a legacy hello at an `Open` side binds at hello receipt
         * (the kernel's argument verbatim), and a `HELLO2` at an uncredentialed
         * `Open` side binds at `HELLO2` receipt, which is the same position —
         * the peer's `HELLO2` is written from *its* `onOpen`, exactly where a
         * legacy hello would have been.
         */
        private fun bindAndAnnounce(peer: PeerId?, peerMirrorRef: UUID, achieved: AuthLevel) {
            // The level is a PARAMETER, fixed by the admission row that called
            // us, and it is applied before the ingress exists — so it is bound
            // by the same happens-before that binds the mirror's peer
            // (`[DSC1-HELLO-13]`, and `RegistryMirrorCell.peer`'s own
            // argument). Publishing it on the observable field first keeps the
            // two readings — what a delivery is stamped with, and what
            // `achievedAuthLevel` reports — the same value by construction
            // rather than by two assignments agreeing.
            this.achieved = achieved
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
            // The hello-bound key, from THIS connection's PendingHello, is what
            // the announcement gate verifies under ([DSC1-ANN-05]) — the socket
            // half of the binding. `withVerifier` keeps the side-scoped replay
            // ledger, so the catch-up burst after a reconnect is judged against
            // the same per-identity high-water mark the previous connection
            // advanced ([DSC1-ANN-12..13]).
            val boundKey = pending?.takeIf { it.derivedId == peer }?.publicKey
            val admission = side.announcementAdmission?.let { ledger ->
                if (boundKey != null && peer != null) ledger.withVerifier(connectionBoundVerifier(peer, boundKey))
                else ledger
            }
            ingress = Peering.hostIngress(
                side,
                fromPeer = peer,
                fromPeerAuth = achieved,
                announcementAdmission = admission,
            )
            announcement?.close() // a re-hello (reconnect) supersedes the previous announcer
            announcement = Peering.announceTo(side, CellRef(peerMirrorRef), via = egress)
            // Last, so it is never true for an instance whose ingress and
            // announcer are not both installed: this is what a refused-dial run
            // is counted against (computenet-4gzr, [admitted]).
            admitted = true
        }

        fun onFrame(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            // enqueue only — decoding happens on the bridge host; frames
            // before an admitted hello have nowhere to go and drop (T05
            // finding 7: now counted via preHelloDropCount)
            val current = ingress
            if (current != null) {
                // computenet-dqy.68: counted BEFORE the hop it hands to, so the
                // reading means "this side's socket delivered it", never "the
                // bridge accepted it" — that is what `staged` is for.
                framesReceivedCount.incrementAndGet()
                current.propagate(bytes)
            } else {
                preHelloDropCount.incrementAndGet()
            }
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

    /**
     * The listening side of the transport.
     *
     * ## The listening socket is watched, because it can die without a word
     *
     * A `WebSocketServer` can lose its **listening** channel while the object,
     * its selector thread and its existing connections all stay perfectly
     * healthy — and report it nowhere. [listeningSocketLoss] and the watchdog
     * behind it exist so that event has a voice (computenet-dqy.39). Its
     * sibling [acceptorStopped] covers the *other* way this listener can go
     * deaf — the acceptor stopping with that channel still open
     * (computenet-dqy.56) — because neither seam can see the other's failure.
     *
     * The mechanism is java-websocket 1.6.0's, characterized as an executable
     * fact by `WsListenerAcceptRstTest` (read its KDoc for the bytecode
     * offsets and the platform measurements): `WebSocketServer.doAccept`
     * configures a freshly accepted socket — `setTcpNoDelay`, `setKeepAlive` —
     * before its own `try` block starts, and declares `throws IOException`. On
     * a BSD/macOS host, `setsockopt` on a socket whose peer has already sent
     * RST returns `EINVAL`, so a reset that races an accept throws out of that
     * unguarded prologue and lands in the selector loop's last-resort
     * `handleIOException(key, null, ex)` — where `key` is the **server's**
     * acceptable key. The listening channel is deregistered *and* closed. The
     * library then only `log.trace()`s it, `onError` is never called (that is
     * `handleFatal`'s path, which this is not), and this repository ships
     * `slf4j-api` with no provider, so nothing anywhere says a word.
     *
     * What that costs an operator is total: the process stays up, the cell
     * graph stays healthy, and the peer is simply unreachable forever. Dialers
     * get `ECONNREFUSED` on every retry, so no re-hello runs and
     * `Peering.announceTo` never re-announces. The only symptom is absence.
     * That is also a weaker guarantee than the in-process path offers, against
     * AGENTS.md's "in-process and remote paths should preserve the same
     * observable semantics".
     *
     * ## That mechanism is repaired here (computenet-dqy.37)
     *
     * [takeOverAccepting] takes the accept off the library's selector and
     * [admit] vendors 1.6.0's accept path with the two setters inside a
     * `try/catch`, so an `IOException` from configuring a freshly accepted
     * socket is attributed to **that** socket — closed, counted on
     * [rejectedAccepts] — instead of to the server's acceptable key.
     * `WsListenerAcceptRstTest` asserts the inverted claim the repair makes
     * true: the listener still accepts after a deliberate reset storm.
     *
     * Scope, stated because it is easy to over-read: this closes the
     * `doAccept`-prologue mechanism. It is not a claim that a listening socket
     * can no longer be lost by any route, which is why the watchdog below
     * stays.
     *
     * ## What the watchdog does, and firmly does not do
     *
     * It polls the listening channel's `isOpen` and, on a close nobody asked
     * for, calls [onError] with a [ListeningSocketLostException] naming the
     * cause. **That is diagnosability only** (computenet-dqy.39). The listener
     * is *not* re-served and the port is *not* recovered. It now guards the
     * residue rather than the known mechanism: any *other* way the listening
     * channel could close unasked still surfaces as an immediate named
     * diagnosis rather than as a distant 30s "timed out awaiting: collector
     * announced".
     *
     * The channel comes from public API, not reflection: [onConnect] is called
     * by `doAccept` with the server's own acceptable key before it accepts, so
     * the first connection attempt hands over the [ServerSocketChannel] to
     * watch (a listener that has never been connected to cannot yet have hit a
     * mechanism that fires *during* an accept). A listener served on a
     * caller-bound channel knows it from construction.
     */
    class WsListener : WebSocketServer {

        private val side: Peering.Side

        /**
         * The listening channel, once known: handed over by the caller-bound
         * constructor below, otherwise captured from the server's own key on
         * the first accept ([onConnect]). `@Volatile` because the selector
         * thread writes it and the watchdog thread reads it.
         */
        @Volatile
        private var listeningChannel: ServerSocketChannel? = null

        internal constructor(endpoint: InetSocketAddress, side: Peering.Side) : super(endpoint) {
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
            this.listeningChannel = channel
        }

        private val sessions = ConcurrentHashMap<WebSocket, Session>()
        private val started = CountDownLatch(1)

        /**
         * The two silent drops on this listener's announcement path, summed
         * over its live sessions (computenet-dqy.40): binary frames refused
         * before an admitted hello ([Session.preHelloDrops]) and announcements
         * refused at a shut mirror gate ([Session.refusedAnnouncements]).
         *
         * Neither writes anything to `System.err` — measured, see
         * `WsAnnouncementSilenceInventoryTest` — so a lost announcement whose
         * only recorded symptom is silence cannot be attributed without them.
         * Live sessions only: a closed connection's Session is removed in
         * [onClose], so a count taken after a disconnect is a count of what is
         * still connected.
         */
        val preHelloDrops: Long get() = sessions.values.sumOf { it.preHelloDrops }

        /** @see preHelloDrops */
        val refusedAnnouncements: Long get() = sessions.values.sumOf { it.refusedAnnouncements }

        /**
         * Seam-1 accounting for every hello this listener has ever refused at
         * [side]'s allowlist (spec 40/43 seam 1, `[SEC1-07]`;
         * computenet-usd.4.2), the wire half of the accounted-refusal pattern
         * `BridgeIngressCell` established for a refused frame
         * (computenet-usd.4.1).
         *
         * Allocated **once, here** rather than per [Session] — unlike
         * [preHelloDrops]/[refusedAnnouncements], summing over live sessions
         * would not work for this counter: a refused hello's [Session.refuse]
         * closes the connection immediately, and [onClose] removes that exact
         * Session from [sessions] right after, so a per-Session sink would be
         * discarded together with the very refusal it recorded — losing
         * exactly the count this seam exists to keep. Handing every accepted
         * connection's Session the *same* sink (see [onOpen]) means a
         * refusal is still counted here after its Session is gone.
         */
        private val admissionDenials = BoundaryDenials()
        private val admissionSink = admissionDenials.sinkFor("hello")

        /**
         * This listener's hello replay memory (`[DSC1-HELLO-11]`), shared across
         * every connection it accepts for the same reason [admissionSink] is:
         * **a replay arrives on a new socket**, so per-[Session] state could
         * never see the acceptance it is replaying. See [replayGuardFor].
         *
         * `by lazy` because [side] is assigned by this class's secondary
         * constructors — a property initializer cannot read it yet — and the
         * guard's window comes from that side's policy. Still exactly one guard
         * per listener: `lazy` is synchronized, and the first read is the first
         * `onOpen`.
         */
        private val replayGuard by lazy { replayGuardFor(side) }

        /**
         * The authentication level each live connection reached, in no
         * particular order — `AuthLevel.Authenticated` for a peering that
         * completed a `HELLO2`/`PROOF` exchange, `TransportVouched` for one the
         * transport merely vouched for, null for a connection that has not
         * admitted a peer yet (`[DSC1-HELLO-04]`, `[DSC1-HELLO-09]`).
         *
         * Live sessions only, like [preHelloDrops]: a closed connection's
         * Session is removed in [onClose]. An **observation**, not a control
         * surface — see [Session.achievedAuthLevel] for why nothing downstream
         * branches on it yet.
         */
        val achievedAuthLevels: List<AuthLevel?> get() = sessions.values.map { it.achievedAuthLevel }

        /**
         * Monotonic count of hellos this listener refused across every
         * connection it has ever accepted (spec 40/43 `[SEC1-07]`) — reads
         * [admissionSink] directly, **not** summed over [sessions] (see its
         * KDoc for why).
         */
        val admissionDenialCount: Long get() = admissionSink.denialCount

        /**
         * The announcement channel's two ends on this listener's live sessions
         * (computenet-dqy.68) — see [Session.framesSent] for what they cut apart.
         */
        val framesSent: Long get() = sessions.values.sumOf { it.framesSent }

        /** @see framesSent */
        val framesReceived: Long get() = sessions.values.sumOf { it.framesReceived }

        /** @see framesSent */
        val socketHasBufferedData: Boolean get() = sessions.values.any { it.socketHasBufferedData }

        /**
         * Set before any deliberate shutdown reaches the library, so the
         * watchdog never mistakes a close we asked for for a loss.
         *
         * [stop] with a timeout and a message is the single funnel: 1.6.0's
         * `stop()` and `stop(int)` both reach it through `invokevirtual`, so
         * overriding it covers every deliberate stop, including
         * `HeldPort.release`'s and the demos'.
         */
        @Volatile
        private var stopRequested = false

        /**
         * The loss, once reported — one-shot, so a lost listener says it once
         * rather than every poll.
         *
         * Non-null exactly when this listener's listening socket closed while
         * nobody had asked it to stop; the same object that was handed to
         * [onError]. A test or an operator-facing probe can read this instead
         * of scraping stderr.
         */
        val listeningSocketLoss: ListeningSocketLostException? get() = loss.get()

        private val loss = AtomicReference<ListeningSocketLostException?>(null)

        /**
         * The acceptor's stop, once reported — one-shot, the same shape as
         * [listeningSocketLoss] and for the same reason (computenet-dqy.56).
         *
         * Non-null exactly when this listener's vendored acceptor thread left
         * [acceptLoop] while nobody had asked it to stop and its listening
         * channel was still open; the same object that was handed to [onError].
         *
         * The two seams cover the two ways this listener can go deaf, and
         * neither can see the other's: [listeningSocketLoss] polls for a channel
         * that CLOSED, so it stays null when the channel is open and nothing is
         * accepting — which is the *worse* of the two for a dialer, because a
         * TCP connect into an unattended backlog hangs rather than being
         * refused. Before this seam existed that state was reported through
         * [onError] only, so a health check had to scrape stderr to learn the
         * listener had gone deaf while an operator could poll for the other
         * half. A listener that can go deaf with no observable signal is the
         * weaker remote-side guarantee this line of work exists to remove.
         *
         * Like [listeningSocketLoss], this is a DIAGNOSIS, not a recovery: the
         * acceptor does not come back.
         */
        val acceptorStopped: AcceptorStoppedException? get() = acceptorStop.get()

        private val acceptorStop = AtomicReference<AcceptorStoppedException?>(null)

        internal fun awaitStart(timeout: Long, unit: TimeUnit): Boolean = started.await(timeout, unit)

        override fun onStart() {
            started.countDown()
            takeOverAccepting()
            watchListeningSocket(port)
            watchWriteDemand(port)
        }

        /**
         * **The repair (computenet-dqy.69): how many times this listener put back
         * a write demand java-websocket 1.6.0 had lost.**
         *
         * Monotonic and per-listener. Non-zero means the defect below fired on
         * this listener and was repaired; it is not a health warning, it is the
         * evidence the repair is load-bearing. Zero on a listener that never
         * lost one — which is the overwhelming majority: the measured rate was
         * 9 stalls in 12,500 ubuntu peerings (computenet-dqy.68).
         *
         * @see sweepWriteDemand
         */
        val writeDemandReArms: Long get() = writeDemandReArmCount.get()

        private val writeDemandReArmCount = AtomicLong()

        /**
         * A label this listener's [WRITE_REARM_MARKER] lines carry, so a marker
         * a test provoked ON PURPOSE can be told apart from a real occurrence.
         *
         * Empty on every listener the production path builds, and nothing in
         * `wire`'s main source ever sets it — so **an unlabelled marker line is
         * a genuine re-arm**, and a labelled one is a test instrumenting itself.
         * Set it from the test that deliberately strands a frame, before the
         * strand.
         *
         * Why this exists: the `wire-suite-sample` workflow runs the whole
         * `:wire:` suite per iteration, and
         * `WsListenerWriteDemandReArmTest`'s first test strands a frame by
         * construction — so every iteration emitted one marker and two
         * 500-iteration samples had to be read by subtracting a
         * one-per-iteration baseline by hand. That subtraction was misread once
         * (computenet-dqy.70's P1 raise) before computenet-0r4i re-did it. With
         * the label, a sample is read with a filter and needs no arithmetic.
         *
         * `@Volatile` because the sweep thread reads what the test thread wrote.
         */
        @Volatile
        var reArmMarkerLabel: String = ""

        /**
         * Consecutive sweeps in which a connection was seen in the lost-demand
         * state. Keyed by connection, pruned every sweep to the connections that
         * are still in it, so a closed or recovered connection leaves nothing
         * behind. See [sweepWriteDemand] for why one observation is not enough.
         */
        private val lostDemandStreak = ConcurrentHashMap<WebSocket, Int>()

        /**
         * **The repair for computenet-dqy.69: a lost write demand is put back.**
         *
         * ## The defect
         *
         * java-websocket 1.6.0's server writes from its selector thread and
         * accepts frames from any caller thread, and the two race over one
         * `SelectionKey`'s interest set:
         *
         * ```java
         * WebSocketImpl.write:            outQueue.add(buf); wsl.onWriteDemand(this);   // caller thread
         * WebSocketServer.onWriteDemand:  conn.getSelectionKey().interestOps(OP_READ | OP_WRITE); selector.wakeup();
         * WebSocketServer.doWrite:        if (batch(conn, channel) && key.isValid()) key.interestOps(OP_READ);  // selector thread
         * ```
         *
         * `batch` returns true exactly when it emptied `outQueue`. A caller that
         * enqueues and arms `OP_WRITE` in the window *after* that last
         * `outQueue.poll()` and *before* `doWrite` clears the interest is
         * clobbered: the frame sits in `outQueue`, no thread will ever register
         * write interest for it again, and the `wakeup` only releases a `select`
         * with nothing to write. The connection stays open and fully READABLE,
         * `send` returned normally, nothing throws, and every frame queued
         * afterwards is stranded behind the first — so the peer sees a
         * contiguous PREFIX of the stream and an await for anything later simply
         * expires. computenet-dqy.68 measured it at 9 occurrences in 12,500
         * ubuntu peerings (1.8% per fresh-JVM `:wire` suite run) and all
         * nineteen ever observed lose server->client, because
         * `WebSocketClient.onWriteDemand` is `// nothing to do` — the dialer
         * writes from a thread blocking on `outQueue.take()` and cannot lose a
         * demand. That asymmetry is why only the listener carries this sweep.
         *
         * ## The repair
         *
         * The lost state is *exactly* observable, so this is a detector rather
         * than a heuristic: `hasBufferedData()` (`!outQueue.isEmpty()`) is true
         * while the key is valid and its interest set has no `OP_WRITE`. Nothing
         * will drain that queue, because `OP_WRITE` is what makes the selector
         * call `doWrite` at all. [onWriteDemand] — `public final` on
         * `WebSocketServer`, so this is API and not vendoring — puts the demand
         * back: `interestOps(OP_READ or OP_WRITE)` plus `selector.wakeup()`,
         * which is byte-for-byte the demand that was clobbered.
         *
         * Ordinary backpressure is *not* this state and is left alone: when the
         * socket buffer is full, `batch` returns false and `doWrite` leaves
         * `OP_WRITE` set, so a slow peer never trips the detector however deep
         * its queue gets.
         *
         * ## Why two consecutive observations
         *
         * There is one benign instant that looks identical: between
         * `outQueue.add(buf)` and `wsl.onWriteDemand(this)` on the caller
         * thread, a frame is queued and the interest is not yet armed. That
         * window is a few instructions long, so re-arming into it would be
         * harmless (an extra `interestOps` the selector clears on its next
         * empty batch) — but *counting* it would inflate [writeDemandReArms],
         * and that counter is the evidence this repair is judged on. Requiring
         * the state to survive two sweeps [WRITE_REARM_POLL_MS] apart makes a
         * benign reading vanishingly unlikely while bounding recovery at twice
         * the poll, which is two orders of magnitude inside the 3s budget the
         * awaits this strands actually have.
         *
         * `internal` so a test can drive one sweep deterministically instead of
         * waiting on the poll thread.
         */
        internal fun sweepWriteDemand() {
            val stillLost = HashSet<WebSocket>()
            for (conn in connections) {
                val impl = conn as? WebSocketImpl ?: continue
                val key = impl.selectionKey ?: continue
                val lost = try {
                    impl.hasBufferedData() && key.isValid() && (key.interestOps() and SelectionKey.OP_WRITE) == 0
                } catch (_: CancelledKeyException) {
                    false // the connection is going away; its queue is not ours to save
                }
                if (!lost) continue
                val streak = (lostDemandStreak[conn] ?: 0) + 1
                if (streak < LOST_DEMAND_CONFIRMATIONS) {
                    lostDemandStreak[conn] = streak
                    stillLost += conn
                    continue
                }
                // Confirmed: bytes queued, no write interest, across two sweeps.
                lostDemandStreak.remove(conn)
                val n = writeDemandReArmCount.incrementAndGet()
                try {
                    onWriteDemand(conn)
                } catch (_: CancelledKeyException) {
                    // 1.6.0 catches this inside onWriteDemand; belt and braces
                }
                // Never silent, for the same reason nothing else on this path is:
                // this is a real defect firing, repaired. It is also the ONLY
                // evidence a genuine occurrence leaves — the defect is rare (9 in
                // 12,500 ubuntu peerings) and platform-skewed, so the ubuntu
                // wire-suite sample is read by grepping for this marker. One line
                // per occurrence; a healthy process prints none.
                System.err.println(
                    "[WsListener] $WRITE_REARM_MARKER: put back a write demand java-websocket lost on port $port " +
                        (if (reArmMarkerLabel.isEmpty()) "" else "label=$reArmMarkerLabel ") +
                        "(re-arm #$n; the out-queue held bytes with no OP_WRITE across " +
                        "$LOST_DEMAND_CONFIRMATIONS sweeps ${WRITE_REARM_POLL_MS}ms apart). " +
                        "See WsListener.sweepWriteDemand — computenet-dqy.69.",
                )
            }
            lostDemandStreak.keys.retainAll(stillLost)
        }

        /** @see sweepWriteDemand */
        private fun watchWriteDemand(boundPort: Int) {
            Thread {
                while (!stopRequested) {
                    try {
                        sweepWriteDemand()
                    } catch (t: Throwable) {
                        // One bad connection must never end this sweep: a dead
                        // re-arm thread is the defect back, silently.
                        if (!stopRequested) System.err.println("[WsListener] write-demand sweep failed: $t")
                    }
                    try {
                        Thread.sleep(WRITE_REARM_POLL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }.apply { isDaemon = true; name = "ws-listener-write-rearm-$boundPort" }.start()
        }

        /**
         * How many freshly accepted connections this listener discarded because
         * configuring them threw — the reset-victim count, and the number of
         * times the listening socket would have been closed instead under
         * unvendored java-websocket 1.6.0.
         *
         * Monotonic and per-listener. Expected to be 0 on Linux (`setsockopt`
         * there succeeds on a reset victim, so the failure surfaces on the first
         * per-connection read, which java-websocket already attributes
         * correctly) and non-zero on BSD/macOS under reset load.
         */
        val rejectedAccepts: Long get() = rejectedAcceptCount.get()

        private val rejectedAcceptCount = AtomicLong()

        /**
         * `doAccept`'s first statement, called with the **server's** acceptable
         * key. Only reached if [takeOverAccepting] failed, since it cancels the
         * server key that makes `doAccept` run at all; kept so that a listener
         * on the fallback path still hands its listening channel to the
         * watchdog. Always admits: returning false would `key.cancel()` that
         * very key.
         */
        override fun onConnect(key: SelectionKey): Boolean {
            if (listeningChannel == null) listeningChannel = key.channel() as? ServerSocketChannel
            return super.onConnect(key)
        }

        /**
         * **The repair (computenet-dqy.37): java-websocket 1.6.0's accept path,
         * vendored with one `try/catch` added, and taken off the library's
         * selector so the library's version can no longer run.**
         *
         * Upstream `WebSocketServer.doAccept` reads (1.6.0, verbatim):
         *
         * ```java
         * SocketChannel channel = server.accept();
         * if (channel == null) return;
         * channel.configureBlocking(false);
         * Socket socket = channel.socket();
         * socket.setTcpNoDelay(isTcpNoDelay());   // throws on a reset victim, BSD only
         * socket.setKeepAlive(true);              // and outside any try of its own
         * WebSocketImpl w = wsf.createWebSocket(this, drafts);
         * w.setSelectionKey(channel.register(selector, SelectionKey.OP_READ, w));
         * try { w.setChannel(wsf.wrapChannel(channel, w.getSelectionKey()));
         *       i.remove(); allocateBuffers(w); }
         * catch (IOException ex) { ... handleIOException(w.getSelectionKey(), null, ex); }
         * ```
         *
         * The two setters are the whole defect. `doAccept` declares
         * `throws IOException`, so a `SocketException` from them unwinds into
         * the selector loop's last resort, `handleIOException(key, null, ex)`,
         * where `key` is the **server's** acceptable key — cancelled and closed,
         * silently. [admit] performs the same sequence with the configuration
         * inside its own `catch`: the failure is charged to the channel it came
         * from, that channel is closed, [rejectedAccepts] counts it, and the
         * listening socket is untouched.
         *
         * ## Why the whole accept has to move, not just the configuration
         *
         * `doAccept` and `handleIOException` are both `private` in 1.6.0
         * (verified with `javap -p`), so neither can be overridden. `onConnect`
         * is `protected` and runs at the top of `doAccept`, which looks like the
         * seam — and it is not sufficient, **measured**: accepting there and
         * returning true still leaves `doAccept`'s own `server.accept()` to run
         * immediately afterwards, so any connection queued or arriving in that
         * window goes through the unguarded prologue anyway. With that version
         * in place the pre-repair reproduction still killed the listener after
         * 2 resets. So the library must not accept at all: [takeOverAccepting]
         * cancels the server's key on the library's selector, which makes
         * `key.isValid()` false in the selector loop and `doAccept` unreachable,
         * and this class owns the accept from then on.
         *
         * ## What that costs, stated because it is real
         *
         * - Two `private` fields are read reflectively once per listener,
         *   `WebSocketServer.server` and `WebSocketServer.selector`, at
         *   [onStart] — after `doSetupSelectorAndServerThread` has assigned
         *   both. java-websocket is on the classpath (unnamed module), so this
         *   needs no `--add-opens`. If either read fails the listener says so
         *   through [onError] and stays on the library's own accept path,
         *   defect and all, rather than silently not listening.
         * - One extra `Selector` and one daemon thread per listener.
         * - Connections are registered with the library's selector from that
         *   thread instead of from the selector thread. They are registered with
         *   interest `0`, wired up, and only then switched to `OP_READ` and
         *   woken, so the selector cannot see a key whose `WebSocketImpl` has no
         *   channel yet. Cross-thread mutation of that selector is already
         *   normal here — 1.6.0's own `onWriteDemand` does `interestOps` +
         *   `wakeup` from the worker threads.
         *
         * This pins the vendored code to 1.6.0's internals, which is why
         * `upstream/java-websocket-doAccept-fix/` carries the same fix as a
         * patch against upstream: when a release with it ships, [takeOverAccepting],
         * [acceptLoop] and [admit] delete and `onConnect` goes back to capturing
         * [listeningChannel] only.
         */
        private fun takeOverAccepting() {
            val server = privateField("server", ServerSocketChannel::class.java)
            val libSelector = privateField("selector", Selector::class.java)
            if (server == null || libSelector == null) {
                onError(
                    null,
                    IOException(
                        "computenet-dqy.37: could not take over WebSocketServer's accept path " +
                            "(server=$server, selector=$libSelector). This listener stays on " +
                            "java-websocket 1.6.0's own doAccept, where a TCP reset that races an " +
                            "accept closes the LISTENING socket on a BSD/macOS host. It still " +
                            "listens; it is just exposed to that defect, and the watchdog will " +
                            "report the loss if it happens.",
                    ),
                )
                return
            }
            listeningChannel = server
            server.keyFor(libSelector)?.cancel()
            libSelector.wakeup()
            Thread { acceptLoop(server, libSelector) }
                .apply { isDaemon = true; name = "ws-listener-accept-$port" }
                .start()
        }

        private fun <T : Any> privateField(name: String, type: Class<T>): T? =
            runCatching {
                val field = WebSocketServer::class.java.getDeclaredField(name)
                field.isAccessible = true
                type.cast(field.get(this))
            }.getOrNull()

        /** @see takeOverAccepting */
        private fun acceptLoop(server: ServerSocketChannel, libSelector: Selector) {
            val acceptSelector = try {
                Selector.open()
            } catch (e: IOException) {
                reportAcceptorStopped(e)
                return
            }
            var cause: Throwable? = null
            try {
                server.register(acceptSelector, SelectionKey.OP_ACCEPT)
                while (!stopRequested && server.isOpen) {
                    acceptSelector.select(ACCEPT_SELECT_MS)
                    acceptSelector.selectedKeys().clear()
                    while (!stopRequested) {
                        val channel = server.accept() ?: break
                        // One connection must never cost this listener its
                        // acceptor. [admit] can raise unchecked exceptions the
                        // narrow catches inside it do not cover
                        // (`CancelledKeyException` and `ClosedSelectorException`
                        // are `RuntimeException`s, and the factory is foreign
                        // code), and an escape here would kill this thread while
                        // the listening channel stayed OPEN — see
                        // [reportAcceptorStopped] for why that is worse than the
                        // defect being repaired.
                        try {
                            admit(channel, libSelector)
                        } catch (t: Throwable) {
                            runCatching { channel.close() }
                            if (!stopRequested) {
                                onError(
                                    null,
                                    IOException(
                                        "computenet-dqy.37: admitting an accepted connection failed; " +
                                            "that connection was dropped and this listener keeps accepting",
                                        t,
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (_: ClosedChannelException) {
                // the listener is going down, or has lost its channel: the
                // watchdog owns that diagnosis, not this loop
            } catch (_: ClosedSelectorException) {
            } catch (t: Throwable) {
                cause = t
            } finally {
                runCatching { acceptSelector.close() }
                if (!stopRequested && server.isOpen) reportAcceptorStopped(cause)
            }
        }

        /**
         * The acceptor thread owns this listener's accept path, so if it stops
         * while the listening channel is still **open** the listener is deaf
         * with nothing closed — and [watchListeningSocket] cannot see that,
         * because it only looks for a channel that closed. Worse than the
         * defect this bead repairs: dialers reach an unattended backlog and hang
         * instead of being refused fast.
         *
         * So the acceptor never dies quietly. Every exit that is not a
         * requested [stop] or a closed listening channel says so through
         * [acceptorStopped] and [onError].
         *
         * The seam is published *before* [onError] is called, deliberately and
         * for the same reason [reportListeningSocketLost] does it in that order:
         * `onError` renders foreign objects — the cause chain of whatever ended
         * the loop — so it can itself throw, and a diagnosis that only exists if
         * the printing succeeds is not a diagnosis. One-shot via
         * `compareAndSet`, so the first exit is the one recorded.
         */
        private fun reportAcceptorStopped(cause: Throwable?) {
            val stopped = AcceptorStoppedException(
                "computenet-dqy.37: the vendored acceptor for port $port stopped while its listening " +
                    "socket is still open. This peer will accept TCP connections and complete no " +
                    "handshake, so dialers hang rather than being refused, and the listening-socket " +
                    "watchdog cannot see it. THIS IS A DIAGNOSIS, NOT A RECOVERY: this listener will " +
                    "not accept again on its own.",
                cause,
            )
            if (!acceptorStop.compareAndSet(null, stopped)) return
            onError(null, stopped)
        }

        /** @see takeOverAccepting */
        private fun admit(channel: SocketChannel, libSelector: Selector) {
            try {
                channel.configureBlocking(false)
                val socket = channel.socket()
                socket.tcpNoDelay = isTcpNoDelay
                socket.keepAlive = true
            } catch (_: IOException) {
                // THE FIX. Upstream lets this reach the selector loop, which
                // blames the server's key — the listener. It belongs to
                // `channel`, and to nothing else.
                rejectedAcceptCount.incrementAndGet()
                runCatching { channel.close() }
                return
            }
            // Never silently: `getWebSocketFactory()` is declared to return the
            // wider `WebSocketFactory`, and a listener that quietly discarded
            // every connection would be exactly the invisible deafness this
            // repair exists to remove. [acceptLoop] reports it per connection.
            val factory = getWebSocketFactory() as? WebSocketServerFactory
                ?: throw IllegalStateException(
                    "computenet-dqy.37: WebSocketServer.getWebSocketFactory() is not a " +
                        "WebSocketServerFactory, so the vendored accept path cannot create a connection",
                )
            val w: WebSocketImpl = factory.createWebSocket(this, getDraft())
            var key: SelectionKey? = null
            try {
                // interest 0 first: the selector thread must not be able to see
                // this key as readable before `w` has its channel.
                key = channel.register(libSelector, 0, w)
                w.setSelectionKey(key)
                w.setChannel(factory.wrapChannel(channel, key))
                allocateBuffers(w)
                key.interestOps(SelectionKey.OP_READ)
                libSelector.wakeup()
            } catch (_: IOException) {
                // upstream's own tail of doAccept, minus the private
                // handleIOException: cancel this connection's key, close this
                // connection's channel. Same attribution, same effect.
                key?.cancel()
                runCatching { channel.close() }
            } catch (_: InterruptedException) {
                key?.cancel()
                runCatching { channel.close() }
                Thread.currentThread().interrupt()
            }
        }

        /**
         * Poll [listeningChannel] until it closes or [stop] is called. A daemon
         * thread rather than a shared scheduler: it is one boolean read every
         * [WATCHDOG_POLL_MS], it must outlive nothing, and a listener that has
         * already reported its loss stops polling for good.
         */
        private fun watchListeningSocket(boundPort: Int) {
            Thread {
                while (!stopRequested) {
                    val channel = listeningChannel
                    if (channel != null && !channel.isOpen) {
                        reportListeningSocketLost(boundPort)
                        return@Thread
                    }
                    try {
                        Thread.sleep(WATCHDOG_POLL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }.apply { isDaemon = true; name = "ws-listener-watchdog-$boundPort" }.start()
        }

        private fun reportListeningSocketLost(boundPort: Int) {
            if (stopRequested) return // a stop that landed while this poll was in flight
            val lost = ListeningSocketLostException(
                "listening socket lost: the listener on port $boundPort stopped accepting because its " +
                    "listening channel was closed without stop() being called. This peer is now UNREACHABLE " +
                    "and will not recover on its own — dialers get ECONNREFUSED forever, so no re-hello runs " +
                    "and Peering.announceTo never re-announces; a remote await for one of this peer's refs " +
                    "will simply expire. Known cause (computenet-dqy.37): java-websocket 1.6.0's " +
                    "WebSocketServer.doAccept configures a freshly accepted socket (setTcpNoDelay/setKeepAlive) " +
                    "outside any try/catch of its own, so on a BSD/macOS host a TCP reset that races the accept " +
                    "throws SocketException there, and the selector loop's last-resort handler attributes it to " +
                    "the SERVER's acceptable key and closes the listening channel. THIS IS A DIAGNOSIS, NOT A " +
                    "RECOVERY: nothing has been re-served and the port is gone.",
            )
            if (!loss.compareAndSet(null, lost)) return
            onError(null, lost)
        }

        override fun stop(timeout: Int, closeMessage: String?) {
            stopRequested = true
            super.stop(timeout, closeMessage)
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            // admissionSink is THIS listener's shared sink (see its KDoc) —
            // every Session this listener opens reports into it, so a
            // refusal survives this Session's own removal in onClose.
            val session = Session(
                side,
                { conn.send(it) },
                { conn.close() },
                { conn.hasBufferedData() },
                admissionSink,
                // the PROOF answering a peer's HELLO2 challenge travels as text
                // on this same socket, written from inside onText
                { text: String -> conn.send(text) },
                // this listener's shared guard, not a per-Session one (see its KDoc)
                replayGuard,
            )
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
            lostDemandStreak.remove(conn)
            sessions.remove(conn)?.onClose()
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            System.err.println("[WsListener] $ex")
            ex.printStackTrace()
        }

        /**
         * This listener's listening socket closed while nobody had asked it to
         * stop — see [WsListener]'s KDoc. Reported through [onError]; never
         * thrown at a caller, because there is no caller on that path.
         */
        class ListeningSocketLostException internal constructor(message: String) : IOException(message)

        /**
         * This listener's vendored acceptor thread stopped while its listening
         * socket was still open, without a [stop] having been asked for — see
         * [acceptorStopped] and [reportAcceptorStopped]. Reported through
         * [onError] and readable from [acceptorStopped]; never thrown at a
         * caller, because there is no caller on that path.
         */
        class AcceptorStoppedException internal constructor(
            message: String,
            cause: Throwable?,
        ) : IOException(message, cause)

        internal companion object {
            /**
             * How often the watchdog looks. Small enough that the diagnosis is
             * effectively immediate next to the 10-30s awaits that this failure
             * otherwise expires, and cheap enough to ignore: one volatile read
             * and one `isOpen` per listener per tick.
             */
            const val WATCHDOG_POLL_MS = 200L

            /**
             * How long the vendored acceptor blocks in `select` before
             * re-checking that it is still wanted. Only a shutdown-latency
             * bound: accepts themselves arrive as selector wakeups.
             */
            const val ACCEPT_SELECT_MS = 200L

            /**
             * How often [WsListener.sweepWriteDemand] looks for a stranded
             * out-queue. Recovery is bounded at
             * [LOST_DEMAND_CONFIRMATIONS] x this — 50ms — against awaits that
             * budget 3s and give up at 15s, so the repair is invisible in
             * latency terms; the cost is one `isEmpty` and one `interestOps`
             * read per live connection per tick.
             */
            const val WRITE_REARM_POLL_MS = 25L

            /**
             * The stderr marker one confirmed re-arm prints. Grep the ubuntu
             * `wire-suite-sample` logs for it: on the platform the defect
             * actually fires on, that line **is** the proof the repair fired
             * against a genuinely stalled out-queue, and its absence over a
             * clean 500-iteration sample is proof no announcement was stranded.
             *
             * **Filter out the labelled lines when counting occurrences** — a
             * marker carrying `label=` was provoked on purpose by a test (see
             * [WsListener.reArmMarkerLabel]); one that does not is real:
             *
             * ```
             * grep -rhI 'LOST WRITE DEMAND RE-ARMED' <sample> | grep -vc 'label='
             * ```
             *
             * `-I` is load-bearing whenever `<sample>` is a Gradle results
             * directory rather than a `wire-suite-sample` artifact of `.console`
             * files: `build/test-results/test/binary/output-events.bin` matches
             * too, and `grep -r` reports it as a `Binary file … matches` line
             * that survives the `label=` filter and inflates the count by one
             * (measured 2026-08-25 — 2 reported where the XML held exactly 1
             * unlabelled marker).
             *
             * MEASURED, computenet-0r4i: before the label existed, the two
             * 500-iteration ubuntu samples (runs 31784338227 and 31791814150)
             * held 515 and 513 marker lines, of which exactly 500 each were
             * `WsListenerWriteDemandReArmTest`'s deliberate strand — one per
             * fresh JVM, never zero, never two. The unattributed remainder was
             * 15 and 13.
             */
            const val WRITE_REARM_MARKER = "LOST WRITE DEMAND RE-ARMED"

            /**
             * How many consecutive sweeps must see the lost-demand state before
             * it is counted and re-armed — see [WsListener.sweepWriteDemand]'s
             * "Why two consecutive observations".
             */
            const val LOST_DEMAND_CONFIRMATIONS = 2
        }
    }

    class WsConnection internal constructor(
        uri: URI,
        side: Peering.Side,
        private val backoff: (attempt: Int) -> Long = DEFAULT_RECONNECT_BACKOFF,
        private val refusedDialLimit: Int = REFUSED_DIAL_LIMIT,
    ) : WebSocketClient(uri, Draft_6455(), null, DIAL_TIMEOUT_MS) {

        private val session = Session(
            side,
            { bytes: ByteArray -> send(bytes) },
            { shutdown() },
            { hasBufferedData() },
            sendText = { text: String -> send(text) },
        )

        /**
         * The two silent drops on this dialer's announcement path
         * (computenet-dqy.40) — see [WsListener.preHelloDrops] for why they are
         * worth reading. A client keeps one Session across every reconnect, so
         * [preHelloDrops] accumulates over the connection's whole life while
         * [refusedAnnouncements] reports the *current* instance's mirror only.
         */
        val preHelloDrops: Long get() = session.preHelloDrops

        /** @see preHelloDrops */
        val refusedAnnouncements: Long get() = session.refusedAnnouncements

        /**
         * Hellos this dialer refused at its own allowlist (spec 40/43 seam 1,
         * `[SEC1-07]`; computenet-usd.4.2) — see
         * [Session.admissionDenialCount]. A client keeps one Session across
         * every reconnect, so this accumulates over the connection's whole
         * life, same as [preHelloDrops].
         */
        val admissionDenialCount: Long get() = session.admissionDenialCount

        /**
         * The authentication level this dialer's **current** connection instance
         * reached, or null before it has admitted a peer on it
         * (`[DSC1-HELLO-04]`) — see [Session.achievedAuthLevel]. A client keeps
         * one Session across every reconnect, and `hello()` resets this on each
         * open, so this reports the live connection instance rather than the
         * connection's history.
         */
        val achievedAuthLevel: AuthLevel? get() = session.achievedAuthLevel

        /**
         * The announcement channel's two ends on this dialer (computenet-dqy.68)
         * — see [Session.framesSent] for what they cut apart.
         */
        val framesSent: Long get() = session.framesSent

        /** @see framesSent */
        val framesReceived: Long get() = session.framesReceived

        /** @see framesSent */
        val socketHasBufferedData: Boolean get() = session.socketHasBufferedData

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

        /**
         * Consecutive connection instances that **opened** without this side's
         * peering surviving — the refusal signal this wire does not carry, read
         * off the local connection lifecycle instead
         * ([WsTransport.REFUSED_DIAL_LIMIT]).
         *
         * **Incremented in [onOpen], not in [onClose]**, and that is the whole
         * reliability of it. An open is exactly what costs the listener an
         * accept plus a hello parse, and it happens once per socket; a *close*
         * is neither — java-websocket reports a failed connect as a close, can
         * report two for one socket (`reset()` closes, and the read thread's
         * end-of-transmission closes again), and under load can leave an open
         * socket's close unreported. Counting closes under-counted opens exactly
         * when the machine was busiest: a full-suite run measured 9 refusals
         * charged to a listener by a dialler that believed it had opened 2.
         *
         * Counting opens also makes the failed-connect case fall out for free
         * rather than needing a flag: a connect that never opened never
         * increments, so it still retries forever
         * (`WsReconnectRefusedTest`'s unbound port).
         */
        private val unadmitted = AtomicInteger()

        /**
         * `System.nanoTime()` at this instance's [onOpen], or 0 when no socket
         * is open — consumed by the close it belongs to. @see unadmitted
         */
        private val openedAt = java.util.concurrent.atomic.AtomicLong()

        /**
         * True once [refusedDialLimit] consecutive unadmitted opens have ended
         * the reconnecting. Distinct from [reconnect], which is `shutdown()`'s
         * flag: a client that gave up on a refusing listener is not the same
         * state as one the application closed, and conflating them would make
         * the give-up unreadable.
         */
        @Volatile
        private var abandoned = false

        /** @see WsTransport.REFUSED_DIAL_LIMIT */
        val unadmittedOpens: Int get() = unadmitted.get()

        /**
         * True once this client has given up reconnecting to a listener that
         * keeps refusing its hello ([WsTransport.REFUSED_DIAL_LIMIT]).
         *
         * Observable rather than silent, for the reason an interrupted retry
         * loop is announced in [scheduleReconnect]: "in-process and remote paths
         * owe the same observable semantics, and a quiet give-up would break
         * that quietly."
         */
        val abandonedAfterRefusals: Boolean get() = abandoned

        /**
         * The first close this client saw, already rendered (computenet-dqy.41), and
         * how many followed it.
         *
         * The *first* one is the one that answers "why did the dial not open?" — a
         * later close belongs to a reconnect attempt, not to the original handshake —
         * so this is set once and never overwritten; the counter keeps a run that
         * closed repeatedly from reading as a single event.
         *
         * **This is observable by the time `connectBlocking` returns**, so
         * [dialDiagnosis] never has to wait for it: 1.6.0's
         * `WebSocketClient.onWebsocketClose` calls `onClose` (offset 23) *before*
         * `connectLatch.countDown()` (offset 30), and that countDown is what releases
         * `connectBlocking`. Re-check this against the close ordering on any upgrade —
         * if it inverts, the diagnosis degrades to "no close observed", which is a
         * weaker report and not a wrong one.
         */
        private val firstClose = AtomicReference<String?>(null)
        private val closes = AtomicInteger()

        /**
         * How far this dial got, for a caller that has to explain a connect which
         * never opened (computenet-dqy.41). Read-only; called only on the give-up
         * path.
         *
         * `readyState` is the state at the moment of giving up, and it separates a dial
         * that ran out its ten seconds still waiting (`NOT_YET_CONNECTED`) from one
         * that was torn down (`CLOSING`/`CLOSED` — 1.6.0 assigns `CLOSED` *after*
         * `onClose` returns, so either may be read here; both mean the same thing).
         * The close code is the coarse split between a TCP-level end of either kind
         * (**-1**, which java-websocket's client reaches from a clean EOF *and* from
         * any non-SSL `IOException` in its read loop — so it does **not** distinguish
         * a graceful close from a reset) and a peer that answered but refused the
         * upgrade (**1002**, from `WebSocketImpl.decode`'s client-role rejections).
         */
        internal fun dialDiagnosis(): String {
            val state = runCatching { readyState.toString() }.getOrElse { "readyState unavailable: $it" }
            val close = firstClose.get()
                ?: return "readyState=$state, and the client observed NO close (the dial simply never opened)"
            val more = closes.get().let { if (it > 1) " (and $it closes in all)" else "" }
            return "readyState=$state, first close seen by the client: $close$more"
        }

        /** Deliberate close: stop reconnecting, then close the socket. */
        fun shutdown() {
            reconnect = false
            close()
        }

        /**
         * Re-arm this connection after [abandonedAfterRefusals] — the `:wire`
         * shape of `IrohConnection.heal`, closing the asymmetry computenet-f6dr
         * found: `:wire`'s give-up otherwise had no way back short of building a
         * new [WsConnection].
         *
         * [scheduleReconnect] treats [abandoned] and a maxed-out [unadmitted] run
         * as terminal, so healing must clear both — not just flip [abandoned] —
         * before it can ask for the reconnect it now permits again. An operator's
         * decision to retry a peer that may since have been allowlisted (or whose
         * peering was abandoned on a false-positive refusal read, see
         * [WsTransport.REFUSAL_WINDOW_MS]), not a schedule's: nothing else calls
         * this.
         *
         * A no-op after [shutdown] — [reconnect] stays false, so the
         * [scheduleReconnect] this calls declines to start a loop. Healing
         * resumes a client that gave up on a refusing peer; it does not undo an
         * application's own decision to stop.
         */
        fun heal() {
            unadmitted.set(0)
            abandoned = false
            scheduleReconnect()
        }

        override fun onOpen(handshake: ServerHandshake) {
            // Charged before the hello goes out, because the hello is the cost:
            // this open has already committed the listener to one accept and one
            // hello parse whatever happens next (computenet-4gzr).
            openedAt.set(System.nanoTime())
            unadmitted.incrementAndGet()
            send(session.hello())
        }

        override fun onMessage(message: String) = session.onText(message)

        override fun onMessage(bytes: ByteBuffer) = session.onFrame(bytes)

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            closes.incrementAndGet()
            firstClose.compareAndSet(
                null,
                "code=$code, reason=${reason?.takeIf(String::isNotEmpty)?.let { "\"$it\"" } ?: "<none>"}, " +
                    "closed by ${if (remote) "the peer" else "this side"}",
            )
            // The opens were counted as they happened; this is only where the run
            // is cleared or acted on. An open that outlived the refusal window
            // was not a refusal — see [WsTransport.REFUSED_DIAL_LIMIT] for why
            // this and not `session.peered`. `openedAt == 0` is a close for a
            // connect that never opened: not a refusal either, and not counted,
            // so it leaves the run alone.
            val opened = openedAt.getAndSet(0L)
            if (opened != 0L && System.nanoTime() - opened >= REFUSAL_WINDOW_MS * 1_000_000L) {
                unadmitted.set(0)
            }
            session.onClose() // unpublish: senders park until the re-hello re-announces
            if (unadmitted.get() >= refusedDialLimit) {
                abandoned = true
                System.err.println(
                    "[WsConnection] ${getURI()} closed after $refusedDialLimit consecutive connections that " +
                        "never produced a surviving peering; this listener is refusing us and will not be " +
                        "reconnected to",
                )
                return
            }
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
            // The refusal bound is re-checked HERE and not only in `onClose`,
            // because this method is also reached from the retry loop's own
            // re-arm, which can win the race against the close that would have
            // set `abandoned` (computenet-4gzr).
            if (!reconnect || abandoned || unadmitted.get() >= refusedDialLimit || isOpen) return
            if (!reconnecting.compareAndSet(false, true)) return // a loop is already retrying
            Thread {
                var interrupted = false
                try {
                    var attempt = 0
                    while (reconnect && !abandoned && unadmitted.get() < refusedDialLimit && !isOpen) {
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
