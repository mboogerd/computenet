package civictech.iroh

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.Principal
import civictech.cell.membrane.currentPrincipal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.link.IdentityStatement
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireEdgeLink
import civictech.identity.DeterministicKeySource
import civictech.identity.Ed25519
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import civictech.identity.anchor.decodeIdentityStatementToken
import civictech.identity.anchor.encodeIdentityStatementToken
import civictech.identity.fingerprint
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hello state machine of [IrohTransport.Session], driven directly — no
 * sidecar, no iroh, so this runs on the DEFAULT lanes (it never calls
 * [SidecarBinary.orSkip]).
 *
 * That is deliberate rather than a shortcut: the drop path this pins is
 * reachable only from a peer that sends more frames after a hello this side
 * refused, which over two real sidecars is a race against the `CLOSE_LINK` the
 * refusal issues. Driving the Session directly makes it a fact instead of a
 * timing accident. `IrohPeeringTest` covers the same state machine over real
 * links.
 *
 * Since feature `computenet-egl.3` a Session is constructed with the remote
 * NodeId of its link, and the admission key is derived from *that* — so these
 * tests mint real ed25519 keypairs and hand their raw public halves in as
 * NodeIds ([nodeId]). A hello token is never an admission token here; the only
 * thing it can do is disagree.
 */
class IrohSessionHelloTest {

    /** A fresh, valid 32-byte iroh NodeId, and the key identifier it derives. */
    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

    private fun side(
        name: String? = null,
        allow: Set<PeerId>? = null,
        binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(
            registry,
            ManagedHost(registry = registry),
            peer = name?.let { PeerId(it) },
            allow = allow,
            identityBinding = binding,
        )
    }

    private fun hello(mirrorRef: UUID = UUID.randomUUID(), name: String? = null): ByteArray =
        (IrohTransport.HELLO_PREFIX + mirrorRef + (name?.let { " $it" } ?: "")).toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `frames after a refused hello are dropped and counted, never routed`() {
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(side(), nodeId(), send = { sent += it }, refuse = { refusals++ })

        assertEquals(0L, session.preHelloDrops)

        // The first frame on a link IS the hello (positional grammar); this one
        // is not one, so it is refused and the link is closed.
        session.onData("nonsense".toByteArray(StandardCharsets.UTF_8))
        assertEquals(1, refusals)
        assertEquals(1L, session.admissionDenialCount)
        assertEquals(DenialReason.MALFORMED_HELLO, assertNotNull(session.lastAdmissionDenial).reason)
        assertFalse(session.peered, "a refused hello must install no ingress")
        assertEquals(0L, session.preHelloDrops, "the refused hello itself is not a drop")

        // Anything the peer wrote before the close lands here: nowhere to route,
        // so dropped — and now counted.
        session.onData(byteArrayOf(1, 2, 3))
        assertEquals(1L, session.preHelloDrops)
        session.onData(byteArrayOf(4, 5, 6))
        assertEquals(2L, session.preHelloDrops)

        assertTrue(sent.isEmpty(), "a refused link must never be written to")
    }

