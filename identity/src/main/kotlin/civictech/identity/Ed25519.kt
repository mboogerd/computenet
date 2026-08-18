package civictech.identity

import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECKey
import java.security.spec.NamedParameterSpec

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

    /** A fresh keypair from the platform's [SecureRandom]. */
    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

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
