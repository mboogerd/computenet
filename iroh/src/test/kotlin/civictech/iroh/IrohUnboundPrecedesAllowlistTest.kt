package civictech.iroh

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature `computenet-5y8t.4`, decision F4-D6: on the `:iroh` hello path the
 * binding's `Unbound` refusal fires **before** the allowlist is ever
 * consulted — even when the allowlist names EXACTLY the key-derived fallback
 * name (`PeerId(keyOf(remote).name)`) the presented key would otherwise
 * resolve to under `Interim`. If the allowlist were reached with that
 * fallback name, the link below would be admitted; instead it is refused
 * with the `Unbound` detail, never the allowlist's.
 *
 * Driven exactly as [IrohSessionHelloTest] drives `IrohTransport.Session` —
 * no sidecar, no iroh — so this class runs on the default `:iroh:test` lane
 * (it never calls `SidecarBinary.orSkip`). See that file's
 * `a link whose key the binding holds no identity for is refused...` test,
 * which this one strengthens with a non-null allowlist naming the exact
 * fallback the key derives to.
 *
 * This is test-only coverage of an order that already exists in
 * `IrohTransport.Session.onHello` (the `IdentityResolution.Unbound` branch
 * at the derive step, before `admitted`/`Side.admits` is ever called). The
 * bindings here are test stand-ins that verify nothing about a real identity
 * system; `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED, and nothing in this
 * file speaks to stolen-key resistance or revocation.
 */
class IrohUnboundPrecedesAllowlistTest {

    /** A fresh, valid 32-byte iroh NodeId, and the key identifier it derives — see [IrohSessionHelloTest]. */
    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

    private fun side(
        allow: Set<PeerId>? = null,
        binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), allow = allow, identityBinding = binding)
    }

    private fun hello(mirrorRef: UUID = UUID.randomUUID()): ByteArray =
        (IrohTransport.HELLO_PREFIX + mirrorRef).toByteArray(StandardCharsets.UTF_8)

    /**
     * A binding holding no identity for [unbound], and the interim answer for
     * every other key — the smallest partial binding, as in
     * [IrohSessionHelloTest]'s own unbound test.
     */
    private fun bindingWithoutIdentityFor(unbound: KeyId) = PeerIdentityBinding { key, presented ->
        if (key == unbound) IdentityResolution.Unbound(UnboundReason.NO_BINDING) else PeerIdentityBinding.Interim.resolve(key, presented)
    }

    @Test
    fun `a link is refused Unbound even when the allowlist names the key-derived fallback, and Interim admits it`() {
        val remote = nodeId()
        val allow = setOf(PeerId(keyOf(remote).name))

        // -- control: the same allowlist, under Interim, admits --------------
        val control = IrohTransport.Session(
            side(allow = allow),
            remote,
            send = { },
            refuse = { throw AssertionError("the interim binding must admit an allowlisted key") },
        )
        control.onData(hello())
        assertTrue(control.peered, "the interim binding, with this allowlist, admits the link")
        assertEquals(0L, control.admissionDenialCount)

        // -- the refusal: Unbound before the allowlist ------------------------
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(
            side(allow = allow, binding = bindingWithoutIdentityFor(keyOf(remote))),
            remote,
            send = { sent += it },
            refuse = { refusals++ },
        )

        session.onData(hello())

        assertEquals(1, refusals, "the link is closed")
        assertEquals(1L, session.admissionDenialCount)
        val denial = assertNotNull(session.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(null, denial.principal, "no identity means none to attribute the refusal to")
        val detail = assertNotNull(denial.detail)
        assertTrue(
            detail.contains("UnboundReason.${UnboundReason.NO_BINDING.name}"),
            "the detail carries the machine-readable reason, not the allowlist's: $detail",
        )
        assertFalse(detail.contains("allowlist"), "the allowlist detail must never appear: $detail")
        assertFalse(session.peered, "no ingress on a refused hello")
        assertEquals(null, session.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(sent.isEmpty(), "nothing is written to a refused link")
    }
}
