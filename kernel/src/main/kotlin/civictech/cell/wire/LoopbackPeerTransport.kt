package civictech.cell.wire

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-kernel [PeerTransport] binding, scheme `loopback`: [Peering.loopback]
 * behind the seam, so kernel tests and `civictech.testkit.PeerTransportContract`
 * have a binding without `:wire` or `:iroh` on the classpath (gyvli-D1).
 *
 * **Rig-scoped, like `:demo:beadsmirror`'s `TwoNodeRig` shared `PeerTransport`
 * instance.** Both ends of a loopback peering live in one process, so one
 * instance is shared by the two sides: [listen] records a side under a name,
 * and [dial] pairs with the recorded listener through
 * `Peering.loopback(listener side, dialling side)`.
 * An address is `loopback://<name>`; an empty name asks for a fresh one (the
 * loopback's "port 0"), which [PeerListener.boundAddress] then reports.
 *
 * **Reconnect runs through [ReconnectPolicy] like a socket binding's.** A
 * loopback link never drops on its own, so the only link-downs are the ones
 * the seam causes: [PeerConnection.partition] (the policy is [severed]
 * [ReconnectPolicy.severed] first), [PeerConnection.close] (the policy is
 * closed [deliberately][ReconnectPolicy.closeDeliberately] first), and the
 * listener's [close][PeerListener.close] (neither — an undeliberate drop from
 * the dialler's point of view). Every link-down asks the policy whether to
 * re-dial, and every re-dial attempt asks again before it opens, on the
 * policy's backoff. So on this binding, as on the socket ones, it is the
 * policy's intent flags — not the absence of a reconnect loop — that keep a
 * partition partitioned and a closed connection closed. A re-dial whose
 * listener has gone keeps retrying on the backoff, as a socket dial into a
 * down port does, until a listener re-binds the name or the connection is
 * closed.
 *
 * **Stats.** Frames are counted at [Peering.loopback]'s two interpose seams —
 * between each egress and the far ingress — rather than inside [Peering].
 * A loopback direction loses nothing, so a direction's sent and received
 * counts are the same number seen from its two ends.
 * [PeerStats.refusedAnnouncements] reads the ingress's
 * `announcement-admission` denials.
 *
 * **Credentials.** A loopback exchanges no hello, but it refuses the side a
 * socket binding would ([helloLimits], by default the ceiling both socket
 * hellos declare), so a rig built on loopback fails where the same rig on a
 * socket would, at the same call.
 *
 * @param backoff the re-dial schedule; a test drives it near zero.
 * @param nanoTime the clock the policy's refusal window is read against.
 */
class LoopbackPeerTransport(
    private val backoff: (attempt: Int) -> Long = ReconnectPolicy.DEFAULT_BACKOFF,
    val helloLimits: HelloCredentialLimits = DEFAULT_HELLO_LIMITS,
    private val nanoTime: () -> Long = System::nanoTime,
) : PeerTransport {

    /** A loopback address: a listener's name within one [LoopbackPeerTransport]. */
    data class LoopbackAddress(val name: String) : PeerAddress {
        init {
            require(' ' !in name && '/' !in name) { "a loopback listener name holds no space or '/': '$name'" }
        }

        override val scheme: String get() = SCHEME
        override val text: String get() = "$PREFIX$name"
    }

    override val scheme: String get() = SCHEME

    private val listeners = ConcurrentHashMap<String, Listener>()

    override fun parseAddress(text: String): PeerAddress {
        require(text.startsWith(PREFIX)) { "not a $SCHEME address (expected $PREFIX<name>): '$text'" }
        return LoopbackAddress(text.removePrefix(PREFIX))
    }

    private fun own(address: PeerAddress): LoopbackAddress {
        require(address.scheme == SCHEME) { "a $SCHEME transport cannot serve a ${address.scheme} address: ${address.text}" }
        return address as? LoopbackAddress ?: parseAddress(address.text) as LoopbackAddress
    }

    override fun listen(address: PeerAddress, side: Peering.Side): PeerListener {
        helloLimits.requireSendable(side)
        val requested = own(address).name
        val name = requested.ifEmpty { "listener-${UUID.randomUUID()}" }
        val listener = Listener(LoopbackAddress(name), side)
        check(listeners.putIfAbsent(name, listener) == null) { "a loopback listener is already bound at $PREFIX$name" }
        return listener
    }

    override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection {
        helloLimits.requireSendable(side)
        val target = own(address)
        val listener = checkNotNull(listeners[target.name]) { "no loopback listener is bound at ${target.text}" }
        return Connection(target, side).also { it.connect(listener) }
    }

    /** A recorded listening side. Closing it drops every connection peered with it, undeliberately. */
    private inner class Listener(override val boundAddress: LoopbackAddress, override val side: Peering.Side) :
        PeerListener {

        val connections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()

        @Volatile
        var closed = false
            private set

        override val stats: PeerStats
            get() = connections.fold(PeerStats(0, 0, 0, 0)) { acc, c ->
                PeerStats(
                    framesSent = acc.framesSent + c.receivedFrames.get(),
                    framesReceived = acc.framesReceived + c.sentFrames.get(),
                    unadmittedOpens = 0,
                    refusedAnnouncements = acc.refusedAnnouncements + c.listenerSideRefusals(),
                )
            }

        override fun close() {
            if (closed) return
            closed = true
            listeners.remove(boundAddress.name, this)
            connections.toList().forEach { it.listenerGone(this) }
        }
    }

    private inner class Connection(private val target: LoopbackAddress, override val side: Peering.Side) :
        PeerConnection {

        private val policy = ReconnectPolicy(backoff)

        /** Frames this (dialling) side sent — `Peering.loopback`'s b-to-a direction. */
        val sentFrames = AtomicLong()

        /** Frames this side received — the a-to-b direction. */
        val receivedFrames = AtomicLong()

        // All guarded by this connection's monitor.
        private var loopback: Peering.Loopback? = null
        private var peeredWith: Listener? = null
        private var carrying = false
        private var redialPending = false

        override val isCarrying: Boolean get() = synchronized(this) { carrying }

        override val stats: PeerStats
            get() = PeerStats(
                framesSent = sentFrames.get(),
                framesReceived = receivedFrames.get(),
                unadmittedOpens = policy.unadmittedOpens,
                refusedAnnouncements = synchronized(this) { loopback?.ingressOnB }
                    ?.boundaryDenials?.get(ANNOUNCEMENT_EXPOSURE)?.denialCount ?: 0L,
            )

        fun listenerSideRefusals(): Long =
            synchronized(this) { loopback?.ingressOnA }?.boundaryDenials?.get(ANNOUNCEMENT_EXPOSURE)?.denialCount ?: 0L

        /** The first dial. */
        @Synchronized
        fun connect(listener: Listener) {
            check(open(listener)) { "the loopback connection to ${target.text} refused its own first dial" }
        }

        /**
         * Open a link to [listener], if the policy still wants one — asked at
         * the moment of opening, so a sever or close that raced the attempt
         * wins (computenet-g1aua's shape).
         */
        private fun open(listener: Listener): Boolean {
            if (!policy.admitDial(nanoTime())) return false
            val current = loopback
            if (current != null && peeredWith === listener) {
                current.heal() // a fresh connection instance on the same frame links
            } else {
                // `a` is the listener, `b` this dialling side (the interposers count per direction)
                loopback = Peering.loopback(
                    listener.side,
                    side,
                    interposeAToB = counting(receivedFrames),
                    interposeBToA = counting(sentFrames),
                )
                peeredWith?.connections?.remove(this)
                peeredWith = listener
                listener.connections += this
            }
            policy.onAdmitted() // a loopback has no hello to refuse
            carrying = true
            return true
        }

        /** Take the link down and ask the policy whether to come back. */
        private fun linkDown() {
            if (carrying) {
                loopback?.partition()
                carrying = false
                policy.onClosed(nanoTime())
            }
            if (policy.shouldRedial()) scheduleRedial(0)
        }

        private fun scheduleRedial(attempt: Int) {
            if (redialPending) return
            redialPending = true
            REDIAL.schedule({ redial(attempt) }, policy.nextDelayMs(attempt), TimeUnit.MILLISECONDS)
        }

        @Synchronized
        private fun redial(attempt: Int) {
            redialPending = false
            if (carrying || !policy.shouldRedial()) return
            val listener = listeners[target.name]
            if (listener == null || listener.closed || !open(listener)) {
                if (policy.shouldRedial()) scheduleRedial(attempt + 1)
            }
        }

        @Synchronized
        override fun partition() {
            check(!policy.deliberateClose) { "the loopback connection to ${target.text} is closed" }
            policy.sever()
            linkDown()
        }

        @Synchronized
        override fun heal() {
            check(!policy.deliberateClose) { "the loopback connection to ${target.text} is closed; dial a new one" }
            policy.heal()
            val listener = listeners[target.name]
            check(listener != null && !listener.closed) { "no loopback listener is bound at ${target.text}" }
            if (carrying) {
                // supersede the live instance, as a re-hello does
                carrying = false
                policy.onClosed(nanoTime())
            }
            check(open(listener)) { "the loopback connection to ${target.text} could not re-open" }
        }

        @Synchronized
        fun listenerGone(listener: Listener) {
            if (peeredWith === listener) linkDown()
        }

        @Synchronized
        override fun close() {
            if (policy.deliberateClose) return
            policy.closeDeliberately() // before the link goes down: nothing re-arms
            linkDown()
            peeredWith?.connections?.remove(this)
        }
    }

    companion object {
        const val SCHEME: String = "loopback"
        private const val PREFIX: String = "$SCHEME://"
        private const val ANNOUNCEMENT_EXPOSURE = "announcement-admission"

        /** The limits both socket hellos declare: 8 statements, a space-free non-empty name. */
        val DEFAULT_HELLO_LIMITS: HelloCredentialLimits = HelloCredentialLimits(
            maxStatements = HelloCredentialLimits.DEFAULT_MAX_STATEMENTS,
            tokenOk = HelloCredentialLimits.NAME_TOKEN,
            lineName = "loopback hello",
        )

        private fun counting(counter: AtomicLong) = Peering.FrameInterpose { frame ->
            counter.incrementAndGet()
            listOf(frame)
        }

        /** One daemon thread for every loopback's re-dial timers; the work it runs is short and non-blocking. */
        private val REDIAL: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "loopback-peer-redial").apply { isDaemon = true }
        }
    }
}

/** ServiceLoader entry for [LoopbackPeerTransport]; [create] ignores its config. */
class LoopbackPeerTransportProvider : PeerTransportProvider {
    override val scheme: String get() = LoopbackPeerTransport.SCHEME

    override fun create(config: Map<String, String>): PeerTransport = LoopbackPeerTransport()
}
