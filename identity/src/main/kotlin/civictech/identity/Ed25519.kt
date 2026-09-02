package civictech.identity

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * The JDK-only Ed25519 primitives this module is built on (JEP 339, JDK 15+;
 * the toolchain here is 21). Everything comes from `java.security` — there is
 * deliberately no third-party crypto provider anywhere in `:identity`
 * ([DSC1-KEY-01], [DSC1-WIRE-04]).
 *
 * Two JDK naming quirks are load-bearing and easy to get wrong (both measured
 * on the toolchain, not inferred):
 *
 * - `PublicKey.getAlgorithm()` on a JDK Ed25519 key returns **`"EdDSA"`**, not
 *   `"Ed25519"`. The curve is only readable through [EdECKey.getParams], which
 *   is why [isEd25519] exists and why no code here compares `algorithm`.
 * - The [java.security.KeyFactory] name used to *parse* stored keys is
 *   [KEY_FACTORY] (`"EdDSA"`), which accepts Ed25519 **and Ed448**. Parsing
 *   with the wide factory and then rejecting the wrong curve is what makes an
 *   Ed448 key a distinguishable `UNSUPPORTED` refusal rather than an
 *   indistinguishable parse failure.
 */
object Ed25519 {
    /** Algorithm name for [KeyPairGenerator] and [Signature]. */
    const val ALGORITHM: String = "Ed25519"

    /**
     * [java.security.KeyFactory] name used to decode persisted key material.
     * Wider than [ALGORITHM] on purpose — see the class KDoc.
     */
    const val KEY_FACTORY: String = "EdDSA"

    /** Ed25519 signatures are always 64 bytes; asserted by tests, not relied on for control flow. */
    const val SIGNATURE_LENGTH: Int = 64

    /** An Ed25519 raw public key (a bare Edwards point, e.g. an iroh NodeId) is always 32 bytes. */
    const val RAW_PUBLIC_KEY_LENGTH: Int = 32

