package civictech.cell.membrane

import civictech.cell.BoundaryDenialAccounting
import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.control.Attention
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.link.LinkPolicy
import civictech.cell.link.Linked
import civictech.cell.link.PeerId
import civictech.cell.port.Port
import civictech.cell.protocol.ProtocolId
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.port.registerPort
import civictech.cell.proxy.Proxy
import java.util.UUID

/**
 * A surface mode of one [Exposure] (spec 10/11 "Decided model"):
 * - **FLATTEN** (green): the exposure delegates, off the per-message path —
 *   authority is link-time `onLink` policies only.
 * - **MEDIATE** (red-with-logic): a served proxy sits on the path, captures
 *   each `Invocation`, and forwards it — the sole flow-time enforcement
 *   point (10/11 "Boundary policy").
 */
enum class SurfaceMode { FLATTEN, MEDIATE }

/**
 * One named seam of a composite cell's membrane (spec 10/11 "Decided
 * model"): [externalName] is the ONLY name external resolvers may use;
 * [organellePortName] documents which internal port it re-presents, for
 * diagnostics and future wire/DSL lowering (40/41, 50/51 — G-52 residual).
 * Couplings (Symport/Antiport) and an observe/trace outlet are part of the
 * decided model but out of scope here: coupling liveness is research-gated
 * (95 §R3, G-53) and this ticket ships without them (gated off) rather than
 * with the documented wait-forever caveat.
 */
data class Exposure(
    val externalName: String,
    val organellePortName: String,
    val mode: SurfaceMode,
    /**
     * Identity-keyed predicates evaluated at the three seams this boundary
     * already owns (spec 40/43 "BoundaryPolicy", decided 93 I-28, W4.1/G-54).
     * Absent a declared policy, every predicate defaults open and this
     * exposure behaves exactly as before this ticket (P7/P6).
     */
    val policy: BoundaryPolicy = BoundaryPolicy(),
)

/**
 * A composite cell (spec 10/11 §"Hierarchy: organelles" and §"Membranes and
 * policies"): a cell that contains organelle cells and declares an
 * [exposureMap] naming the only ports external resolvers may reach —
 * **hidden by default (G-9)**.
 *
 * Enforcement: organelles are held only as direct in-process references by
 * the subclass (never spawned onto a [civictech.cell.host.ManagedHost] in
 * their own right) — "Organelles stay addressable internally via the direct
 * references the container holds from spawning them; hiding costs nothing
 * on the internal path" (10/11). A host's port/cell resolution
 * (`findPort`/`lookup`/`connect`, spec 30/31) walks each cell's own
 * [civictech.cell.port.PortRegistry] entry; since only [flatten]/[mediate]
 * register a port under this composite's name, that per-cell registry *is*
 * the containment record host resolution consults — an external connect
 * naming a non-exposed organelle port name, or the organelle's own
 * (unpublished) [CellRef], finds nothing and is refused as unknown-port,
 * exactly as 10/11 requires, without any new host-level bookkeeping. This
 * generalizes the existing G-28 parent/child pattern (host-nesting, M8.1)
 * to cell-granularity containment.
 */
abstract class CompositeCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, BoundaryDenialAccounting {

    private val exposureMapMutable = linkedMapOf<String, Exposure>()

    /** The declared exposure map (spec 10/11) — read-only view for diagnostics/tests. */
    val exposureMap: Map<String, Exposure> get() = exposureMapMutable

    /**
     * Per-exposure denial accounting for this membrane's [BoundaryPolicy]
     * seams (spec 40/43, `[SEC1-25]`/`[SEC1-26]`; realization (B), rationale
     * in [BoundaryDenials]' KDoc). One [BoundaryDenialSink] per exposure that
     * can carry a seam — every [mediate]/[mediateOutlet] exposure (whose
     * surface is mediated whether or not the policy currently declares a
     * predicate) plus any [flatten] exposure that declares `linkAuthority`. A
     * plain `flatten()` allocates none. The hosting `ManagedHost` attaches the
     * reporter that routes each refusal into its own `DeadLetters`, so
     * sanitization (spec 23 R8) is inherited rather than reimplemented here.
     *
     * A test reads a boundary's counter here — `boundaryDenials["<exposure>"]!!
     * .denialCount` — which is why the sinks are exposed rather than private.
     */
    final override val boundaryDenials: BoundaryDenials = BoundaryDenials()