    @Test
    fun `an admitted hello mints a mirror, answers with a nameless hello, and installs the ingress`() {
        val sent = mutableListOf<ByteArray>()
        val remote = nodeId()
        val local = side(name = "local")
        val session = IrohTransport.Session(local, remote, send = { sent += it }, refuse = { })

        // No token at all: the connection's NodeId is the whole admission story.
        session.onData(hello())

        assertTrue(session.peered, "an admitted hello installs the ingress")
        assertEquals(0L, session.preHelloDrops)
        assertEquals(0L, session.admissionDenialCount)
        // The identity stamped on the mirror is the binding's, resolved from the
        // NodeId-derived key — not the hello, which named nobody.
        assertEquals(
            local.identityBinding.boundPeer(keyOf(remote)),
            assertNotNull(session.mirrorCell).peer,
            "the mirror carries the identity this side's binding resolved for the link's key",
        )
        // The hello is our FIRST frame and our only one: everything after it is
        // the announcement catch-up `bindAndAnnounce` starts, which is ordinary
        // wire traffic (the bridge host's own mirror and ingress cells are local
        // refs, so `announceTo`'s sweep names them).
        val answer = String(sent.first(), StandardCharsets.UTF_8)
        assertTrue(answer.startsWith(IrohTransport.HELLO_PREFIX), "our first frame is a hello: $answer")
        assertEquals(
            1,
            sent.count { String(it, StandardCharsets.UTF_8).startsWith(IrohTransport.HELLO_PREFIX) },
            "exactly one hello is ever written to a link",
        )
        assertEquals(
            IrohTransport.HELLO_PREFIX + assertNotNull(session.mirrorRef).id,
            answer,
            "our hello is exactly the prefix and this link instance's mirror ref — no name token, " +
                "even though this side's `peer` is set",
        )
    }

