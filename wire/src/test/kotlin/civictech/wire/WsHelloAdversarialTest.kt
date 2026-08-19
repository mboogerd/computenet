package civictech.wire

import civictech.cell.BoundaryDenials
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
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The feature's four named adversarial hellos — BS-09 (impersonation), BS-10
 * (forgery), BS-11 (downgrade) and BS-12 (replay) — each driven at exactly the
 * single admission point, `WsTransport.Session.onText`
 * (`[DSC1-HELLO-06..09, 11, 13]`).
 *
 * ## Instrument
 *
 * `:wire` carries no `:testkit` dependency, so this hand-rolls the same
 * instrument `WsAuthenticatedHelloTest`'s refusal-taxonomy case uses: drive a
 * `Session` directly with hand-crafted `HELLO2`/`PROOF` lines, this test
 * playing the remote (hostile) role with keys it holds — a hostile peer is
 * easier to *be* than to build. Deterministic keypairs come from
 * `DeterministicKeySource` (`HelloProtocolTest`'s fixture), so a failure here
 * is reproducible rather than a fresh key every run.
 *
 * Deliberately **not** duplicated here: `WsAuthenticatedHelloTest`'s own
 * taxonomy test also produces a refusal of each class, but only to check the
 * *accounting* contract (a machine-distinguishable reason, recorded before
 * close). What is named here is the *security property* each scenario proves.
 */
class WsHelloAdversarialTest {

    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    private class Stack(
        val identity: PeerIdentity,
        allow: Set<PeerId>? = null,
        auth: PeerAuthPolicy = PeerAuthPolicy.RequireAuthenticated(),
    ) {
        val registry = LocationRegistry()
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = auth,
            credentials = identity.asPeerCredentials(),
        )
    }

    /**
     * The remote role, played locally: encodes the hellos and proofs a peer
     * would send to [session], signing with [identity]'s private half —
     * `WsAuthenticatedHelloTest.Peer`'s shape, reproduced here so this file's
     * scenarios can be exact about what arrives.
     */
    private class Peer(val session: WsTransport.Session, val identity: PeerIdentity) {
        val mirrorRef: UUID = UUID.randomUUID()
        val nonce: ByteArray = generateHelloNonce()

        /** The local side's own `HELLO2`, parsed — its mirror ref and nonce are half of every challenge. */
        lateinit var localHello: Hello2
            private set

        /** Take the local side's hello, as a peer receiving it would. */
        fun open() {
            localHello = (parseHello2(session.hello()) as HelloParse.Ok).message
        }

        /** [claimedBy] lets a scenario present [identity]'s key while claiming a different id (BS-09/14). */
        fun hello(claimedBy: PeerIdentity = identity): Hello2 =
            Hello2(mirrorRef, claimedBy.peerId, identity.publicKey.encoded, nonce)

        fun send(line: String) = session.onText(line)

        /** The challenge this peer signs: itself as signer, the local side as verifier. */
        fun challenge(): HelloChallenge = HelloChallenge(
            signerPeerId = identity.peerId,
            verifierPeerId = localHello.claimedPeerId,
            verifierNonce = localHello.nonce,
            signerNonce = nonce,
            signerMirrorRef = mirrorRef,
            verifierMirrorRef = localHello.mirrorRef,
        )

        /** A complete valid exchange; returns the `HELLO2` line it sent, for replaying (BS-12). */
        fun handshake(): String {
            val line = encodeHello2(hello())
            send(line)
            send(encodeProof(Proof(identity.sign(helloChallengeBytes(challenge())))))
            return line
        }
    }

    /**
     * BS-09 impersonation: a peer presents key K but claims the `PeerId`
     * derived from a *different* key K'. Refused `ID_MISMATCH` with a record
     * naming both ids, and — the substance of `[DSC1-HELLO-13]` — refused
     * before any ingress exists: nothing this attacker sends afterward can be
     * routed anywhere, and no announcement can have been accepted from it.
     */
    @Test
    fun `BS-09 impersonation is refused ID_MISMATCH before any ingress exists, naming both ids`() {
        val local = Stack(identity("bs09-local"))
        val attacker = identity("bs09-attacker")
        val victim = identity("bs09-victim")

        val session = WsTransport.Session(local.side, send = {}, refuse = {})
        val peer = Peer(session, attacker)
        peer.open()

        // presents `attacker`'s key, claims `victim`'s id
        peer.send(encodeHello2(peer.hello(claimedBy = victim)))

        val denial = requireNotNull(session.lastAdmissionDenial) { "the impersonation was not accounted at all" }
        denial.reason shouldBe DenialReason.ID_MISMATCH
        denial.principal shouldBe victim.peerId
        val detail = requireNotNull(denial.detail) { "an ID_MISMATCH record must name both ids" }
        detail shouldContain victim.peerId.name // claimed
        detail shouldContain attacker.peerId.name // derived from the presented key

        // never admitted: no bind, no ingress, no announcer — bindAndAnnounce
        // and the achieved-level write happen only on a path that admitted
        session.achievedAuthLevel.shouldBeNull()

        // BEFORE any ingress exists: a frame arriving after the refusal has
        // nowhere to route and is dropped and counted, never delivered —
        // proof that no ingress was ever installed on this connection instance
        session.preHelloDrops shouldBe 0L
        session.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        session.preHelloDrops shouldBe 1L
    }

    /**
     * BS-10 forged signature: a well-formed public key with a signature it
     * could not have produced — refused `BAD_SIGNATURE` (`[DSC1-HELLO-07]`).
     * Both of the bead's stated routes to a bad signature: signed with a
     * different private key than the one presented, and a genuine signature
     * corrupted after the fact.
     */
    @Test
    fun `BS-10 a proof this peer could not have produced is refused BAD_SIGNATURE`() {
        val local = Stack(identity("bs10-local"))
        val genuine = identity("bs10-genuine")
        val impostor = identity("bs10-impostor")

        // -- signed with a different private key than the one HELLO2 presented --
        run {
            val session = WsTransport.Session(local.side, send = {}, refuse = {}, sendText = {})
            val peer = Peer(session, genuine)
            peer.open()
            peer.send(encodeHello2(peer.hello()))
            peer.send(encodeProof(Proof(impostor.sign(helloChallengeBytes(peer.challenge())))))

            val denial = requireNotNull(session.lastAdmissionDenial) { "the forged proof was not accounted at all" }
            denial.reason shouldBe DenialReason.BAD_SIGNATURE
            denial.principal shouldBe genuine.peerId
            session.achievedAuthLevel.shouldBeNull()
        }

        // -- a genuine signature, corrupted after the fact --
        run {
            val session = WsTransport.Session(local.side, send = {}, refuse = {}, sendText = {})
            val peer = Peer(session, genuine)
            peer.open()
            peer.send(encodeHello2(peer.hello()))
            val real = genuine.sign(helloChallengeBytes(peer.challenge()))
            val corrupted = real.copyOf()
            corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
            peer.send(encodeProof(Proof(corrupted)))

            val denial = requireNotNull(session.lastAdmissionDenial) { "the corrupted proof was not accounted at all" }
            denial.reason shouldBe DenialReason.BAD_SIGNATURE
            denial.principal shouldBe genuine.peerId
            session.achievedAuthLevel.shouldBeNull()
        }
    }

    /**
     * BS-11 downgrade: a side configured `RequireAuthenticated` receives a
     * legacy name-only hello — refused `AUTH_REQUIRED`
     * (`[DSC1-HELLO-08..09]`), and that reason is distinct from the
     * `NOT_ADMITTED` an allowlist refusal produces on the very same sink. No
     * peer reaches `TransportVouched` on either connection.
     */
    @Test
    fun `BS-11 downgrade is refused AUTH_REQUIRED, distinct from an allowlist NOT_ADMITTED on the same sink`() {
        val sink = BoundaryDenials().sinkFor("hello")
        val requiring = Stack(identity("bs11-requiring"))
        val openWithAllowlist = Stack(
            identity("bs11-open-allowlisted"),
            allow = setOf(PeerId("somebody-else")),
            auth = PeerAuthPolicy.Open,
        )

        val downgradeSession = WsTransport.Session(requiring.side, send = {}, refuse = {}, admissionSink = sink)
        downgradeSession.onText("HELLO ${UUID.randomUUID()} mallory")
        val downgrade = requireNotNull(downgradeSession.lastAdmissionDenial) { "the downgrade was not accounted at all" }
        downgrade.reason shouldBe DenialReason.AUTH_REQUIRED
        downgradeSession.achievedAuthLevel.shouldBeNull()

        val allowlistSession = WsTransport.Session(openWithAllowlist.side, send = {}, refuse = {}, admissionSink = sink)
        allowlistSession.onText("HELLO ${UUID.randomUUID()} mallory")
        val allowlistDenial = requireNotNull(allowlistSession.lastAdmissionDenial) {
            "the allowlist refusal was not accounted at all"
        }
        allowlistDenial.reason shouldBe DenialReason.NOT_ADMITTED
        allowlistSession.achievedAuthLevel.shouldBeNull()

        // machine-distinguishable, on the SAME sink
        (downgrade.reason == allowlistDenial.reason) shouldBe false
        sink.denialCount shouldBe 2L
    }

    /**
     * BS-12 replayed hello: capture an accepted hello's bytes and replay them
     * on a NEW connection instance, inside the retention window — refused
     * `REPLAY`, not `BAD_SIGNATURE` (`[DSC1-HELLO-11]`). The default window is
     * 600s, comfortably wide enough that no clock manipulation is needed here
     * (eviction itself is `HelloProtocolTest`'s scope, via an injected clock).
     *
     * Replaying the bare `HELLO2` line is enough to discriminate the two
     * reasons: the nonce-replay check runs *before* any signature work, so
     * this can never even reach a verifier — which is the property under
     * test, not an artifact of what this scenario happens to send.
     */
    @Test
    fun `BS-12 a replayed hello is refused REPLAY, not BAD_SIGNATURE`() {
        val local = Stack(identity("bs12-local"))
        val remote = identity("bs12-remote")
        val guard = HelloReplayGuard()

        fun session() = WsTransport.Session(local.side, send = {}, refuse = {}, sendText = {}, replayGuard = guard)

        val accepting = session()
        val accepted = Peer(accepting, remote)
        accepted.open()
        val acceptedHelloLine = accepted.handshake()
        accepting.achievedAuthLevel shouldBe AuthLevel.Authenticated
        accepting.lastAdmissionDenial.shouldBeNull()

        // a NEW connection instance, replaying the exact accepted HELLO2 bytes
        val replaySession = session()
        replaySession.onText(acceptedHelloLine)

        val denial = requireNotNull(replaySession.lastAdmissionDenial) { "the replay was not accounted at all" }
        denial.reason shouldBe DenialReason.REPLAY
        denial.principal shouldBe remote.peerId
        replaySession.achievedAuthLevel.shouldBeNull()
    }
}
