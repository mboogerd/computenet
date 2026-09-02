package civictech.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.InvalidKeySpecException

/**
 * Pins `Ed25519.publicKeyFromRaw`/`rawPublicKey` — the raw-bytes <-> PublicKey
 * conversion computenet-egl.3.1 adds so an iroh NodeId (32 raw Ed25519 public
 * key bytes) becomes a key `civictech.identity.fingerprint` accepts, and back.
 */
class Ed25519RawPublicKeyTest {

    @Test
    fun `round-trips through raw bytes preserving the key identifier and encoding`() {
        val kp = Ed25519.generateKeyPair()

        val raw = Ed25519.rawPublicKey(kp.public)
        val reconstructed = Ed25519.publicKeyFromRaw(raw)

        assertTrue(Ed25519.isEd25519(reconstructed), "reconstructed key should be recognized as Ed25519")
        assertEquals("X.509", reconstructed.format)
        assertEquals(fingerprint(kp.public), fingerprint(reconstructed))
        assertTrue(
            reconstructed.encoded.contentEquals(kp.public.encoded),
            "reconstructed key's SPKI encoding should equal the original's",
        )
    }

    @Test
    fun `rawPublicKey is exactly 32 bytes and is the tail of the SPKI encoding`() {
        val kp = Ed25519.generateKeyPair()

        val raw = Ed25519.rawPublicKey(kp.public)

        assertEquals(32, raw.size)
        val encoded = kp.public.encoded
        assertTrue(
            raw.contentEquals(encoded.copyOfRange(encoded.size - 32, encoded.size)),
            "raw bytes should equal the last 32 bytes of the SPKI encoding",
        )
    }

    @Test
    fun `publicKeyFromRaw refuses a length other than 32 and names the length`() {
        val tooShort = ByteArray(31)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Ed25519.publicKeyFromRaw(tooShort)
        }
        assertTrue(ex.message?.contains("31") == true, "message should name the offending length: ${ex.message}")

        val tooLong = ByteArray(33)
        val ex2 = assertThrows(IllegalArgumentException::class.java) {
            Ed25519.publicKeyFromRaw(tooLong)
        }
        assertTrue(ex2.message?.contains("33") == true, "message should name the offending length: ${ex2.message}")
    }

    /**
     * `y = 2`, little-endian, sign bit clear: a value strictly less than the
     * field prime `p = 2^255 - 19` (so it is not rejected merely for being
     * out of range), for which `x^2 = (y^2 - 1) / (d*y^2 + 1) mod p` is a
     * quadratic non-residue — no `x` exists, so no point on the Ed25519
     * curve has this `y` coordinate at all (computed offline; the JDK's own
     * `Signature.initVerify` on this key throws
     * `InvalidKeyException: Invalid point`, confirmed against this toolchain).
     */
    private val invalidEdwardsPoint: ByteArray = ByteArray(32).also { it[0] = 2 }

    @Test
    fun `publicKeyFromRaw refuses an invalid Edwards point encoding as IllegalArgumentException, not InvalidKeySpecException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Ed25519.publicKeyFromRaw(invalidEdwardsPoint)
        }
        assertFalse(ex is InvalidKeySpecException, "must surface as IllegalArgumentException, not the JDK's checked type")
    }

    @Test
    fun `publicKeyFromRaw's message does not echo the input bytes`() {
        val hexOfBytes = invalidEdwardsPoint.joinToString("") { "%02x".format(it) }

        val ex = assertThrows(IllegalArgumentException::class.java) {
            Ed25519.publicKeyFromRaw(invalidEdwardsPoint)
        }

        val message = ex.message ?: ""
        assertFalse(message.contains(hexOfBytes), "message should not echo the raw bytes: $message")
    }

    @Test
    fun `rawPublicKey refuses a non-Ed25519 key`() {
        val rsaKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        assertThrows(IllegalArgumentException::class.java) {
            Ed25519.rawPublicKey(rsaKeyPair.public)
        }
    }
}
