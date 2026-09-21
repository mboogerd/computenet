package civictech.iroh.discover

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import civictech.iroh.FakeSidecar
import civictech.iroh.HostMessage
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.LinkDirection
import civictech.iroh.SidecarClient
import civictech.iroh.SidecarMessage
import civictech.iroh.SidecarProtocol.DIRECTION_INBOUND
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A fresh, valid 32-byte iroh NodeId. Every id in this package is a real
 * Ed25519 public key, because `Session.admissionKey` fingerprints it and a
 * NodeId that is not a key is a `MALFORMED_HELLO` refusal rather than a test.
 */
internal fun freshNodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

/** The [KeyId] a link on [nodeId] is admitted under — what a binding is keyed by. */
internal fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

/** There is no child process behind a [FakeSidecar]; a node's own id is a value the test chooses. */
internal class FixedSidecar(override val nodeId: ByteArray) : IrohTransport.Sidecar {
    override fun close() = Unit
}

/** A [Peering.Side] over its own registry and host, with the binding and allowlist a test wants. */
internal fun sideWith(
    peer: PeerId = PeerId("node"),
    allow: Set<PeerId>? = null,
    binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
): Peering.Side {
    val registry = LocationRegistry()
    return Peering.Side(
        registry,
        ManagedHost(registry = registry),
        peer = peer,
        allow = allow,
        identityBinding = binding,
    )
}

/** Who [side]'s binding resolves [nodeId]'s key to — the identity a mirror is stamped with. */
internal fun resolvedBy(side: Peering.Side, nodeId: ByteArray): PeerId =
    when (val resolution = side.identityBinding.resolve(keyOf(nodeId), emptyList())) {
        is IdentityResolution.Bound -> resolution.peer
        is IdentityResolution.Unbound -> fail("expected ${keyOf(nodeId)} to be bound, got $resolution")
    }

/** Run [call] on a daemon thread and return a function that waits for its result. */
internal fun <T> settle(what: String, call: () -> T): () -> T {
    val done = ArrayBlockingQueue<Result<T>>(1)
    Thread({ done.put(runCatching { call() }) }, what).apply { isDaemon = true }.start()
    return { (done.poll(30, TimeUnit.SECONDS) ?: fail("$what did not settle within 30s")).getOrThrow() }
}

/**
 * One fake-backed node: a [FakeSidecar], a [SidecarClient] over it, an
 * [IrohNode] with a chosen NodeId and a [DiscoveredPeering] driven by a
 * [ManualTimer] (`computenet-ktn1l.4`).
 *
 * The clock is a plain field the test writes, exactly as `DiscoveredPeeringTest`'s
 * single-node rig does: [advanceTo] moves it and releases every retry armed for
 * that instant, which is the only way time passes in this package
 * ([DSC2-DIAL-08], [DSC2-DIAL-09]).
 */
internal class FakeNode(
    val label: String,
    val fake: FakeSidecar,
    val client: SidecarClient,
    val node: IrohNode,
    val own: ByteArray,
    val side: Peering.Side,
) : AutoCloseable {

    @Volatile
    var now: Long = 0L

    val timer = ManualTimer { now }

    lateinit var peering: DiscoveredPeering

    /** Move this node's clock to [instant] and run every retry armed for it or earlier. */
    fun advanceTo(instant: Long) {
        now = instant
        timer.advanceTo(instant)
    }

    /** One `PEER_DISCOVERED`, as the sidecar's mDNS enumeration emits it. */
    fun discover(key: ByteArray, addresses: List<String> = listOf("127.0.0.1:1")) {
        fake.send(SidecarMessage.PeerDiscovered(key, addresses))
    }

    fun viewOf(key: ByteArray): PeerView? = peering.snapshot().firstOrNull { it.keyHex == NodeKey(key).hex }

    /** Every link this node holds for [key] right now. */
    fun links(key: ByteArray): List<IrohNode.LinkView> = node.links(key)

    override fun close() {
        runCatching { if (::peering.isInitialized) peering.close() }
        runCatching { client.close() }
        runCatching { fake.close() }
    }

    companion object {
        /**
         * Start a node and a [DiscoveredPeering] over a fresh fake, answering
         * the `LISTEN` and the `WATCH_PEERS` by hand.
         *
         * Both start calls block on a control reply, so each runs on its own
         * thread while this one plays the sidecar.
         */
        fun start(
            label: String,
            own: ByteArray,
            side: Peering.Side,
            policy: DialPolicy = DialPolicy(),
        ): FakeNode {
            val fake = FakeSidecar()
            val client = SidecarClient.connect(fake.port)
            val node = IrohNode(FixedSidecar(own), client, side)
            val self = FakeNode(label, fake, client, node, own, side)
            settle("$label node start") { node.start(30.seconds) }.let { started ->
                assertEquals(HostMessage.Listen, fake.nextHostMessage(), "$label LISTENs on its one shared client")
                fake.send(SidecarMessage.Listening(listOf("127.0.0.1:1")))
                started()
            }
            val startPeering = settle("$label peering start") {
                DiscoveredPeering.start(node, policy, clock = { self.now }, timer = self.timer)
            }
            assertEquals(HostMessage.WatchPeers, fake.nextHostMessage(), "$label subscribes to discovery")
            fake.send(SidecarMessage.Watching)
            self.peering = startPeering()
            return self
        }
    }
}