    /**
     * The fixed RFC 8410 §4 SubjectPublicKeyInfo prefix for an Ed25519 public
     * key: `SEQUENCE { SEQUENCE { OID id-Ed25519 }, BIT STRING (32 bytes) }`
     * with an empty bit-string unused-bits count, up to but not including the
     * 32 raw key bytes. A JDK Ed25519 [PublicKey.getEncoded] is exactly this
     * 12-byte prefix followed by the 32 raw bytes (44 bytes total) — verified
     * by [rawPublicKey]/[publicKeyFromRaw]'s round-trip test, not merely
     * assumed from the RFC.
     */
    private val ED25519_SPKI_PREFIX: ByteArray = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** A fresh keypair from the platform's [SecureRandom]. */
    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    /**
     * Wraps [raw] — 32 bytes of a bare Edwards point, such as an iroh NodeId —
     * in the RFC 8410 SubjectPublicKeyInfo envelope and parses it as a JDK
     * Ed25519 [PublicKey]. The result is accepted by [civictech.identity.fingerprint]
     * and satisfies `Ed25519.isEd25519(result)`.
     *
     * A JDK quirk this function works around: [KeyFactory.generatePublic] for
     * `"EdDSA"` does **not** validate the Edwards point at parse time — it
     * stores the 32 bytes as "unparsed keybits" and only decompresses (and
     * therefore validates) the point lazily, the first time the key is used
     * by a [Signature]. A construction-time contract can't rely on that, so
     * this function forces the decompression immediately by initializing a
     * throwaway verification [Signature] with the freshly-parsed key — the
     * cheapest JDK-only operation that reaches the point-decompression code
     * path — and treats the [InvalidKeyException] that surfaces for a
     * malformed point (e.g. `"y value is too large"`, `"Invalid point"`) as
     * this function's own refusal.
     *
     * Total over hostile input in the sense that matters at an admission seam:
     * every failure — wrong length, or 32 bytes that are not a valid Edwards
     * point encoding — surfaces as [IllegalArgumentException] (never the JDK's
     * checked [InvalidKeySpecException] or [InvalidKeyException]), so a caller
     * can catch one type and refuse rather than crash. The message never
     * echoes [raw]'s bytes; only the (public) length is named on a length
     * mismatch.
     *
     * @throws IllegalArgumentException if `raw.size != `[RAW_PUBLIC_KEY_LENGTH],
     *   naming the offending length, or if the 32 bytes are not a valid
     *   Ed25519 point encoding.
     */
    fun publicKeyFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == RAW_PUBLIC_KEY_LENGTH) {
            "raw Ed25519 public key must be $RAW_PUBLIC_KEY_LENGTH bytes, got ${raw.size}"
        }
        val spki = ED25519_SPKI_PREFIX + raw
        val key = try {
            KeyFactory.getInstance(KEY_FACTORY).generatePublic(X509EncodedKeySpec(spki))
        } catch (e: InvalidKeySpecException) {
            throw IllegalArgumentException("not a valid Ed25519 public key encoding", e)
        }
        try {
            Signature.getInstance(ALGORITHM).initVerify(key)
        } catch (e: InvalidKeyException) {
            throw IllegalArgumentException("not a valid Ed25519 public key point encoding", e)
        }
        return key
    }

    /**
     * The inverse of [publicKeyFromRaw]: the 32 raw Edwards-point bytes of an
     * Ed25519 [publicKey] — the trailing bytes of its X.509/SPKI encoding
     * after the fixed [ED25519_SPKI_PREFIX].
     *
     * @throws IllegalArgumentException if [publicKey] is not an Ed25519 key
     *   (see [isEd25519]).
     */
    fun rawPublicKey(publicKey: PublicKey): ByteArray {
        require(isEd25519(publicKey)) {
            "not an Ed25519 public key: algorithm=${publicKey.algorithm}, class=${publicKey.javaClass.name}"
        }
        val encoded = publicKey.encoded
        return encoded.copyOfRange(encoded.size - RAW_PUBLIC_KEY_LENGTH, encoded.size)
    }

    /** True when [key] is an Edwards key on curve Ed25519 (as opposed to Ed448, or a non-EdDSA key). */
    fun isEd25519(key: java.security.Key): Boolean =
        key is EdECKey && key.params.name == NamedParameterSpec.ED25519.name

    /** Ed25519 signature over the raw [message] bytes. */
    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }

    /**
     * Ed25519 verification over the raw [message] bytes.
     *
     * Total: a malformed signature (wrong length, garbage) makes the JDK throw
     * [java.security.SignatureException]; every such failure is a plain `false`
     * here. Verification is the one operation an attacker chooses the inputs
     * for, so it must not be able to raise anything at a caller's seam.
     */
    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        try {
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signature)
        } catch (_: GeneralSecurityException) {
            false
        }
}

/**
 * **TEST-ONLY** deterministic keypair source ([DSC1-KEY-08]).
 *
 * Reproducible keys are useful in tests (a fixed [PeerId] across runs) and
 * catastrophic in production (every node with the same seed is the same node).
 * The guard is structural rather than documentary: nothing in `:identity`
 * calls this, [FilePeerKeyStore] exposes no seam to inject it (its only
 * constructor takes the directory — asserted by a test), and there is no
 * configuration key that could route to it. Reaching it requires naming this
 * object at the call site, every time.
 */
object DeterministicKeySource {
    /**
     * The keypair determined by [seed]: equal seeds yield byte-identical
     * private and public encodings, unequal seeds yield different keys.
     */
    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.isNotEmpty()) { "seed must not be empty" }
        val generator = KeyPairGenerator.getInstance(Ed25519.ALGORITHM)
        generator.initialize(NamedParameterSpec.ED25519, SeededRandom(seed))
        return generator.generateKeyPair()
    }
}

/**
 * A deterministic [SecureRandom] — SHA-256 over `seed || counter`.
 *
 * Written out rather than using `SecureRandom.getInstance("SHA1PRNG")` +
 * `setSeed`, whose determinism is a SUN-provider implementation detail that
 * also depends on nothing having drawn from the instance first.
 */
private class SeededRandom(seed: ByteArray) : SecureRandom() {
    private val seed: ByteArray = seed.copyOf()
    private var counter: Long = 0

    @Synchronized
    override fun nextBytes(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(seed)
            digest.update(counterBytes())
            counter++
            val block = digest.digest()
            val take = minOf(block.size, bytes.size - offset)
            block.copyInto(bytes, offset, 0, take)
            offset += take
        }
    }

    private fun counterBytes(): ByteArray =
        ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }

    /** Never leaks the seed. */
    override fun toString(): String = "SeededRandom(deterministic, test-only)"
}
