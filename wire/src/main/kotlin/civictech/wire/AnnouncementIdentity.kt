package civictech.wire

import civictech.cell.link.PeerId
import civictech.cell.wire.AnnouncementSigningConfig
import civictech.cell.wire.AnnouncementVerification
import civictech.cell.wire.AnnouncementVerifier
import civictech.cell.wire.DEFAULT_ANNOUNCEMENT_SKEW_MILLIS
import civictech.cell.wire.DEFAULT_ANNOUNCEMENT_TTL_MILLIS
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.SignableAnnouncement
import civictech.identity.Ed25519SignatureVerifier
import civictech.identity.IncarnationStore
import civictech.identity.announce.AnnouncementSigningInput
import civictech.identity.announce.canonicalBytes
import java.security.PublicKey

/**
 * The two halves `:kernel` deliberately cannot supply, composed here — this
 * file is the whole reason `computenet-ssa.4.4` exists in `:wire` rather than
 * in `:kernel`.
 *
 * `civictech.cell.wire.AnnouncementSigningConfig.encode` and
 * `civictech.cell.wire.AnnouncementVerification.verifier` are injected seams
 * because `:kernel` may hold neither the canonical announcement encoding nor a
 * cryptographic provider ([DSC1-WIRE-04]). `:wire` depends on `:identity`, so
 * `civictech.identity.announce.canonicalBytes` and
 * [Ed25519SignatureVerifier] are both in scope here, and this is where the
 * socket path binds them together.
 */

/**
 * The bytes an announcement commits to: the kernel's [SignableAnnouncement]
 * mapped field-for-field onto `:identity`'s record, then encoded canonically.
 *
 * One definition, used by both directions — the signer's `encode` and the
 * verifier's `canonicalBytes` — so "the bytes that were signed" can never
 * disagree across the socket.
 *
 * It **throws** for an announcement `canonicalBytes` refuses to encode
 * injectively (an ill-formed-UTF-16 `portName` or minting peer name,
 * `computenet-9qgg`). On the emit side that throw propagates rather than being
 * softened into an unsigned frame.
 *
 * On the receive side it is no longer reached for that input:
 * `civictech.cell.wire.AnnouncementAdmission` refuses an ill-formed
 * announcement as `civictech.cell.DenialReason.MALFORMED_ANNOUNCEMENT` before
 * the verifier is called (`computenet-l8y5`), which is what makes an
 * unencodable announcement distinguishable from a forged one in the audit
 * trail. The safety net stays: [Ed25519SignatureVerifier] is total, so if the
 * encoder's domain and the gate's mirror of it ever drift apart the throw is
 * still caught and still verifies `false` — a `BAD_SIGNATURE` refusal rather
 * than an exception out of the ingress path.
 */
fun announcementCanonicalBytes(announcement: SignableAnnouncement): ByteArray = canonicalBytes(
    AnnouncementSigningInput(
        mintingPeerId = announcement.mintingPeerId,
        counter = announcement.counter,
        notAfter = announcement.notAfter,
        contractId = announcement.contractId,
        methodId = announcement.methodId,
        cellRef = announcement.cellRef,
        portName = announcement.portName,
        args = announcement.args,
    ),
)

/**
 * The emit half for a socket side: `:identity`'s canonical encoding, with the
 * kernel's defaults for everything else.
 *
 * Signing still activates only when the side also holds credentials — see
 * `Peering.Side.announcementSigning`.
 *
 * @param incarnation which run of this signing identity's process the signer
 *   belongs to, read once at signer construction; see
 *   `civictech.cell.wire.AnnouncementSigner.counterFloor`. The default is the
 *   wall clock, which fails *closed* but can only observe monotonicity across
 *   a restart, never prove it. An operator who needs the proof — and whose
 *   identity has a directory to write into — passes
 *   [durableIncarnation] instead (`computenet-tdcx`).
 */
fun socketAnnouncementSigning(
    clock: () -> Long = System::currentTimeMillis,
    ttlMillis: Long = DEFAULT_ANNOUNCEMENT_TTL_MILLIS,
    signerKeyId: String? = null,
    incarnation: () -> Long = System::currentTimeMillis,
): AnnouncementSigningConfig = AnnouncementSigningConfig(
    encode = ::announcementCanonicalBytes,
    clock = clock,
    ttlMillis = ttlMillis,
    signerKeyId = signerKeyId,
    incarnation = incarnation,
)

/**
 * The incarnation source a durable [IncarnationStore] provides, in the shape
 * `socketAnnouncementSigning`'s `incarnation` parameter takes.
 *
 * The composition is the whole of `computenet-tdcx` on this side: the kernel's
 * seam is a `() -> Long` read once at construction (`computenet-ssa.6` shaped
 * it that way deliberately), so swapping the clock for a persisted, durably
 * bumped integer needs no kernel change at all. Store failures propagate as
 * `civictech.identity.KeyStoreRefusedException`; nothing here catches them and
 * nothing falls back to the clock, because an operator who configured a durable
 * source did so precisely because this machine's clock is not trustworthy.
 *
 * Pair it with a [civictech.identity.FilePeerIncarnationStore] over the same
 * directory the node's [civictech.identity.FilePeerKeyStore] uses, so the
 * incarnation lives beside the identity it belongs to:
 *
 * ```kotlin
 * socketAnnouncementSigning(
 *     incarnation = durableIncarnation(FilePeerIncarnationStore(keyDirectory)),
 * )
 * ```
 *
 * A **derived** identity (`DeterministicKeySource`, a seed phrase, an HSM- or
 * KMS-backed key) has no directory beside its key, so this covers a strict
 * subset of the identities the clock default covers. It is additive to that
 * default, not a replacement for it.
 */
