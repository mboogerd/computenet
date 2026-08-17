package civictech.identity

import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import java.security.PublicKey

/**
 * An Ed25519 implementation of the kernel's existing
 * [SignatureVerifier] seam (spec 40/43 seam 3). This *replaces the verifier's
 * body, not the seam*, exactly as that interface's KDoc anticipates: no kernel
 * type, call site or wiring changes, and the kernel keeps no dependency on
 * this module.
 *
 * Two things are injected rather than decided here:
 *
 * @param publicKeys resolves the public key a peer is known by. `null` means
 *   "no key for this peer", which verifies as `false` — an unauthenticated
 *   peer is not an error at this seam, it is simply unverified.
 * @param canonicalBytes the deterministic encoding of
 *   `(mintingPeer, counter, payload)` that was signed. The canonical
 *   announcement encoding is a **sibling work item**; this adapter stays
 *   generic over it deliberately, so that exactly one definition of "the bytes
 *   that were signed" can ever exist.
 *
 * Total by construction: every failure — unknown peer, an encoder that throws,
 * a malformed signature, a mismatched key — is `false`. A verifier that can
 * throw turns a hostile input into a control-flow event at an ingress seam.
 */
class Ed25519SignatureVerifier(
    private val publicKeys: (PeerId) -> PublicKey?,
    private val canonicalBytes: (PeerId, Long, Any?) -> ByteArray,
) : SignatureVerifier {

    override fun verify(mintingPeer: PeerId, counter: Long, payload: Any?, signature: ByteArray): Boolean {
        val key = publicKeys(mintingPeer) ?: return false
        if (!Ed25519.isEd25519(key)) return false
        val signed = try {
            canonicalBytes(mintingPeer, counter, payload)
        } catch (_: RuntimeException) {
            return false
        }
        return Ed25519.verify(key, signed, signature)
    }

    override fun toString(): String = "Ed25519SignatureVerifier"
}
