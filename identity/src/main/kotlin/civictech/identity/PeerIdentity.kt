package civictech.identity

import civictech.cell.link.PeerId
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/** Scheme prefix of every key-derived [PeerId] this module mints. */
const val PEER_ID_PREFIX: String = "ed25519:"

/**
 * Total length of a key-derived [PeerId] name: [PEER_ID_PREFIX] (8) plus the
 * unpadded base64url of a SHA-256 digest (32 bytes -> 43 characters).
 */
const val PEER_ID_LENGTH: Int = PEER_ID_PREFIX.length + 43

/**
 * The peer's identity *derived from* its public key: `ed25519:` +
 * base64url-without-padding(SHA-256(SPKI)) ([DSC1-KEY-02..03]).
 *
 * Pure and total over Ed25519 public keys: the input is
 * [PublicKey.getEncoded], which for a JDK Ed25519 key is the X.509
 * SubjectPublicKeyInfo encoding — a canonical byte string that does not vary
 * between processes, providers or restarts. Equal keys therefore give equal
 * [PeerId]s and unequal keys give unequal ones (SHA-256 preimage/collision
 * resistance), with no state, clock or configuration involved.
 *
 * The result is a plain [PeerId]; the kernel's type is unchanged. Today a peer
 * asserts its name and the transport vouches for it
 * ([civictech.cell.membrane.AuthLevel.TransportVouched]); a name in this form
 * is one a holder of the corresponding private key can prove.
 *
 * @throws IllegalArgumentException if [publicKey] is not an Ed25519 public key
 *   with an X.509 encoding. This is not a secrecy concern — the message names
 *   only public metadata (algorithm/curve/format).
 */
fun fingerprint(publicKey: PublicKey): PeerId {
    require(Ed25519.isEd25519(publicKey)) {
        "not an Ed25519 public key: algorithm=${publicKey.algorithm}, class=${publicKey.javaClass.name}"
    }
    val spki = publicKey.encoded
    requireNotNull(spki) { "public key has no encoded form (format=${publicKey.format})" }
    require(publicKey.format == "X.509") { "expected an X.509/SPKI public key encoding, got ${publicKey.format}" }
    val digest = MessageDigest.getInstance("SHA-256").digest(spki)
    return PeerId(PEER_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
}

/**
 * A loaded Ed25519 keypair and the [PeerId] its public half derives to.
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

    /** This peer's key-derived identity — see [fingerprint]. */
    val peerId: PeerId = fingerprint(publicKey)

    /** Ed25519 signature over [message] with the private half. */
    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)

    /** Ed25519 verification of [signature] over [message] against this peer's own public half. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(publicKey, message, signature)

    /** Redacted: the [peerId] is public by construction, the private key never appears. */
    override fun toString(): String = "PeerIdentity(peerId=${peerId.name}, privateKey=<redacted>)"
}
