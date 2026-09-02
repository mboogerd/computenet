package civictech.identity

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
 * the [PeerId] that key identifier resolves to.
 *
 * The private key is **not** a property: it is reachable only through [sign],
 * so no accessor, destructuring, copy or serializer can carry it out of here.
 * [toString] is redacted for the same reason ([DSC1-KEY-09]) — and so is the
 * absence of `equals`/`hashCode`, which would otherwise invite comparing
 * private material.
 */
class PeerIdentity(
    val publicKey: PublicKey,
    private val privateKey: PrivateKey,
) {
    constructor(keyPair: KeyPair) : this(keyPair.public, keyPair.private)

    /** This peer's key identifier — the fingerprint of [publicKey], see [fingerprint]. */
    val keyId: KeyId = fingerprint(publicKey)

    /**
     * This peer's durable identity, **resolved through the kernel's single
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
     */
    val peerId: PeerId = PeerIdentityBinding.Interim.identityOf(keyId)

    /** Ed25519 signature over [message] with the private half. */
    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)

    /** Ed25519 verification of [signature] over [message] against this peer's own public half. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(publicKey, message, signature)

    /** Redacted: the [peerId] is public by construction, the private key never appears. */
    override fun toString(): String = "PeerIdentity(peerId=${peerId.name}, privateKey=<redacted>)"
}
