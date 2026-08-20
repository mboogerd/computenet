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
 * How many bits of a signed announcement's counter are the per-announcement
 * sequence, leaving the high bits for the signer's **incarnation**
 * ([AnnouncementSigningConfig.incarnation]) — the mechanism that carries
 * [DSC1-ANN-04]'s strict increase across a process restart (`computenet-ssa.6`).
 *
 * Twenty bits, i.e. 1,048,576 announcements per millisecond of incarnation
 * headroom. Two numbers bound the choice from either side, and both are
 * arithmetic rather than measurement:
 *
 * - *Below*: an incarnation's sequence must not run far enough to reach the
 *   floor a **later** incarnation would be given, or a restart would go
 *   backwards after all. With a millisecond-resolution incarnation, that needs
 *   more than 2^20 announcements per millisecond of uptime sustained for the
 *   whole run — a rate three orders of magnitude above what an announcement
 *   path can carry (announcements are per publish/unpublish/link/unlink, epic
 *   §9 risk 5), so the margin is not close.
 * - *Above*: `epochMillis shl 20` must stay a positive `Long`. It does until
 *   `(2^63 - 1) ushr 20` milliseconds — the year 2248. Past that
 *   [AnnouncementSigner] refuses at construction rather than wrapping into a
 *   negative counter, which the receiver's `counter <= seen` test would read as
 *   a permanent replay.
 */
const val ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT: Int = 20

/**
 * The counter floor an [AnnouncementSigningConfig.incarnation] value stands
 * for: the value below which that incarnation of a signer will never assign a
 * counter.
 *
 * `0` maps to `0`, so an injected `{ 0L }` reproduces the pre-`computenet-ssa.6`
 * sequence exactly (`1, 2, 3, ...`) — which is what the emit-side transcript
 * tests inject, since what they assert is the increment and not the floor.
 */
fun announcementCounterFloor(incarnation: Long): Long {
    require(incarnation >= 0 && incarnation <= (Long.MAX_VALUE ushr ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT)) {
        "announcement counter incarnation out of range: $incarnation does not fit " +
            "$ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT bits below Long.MAX_VALUE, so the floor it names " +
            "would wrap negative and every announcement this signer minted would classify as REPLAY " +
            "(valid range 0..${Long.MAX_VALUE ushr ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT})"
    }
    return incarnation shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT
}

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
 * @property incarnation which *run* of this signing identity's process this
 *   signer belongs to, read **once**, at construction. See
 *   [AnnouncementSigner.counterFloor] for what it buys and what it assumes.
 *   Injected rather than read directly for the same reason [clock] is — so a
 *   test can name two incarnations without waiting for a clock to tick — and
 *   held separate from [clock] because the two are read on different schedules
 *   (once per signer versus once per announcement) and an operator who later
 *   wants a *durable* incarnation source has somewhere to put it.
 */
data class AnnouncementSigningConfig(
    val encode: (SignableAnnouncement) -> ByteArray,
    val clock: () -> Long = System::currentTimeMillis,
    val ttlMillis: Long = DEFAULT_ANNOUNCEMENT_TTL_MILLIS,
    val signerKeyId: String? = null,
    val incarnation: () -> Long = System::currentTimeMillis,
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
 * **A `Side` is per process, though, and that is the other half.** The counter
 * is therefore seeded from the signer's [counterFloor] rather than from zero, so
 * that the sequence carries across a *process* restart the way it already
 * carried across a reconnect (`computenet-ssa.6`). The reasoning, the assumption
 * it rests on and the way it fails are on [counterFloor].
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
    /**
     * The value below which this signer will never assign a counter:
     * [announcementCounterFloor] of [AnnouncementSigningConfig.incarnation],
     * read once, here, and never again.
     *
     * **This is the whole of `computenet-ssa.6`'s fix, and it is a seed, not a
     * rule change.** [DSC1-ANN-04] requires each counter to be strictly greater
     * than every counter previously assigned *on that peering* — across the
     * peering's life, not across one process's. The pre-`ssa.6` signer met that
     * only within one process: a peer that restarted and re-minted the same
     * identity began again at `1` while its peer's high-water mark for that
     * identity was already past it, so its entire catch-up burst classified as
     * [civictech.cell.DenialReason.REPLAY], produced zero registry change, and
     * the peering never re-converged — silently from the sender's side, and
     * permanently, because nothing lowers a high-water mark.
     *
     * Seeding the counter from the incarnation makes a later incarnation's first
     * counter exceed an earlier incarnation's last, so the receiver accepts the
     * burst with **no change to the gate at all** — no reset, no window, no
     * exemption. That is the property that keeps BS-06 true: [sign] still does
     * one `incrementAndGet` per announcement, counters within a signer are still
     * strictly increasing, and a byte-identical redelivery still carries a
     * counter the receiver has already recorded, so it is still `<= seen` and
     * still REPLAY. Nothing about replay detection is relaxed; only the *floor*
     * a fresh signer starts from moves.
     *
     * ## What it assumes, and how it fails
     *
     * With the default [AnnouncementSigningConfig.incarnation] the ordering of
     * incarnations is the ordering of the signer's own wall clock across its
     * own restarts. That is **exactly** the loosely-synchronised-clock
     * assumption [DSC1-NV-03] already declares for expiry — this adds no new
     * assumption, and needs less than expiry does, since only one machine's
     * clock is compared with itself.
     *
     * A clock that steps **backwards** across a restart (an NTP correction, a
     * container with no battery-backed clock) yields a floor below the peer's
     * high-water mark, and the burst dead-letters as REPLAY — which is today's
     * behaviour exactly, not a new failure, and it fails *closed*. What it is
     * **not** is a durable counter: a signer whose incarnation source is the
     * clock cannot prove monotonicity, only observe it. An operator who needs
     * the proof injects a durable [AnnouncementSigningConfig.incarnation]
     * (a persisted, monotonically bumped integer alongside the key material);
     * the seam is deliberately shaped so that requires no change here.
     */
    val counterFloor: Long = announcementCounterFloor(config.incarnation())

    private val counter = AtomicLong(counterFloor)

    /** See [AnnouncementSigningConfig.signerKeyId]. */
    val signerKeyId: String = config.signerKeyId ?: credentials.peerId.name

    /** The last counter assigned; [counterFloor] before the first announcement. Test surface. */
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
