package civictech.wire

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityStatement
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.membrane.AuthLevel
import civictech.cell.membrane.Principal
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireEdgeLink
import civictech.identity.DeterministicKeySource
import civictech.identity.FilePeerKeyStore
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID

/**
 * Feature `computenet-5y8t.3` (DSC4), task `computenet-5y8t.3.4`: `:wire`
 * admission speaks `HELLO3`. A side whose credentials hold anchor-signed
 * statements sends them; a receiving side resolves the presented key WITH
 * those statements through `Peering.Side.identityBinding`; and every `Unbound`
 * verdict is accounted `UNVOUCHED` or `STATEMENT_EXPIRED` through
 * `civictech.cell.wire.denialReasonFor`.
 *
 * The listener `L` here holds a real `AnchorVouchedBinding` accepting one
 * anchor `A`, on a **fixed clock**, and allowlists the stable name `alice` —
 * a name that is not in key-derived form, so an admission under it cannot
 * have come from the `HELLO2` derivation.
 *
 * What these tests are NOT: none of them speaks to stolen-key resistance or
 * revocation. A peer presenting someone else's statement beside its own
 * keypair is refused because that statement binds a different key
 * (`UnboundReason.KEY_MISMATCH`) — a statement that does not vouch for the
 * presented key, nothing more. `[DSC1-NV-01]` and `[DSC1-NV-03]` stay
 * EXPLICITLY UNVERIFIED.
 *
 * Session-level cases drive `WsTransport.Session` directly, as
 * [WsAuthenticatedHelloTest] does; the last case peers two named identities
 * over a real socket.
 */
class WsAnchorVouchedHelloTest {

    @TempDir
    lateinit var keyDirs: Path

    private val fixedNow = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun keyed(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    private val anchorA = AnchorIssuer(keyed("anchor-A"))
    private val anchorC = AnchorIssuer(keyed("anchor-C"))

    private val aliceKeys = DeterministicKeySource.keyPairFromSeed("alice".toByteArray())
    private val aliceKeyId = PeerIdentity(aliceKeys).keyId
    private val aliceName = PeerId("alice")

    private fun statement(
        issuer: AnchorIssuer = anchorA,
        notBefore: Long = fixedNow - day,
        notAfter: Long = fixedNow + day,
    ): IdentityStatement = issuer.bind(aliceName, aliceKeyId, 1, notBefore, notAfter)

    private fun alice(vararg statements: IdentityStatement = arrayOf(statement())) =
        PeerIdentity(aliceKeys, aliceName, statements.toList())

    private fun anchorBinding() =
        AnchorVouchedBinding(mapOf(anchorA.issuerId to anchorA.publicKey), clock = { fixedNow })

    /**
     * A peering side in memory: `RequireAuthenticated`, signing and verifying
     * announcements, whenever it holds credentials — unless [openUnsigned],
     * which keeps the credentials (so it still sends and challenges a keyed
     * hello) but is `Open` and signs no announcement. See the socket case for
     * why that variant exists.
     */
    private class Side(
        identity: PeerIdentity?,
        binding: PeerIdentityBinding,
        allow: Set<PeerId>? = null,
        openUnsigned: Boolean = false,
    ) {
        private val signs = identity != null && !openUnsigned

        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = if (signs) PeerAuthPolicy.RequireAuthenticated() else PeerAuthPolicy.Open,
            credentials = identity?.asPeerCredentials(),
            announcementSigning = if (signs) socketAnnouncementSigning() else null,
            announcementVerification = if (signs) socketAnnouncementVerification() else null,
            identityBinding = binding,
        )
    }

    /** One Session over [side], recording what it writes and how often it refuses. */
    private class Driven(side: Side) {
        val texts = mutableListOf<String>()
        var refusals = 0
        val session = WsTransport.Session(
            side.side,
            send = {},
            refuse = { refusals++ },
            sendText = { texts += it },
        )
    }

    private fun listener(binding: PeerIdentityBinding = anchorBinding()) =
        Side(keyed("listener-L"), binding, allow = setOf(aliceName))

