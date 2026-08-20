package civictech.cell.link

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

/**
 * G-29 phase 1 (M8.2): who is asking. A bridge ingress stamps every delivered
 * invocation with its transport peer's id; handshakes running during that
 * delivery see it on [LinkRequest.identity]. Local links carry null (= this
 * process). Authentication of the name is future work (43) — today it
 * identifies the *connection*, which the transport vouches for.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PeerId")
data class PeerId(val name: String) : Identity, java.io.Serializable

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