    /**
     * Flatten-exposes an existing organelle [port] under [externalName]: the
     * SAME port object is re-registered in this composite's namespace (spec
     * 10/11: "an exposed link is the organelle outlet's own subscriber, not
     * a second consumer" — cardinality/SPSC stays counted once, at the
     * organelle port). This is `delegate`'s O(1) collapse (10/14): the
     * composite is not on the per-message path at all.
     */
    protected fun <P : Port> flatten(
        externalName: String,
        organellePortName: String,
        port: P,
        policy: BoundaryPolicy = BoundaryPolicy(),
    ): P {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        require(!policy.forcesMediate) {
            "Exposure $externalName declares a flow-time predicate (protocolAuthority/disclosure/integrity); " +
                "it MUST use mediate()/mediateOutlet(), not flatten() (spec 10/11 \"Boundary policy\")"
        }
        installLinkAuthority(externalName, port, policy)
        exposureMapMutable[externalName] = Exposure(externalName, organellePortName, SurfaceMode.FLATTEN, policy = policy)
        return registerPort(externalName, port)
    }

    /**
     * Mediate-exposes [organelleInlet] under [externalName]: installs a NEW
     * [FanInlet] on this composite, served by a hand-written [MediateProxy]
     * (10/14 "mediate-is-serve": `serve(proxy)` is a real cell on the
     * per-message path) that captures each invocation and forwards it to
     * [organelleInlet] — budget/cardinality is counted at this proxy's own
     * external face (10/11), distinct from the organelle's.
     *
     * [policy]'s seam-2 `linkAuthority` runs at this exposed port's `onLink`;
     * seam-3 `integrity` (`PORT_API` inbound, spec 40/43) is enforced by the
     * [MediateProxy] before delivery to [organelleInlet] — e.g. exposing a
     * `deltaInlet` with `BoundaryPolicy(integrity = IntegrityPolicy.RequireSigned)`
     * for untrusting-but-cooperating replica gossip (decided 93 I-28).
     *
     * KSP-generating this proxy from a declarative membrane annotation is
     * G-52's residual (50/51); this is the hand-written realization the
     * ticket ships instead. Coupling gates (Symport/Antiport) are not
     * wired here (G-53, research-gated liveness) — this proxy is a
     * transparent forward only, beyond the [policy] it now evaluates.
     *
     * **[externalName] must be spelled exactly like the property this call's
     * result is assigned to** (G-17, [registerPort]): KSP scans the
     * *property name*, not this string literal, to build the cell's
     * descriptor, while [registerPort] indexes the port under [externalName].
     * A mismatch compiles cleanly and only surfaces at `host.spawn`, e.g.
     * `val exposed = mediate("exposedInlet", ...)` fails with `descriptor
     * declares ports [exposed] not found in registry [exposedInlet] —
     * registerPort's name must equal the property name (G-17)`.
     */
    protected fun <Api : Any> mediate(
        externalName: String,
        organellePortName: String,
        organelleInlet: FanInlet<Api>,
        policy: BoundaryPolicy = BoundaryPolicy(),
    ): FanInlet<Api> {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        val denials = boundaryDenials.sinkFor(externalName)
        val exposed = FanInlet(organelleInlet.clazz)
        exposed.serve(
            Proxy.fromClass(
                organelleInlet.clazz,
                MediateProxy(organelleInlet.call, policy.integrity, denials = denials),
            ),
        )
        installLinkAuthority(externalName, exposed, policy)
        exposureMapMutable[externalName] = Exposure(externalName, organellePortName, SurfaceMode.MEDIATE, policy)
        return registerPort(externalName, exposed)
    }

