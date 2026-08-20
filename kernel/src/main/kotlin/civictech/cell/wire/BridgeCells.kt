package civictech.cell.wire

import civictech.cell.BoundaryDenialAccounting
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.link.AuthLevel
import civictech.cell.link.PeerId
import civictech.cell.port.FanOutlet
import civictech.cell.protocol.ProtocolId
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import java.util.UUID

/**
 * The network bridge as ordinary cells (spec 41 point 4): between egress and
 * ingress only bytes travel — an in-process loopback link (M5.3) or a socket
 * (M5.5). Because bridges are cells with ordinary ports and links, policies,
 * membranes and supervision apply to network crossings with no special
 * casing. P1: fully meaningful on the SimulationController — the generative
 * harness exercises the whole wire format without a network.
 */
class BridgeEgressCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, InvocationSink {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<ByteArray>>())

    /** Proxies use this cell as their [InvocationSink]; every send becomes a frame. */
    override fun deliver(invocation: HostedPortInvocation) {
        val args = invocation.invocation.args
        // spec 23 corollary: a lease on a remote pool is meaningless
        require(args.none { it is Leased<*> }) {
            "Leased payloads must not cross machine boundaries (spec 23) — freeze or copy first"
        }
        val frame = WireCodec.encode(invocation)
        // move-by-serialize: the sender's reference dies with the encode (spec 23)
        args.forEach { (it as? Owned<*>)?.consume() }
        outlet.call.propagate(frame)
    }
}

/**
 * Frames in, local delivery out: decode and hand to [deliverTo] — typically
 * the receiving side's `LocationRegistry::deliver`, so parked/replayed
 * semantics apply to remote traffic unchanged (spec 41 point 5). Decode
 * failures throw: the hosting host's supervision policy decides, like any
 * other cell failure.
 *
 * An admission refusal (seam 1, spec 40/43, `[SEC1-07]`) is different: it is
 * accounted through [boundaryDenials] rather than thrown, so it is never
 * classified as a cell fault — see [admit]. Only a genuine decode failure
 * (a frame that passed admission but is not a well-formed [WireCodec] frame)
 * is a fault on this cell.
 *
 * Eager cell (C-7): serves in `init` so it composes host-free.
 */
class BridgeIngressCell(
    private val deliverTo: InvocationSink,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    /** Transport identity of the peer this ingress receives from (M8.2); stamps every delivery. */
    private val peer: PeerId? = null,
    /**
     * How strongly [peer] is vouched for on *this connection* (DSC1
     * `[DSC1-HELLO-05]`): stamped onto every delivery beside [peer], so a cell
     * reading `civictech.cell.membrane.currentPrincipal()` observes
     * `Principal.Peer(peer, peerAuth)`.
     *
     * **Bound once, by the caller, at the admission decision** — never derived
     * from a frame. `Peering.hostIngress` is the only production constructor
     * of this cell, and both of its callers fix the value before the ingress
     * exists: `WsTransport.Session.bindAndAnnounce` passes the level its
     * admission row decided, and `Peering.loopback` passes the level its two
     * `Side` configurations imply. That is what makes the happens-before on
     * `RegistryMirrorCell.peer` hold for the level too (`[DSC1-HELLO-13]`): no
     * delivery can observe a level that later changes, because this field is a
     * `val` fixed before the first frame can arrive.
     *
     * Defaults to [AuthLevel.TransportVouched] — today's behaviour, byte for
     * byte, for every caller that does not mention authentication
     * (`[DSC1-WIRE-06]`).
     */
    private val peerAuth: AuthLevel = AuthLevel.TransportVouched,
    /**
     * Boundary admission (M8.3, spec 43 mechanism 2): allowlists are bridge
     * configuration, not a protocol fork. A refused frame is refused before
     * [WireCodec.decode] runs and before any delivery reaches the local
     * registry ([SEC1-06]): a typed [civictech.cell.BoundaryDenial] naming the
     * refused [PeerId] is emitted through [boundaryDenials] and this cell's
     * denial counter increments ([SEC1-07]). Nothing is thrown, so the
     * refusal is never classified as a cell fault that triggers supervision
     * RESTART or escalation — a denial is not a fault (BS-14).
     */
    private val admit: (PeerId?) -> Boolean = { true },
    /**
     * Reverse-direction sink for upstream protocol replies over a
     * wire-reconstructed [WireEdgeLink] (spec 41 point 4, G-35 phase B) —
     * "the reverse bridge path a cross-host link already maintains for
     * re-resolution". Defaults to [deliverTo] itself: when that is a
     * `LocationRegistry::deliver`, re-resolution already reaches the
     * originating peer for any ref it mirrors.
     */
    private val replySink: InvocationSink = deliverTo,
    /** This side's negotiated protocol-id set (G-35 phase B); see [defaultProtocolCapabilities]. */
    private val protocolCapabilities: Set<ProtocolId> = defaultProtocolCapabilities(),
) : Cell, BoundaryDenialAccounting {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<ByteArray>>())

    /**
     * Seam-1 accounting for this ingress (spec 40/43, `[SEC1-07]`). One sink,
     * allocated at construction — see [admit]'s KDoc — and reached by tests
     * through the indexer (`boundaryDenials["bridge-ingress"]`), the same
     * convention `CompositeCell` uses for its per-exposure sinks.
     */
    override val boundaryDenials: BoundaryDenials = BoundaryDenials()
    private val admissionSink = boundaryDenials.sinkFor("bridge-ingress")

    init {
        inlet.serve(object : Propagate<ByteArray> {
            override fun propagate(value: ByteArray) {
                if (!admit(peer)) {
                    // Seam 1 (spec 40/43, [SEC1-07]): refused before decode and
                    // before any delivery reaches the local registry
                    // ([SEC1-06]). Nothing throws — a denial is not a cell
                    // fault (BS-14) — so this never reaches supervision.
                    admissionSink.deny(
                        seam = BoundarySeam.ADMISSION,
                        reason = DenialReason.NOT_ADMITTED,
                        principal = peer,
                        subject = null,
                        detail = "frame from $peer refused: not on the allowlist (spec 43)",
                        deniedArgs = listOf(value),
                    )
                    return
                }
                val decoded = WireCodec.decode(value)
                val withPeer = if (decoded.type == HostedPortInvocation.Type.PORT_PROTOCOL) {
                    val edge = decoded.protocolLink as WireEdgeLink
                    decoded.copy(protocolLink = edge.withBridge(replySink, protocolCapabilities), peer = peer, peerAuth = peerAuth)
                } else {
                    decoded.copy(peer = peer, peerAuth = peerAuth)
                }
                deliverTo.deliver(withPeer)
            }
        })
    }
}
