package civictech.cell.link

import civictech.cell.port.PortRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The vocabulary split of feature `computenet-376c`: a [KeyId] is what
 * boundary admission is configured in, a [PeerId] is what attribution is
 * stamped with, and [PeerIdentityBinding] is the single named seam between
 * them.
 *
 * The point of the last case is the one that is easy to lose: [allowPeers]
 * must go *through* the binding on every evaluation, not assume the interim
 * identity-is-the-key-name rule. Substituting a binding has to change the
 * verdict on an unchanged request.
 */
class PeerIdentityBindingTest {
    private fun request(identity: Identity?) =
        LinkRequest(from = PortRef.generate(), to = PortRef.generate(), identity = identity)

    @Test
    fun `the interim binding derives an identity of the key identifier's own name`() {
        assertEquals(PeerId("k"), PeerIdentityBinding.Interim.identityOf(KeyId("k")))
    }

    @Test
    fun `allowPeers keyed on a KeyId admits, rejects and passes exactly as it did on a PeerId`() {
        val policy = allowPeers(KeyId("good"))

        assertNull(policy.evaluate(request(PeerId("good"))), "the allowlisted peer is admitted")

        val rejection = policy.evaluate(request(PeerId("evil")))
        assertNotNull(rejection, "a peer off the allowlist is rejected")
        assertEquals(
            "peer ${PeerId("evil")} is not on the allowlist (spec 43)",
            rejection.reason,
        )

        assertNull(policy.evaluate(request(null)), "a local request (null identity) passes")
    }

    @Test
    fun `substituting the binding flips the verdict with no change to the request`() {
        val prefixing = PeerIdentityBinding { PeerId("name-of-" + it.name) }
        val request = request(PeerId("good"))

        // Same key on the allowlist, same request: only the binding differs.
        assertNull(allowPeers(KeyId("good")).evaluate(request))
        assertNotNull(allowPeers(KeyId("good"), binding = prefixing).evaluate(request))

        // ...and the key whose identity the substituted binding DOES resolve to
        // `good` is a different key entirely.
        assertNull(allowPeers(KeyId("good"), binding = prefixing).evaluate(request(PeerId("name-of-good"))))
    }
}