    /**
     * Mediate-exposes [organelleOutlet] under [externalName]: the exposed
     * port IS the organelle's own outlet object — flatten's O(1) reuse — so
     * the existing per-link `onLinked` catch-up (20/21 §Pull) keeps firing
     * per real external subscriber without a duplicated proxy re-deriving
     * organelle state generically (a genuinely separate outlet proxy would
     * only ever see ONE `onLinked` firing, at its own subscription time, and
     * so cannot re-run catch-up per later external subscriber — G-52
     * residual: a KSP-generated dedicated outlet proxy is the eventual
     * cardinality-isolated realization). What makes this Mediate rather than
     * Flatten is the installed [BoundaryPolicy]:
     *
     * - `disclosure` installs [FanOutlet.disclosureFilter] — one filter over
     *   BOTH the `onLinked` catch-up unicast and the live broadcast (20/21
     *   §Pull, decided 93 I-28: "a snapshot IS a delta"), which accounts each
     *   suppressed delivery attempt through this exposure's
     *   [BoundaryDenialSink] (`[SEC1-25]`/`[SEC1-26]`; per-attempt counting
     *   explained on [asDeltaFilter]).
     * - `protocolAuthority[Protocols.Attention].ceiling` clamps an asserted
     *   attention level via [ProtocolSupport.inboundFilter] before this
     *   outlet's own attention handling sees it (30/34 decision 6:
     *   `slot.level = min(asserted, ceiling)`, fold/band-gating untouched).
     *
     * - `linkAuthority` is producer-side **subscribe** authority: who may
     *   subscribe to this feed at all (SEC1-12). It is installed on the
     *   organelle outlet's own [civictech.cell.link.LinkSupport] and evaluated
     *   by [civictech.cell.link.handshake] as the *source*-side gate.
     *
     * That last point was decided, not inherited (2026-08-15, option (a) of
     * this exposure's (a)/(b) fork; recorded here because the misleading claim
     * it replaces lived here). The earlier text said the mechanism "has no
     * admission hook on the producing side beyond SPSC exclusivity", reasoning
     * that the handshake always runs on the port being linked *to* — which for
     * a consumer subscribing to an exposed outlet is the CONSUMER's own inlet,
     * external to this membrane. The premise about the target was right; the
     * conclusion was not. The handshake already threaded the producer as
     * `(portOut as? Linked)?.linking` — registering the link and firing
     * `onLinked` on it — and [FanOutlet] IS [Linked], so what was missing was
     * one `reject()` call on that side, not a seam. It is now made, second
     * (after the target's, so existing refusal strings are untouched) and
     * before any install (so a refusal leaves no subscriber entry, SEC1-09).
     * The bridged handshake needed no change at all: for the producer side it
     * already evaluates the local port's policies, which is this outlet.
     *
     * Tradeoff: the gate is symmetric with the landed inlet seam
     * (first-rejection-wins, [currentPrincipal] identity, no new type and no
     * wire change) and default-open is preserved (an empty policy list
     * short-circuits). What it does NOT cover is every attach path that
     * bypasses the handshake, which stay ungated by link-time authority:
     * [FanOutlet.subscribe] called directly (`Use.fixed` endpoints and
     * [civictech.cell.evolve.Evolution]'s COMMIT relink), `tap(negotiated =
     * false)` and taps whose target is not a [Linked] port, and
     * [FanOutlet.observe] (which is handed no payload). For those, `disclosure`
     * remains the flow-time backstop; promotion is re-authorized at its own
     * PRECHECK gate, since COMMIT is documented non-vetoing.
     *
     * **[externalName] must be spelled exactly like the property this call's
     * result is assigned to** (G-17, [registerPort]) — the same trap as
     * [mediate]'s: KSP scans the property name to build the descriptor, this
     * registers under [externalName], and a mismatch compiles but only fails
     * at `host.spawn` (see [mediate]'s KDoc for the exact failure message).
     */
    protected fun <Api : Any> mediateOutlet(
        externalName: String,
        organellePortName: String,
        organelleOutlet: FanOutlet<Api>,
        policy: BoundaryPolicy,
    ): FanOutlet<Api> {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        require(policy.forcesMediate) {
            "mediateOutlet($externalName) requires a flow-time predicate " +
                "(protocolAuthority/disclosure/integrity); use flatten() for an outlet whose only " +
                "authority is link-time (linkAuthority), which flatten() installs too"
        }
        val denials = boundaryDenials.sinkFor(externalName)
        installLinkAuthority(externalName, organelleOutlet, policy)
        organelleOutlet.disclosureFilter =
            policy.disclosure.asDeltaFilter(denials, subject = organelleOutlet.clazz.simpleName)
        if (policy.protocolAuthority.isNotEmpty()) {
            ProtocolSupport.of(organelleOutlet).inboundFilter = policy.protocolAuthority.asProtocolFilter(denials)
        }
        exposureMapMutable[externalName] =
            Exposure(externalName, organellePortName, SurfaceMode.MEDIATE, policy)
        return registerPort(externalName, organelleOutlet)
    }

