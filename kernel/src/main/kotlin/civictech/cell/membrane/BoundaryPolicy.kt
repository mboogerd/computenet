package civictech.cell.membrane

import civictech.cell.attention.AttentionBand
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkPolicy
import civictech.cell.link.PeerId
import civictech.cell.protocol.ProtocolId

/**
 * Identity every crossing carries (spec 40/43 "Identity", decided 93 I-28
 * §4.1): [LocalTrusted] for in-host/same-registry crossings (today's null
 * identity); [Peer] for bridge crossings, keyed on the [PeerId] the G-29
 * ingress already stamps.
 */
sealed interface Principal {
    data object LocalTrusted : Principal
    data class Peer(val id: PeerId, val auth: AuthLevel) : Principal
}

/**
 * How strongly a [Principal.Peer.id] is vouched for (spec 40/43): phase-1
 * (landed) is [TransportVouched] — the transport connection vouches for the
 * name; phase-2 (deferred, 95 §R7) is [Authenticated] — a key/DID plus a
 * signed-nonce hello challenge. The policy vocabulary below is stable across
 * the upgrade; only the strength this enum certifies changes.
 */
enum class AuthLevel { TransportVouched, Authenticated }

/**
 * The ambient [Principal] for the delivery in flight (spec 40/43 §4.1):
 * mirrors the existing [CurrentPeer] ambient (G-29 phase 1) — a stamped
 * [PeerId] becomes a [Principal.Peer] at [AuthLevel.TransportVouched] (no
 * stronger claim is possible until phase-2 exists); no stamped peer is
 * [Principal.LocalTrusted].
 */
fun currentPrincipal(): Principal =
    CurrentPeer.get()?.let { Principal.Peer(it, AuthLevel.TransportVouched) } ?: Principal.LocalTrusted

/** Seam 1 predicate (spec 40/43): admits or refuses a [Principal] at the peering hello. */
fun interface PeerPredicate {
    fun admits(principal: Principal): Boolean

    companion object {
        val AllowAll = PeerPredicate { true }
    }
}

/**
 * Per-[ProtocolId] flow-time authority (spec 40/43 seam 3): a floor on
 * [AuthLevel], an attention [ceiling] band (clamps an asserted level, the
 * fold/band-gating untouched), and a per-[Principal] [ratePerWindow] anti-
 * Sybil throttle. All default open (P7).
 */
data class ProtocolAuthority(
    val minAuth: AuthLevel = AuthLevel.TransportVouched,
    val ceiling: AttentionBand? = null,
    val ratePerWindow: Int? = null,
)

/**
 * A named, registered pure `Delta -> Delta` transform (spec 40/43): never a
 * lambda on the wire (P9), parallel to attention's [civictech.cell.attention.AttentionAggregator]
 * registration style.
 */
data class ProjectionId(val name: String)

/** The transform a [ProjectionId] resolves to; registered, not serialized. */
fun interface Projection {
    /** Returns the redacted/scoped delta, or null to suppress this particular emission. */
    fun apply(delta: Any): Any?
}

/** Process-local registry from [ProjectionId] to its [Projection] (spec 40/43, P9). */
object ProjectionRegistry {
    private val transforms = mutableMapOf<ProjectionId, Projection>()

    fun register(id: ProjectionId, projection: Projection) {
        transforms[id] = projection
    }

    fun resolve(id: ProjectionId): Projection =
        requireNotNull(transforms[id]) { "unregistered ProjectionId: ${id.name} (spec 40/43)" }
}

/**
 * Seam-3 `PORT_API` outbound predicate (spec 40/43, 20/21 §Pull): one filter
 * covers both the `onLinked` catch-up unicast and the live stream uniformly
 * — "a snapshot IS a delta" (decided 93 I-28).
 */
sealed interface DisclosurePolicy {
    /** Forwards unchanged — today's behavior. */
    data object Full : DisclosurePolicy

    /** Runs the named [id] projection over every emitted delta. */
    data class Project(val id: ProjectionId) : DisclosurePolicy

    /** Suppresses catch-up and live emission entirely (attention-/management-only peerings). */
    data object Deny : DisclosurePolicy
}

/** Seam-3 `PORT_API` inbound predicate (spec 40/43): integrity of arriving deltas. */
sealed interface IntegrityPolicy {
    data object None : IntegrityPolicy

    /** Ingress MUST verify a signature before delivery; failure dead-letters (never delivered). */
    data object RequireSigned : IntegrityPolicy
}

/**
 * The boundary-policy vocabulary (spec 40/43 "BoundaryPolicy: three seams,
 * one per dispatch class", decided 93 I-28): identity-keyed predicates
 * attached to a membrane [Exposure]. Every predicate defaults to today's
 * open behavior (P7/P6) — absent a declared [BoundaryPolicy], every crossing
 * is exactly as permissive as before this ticket.
 */
data class BoundaryPolicy(
    /** Seam 1 (peering hello / bridge ingress) — the landed G-29 gate's predicate slot. */
    val admission: PeerPredicate = PeerPredicate.AllowAll,
    /** Seam 2 (`onLink`) — reuses the existing [LinkPolicy] mechanism (G-14), first-rejection-wins. */
    val linkAuthority: List<LinkPolicy> = emptyList(),
    /** Seam 3 (`PORT_PROTOCOL`), keyed per protocol. */
    val protocolAuthority: Map<ProtocolId, ProtocolAuthority> = emptyMap(),
    /** Seam 3 (`PORT_API` outbound). */
    val disclosure: DisclosurePolicy = DisclosurePolicy.Full,
    /** Seam 3 (`PORT_API` inbound). */
    val integrity: IntegrityPolicy = IntegrityPolicy.None,
) {
    /**
     * "Declaring any flow-time predicate ... MUST force the exposure to
     * Mediate" (spec 10/11 "Boundary policy"). Admission/linkAuthority are
     * seams 1/2 and do not, by themselves, require Mediate.
     */
    val forcesMediate: Boolean
        get() = protocolAuthority.isNotEmpty() ||
            disclosure != DisclosurePolicy.Full ||
            integrity != IntegrityPolicy.None
}

/**
 * Envelope a [IntegrityPolicy.RequireSigned] inbound delta arrives in (spec
 * 40/43 seam 3): a signature over `(mintingPeer, counter, payload)` — the
 * per-source [counter] defeats replay; signing is per-emitting-peer, never
 * per-logical-cell (93 I-28 §4.3).
 */
data class SignedDelta<T>(
    val payload: T,
    val mintingPeer: PeerId,
    val counter: Long,
    val signature: ByteArray,
)

/** Verifies a [SignedDelta]'s signature (spec 40/43 seam 3). */
fun interface SignatureVerifier {
    fun verify(mintingPeer: PeerId, counter: Long, payload: Any?, signature: ByteArray): Boolean

    companion object {
        /**
         * Phase-1 strength (`AuthLevel.TransportVouched`, decided 93 I-28
         * §4.1): no cryptographic key exists for a peer yet — key/DID
         * authentication is phase-2, deferred research (95 §R7). What this
         * verifier certifies is only that the signature names the peer the
         * transport already vouches for; the [SignedDelta.counter] (checked
         * separately by the ingress seam, replay defense) carries the real
         * integrity weight at this phase. Phase-2 replaces this verifier's
         * body, not the [SignatureVerifier] seam.
         */
        val TransportVouched = SignatureVerifier { peer, _, _, signature ->
            signature.contentEquals(peer.name.toByteArray())
        }
    }
}
