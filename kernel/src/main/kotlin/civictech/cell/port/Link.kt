package civictech.cell.port

import civictech.gen.wire.NatureVector
import java.util.*

/**
 * CP-F3: reconcile a producer's [offered] natures against a consumer's
 * [required] natures, mapping a [Reconciliation.Refuse] onto the typed
 * [LinkResult.Rejected] the handshake returns. Null ⇒ [Reconciliation.Direct]
 * ⇒ the link proceeds exactly as today.
 */
private fun reconcileNatures(offered: NatureVector, required: NatureVector): LinkResult.Rejected? =
    when (val outcome = NatureNegotiation.reconcile(offered, required)) {
        Reconciliation.Direct -> null
        is Reconciliation.Refuse -> LinkResult.Rejected(
            outcome.mismatch,
            "nature mismatch on ${outcome.mismatch.axis}: producer offers " +
                "${outcome.mismatch.offered}, consumer requires ${outcome.mismatch.required} " +
                "(no adapter — typed refusal, CP-F3)",
        )
    }

/**
 * A first-class, directional connection from an outlet to an inlet (G-12).
 * The set of links is the topology.
 */
interface Link {
    val id: UUID
    val from: PortRef

    val to: PortRef

    /**
     * Endpoint objects where known in-process (G-13 minimal): the producer-
     * and consumer-side ports, used by generic protocols (12) to travel along
     * the link. Null for endpoints that are not reachable as objects
     * (`Use.fixed` ad-hoc endpoints, bridged links across the wire).
     */
    val fromPort: Port? get() = null
    val toPort: Port? get() = null

    /**
     * Peer-negotiated protocol ids reachable across this link when its
     * counterpart is not a local [Port] object (spec 41 point 4, G-35 phase
     * B). Empty for ordinary in-process links, whose [ProtocolSupport] relay
     * already reaches every locally-registered handler directly.
     */
    val protocolCapabilities: Set<ProtocolId> get() = emptySet()

    /**
     * Wire realization of a protocol send when [fromPort]/[toPort] is null
     * (G-35 phase B): bridged links populate this so [Protocols] can route a
     * message across the bridge instead of relaying through [ProtocolSupport]
     * — a plain callback slot, so `cell.port` stays transport-neutral (no
     * wire dependency lives here, only in whoever constructs the link).
     */
    val protocolBridge: ProtocolBridge? get() = null

    /** Idempotent: detaches both sides and fires the target port's onUnlink once. */
    fun unlink()

    /** Infrastructure lifecycle observer; invoked once after a successful detach. */
    fun onUnlink(listener: (Link) -> Unit) {}
}

/** See [Link.protocolBridge]. */
fun interface ProtocolBridge {
    fun send(id: ProtocolId, message: Any, upstream: Boolean)
}

sealed interface LinkResult {
    data class Connected(val link: Link) : LinkResult

    /**
     * A refused handshake. [reason] is the human string every existing call
     * site and test already asserts on; [mismatch] is the CP-F1 typed slot,
     * non-null only when a scoped-axis nature conflict caused the refusal
     * (CP-F3). The secondary constructor keeps every legacy `Rejected(reason)`
     * site — and their asserted strings — byte-for-byte intact.
     */
    data class Rejected(
        val mismatch: civictech.gen.wire.NatureMismatch?,
        val reason: String,
    ) : LinkResult {
        constructor(reason: String) : this(null, reason)
    }

    /**
     * The handshake runs on the target's host (cross-host proxy link); the outcome
     * is not observable by the initiator until the wire layer exists (M5).
     * A rejection on the target host is emitted to its dead-letter outlet.
     */
    data object Deferred : LinkResult
}

data class LinkRequest(
    val from: PortRef,
    val to: PortRef,
    /** Identity slot from day one (G-14); verification is future work (G-29). */
    val identity: Identity? = null,
    /** Consume vs Observe (spec 20/23 §Taps, 10/12 §Cardinality rule 2). */
    val role: LinkRole = LinkRole.Consume,
)

/**
 * The role a downstream attachment plays on a link's handshake (spec 20/23
 * §Taps; 10/12 §Cardinality rule 2 extension): a **Consume** link receives
 * the outlet's declared contract form and bears the consume-once/release
 * obligation, counted by the SPSC funnel; an **Observe** link (a tap)
 * receives a read-only `Borrowed` projection, valid only for the emitting
 * invocation, and is never counted — always admitted regardless of the
 * exclusive bit.
 */
enum class LinkRole { Consume, Observe }

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

