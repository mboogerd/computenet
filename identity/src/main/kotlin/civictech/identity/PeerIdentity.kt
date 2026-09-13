package civictech.identity

import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * Scheme prefix of every key-derived name this module mints — the form a
 * [KeyId] takes, and (under
 * [PeerIdentityBinding.Interim][civictech.cell.link.PeerIdentityBinding.Companion.Interim])
 * therefore also the form of the [PeerId] that key identifier resolves to.
 */
const val PEER_ID_PREFIX: String = "ed25519:"

/**
 * Total length of a key-derived name: [PEER_ID_PREFIX] (8) plus the unpadded
 * base64url of a SHA-256 digest (32 bytes -> 43 characters). Describes the
 * key-derived form, i.e. a [KeyId] — and, under the interim binding, the
 * [PeerId] it resolves to as well.
 *
 * The constant keeps its `PEER_ID_` name deliberately: it is read by
 * `civictech.wire.isKeyDerivedPeerIdForm`, which checks a *claimed [PeerId]*
 * against this shape, and renaming it would be churn with no semantic gain
 * (feature `computenet-376c`).
 */
const val PEER_ID_LENGTH: Int = PEER_ID_PREFIX.length + 43

/**
 * The **key identifier** derived from a public key: `ed25519:` +
 * base64url-without-padding(SHA-256(SPKI)) ([DSC1-KEY-02..03]).
 *
 * A [KeyId], not a [PeerId] (feature `computenet-376c`): what a fingerprint
 * names is *which key*, which is the question boundary admission asks. The
 * durable identity of the peer that key belongs to is resolved from this
 * value through [PeerIdentityBinding], and nowhere else.
 *
 * Pure and total over Ed25519 public keys: the input is
 * [PublicKey.getEncoded], which for a JDK Ed25519 key is the X.509
 * SubjectPublicKeyInfo encoding — a canonical byte string that does not vary
 * between processes, providers or restarts. Equal keys therefore give equal
 * [KeyId]s and unequal keys give unequal ones (SHA-256 preimage/collision
 * resistance), with no state, clock or configuration involved.
 *
 * The bytes are unchanged by the type: the same string this function returned
 * as a [PeerId] before the split is the string it returns as a [KeyId] now,
 * and every hello frame that carries it is byte-identical.
 *
 * @throws IllegalArgumentException if [publicKey] is not an Ed25519 public key
 *   with an X.509 encoding. This is not a secrecy concern — the message names
 *   only public metadata (algorithm/curve/format).
 */
