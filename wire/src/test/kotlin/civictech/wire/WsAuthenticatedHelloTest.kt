package civictech.wire

import civictech.cell.BoundaryDenial
import civictech.cell.BoundaryDenials
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.membrane.AuthLevel
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.identity.FilePeerKeyStore
import civictech.identity.PeerIdentity
import civictech.identity.fingerprint
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * BS-01 and the hello path's refusal taxonomy: the authenticated hello working
 * end to end over a real socket, and every class of refusal it can take,
 * accounted before the connection closes (DSC1, `[DSC1-HELLO-01..04, 06..13]`,
 * `[DSC1-OBS-01, 04..05]`).
 *
 * ## What is here and what is deliberately elsewhere
 *
 * This is the **integration** side of the feature: the positive exchange
 * (BS-01), the freshness of a re-hello's nonce, a reconnect that
 * re-authenticates rather than tripping this side's own replay memory, and the
 * *accounting* contract — that each refusal class arrives at the admission sink
 * with a machine-distinguishable [DenialReason], the id it is attributed to, and
 * a detail string carrying no secret.
 *
 * The **adversarial** scenarios BS-09 (impersonation), BS-10 (forgery), BS-11
 * (downgrade), BS-12 (replay) and BS-14 (allowlist on the derived id), plus the
 * §9.1 mixed-version proofs in both directions, are a sibling item's named tests
 * in their own files. The taxonomy test below overlaps them only in that it has
 * to *produce* a refusal of each class in order to read its record: what it
 * asserts is the record, not the security property.
 *
 * ## Why a `Session` is driven directly for the taxonomy
 *
 * A hostile peer is easier to *be* than to build: the refusal cases hand
 * `Session.onText` the exact line a peer would have sent, playing the remote role
 * with keys this test holds. That is the same instrument
 * [WsTransportPreHelloDropTest] and `WsAnnouncementSilenceInventoryTest` already
 * use, and it is what makes the `PROOF`-stage cases (a forged signature, a
 * replayed proof) reachable at all without a second implementation of the
 * protocol. The positive cases go over a real socket, where nothing is posed.
 *
 * `:wire` carries no `:testkit` dependency, so the `Stack`/`await` scaffolding is
 * hand-rolled as in [WsPeerIdentityTest] and [WsAdmissionDenialTest].
 */
class WsAuthenticatedHelloTest {

