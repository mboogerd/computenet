package civictech.cell.membrane

import civictech.cell.control.AttentionBand
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
 * lambda on the wire (P9), parallel to attention's [civictech.cell.control.AttentionAggregator]
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
 *
 * ## Reconvergence across disclosure regimes: a projected value is a DISTINCT LOGICAL VALUE
 *
 * Decided `computenet-usd.3.2` (2026-08-16), settling 93 I-28 §9 open question 1
 * — **stance (ii)**. A reconvergent (diamond) graph can present one downstream
 * cell with values that reached it under different disclosure regimes: one arm
 * crossing a membrane declaring [Project], another arm crossing none. Since
 * disclosure is a property of ONE boundary (see [BoundaryPolicy]'s multi-hop
 * block), nothing about that meeting is visible to either boundary or to the
 * join. The modelling rule is:
 *
 * > `Project(p)` does not produce a dimmer view of the same logical value. It
 * > **mints a different logical value**, in `p`'s own domain, at the boundary.
 * > Joining `p(v)` with `v` and reading the pair as one consistent state is
 * > therefore a **modelling error at the graph author's hand** — two different
 * > logical values presented as one — and, per the stance's own words, one the
 * > boundary would be the right place to refuse.
 *
 * ### Why (ii) and not (i)
 *
 * Stance (i) — "disclosure regimes must be uniform across all arms reaching a
 * reconvergence point", checkable at link time — was rejected on two grounds,
 * the second of which is fatal independent of any missing machinery:
 *
 * 1. **Nothing to consult.** There is no cross-hop policy visibility: a policy
 *    is a property of the exposure it is declared on, nothing propagates it,
 *    and identity does not transit hops ([BoundaryPolicy]'s multi-hop block,
 *    `computenet-usd.4.3`). At link time a join cannot ask an arm which regime
 *    its upstream crossing was under, because the arm carries no such record.
 * 2. **"The regime of an arm" is not well-formed.** The regime a value actually
 *    crossed under is decided **per emission**, not per link, on three counts
 *    that are all landed today:
 *    - a [Project] transform decides per delta — the same policy lets one wave
 *      through and suppresses the next (exactly what BS-13's fixture does), so
 *      even one boundary under one principal has no constant "regime";
 *    - it is re-declarable at runtime with no link touched:
 *      [ProjectionRegistry.register] overwrites the transform behind a live
 *      [ProjectionId], and `FanOutlet.disclosureFilter` is a public `var`;
 *    - it is identity-sensitive. Not because [DisclosurePolicy] itself carries a
 *      [Principal] — it does not, and `CompositeCell`'s filter reads
 *      [currentPrincipal] only to *label the denial record* — but because a
 *      [Projection] is an opaque `(Any) -> Any?` free to read the ambient
 *      principal, and that principal varies per emission frame rather than per
 *      subscriber: a catch-up unicast runs under the requesting peer, a live
 *      broadcast under `LocalTrusted` or, when a remote ack unblocks it, under
 *      the *acking* peer (`ManagedHost`, `computenet-usd.4.3`, which names
 *      "a principal-keyed projection" as the case it affects). So two
 *      subscribers of one outlet can hold state minted under different regimes
 *      — across emissions; **within** one broadcast `[SEC1-19]` shares a single
 *      verdict with every attachment.
 *
 *    A uniformity constraint would therefore have to be re-evaluated per
 *    emission (and, for a principal-keyed projection, per principal), at which
 *    point it is not a link-time declaration constraint at all. Adding the
 *    metadata (1) lacks would not repair (2).
 *
 * Stance (ii) also *earns its keep on the landed machinery*, which is why this
 * feature needed no new frontier or link semantics: if a projected value is a
 * distinct logical value, then a **suppressed** projection is simply "this arm
 * has no value in its own domain for this wave" — the same shape as any arm
 * that does not contribute to a wave, whose disposition the model already
 * decided (the I-18 edge-will-not-deliver classification -> `WaveFrontier`
 * RE-SCOPE + a surfaced `GlitchViolation`, landed `computenet-usd.3.1`). Under
 * stance (i) the same graph would instead have been *ill-formed from the
 * start*, and the runtime's job would have been to reject it — a strictly
 * larger, and unbuildable, obligation.
 *
 * ### Enforceable today: nothing. Deferred, and what it needs
 *
 * Neither stance is enforceable on the landed machinery, and this record does
 * not pretend otherwise: there is **no regime tagging on values**, so a
 * projected delta is indistinguishable downstream from a full one, and the
 * reconvergence point cannot detect — let alone refuse — that its arms
 * disagree. Enforcing (ii) needs a value-level regime tag that survives
 * re-emission through intermediate cells (a provenance carrier the wave/tag
 * plane deliberately does not have — predicates never mint or carry waves, 93
 * I-28 §6 / G-20), plus a rule for what a cell derived from two regimes then
 * *is*. That is cross-hop composition, which 93 I-28 §8 leaves open and SEC1
 * does not settle. **Until it lands, the rule above is a modelling discipline
 * for graph authors, enforced by nobody.**
 *
 * ### The residual this leaves, stated where it bites
 *
 * `[SEC1-28]` ("the reconvergence point never observes a mixed pre/post-denial
 * combination") is therefore only partly true, and is filed UNVERIFIED in
 * `concord/corpus/DISPUTES.md`. What holds, asserted in
 * `kernel/src/test/kotlin/civictech/cell/consistency/BoundaryDenialDiamondGlitchTest.kt`:
 * a glitch-free join releases each wave contiguously and carries only that
 * wave's contributions, so the denied arm's pre-denial value is never
 * substituted into the release. What does not: the join forms no *combination*
 * — it groups — so any consumer that folds the arms holds the mixture, at a
 * denied wave and (torn, transiently) at every other wave too. Nothing refuses
 * it, which under this stance is exactly the deferred enforcement above.
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
 * is exactly as permissive as before this ticket. Peer admission is enforced
 * at the hello gate (`Peering.Allowlist`), not here.
 *
 * ## Multi-hop: re-declare at every membrane, never compose implicitly
 *
 * A delta that crosses membrane A and then membrane B is subject to **B's own**
 * [disclosure] and [integrity], evaluated afresh at B. Nothing here composes A's
 * predicates onto B's crossing, and nothing propagates them there: a policy is a
 * property of the exposure it is declared on, and an exposure that declares none
 * is open (P7) regardless of what the value passed through upstream. So a
 * membrane that must not re-disclose what it received under a projection has to
 * **say so on its own exposure**; inheriting A's projection is not something B
 * gets for free, and a design that assumed it would be wrong.
 *
 * That is the decided *safe default* of 93 I-28 §9 open question 2 ("the safe
 * default is re-declare"), and it is deliberately the whole of what SEC1
 * settles. The safe default is not the same as an answer: cross-hop
 * **composition** — whether a downstream membrane should be able to see, weaken
 * or be bound by an upstream one's disclosure, and what a transitive audit of a
 * multi-hop path even means — stays open at 93 I-28 §8 and is nobody's here. Two
 * consequences worth stating plainly, because re-declare is safe rather than
 * complete:
 *
 * - re-declaring is **manual and unenforced**. Nothing detects a membrane that
 *   forwards a projected feed under `Full`; the boundary that failed to
 *   re-declare simply discloses what it holds.
 * - a re-declared predicate is evaluated against **the local crossing's**
 *   [Principal], not the originating one. Identity does not transit hops either
 *   (`currentPrincipal` reads the ambient stamp of the delivery in flight), so
 *   "who is really asking, two hops out" is not a question this vocabulary can
 *   answer today.
 *
 * ## Sibling decision: reconvergence across regimes
 *
 * The other half of I-28 §9 — what it means when two arms of one *diamond*
 * reach a join under different disclosure regimes — is decided on
 * [DisclosurePolicy] (`computenet-usd.3.2`): a projected value is a distinct
 * logical value, so reconvergence across regimes is a modelling error, and
 * enforcing that is deferred for want of regime tagging. Read the two together:
 * this block is why the enforcement has nothing to consult.
 *
 * No `[43-*]` requirement id is minted for any of this: `doc/spec/40-distribution/43-security.md`
 * carries no normative EARS ids, so the default lives here as design record
 * (`computenet-usd.4.3`), not as a citable requirement.
 */
data class BoundaryPolicy(
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
