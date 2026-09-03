package civictech.cell.link

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

/**
 * **Which key authenticated a connection** — the key identifier, and *not* a
 * durable identity (feature `computenet-376c`; maintainer decision on
 * `computenet-aimh`, 2026-08-29).
 *
 * Consumed by **boundary admission**: allowlists ([allowPeers],
 * `Peering.Side.allow`), refusals, and the self-assertion check on a hello.
 * The question it answers is "may this connection in front of me be let in",
 * which is a property of the key on the wire right now.
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
 * - `civictech.cell.wire.AnnouncementSigningInput.mintingPeerId`.
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
 */
fun interface PeerIdentityBinding {
    /** The durable identity of the peer that key [key] belongs to. */
    fun identityOf(key: KeyId): PeerId

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
         */
        val Interim: PeerIdentityBinding = PeerIdentityBinding { PeerId(it.name) }
    }
}

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
 */
data class PeerStamp(val id: PeerId, val auth: AuthLevel = AuthLevel.TransportVouched)

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
     * Run [block] under the stamp `(peer, auth)`. [auth] defaults to
     * [AuthLevel.TransportVouched], so every pre-DSC1 call site — and every
     * `with(null) { ... }` reset — keeps its exact previous meaning.
     */
    fun <R> with(peer: PeerId?, auth: AuthLevel = AuthLevel.TransportVouched, block: () -> R): R =
        withStamp(peer?.let { PeerStamp(it, auth) }, block)

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
