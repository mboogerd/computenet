package civictech.wire

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.wire.DEFAULT_NONCE_RETENTION_MILLIS
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.Ed25519
import civictech.identity.PeerIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The DSC1 hello grammar, challenge encoding, nonce properties and replay
 * window — the pure half of feature `computenet-ssa.2`
 * (task `computenet-ssa.2.1`).
 *
 * Every case here is a *pure* one: no socket, no `WsTransport.Session`, no
 * admission decision. The socket-level handshake and its adversarial suite are
 * the two follow-up items under the same feature; what this file pins is that
 * the pieces they compose are unambiguous, deterministic, role-asymmetric and
 * bounded before anything wires them to a connection.
 *
 * Deterministic keypairs come from `DeterministicKeySource` (test-only by
 * construction), so a failure here is reproducible rather than a fresh key
 * every run.
 */
class HelloProtocolTest {

    private val identityA = PeerIdentity(DeterministicKeySource.keyPairFromSeed("hello-protocol-A".toByteArray()))
    private val identityB = PeerIdentity(DeterministicKeySource.keyPairFromSeed("hello-protocol-B".toByteArray()))

    private val mirrorA: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val mirrorB: UUID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")

    private val nonceA = ByteArray(HELLO_NONCE_BYTES) { it.toByte() }
    private val nonceB = ByteArray(HELLO_NONCE_BYTES) { (200 - it).toByte() }

    private fun helloFromA() = Hello2(
        mirrorRef = mirrorA,
        claimedPeerId = identityA.peerId,
        publicKeySpki = identityA.publicKey.encoded,
        nonce = nonceA,
    )

    /** A's challenge: A signs, B verifies. See [HelloChallenge] for the field order. */
    private fun challengeSignedByA() = HelloChallenge(
        signerPeerId = identityA.peerId,
        verifierPeerId = identityB.peerId,
        verifierNonce = nonceB,
        signerNonce = nonceA,
        signerMirrorRef = mirrorA,
        verifierMirrorRef = mirrorB,
    )

    private fun <T> HelloParse<T>.ok(): T {
        check(this is HelloParse.Ok) { "expected a parsed message, got $this" }
        return message
    }

    private fun HelloParse<*>.malformation(): HelloMalformation {
        check(this is HelloParse.Malformed) { "expected a malformed result, got $this" }
        return kind
    }

    // ------------------------------------------------------------------
    // (1) round trip
    // ------------------------------------------------------------------

    @Test
    fun `HELLO2 encodes and parses back to exactly the same message`() {
        val hello = helloFromA()
        val line = encodeHello2(hello)

        line.startsWith(HELLO2_PREFIX) shouldBe true
        line.split(" ").size shouldBe HELLO2_TOKEN_COUNT + 1 // the prefix token plus four fields

        val parsed = parseHello2(line).ok()
        parsed shouldBe hello
        parsed.mirrorRef shouldBe mirrorA
        parsed.claimedPeerId shouldBe identityA.peerId
        parsed.publicKeySpki.contentEquals(identityA.publicKey.encoded) shouldBe true
        parsed.nonce.contentEquals(nonceA) shouldBe true

        // Re-encoding a parsed line reproduces it byte for byte: the encoding is
        // canonical, so there is no second spelling of the same hello.
        encodeHello2(parsed) shouldBe line
    }

    @Test
    fun `PROOF encodes and parses back to exactly the same signature`() {
        val proof = Proof(identityA.sign(helloChallengeBytes(challengeSignedByA())))
        val line = encodeProof(proof)

        line.startsWith(PROOF_PREFIX) shouldBe true
        val parsed = parseProof(line).ok()
        parsed shouldBe proof
        parsed.signature.contentEquals(proof.signature) shouldBe true
        encodeProof(parsed) shouldBe line
    }

    // ------------------------------------------------------------------
    // (2) malformed input never becomes a PeerId
    // ------------------------------------------------------------------

