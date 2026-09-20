package civictech.iroh.discover

import civictech.cell.DenialReason
import civictech.cell.link.IdentityResolution
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.UnboundReason
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import civictech.identity.anchor.encodeIdentityStatementToken
import civictech.identity.fingerprint
import civictech.iroh.FakeSidecar
import civictech.iroh.HostMessage
import civictech.iroh.IrohTransport
import civictech.iroh.LinkDirection
import civictech.iroh.SidecarMessage
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.await
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **BS-05b**, `[DSC2-ID-05]`, F3-D7: two refusals that look alike from a
 * distance and must be countable apart (`computenet-ktn1l.4`).
 *
 * - A hello on key K that resolves to an identity **different from the one a
 *   live link for K is already attributed to** is
 *   [DenialReason.IDENTITY_MISMATCH] — a refusal only the discovery gate can
 *   make, because only it knows what K is currently peered as. The live link
 *   is untouched: a newcomer never displaces an established peering.
 * - A hello whose **presented statements do not back K** is refused by the
 *   identity binding, before the gate is consulted at all, under today's
 *   `UNVOUCHED`/`STATEMENT_EXPIRED` — unchanged by this feature.
 *
 * Both run against one node here, so `refusedBy()` holds both at once and the
 * claim that they are **distinct constants** is checked on one map rather than
 * on two runs that could each be reading the same entry.
 *
 * Nothing about either refusal is a tie-break: a refused link is blamed, a
 * tie-break loser is not, and this file pins that the two accountings do not
 * leak into each other.
 */
class IdentityMismatchFakeTest {

    private val fixedNow = 1_700_000_000_000L
    private val day = 86_400_000L

