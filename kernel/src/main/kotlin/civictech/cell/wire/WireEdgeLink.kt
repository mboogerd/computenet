package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.port.EdgeClose
import civictech.cell.port.Link
import civictech.cell.port.LinkResult
import civictech.cell.port.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolBridge
import civictech.cell.port.ProtocolId
import civictech.cell.port.Protocols
import civictech.cell.port.handshake
import civictech.cell.port.natures
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
import civictech.gen.wire.NatureVector
import civictech.gen.wire.ProtocolRegistry
import java.util.UUID

/**
 * A port's addressable identity for routing (spec 41 point 4): `PortRef`
 * alone carries no name (it is an opaque comparison key, resolved by
 * `PortRegistry.of(cell)[name]`), so wire routing needs the pair explicitly.
 */
data class PortAddress(val cell: CellRef, val port: String)

/**
 * A [Link] whose counterpart lives across a bridge (spec 41 point 4, G-35/
 * G-39 phase B): unlike an ad-hoc `Use.fixed` subscription, this link has
 * real identity — [id]/[from]/[to] survive repeated frames for the same
 * logical edge — so [ProtocolSupport] bookkeeping keyed by link id
 * (`Attention.linkLevels`, `GlitchFreeCell.edges`) behaves as it would for
 * an in-process link.
 *
 * [fromPort]/[toPort] are null until a delivery on the destination host
 * overlays the real local [Port] (`ManagedHost`'s `DirectedProtocolLink`);
 * [protocolBridge] uses [sink] to route a message to the *other* endpoint's
 * address — for a link constructed to send (via [bridgeTo]/[bridgeFrom]),
 * that is the peer's bridge egress; for a link reconstructed from a decoded
 * frame, that is the reverse path a peer connection already maintains
 * (typically `LocationRegistry::deliver`), so an upstream reply re-resolves
 * exactly like any other remote send (spec 41 point 3).
 */
class WireEdgeLink(
    override val id: UUID,
    override val from: PortRef,
    override val to: PortRef,
    val fromAddr: PortAddress,
    val toAddr: PortAddress,
    override val protocolCapabilities: Set<ProtocolId> = emptySet(),
    private val sink: InvocationSink? = null,
    override val fromPort: Port? = null,
    override val toPort: Port? = null,
    /**
     * CP-G2: the natures of *this link's sending endpoint* (the producer for a
     * `bridgeTo` link). Rides the `EdgeOpen` frame ([WireCodec]) so the peer's
     * handshake reconciles the real cross-host vector; DEFAULT ⇒ zero wire bytes.
     */
    val natures: NatureVector = NatureVector.DEFAULT,
) : Link {
    @Volatile private var active = true
    private val unlinkListeners = mutableListOf<(Link) -> Unit>()

    override val protocolBridge: ProtocolBridge? = sink?.let { s ->
        ProtocolBridge { protocolId, message, upstream ->
            val target = if (upstream) fromAddr else toAddr
            s.deliver(
                HostedPortInvocation(
                    cellRef = target.cell,
                    portName = target.port,
                    type = HostedPortInvocation.Type.PORT_PROTOCOL,
                    invocation = Invocation("", emptyList(), emptyList()),
                    protocolId = protocolId,
                    protocolLink = this,
                    protocolMessage = message,
                ),
            )
        }
    }

    /** Attaches a working reply [sink] and negotiated [capabilities] to a bare, decode-reconstructed link. */
    fun withBridge(sink: InvocationSink, capabilities: Set<ProtocolId>): WireEdgeLink =
        WireEdgeLink(id, from, to, fromAddr, toAddr, capabilities, sink, fromPort, toPort, natures)

    override fun onUnlink(listener: (Link) -> Unit) {
        if (active) unlinkListeners += listener else listener(this)
    }

    override fun unlink() {
        if (!active) return
        active = false
        unlinkListeners.toList().forEach { it(this) }
        unlinkListeners.clear()
    }
}

/** Default capability set: every protocol this process's [ProtocolRegistry] knows (see 41 point 4). */
fun defaultProtocolCapabilities(): Set<ProtocolId> =
    ProtocolRegistry.protocols.mapTo(mutableSetOf()) { ProtocolId(it.protocolId) }