    @Test
    fun `a HELLO2 with the wrong token count is malformed, never a PeerId`() {
        val hello = helloFromA()
        val line = encodeHello2(hello)
        val fields = line.substring(HELLO2_PREFIX.length).split(" ")

        // Too few tokens.
        parseHello2(HELLO2_PREFIX + fields.take(3).joinToString(" "))
            .malformation() shouldBe HelloMalformation.WRONG_TOKEN_COUNT
        // Extra trailing token — the legacy grammar's failure mode, an error here.
        parseHello2("$line extra-token").malformation() shouldBe HelloMalformation.WRONG_TOKEN_COUNT
        // A bare trailing space is an extra (empty) token, not something to trim away.
        parseHello2("$line ").malformation() shouldBe HelloMalformation.WRONG_TOKEN_COUNT
        // A doubled separator likewise.
        parseHello2(line.replaceFirst(" ", "  ")).malformation() shouldBe HelloMalformation.WRONG_TOKEN_COUNT

        // None of the above produced a message at all, so no PeerId was minted.
        listOf(
            HELLO2_PREFIX + fields.take(3).joinToString(" "),
            "$line extra-token",
            "$line ",
            line.replaceFirst(" ", "  "),
        ).forEach { (parseHello2(it) is HelloParse.Malformed) shouldBe true }
    }

