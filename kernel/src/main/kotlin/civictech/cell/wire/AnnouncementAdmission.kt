package civictech.cell.wire

import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How far a receiver's clock may lag the minting peer's before a still-valid
 * announcement is refused as [DenialReason.EXPIRED], in milliseconds.
 *
 * Thirty seconds, and it is a **configured constant, not a measurement**
 * ([DSC1-NV-03]). Its limits, stated here rather than only in the bead:
 *
 * - It is *not* clock-skew detection. Nothing here observes the peer's clock,
 *   compares the two, or reports a divergence; a deployment whose clocks are
 *   further apart than this simply refuses announcements, and the refusal says
 *   so ([AnnouncementAdmission.check]'s `EXPIRED` detail names the receiver's
 *   clock precisely so an operator can tell that case from a genuinely stale
 *   frame). Detection is deferred, deliberately.
 * - It buys nothing against a *fast* receiver clock, only a slow one: the
 *   allowance is subtracted from now, never added to `notAfter`'s other side.
 *   An announcement minted in the future is not refused for being early —
 *   [DEFAULT_ANNOUNCEMENT_TTL_MILLIS] bounds how far ahead a signer may set
 *   `notAfter`, and a hostile signer's `notAfter` is inside its own signed
 *   region either way, so no receiver-side ceiling would make it more honest.
 * - It is not a replay window. Replay is defeated by the per-identity counter
 *   ([AnnouncementAdmission]'s high-water mark), and expiry only bounds how
 *   long a captured frame is worth replaying *at a receiver that has lost that
 *   state*.
 */
const val DEFAULT_ANNOUNCEMENT_SKEW_MILLIS: Long = 30_000L

/**
 * Verifies one announcement's signature — the receive-side counterpart of
 * [AnnouncementSigningConfig.encode], and injected for exactly the same reason:
 * `:kernel` may hold no cryptographic provider and no canonical encoder
 * ([DSC1-WIRE-04]).
 *
 * **This is `civictech.cell.membrane.SignatureVerifier`'s shape, with the
 * payload narrowed** from `Any?` to [SignableAnnouncement] — the one payload an
 * announcement crossing can carry. A `SignatureVerifier` is therefore a direct
 * fit; the kernel-side test wires
 * `civictech.identity.Ed25519SignatureVerifier(publicKeys, canonicalBytes)` and
 * hands its `verify` straight to this seam, and task 4 wires the same object in
 * `:wire`.
 *
 * It is declared here rather than *imported* from `membrane` because the T10-C
 * package-edge ratchet (`kernel/src/test/resources/architecture/package-edges.txt`)
 * pins no `wire -> membrane` edge, and an announcement gate is not worth one:
 * the narrowing is the useful half of the type anyway, and a total
 * `SignatureVerifier` satisfies it by construction.
 *
 * **Must be total.** Every failure — an unknown peer, a malformed signature, a
 * mismatched key, an encoder that throws — is `false`, never an exception:
 * a verifier that can throw turns hostile input into a control-flow event at an
 * ingress seam. `Ed25519SignatureVerifier` already guarantees exactly this, and
 * that guarantee is why the gate never has to guard the call. One consequence
 * is recorded on `computenet-l8y5`: an *unencodable* announcement (the
 * ill-formed-UTF-16 `portName` `canonicalBytes` rejects) verifies `false` and
 * so classifies here as [DenialReason.BAD_SIGNATURE], which is honest but not
 * distinguishing. Giving it its own reason is that item's work, and nothing
 * here forecloses it.
 */
fun interface AnnouncementVerifier {
    fun verify(
        mintingPeer: PeerId,
        counter: Long,
        announcement: SignableAnnouncement,
        signature: ByteArray,
    ): Boolean
}

/**
 * The injection surface a side needs in order to *verify* the announcements it
 * receives — the mirror image of [AnnouncementSigningConfig].
 *
 * **A side verifies exactly when this is present**, independent of
 * [PeerAuthPolicy]. That is the same rule, for the same reason, that
 * [Peering.Side.announcementSigning] settles on the emit side: the kernel
 * cannot manufacture a verifier any more than it can manufacture an encoder
 * ([DSC1-WIRE-04]), so "this side requires signed announcements" is the
 * presence of this object and nothing else. Deriving it from
 * `PeerAuthPolicy.RequireAuthenticated` instead would state an invariant the
 * kernel cannot enforce (it could only demand that *some* config was supplied,
 * not that verification works) and would oblige every kernel-side test to
 * inject a canonical encoder the kernel is forbidden to own.
 *
 * Two consequences, both deliberate:
 *
 * - A side with no verification configuration behaves **exactly as it did
 *   before this feature**, byte for byte ([DSC1-WIRE-06]): the four signing
 *   fields are read by nothing, an unsigned announcement is delivered, and a
 *   signed one is delivered unverified. That is what keeps every landed
 *   `Peering.Side` — kernel, `:wire`, every demo — on its current path.
 * - Where this *is* present, the gate is unconditional: an unsigned
 *   announcement is refused ([DenialReason.UNSIGNED]) rather than tolerated.
 *   There is no per-frame opt-out and no "warn only" mode.
 *
 * The residual this leaves is task 4's, and it is the same residual the emit
 * side left: until `:wire` supplies both halves and adds the
 * `RequireAuthenticated`-implies-signing `require`, a `RequireAuthenticated`
 * side that neither signs nor verifies is representable. Enforcing the
 * implication belongs where the canonical encoder is in scope.
 *
 * @property verifier see [AnnouncementVerifier]; must be total.
 * @property clock the receiver's source of "now" in epoch millis. Injected so a
 *   test fixes expiry without sleeping, and so the [DenialReason.EXPIRED]
 *   detail can name *which* clock refused ([clockName]).
 * @property skewMillis how far this receiver's clock may lag the signer's
 *   before a live announcement is refused; see
 *   [DEFAULT_ANNOUNCEMENT_SKEW_MILLIS] for what the number does and does not
 *   claim.
 * @property clockName how the [DenialReason.EXPIRED] detail names the clock
 *   that made the decision (epic §9.6). Free text for the audit trail — an
 *   operator reading "expired against `<name>`" needs to know *whose* now was
 *   used before concluding the peer is stale.
 */
data class AnnouncementVerification(
    val verifier: AnnouncementVerifier,
    val clock: () -> Long = System::currentTimeMillis,
    val skewMillis: Long = DEFAULT_ANNOUNCEMENT_SKEW_MILLIS,
    val clockName: String = "the receiver's system clock",
)

/**
 * Why one announcement was refused: the machine-readable [reason] and the
 * audit-trail [detail] that accompanies it into the [civictech.cell.BoundaryDenial].
 *
 * [detail] is free text and is **rendered into a dead letter and, when the
 * host's metered logger picks it, into stderr** — so it carries no private key
 * material, no raw signature bytes and no nonce ([DSC1-OBS-05]). What it may
 * carry is public metadata a reader needs to act: peer names (public by
 * construction — a `PeerId` is a key fingerprint), counters, and epoch
 * timestamps.
 */
data class AnnouncementRejection(val reason: DenialReason, val detail: String)

/**
 * The trust decision for arriving announcements, in **one** place: the closed
 * taxonomy of [DSC1-ANN-05..13], plus the replay state it needs.
 *
 * ## Where this object lives, and why that is the whole point
 *
 * On the [Peering.Side], not on the [BridgeIngressCell] — exactly as
 * [AnnouncementSigner] lives on the Side and not on the [BridgeEgressCell], and
 * for the mirrored reason ([DSC1-ANN-13], epic §9.3). The high-water mark is
 * per *minting peer identity*, and a reconnect mints a fresh ingress
 * (`WsTransport.Session`) or a fresh mirror pair (`Peering.Loopback.heal`). A
 * ledger on the ingress would be discarded with it, and a captured frame could
 * then be replayed by first provoking a reconnect — which is not a replay
 * defense at all. `SignedAnnouncementTest`'s ingress-replacement case is what
 * holds this: it replaces the ingress and replays a frame the *previous* one
 * accepted.
 *
 * ## Bounded state
 *
 * One `Long` per minting identity ever admitted, and nothing per announcement:
 * [highWater] is written only on the accept path and only with a strictly
 * greater counter, so its size is `O(admitted peers)` and its growth is
 * independent of traffic. Deliberately **not** windowed or evicted — unlike the
 * hello nonce memory ([DEFAULT_NONCE_RETENTION_MILLIS]), which is per *hello*
 * and therefore unbounded without a window, this is already proportional to the
 * peer set an operator provisioned.
 *
 * ## Order of checks, which is a semantic decision and not an optimization
 *
 * [DenialReason.ID_MISMATCH] is decided **before** the signature is verified,
 * so that which reason a frame gets does not depend on where the verifier
 * happens to look up keys. [AnnouncementVerifier.verify] is handed the frame's
 * *minting* peer, never [boundPeer] — the binding is this class's job, not the
 * verifier's — and that is what makes the ordering matter differently for the
 * two verifier shapes DSC1 admits:
 *
 * - A verifier that resolves only **the connection's** key (task 4's
 *   hello-bound shape) answers `false` for a validly signed announcement
 *   minted by B and injected on a connection bound to A. Verified first, that
 *   frame reports [DenialReason.BAD_SIGNATURE] — collapsing impersonation into
 *   "bad crypto" and losing precisely the fact that this gate binds an
 *   announcement to *its connection*.
 * - A verifier over a **directory** of known peers (what `SignedAnnouncementTest`
 *   injects, and what a multi-peer receiver naturally holds) answers `true` for
 *   that same frame, and the binding check reports [DenialReason.ID_MISMATCH]
 *   on either side of the verify call.
 *
 * **Caveat, measured at review and stated here rather than only in the review:**
 * because this file's suite injects the directory-shaped verifier, no test here
 * pins the order. Moving both ID_MISMATCH blocks below the verify call compiles
 * and leaves all ten cases green. The order is kept for the first bullet's sake
 * — it is a deliberate choice, not a test-constrained invariant, and a change to
 * it will not redden this suite.
 */
class AnnouncementAdmission internal constructor(
    private val config: AnnouncementVerification,
) {
    /** Highest counter accepted per minting identity — `MediateProxy`'s discipline (spec 40/43 seam 3). */
    private val highWater = ConcurrentHashMap<PeerId, Long>()
    private val rejected = AtomicLong()

    /**
     * Monotonic count of announcements this side refused, all reasons summed
     * ([DSC1-OBS-02..04]). Readable without a debugger and **without a host**,
     * in the style of `WsTransport.Session.preHelloDrops`.
     *
     * Deliberately on the side-scoped ledger rather than only on the ingress's
     * `BoundaryDenialSink`: the per-boundary counter
     * (`boundaryDenials["announcement-admission"].denialCount`) is reset to zero
     * by the very ingress replacement this class exists to survive, so an
     * operator watching a reconnecting peer would see the count go backwards.
     * Both counters exist; this is the one that spans the connection's life.
     */
    val rejectedAnnouncements: Long get() = rejected.get()

    /** How many minting identities this ledger holds a high-water mark for — the bounded-state read. */
    val trackedPeers: Int get() = highWater.size

    /** The highest counter accepted from [peer], or null if none ever was. Test/diagnostic surface. */
    fun highWaterFor(peer: PeerId): Long? = highWater[peer]

    /**
     * The gate. Returns `null` to admit — in which case [frame]'s counter has
     * *already* been recorded as this identity's new high-water mark — or the
     * [AnnouncementRejection] to account.
     *
     * [boundPeer] is the identity the **connection** is bound to, fixed by the
     * caller before the ingress existed ([BridgeIngressCell.peer]): a hello's
     * admission row on a socket, the opposite `Side`'s configuration on a
     * loopback. It is never read out of the frame — that is the whole binding.
     */
    fun check(boundPeer: PeerId?, frame: WireFrame): AnnouncementRejection? {
        val rejection = classify(boundPeer, frame)
        if (rejection != null) rejected.incrementAndGet()
        return rejection
    }

    private fun classify(boundPeer: PeerId?, frame: WireFrame): AnnouncementRejection? {
        // [DSC1-ANN-05] UNSIGNED: the four fields travel together or not at all,
        // so a partially populated frame is as unsigned as an empty one.
        val encodedSignature = frame.signature
        val signerKeyId = frame.signerKeyId
        val counter = frame.sigCounter
        val notAfter = frame.notAfter
        if (encodedSignature == null || signerKeyId == null || counter == null || notAfter == null) {
            return AnnouncementRejection(
                DenialReason.UNSIGNED,
                "announcement carries no signature and this side requires one " +
                    "(signature=${encodedSignature != null}, signerKeyId=${signerKeyId != null}, " +
                    "counter=${counter != null}, notAfter=${notAfter != null})",
            )
        }

        // [DSC1-ANN-08] ID_MISMATCH, before any crypto — see the class KDoc.
        // `signerKeyId` IS the minting identity's name: on every :identity-backed
        // signer it defaults to `credentials.peerId.name`, which is the key's own
        // fingerprint ([DSC1-WIRE-01], AnnouncementSigningConfig.signerKeyId).
        val mintingPeer = PeerId(signerKeyId)
        if (boundPeer == null) {
            return AnnouncementRejection(
                DenialReason.ID_MISMATCH,
                "announcement minted by '${mintingPeer.name}' arrived on a connection bound to no " +
                    "peer identity, so nothing can be held to it",
            )
        }
        if (mintingPeer != boundPeer) {
            return AnnouncementRejection(
                DenialReason.ID_MISMATCH,
                "announcement minted by '${mintingPeer.name}' arrived on the connection bound to " +
                    "'${boundPeer.name}' — the signature may verify perfectly; what fails is the " +
                    "binding between the minting identity and this connection",
            )
        }

        // [DSC1-ANN-06] BAD_SIGNATURE. The transport representation is base64url
        // (WireFrame.signature); anything that is not is refused here rather than
        // reaching the verifier.
        val signature = runCatching { BASE64URL.decode(encodedSignature) }.getOrNull()
            ?: return AnnouncementRejection(
                DenialReason.BAD_SIGNATURE,
                "the signature field is not base64url (its length and content are not reported here, " +
                    "[DSC1-OBS-05])",
            )
        val announcement = SignableAnnouncement(
            mintingPeerId = mintingPeer,
            counter = counter,
            notAfter = notAfter,
            contractId = frame.contractId,
            methodId = frame.methodId,
            cellRef = frame.cellRef,
            portName = frame.portName,
            args = frame.args,
        )
        if (!config.verifier.verify(mintingPeer, counter, announcement, signature)) {
            return AnnouncementRejection(
                DenialReason.BAD_SIGNATURE,
                "the signature does not verify under the key bound to this connection " +
                    "('${boundPeer.name}', counter=$counter)",
            )
        }

        // [DSC1-ANN-07] EXPIRED, against the RECEIVER's injected clock plus the
        // configured skew allowance (epic §9.6: the reason names the clock).
        val now = config.clock()
        if (notAfter < now - config.skewMillis) {
            return AnnouncementRejection(
                DenialReason.EXPIRED,
                "announcement expired: notAfter=$notAfter is before now=$now less the " +
                    "${config.skewMillis}ms skew allowance, measured against ${config.clockName}",
            )
        }

        // [DSC1-ANN-09] REPLAY. Check-and-advance in one atomic step so two
        // ingresses of one side (a reconnect mid-flight) cannot both accept the
        // same counter.
        var accepted = false
        highWater.compute(mintingPeer) { _, seen ->
            if (seen != null && counter <= seen) {
                seen
            } else {
                accepted = true
                counter
            }
        }
        if (!accepted) {
            return AnnouncementRejection(
                DenialReason.REPLAY,
                "counter=$counter does not exceed the highest already accepted from " +
                    "'${mintingPeer.name}' (${highWater[mintingPeer]})",
            )
        }
        return null
    }

    private companion object {
        val BASE64URL: Base64.Decoder = Base64.getUrlDecoder()
    }
}
