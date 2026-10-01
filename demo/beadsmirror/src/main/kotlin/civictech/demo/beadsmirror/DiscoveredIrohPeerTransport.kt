package civictech.demo.beadsmirror

import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerStats
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.PeerTransportProvider
import civictech.cell.wire.Peering
import civictech.cell.wire.ReconnectPolicy
import civictech.iroh.IrohNode
import civictech.iroh.IrohPeerTransport
import civictech.iroh.IrohTransport
import civictech.iroh.discover.DialPolicy
import civictech.iroh.discover.DiscoveredPeering
import civictech.iroh.discover.NodeKey
import civictech.iroh.discover.PeerView
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beadsmirror's discovery-backed [PeerTransport] binding. Its fixed address is
 * `iroh+mdns://`: neither a node id nor an endpoint address crosses from the
 * listener to the dialler; both sidecars advertise/watch on the local mDNS
 * segment instead.
 *
 * Discovery owns the dialled connection only until [stopDiscovery] or the
 * first [PeerConnection.partition]. Detaching keeps the formed link alive and
 * hands that connection to this binding. From then on the ordinary kernel
 * transport contract owns its lifecycle: partition holds it severed and heal
 * restores the same discovered peer.
 */
class DiscoveredIrohPeerTransport(
    private val binary: Path,
    private val reconnectBackoff: (attempt: Int) -> Long = ReconnectPolicy.DEFAULT_BACKOFF,
    private val formationTimeoutMillis: Long = 60_000,
    private val healTimeoutMillis: Long = 30_000,
) : PeerTransport {

    /** The only address in this binding: discovery supplies the endpoint. */
    data object Address : PeerAddress {
        override val scheme: String get() = SCHEME
        override val text: String get() = ADDRESS
    }

    private val lock = Any()
    private val listenerTransport = IrohPeerTransport(
        binary = binary,
        sidecarArgs = SIDECAR_ARGS,
        backoff = reconnectBackoff,
    )
    private var listener: PeerListener? = null
    private var dialled: DialledConnection? = null

    /** The dialling end's discovery policy, retained and readable after detach. */
    val discovery: DiscoveredPeering? get() = synchronized(lock) { dialled?.discovery }

    /** The shared-sidecar node used by the dialling end. */
    val dialledNode: IrohNode? get() = synchronized(lock) { dialled?.node }

    override val scheme: String get() = SCHEME

    override fun parseAddress(text: String): PeerAddress {
        require(text == ADDRESS) { "not an $SCHEME address (expected $ADDRESS): '$text'" }
        return Address
    }

    override fun listen(address: PeerAddress, side: Peering.Side): PeerListener = synchronized(lock) {
        own(address)
        check(listener == null) { "this $SCHEME transport is already listening" }
        val delegate = listenerTransport.listen(listenerTransport.parseAddress("iroh://"), side)
        DiscoveryListener(delegate).also { listener = it }
    }

    override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection = synchronized(lock) {
        own(address)
        check(dialled == null) { "this $SCHEME transport has already dialled" }
        val stderr = StderrTail()
        val node = IrohTransport.node(side, binary, stderrSink = stderr::add, sidecarArgs = SIDECAR_ARGS)
        val discovery = try {
            DiscoveredPeering.start(node, DialPolicy(schedule = reconnectBackoff))
        } catch (failure: Throwable) {
            runCatching { node.close() }
            throw failure
        }
        val peeredKey = try {
            awaitFormation(discovery, stderr)
        } catch (failure: Throwable) {
            runCatching { discovery.close() }
            runCatching { node.close() }
            throw failure
        }
        DialledConnection(side, node, discovery, peeredKey, stderr).also { dialled = it }
    }

    /** Stop watching after formation without dropping the formed connection. Idempotent. */
    fun stopDiscovery() = synchronized(lock) { diallingEnd().detach() }

    private fun own(address: PeerAddress) {
        require(address.scheme == SCHEME && address.text == ADDRESS) {
            "an $SCHEME transport cannot serve a ${address.scheme} address: ${address.text}"
        }
    }

    private fun diallingEnd(): DialledConnection = checkNotNull(dialled) {
        "stopDiscovery acts on the dialling end, and this $SCHEME transport has not dialled one"
    }

    private fun awaitFormation(discovery: DiscoveredPeering, stderr: StderrTail): String {
        val deadline = System.nanoTime() + formationTimeoutMillis * 1_000_000
        while (true) {
            val views = discovery.snapshot()
            val peered = views.filter { it.state.startsWith(PEERED_PREFIX) }
            check(peered.size <= 1) {
                "discovery peered ${peered.size} keys, expected exactly one: ${describe(peered)}. " +
                    "Another --mdns sidecar on this segment was admitted; this binding will not guess which one " +
                    "is the mirror's peer"
            }
            if (peered.size == 1) return peered.single().keyHex
            check(System.nanoTime() < deadline) {
                stderr.mdnsUnavailable()?.let { line ->
                    "no discovered peer was peered within ${formationTimeoutMillis}ms; the sidecar reported: $line"
                } ?: "no discovered peer was peered within ${formationTimeoutMillis}ms and the sidecar reported no " +
                    "mDNS problem; retained views: ${describe(views)}; sidecar stderr tail: ${stderr.tail()}"
            }
            Thread.sleep(50)
        }
    }

    private class DiscoveryListener(private val delegate: PeerListener) : PeerListener {
        override val side: Peering.Side get() = delegate.side
        override val boundAddress: PeerAddress get() = Address
        override val stats: PeerStats get() = delegate.stats
        override fun close() = delegate.close()
    }

    private class HandedConnection(
        val partition: () -> Unit,
        val heal: () -> Unit,
        val close: () -> Unit,
        val carrying: () -> Boolean,
        val stats: () -> PeerStats,
    )

    private inner class DialledConnection(
        override val side: Peering.Side,
        val node: IrohNode,
        val discovery: DiscoveredPeering,
        private val peeredKeyHex: String,
        private val stderr: StderrTail,
    ) : PeerConnection {

        private var handed: HandedConnection? = null
        private var detached = false
        private var severed = false
        private var closed = false

        override val isCarrying: Boolean
            get() = synchronized(lock) {
                !closed && (handed?.carrying?.invoke() ?: discovery.snapshot().any(::isPeeredKey))
            }

        override val stats: PeerStats
            get() = synchronized(lock) {
                handed?.stats?.invoke() ?: PeerStats(0, 0, 0, 0)
            }

        fun detach() {
            check(!closed) { "the discovered peering is closed" }
            if (detached) return
            detached = true
            val transferred = discovery.detach()
            val (mine, others) = transferred.entries.partition { it.key.hex == peeredKeyHex }
            others.forEach { runCatching { it.value.close() } }
            val connection = checkNotNull(mine.singleOrNull()?.value) {
                "detaching discovery handed back no connection for ${peeredKeyHex.take(8)} " +
                    "(handed: ${transferred.keys.map(NodeKey::short)})"
            }
            handed = HandedConnection(
                partition = connection::partition,
                heal = connection::heal,
                close = connection::close,
                carrying = { connection.isCarrying },
                stats = {
                    PeerStats(
                        framesSent = connection.framesSent,
                        framesReceived = connection.framesReceived,
                        unadmittedOpens = connection.unadmittedOpens,
                        refusedAnnouncements = connection.refusedAnnouncements,
                    )
                },
            )
        }

        override fun partition() = synchronized(lock) {
            check(!closed) { "the discovered peering is closed" }
            check(!severed) { "the discovered peering is already partitioned" }
            detach()
            checkNotNull(handed).partition()
            severed = true
        }

        override fun heal() = synchronized(lock) {
            check(!closed) { "the discovered peering is closed; dial a new one" }
            check(severed) { "the discovered peering is not partitioned" }
            val connection = checkNotNull(handed)
            try {
                connection.heal()
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "heal() of the discovered connection failed: ${failure.message}; dialling sidecar stderr tail: " +
                        stderr.tail(),
                    failure,
                )
            }
            severed = false
            val deadline = System.nanoTime() + healTimeoutMillis * 1_000_000
            while (!connection.carrying()) {
                check(System.nanoTime() < deadline) {
                    "the discovered iroh peering did not carry again within ${healTimeoutMillis}ms of heal(); " +
                        "dialling sidecar stderr tail: ${stderr.tail()}"
                }
                Thread.sleep(20)
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                handed?.let { runCatching { it.close() } }
                handed = null
                if (!detached) runCatching { discovery.close() }
                runCatching { node.close() }
            }
        }

        private fun isPeeredKey(view: PeerView): Boolean =
            view.keyHex == peeredKeyHex && view.state.startsWith(PEERED_PREFIX)
    }

    private class StderrTail {
        private val lines = ArrayDeque<String>()
        private var mdnsLine: String? = null

        @Synchronized
        fun add(line: String) {
            if (mdnsLine == null && line.contains(MDNS_UNAVAILABLE_MARKER)) mdnsLine = line
            lines.addLast(line)
            while (lines.size > STDERR_TAIL) lines.removeFirst()
        }

        @Synchronized
        fun mdnsUnavailable(): String? = mdnsLine

        @Synchronized
        fun tail(): String = if (lines.isEmpty()) "(none)" else lines.joinToString(" | ")
    }

    private fun describe(views: List<PeerView>): String =
        if (views.isEmpty()) "none" else views.joinToString { "${it.keyHex.take(8)}=${it.state}" }

    companion object {
        const val SCHEME: String = "iroh+mdns"
        const val ADDRESS: String = "$SCHEME://"
        private val SIDECAR_ARGS: List<String> = listOf("--offline", "--mdns")
        private const val MDNS_UNAVAILABLE_MARKER = "mdns unavailable"
        private const val STDERR_TAIL = 50
        private const val PEERED_PREFIX = "Peered("
    }
}

/** ServiceLoader provider for beadsmirror's `iroh+mdns` binding. */
class DiscoveredIrohPeerTransportProvider : PeerTransportProvider {
    override val scheme: String get() = DiscoveredIrohPeerTransport.SCHEME

    override fun create(config: Map<String, String>): PeerTransport {
        val known = setOf("binary", "formationTimeoutMillis", "healTimeoutMillis")
        val unknown = config.keys - known
        require(unknown.isEmpty()) { "unknown $scheme transport config keys: $unknown" }
        val binary = Path.of(requireNotNull(config["binary"]) { "the $scheme transport needs a 'binary'" })
        require(Files.isRegularFile(binary)) { "the $scheme sidecar binary $binary is not a file" }
        return DiscoveredIrohPeerTransport(
            binary = binary,
            formationTimeoutMillis = config["formationTimeoutMillis"]?.toLong() ?: 60_000,
            healTimeoutMillis = config["healTimeoutMillis"]?.toLong() ?: 30_000,
        )
    }
}
