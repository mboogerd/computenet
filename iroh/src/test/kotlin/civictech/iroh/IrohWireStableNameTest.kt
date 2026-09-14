package civictech.iroh

import civictech.cell.CellRef
import civictech.cell.control.Attention
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.AuthLevel
import civictech.cell.link.PeerId
import civictech.cell.membrane.Principal
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.identity.Ed25519
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import civictech.identity.fingerprint
import civictech.wire.WsTransport
import civictech.wire.asPeerCredentials
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.KeyPair
import java.security.interfaces.EdECPrivateKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Feature `computenet-5y8t.5` (DSC4 feature 5), task `computenet-5y8t.5.2`:
 * **one stable name across both transports, at one listener.** Epic
 * `computenet-egl.3`'s goal — "the same PeerId across transports" — is met only
 * if `alice` over `:iroh` and `alice` over `:wire` are ONE name to ONE listener;
 * [IrohKeyBoundAdmissionTest] (its anchor-statement case) shows `:iroh` alone
 * and `civictech.wire.WsAnchorVouchedHelloTest` shows `:wire` alone.
 *
 * ## What is shown
 *
 * - **One listener.** A single `Peering.Side` `lSide` — one `LocationRegistry`,
 *   one `allow = {PeerId("alice")}` entry, one `AnchorVouchedBinding` accepting
 *   exactly anchor `A` — is served at the same time by
 *   `IrohTransport.listen(lSide, …)` and `WsTransport.listen(0, lSide)`. The
 *   task's `unverified:` premise that one `Side` can serve both listeners at
 *   once is exercised here as written, not replaced by its two-`Side`
 *   fallback: if a second listener on the same `Side` broke either one, the
 *   stamp and location assertions below could not both hold.
 * - **Two transports, two keys, one name.** alice dials over `:iroh` on key
 *   `Ki` — the sidecar's NodeId key, seeded with `--secret-key` from the JVM
 *   keypair (checked, not assumed) — presenting `A.bind(alice, fingerprint(Ki),
 *   issuance = 1, …)`; and over `:wire` on a DIFFERENT key `Kw`, presenting
 *   `A.bind(alice, fingerprint(Kw), issuance = 2, …)`. The name, not the key,
 *   is the identity, so `fingerprint(Ki) != fingerprint(Kw)` is asserted: the
 *   test cannot pass by both dialers accidentally sharing a key.
 * - **What L records, read from L.** Each dialer drives one `PORT_PROTOCOL`
 *   attention delivery to a probe cell on L's host; the stamp is read from
 *   what that probe observed on L via `currentPrincipal()` — never from the
 *   dialer's own state — and must be
 *   `Principal.Peer(PeerId("alice"), AuthLevel.Authenticated, A.issuerId)` for
 *   BOTH. Each dialer also publishes a cell; the `Remote` location of each is
 *   read from L's registry and must carry `peer == PeerId("alice")`. Both
 *   listeners' `admissionDenialCount` is read and must be 0.
 *
 * ## The shape chosen: `Open` with credentials held, no announcement signing
 *
 * Every side here is `PeerAuthPolicy.Open` and holds credentials, and none
 * signs announcements — the shape `WsAnchorVouchedHelloTest`'s first socket
 * case pins. Credentials held is what makes `:wire` send `HELLO3` and answer a
 * challenge, and `:iroh` send `IROH-HELLO2`; the admission and the stamp are
 * therefore driven by the presented statements on both transports. The
 * signed-announcement form is not used because `IrohKeyBoundAdmissionTest`'s
 * iroh rig signs none, and with no announcement signing the name's replay
 * ledger at L is never engaged — so feature decision 5y8t.F5-D8 (explicit
 * incarnations, second `Side` built after the first announced) has nothing to
 * order here. L's own name `listener` is vouched by `A` on L's key, and L's
 * iroh sidecar is seeded with that key, so both dialers (also on
 * `AnchorVouchedBinding({A})`) admit L under the same name over both
 * transports.
 *
 * ## What is NOT shown
 *
 * Nothing here is revocation, supersession or stolen-key resistance. Both keys
 * are admitted because `A` vouched for each; neither statement displaces the
 * other, and a thief holding `Ki` or `Kw` with its statement is alice here as
 * everywhere. `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED.
 *
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar) this
 * test reports SKIPPED, never failed — see [SidecarBinary]. Every wait is
 * bounded ([await]).
 */
class IrohWireStableNameTest {

    private val alice = PeerId("alice")

    private fun seedArgs(keys: KeyPair): List<String> =
        listOf("--secret-key", (keys.private as EdECPrivateKey).bytes.orElseThrow().joinToString("") { "%02x".format(it) })

    /** One peering side over its own registry, plus a host on that registry for cells. */
    private class Stack(identity: PeerIdentity, binding: AnchorVouchedBinding, allow: Set<PeerId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            ManagedHost(registry = registry),
            allow = allow,
            auth = PeerAuthPolicy.Open,
            credentials = identity.asPeerCredentials(),
            identityBinding = binding,
        )
    }

    private fun protocolFrame(target: CellRef) = HostedPortInvocation(
        cellRef = target,
        portName = "outlet",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "outlet"),
        ),
        protocolMessage = Attention(1f),
    )

    private fun stderrSink(label: String): (String) -> Unit = { line -> println("[iroh-stderr $label] $line") }

    @Test
    fun `alice over iroh on one key and over wire on another reaches one anchor-bound listener as one PeerId`() {
        val binary = SidecarBinary.orSkip()

        val anchor = AnchorIssuer(PeerIdentity(Ed25519.generateKeyPair()))
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        fun binding() = AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey))
        fun vouched(keys: KeyPair, name: PeerId, issuance: Long) =
            PeerIdentity(keys, name, listOf(anchor.bind(name, fingerprint(keys.public), issuance, now - day, now + day)))

        val lKeys = Ed25519.generateKeyPair()
        val ki = Ed25519.generateKeyPair()
        val kw = Ed25519.generateKeyPair()
        assertNotEquals(
            fingerprint(ki.public),
            fingerprint(kw.public),
            "the two transports must carry DIFFERENT keys, or one name could be one key by accident",
        )

        val kiArgs = seedArgs(ki)
        val nodeIdI = SidecarProcess.spawn(binary, args = kiArgs).use { it.nodeId }
        assertTrue(
            nodeIdI.contentEquals(Ed25519.rawPublicKey(ki.public)),
            "the sidecar spawned with Ki's seed reports Ki's public half as its NodeId",
        )

        val l = Stack(vouched(lKeys, PeerId("listener"), 1), binding(), allow = setOf(alice))
        val overIroh = Stack(vouched(ki, alice, 1), binding())
        val overWire = Stack(vouched(kw, alice, 2), binding())

        val publishedOverIroh = SetCell<String>()
        overIroh.host.managementInlet.call.spawn(publishedOverIroh)
        val publishedOverWire = SetCell<String>()
        overWire.host.managementInlet.call.spawn(publishedOverWire)

        IrohTransport.listen(l.side, binary, stderrSink = stderrSink("listener"), sidecarArgs = seedArgs(lKeys))
            .use { irohListener ->
                assertTrue(
                    irohListener.nodeId.contentEquals(Ed25519.rawPublicKey(lKeys.public)),
                    "L's iroh NodeId is the key L's own statement vouches for",
                )
                // The SAME Side, a second listener, at the same time.
                val wsListener = WsTransport.listen(0, l.side)
                try {
                    val probe = IrohKeyBoundAdmissionTest.PrincipalProbeCell()
                    l.host.managementInlet.call.spawn(probe)

                    IrohTransport.connect(
                        overIroh.side,
                        irohListener.nodeId,
                        irohListener.addresses,
                        binary,
                        stderrSink = stderrSink("alice-iroh"),
                        sidecarArgs = kiArgs,
                    ).use { irohConnection ->
                        val wsConnection = WsTransport.connect(URI("ws://localhost:${wsListener.port}"), overWire.side) { 0L }
                        try {
                            await("both alice dialers learn L's probe, and L learns both alice cells") {
                                overIroh.registry.location(probe.ref) is LocationRegistry.Remote &&
                                    overWire.registry.location(probe.ref) is LocationRegistry.Remote &&
                                    l.registry.location(publishedOverIroh.ref) is LocationRegistry.Remote &&
                                    l.registry.location(publishedOverWire.ref) is LocationRegistry.Remote
                            }
                            assertTrue(irohConnection.peered, "alice over :iroh must be admitted")

                            val expected = Principal.Peer(alice, AuthLevel.Authenticated, anchor.issuerId)

                            overIroh.registry.deliver(protocolFrame(probe.ref))
                            await("alice's :iroh delivery was dispatched on L") { probe.principals.size >= 1 }
                            assertEquals(
                                expected,
                                probe.principals[0],
                                "L's probe stamps the :iroh delivery (key Ki) with the anchor-vouched name",
                            )

                            overWire.registry.deliver(protocolFrame(probe.ref))
                            await("alice's :wire delivery was dispatched on L") { probe.principals.size >= 2 }
                            assertEquals(
                                expected,
                                probe.principals[1],
                                "L's probe stamps the :wire delivery (key Kw) with the SAME anchor-vouched name",
                            )
                            assertEquals(2, probe.principals.size, "exactly one delivery per transport reached L")

                            assertEquals(
                                alice,
                                (l.registry.location(publishedOverIroh.ref) as LocationRegistry.Remote).peer,
                                "L's registry attributes the cell alice published over :iroh to PeerId(alice)",
                            )
                            assertEquals(
                                alice,
                                (l.registry.location(publishedOverWire.ref) as LocationRegistry.Remote).peer,
                                "L's registry attributes the cell alice published over :wire to PeerId(alice)",
                            )

                            assertEquals(0L, irohListener.admissionDenialCount, "the :iroh listener refused nobody")
                            assertEquals(0L, wsListener.admissionDenialCount, "the :wire listener refused nobody")
                            assertTrue(
                                irohListener.linkErrors.isEmpty(),
                                "sidecar reported link errors: ${irohListener.linkErrors}",
                            )
                        } finally {
                            wsConnection.shutdown()
                        }
                    }
                } finally {
                    runCatching { wsListener.stop(1000) }
                }
            }
    }
}
