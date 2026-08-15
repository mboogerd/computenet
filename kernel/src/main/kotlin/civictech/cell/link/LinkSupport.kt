package civictech.cell.link

import civictech.cell.port.PortRef
import java.util.UUID

/** A port that participates in link handshakes and tracks its links. */
interface Linked {
    val linking: LinkSupport
}

/** Per-port link state: registered links, handshake hooks, link-time policies. */
class LinkSupport {
    private val active = LinkedHashMap<UUID, Link>()

    /**
     * The [Identity] each registered link was ESTABLISHED with ([SEC1-10]).
     * A [LinkRequest] carries its identity only for the duration of the
     * handshake, but re-authorization at a later rebind — promotion, spec 43
     * §"The three seams" item 2 — must re-present the *establishing* peer, not
     * whoever happens to be ambient when the rebind runs. Sparse on purpose:
     * only a link established with a stamped peer has an entry, so a local
     * (null-identity) link costs nothing and resolves to null, which every
     * [LinkPolicy] treats as a local request ([allowPeers], spec 43 posture).
     */
    private val establishedBy = LinkedHashMap<UUID, Identity>()

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

    /**
     * Re-evaluate this port's [policies] for an ALREADY-established link that
     * is being rebound (spec 43 §"The three seams" item 2, 93 I-28 §4.3 seam 2:
     * "promotion IS a rebind and MUST re-authorize"). Deliberately the same
     * first-rejection-wins walk [reject] does — re-authorization asks the very
     * question admission asked, so a divergence between the two would be the
     * bug — named separately so a call site reads as what it is, and so the two
     * can diverge later without silently changing admission.
     */
    fun reauthorize(request: LinkRequest): LinkResult.Rejected? = reject(request)

    fun register(link: Link) = register(link, null)

    /**
     * Register [link], retaining the [identity] that established it (see
     * [establishedBy]) for later re-authorization. Additive: the single-argument
     * [register] delegates here with a null identity, so attach paths that never
     * saw a peer ([civictech.cell.port.StreamTo], direct subscribes) are
     * unchanged.
     */
    fun register(link: Link, identity: Identity?) {
        active[link.id] = link
        if (identity != null) establishedBy[link.id] = identity
    }

    /**
     * The identity that established this port's link to [targetRef], or null
     * when the link was made without a stamped peer — or by a path that runs no
     * handshake at all (a direct `FanOutlet.subscribe` / `Use.fixed` endpoint,
     * and the relink [civictech.cell.evolve.Promotion] performs at COMMIT).
     * Callers MUST treat null as "local request", exactly as [allowPeers] does:
     * absence of a retained identity is not evidence of a remote one.
     *
     * Most recent registration wins when a port has been re-linked to the same
     * [targetRef] more than once — that is the binding currently installed.
     */
    fun identityFor(targetRef: PortRef): Identity? =
        active.values.lastOrNull { it.to == targetRef }?.let { establishedBy[it.id] }

    fun remove(link: Link): Boolean {
        establishedBy.remove(link.id)
        return active.remove(link.id) != null
    }
}
