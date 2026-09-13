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
 * The vocabulary split of feature `computenet-376c`: a [KeyId] is what a
 * hello is **proven** on, a [PeerId] is what attribution is stamped with, and
 * [PeerIdentityBinding] is the single named seam between them.
 *
 * Allowlists name **identities** (epic `computenet-5y8t`): [allowPeers] is
 * configured in [PeerId]s and compares the stamped identity by name, consulting
 * no binding — the identity was already resolved when the ingress stamped it.
 *
 * Task `computenet-hbqvz` made the seam partial ([IdentityResolution]); the
 * loopback case pins that a key resolving to [IdentityResolution.Unbound]
 * backs no name. That is the *shape* of the refusal arm only — nothing here
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
    fun `allowPeers keyed on a PeerId admits, rejects and passes on the stamped identity`() {
        val policy = allowPeers(PeerId("good"))

        assertNull(policy.evaluate(request(PeerId("good"))), "the allowlisted peer is admitted")

        val rejection = policy.evaluate(request(PeerId("evil")))
        assertNotNull(rejection, "a peer off the allowlist is rejected")
        assertEquals(
            "peer ${PeerId("evil")} is not on the allowlist (spec 43)",
            rejection.reason,
        )

        assertNull(policy.evaluate(request(null)), "a local request (null identity) passes")
    }

    /**
     * With no binding in play there is nothing to substitute: the verdict is a
     * comparison of names. `name-of-good` is exactly what a prefixing binding
     * would have resolved the key `good` to under the retired key-configured
     * allowlist; it is refused here because it is not the configured name.
     */
    @Test
    fun `allowPeers compares the stamped identity by name and consults no binding`() {
        val policy = allowPeers(PeerId("good"))

        assertNull(policy.evaluate(request(PeerId("good"))), "the configured identity is admitted")
        assertNotNull(
            policy.evaluate(request(PeerId("name-of-good"))),
            "an identity that is not the configured name is rejected, whatever key it was proven on",
        )
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