/**
 * G-29 phase 1 (M8.2): who is asking. A bridge ingress stamps every delivered
 * invocation with its transport peer's id; handshakes running during that
 * delivery see it on [LinkRequest.identity]. Local links carry null (= this
 * process). Authentication of the name is future work (43) — today it
 * identifies the *connection*, which the transport vouches for.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PeerId")
data class PeerId(val name: String) : Identity, java.io.Serializable

/**
 * Ambient identity of the delivery being executed (set by the host around
 * bridged management invocations, read by [handshake]).
 */
object CurrentPeer {
    private val local = ThreadLocal<PeerId?>()

    fun get(): PeerId? = local.get()

    fun <R> with(peer: PeerId?, block: () -> R): R {
        val previous = local.get()
        local.set(peer)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }
}

/**
 * Deny-by-default building block (M8.3): remote identities not in [peers]
 * are rejected; local requests (null identity) pass — boundary control, not
 * ambient suspicion (spec 43 posture).
 */
fun allowPeers(vararg peers: PeerId): LinkPolicy = LinkPolicy { request ->
    when (request.identity) {
        null -> null
        in peers -> null
        else -> LinkResult.Rejected("peer ${request.identity} is not on the allowlist (spec 43)")
    }
}

/** Link-time policy; composable, first rejection wins. Null = no objection. */
fun interface LinkPolicy {
    fun evaluate(request: LinkRequest): LinkResult.Rejected?
}

/** A port that participates in link handshakes and tracks its links. */
interface Linked {
    val linking: LinkSupport
}

/** Per-port link state: registered links, handshake hooks, link-time policies. */
class LinkSupport {
    private val active = LinkedHashMap<UUID, Link>()

    val links: Collection<Link> get() = active.values.toList()

    /** Handshake hook on the serving port; default accepts. */
    var onLink: (Link) -> LinkResult = { LinkResult.Connected(it) }

    /**
     * Post-install hook, fired on both sides once the link is installed and
     * registered — unlike [onLink], the counterpart is reachable here. The
     * source-side seam for late-join catch-up (G-22, spec 21).
     */
    var onLinked: (Link) -> Unit = {}
    var onUnlink: (Link) -> Unit = {}
    val policies = mutableListOf<LinkPolicy>()

    /**
     * Multicast siblings of [onLinked]/[onUnlink] for infrastructure (generic
     * protocols, attention) that must observe links without stealing the
     * single cell-facing hook slots above.
     */
    internal val onLinkedListeners = mutableListOf<(Link) -> Unit>()
    internal val onUnlinkListeners = mutableListOf<(Link) -> Unit>()

    /**
     * Re-fire every on-link hook for an already-installed [link] (PN-9): the
     * single cell-facing [onLinked] slot **and** the [onLinkedListeners]
     * multicast. Used by anti-entropy re-announce paths that push catch-up over
     * an existing link — since catch-up moved to the multicast, a bare
     * `onLinked(link)` would no longer reach it.
     */
    fun fireLinked(link: Link) {
        onLinked(link)
        onLinkedListeners.forEach { it(link) }
    }

    fun reject(request: LinkRequest): LinkResult.Rejected? =
        policies.firstNotNullOfOrNull { it.evaluate(request) }

    fun register(link: Link) {
        active[link.id] = link
    }

    fun remove(link: Link): Boolean = active.remove(link.id) != null
}

/**
 * Runs the target-side handshake shared by the inlet implementations:
 * policies → cardinality (checked by the caller) → onLink → install.
 */
internal fun <Api> handshake(
    portOut: LinkTo<Api>,
    target: Linked,
    targetRef: PortRef,
    role: LinkRole = LinkRole.Consume,
    install: () -> Unit,
    uninstall: (Link) -> Unit,
): LinkResult {
    val support = target.linking
    // identity rides the delivery (M8.2): bridged requests carry their peer
    support.reject(LinkRequest(portOut.ref, targetRef, CurrentPeer.get(), role))?.let { return it }

    // CP-F3: reconcile the two port nature-vectors after policies, before
    // install. Direct (the same-nature/default fast path) costs nothing; a
    // scoped-axis conflict becomes a loud typed refusal where today the
    // mismatch would drop silently at first emission.
    reconcileNatures(portOut.natures, (target as? Port)?.natures ?: NatureVector.DEFAULT)
        ?.let { return it }

    val sourceLinking = (portOut as? Linked)?.linking
    val link = PortLink(portOut.ref, targetRef, portOut, target as? Port) { link ->
        // The close is terminal on the link's in-process protocol/data FIFO:
        // announce it while both endpoints are still reachable, then detach.
        link.toPort?.let { port ->
            val protocols = ProtocolSupport.of(port)
            if (protocols.handles(Protocols.TopologyOrder)) {
                protocols.deliver(Protocols.TopologyOrder, link, EdgeClose)
            }
        }
        uninstall(link)
        sourceLinking?.remove(link)
        support.remove(link)
        support.onUnlink(link)
        support.onUnlinkListeners.forEach { it(link) }
        sourceLinking?.onUnlinkListeners?.forEach { it(link) }
    }
    return when (val result = support.onLink(link)) {
        is LinkResult.Connected -> {
            install()
            support.register(link)
            sourceLinking?.register(link)
            // Only topology-interested consumers pay for edge markers.  Open
            // precedes onLinked catch-up and every subsequent data invocation.
            link.toPort?.let { port ->
                val protocols = ProtocolSupport.of(port)
                if (protocols.handles(Protocols.TopologyOrder)) {
                    protocols.deliver(Protocols.TopologyOrder, link, EdgeOpen)
                }
            }
            support.onLinked(link)
            sourceLinking?.onLinked?.invoke(link)
            support.onLinkedListeners.forEach { it(link) }
            sourceLinking?.onLinkedListeners?.forEach { it(link) }
            result
        }

        else -> result
    }
}