    /**
     * Installs seam 2 (`onLink`, `BoundaryPolicy.linkAuthority`) onto [port]'s
     * link policies — first-rejection-wins, exactly as before — with each
     * installed [LinkPolicy] wrapped so a non-null
     * [civictech.cell.link.LinkResult.Rejected] verdict is accounted through
     * this exposure's [BoundaryDenialSink] **before** it is returned to the
     * handshake (`[SEC1-25]`/`[SEC1-26]`, BS-2).
     *
     * Accounting happens HERE, at the membrane — not in `civictech.cell.link`
     * (`LinkSupport`/`Handshake`), which this task claims defensively only.
     * `LinkSupport.reject` walks `policies.firstNotNullOfOrNull { it.evaluate(
     * request) }`; wrapping each policy before it is added means the wrapper
     * observes exactly the verdict the unwrapped policy would have produced
     * and returns it unchanged, so first-rejection-wins, the allowed/local-
     * request fast path (`allowPeers` on a null identity), and the "reject
     * before any install/register runs" ordering in
     * [civictech.cell.link.handshake] are all untouched — no half-registered
     * port, no subscriber entry, for either the allowed or the denied case.
     *
     * **Three** `linkAuthority` evaluation points now exist at this seam on
     * this branch, and wrapping at install time covers all three because each
     * walks the very `policies` list this function populates: the target-side
     * `support.reject(...)` in both `handshake()` overloads
     * (`civictech.cell.link.Handshake.kt`); the source-side
     * `sourceLinking?.reject(...)` that `computenet-usd.5.1` added for
     * producer-side subscribe authority; and `LinkSupport.reauthorize` at
     * `Evolution.promote`'s PRECHECK (`computenet-usd.5.2`,
     * `Evolution.reauthorizeRebinds`). When `computenet-usd.1.5` landed on
     * `main` only the first existed, and this KDoc said so; because the
     * wrapper sits on the policy rather than on any call site, the other two
     * were accounted the moment `computenet-usd.5` merged, with no change
     * here.
     *
     * The sink is resolved after the empty check, so an exposure that declares
     * no `linkAuthority` allocates nothing — default-open stays byte-for-byte
     * unchanged with zero flow-time cost (`[SEC1-02]`/`[SEC1-03]`, BS-15).
     */
    private fun installLinkAuthority(externalName: String, port: Port, policy: BoundaryPolicy) {
        if (policy.linkAuthority.isEmpty()) return
        val denials: BoundaryDenialSink = boundaryDenials.sinkFor(externalName)
        val accounted = policy.linkAuthority.map { linkPolicy ->
            LinkPolicy { request ->
                val rejected = linkPolicy.evaluate(request)
                if (rejected != null) {
                    denials.deny(
                        seam = BoundarySeam.LINK_AUTHORITY,
                        reason = DenialReason.LINK_REFUSED,
                        principal = request.identity as? PeerId,
                        detail = rejected.reason,
                    )
                }
                rejected
            }
        }
        (port as? Linked)?.linking?.policies?.addAll(accounted)
    }
}

