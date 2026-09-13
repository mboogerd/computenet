package civictech.cell.link

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.PortRef
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.Peering
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
 * The point of the substitution case is the one that is easy to lose:
 * [allowPeers] must go *through* the binding on every evaluation, not assume
 * the interim identity-is-the-key-name rule. Substituting a binding has to
 * change the verdict on an unchanged request.
 *
 * Task `computenet-hbqvz` made the seam partial ([IdentityResolution]); the
 * last case pins that a key resolving to [IdentityResolution.Unbound] vouches
 * for nobody. That is the *shape* of the refusal arm only — nothing here
 * models revocation, and nothing here says anything about a stolen key
 * (`[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED).
 */
class PeerIdentityBindingTest {
    private fun request(identity: Identity?) =
        LinkRequest(from = PortRef.generate(), to = PortRef.generate(), identity = identity)

    @Test
    fun `the interim binding resolves every key identifier to the identity of its own name`() {
        assertEquals(
            IdentityResolution.Bound(PeerId("k"), null, null),
            PeerIdentityBinding.Interim.resolve(KeyId("k"), emptyList()),
        )
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
        val prefixing = PeerIdentityBinding { key, _ -> IdentityResolution.Bound(PeerId("name-of-" + key.name), null, null) }
        val request = request(PeerId("good"))

        // Same key on the allowlist, same request: only the binding differs.
        assertNull(allowPeers(KeyId("good")).evaluate(request))
        assertNotNull(allowPeers(KeyId("good"), binding = prefixing).evaluate(request))

        // ...and the key whose identity the substituted binding DOES resolve to
        // `good` is a different key entirely.
        assertNull(allowPeers(KeyId("good"), binding = prefixing).evaluate(request(PeerId("name-of-good"))))
    }

    @Test
    fun `an allowlisted key the binding holds no identity for admits nobody, and the other keys still count`() {
        // A binding with no identity for `unbound`, and the interim answer for
        // every other key. Nothing may stand in for the missing identity — in
        // particular not the identity of the key's own name, `PeerId("unbound")`.
        val partial = PeerIdentityBinding { key, presented ->
            if (key == KeyId("unbound")) {
                IdentityResolution.Unbound(UnboundReason.NO_BINDING)
            } else {
                PeerIdentityBinding.Interim.resolve(key, presented)
            }
        }

        // The very request a PeerId(key.name) fallback would admit is refused.
        val onlyUnbound = allowPeers(KeyId("unbound"), binding = partial)
        assertNotNull(onlyUnbound.evaluate(request(PeerId("unbound"))), "an unbound key vouches for nobody")
        assertNull(onlyUnbound.evaluate(request(null)), "a local request still passes")

        // An unbound entry does not poison the list: the bound key still admits.
        val mixed = allowPeers(KeyId("unbound"), KeyId("good"), binding = partial)
        assertNull(mixed.evaluate(request(PeerId("good"))))
        assertNotNull(mixed.evaluate(request(PeerId("unbound"))))
    }

    /**
     * `Peering.loopbackAuthLevel` is the kernel's own consumer of the seam: a
     * sender is promoted only when its configured name is the identity its key
     * resolves to. A key with no identity backs no name, so the sender stays
     * `TransportVouched` — even though its configured name is exactly what a
     * `PeerId(key.name)` fallback would have produced.
     *
     * **The partial binding sits on the RECEIVER.** Task `computenet-hbqvz`
     * landed this case with the binding on the sender, because the loopback
     * then resolved a sender's key through the sender's own binding. Task
     * `computenet-5y8t.1.3` deliberately changed that (feature
     * `computenet-5y8t.1`, decision D9): the relying side resolves the
     * sender's presented key, as the socket's admitting side does, so the
     * binding that can refuse is the receiver's. The sender here is `Interim`.
     */
    @Test
    fun `a loopback sender whose key resolves to no identity is not promoted`() {
        val senderKey = KeyId("sender-key")
        val unboundForSender = PeerIdentityBinding { key, presented ->
            if (key == senderKey) {
                IdentityResolution.Unbound(UnboundReason.NO_BINDING)
            } else {
                PeerIdentityBinding.Interim.resolve(key, presented)
            }
        }

        fun side(key: KeyId, binding: PeerIdentityBinding): Peering.Side {
            val registry = LocationRegistry()
            return Peering.Side(
                registry,
                ManagedHost(registry = registry),
                peer = PeerId(key.name),
                credentials = credentials(key),
                identityBinding = binding,
            )
        }
        val sender = side(senderKey, PeerIdentityBinding.Interim)

        // Control: a receiver under the interim binding promotes the same
        // sender, so the verdict below is the receiver binding's refusal arm
        // and nothing else.
        assertEquals(
            AuthLevel.Authenticated,
            Peering.loopbackAuthLevel(sender, side(KeyId("receiver-key"), PeerIdentityBinding.Interim)),
        )
        assertEquals(
            AuthLevel.TransportVouched,
            Peering.loopbackAuthLevel(sender, side(KeyId("receiver-key"), unboundForSender)),
        )
    }

    private fun credentials(key: KeyId) = object : PeerCredentials {
        override val keyId: KeyId = key
        override val peerId: PeerId = PeerId(key.name)
        override val publicKey: ByteArray = ByteArray(0)
        override fun sign(message: ByteArray): ByteArray = ByteArray(0)
    }
}