    /** The anchor this side accepts, and a key it vouches for that is NOT the key any link comes up on. */
    private val anchor = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("ktn1l4-anchor".toByteArray())))
    private val carolKeys = DeterministicKeySource.keyPairFromSeed("ktn1l4-carol".toByteArray())

    /**
     * A binding whose answer for ONE key ([k]) the test can change mid-run,
     * and which defers every other key to a real [AnchorVouchedBinding].
     *
     * The two halves are deliberately one binding: BS-05b's claim is about two
     * reasons observable **together**, which needs one node, which needs one
     * binding that can produce both.
     */
    private class SwitchableBinding(
        private val k: civictech.cell.link.KeyId,
        private val fallback: PeerIdentityBinding,
    ) : PeerIdentityBinding {
        @Volatile
        var nameForK: String = "alice"

        override fun resolve(
            key: civictech.cell.link.KeyId,
            presented: List<civictech.cell.link.IdentityStatement>,
        ): IdentityResolution =
            if (key == k) {
                IdentityResolution.Bound(PeerId(nameForK), issuer = null, statement = null)
            } else {
                fallback.resolve(key, presented)
            }
    }

    private fun hello2Line(name: String, tokens: List<String>): ByteArray =
        (IrohTransport.HELLO2_PREFIX + UUID.randomUUID() + " " + name + tokens.joinToString("") { " $it" })
            .toByteArray(StandardCharsets.UTF_8)

    /** Wait for a `DATA` on [link] — the hello the dialler writes at the end of `openLink`. */
    private fun awaitDataOn(fake: FakeSidecar, link: Long, seconds: Long = 30) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            val message = fake.pollHostMessage(200) ?: continue
            if (message is HostMessage.Data && message.link == link) return
        }
        fail("no DATA on link $link within ${seconds}s")
    }

    /**
     * Wait for the `CLOSE_LINK` a refusal produces on [link] and answer it with
     * the `LINK_DOWN` a sidecar would (`PROTOCOL.md` §3, one per side).
     *
     * The down is not decoration: an accepted link's refusal reaches the
     * discovery policy on the down event — the Session that recorded it is
     * dropped there — so a test that closed the link and never took it down
     * would be asserting on a count that had nowhere to come from.
     */
    private fun awaitCloseAndDown(fake: FakeSidecar, link: Long, seconds: Long = 30) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            val message = fake.pollHostMessage(200) ?: continue
            if (message is HostMessage.CloseLink && message.link == link) {
                fake.send(SidecarMessage.LinkDown(link, "refused"))
                return
            }
        }
        fail("link $link was not closed within ${seconds}s")
    }

    @Test
    fun `an identity that changed under a live link is IDENTITY_MISMATCH, distinct from an unvouched key`() {
        val k = freshNodeId()
        val unvouched = freshNodeId()
        val binding = SwitchableBinding(
            keyOf(k),
            AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey), clock = { fixedNow }),
        )
        FakeNode.start("N", freshNodeId(), sideWith(peer = PeerId("node"), binding = binding)).use { n ->
            // ---- (a) K is peered, attributed alice.
            n.discover(k)
            val live = n.fake.nextDial().also { dial ->
                n.fake.send(SidecarMessage.LinkUp(dial.link, k, DIRECTION_OUTBOUND))
                awaitDataOn(n.fake, dial.link)
                n.fake.hello1From(dial.link)
            }.link
            await("k to be peered as alice") { n.links(k).firstOrNull()?.peered == true }
            assertEquals(PeerId("alice"), n.links(k).single().attributedPeer)

            // The binding now resolves the SAME key to a different identity —
            // a rebinding, a stolen key, a misconfiguration: the gate does not
            // have to know which, only that the premise of one peering per key
            // has failed.
            binding.nameForK = "bob"

            // A second link for k, inbound, presenting a plain hello.
            n.fake.presentInbound(5_001, k)
            n.fake.hello1From(5_001)

            await("the mismatch to be recorded") { n.node.admissionDenialCount == 1L }
            val denial = assertNotNull(assertNotNull(n.node.sessionFor(5_001)).lastAdmissionDenial)
            assertEquals(DenialReason.IDENTITY_MISMATCH, denial.reason)
            assertEquals(PeerId("bob"), denial.principal, "the refusal is attributed to who the newcomer resolved as")
            val detail = assertNotNull(denial.detail)
            assertTrue(detail.contains(NodeKey(k).short), "the detail names the key in dispute: $detail")

            awaitCloseAndDown(n.fake, 5_001)
            await("the refusal to be attributed by reason") {
                n.peering.counters.refusedBy()[DenialReason.IDENTITY_MISMATCH] == 1L
            }

            // The live link is untouched — which is the half of [DSC2-ID-05]
            // that says a newcomer cannot displace an established peering.
            assertTrue(n.links(k).any { it.linkId == live && it.peered }, "the live link is still peered")
            assertEquals(PeerId("alice"), n.links(k).first { it.linkId == live }.attributedPeer)
            assertEquals("Peered(OUTBOUND)", assertNotNull(n.viewOf(k)).state)
            assertEquals("alice", assertNotNull(n.viewOf(k)).attributedPeer, "the table still names the live identity")
            assertEquals(0L, n.peering.counters.tieBreakClosed.count, "a refusal is not a tie-break close")

            // ---- (b) a hello whose statements do not back its key. The
            // statement is real, signed by an accepted anchor, and vouches for
            // carol's key — while the link came up on a different one.
            val statement = anchor.bind(
                PeerId("carol"),
                fingerprint(carolKeys.public),
                notBefore = fixedNow - day,
                notAfter = fixedNow + day,
            )
            n.fake.presentInbound(5_002, unvouched)
            n.fake.send(
                SidecarMessage.Data(5_002, hello2Line("carol", listOf(encodeIdentityStatementToken(statement)))),
            )

            await("the unvouched refusal to be recorded") { n.node.admissionDenialCount == 2L }
            val unvouchedDenial = assertNotNull(assertNotNull(n.node.sessionFor(5_002)).lastAdmissionDenial)
            assertEquals(
                DenialReason.UNVOUCHED,
                unvouchedDenial.reason,
                "unchanged by this feature: the binding refuses before the gate is consulted",
            )
            assertTrue(
                assertNotNull(unvouchedDenial.detail).contains("UnboundReason.${UnboundReason.KEY_MISMATCH.name}"),
                "the machine-readable reason rides in the detail: ${unvouchedDenial.detail}",
            )

            awaitCloseAndDown(n.fake, 5_002)
            await("both reasons to be attributed") { n.peering.counters.refusedBy().size == 2 }

            // ---- the claim BS-05b is actually about: two reasons, apart.
            assertNotEquals(
                DenialReason.IDENTITY_MISMATCH,
                unvouchedDenial.reason,
                "the two refusals must be distinguishable constants, not one reason wearing two details",
            )
            assertEquals(
                mapOf(DenialReason.IDENTITY_MISMATCH to 1L, DenialReason.UNVOUCHED to 1L),
                n.peering.counters.refusedBy(),
                "each refusal counted once, under its own reason ([DSC2-ID-05])",
            )
            assertEquals(2L, n.peering.counters.hellosRefused)
            assertEquals(0L, n.peering.counters.tieBreakClosed.count, "and neither is a tie-break close")

            // The surviving peering outlived both refusals.
            assertEquals(
                listOf(live),
                n.links(k).filter { it.peered }.map { it.linkId },
                "one live peering for k, the one that was there first",
            )
            assertEquals(LinkDirection.OUTBOUND, n.links(k).single { it.peered }.direction)
        }
    }
}
