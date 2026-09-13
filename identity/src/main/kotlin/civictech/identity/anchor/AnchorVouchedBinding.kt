package civictech.identity.anchor

import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.wire.DEFAULT_ANNOUNCEMENT_SKEW_MILLIS
import civictech.identity.Ed25519
import java.security.PublicKey

/**
 * The **anchor-vouched** [PeerIdentityBinding] (epic `computenet-5y8t`,
 * feature `computenet-5y8t.2` rules 3-6, task `computenet-5y8t.2.3`): a key
 * resolves to the name an accepted issuer signed for it, or to a
 * machine-readable refusal.
 *
 * **The issuer boundary** (the 2026-08-29 amendment on `computenet-aimh`,
 * quoted on the epic): the relying process decides which issuers it accepts,
 * and hands them in as [acceptedIssuers]. More than one anchor may be
 * accepted; each resolution names the issuer that vouched
 * ([IdentityResolution.Bound.issuer]). **No anchor is privileged** — this
 * class holds no default issuer, no constant anchor key and no issuer-id
 * literal, and ComputeNet's own dummy [AnchorIssuer] is accepted only when
 * a caller puts it in the map, exactly like anyone else's (epic decision
 * 5y8t-D2).
 *
 * **Verification is OFFLINE and pure**: accepted issuers and [clock] arrive
 * by constructor, [resolve] reads only this instance's fields and its
 * arguments, and this file imports nothing that can reach a socket, a file
 * or a service. `AnchorVouchedBindingTest` checks the imports structurally;
 * that is a property of this class, not a network sandbox.
 *
 * **The chain**, per presented statement in presented order, stopping at the
 * first failure:
 * 1. its issuer is accepted, else [UnboundReason.ISSUER_NOT_ACCEPTED];
 * 2. its signature verifies under that issuer's key over
 *    [statementSigningBytes] (bytes that cannot be canonicalised — an
 *    ill-formed UTF-16 string — cannot have been signed), else
 *    [UnboundReason.BAD_SIGNATURE];
 * 3. it binds the resolved key, else [UnboundReason.KEY_MISMATCH];
 * 4. it is in its validity window at `now = clock()`:
 *    `now < notBefore` is [UnboundReason.NOT_YET_VALID] (strict — no
 *    allowance on the lower bound), `notAfter < now - skewMillis` is
 *    [UnboundReason.EXPIRED] (a lag-only allowance, the
 *    `civictech.cell.wire.AnnouncementAdmission` expiry shape; decision
 *    5y8t.2-D3).
 *
 * The first statement passing all four is [IdentityResolution.Bound] with
 * the presented instance itself as `statement`. When none passes, the reason
 * is that of the statement that got FURTHEST down the chain (ties: the
 * earliest presented) — the most specific failure, so a stray statement from
 * an unaccepted issuer never masks an expired one from an accepted issuer.
 * An empty list is [UnboundReason.NO_STATEMENT]; [UnboundReason.NO_BINDING]
 * is never produced.
 *
 * **Total**: [resolve] never throws, for any key and any list. Configuration
 * errors (a non-Ed25519 key, a negative skew) fail at construction instead.
 *
 * **The window rests on `[DSC1-NV-03]`** (clock-skew adequacy), which stays
 * EXPLICITLY UNVERIFIED: the window is evaluated against the RECEIVER's
 * clock, and nothing here shows [skewMillis] is adequate between real peers.
 *
 * **No supersession, no revocation** (epic residual R4, decision 5y8t-D11):
 * `issuance` is carried into [IdentityResolution.Bound.statement] and
 * compared with nothing, and this class keeps no memory between calls. Any
 * verifying in-window statement resolves, even after a higher-issuance one
 * was seen. `AnchorVouchedBindingTest` pins that absence on purpose, so a
 * green result is never read as revocation.
 *
 * **`[DSC1-NV-01]` (stolen-key resistance) remains EXPLICITLY UNVERIFIED**:
 * whoever holds a key an accepted issuer vouched for is that name as far as
 * this binding is concerned, and whoever holds an accepted issuer's key can
 * vouch any name (residual R1).
 */
class AnchorVouchedBinding(
    acceptedIssuers: Map<IssuerId, PublicKey>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMillis: Long = DEFAULT_ANNOUNCEMENT_SKEW_MILLIS,
) : PeerIdentityBinding {

    private val acceptedIssuers: Map<IssuerId, PublicKey> = acceptedIssuers.toMap()

    init {
        for ((issuer, key) in this.acceptedIssuers) {
            require(Ed25519.isEd25519(key)) {
                "accepted issuer ${issuer.name} has a non-Ed25519 key: algorithm=${key.algorithm}"
            }
        }
        require(skewMillis >= 0) { "skewMillis must be >= 0, was $skewMillis" }
    }

    override fun resolve(key: KeyId, presented: List<IdentityStatement>): IdentityResolution {
        if (presented.isEmpty()) return IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        val now = clock()
        var best: UnboundReason? = null
        for (statement in presented) {
            val reason = check(key, statement, now) ?: return IdentityResolution.Bound(
                peer = statement.name,
                issuer = statement.issuer,
                statement = statement,
            )
            if (best == null || depth(reason) > depth(best)) best = reason
        }
        return IdentityResolution.Unbound(best ?: UnboundReason.NO_STATEMENT)
    }

    /** The first failing step of the chain for [statement], or null when it passes all four. */
    private fun check(key: KeyId, statement: IdentityStatement, now: Long): UnboundReason? {
        val issuerKey = acceptedIssuers[statement.issuer] ?: return UnboundReason.ISSUER_NOT_ACCEPTED
        val verified = try {
            Ed25519.verify(issuerKey, statementSigningBytes(statement), statement.signature)
        } catch (_: RuntimeException) {
            false
        }
        if (!verified) return UnboundReason.BAD_SIGNATURE
        if (statement.keyId != key) return UnboundReason.KEY_MISMATCH
        if (now < statement.notBefore) return UnboundReason.NOT_YET_VALID
        if (statement.notAfter < saturatingMinus(now, skewMillis)) return UnboundReason.EXPIRED
        return null
    }

    override fun toString(): String =
        "AnchorVouchedBinding(acceptedIssuers=${acceptedIssuers.keys.map { it.name }})"

    private companion object {
        /** How far down the chain a failure got; a larger depth is the more specific reason. */
        fun depth(reason: UnboundReason): Int = when (reason) {
            UnboundReason.NO_BINDING, UnboundReason.NO_STATEMENT -> 0
            UnboundReason.ISSUER_NOT_ACCEPTED -> 1
            UnboundReason.BAD_SIGNATURE -> 2
            UnboundReason.KEY_MISMATCH -> 3
            UnboundReason.NOT_YET_VALID, UnboundReason.EXPIRED -> 4
        }

        /** `a - b` for `b >= 0`, clamped at [Long.MIN_VALUE] so an extreme clock cannot wrap into "expired". */
        fun saturatingMinus(a: Long, b: Long): Long =
            if (a < Long.MIN_VALUE + b) Long.MIN_VALUE else a - b
    }
}
