package civictech.wire

import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerStats
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransportProvider
import civictech.cell.wire.Peering
import civictech.cell.wire.ReconnectPolicy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.net.URI
import java.nio.channels.ServerSocketChannel

/**
 * The `ws` binding of the kernel's transport seam (feature `computenet-gyvli`,
 * gyvli-D1): [WsTransport] behind [PeerTransport], so a caller names
 * `ws://host:port` and never a java-websocket type — [PeerListener] and
 * [PeerConnection] expose none (computenet-j1f2).
 *
 * **Addresses.** `ws://<host>:<port>`, nothing after the port. Port 0 asks
 * for any free port; [PeerListener.boundAddress] reports the one granted, on
 * the host that was asked for. An ephemeral request on `localhost` binds the
 * address `localhost` resolves to ([WsTransport.listen]'s dqy.28 choice); any
 * other host is bound as given.
 *
 * **Reconnect and intent** (gyvli-D2, gyvli-D4). Each connection runs on its
 * [WsTransport.WsConnection]'s kernel [ReconnectPolicy]:
 *
 * - [PeerConnection.partition] severs the policy, then closes the socket: no
 *   re-dial until [PeerConnection.heal], and a dial already in flight that
 *   opens after the sever is closed quietly (computenet-g1aua's gate).
 * - [PeerConnection.heal] heals the policy and re-dials on the **same**
 *   connection — the same `Session`, so the healed instance supersedes the
 *   severed one and lifts its tombstones (gyvli-D3) — returning once the link
 *   carries.
 * - [PeerConnection.close] is **always deliberate**: the policy records it
 *   before the socket closes, so no `ws-reconnect-` loop is ever armed by it
 *   (computenet-8uv6, decided here per gyvli-D4). Only a close this binding did
 *   not initiate — the peer's, a network drop — re-arms, on the policy's
 *   schedule exactly as `WsReconnectSmokeTest` pins for the raw API.
 *
 * **"Carrying"** means the socket is open and the peer has sent a frame on it,
 * which it does only after admitting this side's hello (see
 * [WsTransport.REFUSED_DIAL_LIMIT]); [dial] and [PeerConnection.heal] return
 * only once that holds. A dial the listener refuses therefore never returns a
 * connection: it fails with [DialRefusedException] once the policy abandons
 * after [ReconnectPolicy.REFUSED_DIAL_LIMIT] unadmitted opens.
 *
 * @param backoff the re-dial schedule every connection's policy runs on; a
 *   test drives it near zero.
 * @param refusedDialLimit the refused-dial bound every connection's policy
 *   applies.
 * @param carryTimeoutMs how long [dial] and [PeerConnection.heal] wait for a
 *   link to carry before failing.
 */
