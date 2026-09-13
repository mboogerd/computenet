package civictech.wire

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.cell.membrane.AuthLevel
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Feature `computenet-5y8t.4`, decision F4-D6: on every `:wire` hello path the
 * binding's `Unbound` refusal fires **before** the allowlist is ever
 * consulted — even when the allowlist names EXACTLY the key-derived fallback
 * name a `PeerId(key.name)` shortcut would have produced. If the allowlist
 * were reached with that fallback name, both rows below would be admitted;
 * instead each is refused with the `Unbound` detail, never the allowlist's.
 *
 * This is test-only coverage of an order that already exists in
 * `WsTransport` (`onAuthenticatedHello`'s `IdentityResolution.Unbound` branch
 * at the derive step, `onLegacyHello`'s at the same step) — see
 * [WsAuthenticatedHelloTest] for the taxonomy this pins one more corner of.
 * The bindings here are test stand-ins that verify nothing about a real
 * identity system; `[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED, and nothing in
 * this file speaks to stolen-key resistance or revocation.
 *
 * `:wire` carries no `:testkit` dependency, so this reuses the hand-rolled
 * `Stack`/`Peer` scaffolding [WsAuthenticatedHelloTest] and
 * [WsHelloAllowlistDerivedIdTest] already establish, rather than widening
 * either of those (private) fixtures.
 */
class WsUnboundPrecedesAllowlistTest {

    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    /**
     * A binding holding no identity for [unbound], and the interim answer for
     * every other key — see `WsAuthenticatedHelloTest.bindingWithoutIdentityFor`,
     * reproduced here rather than widened there.
     */
    private fun bindingWithoutIdentityFor(unbound: KeyId) = PeerIdentityBinding { key, presented ->
        if (key == unbound) IdentityResolution.Unbound(UnboundReason.NO_BINDING) else PeerIdentityBinding.Interim.resolve(key, presented)
    }

    private class Stack(
        val identity: PeerIdentity,
        allow: Set<PeerId>? = null,
        binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ) {
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            announcementSigning = socketAnnouncementSigning(),
            announcementVerification = socketAnnouncementVerification(),
            identityBinding = binding,
        )
    }

    /** The remote role, played locally — see `WsAuthenticatedHelloTest.Peer`, reproduced for this file's scenario. */
    private class Peer(val session: WsTransport.Session, val identity: PeerIdentity) {
        val mirrorRef: UUID = UUID.randomUUID()
        val nonce: ByteArray = generateHelloNonce()

        lateinit var localHello: Hello2
            private set

        fun open() {
            localHello = (parseHello2(session.hello()) as HelloParse.Ok).message
        }

        fun hello(claimedBy: PeerIdentity = identity): Hello2 =
            Hello2(mirrorRef, claimedBy.peerId, identity.publicKey.encoded, nonce)

        fun send(line: String) = session.onText(line)

        fun challenge(): HelloChallenge = HelloChallenge(
            signerPeerId = identity.peerId,
            verifierPeerId = localHello.claimedPeerId,
            verifierNonce = localHello.nonce,
            signerNonce = nonce,
            signerMirrorRef = mirrorRef,
            verifierMirrorRef = localHello.mirrorRef,
        )

        fun handshake(): String {
            val line = encodeHello2(hello())
            send(line)
            send(encodeProof(Proof(identity.sign(helloChallengeBytes(challenge())))))
            return line
        }
    }

    /**
     * The HELLO2 row: a receiving side allowlists exactly `remote.peerId` —
     * the `Interim` resolution of `remote.keyId`, i.e. the name a
     * `PeerId(key.name)` fallback would have produced for the presented key
     * — while its binding holds no identity for that key. The presented key
     * is refused at the derive step, before `Side.admits` is ever called; no
     * `PROOF` answers it and nothing is admitted.
     *
     * Control: the identical `Stack` arguments (same allowlist) under
     * `PeerIdentityBinding.Interim` complete the handshake at `Authenticated`
     * with no denial — the allowlist entry is a live one, only the binding
     * differs.
     */
    @Test
    fun `a HELLO2 is refused Unbound even when the allowlist names the key-derived fallback, and PeerIdentityBinding-Interim admits it`() {
        val remote = identity("unbound-precedes-remote")

        // -- the refusal: Unbound before the allowlist ------------------------
        val unboundLocal = Stack(
            identity("unbound-precedes-local"),
            allow = setOf(remote.peerId),
            binding = bindingWithoutIdentityFor(remote.keyId),
        )
        val texts = mutableListOf<String>()
        var refusals = 0
        val session = WsTransport.Session(
            unboundLocal.side,
            send = {},
            refuse = { refusals++ },
            sendText = { texts += it },
        )
        val peer = Peer(session, remote)
        peer.open()
        peer.send(encodeHello2(peer.hello()))

        val denial = requireNotNull(session.lastAdmissionDenial) { "the unbound key was not refused" }
        denial.reason shouldBe DenialReason.UNVOUCHED
        // attributed to the id the hello CLAIMED — never a key-derived fallback
        denial.principal shouldBe remote.peerId
        val detail = requireNotNull(denial.detail)
        detail shouldContain "UnboundReason.${UnboundReason.NO_BINDING.name}"
        detail shouldNotContain "allowlist"
        refusals shouldBe 1
        texts shouldBe emptyList()
        session.achievedAuthLevel.shouldBeNull()
        session.peered shouldBe false

        // -- the control: the very same allowlist entry, under Interim, admits --
        val boundLocal = Stack(
            identity("unbound-precedes-local-control"),
            allow = setOf(remote.peerId),
        )
        val controlSession = WsTransport.Session(
            boundLocal.side,
            send = {},
            refuse = { throw AssertionError("the interim binding must admit an allowlisted key") },
            sendText = {},
        )
        val controlPeer = Peer(controlSession, remote)
        controlPeer.open()
        controlPeer.handshake()

        controlSession.achievedAuthLevel shouldBe AuthLevel.Authenticated
        controlSession.lastAdmissionDenial.shouldBeNull()
    }

    /**
     * The legacy name-only row at an `Open` side: the allowlist names exactly
     * `PeerId("mallory")` — the fallback the asserted token `mallory` would
     * derive to — while the binding holds no identity for `KeyId("mallory")`.
     * The refusal fires at the derive step, attributed to no principal (the
     * token is a key identifier, never a name to build a `PeerId` from), and
     * never reaches `Side.admits`.
     *
     * Control: the same allowlist under `PeerIdentityBinding.Interim` peers.
     */
    @Test
    fun `a legacy hello is refused Unbound even when the allowlist names the asserted token's fallback, and Interim peers`() {
        fun openSide(binding: PeerIdentityBinding, allow: Set<PeerId>?): Peering.Side {
            val registry = LocationRegistry()
            return Peering.Side(registry, ManagedHost(registry = registry), allow = allow, identityBinding = binding)
        }

        fun drive(side: Peering.Side): WsTransport.Session {
            val session = WsTransport.Session(side, send = {}, refuse = {})
            session.hello()
            session.onText("HELLO ${UUID.randomUUID()} mallory")
            return session
        }

        val bound = drive(openSide(PeerIdentityBinding.Interim, allow = setOf(PeerId("mallory"))))
        bound.lastAdmissionDenial.shouldBeNull()
        bound.peered shouldBe true

        val unbound = drive(
            openSide(bindingWithoutIdentityFor(KeyId("mallory")), allow = setOf(PeerId("mallory"))),
        )
        val denial = requireNotNull(unbound.lastAdmissionDenial) { "the unbound token was not refused" }
        denial.reason shouldBe DenialReason.UNVOUCHED
        denial.principal.shouldBeNull()
        val detail = requireNotNull(denial.detail)
        detail shouldContain "UnboundReason.${UnboundReason.NO_BINDING.name}"
        detail shouldNotContain "allowlist"
        unbound.peered shouldBe false
    }
}
