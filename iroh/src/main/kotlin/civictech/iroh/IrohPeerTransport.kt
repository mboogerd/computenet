package civictech.iroh

import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerStats
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransportProvider
import civictech.cell.wire.Peering
import civictech.cell.wire.ReconnectPolicy
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `:iroh` binding of the kernel's transport seam (feature
 * `computenet-gyvli`, gyvli-D1), scheme `iroh`: [IrohTransport.listen] and
 * [IrohTransport.connect] behind [PeerTransport], so a caller names an
 * address rather than a sidecar.
 *
 * ## Addresses
 *
 * `iroh://<nodeId hex>?addr=<a>&addr=<b>` ([IrohAddress]) — the peer's
 * endpoint id and its `LISTENING` addresses, which is exactly what an offline
 * dial needs (`ADD_PEER`, `PROTOCOL.md` §3). Address values are URL-encoded,
 * so an IPv6 literal survives the round trip.
 *
 * [listen] **ignores** the node id and addresses of the address it is given:
 * the sidecar's own id is authoritative, and it binds where it binds. So
 * `iroh://` (no id) is the "any" request, like a ws port 0, and the listener's
 * [PeerListener.boundAddress] is the real `nodeId` plus the real `addresses`
 * — what a dialler must be handed. That replaces the demo binding's
 * `syntheticPort`, which had to invent a TCP port for a transport that has
 * none. A [secretKey] pins the listening sidecar's key (`--secret-key`), and
 * with it the node id, across listens.
 *
 * ## Partition and heal
 *
 * [IrohConnection.partition][IrohTransport.IrohConnection.partition] marks the
 * connection's [ReconnectPolicy] severed and closes the link: no re-dial is
 * attempted until [PeerConnection.heal], and a re-dial already in flight when
 * the partition lands is closed quietly when it returns, never admitted
 * (computenet-g1aua). Heal clears the policy and dials one fresh link, then
 * waits (bounded by [timeout]) until it carries.
 *
 * ## One sidecar per endpoint
 *
 * Every [listen] and every [dial] spawns its own sidecar, as
 * [IrohTransport.listen] and [IrohTransport.connect] do; closing the endpoint
 * shuts it down. [secretKey] applies to **listening** sidecars only: two live
 * endpoints holding one key would be one iroh node twice.
 *
 * @param binary the sidecar executable.
 * @param secretKey the listening sidecar's ed25519 secret key, 64 hex chars; null for a fresh key per listen.
 * @param sidecarArgs extra arguments for every spawned sidecar (see [SidecarProcess.spawn]).
 * @param backoff the re-dial schedule for unplanned drops; a test drives it near zero.
 * @param timeout bounds sidecar start-up, each dial, and how long [dial]/[PeerConnection.heal] wait to carry.
 */