fun fingerprint(publicKey: PublicKey): KeyId {
    require(Ed25519.isEd25519(publicKey)) {
        "not an Ed25519 public key: algorithm=${publicKey.algorithm}, class=${publicKey.javaClass.name}"
    }
    val spki = publicKey.encoded
    requireNotNull(spki) { "public key has no encoded form (format=${publicKey.format})" }
    require(publicKey.format == "X.509") { "expected an X.509/SPKI public key encoding, got ${publicKey.format}" }
    val digest = MessageDigest.getInstance("SHA-256").digest(spki)
    return KeyId(PEER_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
}

/**
 * A loaded Ed25519 keypair, the [KeyId] its public half fingerprints to, and
 * the [PeerId] this process presents as its own.
 *
 * Two construction shapes, and they differ only in where [peerId] comes from:
 *
 * - **Unnamed** — `PeerIdentity(publicKey, privateKey)` / `PeerIdentity(keyPair)`:
 *   [peerId] is resolved from [keyId] through the kernel's interim binding and
 *   [statements] is empty. Every construction path that existed before feature
 *   `computenet-5y8t.3` is this one, unchanged.
 * - **Named** — `PeerIdentity(publicKey, privateKey, name, statements)` /
 *   `PeerIdentity(keyPair, name, statements)` (epic `computenet-5y8t` decision
 *   D9): [peerId] IS the explicit `name`, and [statements] are the
 *   anchor-signed [IdentityStatement]s this process holds as evidence for it.
 *
 * The private key is **not** a property: it is reachable only through [sign],
 * so no accessor, destructuring, copy or serializer can carry it out of here.
 * [toString] is redacted for the same reason ([DSC1-KEY-09]) — and so is the
 * absence of `equals`/`hashCode`, which would otherwise invite comparing
 * private material.
 */
class PeerIdentity private constructor(
    explicitName: PeerId?,
    statements: List<IdentityStatement>,
    val publicKey: PublicKey,
    private val privateKey: PrivateKey,
) {
    /** The unnamed identity: [peerId] resolved from [keyId], no [statements]. */
    constructor(publicKey: PublicKey, privateKey: PrivateKey) : this(null, emptyList(), publicKey, privateKey)

    /** The unnamed identity over [keyPair]; see the two-key constructor. */
    constructor(keyPair: KeyPair) : this(keyPair.public, keyPair.private)

    /**
     * The **named** identity: [peerId] is [name], and [statements] are the
     * anchor-signed statements this process presents for it (epic
     * `computenet-5y8t` decision D9, feature decision 5y8t.F3-D6).
     *
     * Fails closed at construction, with messages that name ids only (never
     * key bytes):
     * - [statements] must be non-empty — a name with no evidence is not a
     *   named identity;
     * - every statement's `keyId` must be this identity's [keyId] — a statement
     *   for another key beside this keypair is a misconfiguration (the
     *   `KEYPAIR_MISMATCH` precedent);
     * - every statement's `name` must be [name].
     *
     * **Signatures are NOT verified here.** This process need not hold any
     * issuer's public key; judging a statement is the relying side's job
     * (`civictech.identity.anchor.AnchorVouchedBinding`). Nor is `issuance`
     * compared with anything: it is carried, not interpreted — nothing here
     * supersedes, revokes or rotates, and `[DSC1-NV-01]` (stolen-key
     * resistance) remains EXPLICITLY UNVERIFIED.
     *
     * @throws IllegalArgumentException on any of the three conditions above.
     */
    constructor(
        publicKey: PublicKey,
        privateKey: PrivateKey,
        name: PeerId,
        statements: List<IdentityStatement>,
    ) : this(name, statements, publicKey, privateKey)

    /** The named identity over [keyPair]; see the four-argument constructor. */
    constructor(keyPair: KeyPair, name: PeerId, statements: List<IdentityStatement>) :
        this(keyPair.public, keyPair.private, name, statements)

    /** This peer's key identifier — the fingerprint of [publicKey], see [fingerprint]. */
    val keyId: KeyId = fingerprint(publicKey)

    /**
     * The anchor-signed statements this identity holds for [peerId] — empty on
     * every unnamed construction path, non-empty (and all naming [peerId] and
     * binding [keyId]) on the named one.
     *
     * **Reading a name out of a presented statement is not a derivation from
     * key material.** The name in a statement was chosen by its issuer and
     * signed over; nothing here computes it from [publicKey] or [keyId]. That
     * is the distinction `IdentityDerivationRatchetTest`'s rule (a) draws — it
     * baselines every production file that *constructs* a [PeerId], and this
     * file constructs none: the named path takes a [PeerId] as a parameter.
     *
     * Held, not judged: no signature, validity window or `issuance` is checked
     * here (see the named constructor).
     */
    val statements: List<IdentityStatement> = statements.toList()

    init {
        if (explicitName != null) {
            require(this.statements.isNotEmpty()) {
                "a named PeerIdentity (${explicitName.name}) needs at least one IdentityStatement"
            }
            for (statement in this.statements) {
                require(statement.keyId == keyId) {
                    "IdentityStatement for ${statement.name.name} binds key ${statement.keyId.name}, " +
                        "but this identity's key is ${keyId.name}"
                }
                require(statement.name == explicitName) {
                    "IdentityStatement binding key ${statement.keyId.name} names ${statement.name.name}, " +
                        "but this identity is named ${explicitName.name}"
                }
            }
        }
    }

    /**
     * This peer's durable identity.
     *
     * **On the named path it is the explicit name, verbatim** — not resolved
     * through any binding and not derived from anything.
     *
     * **On the unnamed path it is resolved through the kernel's single
     * [PeerIdentityBinding] seam — not a derivation** (feature
     * `computenet-376c`).
     *
     * Before the split this line read `fingerprint(publicKey)`, which made
     * `:identity` a second place an identity was derived from key material.
     * It now reads the one binding the kernel declares, so DSC4's
     * anchor-vouched names arrive by substituting that binding rather than by
     * editing here. Under
     * [PeerIdentityBinding.Interim][civictech.cell.link.PeerIdentityBinding.Companion.Interim]
     * the value is unchanged: the identity's name is the key identifier's own.
     *
     * The `Unbound` arm is handled, not assumed away (task
     * `computenet-hbqvz`): `Interim` is total by construction, so reaching it
     * is a broken invariant of the kernel's binding and fails loudly at
     * construction — never a name built from [keyId] here, which would be the
     * second derivation site the seam exists to prevent. This is not an
     * admission path; it is a process loading its own key.
     */
    val peerId: PeerId = explicitName
        ?: when (val resolution = PeerIdentityBinding.Interim.resolve(keyId, emptyList())) {
            is IdentityResolution.Bound -> resolution.peer
            is IdentityResolution.Unbound -> error(
                "PeerIdentityBinding.Interim resolved this key to no identity (${resolution.reason}); " +
                    "the interim binding is total, so this is a broken invariant, not a refusal",
            )
        }

    /**
     * This same keypair under the named constructor — the private half never
     * leaves the class. For [FilePeerKeyStore.loadNamed] and
     * [FilePeerKeyStore.storeStatements], which obtain the keypair as
     * [FilePeerKeyStore.loadOrGenerate] does and then attach statements.
     *
     * @throws IllegalArgumentException as the named constructor does.
     */
    internal fun named(name: PeerId, statements: List<IdentityStatement>): PeerIdentity =
        PeerIdentity(publicKey, privateKey, name, statements)

    /** Ed25519 signature over [message] with the private half. */
    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)

    /** Ed25519 verification of [signature] over [message] against this peer's own public half. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(publicKey, message, signature)

    /** Redacted: the [peerId] is public by construction, the private key never appears. */
    override fun toString(): String = "PeerIdentity(peerId=${peerId.name}, privateKey=<redacted>)"
}
