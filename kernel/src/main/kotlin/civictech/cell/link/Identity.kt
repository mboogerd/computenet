package civictech.cell.link

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

/**
 * **Which key authenticated a connection** — the key identifier, and *not* a
 * durable identity (feature `computenet-376c`; maintainer decision on
 * `computenet-aimh`, 2026-08-29).
 *
 * The key is what a hello is **proven** on: the signature check and the
 * self-assertion check on a hello consume it, and a refusal may name it. It is
 * **not** what an allowlist names — allowlists ([allowPeers],
 * `Peering.Side.allow`) name identities (epic `computenet-5y8t`), judged
 * after the proven key has been resolved through [PeerIdentityBinding].
 *
 * **It MUST NOT be stored as attribution.** A key is replaceable under the
 * peer it belongs to: rotate it and every record keyed on the old
 * identifier names nobody. Anything that records *who a peer durably is* —
 * mirrored `Remote` locations, per-`Principal` statements, moderation
 * decisions — takes a [PeerId], resolved from a key identifier through
 * [PeerIdentityBinding] and never by inspecting key material directly.
 *
 * Deliberately **not** an [Identity], **not** `@Serializable` and **not**
 * `java.io.Serializable`: nothing persists or transmits a `KeyId` *as a
 * type*. The hello and announcement frames carry plain strings and are
 * unchanged by this feature.
 *
 * **Under [AuthLevel.TransportVouched] no key exists at all.** The slot then
 * holds the identifier the peer *asserted* and the transport vouched for —
 * exactly what [PeerId] meant before this feature. An Open/legacy-hello
 * reader should not read the type's name as a promise that a key was
 * presented or proved; only [AuthLevel.Authenticated] carries that.
 */
data class KeyId(val name: String)

/**
 * **Who a peer durably IS** — the peer identity, consumed by *attribution*.
 *
 * The counterpart of [KeyId], and the other half of the split the maintainer
 * decision on `computenet-aimh` (2026-08-29) made necessary: identity becomes
 * a stable name and a key becomes something bound to that name and
 * replaceable under it, so one type can no longer carry both (feature
 * `computenet-376c`). While identity was *defined as* the key fingerprint the
 * conflation was invisible, because the two were the same value.
 *
 * Consumers — every one of these means the durable identity, never the key:
 * - [PeerStamp.id], and therefore [CurrentPeer] and
 *   `civictech.cell.proxy.HostedPortInvocation`;
 * - `civictech.cell.membrane.Principal.Peer.id` (`currentPrincipal()`);
 * - `civictech.cell.link.LinkRequest.identity`, which is built from
 *   [CurrentPeer.get];
 * - `civictech.cell.location.LocationRegistry.Remote.peer` (mirrored
 *   attribution);
 * - `civictech.cell.wire.AnnouncementSigningInput.mintingPeerId`;
 * - boundary admission: `civictech.cell.wire.Peering.Side.allow` and
 *   [allowPeers]. The key is what a hello is **proven** on; allowlists name
 *   identities (epic `computenet-5y8t`).
 *
 * **It is NOT derived from key material anywhere except through
 * [PeerIdentityBinding].** That seam is the single place the derivation
 * lives; there is no second site.
 *
 * G-29 phase 1 (M8.2) origin: a bridge ingress stamps every delivered
 * invocation with the identity it resolved for its transport peer; handshakes
 * running during that delivery see it on `LinkRequest.identity`. Local links
 * carry null (= this process).
 *
 * The name, the `@SerialName` and the `java.io.Serializable` marker are
 * deliberately unchanged: the serial name is a compatibility surface, and
 * renaming the type across its ~75 referencing files would be churn with no
 * semantic gain.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PeerId")
data class PeerId(val name: String) : Identity, java.io.Serializable

/**
 * The one seam that resolves a [KeyId] to the [PeerId] it belongs to
 * (feature `computenet-376c`).
 *
 * Shaped like `civictech.cell.membrane.SignatureVerifier`: the kernel
 * *declares* the seam and ships a default in the companion; a later binding
 * (DSC4's anchor-vouched names, supplied from `:identity`) is injected rather
 * than compiled in. Admission decides on the key; whatever it admits is
 * stamped with the identity this binding resolves.
 *
 * **Resolution is PARTIAL** (task `computenet-hbqvz`). [resolve] answers an
 * [IdentityResolution], not a bare [PeerId], because a binding other than
 * [Interim] can hold keys that have *no* identity — a key whose binding was
 * superseded or whose validity window lapsed, under DSC4's anchor-vouched
 * names. A total signature would force such a binding to invent one (a
 * sentinel, an exception, or a fallback to `PeerId(key.name)`, which is the
 * identity-is-the-key behaviour the split exists to end), or push the refusal
 * into a second seam. Every caller therefore handles
 * [IdentityResolution.Unbound] explicitly: an admission path refuses the
 * connection, and nothing ever substitutes a name derived from the key.
 *
 * This is the shape only. No binding in the tree returns
 * [IdentityResolution.Unbound] today, no revocation mechanism exists, and
 * **`[DSC1-NV-01]` remains EXPLICITLY UNVERIFIED** — the refusal arm says
 * nothing about stolen-key resistance.
 */