class WsPeerTransport(
    private val backoff: (attempt: Int) -> Long = ReconnectPolicy.DEFAULT_BACKOFF,
    private val refusedDialLimit: Int = ReconnectPolicy.REFUSED_DIAL_LIMIT,
    private val carryTimeoutMs: Long = DEFAULT_CARRY_TIMEOUT_MS,
) : PeerTransport {

    /** A `ws://host:port` address. */
    data class WsAddress(val host: String, val port: Int) : PeerAddress {
        init {
            require(host.isNotEmpty()) { "a ws address needs a host" }
            require(port in 0..65_535) { "a ws port is 0..65535 (was $port)" }
        }

        override val scheme: String get() = SCHEME

        override val text: String
            get() = "$SCHEME://${if (':' in host) "[$host]" else host}:$port"

        internal val uri: URI get() = URI(text)
    }

    override val scheme: String get() = SCHEME

    override fun parseAddress(text: String): PeerAddress {
        val uri = try {
            URI(text)
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("not a $SCHEME address (expected $SCHEME://host:port): '$text'", e)
        }
        require(uri.scheme == SCHEME) { "not a $SCHEME address (expected $SCHEME://host:port): '$text'" }
        val host = uri.host
        require(host != null && uri.port >= 0) { "a $SCHEME address needs a host and a port: '$text'" }
        require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "a $SCHEME address is $SCHEME://host:port and nothing more: '$text'"
        }
        return WsAddress(host.removePrefix("[").removeSuffix("]"), uri.port)
    }

    private fun own(address: PeerAddress): WsAddress {
        require(address.scheme == SCHEME) { "a $SCHEME transport cannot serve a ${address.scheme} address: ${address.text}" }
        return address as? WsAddress ?: parseAddress(address.text) as WsAddress
    }

    override fun listen(address: PeerAddress, side: Peering.Side): PeerListener {
        val wanted = own(address)
        WsTransport.refuseUnsendable(side) // before anything is bound
        val raw = when {
            wanted.port == 0 && wanted.host == LOCALHOST -> WsTransport.listen(0, side)
            wanted.port != 0 && isWildcard(wanted.host) -> WsTransport.listen(wanted.port, side)
            else -> {
                val channel = ServerSocketChannel.open()
                try {
                    if (wanted.port != 0) channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    channel.bind(InetSocketAddress(InetAddress.getByName(wanted.host), wanted.port))
                    WsTransport.listen(channel, side)
                } catch (e: Exception) {
                    runCatching { channel.close() }
                    throw e
                }
            }
        }
        return Listener(raw, WsAddress(wanted.host, raw.port), side)
    }

    override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection {
        val target = own(address)
        val raw = WsTransport.connect(target.uri, side, backoff, refusedDialLimit)
        val connection = Connection(raw, target, side)
        try {
            connection.awaitCarrying("dial")
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return connection
    }

    /** A serving `ws` endpoint over a [WsTransport.WsListener], which it never exposes. */
    internal class Listener(
        internal val raw: WsTransport.WsListener,
        override val boundAddress: WsAddress,
        override val side: Peering.Side,
    ) : PeerListener {

        override val stats: PeerStats
            get() {
                val framesEnqueued = raw.framesEnqueued
                val framesReceived = raw.framesReceived
                return PeerStats(
                    framesSent = raw.framesSent,
                    framesReceived = framesReceived,
                    unadmittedOpens = 0,
                    refusedAnnouncements = raw.announcementAdmissionDenials,
                    framesEnqueued = framesEnqueued,
                )
            }

        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            raw.stop(STOP_TIMEOUT_MS)
        }
    }

    /** A dialled `ws` endpoint over one [WsTransport.WsConnection], kept across every partition and heal. */
    internal inner class Connection(
        internal val raw: WsTransport.WsConnection,
        private val target: WsAddress,
        override val side: Peering.Side,
    ) : PeerConnection {

        /** The kernel policy this connection's reconnect loop runs on. */
        internal val policy: ReconnectPolicy get() = raw.policy

        override val isCarrying: Boolean get() = raw.carrying

        override val stats: PeerStats
            get() {
                val framesEnqueued = raw.framesEnqueued
                val framesReceived = raw.framesReceived
                return PeerStats(
                    framesSent = raw.framesSent,
                    framesReceived = framesReceived,
                    unadmittedOpens = raw.unadmittedOpens,
                    refusedAnnouncements = raw.announcementAdmissionDenials,
                    framesEnqueued = framesEnqueued,
                )
            }

        @Synchronized
        override fun partition() {
            check(!policy.deliberateClose) { "the ws connection to ${target.text} is closed" }
            raw.sever() // the policy first: nothing re-dials behind the close
            awaitSocketDown()
        }

        @Synchronized
        override fun heal() {
            check(!policy.deliberateClose) { "the ws connection to ${target.text} is closed; dial a new one" }
            if (raw.carrying) return
            raw.heal()
            awaitCarrying("heal")
        }

        @Synchronized
        override fun close() {
            if (policy.deliberateClose) return
            raw.shutdown() // closeDeliberately() before the socket goes: no loop is armed by it
            awaitSocketDown()
        }

        /** Wait until the link carries; fail if the policy gives up first or [carryTimeoutMs] passes. */
        fun awaitCarrying(what: String) {
            val deadline = System.nanoTime() + carryTimeoutMs * 1_000_000L
            while (!raw.carrying) {
                if (policy.abandoned) {
                    throw DialRefusedException(
                        "$what to ${target.text} abandoned after ${policy.unadmittedOpens} consecutive opens the " +
                            "listener never admitted (refused-dial limit ${policy.refusedDialLimit})",
                        stats,
                        policy.shouldRedial(),
                    )
                }
                check(System.nanoTime() < deadline) {
                    "$what to ${target.text} did not carry within ${carryTimeoutMs}ms — ${raw.dialDiagnosis()}"
                }
                Thread.sleep(POLL_MS)
            }
        }

        /** Bounded: a close whose handshake the peer never answers still ends at java-websocket's own timeout. */
        private fun awaitSocketDown() {
            val deadline = System.nanoTime() + STOP_TIMEOUT_MS * 1_000_000L
            while (raw.isOpen && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        }
    }

    /**
     * A dial the listener kept refusing: the connection's policy abandoned it
     * after its refused-dial limit of unadmitted opens. [stats] and
     * [shouldRedial] are the abandoned connection's reading at the moment it
     * gave up, before [dial] closed it.
     */
    class DialRefusedException internal constructor(
        message: String,
        val stats: PeerStats,
        val shouldRedial: Boolean,
    ) : IllegalStateException(message)

    companion object {
        const val SCHEME: String = "ws"

        /** How long [dial] and [PeerConnection.heal] wait for a link to carry by default. */
        const val DEFAULT_CARRY_TIMEOUT_MS: Long = 15_000

        private const val LOCALHOST = "localhost"
        private const val STOP_TIMEOUT_MS = 1_000
        private const val POLL_MS = 5L

        private fun isWildcard(host: String): Boolean =
            host == "0.0.0.0" || host == "::" || host == "0:0:0:0:0:0:0:0"
    }
}

/** ServiceLoader entry for [WsPeerTransport]; [create] ignores its config and returns the production defaults. */
class WsPeerTransportProvider : PeerTransportProvider {
    override val scheme: String get() = WsPeerTransport.SCHEME

    override fun create(config: Map<String, String>): PeerTransport = WsPeerTransport()
}
