package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.InvocationSink
import civictech.identity.DeterministicKeySource
import civictech.identity.Ed25519SignatureVerifier
import civictech.identity.PeerIdentity
import civictech.identity.announce.AnnouncementSigningInput
import civictech.identity.announce.canonicalBytes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-ssa.4.3` — the **ingress admission gate**: BS-02 and BS-04..08 of
 * epic `computenet-ssa`, over `Peering`'s own composition, with **real Ed25519
 * keypairs and the real canonical announcement encoder** from `:identity`
 * (`testImplementation(project(":identity"))`, `:oracle`'s precedent;
 * `:kernel`'s main classpath is untouched, [DSC1-WIRE-04]).
 *
 * ## Why real crypto here, when the emit half deliberately faked it
 *
 * `SignedAnnouncementEmitTest` fakes signing on purpose — what it asserts is
 * *which fields entered the signed region*, and a readable transcript shows
 * that where an opaque 64-byte signature could not. This file asserts the
 * opposite half: whether a signature **verifies**, and against **which key**.
 * A fake verifier there would be a test of the fake. So every signature below
 * is a genuine Ed25519 signature over
 * `civictech.identity.announce.canonicalBytes`, and every rejection is a real
 * verification failure.
 *
 * ## What "this side requires signed announcements" means
 *
 * The presence of an injected [AnnouncementVerification] on the receiving
 * [Peering.Side], and nothing else — not `PeerAuthPolicy.RequireAuthenticated`.
 * The argument is on [AnnouncementVerification]'s KDoc; the consequence for
 * this file is that a side with no verification config is on the pre-feature
 * path and is covered by `TrustBoundaryTest`, `PeerIdentityTest` and
 * `RemoteAddressingTest` staying green unmodified.
 *
 * ## The three observables, on every negative case
 *
 * Each rejection asserts the distinguishing [DenialReason] on a `DeadLetter`,
 * **zero `LocationRegistry` change** (see [Rig.registrySnapshot] — a snapshot of
 * locations *and* topology, not merely "no dead letter was absent"), and the
 * monotonic rejected-announcement counter moving by exactly one.
 *
 * The registry clause is the one most easily satisfied vacuously, so it is
 * **mutation-checked, and here is what was measured** rather than what would be
 * convenient. Deleting the `return` after `announcementSink.deny(...)` in
 * [BridgeIngressCell] — so a refused announcement is denied *and then delivered
 * anyway* — compiles, and turns BS-04, BS-05, BS-07 and BS-08 red at exactly
 * the `registrySnapshot() shouldBe before` line while every dead-letter,
 * reason and counter assertion in the file stays green. So the registry clause
 * is not implied by the dead-letter clause; it is carrying its own weight.
 *
 * BS-06 stays **green** under that mutation, and the reason is worth knowing
 * rather than papering over: re-applying a byte-identical `published(ref)` is
 * idempotent in [LocationRegistry], so a delivered replay leaves the snapshot
 * where it already was. BS-06's registry assertion therefore pins "no
 * double-apply and no regression", which is what its clause actually says — it
 * is not, and cannot be, a check that the delivery was suppressed. The
 * suppression is pinned by the other four.
 */
class SignedAnnouncementTest {

    // ---------------------------------------------------------------- keys

    /** Deterministic keypairs — seeded, so a failure here is reproducible. */
    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    private val identityA = identity("ssa-4-3-peer-a")
    private val identityB = identity("ssa-4-3-peer-b")

    /** A second keypair claiming to be B — the "different key" half of BS-05. */
    private val impostorOfB = identity("ssa-4-3-impostor-of-b")

    private val peerA: PeerId get() = identityA.peerId
    private val peerB: PeerId get() = identityB.peerId

    /** What each receiver knows: the public half of every peer it has heard of. */
    private val directory: Map<PeerId, PublicKey>
        get() = mapOf(peerA to identityA.publicKey, peerB to identityB.publicKey)

    /**
     * A [PeerCredentials] over a real keypair. [peerId] is a *separate*
     * parameter rather than `identity.peerId` so BS-05 can build the one
     * configuration that is otherwise unreachable: a signer claiming B's name
     * while holding a key that does not derive it.
     */
    private class Keys(override val peerId: PeerId, private val identity: PeerIdentity) : PeerCredentials {
        constructor(identity: PeerIdentity) : this(identity.peerId, identity)

        override val publicKey: ByteArray = identity.publicKey.encoded
        override fun sign(message: ByteArray): ByteArray = identity.sign(message)
    }

    /** A clock a test moves by hand — nothing in this file sleeps ([DSC1-ANN-07]). */
    private class TestClock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private val senderClock = TestClock()
    private val receiverClock = TestClock()

    // ------------------------------------------------- the two injected halves

    private fun input(a: SignableAnnouncement) = AnnouncementSigningInput(
        mintingPeerId = a.mintingPeerId,
        counter = a.counter,
        notAfter = a.notAfter,
        contractId = a.contractId,
        methodId = a.methodId,
        cellRef = a.cellRef,
        portName = a.portName,
        args = a.args,
    )

    /**
     * The honest emit-side encoder: `:identity`'s canonical bytes, unmodified.
     *
     * [incarnation] defaults to `{ 0L }` — i.e. counter floor zero, the
     * pre-`computenet-ssa.6` sequence `1, 2, 3, ...` — rather than to the
     * production wall-clock default. Two reasons, both about this file's
     * subject: the counters appear verbatim in the gate's dead-letter details
     * and high-water assertions below, where `1` reads and `1782541056409600`
     * does not; and every clause this file pins is a property of the *gate*,
     * which never looks at a counter's magnitude, only at its order. The floor
     * itself is pinned by the restart case below (which names two incarnations
     * explicitly) and, for the production default, by
     * `SignedAnnouncementEmitTest`.
     */
    private fun signingConfig(
        ttlMillis: Long = 60_000L,
        encode: (SignableAnnouncement) -> ByteArray = { canonicalBytes(input(it)) },
        incarnation: () -> Long = { 0L },
    ) = AnnouncementSigningConfig(
        encode = encode,
        clock = senderClock,
        ttlMillis = ttlMillis,
        incarnation = incarnation,
    )

    /**
     * The receive-side half, built on the kernel's own verifier seam: this is
     * `civictech.cell.membrane.SignatureVerifier`'s implementation
     * [Ed25519SignatureVerifier], handed to [AnnouncementVerifier] with the
     * payload narrowed to the announcement it always was. Task 4 wires the same
     * object in `:wire`.
     */
    private fun verification(
        keys: Map<PeerId, PublicKey> = directory,
        skewMillis: Long = 0L,
    ): AnnouncementVerification {
        val seam = Ed25519SignatureVerifier(
            publicKeys = { peer -> keys[peer] },
            canonicalBytes = { _, _, payload -> canonicalBytes(input(payload as SignableAnnouncement)) },
        )
        return AnnouncementVerification(
            verifier = { peer, counter, announcement, signature -> seam.verify(peer, counter, announcement, signature) },
            clock = receiverClock,
            skewMillis = skewMillis,
            clockName = "the receiver's injected test clock",
        )
    }

    // ------------------------------------------------------------------- rigs

    /** Everything the receiving side is, plus the raw frame sink an attacker would have. */
    private inner class Rig(
        boundPeer: PeerId?,
        verification: AnnouncementVerification? = verification(),
    ) {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(
            registry,
            host,
            peer = PeerId("receiver"),
            credentials = Keys(identity("ssa-4-3-receiver")),
            announcementVerification = verification,
        )
        val deadLetters = CopyOnWriteArrayList<DeadLetter>()

        /** Where an announcement addressed to this side lands. */
        val mirror = Peering.spawnMirror(side, toPeer = InvocationSink { }, peer = boundPeer)

        /**
         * The connection's byte sink, bound to [boundPeer] — the same
         * `Peering.hostIngress` a loopback and `WsTransport` both use, so a
         * frame pushed here travels the production path.
         */
        var ingress: Propagate<ByteArray> = Peering.hostIngress(side, fromPeer = boundPeer)
            private set

        val ingressCells = CopyOnWriteArrayList<BridgeIngressCell>()

        init {
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        /** What a reconnect does: a brand-new ingress cell on the same [Peering.Side]. */
        fun replaceIngress(boundPeer: PeerId?) {
            ingress = Peering.hostIngress(side, fromPeer = boundPeer, onSpawn = { ingressCells += it })
        }

        fun feed(bytes: ByteArray) {
            ingress.propagate(bytes)
            controller.runToIdle()
        }

        val rejected: Long get() = side.announcementAdmission!!.rejectedAnnouncements

        /**
         * Everything an announcement could possibly have moved: which refs the
         * registry knows and how, plus the mirrored topology. Compared whole,
         * so "zero registry change" is a statement about the registry rather
         * than about the one field a test remembered to look at.
         */
        fun registrySnapshot(): List<Any?> = listOf(
            registry.localRefs(),
            registry.remoteRefs(),
            registry.remoteRefs().map { it to registry.location(it) }.toSet(),
            registry.all(),
        )

        fun lastDenial() = deadLetters.mapNotNull { it.denial }.last()
    }

    /** A peer that signs real announcements at a receiver's mirror, and hands you the bytes. */
    private inner class Sender(
        credentials: PeerCredentials,
        config: AnnouncementSigningConfig = signingConfig(),
    ) {
        val side = Peering.Side(
            LocationRegistry(),
            ManagedHost(scheduler = SimulationController(0).scheduler(), registry = LocationRegistry()),
            peer = credentials.peerId,
            credentials = credentials,
            announcementSigning = config,
        )
        val bytes = CopyOnWriteArrayList<ByteArray>()
        private val egress = BridgeEgressCell(signer = side.announcementSigner).also { cell ->
            cell.outlet.subscribe(
                Use.fixed(
                    object : Propagate<ByteArray> {
                        override fun propagate(value: ByteArray) {
                            bytes += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        /** One signed `published(ref)` addressed at [mirror]; returns the frame bytes. */
        fun publish(mirror: CellRef, ref: CellRef = CellRef(UUID.randomUUID())): ByteArray {
            (HostedCellProxy.create(mirror, egress, Peering.AnnounceInletProxy::class.java)
                as Peering.AnnounceInletProxy).inlet.call.published(ref)
            return bytes.last()
        }
    }

    // =========================================================== BS-02, accept

    /**
     * BS-02, end-to-end over the real composition: two keyed sides on a
     * `Peering.loopback` — no socket, no hello ([DSC1-WIRE-05]) — both signing
     * and both verifying. A's published ref lands on B as a `Remote` location
     * **attributed to A's key-derived `PeerId`**, and nothing is dead-lettered.
     */
    @Test
    fun `BS-02 a signed announcement lands as a Remote attributed to the signer's derived PeerId`() {
        val controller = SimulationController(0)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val deadLetters = CopyOnWriteArrayList<DeadLetter>()
        listOf(hostA, hostB).forEach { host ->
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        val a = Peering.Side(
            registryA, hostA, peer = peerA, credentials = Keys(identityA),
            announcementSigning = signingConfig(), announcementVerification = verification(),
        )
        val b = Peering.Side(
            registryB, hostB, peer = peerB, credentials = Keys(identityB),
            announcementSigning = signingConfig(), announcementVerification = verification(),
        )

        val onA = CellRef(UUID.randomUUID())
        registryA.publish(onA, hostA)
        Peering.loopback(a, b)
        controller.runToIdle()

        // it arrived, and it is attributed to the identity that signed it.
        // Not `shouldContainExactly`: the catch-up sweep announces every LOCAL
        // ref A has, which on a hosted side includes A's own bridge cells.
        registryB.remoteRefs() shouldContain onA
        (registryB.location(onA) as LocationRegistry.Remote).peer shouldBe peerA
        deadLetters.shouldBeEmpty()
        // both directions verified; neither side refused anything
        a.announcementAdmission!!.rejectedAnnouncements shouldBe 0L
        b.announcementAdmission!!.rejectedAnnouncements shouldBe 0L
        b.announcementAdmission!!.highWaterFor(peerA).shouldNotBeNull()
    }

    // ========================================================= BS-04, unsigned

    /**
     * BS-04: an announcement from a peer that does not sign, arriving at a side
     * that requires signing. The sending side has credentials and **no**
     * [AnnouncementSigningConfig], so it emits exactly the frames it emitted
     * before this feature — which is the realistic shape of the refusal, not a
     * hand-stripped frame.
     */
    @Test
    fun `BS-04 an unsigned announcement is UNSIGNED with zero registry change`() {
        val rig = Rig(boundPeer = peerB)
        val unsigned = Peering.Side(
            LocationRegistry(),
            ManagedHost(scheduler = SimulationController(0).scheduler(), registry = LocationRegistry()),
            peer = peerB, credentials = Keys(identityB),
        )
        unsigned.announcementSigner shouldBe null
        val egress = BridgeEgressCell(signer = unsigned.announcementSigner)
        val bytes = CopyOnWriteArrayList<ByteArray>()
        egress.outlet.subscribe(
            Use.fixed(
                object : Propagate<ByteArray> {
                    override fun propagate(value: ByteArray) {
                        bytes += value
                    }
                },
                PortRef.generate(),
            ),
        )
        (HostedCellProxy.create(rig.mirror.ref, egress, Peering.AnnounceInletProxy::class.java)
            as Peering.AnnounceInletProxy).inlet.call.published(CellRef(UUID.randomUUID()))

        val before = rig.registrySnapshot()
        rig.feed(bytes.single())

        rig.lastDenial().reason shouldBe DenialReason.UNSIGNED
        rig.registrySnapshot() shouldBe before
        rig.rejected shouldBe 1L
    }

    // ==================================================== BS-05, bad signature

    /**
     * BS-05, both halves the epic names, because they fail for different
     * reasons and a gate could catch one and miss the other:
     *
     * - **over different bytes**: a signer whose canonical encoder commits to a
     *   *different* announcement than the frame declares (here: the next
     *   counter). The signature is a perfectly valid Ed25519 signature by the
     *   right key over the wrong region.
     * - **by a different key**: a signer claiming B's name — so the id binding
     *   passes and the gate cannot short-circuit on [DenialReason.ID_MISMATCH]
     *   — while holding a keypair that does not derive it.
     */
    @Test
    fun `BS-05 a signature over different bytes, and one by a different key, are both BAD_SIGNATURE`() {
        val overDifferentBytes = Sender(
            Keys(identityB),
            signingConfig(encode = { a -> canonicalBytes(input(a.copy(counter = a.counter + 1))) }),
        )
        val byADifferentKey = Sender(Keys(peerB, impostorOfB))

        listOf(overDifferentBytes, byADifferentKey).forEach { sender ->
            val rig = Rig(boundPeer = peerB)
            val frame = sender.publish(rig.mirror.ref)
            val before = rig.registrySnapshot()
            rig.feed(frame)

            rig.lastDenial().reason shouldBe DenialReason.BAD_SIGNATURE
            rig.registrySnapshot() shouldBe before
            rig.rejected shouldBe 1L
        }
    }

    // ========================================================== BS-06, replay

    /**
     * BS-06: the *byte-identical* redelivery, which is the shape a network
     * replay actually has. The clause worth protecting is the second one — the
     * registry equals its post-first-acceptance state, so the replay is neither
     * double-applied nor allowed to *regress* what the first announcement
     * installed.
     */
    @Test
    fun `BS-06 a byte-identical redelivery is REPLAY and leaves the accepted state exactly as it was`() {
        val rig = Rig(boundPeer = peerB)
        val sender = Sender(Keys(identityB))
        val ref = CellRef(UUID.randomUUID())
        val frame = sender.publish(rig.mirror.ref, ref)

        rig.feed(frame)
        rig.registry.remoteRefs() shouldContainExactly setOf(ref)
        val afterAcceptance = rig.registrySnapshot()
        rig.rejected shouldBe 0L

        rig.feed(frame)

        rig.lastDenial().reason shouldBe DenialReason.REPLAY
        rig.registrySnapshot() shouldBe afterAcceptance
        rig.rejected shouldBe 1L
        // and the high-water mark did not move backwards or forwards
        rig.side.announcementAdmission!!.highWaterFor(peerB) shouldBe 1L
    }

    /**
     * The bounded-state clause, read rather than claimed ([DSC1-ANN-13]): many
     * announcements from two identities leave exactly two entries. State is
     * `O(admitted peers)`, not `O(announcements)`.
     */
    @Test
    fun `replay state is one entry per minting identity, not per announcement`() {
        val rig = Rig(boundPeer = peerB)
        val sender = Sender(Keys(identityB))
        repeat(25) { rig.feed(sender.publish(rig.mirror.ref)) }

        rig.side.announcementAdmission!!.trackedPeers shouldBe 1
        rig.side.announcementAdmission!!.highWaterFor(peerB) shouldBe 25L
        rig.registry.remoteRefs() shouldHaveSize 25
        rig.rejected shouldBe 0L
    }

    // ================================================ process restart, ssa.6

    /**
     * `computenet-ssa.6`, the deterministic half: a signing **process** restarts
     * while re-minting the same identity, and the receiver — whose `Peering.Side`
     * and therefore whose replay ledger never went away — accepts the burst
     * instead of dead-lettering all of it as REPLAY.
     *
     * "A process restart" here is a *fresh `Peering.Side` over the same
     * credentials*, which is exactly what a restart is in kernel terms: a new
     * [AnnouncementSigner], a virgin `AtomicLong`, the same key-derived
     * [PeerId]. Nothing about the receiver is rebuilt — a rebuilt receiver would
     * hand the burst a virgin ledger that accepts anything and the test would
     * pass without the property holding, the same trap BS-13 calls out in
     * `:wire`.
     *
     * The two incarnations are **named**, not taken from the wall clock: with the
     * production default two `Sender`s built in the same millisecond would share
     * a floor and this test would flake on a fast machine. The production default
     * is pinned separately, in `SignedAnnouncementEmitTest`.
     *
     * **Measured discrimination** (`computenet-ssa.6`), twice, because the two
     * runs fail in different places and both are worth knowing:
     *
     * - Against the genuinely pre-fix signer — `AtomicLong(0)`, no incarnation
     *   seam at all, so this test could not yet name its two incarnations — the
     *   failure is the defect itself, at `deadLetters.shouldBeEmpty()`:
     *   `reason=REPLAY ... counter=1 does not exceed the highest already
     *   accepted from '<peerB>' (3)`.
     * - Against the fix with only its last line mutated back
     *   (`AtomicLong(0)` while [AnnouncementSigner.counterFloor] is still
     *   computed), the `lastCounter shouldBe announcementCounterFloor(...)`
     *   line trips first: `expected:<1782579262914560000L> but was:<0L>`. Same
     *   cause, caught one assertion earlier.
     *
     * Under both, every *other* case in this file stays green — including BS-06
     * and the ingress-replacement case, which is the point: the fix is not
     * load-bearing for any replay clause.
     */
    @Test
    fun `a signing process that restarts re-minting the same identity is accepted, not REPLAY`() {
        val rig = Rig(boundPeer = peerB)
        val firstBoot = 1_700_000_000_000L
        val secondBoot = firstBoot + 60_000L // the process was down for a minute

        // incarnation 1 announces; the receiver's high-water mark advances
        val first = Sender(Keys(identityB), signingConfig(incarnation = { firstBoot }))
        repeat(3) { rig.feed(first.publish(rig.mirror.ref)) }
        rig.rejected shouldBe 0L
        val highWaterBeforeRestart = rig.side.announcementAdmission!!.highWaterFor(peerB).shouldNotBeNull()
        rig.registry.remoteRefs() shouldHaveSize 3

        // the PROCESS restarts: a brand-new `Peering.Side`, hence a brand-new
        // `AnnouncementSigner` and a virgin counter, re-minting the SAME
        // identity. The receiver is untouched -- same Side, same ledger, its
        // high-water mark for this identity already past the fresh sequence.
        val restarted = Sender(Keys(identityB), signingConfig(incarnation = { secondBoot }))
        restarted.side.announcementSigner!!.lastCounter shouldBe
            announcementCounterFloor(secondBoot) // nothing signed yet, but the floor is already past
        val afterRestart = CellRef(UUID.randomUUID())
        rig.feed(restarted.publish(rig.mirror.ref, afterRestart))

        rig.deadLetters.shouldBeEmpty()
        rig.rejected shouldBe 0L
        rig.registry.remoteRefs() shouldContain afterRestart
        rig.side.announcementAdmission!!.highWaterFor(peerB)
            .shouldNotBeNull() shouldBeGreaterThan highWaterBeforeRestart
    }

    /**
     * The second acceptance clause of `computenet-ssa.6`, stated where it can be
     * broken: the fix must not buy restart recovery with replay tolerance.
     *
     * A byte-identical redelivery is REPLAY *within* an incarnation (BS-06
     * above), and it is still REPLAY when it is redelivered **across** one — a
     * captured frame from incarnation 1 replayed after the peer restarted stays
     * refused, because the ledger's high-water mark for that identity never went
     * down. Recovery comes from the sender's floor going *up*, and a floor that
     * only ever rises cannot re-admit anything already seen.
     */
    @Test
    fun `a frame captured before the restart is still REPLAY after it`() {
        val rig = Rig(boundPeer = peerB)
        val firstBoot = 1_700_000_000_000L
        val first = Sender(Keys(identityB), signingConfig(incarnation = { firstBoot }))
        val captured = first.publish(rig.mirror.ref)

        rig.feed(captured)
        rig.rejected shouldBe 0L
        val afterAcceptance = rig.registrySnapshot()

        // the peer restarts and announces afresh; the burst is admitted
        val restarted = Sender(Keys(identityB), signingConfig(incarnation = { firstBoot + 60_000L }))
        rig.feed(restarted.publish(rig.mirror.ref))
        rig.rejected shouldBe 0L
        val afterRestart = rig.registrySnapshot()

        // and now the captured frame is injected again. Still REPLAY.
        rig.feed(captured)

        rig.lastDenial().reason shouldBe DenialReason.REPLAY
        rig.rejected shouldBe 1L
        rig.registrySnapshot() shouldBe afterRestart
        afterRestart shouldNotBe afterAcceptance // the restart burst really did land
    }

    // ========================================================= BS-07, expired

    /**
     * BS-07: expiry against the receiver's **injected** clock — this test never
     * sleeps — and the refusal names the clock that made the call (epic §9.6),
     * because an operator cannot otherwise tell a stale frame from a receiver
     * whose own clock is wrong.
     */
    @Test
    fun `BS-07 an announcement past notAfter is EXPIRED against the injected clock, which the reason names`() {
        val rig = Rig(boundPeer = peerB, verification = verification(skewMillis = 5_000L))
        val sender = Sender(Keys(identityB), signingConfig(ttlMillis = 60_000L))
        val frame = sender.publish(rig.mirror.ref)

        // inside notAfter + skew: still fine, so the boundary is a boundary
        receiverClock.now = senderClock.now + 60_000L + 5_000L
        val before = rig.registrySnapshot()
        rig.feed(frame)
        rig.rejected shouldBe 0L
        rig.registrySnapshot() shouldNotBe before

        // one millisecond past it
        val rig2 = Rig(boundPeer = peerB, verification = verification(skewMillis = 5_000L))
        val frame2 = Sender(Keys(identityB), signingConfig(ttlMillis = 60_000L)).publish(rig2.mirror.ref)
        receiverClock.now = senderClock.now + 60_000L + 5_001L
        val before2 = rig2.registrySnapshot()
        rig2.feed(frame2)

        val denial = rig2.lastDenial()
        denial.reason shouldBe DenialReason.EXPIRED
        denial.detail!! shouldContain "the receiver's injected test clock"
        rig2.registrySnapshot() shouldBe before2
        rig2.rejected shouldBe 1L
    }

    // ==================================================== BS-08, id mismatch

    /**
     * BS-08 — **the case that distinguishes per-connection key binding from
     * bare signature checking**, and the one the bead forbids omitting.
     *
     * The frame is *validly signed* by B, and the receiver *knows B's public
     * key*, so a gate that merely asked "does this signature verify?" would
     * admit it. It arrives on the connection bound to A, and is refused
     * [DenialReason.ID_MISMATCH].
     *
     * **This case does not pin the check ORDER, and does not claim to.** The
     * verifier injected here resolves keys from a directory holding both A and
     * B, so B's signature verifies `true` and the binding check reports
     * ID_MISMATCH whether it runs before or after the verify — measured at
     * review by moving the ID_MISMATCH blocks below the verify call, which
     * compiles and leaves all ten cases in this file green. What this case does
     * pin is that the binding is checked *at all* (see the mutation note
     * below). The ordering argument in [AnnouncementAdmission]'s KDoc is about a
     * connection-keyed verifier, which is task 4's shape and not this file's.
     *
     * The discriminating half is the second feed: **the very same bytes** are
     * accepted on a connection bound to B. So the refusal is about the
     * connection, not about the frame.
     *
     * Mutation-checked, measured: deleting the `mintingPeer != boundPeer`
     * branch in [AnnouncementAdmission] compiles, and the frame is then
     * *accepted* here — B's signature verifies under B's key, which the
     * receiver knows — so no denial is recorded at all. Two tests go red and no
     * others: this one, and the secrecy test below, which uses an ID_MISMATCH
     * refusal as the dead letter it inspects.
     */
    @Test
    fun `BS-08 a validly signed announcement minted by B on A's connection is ID_MISMATCH`() {
        val onAsConnection = Rig(boundPeer = peerA)
        val sender = Sender(Keys(identityB))
        val ref = CellRef(UUID.randomUUID())
        val frame = sender.publish(onAsConnection.mirror.ref, ref)

        val before = onAsConnection.registrySnapshot()
        onAsConnection.feed(frame)

        val denial = onAsConnection.lastDenial()
        denial.reason shouldBe DenialReason.ID_MISMATCH
        onAsConnection.registrySnapshot() shouldBe before
        onAsConnection.rejected shouldBe 1L

        // the same bytes, on the connection they were minted for: admitted.
        // Without this, "ID_MISMATCH" could just as well mean "unverifiable".
        val onBsConnection = Rig(boundPeer = peerB)
        onBsConnection.feed(Sender(Keys(identityB)).publish(onBsConnection.mirror.ref, ref))
        onBsConnection.rejected shouldBe 0L
        onBsConnection.registry.remoteRefs() shouldContainExactly setOf(ref)
    }

    // ======================================== replay state across a reconnect

    /**
     * [DSC1-ANN-13]'s survival clause, and the one that passes trivially if a
     * test never actually replaces the ingress: the frame is accepted by one
     * ingress cell, that cell is **replaced** by a fresh one (what
     * `WsTransport.Session` does per socket open and `Peering.Loopback.heal`
     * per heal), and the replay is still caught.
     *
     * Mutation-checked, measured: turning `Peering.Side.announcementAdmission`
     * into a computed `get()` — a fresh ledger per read, i.e. per ingress —
     * compiles and turns this test red, along with 7 of the other 8 cases. The
     * kill is deliberately reported as blunt rather than dressed up as
     * surgical: every assertion in this file that reads
     * `side.announcementAdmission` for a counter also gets a fresh object under
     * that mutation, so the breadth is an artifact of the read, not extra
     * evidence. What *this* case contributes over the rest is the only thing
     * that survives a narrower mutation: the ingress cell is genuinely replaced
     * between the two feeds ([Rig.ingressCells] asserts a new cell exists), so
     * a ledger that lived on the ingress would lose the high-water mark exactly
     * where a reconnect does.
     */
    @Test
    fun `replay state survives ingress replacement on reconnect`() {
        val rig = Rig(boundPeer = peerB)
        val sender = Sender(Keys(identityB))
        val frame = sender.publish(rig.mirror.ref)

        rig.feed(frame)
        rig.rejected shouldBe 0L

        rig.replaceIngress(peerB)
        rig.ingressCells shouldHaveSize 1 // the replacement really is a different cell
        rig.feed(frame)

        rig.lastDenial().reason shouldBe DenialReason.REPLAY
        rig.rejected shouldBe 1L
        // and the fresh ingress accounts on its own boundary sink, which is why
        // the side-scoped counter above is the one that spans a reconnect
        rig.ingressCells.single().boundaryDenials["announcement-admission"]!!.denialCount shouldBe 1L
    }

    // ============================ computenet-l8y5's boundary, pinned not solved

    /**
     * **Not this task's fix — this task's measurement.** `computenet-l8y5` asks
     * for an ill-formed announcement *encoding* to be distinguishable from a
     * forged signature. Today it is not, and this pins exactly how it is not, so
     * that item starts from measured behaviour and so its fix shows up here as a
     * failing assertion rather than as silence.
     *
     * The mechanism: `civictech.identity.announce.canonicalBytes` **refuses** a
     * `portName` carrying an unpaired surrogate (`computenet-9qgg`) rather than
     * letting UTF-8 substitute `?` and collide two announcements;
     * `Ed25519SignatureVerifier` is total and maps that throw to `verify=false`;
     * and this gate reads `false` as [DenialReason.BAD_SIGNATURE]. So an
     * announcement that is *unencodable* and one that is *forged* arrive at an
     * operator as the same word.
     *
     * The port name is injected by rewriting the encoded frame's JSON, which is
     * how it reaches a receiver in the first place: the kernel wire codec decodes
     * a JSON-escaped lone `\ud800` straight into [WireFrame.portName]
     * (`:identity`'s `WirePortNameSurrogateReachabilityTest` measures that), so
     * this is remote input, not a synthetic value.
     */
    @Test
    fun `an unencodable announcement is BAD_SIGNATURE today — computenet-l8y5's residue, measured`() {
        val rig = Rig(boundPeer = peerB)
        val frame = Sender(Keys(identityB)).publish(rig.mirror.ref)
        val illFormed = frame.decodeToString()
            .replace("\"portName\":\"inlet\"", "\"portName\":\"\\ud800\"")
        illFormed shouldNotBe frame.decodeToString()
        WireCodec.decodeFrame(illFormed.toByteArray()).frame.portName shouldBe "\uD800"

        val before = rig.registrySnapshot()
        rig.feed(illFormed.toByteArray())

        // the residue, in one line: unencodable reads as forged
        rig.lastDenial().reason shouldBe DenialReason.BAD_SIGNATURE
        rig.registrySnapshot() shouldBe before
        rig.rejected shouldBe 1L
    }

    // ================================================================ secrecy

    /**
     * [DSC1-OBS-05]: no dead letter carries private key material, a nonce, or
     * raw signature bytes. Checked against the *actual* base64url signature of
     * the refused frame and against the sender's public key encoding, over the
     * whole rendered dead letter — description, detail and the record's own
     * fields.
     *
     * **Bound: one refusal, ID_MISMATCH.** This case does not sweep the
     * taxonomy. That the other four reasons are equally safe is a *structural*
     * property of [AnnouncementAdmission.check]'s detail strings — they
     * interpolate booleans, peer names, counters and epoch millis and nothing
     * else, and the BAD_SIGNATURE branch says in so many words that it withholds
     * the signature — together with the `deniedArgs = emptyList()` at the
     * `BridgeIngressCell` call site, which is what keeps the raw frame out of
     * the record. Neither is asserted here, so a future detail string that
     * interpolated `signature` under, say, REPLAY would not redden this file.
     */
    @Test
    fun `no dead letter carries key material or raw signature bytes`() {
        val ref = CellRef(UUID.randomUUID())
        val rig = Rig(boundPeer = peerA) // bound to A, so B's frame is refused
        val frame = Sender(Keys(identityB)).publish(rig.mirror.ref, ref)
        val signature = WireCodec.decodeFrame(frame).frame.signature!!
        rig.feed(frame)

        rig.deadLetters shouldHaveSize 1
        val letter = rig.deadLetters.single()
        val rendered = letter.description + "|" + letter.denial!!.detail + "|" + letter.denial
        rendered shouldNotContain signature
        rendered shouldNotContain java.util.Base64.getEncoder().encodeToString(identityB.publicKey.encoded)
        // the sender's *name* is public by construction (it is a key fingerprint)
        // and naming it is the point of the record
        rendered shouldContain peerB.name
        signature.length shouldBeGreaterThan 40 // the string we searched for was a real one
    }
}