fun interface PeerIdentityBinding {
    /**
     * The durable identity of the peer that key [key] belongs to —
     * [IdentityResolution.Bound] — or [IdentityResolution.Unbound] when this
     * binding holds no identity for [key].
     *
     * [presented] is the evidence the key's holder presented for a name: the
     * [IdentityStatement]s it carries (feature `computenet-5y8t.1`). It has
     * **no default** on purpose — every caller states the evidence it holds,
     * even when that is `emptyList()`, so a site that could forward statements
     * and does not is visible at the call rather than hidden behind a default.
     * A binding is free to ignore the list ([Interim] does); a binding that
     * vouches names from statements reads it.
     */
    fun resolve(key: KeyId, presented: List<IdentityStatement>): IdentityResolution

    companion object {
        /**
         * The **INTERIM** binding (feature `computenet-376c`): a peer's
         * identity is its key identifier's own name.
         *
         * **This lambda body is THE ONE place an identity is derived from a
         * key identifier.** The whole point of the seam is that the
         * derivation has a single named home, so that when DSC4 lands
         * anchor-vouched stable names it replaces *this default binding* and
         * not a scattering of call sites. If you find yourself writing
         * `PeerId(someKey.name)` — or a fingerprint-to-`PeerId` step —
         * anywhere else, that is the second site this seam exists to
         * prevent.
         *
         * It makes the split behaviour-preserving today: identity and key
         * identifier hold the same string, which is exactly what they held
         * before the two types existed.
         *
         * **`[DSC1-NV-01]` remains EXPLICITLY UNVERIFIED.** This seam claims
         * nothing about stolen-key resistance — an attacker holding a peer's
         * key is still that peer as far as this binding is concerned (epic
         * `computenet-5y8t` residual R4). Naming the derivation does not
         * strengthen it.
         *
         * **Total by construction**: it resolves every key identifier to
         * [IdentityResolution.Bound] and never answers
         * [IdentityResolution.Unbound], so no verdict anywhere changes before a
         * partial binding is injected.
         *
         * **Ignores `presented`** (feature `computenet-5y8t.1`): a statement
         * naming some other peer or issuer changes nothing, and the result is
         * always key-derived — `issuer` and `statement` are null.
         */
        val Interim: PeerIdentityBinding = PeerIdentityBinding { key, _ ->
            IdentityResolution.Bound(PeerId(key.name), issuer = null, statement = null)
        }
    }
}

/**
 * The answer [PeerIdentityBinding.resolve] gives for one key identifier: the
 * identity it is bound to, or a machine-readable statement that it has none
 * (task `computenet-hbqvz`).
 *
 * **Sealed rather than a nullable [PeerId]** on purpose: the positive arm is
 * where DSC4 attributes a resolution to the issuer that vouched for it
 * (feature `computenet-5y8t.1`), which a bare `PeerId?` cannot carry, and the
 * negative arm carries a *reason* a refusal can classify without string
 * matching — the `civictech.wire.HelloMalformation` precedent.
 */
sealed interface IdentityResolution {
    /**
     * [key][PeerIdentityBinding.resolve]'s durable identity is [peer], vouched
     * for by [issuer] on the strength of [statement].
     *
     * **No defaults** (feature `computenet-5y8t.1`, decision D8): every
     * producer says who vouched. [issuer] is null **exactly when the identity
     * is key-derived** — [PeerIdentityBinding.Interim]'s answer — and non-null
     * when a named issuer vouched for it; [statement] is the presented
     * [IdentityStatement] the resolution rests on, or null when it rests on
     * none.
     */
    data class Bound(
        val peer: PeerId,
        val issuer: IssuerId?,
        val statement: IdentityStatement?,
    ) : IdentityResolution

    /**
     * The binding holds **no identity** for the key. A caller must not invent
     * one: an admission path refuses the connection, attribution records
     * nothing, and in particular nothing falls back to `PeerId(key.name)`.
     */
    data class Unbound(val reason: UnboundReason) : IdentityResolution
}

/**
 * Why a key identifier resolved to no identity — the machine-readable half of
 * [IdentityResolution.Unbound].
 *
 * No production binding in the tree produces any of these today
 * ([PeerIdentityBinding.Interim] is total); the kernel's only producer of
 * [IdentityResolution.Unbound] is test code. The verifying binding that
 * produces the finer reasons is built by feature `computenet-5y8t.2` (DSC4's
 * anchor-signed statements).
 *
 * **Append new reasons; never reorder** (feature `computenet-5y8t.1`,
 * decisions D4/D11). Nothing persists or transmits the ordinal, but the
 * `AuthLevel` discipline is kept so that it never has to become a
 * compatibility rule; `IdentityResolutionTest` pins the exact entries list.
 */