    @Test
    fun `a HELLO2 with an undecodable field is malformed, each kind distinguishable`() {
        val hello = helloFromA()
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hello.publicKeySpki)
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hello.nonce)
        val id = identityA.peerId.name

        fun line(mirror: String = mirrorA.toString(), claimed: String = id, k: String = key, n: String = nonce) =
            "$HELLO2_PREFIX$mirror $claimed $k $n"

        parseHello2(line(mirror = "not-a-uuid")).malformation() shouldBe
            HelloMalformation.UNDECODABLE_MIRROR_REF
        parseHello2(line(claimed = "jvm-a")).malformation() shouldBe
            HelloMalformation.CLAIMED_ID_NOT_KEY_DERIVED
        // Right prefix, wrong length: still not a fingerprint.
        parseHello2(line(claimed = "ed25519:too-short")).malformation() shouldBe
            HelloMalformation.CLAIMED_ID_NOT_KEY_DERIVED
        parseHello2(line(k = "n0t-base64!!")).malformation() shouldBe
            HelloMalformation.UNDECODABLE_PUBLIC_KEY
        parseHello2(line(k = "")).malformation() shouldBe
            HelloMalformation.UNDECODABLE_PUBLIC_KEY
        parseHello2(line(n = "!!!!")).malformation() shouldBe
            HelloMalformation.UNDECODABLE_NONCE
        // Decodable but below [DSC1-HELLO-01]'s 128-bit floor.
        parseHello2(line(n = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8))))
            .malformation() shouldBe HelloMalformation.NONCE_TOO_SHORT

        // Every malformation maps to one machine-readable denial reason.
        HelloParse.Malformed(HelloMalformation.UNDECODABLE_NONCE, "x").reason shouldBe
            DenialReason.MALFORMED_HELLO
    }

    @Test
    fun `PROOF rejects the wrong prefix, the wrong token count and an undecodable signature`() {
        parseProof(encodeHello2(helloFromA())).malformation() shouldBe HelloMalformation.NOT_PROOF
        parseProof("PROOF aGVsbG8 extra").malformation() shouldBe HelloMalformation.WRONG_TOKEN_COUNT
        parseProof("PROOF !!!!").malformation() shouldBe HelloMalformation.UNDECODABLE_SIGNATURE
        parseProof(PROOF_PREFIX).malformation() shouldBe HelloMalformation.UNDECODABLE_SIGNATURE
    }

    // ------------------------------------------------------------------
    // (3) the literal legacy-collision proof (epic section 9.1)
    // ------------------------------------------------------------------

    @Test
    fun `no encoded line can be misparsed by the legacy HELLO parser`() {
        // The frozen legacy prefix, trailing space included — mirrored from
        // WsTransport's private constant, which these bytes may never change.
        LEGACY_HELLO_PREFIX shouldBe "HELLO "

        val hello2 = encodeHello2(helloFromA())
        val proof = encodeProof(Proof(identityA.sign(helloChallengeBytes(challengeSignedByA()))))

        // startsWith("HELLO ") is false because the sixth character is '2' (or 'F'),
        // not a space — so the legacy `require` throws instead of splitting.
        hello2.startsWith(LEGACY_HELLO_PREFIX) shouldBe false
        proof.startsWith(LEGACY_HELLO_PREFIX) shouldBe false
        helloBytesCannotBeMisparsedByLegacy(hello2) shouldBe true
        helloBytesCannotBeMisparsedByLegacy(proof) shouldBe true

        // And the other direction: a legacy hello is refused, never downgraded.
        parseHello2("HELLO $mirrorA jvm-a").malformation() shouldBe HelloMalformation.NOT_HELLO2
        parseHello2("HELLO $mirrorA").malformation() shouldBe HelloMalformation.NOT_HELLO2
    }

    @Test
    fun `the hazard this grammar avoids is real - the legacy parse shape absorbs extra tokens into the name`() {
        // Non-vacuity for the test above: it is only worth pinning because
        // appending to the legacy line really does corrupt identity. This
        // reproduces WsTransport's own parse shape, `removePrefix(HELLO).trim()
        // .split(" ", limit = 2)`, on a line with one extra token.
        val appended = "HELLO $mirrorA jvm-a AAAA-key-material"
        val parts = appended.removePrefix(LEGACY_HELLO_PREFIX).trim().split(" ", limit = 2)
        val absorbedName = PeerId(parts[1])

        // A wrong PeerId, silently, with no error anywhere — which is exactly why
        // HELLO2 is a new message form rather than extra tokens on this line.
        absorbedName shouldBe PeerId("jvm-a AAAA-key-material")
        (absorbedName == PeerId("jvm-a")) shouldBe false
    }

    // ------------------------------------------------------------------
    // (4) challenge bytes: deterministic and role-asymmetric
    // ------------------------------------------------------------------

    @Test
    fun `challenge bytes are deterministic for equal inputs`() {
        val first = helloChallengeBytes(challengeSignedByA())
        val second = helloChallengeBytes(challengeSignedByA())
        first.contentEquals(second) shouldBe true

        // And sensitive to every field: changing any one of the six changes them.
        val base = challengeSignedByA()
        val perturbations = listOf(
            HelloChallenge(identityB.peerId, base.verifierPeerId, base.verifierNonce, base.signerNonce, base.signerMirrorRef, base.verifierMirrorRef),
            HelloChallenge(base.signerPeerId, identityA.peerId, base.verifierNonce, base.signerNonce, base.signerMirrorRef, base.verifierMirrorRef),
            HelloChallenge(base.signerPeerId, base.verifierPeerId, nonceA, base.signerNonce, base.signerMirrorRef, base.verifierMirrorRef),
            HelloChallenge(base.signerPeerId, base.verifierPeerId, base.verifierNonce, nonceB, base.signerMirrorRef, base.verifierMirrorRef),
            HelloChallenge(base.signerPeerId, base.verifierPeerId, base.verifierNonce, base.signerNonce, mirrorB, base.verifierMirrorRef),
            HelloChallenge(base.signerPeerId, base.verifierPeerId, base.verifierNonce, base.signerNonce, base.signerMirrorRef, mirrorA),
        )
        perturbations.forEach { helloChallengeBytes(it).contentEquals(first) shouldBe false }
    }

    @Test
    fun `challenge bytes are role-asymmetric, so a reflected PROOF cannot verify`() {
        val aSigns = challengeSignedByA()
        val bSigns = aSigns.mirrored()

        val aBytes = helloChallengeBytes(aSigns)
        val bBytes = helloChallengeBytes(bSigns)
        aBytes.contentEquals(bBytes) shouldBe false

        // Mirroring is an involution: B's view of A's challenge is A's challenge.
        helloChallengeBytes(bSigns.mirrored()).contentEquals(aBytes) shouldBe true

        // The security consequence, end to end: A's own proof, echoed straight
        // back at A, does not verify as B's proof.
        val aProof = identityA.sign(aBytes)
        Ed25519.verify(identityA.publicKey, aBytes, aProof) shouldBe true
        Ed25519.verify(identityA.publicKey, bBytes, aProof) shouldBe false
    }

    @Test
    fun `the challenge bytes open with the versioned domain-separation tag`() {
        // The tag is what makes a hello challenge unable to be a valid
        // announcement signing input under the same keypair
        // (`civictech.identity.announce.canonicalBytes`, whose first field is a
        // length-prefixed minting peer id — a 51-character `ed25519:`
        // fingerprint, never this 34-byte string). Added in review: nothing else
        // in this file notices the tag's removal, so deleting it silently
        // reopened that cross-protocol question with a green suite. Pinned
        // here as a *prefix*, deliberately not as a whole-message golden
        // vector: the six fields behind it stay free to gain a field, while
        // dropping or renaming the tag reddens a build.
        val tag = "computenet/DSC1/hello-challenge/v1".toByteArray(Charsets.UTF_8)
        tag.size shouldBe 34

        // Inside the signed region, first, and length-prefixed like every other
        // variable-width field — so it cannot run together with the id after it.
        val expectedPrefix = byteArrayOf(0, 0, 0, tag.size.toByte()) + tag
        val bytes = helloChallengeBytes(challengeSignedByA())
        bytes.copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix) shouldBe true

        // Every challenge carries it, whichever role signs.
        helloChallengeBytes(challengeSignedByA().mirrored())
            .copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix) shouldBe true
    }

    @Test
    fun `an unpaired surrogate in a peer id name is refused rather than encoded`() {
        // computenet-9qgg's discipline: String -> UTF-8 maps an unpaired
        // surrogate to '?', which would collide distinct names into one
        // signature. Fail closed instead.
        listOf("\uD800", "\uDC00", "ed25519:\uD800").forEach { name ->
            shouldThrow<IllegalArgumentException> {
                helloChallengeBytes(
                    HelloChallenge(PeerId(name), identityB.peerId, nonceB, nonceA, mirrorA, mirrorB),
                )
            }
            shouldThrow<IllegalArgumentException> {
                helloChallengeBytes(
                    HelloChallenge(identityA.peerId, PeerId(name), nonceB, nonceA, mirrorA, mirrorB),
                )
            }
        }
        // A well-formed surrogate pair is fine — the check rejects ill-formed
        // UTF-16, not non-ASCII text.
        helloChallengeBytes(
            HelloChallenge(PeerId("😀"), identityB.peerId, nonceB, nonceA, mirrorA, mirrorB),
        ).isNotEmpty() shouldBe true
    }

    // ------------------------------------------------------------------
    // (5) nonces
    // ------------------------------------------------------------------

    @Test
    fun `generated nonces are 32 bytes and distinct across consecutive calls`() {
        generateHelloNonce().size shouldBe HELLO_NONCE_BYTES
        HELLO_NONCE_BYTES shouldBe 32
        // 256 bits, comfortably above [DSC1-HELLO-01]'s 128-bit floor.
        (HELLO_NONCE_BYTES * 8 >= 128) shouldBe true

        val drawn = (1..256).map { generateHelloNonce().toList() }
        drawn.toSet().size shouldBe drawn.size
        // Not a randomness test — a repetition test. A cached or reused nonce
        // ([DSC1-HELLO-02]'s failure mode) shows up here immediately.
    }

    // ------------------------------------------------------------------
    // (6) the retention window
    // ------------------------------------------------------------------

    @Test
    fun `a recorded nonce and signature are seen within the window and forgotten after it`() {
        var now = 1_000L
        val guard = HelloReplayGuard(retentionMillis = 10_000L, clock = { now })
        val peer = identityA.peerId
        val signature = identityA.sign(helloChallengeBytes(challengeSignedByA()))

        guard.hasSeenNonce(peer, nonceA) shouldBe false
        guard.hasSeenSignature(peer, signature) shouldBe false

        guard.recordAccepted(peer, nonceA, signature)
        guard.hasSeenNonce(peer, nonceA) shouldBe true
        guard.hasSeenSignature(peer, signature) shouldBe true

        // A different nonce, a different signature, and a different peer are all
        // unaffected — the state is keyed by the admitted peer.
        guard.hasSeenNonce(peer, nonceB) shouldBe false
        guard.hasSeenSignature(peer, identityB.sign(byteArrayOf(1))) shouldBe false
        guard.hasSeenNonce(identityB.peerId, nonceA) shouldBe false

        // At exactly the window the entry is still remembered; one millisecond
        // later it is evicted, and the peer's slot goes with it.
        now += 10_000L
        guard.hasSeenNonce(peer, nonceA) shouldBe true
        now += 1L
        guard.hasSeenNonce(peer, nonceA) shouldBe false
        guard.hasSeenSignature(peer, signature) shouldBe false
        guard.retainedEntries(peer) shouldBe 0
        guard.retainedPeers() shouldBe 0
    }

    @Test
    fun `a flood of accepted hellos holds at most 64 entries per peer`() {
        var now = 1_000L
        val guard = HelloReplayGuard(retentionMillis = 10_000L, clock = { now })
        val peer = identityA.peerId

        // Injective in `i` on purpose: a generator like `{ (i + it).toByte() }`
        // silently collides i and i+256, which would make the eviction
        // assertions below mean something other than what they say.
        fun distinct(size: Int, i: Int) = ByteArray(size).also { bytes ->
            bytes[0] = (i ushr 24).toByte()
            bytes[1] = (i ushr 16).toByte()
            bytes[2] = (i ushr 8).toByte()
            bytes[3] = i.toByte()
        }

        fun nonceOf(i: Int) = distinct(HELLO_NONCE_BYTES, i)
        fun signatureOf(i: Int) = distinct(Ed25519.SIGNATURE_LENGTH, i)

        repeat(500) { i ->
            guard.recordAccepted(peer, nonceOf(i), signatureOf(i))
            now += 1L // still well inside the window, so nothing expires
        }

        guard.retainedEntries(peer) shouldBe HelloReplayGuard.MAX_ENTRIES_PER_PEER
        HelloReplayGuard.MAX_ENTRIES_PER_PEER shouldBe 64
        guard.retainedPeers() shouldBe 1

        // Oldest evicted first: the newest 64 are still detected as replays, the
        // oldest are not. That is the documented trade — bounded memory over
        // unbounded detection — not an accident.
        guard.hasSeenNonce(peer, nonceOf(499)) shouldBe true
        guard.hasSeenSignature(peer, signatureOf(499)) shouldBe true
        guard.hasSeenNonce(peer, nonceOf(500 - HelloReplayGuard.MAX_ENTRIES_PER_PEER)) shouldBe true
        guard.hasSeenNonce(peer, nonceOf(0)) shouldBe false

        // Per-peer, not global: a second peer gets its own 64.
        repeat(500) { i -> guard.recordAccepted(identityB.peerId, nonceOf(i), signatureOf(i)) }
        guard.retainedEntries(identityB.peerId) shouldBe HelloReplayGuard.MAX_ENTRIES_PER_PEER
        guard.retainedEntries(peer) shouldBe HelloReplayGuard.MAX_ENTRIES_PER_PEER
        guard.retainedPeers() shouldBe 2
    }

    @Test
    fun `the guard refuses a non-positive retention window`() {
        shouldThrow<IllegalArgumentException> { HelloReplayGuard(retentionMillis = 0L) }
        shouldThrow<IllegalArgumentException> { HelloReplayGuard(retentionMillis = -1L) }
    }

    // ------------------------------------------------------------------
    // Kernel vocabulary: Side defaults, the construction-time precondition,
    // and the new denial reasons.
    // ------------------------------------------------------------------

    @Test
    fun `a Side constructed without auth or credentials keeps today's behaviour`() {
        val side = Peering.Side(LocationRegistry(), ManagedHost())
        side.auth shouldBe PeerAuthPolicy.Open
        side.credentials shouldBe null
        // Unchanged admission: no allowlist means everything is admitted.
        side.admits(null) shouldBe true
        side.admits(PeerId("jvm-a")) shouldBe true
    }

    @Test
    fun `RequireAuthenticated without credentials fails at construction, not at hello time`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            Peering.Side(
                LocationRegistry(),
                ManagedHost(),
                auth = PeerAuthPolicy.RequireAuthenticated(),
            )
        }
        thrown.message!! shouldContain "RequireAuthenticated needs credentials"

        // With credentials it constructs, and the side carries the derived id.
        val side = Peering.Side(
            LocationRegistry(),
            ManagedHost(),
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identityA.asPeerCredentials(),
        )
        side.credentials!!.peerId shouldBe identityA.peerId
        (side.auth as PeerAuthPolicy.RequireAuthenticated).nonceRetentionMillis shouldBe
            DEFAULT_NONCE_RETENTION_MILLIS

        // Open with credentials is legal too: a keypair is configuration, not a
        // policy, which is what an in-process loopback needs.
        Peering.Side(
            LocationRegistry(),
            ManagedHost(),
            credentials = identityA.asPeerCredentials(),
        ).auth shouldBe PeerAuthPolicy.Open
    }

    @Test
    fun `the default retention window is ten minutes`() {
        DEFAULT_NONCE_RETENTION_MILLIS shouldBe 600_000L
        PeerAuthPolicy.RequireAuthenticated().nonceRetentionMillis shouldBe 600_000L
        PeerAuthPolicy.RequireAuthenticated(nonceRetentionMillis = 5L).nonceRetentionMillis shouldBe 5L
    }

    @Test
    fun `the seam-1 hello denial reasons exist and are distinct from the allowlist refusal`() {
        DenialReason.entries.map { it.name } shouldContainAll
            listOf("AUTH_REQUIRED", "ID_MISMATCH", "MALFORMED_HELLO")
        // The distinction [DSC1-HELLO-08] demands: a downgrade attempt is not an
        // allowlist refusal.
        (DenialReason.AUTH_REQUIRED == DenialReason.NOT_ADMITTED) shouldBe false
        (DenialReason.ID_MISMATCH == DenialReason.BAD_SIGNATURE) shouldBe false
    }

    @Test
    fun `PeerIdentity adapts to PeerCredentials without exposing key material`() {
        val credentials = identityA.asPeerCredentials()

        credentials.peerId shouldBe identityA.peerId
        credentials.publicKey.contentEquals(identityA.publicKey.encoded) shouldBe true

        // A fresh copy per read: mutating what a caller got back cannot perturb
        // the identity behind it.
        credentials.publicKey.also { it[0] = 0 }
        credentials.publicKey.contentEquals(identityA.publicKey.encoded) shouldBe true

        // Signs exactly the bytes it is given, verifiable by the wrapped identity.
        val message = helloChallengeBytes(challengeSignedByA())
        identityA.verify(message, credentials.sign(message)) shouldBe true

        credentials.toString() shouldBe "PeerIdentityCredentials(peerId=${identityA.peerId.name})"
    }

    @Test
    fun `a claimed id is only accepted in the fixed key-derived form`() {
        isKeyDerivedPeerIdForm(identityA.peerId.name) shouldBe true
        isKeyDerivedPeerIdForm(identityB.peerId.name) shouldBe true
        isKeyDerivedPeerIdForm("jvm-a") shouldBe false
        isKeyDerivedPeerIdForm("") shouldBe false
        isKeyDerivedPeerIdForm("ed25519:") shouldBe false
        // Right length, wrong alphabet: '+' and '/' are base64, not base64url.
        isKeyDerivedPeerIdForm("ed25519:" + "+".repeat(43)) shouldBe false
        // Right length and alphabet, wrong prefix.
        isKeyDerivedPeerIdForm("ed25518:" + identityA.peerId.name.substringAfter("ed25519:")) shouldBe false
    }
}