    @TempDir
    lateinit var keyDirs: Path

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    /**
     * A peering side holding a **persisted** keypair — read back from disk
     * through [FilePeerKeyStore], which is the shape BS-01 names, rather than a
     * keypair minted in memory for the occasion.
     *
     * `Peering.Side.peer` is left null on purpose. An authenticated peering names
     * peers by fingerprint and by nothing else, so leaving the asserted name
     * absent is what makes "the bound id came from the key" a claim this test can
     * hold: if anything supplied the id from configuration instead, the
     * assertions below would read null.
     */
    private inner class Stack(
        name: String,
        allow: Set<KeyId>? = null,
    ) {
        val identity: PeerIdentity = FilePeerKeyStore(keyDirs.resolve(name)).loadOrGenerate()
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            allow = allow,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            announcementSigning = socketAnnouncementSigning(),
            announcementVerification = socketAnnouncementVerification(),
        )
    }

    // Same shape and deadline as WsPeerIdentityTest.await: every condition
    // returns the instant it holds on a healthy machine, so a generous deadline
    // costs nothing except when the test is already failing.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    private fun LocationRegistry.remote(ref: CellRef): LocationRegistry.Remote =
        location(ref) as LocationRegistry.Remote

    /**
     * BS-01, over a real socket: two peers each holding a persisted Ed25519
     * keypair, both sides `RequireAuthenticated`, complete the hello exchange —
     * both admit, each binds the id **derived from the other's public key**, and
     * both connections report `AuthLevel.Authenticated`
     * (`[DSC1-HELLO-01, 03..04]`).
     *
     * The identity assertion is made twice on purpose: once against the
     * admitting side's own `PeerIdentityBinding` applied to
     * `fingerprint(otherKey)` — the derivation the requirement names, now split
     * into "fingerprint the key, resolve the key identifier" (feature
     * `computenet-376c`) — and once against the `PeerId` the other side's key
     * store minted, the same value reached independently. With `Side.peer` null
     * on both sides (see [Stack]), nothing else in the system could have
     * produced it.
     */
    @Test
    fun `two keyholding peers complete the hello exchange and bind each other's key-derived id at Authenticated`() {
        val server = Stack("bs01-server")
        val client = Stack("bs01-client")

        // published before anyone connects, so each side's first announcement is
        // `announceTo`'s catch-up burst — the earliest announcement that exists
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)
        val writer = CollectingCell()
        client.host.managementInlet.call.spawn(writer)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("the dialer learned the listener's collector") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            await("the listener learned the dialer's writer") {
                server.registry.location(writer.ref) is LocationRegistry.Remote
            }
            await("both sides reported Authenticated") {
                connection.achievedAuthLevel == AuthLevel.Authenticated &&
                    listener.achievedAuthLevels == listOf(AuthLevel.Authenticated)
            }

            // the bound id is what the ADMITTING side's binding resolves the
            // fingerprint of the presented key to — `.peer` is a PeerId and
            // `fingerprint` now returns a KeyId, so the two are compared where
            // the transport actually joins them ...
            client.registry.remote(collector.ref).peer shouldBe
                client.side.identityBinding.identityOf(fingerprint(server.identity.publicKey))
            server.registry.remote(writer.ref).peer shouldBe
                server.side.identityBinding.identityOf(fingerprint(client.identity.publicKey))
            // ... reached independently as the id each key store minted ...
            client.registry.remote(collector.ref).peer shouldBe server.identity.peerId
            server.registry.remote(writer.ref).peer shouldBe client.identity.peerId
            // ... and no configured name could have supplied it
            server.side.peer.shouldBeNull()
            client.side.peer.shouldBeNull()

            // admission, not refusal: nothing was denied on either side
            listener.admissionDenialCount shouldBe 0L
            connection.admissionDenialCount shouldBe 0L
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * `[DSC1-HELLO-02]`: every hello carries a distinct fresh nonce, *including
     * each re-hello after a reconnect*.
     *
     * Driven on a retained `Session` because that is precisely the shape the
     * requirement is about: `WsConnection` keeps **one** Session for its whole
     * life and calls `hello()` once per open, so two `hello()` calls on one
     * Session are literally what a reconnect does. A nonce fresh per *Session*
     * rather than per *open* would pass a socket test that never reconnected.
     */
    @Test
    fun `a re-hello on a retained client Session carries a nonce distinct from the previous hello's`() {
        val stack = Stack("nonce-freshness")
        val session = WsTransport.Session(stack.side, send = {}, refuse = {})

        val first = (parseHello2(session.hello()) as HelloParse.Ok).message
        val second = (parseHello2(session.hello()) as HelloParse.Ok).message

        first.nonce.size shouldBe HELLO_NONCE_BYTES
        second.nonce.size shouldBe HELLO_NONCE_BYTES
        second.nonce.toList() shouldNotBe first.nonce.toList()
        // the identity is the same across both opens — only the challenge and
        // the per-open mirror ref changed
        first.claimedPeerId shouldBe stack.identity.peerId
        second.claimedPeerId shouldBe stack.identity.peerId
        second.mirrorRef shouldNotBe first.mirrorRef
    }

    /**
     * A reconnect re-runs the whole exchange and is admitted again — the
     * listener's replay memory, shared across every connection it accepts, must
     * not mistake a legitimate re-hello for a replay. `[DSC1-HELLO-02]` is what
     * makes that true: the returning dialer's nonce is fresh, so the guard has
     * never seen it.
     *
     * The re-mirroring through a *different* egress is asserted as part of the
     * condition, as in [WsPeerIdentityTest]: without it the test could pass on a
     * connection that never actually re-formed.
     */
    @Test
    fun `a reconnect re-authenticates instead of tripping the listener's replay memory`() {
        val server = Stack("reconnect-server")
        val client = Stack("reconnect-client")
        val writer = CollectingCell()
        client.host.managementInlet.call.spawn(writer)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("the listener learned the dialer's writer") {
                server.registry.location(writer.ref) is LocationRegistry.Remote
            }
            val egressBefore = server.registry.remote(writer.ref).sink

            // drop the socket from the listener side; the dialer's backoff loop
            // reconnects on its own into a brand-new listener Session
            listener.connections.forEach { it.close() }

            await("the listener re-admitted the returning dialer through a new session, at Authenticated") {
                (server.registry.location(writer.ref) as? LocationRegistry.Remote)
                    ?.let { it.sink !== egressBefore } == true &&
                    listener.achievedAuthLevels == listOf(AuthLevel.Authenticated) &&
                    connection.achievedAuthLevel == AuthLevel.Authenticated
            }

            server.registry.remote(writer.ref).peer shouldBe client.identity.peerId
            // the whole point: the re-hello was not refused as a replay
            listener.admissionDenialCount shouldBe 0L
            connection.admissionDenialCount shouldBe 0L
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * `[DSC1-OBS-01]`, `[DSC1-OBS-04]`, `[DSC1-OBS-05]`: a refused hello of
     * **every class** is accounted on the admission sink — with the id it is
     * attributed to and a machine-distinguishable [DenialReason] — before the
     * connection is closed; the count rises monotonically across the six; and no
     * nonce, signature byte or key material appears in any detail string or in
     * anything written to `System.err`.
     *
     * All six classes are driven against one shared admission sink, each on its
     * own connection instance, with this test playing the remote role. The
     * ordering claim ("accounted *before* the connection closes") is asserted
     * structurally rather than argued: the `refuse` lambda records the count it
     * observes, so a refusal that closed the connection first would report a
     * lower count than the record it produced.
     */
    @Test
    fun `every hello refusal class is accounted with a distinguishable reason before the connection closes`() {
        val local = Stack("taxonomy-local")
        val remote = Stack("taxonomy-remote")
        val stranger = Stack("taxonomy-stranger")
        // an allowlist naming somebody else, evaluated on the DERIVED id
        val allowlisted = Stack("taxonomy-allowlisted", allow = setOf(stranger.identity.keyId))

        // One sink for the whole test, exactly as a listener shares one across
        // every connection it accepts — otherwise a per-Session sink would reset
        // the counter and "monotonic across the classes" would be unassertable.
        val sink = BoundaryDenials().sinkFor("hello")
        // One guard likewise: REPLAY needs a second connection instance to see
        // the first one's acceptance (WsTransport.replayGuardFor).
        val guard = HelloReplayGuard()

        val observed = mutableListOf<DenialReason>()
        val secretTokens = mutableListOf<String>()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        var refusals = 0L

        fun session(side: Peering.Side, onRefuse: () -> Unit) = WsTransport.Session(
            side,
            send = {},
            refuse = onRefuse,
            admissionSink = sink,
            sendText = {},
            replayGuard = guard,
        )

        /** Drive one refusal and return its record, checking the accounting around it. */
        fun refusal(side: Peering.Side = local.side, drive: (Peer) -> Unit): BoundaryDenial {
            val countSeenByRefuse = mutableListOf<Long>()
            val session = session(side) { countSeenByRefuse += sink.denialCount }
            val peer = Peer(session, remote.identity)
            peer.open()
            secretTokens += b64.encodeToString(peer.nonce)
            secretTokens += b64.encodeToString(peer.localHello.nonce)
            drive(peer)
            val denial = requireNotNull(session.lastAdmissionDenial) { "the hello was not accounted at all" }
            // accounted BEFORE the close: refuse() already saw the increment
            countSeenByRefuse shouldBe listOf(refusals + 1)
            sink.denialCount shouldBe refusals + 1
            refusals += 1
            observed += denial.reason
            return denial
        }

        val captured = stderrWrittenByThisThread {
            // -- AUTH_REQUIRED: a legacy name-only hello at a RequireAuthenticated
            // side. Distinguishable from NOT_ADMITTED, which is the requirement.
            val downgrade = refusal { it.send("HELLO ${UUID.randomUUID()} mallory") }
            downgrade.reason shouldBe DenialReason.AUTH_REQUIRED
            downgrade.principal shouldBe PeerId("mallory")

            // -- MALFORMED_HELLO: a HELLO2 with the wrong token count. No claimed
            // id is recorded because a malformed line never yields one — the
            // grammar refuses it before any PeerId is minted.
            val malformed = refusal { it.send("${HELLO2_PREFIX}only-one-token") }
            malformed.reason shouldBe DenialReason.MALFORMED_HELLO
            malformed.principal.shouldBeNull()

            // -- ID_MISMATCH: presents `remote`'s key while claiming `stranger`'s
            // id; the record names BOTH.
            val mismatch = refusal { it.send(encodeHello2(it.hello(claimedBy = stranger.identity))) }
            mismatch.reason shouldBe DenialReason.ID_MISMATCH
            mismatch.principal shouldBe stranger.identity.peerId
            requireNotNull(mismatch.detail) shouldContain remote.identity.peerId.name

            // -- BAD_SIGNATURE: a well-formed PROOF this peer could not have produced
            val forged = refusal {
                it.send(encodeHello2(it.hello()))
                it.send(encodeProof(Proof(FORGED_SIGNATURE)))
            }
            forged.reason shouldBe DenialReason.BAD_SIGNATURE
            forged.principal shouldBe remote.identity.peerId

            // -- NOT_ADMITTED: a complete, valid exchange at a side whose
            // allowlist names somebody else
            val unlisted = refusal(side = allowlisted.side) { it.handshake() }
            unlisted.reason shouldBe DenialReason.NOT_ADMITTED
            unlisted.principal shouldBe remote.identity.peerId

            // -- REPLAY: the same HELLO2 bytes on a NEW connection instance after
            // one this side accepted. The acceptance is driven first, on a Session
            // sharing the guard, and its hello line is then replayed verbatim.
            val accepting = session(local.side) { error("the valid exchange must not be refused") }
            val accepted = Peer(accepting, remote.identity)
            accepted.open()
            secretTokens += b64.encodeToString(accepted.nonce)
            val acceptedLine = accepted.handshake()
            accepting.achievedAuthLevel shouldBe AuthLevel.Authenticated
            accepting.lastAdmissionDenial.shouldBeNull()

            val replayed = refusal { it.send(acceptedLine) }
            replayed.reason shouldBe DenialReason.REPLAY
            replayed.principal shouldBe remote.identity.peerId
        }

        // six classes, all distinct: a reader of the audit trail can tell a
        // downgrade attempt from an allowlist refusal from a forgery
        observed shouldContainExactly listOf(
            DenialReason.AUTH_REQUIRED,
            DenialReason.MALFORMED_HELLO,
            DenialReason.ID_MISMATCH,
            DenialReason.BAD_SIGNATURE,
            DenialReason.NOT_ADMITTED,
            DenialReason.REPLAY,
        )

        // [DSC1-OBS-05]: nothing secret in anything written. Checked against the
        // base64url of the actual bytes this test put on the wire — every nonce
        // (both roles), the presented key, and the forged signature — since
        // base64url is the encoding a leak would appear in.
        val forbidden = secretTokens +
            b64.encodeToString(remote.identity.publicKey.encoded) +
            b64.encodeToString(FORGED_SIGNATURE)
        forbidden.forEach { captured shouldNotContain it }
        // and non-vacuously: the lines DO name the ids, which is what makes them
        // an audit trail rather than silence
        captured shouldContain remote.identity.peerId.name
        captured shouldContain stranger.identity.peerId.name
    }

    /**
     * The remote role, played locally: encodes the hellos and proofs a peer would
     * send to [session], signing with [identity]'s private half.
     *
     * It exists so the refusal cases can be *exact* about what arrived — a forged
     * signature, an id that does not match the key, the same bytes twice — which
     * no real dialer can be made to send.
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

        /** A complete valid exchange; returns the `HELLO2` line it sent, for replaying. */
        fun handshake(): String {
            val line = encodeHello2(hello())
            send(line)
            send(encodeProof(Proof(identity.sign(helloChallengeBytes(challenge())))))
            return line
        }
    }

    private companion object {
        /** 64 bytes of a fixed value: an Ed25519-shaped signature nobody signed. */
        val FORGED_SIGNATURE = ByteArray(64) { 7 }
    }
}