enum class UnboundReason {
    /** The binding holds no identity for this key identifier. */
    NO_BINDING,

    /** No [IdentityStatement] was presented for this key identifier. */
    NO_STATEMENT,

    /** A statement was presented, but its [IdentityStatement.issuer] is not one this binding accepts. */
    ISSUER_NOT_ACCEPTED,

    /** A statement from an accepted issuer was presented, but its signature does not verify. */
    BAD_SIGNATURE,

    /** A verified statement was presented, but it binds a different [KeyId] than the one resolved. */
    KEY_MISMATCH,

    /** The statement's validity window ended before the moment of resolution. */
    EXPIRED,

    /** The statement's validity window had not yet begun at the moment of resolution. */
    NOT_YET_VALID,
}

/**
 * **Who vouched** for a peer's name — the issuer an [IdentityResolution.Bound]
 * is attributed to (feature `computenet-5y8t.1`).
 *
 * An opaque name to the kernel. Under DSC4's anchor binding it is the anchor
 * public key's fingerprint (epic `computenet-5y8t`, decision D3); the kernel
 * does not know that and draws nothing from it.
 *
 * Deliberately carries **no serialization annotation**: no wire frame,
 * journal record or serializer carries an `IssuerId`.
 */
data class IssuerId(val name: String)

/**
 * The kernel-side **shape** of an issuer-signed binding of [name] to [keyId]
 * (feature `computenet-5y8t.1`, decision D2) — kernel DATA like
 * `civictech.cell.membrane.SignedDelta`: fields only.
 *
 * What this type deliberately does **not** have: canonical signed bytes, a
 * verify step, or any cryptography. The bytes that are signed have one
 * definition, in `:identity`, which the kernel does not depend on; this type
 * only carries a statement from the peer that presents it to the binding that
 * judges it. Nothing in the kernel checks [signature].
 *
 * [issuance], [notBefore] and [notAfter] are present from the first version of
 * the format (epic `computenet-5y8t`, decision D4) so that a later
 * supersession or revocation item never has to break it. **Nothing compares
 * them today** (decision D3): carrying a validity window is not enforcing one,
 * and this type claims nothing about stolen-key resistance or revocation —
 * `[DSC1-NV-01]` remains EXPLICITLY UNVERIFIED.
 *
 * **Equality** is the data-class default over a [ByteArray] field (decision
 * D13, the `SignedDelta` precedent): two instances with equal-content but
 * distinct signature arrays are NOT equal. Compare instances you hold, never
 * separately built copies.
 *
 * Deliberately carries **no serialization annotation**: no wire frame,
 * journal record or serializer carries an `IdentityStatement` from the kernel.
 */
data class IdentityStatement(
    val name: PeerId,
    val keyId: KeyId,
    val issuer: IssuerId,
    val issuance: Long,
    val notBefore: Long,
    val notAfter: Long,
    val signature: ByteArray,
)

/**
 * How strongly a peer's [PeerId] is vouched for (spec 40/43, DSC1
 * `[DSC1-HELLO-05]`): [TransportVouched] — the transport connection vouches
 * for the name a peer asserted; [Authenticated] — the peer proved possession
 * of the key its id is the fingerprint of, over a challenge bound to this
 * connection instance.
 *
 * **Why this lives in `civictech.cell.link` rather than beside
 * `civictech.cell.membrane.Principal`** (which is what
 * `civictech.cell.membrane.AuthLevel` still names, as a typealias onto this
 * declaration): the level is *carried* — it rides the same per-connection
 * stamp as [PeerId], through [CurrentPeer] and
 * `civictech.cell.proxy.HostedPortInvocation.peerAuth`, and is read by
 * `civictech.cell.wire`'s bridge and by `:wire`'s transport. Every one of
 * those packages already depends on `civictech.cell.link` and none of them
 * depends on `civictech.cell.membrane`; declaring the level here is what
 * keeps the carrier from dragging a new package edge behind it
 * (`ArchitectureRatchetTest`). The *policy* that reads it —
 * `ProtocolAuthority.minAuth`, `currentPrincipal()` — stays in `membrane`.
 *
 * **Declaration order IS the authority ordering, weakest first** (epic
 * `computenet-ssa` §9.7). This is a semantic contract, not a listing
 * convention: `civictech.cell.membrane.ProtocolAuthority.minAuth` is a
 * *floor*, and the sites that enforce it compare two `AuthLevel`s with the
 * ordering operators Kotlin derives from `Enum.compareTo` — i.e. from
 * `ordinal`. `TransportVouched < Authenticated` therefore has to hold for a
 * `minAuth = Authenticated` floor to refuse a merely transport-vouched
 * principal.
 *
 * Consequences for anyone editing this declaration:
 * - **Reordering the constants, or inserting one out of strength order, is a
 *   security change**, not a refactor — it silently flips which crossings a
 *   floor admits. A new level goes at the position its *strength* dictates.
 * - `AuthLevelOrderingTest` pins both the comparison and the exact entries
 *   list, so a reorder or an insertion fails loudly rather than quietly
 *   re-grading live crossings.
 * - Nothing persists or transmits an `AuthLevel`: it is not `@Serializable`,
 *   no wire frame or journal record carries one, and no `when` matches on it
 *   (audited at `40c13c97`; recorded on `computenet-ssa.3`). The level a
 *   crossing achieved is *derived* from that crossing's admission, never read
 *   off a frame — see [PeerStamp]. Declaration order is thus a *local*
 *   semantic convention today, with no cross-version compatibility constraint
 *   on the ordinals — keep it that way, or the reorder rule above hardens
 *   into a wire-compatibility rule.
 */