class IrohPeerTransport(
    private val binary: Path,
    private val secretKey: String? = null,
    private val sidecarArgs: List<String> = emptyList(),
    private val backoff: (attempt: Int) -> Long = ReconnectPolicy.DEFAULT_BACKOFF,
    private val timeout: Duration = 30.seconds,
    private val stderrSink: (String) -> Unit = {},
) : PeerTransport {

    init {
        require(secretKey == null || SECRET_KEY.matches(secretKey)) { "secretKey must be 64 hex characters" }
    }

    /**
     * An iroh address: a node id ([nodeIdHex], 64 lowercase hex chars, or
     * empty for a listen request) and the peer's `LISTENING` [addresses].
     */
    data class IrohAddress(val nodeIdHex: String, val addresses: List<String>) : PeerAddress {
        init {
            require(nodeIdHex.isEmpty() || NODE_ID.matches(nodeIdHex)) {
                "an iroh node id is 64 lowercase hex characters (or empty for a listen request): '$nodeIdHex'"
            }
        }

        override val scheme: String get() = SCHEME

        override val text: String
            get() = PREFIX + nodeIdHex + addresses.joinToString(separator = "&", prefix = if (addresses.isEmpty()) "" else "?") {
                "addr=" + URLEncoder.encode(it, StandardCharsets.UTF_8)
            }
    }

    override val scheme: String get() = SCHEME

    override fun parseAddress(text: String): PeerAddress {
        require(text.startsWith(PREFIX)) { "not an $SCHEME address (expected $PREFIX<nodeId>?addr=..): '$text'" }
        val rest = text.removePrefix(PREFIX)
        val nodeId = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        val addresses = if (query.isEmpty()) {
            emptyList()
        } else {
            query.split('&').map { pair ->
                require(pair.startsWith("addr=")) { "an $SCHEME address carries only addr= parameters: '$text'" }
                URLDecoder.decode(pair.removePrefix("addr="), StandardCharsets.UTF_8)
            }
        }
        return IrohAddress(nodeId, addresses)
    }

    private fun own(address: PeerAddress): IrohAddress {
        require(address.scheme == SCHEME) { "an $SCHEME transport cannot serve a ${address.scheme} address: ${address.text}" }
        return address as? IrohAddress ?: parseAddress(address.text) as IrohAddress
    }

    override fun listen(address: PeerAddress, side: Peering.Side): PeerListener {
        own(address) // validated, then ignored: the sidecar's own id and addresses are authoritative
        val args = sidecarArgs + (secretKey?.let { listOf("--secret-key", it) } ?: emptyList())
        return Listener(IrohTransport.listen(side, binary, timeout, stderrSink, args), side)
    }

    override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection {
        val target = own(address)
        require(target.nodeIdHex.isNotEmpty()) { "an $SCHEME dial needs the listener's node id: ${target.text}" }
        val nodeId = checkNotNull(target.nodeIdHex.hexToBytesOrNull()) { "unparseable node id in ${target.text}" }
        val connection = IrohTransport.connect(
            side,
            nodeId,
            target.addresses,
            binary,
            timeout = timeout,
            stderrSink = stderrSink,
            backoff = backoff,
            redialTimeout = timeout,
            sidecarArgs = sidecarArgs,
        )
        return try {
            Connection(connection, side, target).also { it.awaitCarrying("dial") }
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    /** A dial the peer kept refusing: [unadmittedOpens] links came up and went down unadmitted, and it gave up. */
    class DialRefusedException(val unadmittedOpens: Int, message: String) : IllegalStateException(message)

    private class Listener(private val listener: IrohTransport.IrohListener, override val side: Peering.Side) :
        PeerListener {

        override val boundAddress: IrohAddress = IrohAddress(listener.nodeId.toHex(), listener.addresses)

        override val stats: PeerStats
            get() = PeerStats(
                framesSent = listener.framesSent,
                framesReceived = listener.framesReceived,
                unadmittedOpens = 0,
                refusedAnnouncements = listener.refusedAnnouncements,
            )

        override fun close() = listener.close()
    }

    private inner class Connection(
        private val connection: IrohTransport.IrohConnection,
        override val side: Peering.Side,
        private val target: IrohAddress,
    ) : PeerConnection {

        @Volatile
        private var closed = false

        override val isCarrying: Boolean get() = !closed && connection.isCarrying

        override val stats: PeerStats
            get() = PeerStats(
                framesSent = connection.framesSent,
                framesReceived = connection.framesReceived,
                unadmittedOpens = connection.unadmittedOpens,
                refusedAnnouncements = connection.refusedAnnouncements,
            )

        override fun partition() {
            check(!closed) { "the $SCHEME connection to ${target.text} is closed" }
            connection.partition()
        }

        override fun heal() {
            check(!closed) { "the $SCHEME connection to ${target.text} is closed; dial a new one" }
            connection.heal(timeout)
            awaitCarrying("heal")
        }

        /**
         * `IrohConnection` returns once THIS side's hello is sent; the seam
         * promises a link that carries, which is one step later — the peer's
         * hello admitted back. Bounded; a peer that refuses us ends it early.
         */
        fun awaitCarrying(what: String) {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (!connection.isCarrying) {
                if (connection.abandonedAfterRefusals) {
                    throw DialRefusedException(
                        connection.unadmittedOpens,
                        "$what to ${target.text}: the peer refused ${connection.unadmittedOpens} consecutive links " +
                            "unadmitted; not re-dialling",
                    )
                }
                check(System.nanoTime() < deadline) { "$what to ${target.text} did not carry within $timeout" }
                Thread.sleep(10)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            connection.close() // deliberate (gyvli-D4): disarms the re-dial, then tears down the sidecar
        }
    }

    companion object {
        const val SCHEME: String = "iroh"
        private const val PREFIX: String = "$SCHEME://"
        private val NODE_ID = Regex("[0-9a-f]{64}")
        private val SECRET_KEY = Regex("[0-9a-fA-F]{64}")
    }
}

/**
 * ServiceLoader entry for [IrohPeerTransport]. Config keys:
 *
 * - `binary` (required): the sidecar executable, which must exist.
 * - `secretKey` (optional): the listening sidecar's secret key, 64 hex chars.
 * - `sidecarArgs` (optional): extra sidecar arguments, whitespace-separated.
 *
 * Unknown keys are refused, so a misspelt key fails at configuration.
 */
class IrohPeerTransportProvider : PeerTransportProvider {
    override val scheme: String get() = IrohPeerTransport.SCHEME

    override fun create(config: Map<String, String>): PeerTransport {
        val unknown = config.keys - setOf("binary", "secretKey", "sidecarArgs")
        require(unknown.isEmpty()) { "unknown ${IrohPeerTransport.SCHEME} transport config keys: $unknown" }
        val binary = Path.of(requireNotNull(config["binary"]) { "the ${IrohPeerTransport.SCHEME} transport needs a 'binary'" })
        require(Files.isRegularFile(binary)) { "the ${IrohPeerTransport.SCHEME} sidecar binary $binary is not a file" }
        val args = config["sidecarArgs"]?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()
        return IrohPeerTransport(binary, secretKey = config["secretKey"], sidecarArgs = args)
    }
}