/**
 * Two fake-backed nodes and a LAN between them written by hand
 * (`computenet-ktn1l.4`, BS-08, `[DSC2-DIAL-05]`).
 *
 * ## Why a relay rather than two real sidecars
 *
 * BS-08 is a statement about what two nodes agree on when they dial each other
 * at the same time. Over real sidecars the answer depends on which dial the
 * network completes first, so a green run would be a sample rather than a
 * fact, and a red one would be a flake. Here **the test decides the order**:
 * every frame moves only when [pump] moves it, so the same scenario can be
 * played in several orders and the end state asserted to be the same one
 * ([DSC2-DIAL-08], [DSC2-DIAL-09] — no sidecar binary, no `Thread.sleep`).
 *
 * ## What it relays
 *
 * One link is two link ids: a `DIAL` from one node becomes an OUTBOUND link on
 * the dialler under the id the dialler chose, and an INBOUND link on the peer
 * under an id this rig chooses. [connect] records that pair; [pump] then moves
 * `DATA` from either end to the other end's id, and turns a `CLOSE_LINK` into
 * exactly one `LINK_DOWN` **per side** (`PROTOCOL.md` §3) — never two on the
 * same side, however many times a link is asked to close.
 */
internal class TwoNodeFakeRig(val a: FakeNode, val b: FakeNode) : AutoCloseable {

    /** One relayed link: which node holds which id. */
    private data class Pair2(val left: FakeNode, val leftLink: Long, val right: FakeNode, val rightLink: Long)

    private val pairs = mutableListOf<Pair2>()
    private val downed = ConcurrentHashMap.newKeySet<String>()

    /** `DIAL`s taken off each fake by [pump] and not yet connected, oldest first. */
    private val pendingDials = mapOf(
        a.label to LinkedBlockingDeque<HostMessage.Dial>(),
        b.label to LinkedBlockingDeque<HostMessage.Dial>(),
    )

    /** Frames this rig has moved. A stalled count is what "quiescent" means here. */
    val relayed = AtomicLong()

    private var nextInboundLink = 10_000L

    /** Every node, so a loop does not have to name them. */
    private val nodes = listOf(a, b)