enum class AuthLevel { TransportVouched, Authenticated }

/**
 * Who a delivery came from, and how strongly that name is vouched for — the
 * per-connection stamp a bridge ingress applies, in one value.
 *
 * **One stamp, not two ambients** (DSC1 §3 seam 3). [auth] is bound once, at
 * the moment the connection is admitted, and travels with [id] everywhere the
 * id already travelled: it is never derived per message, never read off a
 * frame, and never widened by anything a peer sends after admission. A peer
 * cannot promote itself by asserting a level, because no encoding carries one.
 *
 * [TransportVouched][AuthLevel.TransportVouched] is the default, which is what
 * keeps every pre-DSC1 caller at exactly today's behaviour
 * (`[DSC1-WIRE-06]`).
 *
 * **[id] is the IDENTITY, never the key identifier** (feature
 * `computenet-376c`). A stamp is attribution: it says who the delivery is
 * *from*, which outlives any particular key. The admitting side judges the
 * connection on its [KeyId] and stamps the [PeerId] it resolved through its
 * [PeerIdentityBinding]; a `KeyId` never reaches this slot.
 *
 * [issuer] rides the same stamp as [id] and [auth] — one stamp, not two
 * ambients (DSC1 §3 seam 3; feature `computenet-5y8t.1`, decision D5/D12): it
 * is bound once, by the caller, at the admission decision, and is never
 * derived per message or read off a frame. It is null exactly when the
 * identity is key-derived ([PeerIdentityBinding.Interim]) or the crossing is
 * not [AuthLevel.Authenticated]. Like [AuthLevel] itself, it is not
 * `@Serializable`: no wire frame, journal record or serializer carries an
 * [IssuerId] (audited alongside the [AuthLevel] KDoc's audit note).
 */
data class PeerStamp(val id: PeerId, val auth: AuthLevel = AuthLevel.TransportVouched, val issuer: IssuerId? = null)

/**
 * Ambient identity of the delivery being executed (set by the host around
 * bridged management invocations, read by [handshake] and by
 * `civictech.cell.membrane.currentPrincipal`).
 *
 * The ambient holds a whole [PeerStamp] — id *and* achieved [AuthLevel] — so
 * the auth level rides the existing channel rather than a second one beside
 * it. [get] keeps returning the bare [PeerId] for the many callers that only
 * ever wanted the name; [stamp] is the widened read.
 */
object CurrentPeer {
    private val local = ThreadLocal<PeerStamp?>()

    /** The stamped peer's id, or null when this delivery carries no stamp (= local). */
    fun get(): PeerId? = local.get()?.id

    /** The whole stamp — id plus the level its connection was admitted at. */
    fun stamp(): PeerStamp? = local.get()

    /**
     * Run [block] under the stamp `(peer, auth, issuer)`. [auth] defaults to
     * [AuthLevel.TransportVouched] and [issuer] defaults to null, so every
     * pre-DSC1 call site — and every `with(null) { ... }` reset — keeps its
     * exact previous meaning.
     */
    fun <R> with(peer: PeerId?, auth: AuthLevel = AuthLevel.TransportVouched, issuer: IssuerId? = null, block: () -> R): R =
        withStamp(peer?.let { PeerStamp(it, auth, issuer) }, block)

    /**
     * [with] by whole stamp. A distinct name rather than an overload on
     * purpose: `with(null) { ... }` — the fan-out reset in
     * `civictech.cell.port.FanOutlet` — would be ambiguous between a null
     * [PeerId] and a null [PeerStamp], and both spellings mean the same thing.
     */
    fun <R> withStamp(stamp: PeerStamp?, block: () -> R): R {
        val previous = local.get()
        local.set(stamp)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }
}
