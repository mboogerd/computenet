package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.port.PortRef
import civictech.identity.Ed25519
import civictech.identity.Ed25519SignatureVerifier
import civictech.identity.FilePeerKeyStore
import civictech.identity.PeerIdentity
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The **seam** between this feature's two halves: the canonical announcement
 * encoding ([canonicalBytes]) and the kernel-seam adapter
 * ([Ed25519SignatureVerifier]).
 *
 * Each half is well covered alone — `AnnouncementCanonicalBytes*Test` pins the
 * bytes, `Ed25519SignatureVerifierTest` pins the adapter against a *stand-in*
 * encoder — but until this file nothing signed a real announcement with a real
 * key store's key and verified it back through the kernel's own
 * [SignatureVerifier] interface. The two halves agreeing is the feature's whole
 * proposition, and it is the one thing neither task could assert on its own.
 *
 * What the negative cases establish is the security property that matters:
 * a signature is bound to *one* announcement. Injectivity of the encoding
 * ([DSC1-ANN-02..03]) is what makes that true, and here it is measured through
 * the signature rather than through the bytes — a signature honestly minted for
 * one announcement must not verify a different one.
 *
 * No expected value here is recomputed from a production formula: every
 * assertion is `true`/`false` on a verification outcome. The signature is
 * produced by the real signer over the real encoder and checked by the real
 * verifier, so a test that agreed with a *broken* encoder would still have to
 * let a tampered announcement through to pass — which is what the negative
 * cases forbid.
 */
class SignedAnnouncementSeamTest {

    /**
     * The join. The kernel seam hands over `(mintingPeer, counter, payload)`;
     * the announcement encoding wants a whole [AnnouncementSigningInput]. The
     * adapter takes the peer and counter from the **seam**, not from the
     * payload, so a payload that claims a different minter than the crossing it
     * arrived on cannot smuggle that claim into the signed region.
     */
    private val encode: (PeerId, Long, Any?) -> ByteArray = { peer, counter, payload ->
        canonicalBytes((payload as AnnouncementSigningInput).copy(mintingPeerId = peer, counter = counter))
    }

    private fun announcement(peer: PeerId) = AnnouncementSigningInput(
        mintingPeerId = peer,
        counter = 12L,
        notAfter = 1_700_000_000_000L,
        contractId = 0x0102030405060708L,
        methodId = 5L,
        cellRef = CellRef(UUID.fromString("00000000-0000-4000-8000-00000000002a"), 42L),
        portName = "orders",
        args = listOf(
            CellRef(UUID.fromString("11111111-2222-4333-8444-555555555555"), -1L),
            TopologyLink(
                id = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                from = PortRef(
                    UUID.fromString("01234567-89ab-4cde-8f01-234567890abc"),
                    CellRef(UUID.fromString("deadbeef-0000-4000-8000-000000000001"), 9L),
                ),
                to = PortRef(UUID.fromString("fedcba98-7654-4321-8fed-cba987654321"), null),
            ),
            UUID.fromString("99999999-8888-4777-8666-555544443333"),
        ),
    )

    /** Signs [input] the way an announcing peer would: over its canonical bytes. */
    private fun sign(identity: PeerIdentity, input: AnnouncementSigningInput): ByteArray =
        identity.sign(canonicalBytes(input))

