package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.control.Attention
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.link.Linked
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
) : Cell {

    private val exposureMapMutable = linkedMapOf<String, Exposure>()

    /** The declared exposure map (spec 10/11) — read-only view for diagnostics/tests. */
    val exposureMap: Map<String, Exposure> get() = exposureMapMutable

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
        installLinkAuthority(port, policy)
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
     */
    protected fun <Api : Any> mediate(
        externalName: String,
        organellePortName: String,
        organelleInlet: FanInlet<Api>,
        policy: BoundaryPolicy = BoundaryPolicy(),
    ): FanInlet<Api> {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        val exposed = FanInlet(organelleInlet.clazz)
        exposed.serve(
            Proxy.fromClass(
                organelleInlet.clazz,
                MediateProxy(organelleInlet.call, policy.integrity),
            ),
        )
        installLinkAuthority(exposed, policy)
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
     *   §Pull, decided 93 I-28: "a snapshot IS a delta").
     * - `protocolAuthority[Protocols.Attention].ceiling` clamps an asserted
     *   attention level via [ProtocolSupport.inboundFilter] before this
     *   outlet's own attention handling sees it (30/34 decision 6:
     *   `slot.level = min(asserted, ceiling)`, fold/band-gating untouched).
     *
     * `linkAuthority` is NOT wired here: the target-side handshake (10/13)
     * always runs on whichever port is being linked *to*, which for a
     * consumer subscribing to an exposed outlet is the CONSUMER's own inlet
     * (external, outside this membrane) — the existing [handshake]/
     * [LinkSupport] mechanism has no admission hook on the producing side
     * beyond SPSC exclusivity. Seam 2 is fully realized by [mediate]
     * (organelle inlet exposures), where the exposed port genuinely is the
     * handshake target.
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
                "(protocolAuthority/disclosure/integrity); use flatten() for an open outlet"
        }
        organelleOutlet.disclosureFilter = policy.disclosure.asDeltaFilter()
        if (policy.protocolAuthority.isNotEmpty()) {
            ProtocolSupport.of(organelleOutlet).inboundFilter = policy.protocolAuthority.asProtocolFilter()
        }
        exposureMapMutable[externalName] =
            Exposure(externalName, organellePortName, SurfaceMode.MEDIATE, policy)
        return registerPort(externalName, organelleOutlet)
    }

    private fun installLinkAuthority(port: Port, policy: BoundaryPolicy) {
        if (policy.linkAuthority.isEmpty()) return
        (port as? Linked)?.linking?.policies?.addAll(policy.linkAuthority)
    }
}

/**
 * [DisclosurePolicy] as a [FanOutlet.disclosureFilter] (spec 40/43 seam 3):
 * `Full` is the identity filter; `Deny` suppresses every emission; `Project`
 * runs the registered transform over the emitted delta argument (the first
 * argument, by the "one delta-carrying method" convention data-cell contracts
 * follow), suppressing the emission if the projection itself returns null.
 */
private fun DisclosurePolicy.asDeltaFilter(): (Array<out Any?>) -> Array<out Any?>? = filter@{ args ->
    when (this) {
        is DisclosurePolicy.Full -> args
        is DisclosurePolicy.Deny -> null
        is DisclosurePolicy.Project -> {
            val delta = args.firstOrNull() ?: return@filter args
            val projected = ProjectionRegistry.resolve(id).apply(delta) ?: return@filter null
            arrayOf(projected, *args.drop(1).toTypedArray())
        }
    }
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
 */
private fun Map<ProtocolId, ProtocolAuthority>.asProtocolFilter(): (ProtocolId, Any) -> Any? {
    val counts = java.util.concurrent.ConcurrentHashMap<Pair<ProtocolId, Principal>, Int>()
    return filter@{ id, message ->
        val authority = this[id] ?: return@filter message
        val principal = currentPrincipal()
        if (principal == Principal.LocalTrusted) return@filter message
        val peer = principal as Principal.Peer
        if (peer.auth < authority.minAuth) return@filter null
        authority.ratePerWindow?.let { limit ->
            val key = id to principal
            val next = (counts[key] ?: 0) + 1
            counts[key] = next
            if (next > limit) return@filter null
        }
        if (id == Protocols.Attention && authority.ceiling != null && message is Attention) {
            // preserve the emitter's version: this is the same LWW update, only clamped
            return@filter Attention(minOf(message.level, authority.ceiling.level), message.version)
        }
        message
    }
}
