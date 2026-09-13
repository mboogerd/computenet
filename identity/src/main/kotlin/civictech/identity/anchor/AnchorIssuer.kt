package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.identity.PeerIdentity
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A **dummy** anchor issuer: mints anchor-signed [IdentityStatement]s over a
 * [PeerIdentity] it wraps (epic `computenet-5y8t`, feature `computenet-5y8t.2`
 * rule 2, decisions D1/D4/D5).
 *
 * Per the 2026-08-29 amendment on `computenet-aimh` (`doc/distribution/findings.md`
 * 2026-09-12, residual R3): "the anchor ComputeNet operates for itself is
 * explicitly a dummy implementation standing in for" the intended long-run
 * institutional anchors — this class is that dummy, and the shape it proves is
 * the issuer boundary declared by feature `computenet-5y8t.1`. It does not
 * address, and this KDoc does not claim it addresses:
 *
 * - **R1** — anchor-key compromise is total; whoever holds this issuer's key
 *   can bind any name to any key.
 * - **R2** — this issuer's own key cannot rotate without the same rename
 *   problem one tier up; nothing here rotates it.
 * - **R4** — revocation is not built. This issuer mints a monotonically
 *   increasing per-name `issuance` (see the four-argument [bind] overload) but
 *   nothing here supersedes, tombstones, or otherwise treats an old statement
 *   as no longer authoritative — that is a relying-side (binding) concern, not
 *   this issuer's, and DSC4 does not build it either.
 *
 * `[DSC1-NV-01]` (stolen-key resistance) remains EXPLICITLY UNVERIFIED; no
 * test on this class may be named or read as covering it.
 *
 * Wraps a [PeerIdentity] rather than defining a second key-loading or
 * fingerprinting scheme (epic decision D3): construct one from
 * `civictech.identity.FilePeerKeyStore(anchorDir).loadOrGenerate()` for a
 * durable anchor identity, or directly from an in-memory keypair (e.g.
 * `civictech.identity.DeterministicKeySource.keyPairFromSeed(seed)`) for
 * tests. The private key stays reachable only through [PeerIdentity.sign]
 * ([DSC1-KEY-09]) — this class adds no accessor of its own.
 */
class AnchorIssuer(
    private val identity: PeerIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The anchor's public key. The private half is never exposed — see [PeerIdentity]. */
    val publicKey: PublicKey get() = identity.publicKey

    /**
     * This issuer's identity as it appears in every [IdentityStatement] it
     * mints: the anchor key's own fingerprint (epic decision D1/D3), reusing
     * `civictech.identity.fingerprint` — the one scheme in the repo — rather
     * than minting a second.
     */
    val issuerId: IssuerId = IssuerId(identity.keyId.name)

    /**
     * Per-name issuance counters for the four-argument [bind] overload
     * (decision D5). **Dummy behaviour, not protocol**: a real issuer's
     * `issuance` is durable issuer state; this in-memory counter resets with
     * the process and is shared by nothing outside this instance.
     */
    private val issuanceCounters = ConcurrentHashMap<PeerId, AtomicLong>()

    /**
     * Mints an [IdentityStatement] binding [name] to [keyId], signed by this
     * anchor's key over `statementSigningBytes` of the (unsigned) statement.
     *
     * `issuance` is accepted exactly as handed — it is issuer state (decision
     * D5), and this primitive compares nothing about it; the caller is
     * responsible for its meaning.
     *
     * @throws IllegalArgumentException if `notBefore > notAfter`.
     */
    fun bind(name: PeerId, keyId: KeyId, issuance: Long, notBefore: Long, notAfter: Long): IdentityStatement {
        require(notBefore <= notAfter) {
            "notBefore ($notBefore) must be <= notAfter ($notAfter)"
        }
        val unsigned = IdentityStatement(
            name = name,
            keyId = keyId,
            issuer = issuerId,
            issuance = issuance,
            notBefore = notBefore,
            notAfter = notAfter,
            signature = ByteArray(0),
        )
        val signature = identity.sign(statementSigningBytes(unsigned))
        return unsigned.copy(signature = signature)
    }

    /**
     * Convenience over the five-argument [bind]: draws `issuance` from an
     * in-memory per-`name` counter starting at 1 (**dummy behaviour, not
     * protocol** — see [issuanceCounters]) and defaults the validity window to
     * `[clock(), clock() + DEFAULT_VALIDITY_MILLIS]`.
     *
     * @throws IllegalArgumentException if `notBefore > notAfter`.
     */
    fun bind(
        name: PeerId,
        keyId: KeyId,
        notBefore: Long = clock(),
        notAfter: Long = notBefore + DEFAULT_VALIDITY_MILLIS,
    ): IdentityStatement {
        val issuance = issuanceCounters.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()
        return bind(name, keyId, issuance, notBefore, notAfter)
    }

    /** Names the issuer id and carries no private material — see [PeerIdentity.toString]. */
    override fun toString(): String = "AnchorIssuer(issuer=${issuerId.name})"

    companion object {
        /**
         * The default validity window width minted by the four-argument
         * [bind]: 30 days. **A per-deployment policy knob, not a protocol
         * constant, and an ESTIMATE, not a measurement** (epic decision D4).
         *
         * `doc/distribution/findings.md` (2026-09-12, "A validity window in
         * the statement") names the trade this knob makes: a validity window
         * is the only bound on staleness a peer can evaluate offline, and
         * that bound rests on `[DSC1-NV-03]` (clock-skew adequacy), which
         * stays EXPLICITLY UNVERIFIED. A shorter window buys lower revocation
         * latency at the cost of liveness depending on re-issuance *reaching*
         * every peer before its old statement expires — a reachability
         * requirement arriving by the back door. Nothing in this class (or
         * this task) enforces the window against a clock; that is the
         * relying-side binding's job (`computenet-5y8t.2.3`).
         */
        const val DEFAULT_VALIDITY_MILLIS: Long = 2_592_000_000L
    }
}