/**
 * Establishes the producer-side half of a bridged link from [outlet] (at
 * [selfAddr]) to a remote inlet at [toAddr] (spec 41 point 4): registers a
 * real [WireEdgeLink] on the outlet's own link bookkeeping (so generic
 * protocols — attention, suspension — walk it like any in-process link) and
 * fires `EdgeOpen` now, carried across [sink] since the remote side has no
 * local port object here.
 */
fun <T> T.bridgeTo(
    selfAddr: PortAddress,
    toAddr: PortAddress,
    sink: InvocationSink,
    capabilities: Set<ProtocolId> = defaultProtocolCapabilities(),
    /**
     * CP-G2: the remote inlet's required natures (its descriptor vector, arrived
     * over the reverse leg). DEFAULT ⇒ today's behavior — the producer accepts
     * the link without knowing the consumer's requirement. Supplying the real
     * vector turns a genuine cross-host mismatch into a link-time refusal, the
     * same typed [civictech.gen.wire.NatureMismatch] the consumer side reaches.
     */
    counterpart: NatureVector = NatureVector.DEFAULT,
): LinkResult where T : Linked, T : Port {
    val link = WireEdgeLink(
        id = UUID.randomUUID(),
        from = ref,
        to = PortRef.generate(toAddr.cell),
        fromAddr = selfAddr,
        toAddr = toAddr,
        protocolCapabilities = capabilities,
        sink = sink,
        fromPort = this,
        // this producer's own natures ride the EdgeOpen frame to the consumer
        natures = natures,
    )
    // Route through the shared handshake (C-13): source-side onLink admission
    // + onLinked catch-up hooks run, and EdgeOpen is fired downstream over the
    // negotiated protocol path (crossing the wire) rather than raw.
    return handshake(link, from = ref, targetRef = link.to, local = this, fireEdgeOpen = true, counterpart = counterpart)
}

/**
 * Establishes the consumer-side half of a bridged link on [inlet] (at
 * [selfAddr]) from a remote outlet at [fromAddr] (spec 41 point 4):
 * registers a real [WireEdgeLink] on the inlet's own link bookkeeping so an
 * upstream emission (e.g. `AttentionSupport.emitUpstream`) walking
 * `inlet.linking.links` finds it and can route the reply across [sink].
 *
 * Does not itself fire `EdgeOpen` — that crosses the wire exactly once, from
 * the producer's [bridgeTo] call, landing on this inlet's real port via the
 * ordinary `PORT_PROTOCOL` delivery path (so a glitch-free join sees the
 * same single open event it would from an in-process handshake).
 */
fun <T> T.bridgeFrom(
    selfAddr: PortAddress,
    fromAddr: PortAddress,
    sink: InvocationSink,
    capabilities: Set<ProtocolId> = defaultProtocolCapabilities(),
    /**
     * CP-G2: the remote outlet's offered natures, carried across the wire in the
     * producer's `EdgeOpen` frame (read back off the decoded [WireEdgeLink] via
     * [WireEdgeLink.natures]). DEFAULT ⇒ a peer that predates the frame field,
     * or today's caller ⇒ today's behavior verbatim (additive default).
     */
    counterpart: NatureVector = NatureVector.DEFAULT,
): LinkResult where T : Linked, T : Port {
    val link = WireEdgeLink(
        id = UUID.randomUUID(),
        from = PortRef.generate(fromAddr.cell),
        to = ref,
        fromAddr = fromAddr,
        toAddr = selfAddr,
        protocolCapabilities = capabilities,
        sink = sink,
        toPort = this,
        natures = natures,
    )
    // Route through the shared handshake (C-13): this is the *target* side, so
    // the inlet's link policies and the peer allowlist (43) now fire on a
    // bridged edge exactly as on a local one. EdgeOpen is NOT fired here — it
    // crosses the wire once from the producer's bridgeTo and lands on this
    // inlet's real port via the ordinary PORT_PROTOCOL delivery path.
    return handshake(link, from = link.from, targetRef = ref, local = this, fireEdgeOpen = false, counterpart = counterpart)
}

/** Tears down a bridged half-link: fires `EdgeClose` (crossing the bridge when the peer is remote) and detaches. */
fun unlinkBridge(link: Link, downstream: Boolean) {
    if (downstream) Protocols.sendDownstream(link, Protocols.TopologyOrder, EdgeClose)
    else Protocols.sendUpstream(link, Protocols.TopologyOrder, EdgeClose)
    link.unlink()
}