    @Test
    fun `admission is decided on the link's NodeId, and an allowlisted name cannot be asserted onto it`() {
        val goodNodeId = nodeId()
        val goodKey = keyOf(goodNodeId)
        val allow = setOf(PeerId(goodKey.name))

        // ---- the holder of the allowlisted key is admitted -----------------
        val admitted = IrohTransport.Session(
            side(name = "server", allow = allow),
            goodNodeId,
            send = { },
            refuse = { throw AssertionError("the holder of an allowlisted key must not be refused") },
        )
        admitted.onData(hello())
        assertTrue(admitted.peered)
        assertEquals(0L, admitted.admissionDenialCount)

        // ---- a different key is refused, whatever it calls itself ----------
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val malloryNodeId = nodeId()
        val malloryServerSide = side(name = "server", allow = allow)
        val mallory = IrohTransport.Session(
            malloryServerSide,
            malloryNodeId,
            send = { sent += it },
            refuse = { refusals++ },
        )

        mallory.onData(hello())

        assertEquals(1, refusals, "the link is closed")
        assertEquals(1L, mallory.admissionDenialCount)
        val denial = assertNotNull(mallory.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(
            malloryServerSide.identityBinding.boundPeer(keyOf(malloryNodeId)),
            denial.principal,
            "the refusal is attributed to the identity of the key that actually dialled",
        )
        assertFalse(mallory.peered, "no ingress on a refused hello")
        assertEquals(null, mallory.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(sent.isEmpty(), "nothing is written to a refused link — not even our hello")

        // ---- and asserting the allowlisted NAME buys nothing ---------------
        // The self-assertion defect, closed: this is the same unlisted key, now
        // claiming to be the admitted peer. It is refused BEFORE the allowlist
        // ever sees it, on the disagreement itself.
        val forgedSent = mutableListOf<ByteArray>()
        var forgedRefusals = 0
        val forgingSide = side(name = "server", allow = allow)
        val forgingNodeId = nodeId()
        val forging = IrohTransport.Session(
            forgingSide,
            forgingNodeId,
            send = { forgedSent += it },
            refuse = { forgedRefusals++ },
        )

        forging.onData(hello(name = goodKey.name))

        assertEquals(1, forgedRefusals)
        val forgedDenial = assertNotNull(forging.lastAdmissionDenial)
        assertEquals(
            DenialReason.ID_MISMATCH,
            forgedDenial.reason,
            "a token that disagrees with the connection's own key is a mismatch, not an admission",
        )
        assertEquals(
            forgingSide.identityBinding.boundPeer(keyOf(forgingNodeId)),
            forgedDenial.principal,
            "the denial names who was actually on the link, not who they claimed to be",
        )
        assertTrue(
            assertNotNull(forgedDenial.detail).contains(goodKey.name),
            "the detail records the claim: ${forgedDenial.detail}",
        )
        assertFalse(forging.peered)
        assertEquals(null, forging.mirrorRef)
        assertTrue(forgedSent.isEmpty())
    }

    @Test
    fun `a token equal to the resolved identity is redundant and admitted`() {
        val remote = nodeId()
        val local = side(name = "local")
        val resolved = local.identityBinding.boundPeer(keyOf(remote))
        val session = IrohTransport.Session(
            local,
            remote,
            send = { },
            refuse = { throw AssertionError("a confirming token must not be refused") },
        )

        session.onData(hello(name = resolved.name))

        assertTrue(session.peered, "a token that agrees with the derivation confirms it")
        assertEquals(0L, session.admissionDenialCount)
    }

    @Test
    fun `the stamped and denied identity comes from the side's binding, never from the key itself`() {
        val aliasing = PeerIdentityBinding { key, _ -> IdentityResolution.Bound(PeerId("alias-of-" + key.name), null, null) }
        val remote = nodeId()
        val expected = PeerId("alias-of-" + keyOf(remote).name)

        // Admitted path: the ALIAS is what the mirror carries. A site that wrote
        // `PeerId(key.name)` — or fingerprinted its way to an identity — stamps
        // the key identifier here instead, and this fails.
        val open = IrohTransport.Session(
            side(name = "local", binding = aliasing),
            remote,
            send = { },
            refuse = { throw AssertionError("an open side must admit a valid key") },
        )
        open.onData(hello())
        assertTrue(open.peered)
        assertEquals(expected, assertNotNull(open.mirrorCell).peer)

        // Refused path: the same alias is what the denial is attributed to.
        var refusals = 0
        val closed = IrohTransport.Session(
            side(name = "local", allow = setOf(PeerId("nobody")), binding = aliasing),
            remote,
            send = { },
            refuse = { refusals++ },
        )
        closed.onData(hello())
        assertEquals(1, refusals)
        val denial = assertNotNull(closed.lastAdmissionDenial)
        assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
        assertEquals(expected, denial.principal)

        // Mismatch path: the alias is what a claimed token is compared against,
        // so the key identifier's own name is now a FOREIGN claim.
        var mismatchRefusals = 0
        val mismatching = IrohTransport.Session(
            side(name = "local", binding = aliasing),
            remote,
            send = { },
            refuse = { mismatchRefusals++ },
        )
        mismatching.onData(hello(name = keyOf(remote).name))
        assertEquals(1, mismatchRefusals)
        assertEquals(DenialReason.ID_MISMATCH, assertNotNull(mismatching.lastAdmissionDenial).reason)
    }

    /**
     * Task `computenet-hbqvz`, this transport's admission path: a link whose
     * NodeId-derived key the side's binding resolves to **no identity** is
     * refused with a typed reason (`DenialReason.UNVOUCHED` since feature
     * `computenet-5y8t.3`) and the machine-readable `UnboundReason`,
     * attributed to no principal, costs no mirror and is never written to —
     * even on an open side, and even when the hello asserts the very name a
     * `PeerId(key.name)` fallback would have produced.
     *
     * New coverage of a verdict the interim binding never produces; the control
     * half is the same link under `PeerIdentityBinding.Interim`, admitted. What
     * is refused is a key the binding does not bind — nothing here speaks to a
     * stolen key (`[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED).
     */
    @Test
    fun `a link whose key the binding holds no identity for is refused, and nothing stands in for the identity`() {
        val remote = nodeId()
        val unbound = PeerIdentityBinding { key, presented ->
            if (key == keyOf(remote)) {
                IdentityResolution.Unbound(UnboundReason.NO_BINDING)
            } else {
                PeerIdentityBinding.Interim.resolve(key, presented)
            }
        }

        // Control: the interim binding admits this link.
        val control = IrohTransport.Session(
            side(),
            remote,
            send = { },
            refuse = { throw AssertionError("the interim binding must admit a valid key") },
        )
        control.onData(hello(name = keyOf(remote).name))
        assertTrue(control.peered)

        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(side(binding = unbound), remote, send = { sent += it }, refuse = { refusals++ })

        session.onData(hello(name = keyOf(remote).name))

        assertEquals(1, refusals, "the link is closed")
        assertEquals(1L, session.admissionDenialCount)
        val denial = assertNotNull(session.lastAdmissionDenial)
        assertEquals(DenialReason.UNVOUCHED, denial.reason)
        assertEquals(null, denial.principal, "no identity means none to attribute the refusal to")
        assertTrue(
            assertNotNull(denial.detail).contains("UnboundReason.${UnboundReason.NO_BINDING.name}"),
            "the detail carries the machine-readable reason: ${denial.detail}",
        )
        assertFalse(session.peered, "no ingress on a refused hello")
        assertEquals(null, session.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(sent.isEmpty(), "nothing is written to a refused link")
    }

    /** Records the ambient [Principal] of every attention assertion it is handed. */
    private class PrincipalProbeCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val principals = CopyOnWriteArrayList<Principal>()

        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            ProtocolSupport.of(outlet).handle(Protocols.Attention) { _, _ ->
                principals += currentPrincipal()
            }
        }
    }

    /**
     * Feature `computenet-5y8t.1`, this transport's half of the issuer rule:
     * every iroh admission is `Authenticated` (the NodeId IS the proven key), so
     * a delivery on an admitted link carries the issuer of the resolution its
     * hello admission made. Attribution only — nothing here speaks to a stolen
     * key or revocation (`[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED).
     *
     * Driven at Session level: a frame after an admitted hello goes straight to
     * the installed ingress, which decodes and stamps it on the side's
     * `bridgeHost` exactly as a sidecar-delivered frame would be.
     */
    @Test
    fun `a delivery on an admitted link carries the issuer the hello admission resolved`() {
        val issuerNaming = PeerIdentityBinding { k, _ ->
            IdentityResolution.Bound(PeerId("issued-" + k.name), IssuerId("test-issuer"), null)
        }
        val remote = nodeId()
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val local = Peering.Side(registry, host, peer = PeerId("local"), identityBinding = issuerNaming)
        val probe = PrincipalProbeCell()
        host.managementInlet.call.spawn(probe)

        val session = IrohTransport.Session(
            local,
            remote,
            send = { },
            refuse = { throw AssertionError("an open side must admit a valid key") },
        )
        session.onData(hello())
        assertTrue(session.peered)

        val frame = HostedPortInvocation(
            cellRef = probe.ref,
            portName = "outlet",
            type = HostedPortInvocation.Type.PORT_PROTOCOL,
            invocation = Invocation("", emptyList(), emptyList()),
            protocolId = Protocols.Attention,
            protocolLink = WireEdgeLink(
                id = UUID.randomUUID(),
                from = PortRef.generate(),
                to = PortRef.generate(probe.ref),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
                toAddr = PortAddress(probe.ref, "outlet"),
            ),
            protocolMessage = Attention(1f),
        )
        session.onData(WireCodec.encode(frame))

        val deadline = System.currentTimeMillis() + 30_000
        while (probe.principals.isEmpty()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out awaiting the delivery")
            Thread.sleep(50)
        }
        assertEquals(
            Principal.Peer(PeerId("issued-" + keyOf(remote).name), AuthLevel.Authenticated, IssuerId("test-issuer")),
            probe.principals.last(),
        )
    }

    @Test
    fun `an open side admits any valid key`() {
        val session = IrohTransport.Session(
            side(),
            nodeId(),
            send = { },
            refuse = { throw AssertionError("an open side refuses nobody") },
        )
        session.onData(hello())
        assertTrue(session.peered)
        assertEquals(0L, session.admissionDenialCount)
    }

    @Test
    fun `a NodeId that is not a valid key is refused as malformed before the allowlist is consulted`() {
        var refusals = 0
        // 32 bytes that are not a valid Edwards point encoding. An allowlist is
        // present and would refuse this connection anyway — the point is that it
        // is never reached, so the denial names the shape and no principal.
        val notAPoint = ByteArray(32) { 0xFF.toByte() }
        val session = IrohTransport.Session(
            side(name = "server", allow = setOf(PeerId("good"))),
            notAPoint,
            send = { },
            refuse = { refusals++ },
        )

        session.onData(hello())

        assertEquals(1, refusals)
        val denial = assertNotNull(session.lastAdmissionDenial)
        assertEquals(DenialReason.MALFORMED_HELLO, denial.reason)
        assertEquals(null, denial.principal, "no key means no identity to attribute the refusal to")
        assertFalse(session.peered)
        assertTrue(
            assertNotNull(denial.detail).contains("NodeId"),
            "the detail names the shape: ${denial.detail}",
        )
    }

    @Test
    fun `a hello whose first token is not a mirror ref is refused as malformed`() {
        var refusals = 0
        val session = IrohTransport.Session(side(), nodeId(), send = { }, refuse = { refusals++ })

        session.onData((IrohTransport.HELLO_PREFIX + "not-a-uuid").toByteArray(StandardCharsets.UTF_8))

        assertEquals(1, refusals)
        assertEquals(DenialReason.MALFORMED_HELLO, assertNotNull(session.lastAdmissionDenial).reason)
        assertFalse(session.peered)
    }

    // ------------------------------------------------------------------------
    // IROH-HELLO2 (feature computenet-5y8t.3, task computenet-5y8t.3.5): the
    // statement-carrying line, resolved by a real AnchorVouchedBinding under a
    // fixed clock. Every case below is about whether a key an accepted issuer
    // vouched for resolves to the vouched name; none speaks to a stolen key or
    // to revocation ([DSC1-NV-01] stays EXPLICITLY UNVERIFIED).
    // ------------------------------------------------------------------------

    private val fixedNow = 1_700_000_000_000L
    private val day = 86_400_000L

    private val anchorA = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("iroh-anchor-A".toByteArray())))
    private val anchorC = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("iroh-anchor-C".toByteArray())))

    /** alice's keypair; her raw public half is the NodeId her links come up on. */
    private val aliceKeys = DeterministicKeySource.keyPairFromSeed("iroh-alice".toByteArray())
    private val aliceNodeId = Ed25519.rawPublicKey(aliceKeys.public)

    private fun statementFor(
        name: String,
        issuer: AnchorIssuer = anchorA,
        notBefore: Long = fixedNow - day,
        notAfter: Long = fixedNow + day,
    ) = issuer.bind(PeerId(name), fingerprint(aliceKeys.public), notBefore = notBefore, notAfter = notAfter)

    private val alice = PeerIdentity(aliceKeys, PeerId("alice"), listOf(statementFor("alice")))

    /** A side whose binding accepts exactly anchor A, judged at [fixedNow]. */
    private fun anchorBound(allow: Set<PeerId>? = null, credentials: PeerCredentials? = null): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(
            registry,
            ManagedHost(registry = registry),
            allow = allow,
            identityBinding = AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { fixedNow }),
            credentials = credentials,
        )
    }

    /** `PeerCredentials` over a [PeerIdentity] — `:wire`'s adapter is not on this classpath. */
    private class TestCredentials(private val identity: PeerIdentity) : PeerCredentials {
        override val keyId: KeyId get() = identity.keyId
        override val peerId: PeerId get() = identity.peerId
        override val publicKey: ByteArray get() = identity.publicKey.encoded
        override fun sign(message: ByteArray): ByteArray = identity.sign(message)
        override val statements: List<IdentityStatement> get() = identity.statements
    }

    private fun hello2(mirrorRef: UUID, name: String, tokens: List<String>): ByteArray =
        (IrohTransport.HELLO2_PREFIX + mirrorRef + " " + name + tokens.joinToString("") { " $it" })
            .toByteArray(StandardCharsets.UTF_8)

    private fun hello2(name: String, statements: List<IdentityStatement>): ByteArray =
        hello2(UUID.randomUUID(), name, statements.map { encodeIdentityStatementToken(it) })

    /** One Session plus what it wrote and how often it refused. */
    private class Probe(side: Peering.Side, nodeId: ByteArray) {
        val sent = mutableListOf<ByteArray>()
        var refusals = 0
        val session = IrohTransport.Session(side, nodeId, send = { sent += it }, refuse = { refusals++ })
    }

    /** Asserts [probe] refused its one hello as [reason] and left no trace, returning the denial. */
    private fun assertRefusedOnce(probe: Probe, reason: DenialReason): civictech.cell.BoundaryDenial {
        assertEquals(1, probe.refusals, "the link is closed exactly once")
        assertEquals(1L, probe.session.admissionDenialCount)
        val denial = assertNotNull(probe.session.lastAdmissionDenial)
        assertEquals(reason, denial.reason, "denial detail: ${denial.detail}")
        assertFalse(probe.session.peered, "no ingress on a refused hello")
        assertEquals(null, probe.session.mirrorRef, "a refused peer costs this side no mirror")
        assertTrue(probe.sent.isEmpty(), "nothing is written to a refused link")
        return denial
    }

    @Test
    fun `IROH-HELLO2 from the vouched key is admitted, and a delivery carries the anchor-issued identity`() {
        // The fixture's premise: the key a statement binds is the key a link
        // on alice's NodeId is proven on.
        assertEquals(fingerprint(aliceKeys.public), keyOf(aliceNodeId))
        assertTrue("alice" != keyOf(aliceNodeId).name, "the admitted name is not the key-derived one")

        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val local = Peering.Side(
            registry,
            host,
            // The allowlist names the RESOLVED identity, never the key.
            allow = setOf(PeerId("alice")),
            identityBinding = AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { fixedNow }),
        )
        val probe = PrincipalProbeCell()
        host.managementInlet.call.spawn(probe)
        val sent = mutableListOf<ByteArray>()
        val session = IrohTransport.Session(
            local,
            aliceNodeId,
            send = { sent += it },
            refuse = { throw AssertionError("a vouched key presenting its statement must be admitted") },
        )

        val mirrorRef = UUID.randomUUID()
        session.onData(hello2(mirrorRef, "alice", alice.statements.map { encodeIdentityStatementToken(it) }))

        assertTrue(session.peered)
        assertEquals(0L, session.admissionDenialCount)
        assertEquals(PeerId("alice"), assertNotNull(session.mirrorCell).peer)
        assertNotNull(session.mirrorRef, "our own mirror was minted")
        assertEquals(
            IrohTransport.HELLO_PREFIX + assertNotNull(session.mirrorRef).id,
            String(sent.first(), StandardCharsets.UTF_8),
            "our own hello is the first frame written; this side holds no statements, so it is IROH-HELLO1",
        )

        session.onData(WireCodec.encode(attentionFrame(probe.ref)))
        val deadline = System.currentTimeMillis() + 30_000
        while (probe.principals.isEmpty()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out awaiting the delivery")
            Thread.sleep(50)
        }
        assertEquals(
            Principal.Peer(PeerId("alice"), AuthLevel.Authenticated, anchorA.issuerId),
            probe.principals.last(),
        )
    }

    @Test
    fun `a side whose credentials hold statements sends IROH-HELLO2, and every other side sends IROH-HELLO1 exactly`() {
        // Credentialed and named: IROH-HELLO2 with the name and one decodable token.
        val named = Probe(anchorBound(credentials = TestCredentials(alice)), nodeId())
        named.session.openLocalHello()
        val line = String(named.sent.single(), StandardCharsets.UTF_8)
        assertTrue(line.startsWith(IrohTransport.HELLO2_PREFIX), line)
        val tokens = line.removePrefix(IrohTransport.HELLO2_PREFIX).split(" ")
        assertEquals(3, tokens.size, line)
        assertEquals(assertNotNull(named.session.mirrorRef).id.toString(), tokens[0])
        assertEquals("alice", tokens[1])
        // IdentityStatement's signature is a ByteArray, so compare canonical tokens, not data-class equality.
        assertEquals(encodeIdentityStatementToken(alice.statements.single()), tokens[2])
        assertEquals(PeerId("alice"), assertNotNull(decodeIdentityStatementToken(tokens[2])).name)

        // Credentialed but unnamed (no statements): IROH-HELLO1, byte for byte.
        val unnamed = Probe(anchorBound(credentials = TestCredentials(PeerIdentity(Ed25519.generateKeyPair()))), nodeId())
        unnamed.session.openLocalHello()
        assertEquals(
            IrohTransport.HELLO_PREFIX + assertNotNull(unnamed.session.mirrorRef).id,
            String(unnamed.sent.single(), StandardCharsets.UTF_8),
        )

        // No credentials at all: IROH-HELLO1, byte for byte.
        val bare = Probe(side(name = "bare"), nodeId())
        bare.session.openLocalHello()
        assertEquals(
            IrohTransport.HELLO_PREFIX + assertNotNull(bare.session.mirrorRef).id,
            String(bare.sent.single(), StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `a key the presented statements do not vouch for is refused UNVOUCHED or STATEMENT_EXPIRED, before the allowlist`() {
        // The allowlist names alice, so a refusal below that were the
        // allowlist's would read NOT_ADMITTED. None may.
        val allow = setOf(PeerId("alice"))

        // mallory's key, presenting alice's statement under alice's name.
        val malloryNodeId = nodeId()
        val claimsAlice = Probe(anchorBound(allow), malloryNodeId)
        claimsAlice.session.onData(hello2("alice", alice.statements))
        val keyMismatch = assertRefusedOnce(claimsAlice, DenialReason.UNVOUCHED)
        assertEquals(null, keyMismatch.principal)
        assertTrue(assertNotNull(keyMismatch.detail).contains("UnboundReason.${UnboundReason.KEY_MISMATCH.name}"), keyMismatch.detail)

        // mallory's key, alice's statement, mallory's own name — a name the
        // allowlist does NOT hold, so consulting it first would say NOT_ADMITTED.
        val ownName = Probe(anchorBound(allow), malloryNodeId)
        ownName.session.onData(hello2("mallory", alice.statements))
        val ownNameDenial = assertRefusedOnce(ownName, DenialReason.UNVOUCHED)
        assertTrue(assertNotNull(ownNameDenial.detail).contains("UnboundReason.${UnboundReason.KEY_MISMATCH.name}"), ownNameDenial.detail)

        // alice's key, a statement from an issuer this side does not accept.
        val unaccepted = Probe(anchorBound(allow), aliceNodeId)
        unaccepted.session.onData(hello2("alice", listOf(statementFor("alice", issuer = anchorC))))
        val issuerDenial = assertRefusedOnce(unaccepted, DenialReason.UNVOUCHED)
        assertEquals(null, issuerDenial.principal)
        assertTrue(
            assertNotNull(issuerDenial.detail).contains("UnboundReason.${UnboundReason.ISSUER_NOT_ACCEPTED.name}"),
            issuerDenial.detail,
        )

        // alice's key, an accepted statement whose window closed a day ago.
        val expired = Probe(anchorBound(allow), aliceNodeId)
        expired.session.onData(hello2("alice", listOf(statementFor("alice", notBefore = fixedNow - 3 * day, notAfter = fixedNow - day))))
        val expiredDenial = assertRefusedOnce(expired, DenialReason.STATEMENT_EXPIRED)
        assertEquals(null, expiredDenial.principal)
        val expiredDetail = assertNotNull(expiredDenial.detail)
        assertTrue(expiredDetail.contains("UnboundReason.${UnboundReason.EXPIRED.name}"), expiredDetail)
        assertTrue(expiredDetail.contains("clock"), "the detail names whose clock judged the window: $expiredDetail")
    }

    @Test
    fun `IROH-HELLO1 to an anchor-bound side presents no statement and is refused UNVOUCHED, not ID_MISMATCH`() {
        for (name in listOf(null, "alice")) {
            val probe = Probe(anchorBound(), aliceNodeId)
            probe.session.onData(hello(name = name))
            val denial = assertRefusedOnce(probe, DenialReason.UNVOUCHED)
            assertEquals(null, denial.principal)
            assertTrue(
                assertNotNull(denial.detail).contains("UnboundReason.${UnboundReason.NO_STATEMENT.name}"),
                "trailing name $name: ${denial.detail}",
            )
        }
    }

    @Test
    fun `IROH-HELLO2 to an interim side resolves the key-derived name and is refused ID_MISMATCH naming both`() {
        val local = side(name = "local")
        val probe = Probe(local, aliceNodeId)
        probe.session.onData(hello2("alice", alice.statements))

        val denial = assertRefusedOnce(probe, DenialReason.ID_MISMATCH)
        val derived = local.identityBinding.boundPeer(keyOf(aliceNodeId))
        assertEquals(derived, denial.principal, "attributed to who the interim binding says is on the link")
        val detail = assertNotNull(denial.detail)
        assertTrue(detail.contains("alice") && detail.contains(derived.name), detail)
    }

    @Test
    fun `malformed IROH-HELLO2 lines are refused MALFORMED_HELLO on their shape alone`() {
        val token = encodeIdentityStatementToken(alice.statements.single())
        val ref = UUID.randomUUID()
        val malformed = mapOf(
            "two tokens" to hello2(ref, "alice", emptyList()),
            "nine statements" to hello2(ref, "alice", List(9) { token }),
            "empty name via a doubled space" to hello2(ref, "", listOf(token)),
            "a =-padded token" to hello2(ref, "alice", listOf("$token=")),
            "a mirror ref that is not a UUID" to
                (IrohTransport.HELLO2_PREFIX + "not-a-uuid alice " + token).toByteArray(StandardCharsets.UTF_8),
        )
        for ((shape, line) in malformed) {
            // An anchor-bound side that WOULD admit alice's well-formed line:
            // the refusal is the shape's, not the binding's.
            val probe = Probe(anchorBound(), aliceNodeId)
            probe.session.onData(line)
            val denial = assertRefusedOnce(probe, DenialReason.MALFORMED_HELLO)
            assertEquals(null, denial.principal, shape)
            assertTrue(!assertNotNull(denial.detail).contains(token), "$shape: the detail never echoes the bytes")
        }

        // Control: the same fixture, well formed, is admitted.
        val control = Probe(anchorBound(), aliceNodeId)
        control.session.onData(hello2(ref, "alice", listOf(token)))
        assertTrue(control.session.peered)

        // A pre-DSC4 side's only check is `startsWith(IROH-HELLO1 )`, which
        // an IROH-HELLO2 line fails: it is refused malformed, never misread.
        assertFalse(String(hello2(ref, "alice", listOf(token)), StandardCharsets.UTF_8).startsWith(IrohTransport.HELLO_PREFIX))
    }

    private fun attentionFrame(target: CellRef) = HostedPortInvocation(
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
}

/**
 * The identity [key] resolves to through this binding, for spelling an
 * expected value. Fails loudly on `Unbound` rather than substituting anything.
 */
private fun PeerIdentityBinding.boundPeer(key: KeyId): PeerId =
    when (val resolution = resolve(key, emptyList())) {
        is IdentityResolution.Bound -> resolution.peer
        is IdentityResolution.Unbound -> throw AssertionError("expected $key to be bound, got $resolution")
    }