    /**
     * The next `DIAL` [from] has written, pumping until one appears.
     *
     * Strict about nothing else: a `DIAL` may well arrive behind an admitted
     * peering's `DATA`, and [pump] moves that out of the way rather than
     * failing on it — while still failing if the dial never comes.
     */
    fun dialFrom(from: FakeNode, seconds: Long = 30): HostMessage.Dial {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            pendingDials.getValue(from.label).poll()?.let { return it }
            if (pump() == 0) pump(pollMillis = 200)
        }
        fail("${from.label} wrote no DIAL within ${seconds}s")
    }

    /**
     * Bring [dial] up as a peering: an OUTBOUND link on [from] under the id it
     * dialled, and a fresh INBOUND link on [to].
     *
     * Both `LINK_UP`s are written before anything is pumped, so the two nodes'
     * link registries agree that both directions exist before either hello is
     * judged — which is the state `[DSC2-DIAL-05]` is about.
     */
    fun connect(dial: HostMessage.Dial, from: FakeNode, to: FakeNode) {
        val inbound = nextInboundLink++
        pairs += Pair2(from, dial.link, to, inbound)
        from.fake.send(SidecarMessage.LinkUp(dial.link, to.own, DIRECTION_OUTBOUND))
        to.fake.send(SidecarMessage.LinkUp(inbound, from.own, DIRECTION_INBOUND))
    }

    /** The id [holder] holds for the link whose other end is [link] on the other node, or null. */
    private fun peerEnd(holder: FakeNode, link: Long): Pair<FakeNode, Long>? =
        pairs.firstOrNull { it.left === holder && it.leftLink == link }?.let { it.right to it.rightLink }
            ?: pairs.firstOrNull { it.right === holder && it.rightLink == link }?.let { it.left to it.leftLink }

    /**
     * Move every frame both fakes have written, once. Returns how many were
     * moved, so a caller can pump to quiescence without waiting on a clock.
     *
     * A `CLOSE_LINK` becomes one `LINK_DOWN` on each end of the pair, and
     * never a second one for an end that already had its — `PROTOCOL.md` §3
     * gives exactly one per side, and a link both sides decide to close would
     * otherwise be reported twice.
     */
    fun pump(pollMillis: Long = 0): Int {
        var moved = 0
        nodes.forEach { holder ->
            while (true) {
                val message = holder.fake.pollHostMessage(pollMillis) ?: break
                moved++
                relayed.incrementAndGet()
                when (message) {
                    is HostMessage.Dial -> pendingDials.getValue(holder.label).add(message)

                    is HostMessage.Data -> {
                        val far = peerEnd(holder, message.link)
                        if (far != null && "${far.first.label}:${far.second}" !in downed) {
                            far.first.fake.send(SidecarMessage.Data(far.second, message.payload))
                        }
                    }

                    is HostMessage.CloseLink -> {
                        deliverDown(holder, message.link, "closed by ${holder.label}")
                        peerEnd(holder, message.link)?.let { (peer, link) ->
                            deliverDown(peer, link, "peer closed the link")
                        }
                    }

                    else -> Unit
                }
            }
        }
        return moved
    }

    /** One `LINK_DOWN` for [link] on [holder], and never a second. */
    fun deliverDown(holder: FakeNode, link: Long, reason: String) {
        if (downed.add("${holder.label}:$link")) holder.fake.send(SidecarMessage.LinkDown(link, reason))
    }

    /**
     * Pump until nothing moves for [still] consecutive rounds.
     *
     * Each round waits up to [pollMillis] for a frame, so this returns as soon
     * as both hosts have gone silent rather than after a fixed delay — the
     * same discipline `quiesced` uses for a counter, applied to the wire.
     */
    fun quiesce(still: Int = 3, pollMillis: Long = 250) {
        var quiet = 0
        while (quiet < still) {
            quiet = if (pump(pollMillis) == 0) quiet + 1 else 0
        }
    }

    /** Every `DIAL` either fake has decoded, pending ones included. */
    fun dialsFrom(node: FakeNode): Long = node.fake.dials.get()

    /** Assert [node] holds exactly one peered link for [key], in [direction], and return it. */
    fun solePeering(node: FakeNode, key: ByteArray, direction: LinkDirection): IrohNode.LinkView {
        val peered = node.links(key).filter { it.peered }
        assertEquals(1, peered.size, "${node.label} holds ${peered.size} peered links for this key: $peered")
        assertEquals(direction, peered.single().direction, "${node.label} kept the wrong direction")
        return peered.single()
    }

    override fun close() {
        runCatching { a.close() }
        runCatching { b.close() }
    }

    companion object {
        /**
         * Two started nodes whose NodeIds are **sorted**: `a`'s is the smaller
         * under `Arrays.compareUnsigned`, so `a` is the node
         * [PeerTable.loserDirection] gives the OUTBOUND link to and `b` the one
         * it gives the INBOUND link to (aas-D7). The test never has to ask
         * which way round a random pair fell.
         */
        fun startSorted(
            policy: DialPolicy = DialPolicy(),
            binding: (own: ByteArray, peer: ByteArray) -> PeerIdentityBinding = { _, _ -> PeerIdentityBinding.Interim },
        ): TwoNodeFakeRig {
            var first = freshNodeId()
            var second = freshNodeId()
            if (java.util.Arrays.compareUnsigned(first, second) > 0) {
                val swap = first
                first = second
                second = swap
            }
            val a = FakeNode.start("A", first, sideWith(peer = PeerId("A"), binding = binding(first, second)), policy)
            val b = FakeNode.start("B", second, sideWith(peer = PeerId("B"), binding = binding(second, first)), policy)
            return TwoNodeFakeRig(a, b)
        }
    }
}
