package civictech.cell.link

import java.util.UUID

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
