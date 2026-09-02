package civictech.identity

import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import java.security.KeyPairGenerator
import java.security.PublicKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kernel seam ([SignatureVerifier], spec 40/43 seam 3) with an Ed25519
 * body. The kernel is untouched: this is an implementation of the interface it
 * already declares.
 *
 * The canonical announcement encoding is a sibling work item, so the encoder
 * here is injected — these tests pin the *adapter's* behaviour (resolution,
 * totality, which bytes get verified), not any particular encoding.
 */
class Ed25519SignatureVerifierTest {

    private val keys = Ed25519.generateKeyPair()

    // The verifier seam is IDENTITY-keyed (`verify(mintingPeer: PeerId, ...)`,
    // a deliberate DSC4 residual of feature computenet-376c), so the peer here
    // is the identity `PeerIdentity` resolves this key to — not
    // `fingerprint(keys.public)`, which is now a KeyId.
    private val peer = PeerIdentity(keys).peerId
    private val stranger = PeerId("ed25519:nobody-here")

    /** A stand-in for the sibling task's canonical encoding. */
    private val encode: (PeerId, Long, Any?) -> ByteArray = { id, counter, payload ->
        "${id.name}|$counter|$payload".toByteArray()
    }

    private val directory = mapOf(peer to keys.public)
    private val verifier: SignatureVerifier = Ed25519SignatureVerifier(directory::get, encode)

    @Test
    fun `a signature over the canonical bytes verifies`() {
        val signature = Ed25519.sign(keys.private, encode(peer, 7L, "delta"))
        assertTrue(verifier.verify(peer, 7L, "delta", signature))
    }

    @Test
    fun `an unknown peer is false, not an exception`() {
        val signature = Ed25519.sign(keys.private, encode(stranger, 7L, "delta"))
        assertFalse(verifier.verify(stranger, 7L, "delta", signature))
    }

    @Test
    fun `a changed counter or payload does not verify`() {
        val signature = Ed25519.sign(keys.private, encode(peer, 7L, "delta"))
        assertFalse(verifier.verify(peer, 8L, "delta", signature), "the counter is inside the signed bytes")
        assertFalse(verifier.verify(peer, 7L, "tampered", signature))
        assertFalse(verifier.verify(peer, 7L, null, signature))
    }

    @Test
    fun `a signature by another key does not verify`() {
        val impostor = Ed25519.generateKeyPair()
        val signature = Ed25519.sign(impostor.private, encode(peer, 7L, "delta"))
        assertFalse(verifier.verify(peer, 7L, "delta", signature))
    }

    @Test
    fun `a malformed signature is false, not an exception`() {
        assertFalse(verifier.verify(peer, 7L, "delta", ByteArray(0)))
        assertFalse(verifier.verify(peer, 7L, "delta", byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `a resolver that hands back a non-Ed25519 key is false, not an exception`() {
        val rsa: PublicKey = KeyPairGenerator.getInstance("RSA").generateKeyPair().public
        val wrong = Ed25519SignatureVerifier({ rsa }, encode)
        assertFalse(wrong.verify(peer, 1L, "delta", ByteArray(Ed25519.SIGNATURE_LENGTH)))
    }

    @Test
    fun `an encoder that throws is false, not an exception`() {
        val exploding = Ed25519SignatureVerifier(
            directory::get,
            { _, _, _ -> throw IllegalStateException("cannot encode this payload") },
        )
        assertFalse(exploding.verify(peer, 7L, "delta", ByteArray(Ed25519.SIGNATURE_LENGTH)))
    }

    @Test
    fun `the adapter verifies exactly the injected encoding, and nothing else`() {
        // Signing the payload's own bytes rather than the canonical encoding
        // must fail: the encoder decides what was signed.
        val signature = Ed25519.sign(keys.private, "delta".toByteArray())
        assertFalse(verifier.verify(peer, 7L, "delta", signature))

        val identityEncoder = Ed25519SignatureVerifier(directory::get, { _, _, payload -> payload.toString().toByteArray() })
        assertTrue(identityEncoder.verify(peer, 7L, "delta", signature))
    }
}
