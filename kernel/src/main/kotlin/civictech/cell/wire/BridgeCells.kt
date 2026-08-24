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
import civictech.cell.CurrentContext
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.proxy.Invocation
import civictech.gen.wire.Contract
import civictech.nature.ContractRegistry
import java.util.UUID

/**
 * The network bridge as ordinary cells (spec 41 point 4): between egress and
 * ingress only bytes travel — an in-process loopback link (M5.3) or a socket
 * (M5.5). Because bridges are cells with ordinary ports and links, policies,
 * membranes and supervision apply to network crossings with no special
 * casing. P1: fully meaningful on the SimulationController — the generative
 * harness exercises the whole wire format without a network.
 */
class BridgeEgressCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    /**
     * This side's announcement signer, or null when the side has no identity
     * configuration ([DSC1-ANN-01], epic `computenet-ssa.4`). Borrowed, never
     * owned: it belongs to the [Peering.Side] and outlives this cell, which is
     * what keeps the counter strictly increasing across the egress replacement
     * a reconnect performs (see [AnnouncementSigner]).
     *
     * A **trailing parameter with a default**, so every existing construction
     * site compiles unchanged and, with no signer, encodes byte-identical
     * frames ([DSC1-WIRE-06]).
     */
    private val signer: AnnouncementSigner? = null,
) : Cell, InvocationSink {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<ByteArray>>())

    /** Proxies use this cell as their [InvocationSink]; every send becomes a frame. */
    override fun deliver(invocation: HostedPortInvocation) {
        val args = invocation.invocation.args
        // spec 23 corollary: a lease on a remote pool is meaningless
        require(args.none { it is Leased<*> }) {
            "Leased payloads must not cross machine boundaries (spec 23) — freeze or copy first"
        }
        val frame = WireCodec.encode(invocation, signer)
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
 * An **announcement** refusal ([announcementAdmission]) is the same kind of
 * thing as the allowlist refusal and takes the same route — accounted, never
 * thrown — but at a different point and on its own sink: it needs the decoded
 * frame's signing fields, so it runs after [WireCodec.decodeFrame] and before
 * the invocation is handed to [deliverTo]. That placement is the whole
 * guarantee behind "zero registry change on rejection": a refused announcement
 * never reaches `LocationRegistry::deliver`, so there is no window in which it
 * is applied and retracted.
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
    /**
     * The receiving side's announcement admission gate, or null when this side
     * verifies no announcements ([DSC1-ANN-05..13], epic `computenet-ssa.4`).
     *
     * **Borrowed from the [Peering.Side], never owned** — the same discipline
     * [BridgeEgressCell.signer] follows, and for the mirrored reason: the
     * replay high-water mark is per minting *identity*, so it must outlive the
     * ingress replacement a reconnect performs (see [AnnouncementAdmission]).
     *
     * **A trailing parameter with a default**, so every existing construction
     * site compiles unchanged and, with no gate, takes the pre-feature path
     * frame for frame ([DSC1-WIRE-06]).
     */
    private val announcementAdmission: AnnouncementAdmission? = null,
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

    /**
     * Announcement-verification refusals, accounted **separately** from the
     * allowlist's `"bridge-ingress"` sink ([DSC1-OBS-02..04]).
     *
     * Two boundaries, two counters: an allowlist refusal says "this peer is not
     * welcome here at all" and an announcement refusal says "this peer is
     * welcome and this particular claim is not", and summing them would make
     * neither rate readable. Allocated at construction like its sibling, so
     * `boundaryDenials["announcement-admission"]` is non-null on every ingress
     * whether or not one ever fires.
     */
    private val announcementSink = boundaryDenials.sinkFor("announcement-admission")

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
                val decodedFrame = WireCodec.decodeFrame(value)
                val gate = announcementAdmission
                if (gate != null && WireCodec.isAnnouncement(decodedFrame.frame)) {
                    // Seam 1, announcement half (DSC1 [DSC1-ANN-05..13]): the ONE
                    // place the trust decision for an arriving announcement is
                    // taken, before it can reach RegistryMirrorCell and therefore
                    // before any LocationRegistry state moves. Like the allowlist
                    // above, nothing throws — a refusal is not a cell fault
                    // (BS-14) — so this never reaches supervision.
                    //
                    // deniedArgs is deliberately EMPTY. The refused announcement's
                    // arguments are refs, link records and ids: no Owned/Leased can
                    // reach this cell (RegistryAnnounce's whole signature), so there
                    // is nothing to discharge, and handing the raw frame bytes over
                    // as the allowlist path does would put the base64 signature into
                    // a dead letter ([DSC1-OBS-05]).
                    val rejection = gate.check(peer, decodedFrame.frame)
                    if (rejection != null) {
                        announcementSink.deny(
                            seam = BoundarySeam.ADMISSION,
                            reason = rejection.reason,
                            principal = peer,
                            subject = "RegistryAnnounce",
                            detail = rejection.detail,
                            deniedArgs = emptyList(),
                        )
                        return
                    }
                }
                // computenet-wb6s: a link request crosses as an addressable
                // [RemoteLink] call and is translated back into the ordinary
                // linkTo/linkFrom invocation here, BEFORE the peer stamp below —
                // so the handshake that follows sees the identity this ingress
                // authenticated, never one a sender supplied.
                val decoded = if (RemoteLinkRequests.isRequest(decodedFrame.frame)) {
                    RemoteLinkRequests.translate(decodedFrame.invocation, replySink)
                } else {
                    decodedFrame.invocation
                }
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

/**
 * The wire vocabulary for a link request (computenet-wb6s, spec 41 point 4,
 * 40/43 seam 1).
 *
 * A link request is a `PORT_MANAGEMENT` invocation, and until this contract
 * existed it could not cross a wire at all: [WireCodec.encode] refuses any
 * non-`PORT_PROTOCOL` frame whose method carries no `@Contract` ids
 * ("not wire-capable: 'linkFrom' was not captured from a @Contract
 * interface"), and [civictech.cell.port.LinkTo.linkTo] /
 * [civictech.cell.port.LinkFrom.linkFrom] take a **live port object** as their
 * argument — which no encoding could carry across a machine boundary even with
 * ids. So the two halves of the seam were verified only up to an injection
 * point: every cross-boundary link test handed the already-decoded invocation
 * straight to the target side's registry.
 *
 * The fix keeps the model rather than weakening it. `LinkTo`/`LinkFrom` are
 * untouched: what crosses is this contract's **addressable** form of the same
 * request — "link your port to mine, which lives at (cell, port) and speaks the
 * contract with this id" — and the receiving [BridgeIngressCell] translates it
 * back into exactly the `linkTo`/`linkFrom` invocation an in-process caller
 * would have made, stamped with the peer that ingress authenticated. The wire
 * frame is unchanged: ids-only, three already-registered argument types
 * ([CellRef], `String`, `Long`), no new field, no version bump — additive in
 * precisely the sense `RegistryAnnounce` already was.
 *
 * The api is named by its **contract id**, never by a class name (P9): the
 * receiver resolves it through its own [ContractRegistry], so an api the
 * receiver does not know is refused locally rather than believed off the wire.
 */
@Contract(management = true)
interface RemoteLink {
    /**
     * Asks the addressed port — a [civictech.cell.port.LinkTo] — to link to the
     * requesting peer's consumer, which lives at ([cell], [port]) and speaks the
     * contract identified by [apiContractId].
     */
    fun requestLinkTo(cell: CellRef, port: String, apiContractId: Long)

    /**
     * Asks the addressed port — a [civictech.cell.port.LinkFrom] — to accept a
     * link from the requesting peer's producer at ([cell], [port]).
     */
    fun requestLinkFrom(cell: CellRef, port: String, apiContractId: Long)
}

/**
 * Emit and receive sides of a [RemoteLink] request. Kept together so the two
 * cannot drift into disagreeing about what a link request is — the same
 * discipline [WireCodec.isAnnouncement] follows for announcements.
 */
object RemoteLinkRequests {

    /** `LinkTo.linkTo(LinkFrom)` — the handshake-running overload, not the ad-hoc `Use` one. */
    private val LINK_TO = LinkTo::class.java.methods.first {
        it.name == "linkTo" && it.parameterTypes.singleOrNull() == LinkFrom::class.java
    }
    private val LINK_FROM = LinkFrom::class.java.methods.first { it.name == "linkFrom" }

    private val REQUEST_LINK_TO = RemoteLink::class.java.methods.first { it.name == "requestLinkTo" }
    private val REQUEST_LINK_FROM = RemoteLink::class.java.methods.first { it.name == "requestLinkFrom" }

    /**
     * [RemoteLink]'s generated contract id, resolved lazily for the same reason
     * [WireCodec]'s announcement id is: `ContractRegistry` is
     * ServiceLoader-populated and these objects initialize early. Null only if
     * the generated descriptor is missing, in which case nothing is ever
     * recognized as a link request — fail *closed*: an unrecognized frame takes
     * the ordinary delivery path and fails loudly at the target port rather than
     * being silently translated into a link.
     */
    private val contractId: Long? by lazy { ContractRegistry.descriptor(RemoteLink::class.java)?.contractId }

    /** Whether [frame] carries a link request (see [translate]). */
    internal fun isRequest(frame: WireFrame): Boolean {
        val id = contractId ?: return false
        return frame.type == HostedPortInvocation.Type.PORT_MANAGEMENT && frame.contractId == id
    }

    /**
     * Rewrites a decoded [RemoteLink] request into the ordinary
     * `linkTo`/`linkFrom` `PORT_MANAGEMENT` invocation the target port already
     * understands, with a locally-constructed endpoint standing in for the
     * requesting peer's port.
     *
     * [replySink] is the ingress's reverse path (in practice the receiving
     * side's `LocationRegistry::deliver`, which resolves the requester's ref as
     * a `Remote` routed back through the peering's egress), so data the target
     * pushes into the endpoint reaches the requester exactly as any other remote
     * send does (spec 41 point 3).
     */
    internal fun translate(decoded: HostedPortInvocation, replySink: InvocationSink): HostedPortInvocation {
        val args = decoded.invocation.args
        val cell = args[0] as CellRef
        val port = args[1] as String
        val api = apiClass(args[2] as Long)
        return when (decoded.invocation.methodName) {
            "requestLinkTo" -> {
                val endpoint = FanInlet(api)
                endpoint.serve(forwarder(api, cell, port, replySink))
                decoded.copy(invocation = Invocation.of(LINK_TO, arrayOf<Any?>(endpoint)))
            }

            "requestLinkFrom" -> decoded.copy(
                invocation = Invocation.of(LINK_FROM, arrayOf<Any?>(FanOutlet(api))),
            )

            else -> error("unknown RemoteLink method '${decoded.invocation.methodName}'")
        }
    }

    /**
     * Sends a link request to [target] over [sink] (a [BridgeEgressCell], or a
     * registry that routes to one), asking it to link to this side's consumer at
     * [consumer]. [api] must be `@Contract`-annotated: its contract id is what
     * crosses, never its class name.
     */
    fun requestLinkTo(sink: InvocationSink, target: PortAddress, consumer: PortAddress, api: Class<*>) =
        sink.deliver(request(REQUEST_LINK_TO, target, consumer, api))

    /** [requestLinkTo]'s mirror: asks [target] to accept a link from this side's producer at [producer]. */
    fun requestLinkFrom(sink: InvocationSink, target: PortAddress, producer: PortAddress, api: Class<*>) =
        sink.deliver(request(REQUEST_LINK_FROM, target, producer, api))

    private fun request(
        method: java.lang.reflect.Method,
        target: PortAddress,
        own: PortAddress,
        api: Class<*>,
    ): HostedPortInvocation {
        val apiContractId = checkNotNull(ContractRegistry.descriptor(api)?.contractId) {
            "not wire-capable: ${api.name} carries no @Contract, so a link request cannot name it"
        }
        return HostedPortInvocation(
            cellRef = target.cell,
            portName = target.port,
            type = HostedPortInvocation.Type.PORT_MANAGEMENT,
            invocation = Invocation.of(method, arrayOf<Any?>(own.cell, own.port, apiContractId)),
        )
    }

    /**
     * The api interface named by [apiContractId], resolved through this side's
     * own [ContractRegistry] — the receiver's knowledge, not the sender's claim.
     * A contract this side does not know throws, and the throw is a decode-time
     * fault on the ingress cell, handled like any other malformed frame.
     */
    @Suppress("UNCHECKED_CAST")
    private fun apiClass(apiContractId: Long): Class<Any> {
        val fqn = checkNotNull(ContractRegistry.contract(apiContractId)?.fqn) {
            "link request names contract id $apiContractId, which has no local descriptor"
        }
        return (load(fqn) ?: error("contract $fqn has a descriptor but no loadable class")) as Class<Any>
    }

    /**
     * `ContractDescriptor.fqn` writes a nested interface's `$` separator as `.`
     * (see `ContractRegistry.descriptor`), so the name is re-nested one segment
     * at a time from the right until a class loads.
     */
    private fun load(fqn: String): Class<*>? {
        var candidate = fqn
        while (true) {
            runCatching { return Class.forName(candidate) }
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1)
        }
    }

    /**
     * A stand-in for the requesting peer's consumer: every call on [api] becomes
     * a `PORT_API` invocation addressed to ([cell], [port]) and handed to
     * [sink]. The wave context rides it (G-4), exactly as `HostedCellProxy`'s
     * own api path does.
     */
    private fun forwarder(api: Class<Any>, cell: CellRef, port: String, sink: InvocationSink): Any =
        java.lang.reflect.Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, args ->
            sink.deliver(
                HostedPortInvocation(
                    cellRef = cell,
                    portName = port,
                    type = HostedPortInvocation.Type.PORT_API,
                    invocation = Invocation.of(method, args, CurrentContext.get()),
                ),
            )
            null
        }
}
