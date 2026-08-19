package civictech.identity

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Derivation stability ([DSC1-KEY-02..03]): the [PeerId] is a pure, total
 * function of the public key, in one fixed textual form.
 */
class FingerprintTest {

    @Test
    fun `textual form is ed25519 prefixed unpadded base64url of the SHA-256 SPKI digest`() {
        val publicKey = Ed25519.generateKeyPair().public
        val peerId = fingerprint(publicKey)

        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val expected = "ed25519:" + Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, peerId.name)

        assertTrue(peerId.name.startsWith("ed25519:"), peerId.name)
        assertEquals(PEER_ID_PREFIX, "ed25519:")
        // 8 characters of prefix + 43 of unpadded base64url over a 32-byte digest.
        assertEquals(51, peerId.name.length, peerId.name)
        assertEquals(PEER_ID_LENGTH, peerId.name.length)

        val body = peerId.name.removePrefix(PEER_ID_PREFIX)
        assertEquals(43, body.length, body)
        assertTrue(body.none { it == '=' }, "base64url must be unpadded: $body")
        assertTrue(body.none { it == '+' || it == '/' }, "must be the URL alphabet, not standard base64: $body")
        assertTrue(body.all { it.isLetterOrDigit() || it == '-' || it == '_' }, body)
    }

    @Test
    fun `every generated key derives a name of the same fixed length`() {
        repeat(64) {
            assertEquals(PEER_ID_LENGTH, fingerprint(Ed25519.generateKeyPair().public).name.length)
        }
    }

    @Test
    fun `equal keys derive equal PeerIds and unequal keys derive unequal PeerIds`() {
        val a = Ed25519.generateKeyPair().public
        val b = Ed25519.generateKeyPair().public

        // Purity: the same instance, twice.
        assertEquals(fingerprint(a), fingerprint(a))
        // Equal keys reconstructed independently from their SPKI encoding — the
        // form a key takes when it comes back off disk or off the wire.
        val reconstructed = KeyFactory.getInstance(Ed25519.KEY_FACTORY)
            .generatePublic(X509EncodedKeySpec(a.encoded))
        assertNotSame(a, reconstructed, "sanity: a distinct object, not the same instance")
        assertEquals(fingerprint(a), fingerprint(reconstructed))

        assertNotEquals(fingerprint(a), fingerprint(b))
    }

    @Test
    fun `derivation is stable across processes because the seeded source reproduces the key`() {
        // A second JVM run cannot be observed from here; what makes the name
        // stable across runs is that it is a function of the SPKI bytes alone.
        // A seeded key gives a fixed expected value to pin that against.
        val seeded = DeterministicKeySource.keyPairFromSeed("fingerprint-stability".toByteArray()).public
        val expected = fingerprint(seeded)

        val again = DeterministicKeySource.keyPairFromSeed("fingerprint-stability".toByteArray()).public
        assertEquals(expected, fingerprint(again))
        assertEquals(expected.name, fingerprint(again).name)
    }

    @Test
    fun `a non-Ed25519 public key is rejected rather than fingerprinted`() {
        val ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair().public
        val failure = assertFailsWith<IllegalArgumentException> { fingerprint(ed448) }
        assertTrue("Ed25519" in failure.message.orEmpty(), failure.message.orEmpty())

        val rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().public
        assertFailsWith<IllegalArgumentException> { fingerprint(rsa) }
    }
}