/**
 * [DisclosurePolicy] as a [FanOutlet.disclosureFilter] (spec 40/43 seam 3):
 * `Full` is the identity filter; `Deny` suppresses every emission; `Project`
 * runs the registered transform over the emitted delta argument (the first
 * argument, by the "one delta-carrying method" convention data-cell contracts
 * follow), suppressing the emission if the projection itself returns null.
 *
 * ## Counting: one denial record per suppressed delivery **attempt**
 *
 * Both suppression branches account through [denials] before returning null
 * (`[SEC1-25]`/`[SEC1-26]`), so no silent drop remains here. The unit counted
 * is the **delivery attempt**, decided by feature `computenet-usd.1` and
 * realized structurally rather than by bookkeeping: [FanOutlet] evaluates its
 * `disclosureFilter` once per attempted delivery — once per consumer, once per
 * typed tap, once per payload-agnostic observer notification, and once per
 * targeted `at()` delivery (the `onLinked` catch-up unicast / pull reply) —
 * and this closure runs inside each of those evaluations. A boundary that
 * suppressed N deliveries therefore reports exactly N, on all three paths:
 *
 * - one emission broadcast to k consumers/taps under [DisclosurePolicy.Deny]
 *   records k denials, not one "emission suppressed" record;
 * - an emission with no attachment at all attempts no delivery and records
 *   nothing;
 * - a suppressed catch-up unicast is one attempt, counted like any other.
 *
 * That an *emission* is thereby counted more than once is the honest reading:
 * each suppressed attempt is a delivery some subscriber did not get, and the
 * audit question ("what did this boundary refuse to disclose, to whom") is
 * per-attempt. See [FanOutlet.disclosureFilter] for the evaluation contract
 * this relies on.
 *
 * The refused arguments ride to the sink as `deniedArgs` and reach the fan-out
 * only through the host's spec-23-R8 sanitization (`Owned -> Frozen`,
 * `Leased -> ` [civictech.cell.Redacted]) — this seam adds **no discharge
 * logic of its own** and has no second sanitizer. Exactly-once discharge under
 * *repeated* filter evaluation (the same argument array is handed to this
 * closure once per attempt, so two suppressed attempts sanitize the same
 * wrappers twice — tolerated by the R8 rule's already-consumed branches, not
 * repaired here) is sibling feature `computenet-usd.2`'s subject. **The
 * observable cost of that toleration, while it stands:** the *first*
 * suppressed attempt is the one whose dead letter carries the frozen value;
 * every later attempt on the same emission finds the wrapper already consumed
 * and reports `Redacted`, so with k > 1 suppressed attempts an auditor reads
 * one valued record and k-1 markers. That is a fidelity limit of this
 * accounting, not of the counting — the counter still reports k.
 *
 * [subject] names the mediated outlet's contract, and **only** the contract —
 * unlike the integrity seam ([MediateProxy]), whose record carries
 * `Contract#method`. Not because the emitting `Method` is unknowable here: it
 * is in scope at all three [FanOutlet] call sites. It is that
 * [FanOutlet.disclosureFilter]'s type is arguments-only, and widening that
 * public hot-path signature to carry a `Method` was declined as disproportionate
 * to an audit field and outside `computenet-usd.1.4`'s file claim. The emitted
 * delta itself travels in `deniedArgs`, which is the auditable part. Revisit
 * alongside the exactly-once work (`computenet-usd.2`), which has to revisit
 * how this filter is invoked in any case.
 */
private fun DisclosurePolicy.asDeltaFilter(
    denials: BoundaryDenialSink,
    subject: String?,
): (Array<out Any?>) -> Array<out Any?>? = filter@{ args ->
    when (this) {
        is DisclosurePolicy.Full -> args
        is DisclosurePolicy.Deny -> {
            denials.denyDisclosure(DenialReason.DISCLOSURE_DENIED, subject, "DisclosurePolicy.Deny", args)
            null
        }
        is DisclosurePolicy.Project -> {
            val delta = args.firstOrNull() ?: return@filter args
            val projected = ProjectionRegistry.resolve(id).apply(delta) ?: run {
                denials.denyDisclosure(
                    DenialReason.DISCLOSURE_PROJECTED_AWAY,
                    subject,
                    "projection '${id.name}' returned null for this delta",
                    args,
                )
                return@filter null
            }
            arrayOf(projected, *args.drop(1).toTypedArray())
        }
    }
}

/**
 * Accounts one suppressed `PORT_API` outbound delivery attempt (seam 3
 * disclosure) — the single call shape both suppression branches of
 * [asDeltaFilter] use, so the record's fields cannot drift between them.
 *
 * The principal is the crossing's ambient one ([currentPrincipal]): the peer
 * whose delivery was suppressed where a peer is stamped (a remote-triggered
 * catch-up), and null — `LocalTrusted` — for an ordinary in-process broadcast,
 * where the emitting cell has no peer in scope. Recorded honestly as such
 * rather than guessed from the target: a `FanOutlet` attachment is a
 * [civictech.cell.port.PortRef], and no peer identity is derivable from it at
 * this seam.
 */
private fun BoundaryDenialSink.denyDisclosure(
    reason: DenialReason,
    subject: String?,
    detail: String,
    args: Array<out Any?>,
) {
    deny(
        seam = BoundarySeam.DISCLOSURE,
        reason = reason,
        principal = (currentPrincipal() as? Principal.Peer)?.id,
        subject = subject,
        detail = detail,
        deniedArgs = args.toList(),
    )
}

