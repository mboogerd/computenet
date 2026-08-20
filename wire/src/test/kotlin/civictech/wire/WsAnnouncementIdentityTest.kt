package civictech.wire

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.WireFrame
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * The two properties `computenet-ssa.4.4` adds that only a **connection-keyed**
 * receiver can express.
 *
 * `SignedAnnouncementTest` (`:kernel`) covers the taxonomy over a loopback with
 * a *directory* verifier — one that resolves any known peer's key. That shape
 * cannot see either property here:
 *
 * 1. **The `ID_MISMATCH`-before-verify ordering.** With a directory verifier, a
 *    frame minted by B and injected on A's connection verifies `true`, so the
 *    gate reports `ID_MISMATCH` whichever side of the verify call the binding
 *    check sits on — measured at that task's review, where moving both blocks
 *    below the verify left all ten of its cases green. With
 *    [connectionBoundVerifier], B's signature verifies `false` on A's
 *    connection, so verifying first would report `BAD_SIGNATURE` and lose the
 *    impersonation. This file is therefore the first thing in the tree that
 *    fails when the order is swapped.
 * 2. **The `RequireAuthenticated`-implies-signing-and-verification `require`.**
 *    It lives in `:wire` ([requireAnnouncementIdentity]) because only here are
 *    both halves constructible.
 */
class WsAnnouncementIdentityTest {

    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    private val identityA = identity("announcement-identity-A")
    private val identityB = identity("announcement-identity-B")

    /** A side that verifies announcements; its ledger is what the connection-bound verifier rebinds. */
    private fun receivingSide(): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(
            registry,
            ManagedHost(registry = registry),
            peer = identityA.peerId,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identityA.asPeerCredentials(),
            announcementSigning = socketAnnouncementSigning(),
            announcementVerification = socketAnnouncementVerification(),
        )
    }

    /** A side that signs, standing in for whichever peer minted the frame under test. */
    private fun signingSide(who: PeerIdentity): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(
            registry,
            ManagedHost(registry = registry),
            peer = who.peerId,
            credentials = who.asPeerCredentials(),
            announcementSigning = socketAnnouncementSigning(),
        )
    }

    private val cellRef = CellRef(UUID.randomUUID())

    /** A genuinely signed announcement frame, minted by [who]. */
    private fun signedFrame(who: PeerIdentity): WireFrame {
        val signer = signingSide(who).announcementSigner!!
        val signature = signer.sign(
            contractId = 7L,
            methodId = 11L,
            cellRef = cellRef,
            portName = "out",
            args = emptyList(),
        )
        return WireFrame(
            contractId = 7L,
            methodId = 11L,
            cellRef = cellRef,
            portName = "out",
            type = HostedPortInvocation.Type.PORT_MANAGEMENT,
            args = emptyList(),
            signature = signature.signature,
            signerKeyId = signature.signerKeyId,
            sigCounter = signature.counter,
            notAfter = signature.notAfter,
        )
    }

    /**
     * The positive control. Without it the mismatch case below could pass
     * against a verifier that refuses everything.
     *
     * The ledger assertion reads back **this frame's** counter rather than the
     * literal `1L` it used to. Since `computenet-ssa.6` a production signer seeds
     * its counter from its incarnation
     * (`AnnouncementSigner.counterFloor`) instead of from zero, so
     * `socketAnnouncementSigning()` — the production wiring, which this file
     * deliberately uses unmodified — no longer starts at 1. Nothing this case
     * checks was ever about the magnitude: what it wants is that the admitted
     * frame's counter is what the ledger recorded, which is now stated directly.
     */
    @Test
    fun `an announcement signed by the connection's own peer is admitted under the hello-bound key`() {
        val gate = receivingSide().announcementAdmission!!
            .withVerifier(connectionBoundVerifier(identityB.peerId, identityB.publicKey))

        val frame = signedFrame(identityB)
        gate.check(boundPeer = identityB.peerId, frame = frame).shouldBeNull()
        gate.rejectedAnnouncements shouldBe 0L
        gate.highWaterFor(identityB.peerId) shouldBe frame.sigCounter
    }

    /**
     * BS-08's socket shape, and the ordering pin. The verifier here knows
     * **only** A's key, so B's genuine signature verifies `false`; the gate must
     * still report `ID_MISMATCH`, because it decides the binding before it
     * touches the crypto.
     *
     * Mutation that this kills (measured, `computenet-ssa.4.4`): moving both
     * `ID_MISMATCH` blocks in `AnnouncementAdmission.classify` below the
     * `verifier.verify` call compiles and leaves `SignedAnnouncementTest` green,
     * and turns this assertion into `BAD_SIGNATURE`.
     */
    @Test
    fun `a validly signed announcement from B on A's connection is ID_MISMATCH, not BAD_SIGNATURE`() {
        val gate = receivingSide().announcementAdmission!!
            .withVerifier(connectionBoundVerifier(identityA.peerId, identityA.publicKey))

        val rejection = gate.check(boundPeer = identityA.peerId, frame = signedFrame(identityB))!!

        rejection.reason shouldBe DenialReason.ID_MISMATCH
        rejection.detail shouldContain identityB.peerId.name
        gate.rejectedAnnouncements shouldBe 1L
        // Zero ledger change: the impersonated identity gets no high-water mark.
        gate.highWaterFor(identityB.peerId).shouldBeNull()
        gate.highWaterFor(identityA.peerId).shouldBeNull()
    }

    /** The connection-bound verifier really does refuse a key it was not given. */
    @Test
    fun `a frame minted by B but claiming A's key id is BAD_SIGNATURE under A's bound key`() {
        val gate = receivingSide().announcementAdmission!!
            .withVerifier(connectionBoundVerifier(identityA.peerId, identityA.publicKey))

        val forged = signedFrame(identityB).copy(signerKeyId = identityA.peerId.name)
        val rejection = gate.check(boundPeer = identityA.peerId, frame = forged)!!

        rejection.reason shouldBe DenialReason.BAD_SIGNATURE
        gate.highWaterFor(identityA.peerId).shouldBeNull()
    }

    // ------------------------------------------- the :wire require (obligation 2)

    @Test
    fun `a RequireAuthenticated socket side that does not sign and verify is refused at construction`() {
        val registry = LocationRegistry()
        val unsigned = Peering.Side(
            registry,
            ManagedHost(registry = registry),
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identityA.asPeerCredentials(),
        )
        // Representable in :kernel — that is exactly the fail-open this closes.
        unsigned.announcementSigner.shouldBeNull()
        unsigned.announcementAdmission.shouldBeNull()

        val thrown = assertFailsWith<IllegalArgumentException> {
            WsTransport.Session(unsigned, send = {}, refuse = {})
        }
        thrown.message!! shouldContain "must sign AND verify announcements"
    }

    @Test
    fun `signing without verification is not enough`() {
        val registry = LocationRegistry()
        val halfWay = Peering.Side(
            registry,
            ManagedHost(registry = registry),
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identityA.asPeerCredentials(),
            announcementSigning = socketAnnouncementSigning(),
        )
        assertFailsWith<IllegalArgumentException> {
            WsTransport.Session(halfWay, send = {}, refuse = {})
        }
    }

    @Test
    fun `an Open side needs neither, and stays on the pre-feature path`() {
        val registry = LocationRegistry()
        val open = Peering.Side(registry, ManagedHost(registry = registry))
        open.announcementSigner.shouldBeNull()
        open.announcementAdmission.shouldBeNull()
        WsTransport.Session(open, send = {}, refuse = {}).egress.let { it shouldBe it }
    }
}