    /**
     * A `HELLO3` line posed by a peer holding [keys] and claiming [claimed]
     * with [statements] — for the refusal cases, where the line must be
     * exactly what a misconfigured or hostile peer would send.
     */
    private fun posedHello3(keys: PeerIdentity, claimed: PeerId, statements: List<IdentityStatement>): String =
        encodeHello3(Hello3(UUID.randomUUID(), claimed, keys.publicKey.encoded, generateHelloNonce(), statements))

    private fun Driven.assertRefusedUnbound(
        reason: DenialReason,
        unbound: UnboundReason,
        principal: PeerId?,
        registry: LocationRegistry,
    ): String {
        val denial = requireNotNull(session.lastAdmissionDenial) { "the hello was not refused" }
        denial.reason shouldBe reason
        denial.principal shouldBe principal
        val detail = requireNotNull(denial.detail)
        detail shouldContain "UnboundReason.${unbound.name}"
        detail shouldNotContain "allowlist"
        refusals shouldBe 1
        texts.filter { it.startsWith(PROOF_PREFIX) } shouldBe emptyList()
        session.peered shouldBe false
        session.achievedAuthLevel.shouldBeNull()
        registry.remoteRefs() shouldBe emptySet()
        return detail
    }

    /**
     * Example 1: alice's own Session emits `HELLO3`; L answers with a `PROOF`;
     * alice's `PROOF` admits her at `Authenticated` under the stable name, and
     * a delivery on L's ingress observes `Principal.Peer(alice, Authenticated,
     * A)`. Both halves are real Sessions — nothing about the handshake is
     * posed.
     */
    @Test
    fun `a HELLO3 whose statement an accepted anchor vouches for is admitted at Authenticated under the stable name`() {
        isKeyDerivedPeerIdForm(aliceName.name) shouldBe false

        val l = listener()
        val probe = WsPrincipalPromotionTest.PrincipalProbeCell()
        l.host.managementInlet.call.spawn(probe)
        val atL = Driven(l)
        // alice's side judges L's HELLO2 under the interim binding: L is unnamed
        val atAlice = Driven(Side(alice(), PeerIdentityBinding.Interim))

        val aliceLine = atAlice.session.hello()
        val lLine = atL.session.hello()
        aliceLine shouldStartWith HELLO3_PREFIX
        val parsed = (parseHello3(aliceLine) as HelloParse.Ok).message
        parsed.claimedPeerId shouldBe aliceName
        parsed.statements.size shouldBe 1

        atL.session.onText(aliceLine)
        atL.texts.size shouldBe 1
        atL.texts.single() shouldStartWith PROOF_PREFIX
        atL.session.peered shouldBe false // nothing admitted before alice's PROOF

        atAlice.session.onText(lLine)
        val aliceProof = atAlice.texts.single()
        atL.session.onText(aliceProof)
        atAlice.session.onText(atL.texts.single())

        atL.session.lastAdmissionDenial.shouldBeNull()
        atL.session.achievedAuthLevel shouldBe AuthLevel.Authenticated
        atL.session.peered shouldBe true
        atAlice.session.achievedAuthLevel shouldBe AuthLevel.Authenticated

        atL.session.onFrame(ByteBuffer.wrap(WireCodec.encode(attention(probe.ref))))
        val deadline = System.currentTimeMillis() + 30_000
        while (probe.principals.isEmpty()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting the delivery")
            Thread.sleep(50)
        }
        probe.principals.last() shouldBe Principal.Peer(aliceName, AuthLevel.Authenticated, anchorA.issuerId)
    }

    /** Example 2: a peer with its own keypair presenting alice's statement — the statement binds another key. */
    @Test
    fun `a HELLO3 presenting a statement that binds a different key is refused UNVOUCHED as KEY_MISMATCH`() {
        val l = listener()
        val at = Driven(l)
        at.session.hello()
        at.session.onText(posedHello3(keyed("mallory"), aliceName, listOf(statement())))

        at.assertRefusedUnbound(DenialReason.UNVOUCHED, UnboundReason.KEY_MISMATCH, aliceName, l.registry)
    }