    @Test
    fun `an announcement signed with the node's own persisted key verifies at the kernel seam`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir).loadOrGenerate()
        val verifier: SignatureVerifier = Ed25519SignatureVerifier({ mapOf(identity.peerId to identity.publicKey)[it] }, encode)
        val input = announcement(identity.peerId)

        assertTrue(
            verifier.verify(input.mintingPeerId, input.counter, input, sign(identity, input)),
            "the real key store's key, the real encoding and the real seam must agree",
        )
    }

    @Test
    fun `a signature does not transfer to any announcement differing in one field`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir).loadOrGenerate()
        val verifier = Ed25519SignatureVerifier({ mapOf(identity.peerId to identity.publicKey)[it] }, encode)
        val input = announcement(identity.peerId)
        val signature = sign(identity, input)

        // Each of these is a redirect an attacker would want: a different port,
        // a different target cell, a different link, a later expiry. None may
        // ride the signature minted for the original.
        val tampered = mapOf(
            "portName" to input.copy(portName = "attacker"),
            "notAfter" to input.copy(notAfter = input.notAfter + 1),
            "contractId" to input.copy(contractId = input.contractId + 1),
            "methodId" to input.copy(methodId = input.methodId + 1),
            "cellRef.instanceId" to input.copy(cellRef = input.cellRef.copy(instanceId = 43L)),
            "an argument dropped" to input.copy(args = input.args.dropLast(1)),
            "argument order" to input.copy(args = input.args.reversed()),
            "an argument replaced" to input.copy(args = listOf(UUID.fromString("00000000-0000-4000-8000-0000000000ff")) + input.args.drop(1)),
        )
        for ((what, forged) in tampered) {
            assertFalse(
                verifier.verify(forged.mintingPeerId, forged.counter, forged, signature),
                "a signature over the original verified an announcement with a changed $what",
            )
        }
    }

    @Test
    fun `the counter and the minting peer are inside the signed region`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir.resolve("node")).loadOrGenerate()
        val other = FilePeerKeyStore(dir.resolve("other")).loadOrGenerate()
        val directory = mapOf(identity.peerId to identity.publicKey, other.peerId to other.publicKey)
        val verifier = Ed25519SignatureVerifier(directory::get, encode)
        val input = announcement(identity.peerId)
        val signature = sign(identity, input)

        assertFalse(verifier.verify(input.mintingPeerId, input.counter + 1, input, signature), "replay at a bumped counter")

        // A payload asserting someone else's name, presented on this peer's
        // crossing: the adapter overwrites it from the seam, so the bytes
        // verified are this peer's, and the signature over the other name fails.
        val impersonating = input.copy(mintingPeerId = other.peerId)
        assertFalse(
            verifier.verify(identity.peerId, input.counter, impersonating, sign(identity, impersonating)),
            "a payload naming a different minter than the crossing must not verify",
        )
    }

    @Test
    fun `a signature by another peer's key does not verify under this peer's name`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir.resolve("node")).loadOrGenerate()
        val impostor = FilePeerKeyStore(dir.resolve("impostor")).loadOrGenerate()
        val verifier = Ed25519SignatureVerifier({ mapOf(identity.peerId to identity.publicKey)[it] }, encode)
        val input = announcement(identity.peerId)

        assertFalse(
            verifier.verify(input.mintingPeerId, input.counter, input, sign(impostor, input)),
            "anyone could forge an announcement if a stranger's signature verified under the named peer's key",
        )
    }

    @Test
    fun `an off-domain payload is refused as false, not as an exception at the seam`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir).loadOrGenerate()
        val verifier = Ed25519SignatureVerifier({ mapOf(identity.peerId to identity.publicKey)[it] }, encode)
        val input = announcement(identity.peerId)
        val signature = sign(identity, input)

        // The real encoder's fail-closed paths reached through the seam: a
        // payload that is not an announcement at all (ClassCastException) and an
        // announcement carrying an argument outside the signable domain
        // (IllegalArgumentException). Both must surface as `false` — an ingress
        // seam that throws on hostile input is a liveness defect.
        assertFalse(verifier.verify(input.mintingPeerId, input.counter, "not an announcement", signature))
        assertFalse(verifier.verify(input.mintingPeerId, input.counter, null, signature))
        assertFalse(verifier.verify(input.mintingPeerId, input.counter, input.copy(args = listOf("off domain")), signature))

        // And an unknown peer, with a signature that is perfectly valid.
        val unknown = PeerId("ed25519:" + "A".repeat(43))
        assertFalse(verifier.verify(unknown, input.counter, input.copy(mintingPeerId = unknown), sign(identity, input)))
    }

    @Test
    fun `the seam verifies the canonical bytes and nothing else`(@TempDir dir: Path) {
        val identity = FilePeerKeyStore(dir).loadOrGenerate()
        val verifier = Ed25519SignatureVerifier({ mapOf(identity.peerId to identity.publicKey)[it] }, encode)
        val input = announcement(identity.peerId)

        // Signing the payload's `toString` instead of its canonical bytes is the
        // plausible-looking mistake; it must not verify. Its converse — the
        // canonical bytes verifying directly under the raw primitive — pins that
        // the adapter is passing through exactly those bytes.
        assertFalse(verifier.verify(input.mintingPeerId, input.counter, input, identity.sign(input.toString().toByteArray())))
        assertTrue(Ed25519.verify(identity.publicKey, canonicalBytes(input), sign(identity, input)))
    }
}