fun durableIncarnation(store: IncarnationStore): () -> Long = store::nextIncarnation

/**
 * A verifier that knows **one** key: the one [peer] presented in the hello that
 * opened this connection ([DSC1-ANN-05]).
 *
 * This is the shape the socket path needs and the shape the loopback tests
 * cannot have. A directory-shaped verifier — one that resolves any known peer's
 * key — answers `true` for an announcement minted by B and injected on A's
 * connection, leaving the binding entirely to the gate's `ID_MISMATCH` check.
 * This one answers `false`, which is why it is the first thing in the tree that
 * makes the gate's *ordering* observable: `AnnouncementAdmission` decides
 * `ID_MISMATCH` before it verifies, so B-on-A's-connection reports
 * impersonation rather than collapsing into `BAD_SIGNATURE`.
 * `WsAnnouncementIdentityTest` pins that, and fails if the order is swapped.
 */
fun connectionBoundVerifier(peer: PeerId, key: PublicKey): AnnouncementVerifier =
    announcementVerifier { minting -> key.takeIf { minting == peer } }

/**
 * The general shape: verify under whatever key [publicKeys] resolves for the
 * *minting* peer, over [announcementCanonicalBytes].
 *
 * A directory resolver is the right shape for a peering that is not a socket —
 * a `Peering.loopback` has no hello, so the key a side must verify under comes
 * from the opposite side's configuration and is known before the peering
 * exists. On a socket, prefer [connectionBoundVerifier], which resolves exactly
 * one key and is what makes the gate's binding check observable.
 *
 * Total, because [Ed25519SignatureVerifier] is: an unknown peer, a non-Ed25519
 * key, an unencodable announcement and a malformed signature are all `false`.
 */
fun announcementVerifier(publicKeys: (PeerId) -> PublicKey?): AnnouncementVerifier {
    val seam = Ed25519SignatureVerifier(
        publicKeys = publicKeys,
        canonicalBytes = { _, _, payload -> announcementCanonicalBytes(payload as SignableAnnouncement) },
    )
    return AnnouncementVerifier { minting, counter, announcement, signature ->
        seam.verify(minting, counter, announcement, signature)
    }
}

/**
 * The receive half a socket side is configured with.
 *
 * Its [AnnouncementVerification.verifier] is a **fail-closed placeholder**: on a
 * socket, the key an announcement must verify under is not known until that
 * connection's hello has proved it, so every ingress `WsTransport` builds
 * rebinds this object with [connectionBoundVerifier] (see
 * `AnnouncementAdmission.withVerifier`, which keeps the side-scoped replay
 * ledger). The placeholder is only ever reached by an ingress built for a
 * connection that bound no key — a legacy, name-only hello at an `Open` side —
 * and refusing there is the honest answer: nothing proved that peer's identity,
 * so no signature of its can be checked.
 */
fun socketAnnouncementVerification(
    clock: () -> Long = System::currentTimeMillis,
    skewMillis: Long = DEFAULT_ANNOUNCEMENT_SKEW_MILLIS,
    /**
     * The keys this side can verify under **without** a hello to bind one —
     * the `Peering.loopback` shape, where the peer's key comes from the
     * opposite side's configuration rather than from a handshake. Defaults to
     * the fail-closed empty directory described above; every socket ingress
     * rebinds it anyway.
     */
    publicKeys: (PeerId) -> PublicKey? = { null },
): AnnouncementVerification = AnnouncementVerification(
    verifier = announcementVerifier(publicKeys),
    clock = clock,
    skewMillis = skewMillis,
    clockName = "the receiver's system clock",
)

/**
 * The implication `:kernel` left open, enforced where the canonical encoder is
 * in scope: **a `RequireAuthenticated` socket side must both sign and verify
 * announcements.**
 *
 * Both kernel halves activate on the presence of their injected config and not
 * on [PeerAuthPolicy], because the kernel can manufacture neither an encoder nor
 * a verifier ([DSC1-WIRE-04]) — so a `Peering.Side` that demands authenticated
 * peers while accepting *unsigned* announcements from them is representable
 * there. Note what does **not** carry that decision: a
 * `require(auth !is RequireAuthenticated || announcementVerification != null)`
 * is perfectly constructible in kernel. What sanctioned the fail-open for the
 * one task's width of window was that no production `RequireAuthenticated` side
 * existed anywhere — every construction site in the tree was a `:wire` test
 * fixture. This closes it: `:wire` is where an authenticating side is actually
 * built, and where both halves can be supplied.
 *
 * `Peering.Side`'s own `init` already requires credentials for a
 * `RequireAuthenticated` side, so the three conditions together mean such a
 * side genuinely signs (`announcementSigner != null`) and genuinely verifies
 * (`announcementAdmission != null`).
 */
internal fun requireAnnouncementIdentity(side: Peering.Side) {
    if (side.auth !is PeerAuthPolicy.RequireAuthenticated) return
    require(side.announcementSigning != null && side.announcementVerification != null) {
        "a PeerAuthPolicy.RequireAuthenticated side on a socket must sign AND verify announcements: " +
            "an authenticated peering whose registry announcements are unsigned lets any admitted peer " +
            "redirect traffic, and one that does not verify accepts them from anybody. Configure " +
            "announcementSigning = socketAnnouncementSigning() and " +
            "announcementVerification = socketAnnouncementVerification() " +
            "(announcementSigning=${side.announcementSigning != null}, " +
            "announcementVerification=${side.announcementVerification != null})"
    }
}
