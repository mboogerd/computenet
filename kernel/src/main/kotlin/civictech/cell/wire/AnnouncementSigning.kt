package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.link.PeerId
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything a signed location announcement commits to, **in kernel
 * vocabulary** ([DSC1-ANN-01..04], epic `computenet-ssa.4`).
 *
 * A deliberate second carrier. The canonical byte encoding lives in
 * `:identity` (`civictech.identity.announce.canonicalBytes`) over its own
 * `AnnouncementSigningInput`, and `:kernel` must not depend on `:identity` or
 * on any cryptographic provider ([DSC1-WIRE-04]). Every field here is already
 * kernel-visible — the identifying half comes straight off the invocation
 * envelope ([WireFrame.contractId], [WireFrame.methodId], [WireFrame.cellRef],
 * [WireFrame.portName], [WireFrame.args]) and the authenticity half is minted
 * by [AnnouncementSigner] — so the kernel can *describe* what is to be signed
 * without being able to encode or sign it. The mapping to `:identity`'s record
 * and the encoding itself arrive as [AnnouncementSigningConfig.encode], an
 * injected function, exactly the discipline
 * `civictech.identity.Ed25519SignatureVerifier(publicKeys, canonicalBytes)`
 * already applies in the other direction.
 *
 * [notAfter] is epoch milliseconds. What a receiver does with it — skew
 * tolerance, clock source — is the ingress task's policy, not this type's.
 */
data class SignableAnnouncement(
    val mintingPeerId: PeerId,
    val counter: Long,
    val notAfter: Long,
    val contractId: Long,
    val methodId: Long,
    val cellRef: CellRef,
    val portName: String,
    val args: List<Any?>,
)

/** The four [WireFrame] fields one signing event produces. */
data class AnnouncementSignature(
    /** Base64url, unpadded — the representation [WireFrame.signature] pins. */
    val signature: String,
    val signerKeyId: String,
    val counter: Long,
    val notAfter: Long,
)

/**
 * How long a freshly minted announcement stays valid, in milliseconds.
 *
 * Five minutes: long enough that an announcement survives a scheduler stall,
 * a GC pause or a slow reconnect handshake without the receiver having to
 * make an exception for it, and short enough that a captured frame is not
 * indefinitely replayable at a receiver that has lost its counter state.
 * It is a **default**, not a bound anything is proven against — override it
 * through [AnnouncementSigningConfig.ttlMillis] where an operator knows
 * better. Clock-skew adequacy itself is an operational assumption
 * ([DSC1-NV-03]), not a property this constant establishes.
 */
const val DEFAULT_ANNOUNCEMENT_TTL_MILLIS: Long = 5 * 60 * 1000L

/**
 * The injection surface: everything a [Peering.Side] needs in order to sign
 * its announcements that the kernel cannot supply itself.
 *
 * One config object rather than four parameters on [Peering.Side] so that the
 * two production compositions — `Peering.loopback` here and `WsTransport` in
 * `:wire` (task 4) — hand over the same thing, and so that "this side signs"
 * is a single nullable field rather than a combination to be re-derived at
 * every call site.
 *
 * @property encode the canonical announcement encoding, ultimately
 *   `civictech.identity.announce.canonicalBytes` composed with the mapping
 *   from [SignableAnnouncement] to its `AnnouncementSigningInput`. Injected
 *   because `:kernel` may not import it ([DSC1-WIRE-04]). It is permitted —
 *   required, in fact — to *reject* an announcement it cannot encode
 *   injectively; the throw propagates out of the egress rather than being
 *   softened into an unsigned frame.
 * @property clock the source of "now" in epoch millis. Injected so a test
 *   fixes expiry without sleeping.
 * @property ttlMillis [notAfter] is `clock() + ttlMillis`; see
 *   [DEFAULT_ANNOUNCEMENT_TTL_MILLIS].
 * @property signerKeyId names the signing key among the signer's published
 *   keys ([DSC1-WIRE-01]). Defaults to the credentials' own
 *   [PeerCredentials.peerId] name, which *is* the key's fingerprint on every
 *   `:identity`-backed implementation — so the default is already a key id,
 *   not a peer name that happens to be nearby.
 */
data class AnnouncementSigningConfig(
    val encode: (SignableAnnouncement) -> ByteArray,
    val clock: () -> Long = System::currentTimeMillis,
    val ttlMillis: Long = DEFAULT_ANNOUNCEMENT_TTL_MILLIS,
    val signerKeyId: String? = null,
)

/**
 * Signs one peer identity's announcements, and holds that identity's counter.
 *
 * **Where this object lives is the whole point.** The counter is strictly
 * increasing per *peer identity*, not per egress cell ([DSC1-ANN-04], epic
 * §9.3): announcements survive reconnects, and a reconnect mints a fresh
 * `BridgeEgressCell` (`WsTransport.Session`) or a fresh mirror pair
 * (`Peering.Loopback.heal`). So the signer is constructed once, by
 * [Peering.Side], and every egress that side ever has borrows it. Putting the
 * counter on the egress instead would restart the sequence on reconnect, and
 * the receiver's per-identity high-water mark would then reject the whole
 * catch-up burst as replay.
 *
 * **Sign at send, never cache.** [sign] is called once per outgoing
 * announcement and always assigns the next counter, so a catch-up
 * re-announcement after a reconnect is a *new* signing event rather than a
 * re-sent frame ([DSC1-ANN-12]). That is what lets the receiver accept the
 * burst with no exemption logic while a byte-identical redelivery still
 * classifies as replay.
 */
class AnnouncementSigner internal constructor(
    val credentials: PeerCredentials,
    private val config: AnnouncementSigningConfig,
) {
    private val counter = AtomicLong(0)

    /** See [AnnouncementSigningConfig.signerKeyId]. */
    val signerKeyId: String = config.signerKeyId ?: credentials.peerId.name

    /** The last counter assigned; `0` before the first announcement. Test surface. */
    val lastCounter: Long get() = counter.get()

    /**
     * Assign the next counter and sign. Never returns a cached result and
     * never reuses a counter: two calls with identical arguments produce two
     * distinct signatures over two distinct signed regions.
     */
    fun sign(
        contractId: Long,
        methodId: Long,
        cellRef: CellRef,
        portName: String,
        args: List<Any?>,
    ): AnnouncementSignature {
        val next = counter.incrementAndGet()
        val notAfter = config.clock() + config.ttlMillis
        val bytes = config.encode(
            SignableAnnouncement(
                mintingPeerId = credentials.peerId,
                counter = next,
                notAfter = notAfter,
                contractId = contractId,
                methodId = methodId,
                cellRef = cellRef,
                portName = portName,
                args = args,
            ),
        )
        return AnnouncementSignature(
            signature = BASE64URL.encodeToString(credentials.sign(bytes)),
            signerKeyId = signerKeyId,
            counter = next,
            notAfter = notAfter,
        )
    }

    private companion object {
        val BASE64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
