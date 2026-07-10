package civictech.cell.port

import java.util.*

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

    /** Idempotent: detaches both sides and fires the target port's onUnlink once. */
    fun unlink()
}

sealed interface LinkResult {
    data class Connected(val link: Link) : LinkResult
    data class Rejected(val reason: String) : LinkResult

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
)

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

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
    install: () -> Unit,
    uninstall: (Link) -> Unit,
): LinkResult {
    val support = target.linking
    support.reject(LinkRequest(portOut.ref, targetRef))?.let { return it }

    val sourceLinking = (portOut as? Linked)?.linking
    val link = PortLink(portOut.ref, targetRef, portOut, target as? Port) { link ->
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
            support.onLinked(link)
            sourceLinking?.onLinked?.invoke(link)
            support.onLinkedListeners.forEach { it(link) }
            sourceLinking?.onLinkedListeners?.forEach { it(link) }
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

    override fun unlink() {
        if (!active) return
        active = false
        doUnlink(this)
    }
}
