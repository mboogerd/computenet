package civictech.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sign/verify over raw bytes. The negative cases are the point: a verifier
 * that says `true` too often is a security defect, and one that *throws* on a
 * hostile input is a liveness defect at an ingress seam.
 */
class Ed25519SigningTest {

    private val keys = Ed25519.generateKeyPair()
    private val message = "the bytes that were signed".toByteArray()

    @Test
    fun `a signature verifies against its own key and message`() {
        val signature = Ed25519.sign(keys.private, message)
        assertEquals(Ed25519.SIGNATURE_LENGTH, signature.size)
        assertTrue(Ed25519.verify(keys.public, message, signature))
    }

    @Test
    fun `tampered bytes do not verify`() {
        val signature = Ed25519.sign(keys.private, message)

        val flipped = message.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(Ed25519.verify(keys.public, flipped, signature))

        val truncated = message.copyOf(message.size - 1)
        assertFalse(Ed25519.verify(keys.public, truncated, signature))

        val extended = message + 0x00
        assertFalse(Ed25519.verify(keys.public, extended, signature))

        assertFalse(Ed25519.verify(keys.public, ByteArray(0), signature))
    }

    @Test
    fun `a signature by a different key does not verify`() {
        val other = Ed25519.generateKeyPair()
        val signature = Ed25519.sign(other.private, message)
        assertFalse(Ed25519.verify(keys.public, message, signature))
        assertTrue(Ed25519.verify(other.public, message, signature), "sanity: it is a valid signature, by someone else")
    }

    @Test
    fun `a tampered signature does not verify`() {
        val signature = Ed25519.sign(keys.private, message)
        val flipped = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFalse(Ed25519.verify(keys.public, message, flipped))
    }

    @Test
    fun `a malformed signature is false, never an exception`() {
        // The JDK throws SignatureException("signature length invalid") for
        // these; a caller at an ingress seam must see `false`.
        assertFalse(Ed25519.verify(keys.public, message, ByteArray(0)))
        assertFalse(Ed25519.verify(keys.public, message, byteArrayOf(1, 2, 3)))
        assertFalse(Ed25519.verify(keys.public, message, ByteArray(Ed25519.SIGNATURE_LENGTH * 2)))
        assertFalse(Ed25519.verify(keys.public, message, ByteArray(Ed25519.SIGNATURE_LENGTH)))
    }

    @Test
    fun `PeerIdentity signs with the private half and verifies with the public one`() {
        val identity = PeerIdentity(keys)
        val signature = identity.sign(message)
        assertTrue(identity.verify(message, signature))
        assertFalse(identity.verify("something else".toByteArray(), signature))
        assertTrue(Ed25519.verify(keys.public, message, signature), "the same signature an external verifier sees")
    }

    @Test
    fun `empty messages are signable and verifiable`() {
        val signature = Ed25519.sign(keys.private, ByteArray(0))
        assertTrue(Ed25519.verify(keys.public, ByteArray(0), signature))
        assertFalse(Ed25519.verify(keys.public, byteArrayOf(0), signature))
    }
}