/**
 * Handshake for a pre-built [link] whose counterpart is not a local [Port] —
 * a bridged `WireEdgeLink` resolved across the wire (spec 41 point 4, closes
 * C-13). Runs the same target-side gate a local link runs: the [local] port's
 * link policies + peer allowlist (43, identity from [CurrentPeer]) and its
 * `onLink` admission; on acceptance installs the given [link] (rather than
 * minting a `PortLink`), wires the unlink teardown + the `onLinked`/`onUnlink`
 * multicast hooks, and — for the producer side ([fireEdgeOpen]) — emits
 * `EdgeOpen` downstream across the bridge through the negotiated protocol path
 * (the consumer side instead receives it as an ordinary in-band frame).
 *
 * This is the overload `bridgeTo`/`bridgeFrom` route through so a bridged edge
 * negotiates identically to a local one; transport stays out of `cell.port`
 * (the caller supplies the already-resolved [link] and its `protocolBridge`).
 */
internal fun handshake(
    link: Link,
    from: PortRef,
    targetRef: PortRef,
    local: Linked,
    role: LinkRole = LinkRole.Consume,
    fireEdgeOpen: Boolean = false,
    counterpart: NatureVector = NatureVector.DEFAULT,
): LinkResult {
    val support = local.linking
    support.reject(LinkRequest(from, targetRef, CurrentPeer.get(), role))?.let { return it }

    // CP-F3: the bridged edge runs the *same* pure reconcile as a local link,
    // so the verdict is location-transparent (BridgedHandshakeTest asserts
    // localVerdict == remoteVerdict). The remote endpoint's vector arrives as
    // [counterpart]; today's callers pass DEFAULT (additive, zero behavior
    // change) — carrying the peer's descriptor vector across the wire is a
    // follow-on. `fireEdgeOpen` marks the producer side, fixing which vector is
    // offered vs required.
    val localNatures = (local as? Port)?.natures ?: NatureVector.DEFAULT
    val offered = if (fireEdgeOpen) localNatures else counterpart
    val required = if (fireEdgeOpen) counterpart else localNatures
    reconcileNatures(offered, required)?.let { return it }

    return when (val result = support.onLink(link)) {
        is LinkResult.Connected -> {
            support.register(link)
            link.onUnlink { l ->
                support.remove(l)
                support.onUnlink(l)
                support.onUnlinkListeners.forEach { it(l) }
            }
            if (fireEdgeOpen) {
                // Only topology-interested peers pay: crosses the wire iff the
                // remote inlet handles TopologyOrder, exactly as the local path.
                Protocols.sendDownstream(link, Protocols.TopologyOrder, EdgeOpen)
            }
            support.onLinked(link)
            support.onLinkedListeners.forEach { it(link) }
            result
        }

        else -> result
    }
}

internal class PortLink(
    override val from: PortRef,
    override val to: PortRef,
    override val fromPort: Port? = null,
    override val toPort: Port? = null,
    private val doUnlink: (PortLink) -> Unit,
) : Link {
    override val id: UUID = UUID.randomUUID()
    private var active = true
    private val unlinkListeners = mutableListOf<(Link) -> Unit>()

    override fun onUnlink(listener: (Link) -> Unit) {
        if (active) unlinkListeners += listener else listener(this)
    }

    override fun unlink() {
        if (!active) return
        active = false
        doUnlink(this)
        unlinkListeners.toList().forEach { it(this) }
        unlinkListeners.clear()
    }
}