    /**
     * The order on the `HELLO3` path: the binding's verdict precedes the
     * allowlist. `carol` is on no allowlist AND her only statement comes from
     * an unaccepted anchor; the refusal must name the statement
     * (`UNVOUCHED`), never the allowlist (`NOT_ADMITTED`). Example 2 cannot
     * show this — its claimed `alice` is allowlisted, so an allowlist checked
     * first would pass and the same `UNVOUCHED` would follow.
     */
    @Test
    fun `an unvouched HELLO3 claiming a name off the allowlist is refused UNVOUCHED, not NOT_ADMITTED`() {
        val carolKeys = keyed("carol")
        val carol = PeerId("carol")
        val l = listener()
        val at = Driven(l)
        at.session.hello()
        at.session.onText(posedHello3(carolKeys, carol, listOf(anchorC.bind(carol, carolKeys.keyId, 1, fixedNow - day, fixedNow + day))))

        at.assertRefusedUnbound(DenialReason.UNVOUCHED, UnboundReason.ISSUER_NOT_ACCEPTED, carol, l.registry)
    }

    /** Example 3: an unaccepted issuer is UNVOUCHED; a window miss is STATEMENT_EXPIRED and names the clock. */
    @Test
    fun `an unaccepted issuer is UNVOUCHED, and an expired or not-yet-valid statement is STATEMENT_EXPIRED under this side's clock`() {
        fun refuse(statement: IdentityStatement): Pair<Driven, Side> {
            val l = listener()
            val at = Driven(l)
            at.session.hello()
            at.session.onText(posedHello3(PeerIdentity(aliceKeys), aliceName, listOf(statement)))
            return at to l
        }

        val (unaccepted, l1) = refuse(statement(issuer = anchorC))
        unaccepted.assertRefusedUnbound(DenialReason.UNVOUCHED, UnboundReason.ISSUER_NOT_ACCEPTED, aliceName, l1.registry)

        val (expired, l2) = refuse(statement(notBefore = fixedNow - 3 * day, notAfter = fixedNow - 2 * day))
        expired.assertRefusedUnbound(DenialReason.STATEMENT_EXPIRED, UnboundReason.EXPIRED, aliceName, l2.registry) shouldContain
            "this side's clock"

        val (early, l3) = refuse(statement(notBefore = fixedNow + day, notAfter = fixedNow + 2 * day))
        early.assertRefusedUnbound(DenialReason.STATEMENT_EXPIRED, UnboundReason.NOT_YET_VALID, aliceName, l3.registry) shouldContain
            "this side's clock"
    }

    /** Example 4: the pre-DSC4 forms at an anchor-bound side are unvouched — never ID_MISMATCH, never admitted. */
    @Test
    fun `a plain HELLO2 and a legacy hello at an anchor-bound side are refused UNVOUCHED as NO_STATEMENT`() {
        val l = listener()
        val atL = Driven(l)
        val unnamed = keyed("credentialed-unnamed")
        val atUnnamed = Driven(Side(unnamed, PeerIdentityBinding.Interim))
        atL.session.hello()
        val hello2 = atUnnamed.session.hello()
        hello2 shouldStartWith HELLO2_PREFIX
        atL.session.onText(hello2)
        atL.assertRefusedUnbound(DenialReason.UNVOUCHED, UnboundReason.NO_STATEMENT, unnamed.peerId, l.registry)

        val open = Side(null, anchorBinding())
        val atOpen = Driven(open)
        atOpen.session.hello()
        atOpen.session.onText("HELLO ${UUID.randomUUID()} mallory")
        atOpen.assertRefusedUnbound(DenialReason.UNVOUCHED, UnboundReason.NO_STATEMENT, null, open.registry)
    }

    /** Example 5, the stated break: an Interim side resolves the key-derived name, which the stable claim does not match. */
    @Test
    fun `alice's HELLO3 at an Interim listener is refused ID_MISMATCH naming both names`() {
        val l = listener(binding = PeerIdentityBinding.Interim)
        val at = Driven(l)
        at.session.hello()
        at.session.onText(Driven(Side(alice(), PeerIdentityBinding.Interim)).session.hello())

        val denial = requireNotNull(at.session.lastAdmissionDenial)
        denial.reason shouldBe DenialReason.ID_MISMATCH
        denial.principal shouldBe aliceName
        val detail = requireNotNull(denial.detail)
        detail shouldContain "claims alice"
        detail shouldContain aliceKeyId.name
        at.texts shouldBe emptyList()
        at.session.peered shouldBe false
    }