/**
 * [BoundaryPolicy.protocolAuthority] as a [ProtocolSupport.inboundFilter]
 * (spec 40/43 seam 3, 30/34 decision 6): refuses a [Principal] below
 * `minAuth`, clamps a *remotely*-asserted [Attention] level to `ceiling`
 * (`slot.level = min(asserted, ceiling)`), and throttles a [Principal] over
 * `ratePerWindow`. A protocol with no declared [ProtocolAuthority] passes
 * through unchanged (default open, P7). [Principal.LocalTrusted] is always a
 * no-op — "attention is a request, not an entitlement" answers *remotely*-
 * asserted interest (30/34 decision 6); the fast in-host path pays nothing
 * and assumes nothing (93 I-28 §4.2, "Local crossings carry `LocalTrusted`
 * and every predicate is a no-op").
 *
 * [denials] is this exposure's accounting sink: the `minAuth` and
 * `ratePerWindow` branches each account their refusal (`MIN_AUTH` / `RATE`,
 * naming the [ProtocolId] as `subject` and the refused [Principal.Peer.id] as
 * `principal`) before returning null, so neither is a silent drop
 * (`[SEC1-13][SEC1-14][SEC1-16]`). Rate is counted **per-`Principal`**: the
 * `(ProtocolId, Principal)`-keyed window count already isolates one peer's
 * count from another's, so accounting inherits that isolation for free —
 * throttling one principal's crossings never records against, or moves the
 * counter for, another's (BS-11). The `ceiling` branch never becomes a denial
 * site — a clamp is not a refusal (30/34 decision 6, BS-10): it returns a
 * clamped message with no call into [denials] and no counter movement.
 */
private fun Map<ProtocolId, ProtocolAuthority>.asProtocolFilter(
    denials: BoundaryDenialSink,
): (ProtocolId, Any) -> Any? {
    val counts = java.util.concurrent.ConcurrentHashMap<Pair<ProtocolId, Principal>, Int>()
    return filter@{ id, message ->
        val authority = this[id] ?: return@filter message
        val principal = currentPrincipal()
        if (principal == Principal.LocalTrusted) return@filter message
        val peer = principal as Principal.Peer
        if (peer.auth < authority.minAuth) {
            denials.denyProtocol(
                DenialReason.MIN_AUTH,
                id,
                peer.id,
                detail = "auth=${peer.auth} < minAuth=${authority.minAuth}",
                message = message,
            )
            return@filter null
        }
        authority.ratePerWindow?.let { limit ->
            val key = id to principal
            val next = (counts[key] ?: 0) + 1
            counts[key] = next
            if (next > limit) {
                denials.denyProtocol(
                    DenialReason.RATE,
                    id,
                    peer.id,
                    detail = "count=$next > ratePerWindow=$limit",
                    message = message,
                )
                return@filter null
            }
        }
        if (id == Protocols.Attention && authority.ceiling != null && message is Attention) {
            // preserve the emitter's version: this is the same LWW update, only clamped
            return@filter Attention(minOf(message.level, authority.ceiling.level), message.version)
        }
        message
    }
}

/**
 * Accounts one refused `PORT_PROTOCOL` frame (seam 3 `protocolAuthority`) —
 * the single call shape both the `minAuth` and `ratePerWindow` branches of
 * [asProtocolFilter] use, so the record's fields cannot drift between them.
 * [message] rides as the sole [BoundaryDenialSink.deny] `deniedArgs` entry —
 * a metadata-plane frame, never an [civictech.cell.Owned]/[civictech.cell.Leased]
 * exclusive (protocol messages are plain payloads, unlike `PORT_API`
 * arguments; feature `computenet-usd.2`'s exclusive-discharge concerns do not
 * apply at this seam).
 */
private fun BoundaryDenialSink.denyProtocol(
    reason: DenialReason,
    id: ProtocolId,
    principal: PeerId,
    detail: String,
    message: Any,
) {
    deny(
        seam = BoundarySeam.PROTOCOL_AUTHORITY,
        reason = reason,
        principal = principal,
        subject = id.name,
        detail = detail,
        deniedArgs = listOf(message),
    )
}
