package civictech.wire

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.membrane.AuthLevel
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-14: `Peering.Side.admits` is evaluated on the *derived* id, never the
 * claimed string (`[DSC1-HELLO-12]`) — both directions of that claim. An
 * allowlist naming a key-derived `PeerId` cannot be borrowed by a different
 * keypair claiming that name (the identity-binding check — `derived ==
 * claimed`, `[DSC1-HELLO-06]` — refuses it before the allowlist is even
 * consulted), and the genuine holder of the matching private key is admitted.
 *
 * `:wire` carries no `:testkit` dependency, so this drives `Session` directly,
 * the same instrument `WsHelloAdversarialTest` and `WsAuthenticatedHelloTest`
 * use, since this scenario needs exact control over "presents key K, claims a
 * *different* id" — a shape no real dialer can be made to send.
 */
class WsHelloAllowlistDerivedIdTest {

    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    private class Stack(val identity: PeerIdentity, allow: Set<PeerId>? = null) {
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            // requireAnnouncementIdentity: a RequireAuthenticated socket side
            // must sign AND verify its announcements (computenet-ssa.4.4).
            announcementSigning = socketAnnouncementSigning(),
            announcementVerification = socketAnnouncementVerification(),
        )
    }

    /** The remote role, played locally — see `WsHelloAdversarialTest.Peer`, reproduced for this file's scenario. */
    private class Peer(val session: WsTransport.Session, val identity: PeerIdentity) {
        val mirrorRef: UUID = UUID.randomUUID()
        val nonce: ByteArray = generateHelloNonce()

        lateinit var localHello: Hello2
            private set

        fun open() {
            localHello = (parseHello2(session.hello()) as HelloParse.Ok).message
        }

        /** [claimedBy] lets the attacker case present [identity]'s key while claiming a different id. */
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

    @Test
    fun `BS-14 a different keypair claiming an allowlisted id is refused, its genuine holder is admitted`() {
        val allowed = identity("bs14-allowed")
        val attacker = identity("bs14-attacker")
        val local = Stack(identity("bs14-local"), allow = setOf(allowed.peerId))

        // -- an attacker holding a DIFFERENT keypair claims the allowlisted id --
        val attackSession = WsTransport.Session(local.side, send = {}, refuse = {}, sendText = {})
        val attackPeer = Peer(attackSession, attacker)
        attackPeer.open()
        attackPeer.send(encodeHello2(attackPeer.hello(claimedBy = allowed)))

        val denial = requireNotNull(attackSession.lastAdmissionDenial) {
            "impersonating the allowlisted id was not refused at all"
        }
        // refused before the allowlist is even reached: the identity-binding
        // check (derived id != claimed id) fires first, so the allowlist
        // cannot be bypassed by borrowing an allowed name
        denial.reason shouldBe DenialReason.ID_MISMATCH
        denial.principal shouldBe allowed.peerId
        attackSession.achievedAuthLevel.shouldBeNull()

        // -- the genuine holder of the allowlisted identity's own private key --
        val goodSession = WsTransport.Session(local.side, send = {}, refuse = {}, sendText = {})
        val goodPeer = Peer(goodSession, allowed)
        goodPeer.open()
        goodPeer.handshake()

        goodSession.achievedAuthLevel shouldBe AuthLevel.Authenticated
        goodSession.lastAdmissionDenial.shouldBeNull()
    }
}
