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
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.port.FanOutlet
import civictech.cell.protocol.ProtocolId
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.InvocationSink
import civictech.cell.CurrentContext
import civictech.cell.host.LocationRegistry
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkTo
import civictech.cell.port.PortRef
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
     * The **key identifier** this connection was admitted on, judged by
     * [admit] (feature `computenet-376c`). Null when the connection presented
     * no key — an open side admits it, an allowlisted side refuses it.
     *
     * Distinct from [peer] on purpose: this is what admission *decides on*,
     * [peer] is what every delivery is *stamped with*. Under the interim
     * [civictech.cell.link.PeerIdentityBinding] the two carry the same string.
     */
    private val peerKey: KeyId? = null,
    /**
     * Boundary admission (M8.3, spec 43 mechanism 2): allowlists are bridge
     * configuration, not a protocol fork. Judges [peerKey] — the key on the
     * connection — and not [peer], which is the identity it resolved to
     * (feature `computenet-376c`).
     *
     * A refused frame is refused before [WireCodec.decode] runs and before any
     * delivery reaches the local registry ([SEC1-06]): a typed
     * [civictech.cell.BoundaryDenial] naming the refused [PeerId] is emitted
     * through [boundaryDenials] and this cell's denial counter increments
     * ([SEC1-07]). Nothing is thrown, so the refusal is never classified as a
     * cell fault that triggers supervision RESTART or escalation — a denial is
     * not a fault (BS-14).
     *
     * **Residual (feature `computenet-376c`):** the emitted
     * `civictech.cell.BoundaryDenial.principal` stays a [PeerId] and grows no
     * `KeyId` field, so a refusal records the identity that was refused and
     * names the refused key only in its `detail`. Re-keying the denial record
     * is DSC4's remaining work.
     */
    private val admit: (KeyId?) -> Boolean = { true },
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
    /**
     * Where a named [CellRef] lives, as **this side's own registry** answers it
     * — the seam that binds a [RemoteLink] request's named address to the peer
     * this ingress authenticated (computenet-a4ha).
     *
     * A link request is the one arriving frame that names an address *other
     * than its own target*: [RemoteLinkRequests.translate] reconstructs a local
     * endpoint out of `(cell, port)` values the **sender** supplied, and
     * `LocationRegistry.deliver` will then route that address wherever it
     * resolves — a [civictech.cell.host.LocationRegistry.Local] host, or any
     * other peer's [civictech.cell.host.LocationRegistry.Remote] sink. Without
     * this seam an admitted peer could make the receiving side stream its own
     * data into one of the receiver's cells, or to a *third* peer — a confused
     * deputy, since the link is authorised against `Principal = Peer(sender)`
     * while the data lands elsewhere.
     *
     * That is also the point at which the wire path would be a **weaker model**
     * than the in-process one: `LinkTo.linkTo(LinkFrom)` takes a live port
     * object, so an in-process caller must *possess* the endpoint, while over
     * the wire it need only *name* an address. Requiring the named address to
     * resolve to a location this peer announced is what restores the symmetry.
     *
     * **What this does NOT bound, stated here rather than only on the bead.**
     * This seam binds the *aim* and nothing else: every admitted link is
     * admitted only against the requesting peer's own endpoint, so the
     * amplifier cannot be *pointed* at a third party or at the receiver's own
     * cells by the request itself. It imposes no per-peer cap, and none is
     * imposed anywhere: a peer may hold links to as many *distinct* endpoints
     * as it announces.
     *
     * What it no longer leaves open is buying N copies of ONE stream by asking
     * N times. That was closed by computenet-hil6, at the identity of the
     * reconstructed endpoint rather than by a cap here — see
     * [RemoteLinkRequests.standInRef], whose KDoc also states the residual
     * (link-record accumulation is not collapsed, only delivery).
     *
     * **And the aim is bound at the request, not for the link's lifetime**
     * (measured in review, computenet-zlm2). This check runs once; the endpoint
     * [RemoteLinkRequests.translate] builds then re-resolves `(cell, port)`
     * through `LocationRegistry.deliver` on *every* delivery. Because a
     * mirrored announcement overwrites `locations[ref]` whoever sent it, a
     * *different* admitted peer that later announces the same [CellRef]
     * captures the stream — and, announcing first, can even turn a ref this
     * side hosts locally into a `Remote` that then passes [namedByPeer]. That
     * is not specific to this seam: the same announcement captures an ordinary
     * `HostedCellProxy` link P made itself, with no link request in play, which
     * is why it is filed as its own defect rather than fixed here. Read this
     * seam as "a peer may only ask for a link to a ref it has announced", not
     * as "the data can only ever reach that peer".
     *
     * **Defaults to `{ null }`, which refuses every link request** — fail
     * closed. [Peering.hostIngress] is the only production construction and
     * supplies `side.registry::location`; the kernel tests that construct this
     * cell directly exchange no link requests, so the default costs them
     * nothing.
     */
    private val locate: (CellRef) -> LocationRegistry.Location? = { null },
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

    /**
     * Link-request address refusals (computenet-a4ha), accounted **separately**
     * again — a third boundary, a third counter, for the reason
     * [announcementSink] gives: "this peer is not welcome here at all", "this
     * peer's announcement is not believed" and "this peer may not name that
     * address" are three different facts about a connection, and summing them
     * would make none of the three rates readable. Allocated at construction
     * like its siblings, so `boundaryDenials["link-request"]` is non-null on
     * every ingress whether or not one ever fires.
     */
    private val linkRequestSink = boundaryDenials.sinkFor("link-request")

    /**
     * Does [named] resolve to a location [peer] itself announced?
     *
     * The check is deliberately **positive and total**: only a
     * [civictech.cell.host.LocationRegistry.Remote] whose recorded peer equals
     * the identity this ingress stamped passes. A [CellRef] this side hosts
     * itself, one another peer announced, and one nothing has published at all
     * are refused alike, and so is *every* request on an anonymous peering
     * ([peer] null) — an unnamed peer announces its locations anonymously too,
     * so `null == null` would match any other anonymous peer's ref and any
     * unpublished one, which is precisely the binding this is for. An anonymous
     * peering therefore cannot establish a remote link; it never had an
     * identity to bind one to.
     *
     * Two measured limits on how much that buys (computenet-zlm2, review of
     * this change): "a [CellRef] this side hosts itself" is refused only while
     * no peer has *announced* it — a mirrored announcement overwrites a
     * [civictech.cell.host.LocationRegistry.Local] location, and the ref then
     * resolves as that peer's [civictech.cell.host.LocationRegistry.Remote] and
     * passes here; and only the **cell** half is judged — the `port` half of
     * the named address is never checked, so a peer may name any port name on
     * its own cell (deliveries then dead-letter at the requester, on its side
     * of the boundary, not this one).
     */
    private fun namedByPeer(named: CellRef): Boolean {
        val whose = peer ?: return false
        return (locate(named) as? LocationRegistry.Remote)?.peer == whose
    }

    init {
        inlet.serve(object : Propagate<ByteArray> {
            override fun propagate(value: ByteArray) {
                if (!admit(peerKey)) {
                    // Seam 1 (spec 40/43, [SEC1-07]): refused before decode and
                    // before any delivery reaches the local registry
                    // ([SEC1-06]). Nothing throws — a denial is not a cell
                    // fault (BS-14) — so this never reaches supervision.
                    admissionSink.deny(
                        seam = BoundarySeam.ADMISSION,
                        reason = DenialReason.NOT_ADMITTED,
                        principal = peer,
                        subject = null,
                        detail = "frame from $peer (key $peerKey) refused: not on the allowlist (spec 43)",
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
                    // Seam 1, link-request half (computenet-a4ha): the request
                    // names an address, and the ONLY address it may name is one
                    // belonging to the peer this ingress authenticated. Refused
                    // before `translate` reconstructs any endpoint and before
                    // anything reaches the local registry, so no link exists
                    // even momentarily. Like both gates above, nothing is
                    // thrown — a denial is not a cell fault (BS-14) — so this
                    // never reaches supervision.
                    //
                    // deniedArgs is EMPTY for the reason the announcement gate
                    // gives: a RemoteLink request's arguments are a CellRef, a
                    // String and a Long, so no Owned/Leased can reach here and
                    // there is nothing to discharge.
                    val named = RemoteLinkRequests.namedAddress(decodedFrame.invocation)
                    if (!namedByPeer(named.cell)) {
                        linkRequestSink.deny(
                            seam = BoundarySeam.ADMISSION,
                            reason = DenialReason.LINK_REFUSED,
                            principal = peer,
                            subject = "RemoteLink.${decodedFrame.invocation.invocation.methodName}",
                            detail = "link request from $peer named ${named.cell}#${named.port}, " +
                                "which is not a location $peer announced to this side — a peer may " +
                                "only name its own endpoint (computenet-a4ha, spec 40/43 seam 1)",
                            deniedArgs = emptyList(),
                        )
                        return
                    }
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
     *
     * [cell] is **checked against the requesting peer before it is used**
     * (computenet-a4ha): the receiving [BridgeIngressCell] refuses the request
     * unless [cell] resolves, on its own registry, to a location that peer
     * announced — see [BridgeIngressCell.locate], whose KDoc also states the
     * two limits of that check ([port] is not bound, and the binding is taken
     * at the request rather than held for the link's lifetime). Everything the
     * linked port then emits is forwarded to that address, so the check is what
     * keeps "the requesting peer's consumer" in the sentence above true at the
     * moment the link is admitted.
     */
    fun requestLinkTo(cell: CellRef, port: String, apiContractId: Long)

    /**
     * Asks the addressed port — a [civictech.cell.port.LinkFrom] — to accept a
     * link from the requesting peer's producer at ([cell], [port]).
     *
     * **Stated limitation, and it is in the shipped file deliberately**
     * (computenet-a4ha): ([cell], [port]) is *authorised* but not yet *wired*.
     * The address is checked against the requesting peer exactly as
     * [requestLinkTo]'s is — a peer may not name another peer's or the
     * receiver's address here either — and the link is then established against
     * a bare [FanOutlet] of the named api, which **nothing drives**. So the
     * handshake, the policies and the topology edge are all real, and no data
     * flows from the named producer until an inbound route to that outlet
     * exists.
     *
     * That route is the missing half, not an oversight of this contract: the
     * reconstructed outlet has no address of its own, so the producing peer has
     * nowhere to send to. Giving it one is a frame-shape question
     * ([RemoteLinkRequests] deliberately adds no field and no version bump), and
     * it is why [requestLinkTo] — where the *receiving* side already knows where
     * to push — is the direction that carries data today.
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
     * The endpoint address [decoded] names on the **requesting** side — the
     * `(cell, port)` pair of [RemoteLink.requestLinkTo] /
     * [RemoteLink.requestLinkFrom], read without translating anything
     * (computenet-a4ha).
     *
     * Read *before* [translate], because it is what
     * [BridgeIngressCell.namedByPeer] judges: an address that does not belong
     * to the authenticated peer must be refused with no endpoint constructed
     * and nothing handed to the local registry.
     */
    internal fun namedAddress(decoded: HostedPortInvocation): PortAddress {
        val args = decoded.invocation.args
        return PortAddress(args[0] as CellRef, args[1] as String)
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
     *
     * **Precondition, enforced by the caller** (computenet-a4ha): the address
     * [namedAddress] reads out of [decoded] has already been bound to the peer
     * the ingress authenticated ([BridgeIngressCell.locate]). This function
     * trusts that binding — it constructs an endpoint that forwards wherever
     * `(cell, port)` resolves, which is exactly the authority the check grants.
     */
    internal fun translate(decoded: HostedPortInvocation, replySink: InvocationSink): HostedPortInvocation {
        val args = decoded.invocation.args
        val cell = args[0] as CellRef
        val port = args[1] as String
        val api = apiClass(args[2] as Long)
        return when (decoded.invocation.methodName) {
            "requestLinkTo" -> {
                // computenet-hil6: the stand-in carries the DERIVED identity of
                // the port it stands for, not a fresh anonymous one. See
                // [standInRef].
                val endpoint = FanInlet(api, standInRef(cell, port))
                endpoint.serve(forwarder(api, cell, port, replySink))
                decoded.copy(invocation = Invocation.of(LINK_TO, arrayOf<Any?>(endpoint)))
            }

            // The reconstructed producer is INERT: `cell`/`port` were used to
            // authorise the request (see [RemoteLink.requestLinkFrom]'s stated
            // limitation) but nothing drives this outlet, because it has no
            // address the producing peer could send to.
            "requestLinkFrom" -> decoded.copy(
                invocation = Invocation.of(LINK_FROM, arrayOf<Any?>(FanOutlet(api))),
            )

            else -> error("unknown RemoteLink method '${decoded.invocation.methodName}'")
        }
    }

    /**
     * The [PortRef] a reconstructed stand-in endpoint carries: the **derived**
     * identity of the remote port it stands for ([PortRef.of], PN-1), never a
     * fresh anonymous one (computenet-hil6).
     *
     * This is what makes a repeated identical link request idempotent, and it
     * is a repair rather than a policy: `FanOutlet.consumers` is keyed by
     * [PortRef], so re-linking a stand-in for the *same* `(cell, port)`
     * REPLACES the attachment instead of adding a sibling — exactly the
     * mechanism `FanOutlet.streamTo` relies on (T21) and
     * `GossipLinkIdempotenceTest` pins for the gossip mesh, where the link's
     * ref is likewise derived from the pair it connects rather than generated.
     * Before this, `FanInlet(api)` minted `PortRef.generate()` per request, so
     * N identical requests installed N distinct consumers on one outlet and
     * every emission crossed the wire N times (measured: `links=5 consumers=5
     * delivered=5`, five refs with `cell=null`). No number is chosen here and
     * no cap is imposed: a peer may still hold links to as many *distinct*
     * endpoints as it announces — what it can no longer do is buy N copies of
     * one stream by asking N times for the same one.
     *
     * The derivation also makes the stand-in's identity the one the requesting
     * side actually uses: `(cell, port)` is a hosted port over there, so
     * [PortRef.of] reproduces the very ref that port derived for itself, and a
     * targeted delivery (`FanOutlet.at`) or a `MessageContext.sourcePort` stamp
     * now names the real remote port rather than an ingress-local accident.
     *
     * **The bookkeeping follows the attachment** (computenet-lioe). Each
     * admitted request still runs a full handshake, and `LinkSupport.active` is
     * keyed by a random `Link.id`, so a repeat used to leave the superseded
     * record behind on the target outlet even though only one consumer
     * attachment survived — the orphan T21 had to evict explicitly in
     * `streamTo`. `handshake` now evicts it on the general path
     * (`civictech.cell.link.evictSuperseded`, keyed on the whole
     * `(from, to, role)` triple), so N identical requests leave one link record
     * as well as one consumer and one delivery.
     *
     * **What is still not de-duplicated**, stated here rather than only on the
     * bead: [RemoteLink.requestLinkFrom]'s mirror. Its stand-in producer is an
     * anonymous `FanOutlet(api)` with a generated ref, so each repeat has a
     * DISTINCT `from` and is a genuinely new edge by the supersession key —
     * one link record plus one inert `FanOutlet` per request. Collapsing it
     * would mean evicting on `to` alone at the target inlet, which is exactly
     * the rule that would delete the records of every other producer feeding a
     * fan-in inlet. It is a monotone cost a peer controls, but it drives no
     * traffic: nothing can send to that outlet.
     */
    private fun standInRef(cell: CellRef, port: String): PortRef = PortRef.of(cell, port)

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