    /**
     * The sender fails loudly — an `IllegalStateException` naming the limit —
     * for credentials a `HELLO3` line cannot carry, rather than truncating
     * them into a different claim (orchestrator decision on this task,
     * consistent with `IrohTransport`'s hello).
     */
    @Test
    fun `a sender whose credentials a HELLO3 cannot carry fails loudly instead of truncating`() {
        val tooMany = alice(*Array(MAX_HELLO_STATEMENTS + 1) { statement() })
        val many = Driven(Side(tooMany, PeerIdentityBinding.Interim))
        shouldThrow<IllegalStateException> { many.session.hello() }.message shouldContain "at most $MAX_HELLO_STATEMENTS"
        many.session.peered shouldBe false

        val spaced = PeerId("al ice")
        val spacedIdentity = PeerIdentity(aliceKeys, spaced, listOf(anchorA.bind(spaced, aliceKeyId, 1, fixedNow, fixedNow + day)))
        shouldThrow<IllegalStateException> { Driven(Side(spacedIdentity, PeerIdentityBinding.Interim)).session.hello() }
            .message shouldContain "empty or contains a space"
    }

    /**
     * The BS-01 analogue for names, over a real socket: two identities named
     * by anchor `A` and loaded from disk through `FilePeerKeyStore.storeStatements`
     * + `loadNamed`, both under an `AnchorVouchedBinding` — each sends `HELLO3`,
     * answers the other's challenge, reaches `Authenticated`, and attributes
     * the other's `Remote` locations to its stable name.
     *
     * **Both sides are `Open` and sign no announcements, deliberately, and
     * that is a known gap, not a choice of fixture.** Under
     * `RequireAuthenticated` a `:wire` side must sign announcements
     * (`requireAnnouncementIdentity`), and the kernel's announcement gate
     * (`civictech.cell.wire.AnnouncementAdmission`) still reads the minting
     * identity as `PeerId(signerKeyId)` — the signing key's fingerprint —
     * while the connection is bound to the stable name, so every signed
     * announcement from a named peer is refused `ID_MISMATCH` and no `Remote`
     * location ever appears (observed while writing this test). Re-keying that
     * gate is a DSC4 residual in `:kernel`, outside task
     * `computenet-5y8t.3.4`'s files, owned by feature `computenet-5y8t.7`; the
     * `RequireAuthenticated`, signed-announcement variant of this case belongs
     * there. The hello and its `PROOF` are exercised here in full — credentials
     * are held, so both sides challenge — and only the announcement signature
     * is absent.
     */
    @Test
    fun `two named identities peer over a socket at Authenticated, each attributed to its stable name`() {
        val anchor = AnchorIssuer(keyed("socket-anchor"))
        fun named(name: String): PeerIdentity {
            val store = FilePeerKeyStore(keyDirs.resolve(name))
            val keyed = store.loadOrGenerate()
            store.storeStatements(listOf(anchor.bind(PeerId(name), keyed.keyId)))
            return store.loadNamed()
        }
        val binding = AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey))
        val server = Side(named("server-name"), binding, openUnsigned = true)
        val client = Side(named("client-name"), binding, openUnsigned = true)
        val collector = WsAuthenticatedHelloTest.CollectingCell()
        server.host.managementInlet.call.spawn(collector)
        val writer = WsAuthenticatedHelloTest.CollectingCell()
        client.host.managementInlet.call.spawn(writer)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("both sides learned each other's cells at Authenticated") {
                client.registry.location(collector.ref) is LocationRegistry.Remote &&
                    server.registry.location(writer.ref) is LocationRegistry.Remote &&
                    connection.achievedAuthLevel == AuthLevel.Authenticated &&
                    listener.achievedAuthLevels == listOf(AuthLevel.Authenticated)
            }
            (client.registry.location(collector.ref) as LocationRegistry.Remote).peer shouldBe PeerId("server-name")
            (server.registry.location(writer.ref) as LocationRegistry.Remote).peer shouldBe PeerId("client-name")
            listener.admissionDenialCount shouldBe 0L
            connection.admissionDenialCount shouldBe 0L
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    private fun attention(target: CellRef) = HostedPortInvocation(
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
