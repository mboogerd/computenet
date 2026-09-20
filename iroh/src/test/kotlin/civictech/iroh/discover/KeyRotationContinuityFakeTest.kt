package civictech.iroh.discover

import civictech.cell.link.IdentityResolution
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.iroh.FakeSidecar
import civictech.iroh.HostMessage
import civictech.iroh.LinkDirection
import civictech.iroh.SidecarMessage
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **BS-06's FakeSidecar twin**, `[DSC2-ID-06]`, F3-D6: a peer's identity
 * re-appears under a **new** key, and the peering follows the identity rather
 * than the key (`computenet-ktn1l.4`).
 *
 * ## What "supersedes" has to mean, and what it must not
 *
 * Discovery cannot tell a key rotation from a second device: both look like
 * one name arriving on a key this node has not seen. So the rule is written to
 * be right either way — the new key is admitted with **no denial**, and the old
 * one is marked `Superseded` rather than torn down. Its live link stays up
 * until it drops on its own, and only then does the difference show: a
 * superseded key is never dialled again, and a later `PEER_DISCOVERED` for it
 * does not make it dialable. Nothing here closes a link and nothing here
 * refuses one; the whole transition is a change of what the table will *do*
 * next.
 *
 * The real-sidecar half of BS-06 belongs to feature `computenet-qzr7n`. This
 * file is the twin that runs on the default lanes: no sidecar binary, no
 * `Thread.sleep`, a [ManualTimer] for every "later" ([DSC2-DIAL-08],
 * [DSC2-DIAL-09]).
 */
class KeyRotationContinuityFakeTest {

    /** alice, under whichever key presents her — the rotation this file is about. */
    private fun aliceUnder(vararg keys: ByteArray): PeerIdentityBinding {
        val alice = keys.map { keyOf(it) }.toSet()
        return PeerIdentityBinding { key, presented ->
            if (key in alice) {
                IdentityResolution.Bound(PeerId("alice"), issuer = null, statement = null)
            } else {
                PeerIdentityBinding.Interim.resolve(key, presented)
            }
        }
    }

    /**
     * Every host message written within [millis], in order. Used where the
     * claim is an **absence** — a `DIAL` that must never appear — for which
     * `FakeSidecar.nextDial` is the wrong instrument: it skips whatever is in
     * front of it, so it cannot distinguish "no dial" from "no dial yet".
     */
    private fun drain(fake: FakeSidecar, millis: Long = 1_000): List<HostMessage> {
        val out = mutableListOf<HostMessage>()
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            out += fake.pollHostMessage(100) ?: continue
        }
        return out
    }

    /** Whether any `DIAL` in [messages] names [key]. */
    private fun dialsFor(messages: List<HostMessage>, key: ByteArray): Int =
        messages.count { it is HostMessage.Dial && it.peerId.contentEquals(key) }

    /**
     * Answer [node]'s outstanding `DIAL` for [key] with `LINK_UP`, wait for the
     * hello it writes on that very link, and answer it — the shortest route
     * from a sighting to an admitted OUTBOUND peering.
     *
     * The wait is for a `DATA` **on this link id**, not for any `DATA`: once a
     * peering is up its announcements are also `DATA`, and draining one of
     * those instead would answer a hello the dialler has not written yet.
     */
    private fun dialAndAdmit(n: FakeNode, key: ByteArray): Long {
        val dial = n.fake.nextDial()
        assertTrue(dial.peerId.contentEquals(key), "the dial names the discovered key")
        n.fake.send(SidecarMessage.LinkUp(dial.link, key, DIRECTION_OUTBOUND))
        awaitDataOn(n.fake, dial.link)
        n.fake.hello1From(dial.link)
        return dial.link
    }

    /** Wait for a `DATA` on [link], failing after [seconds]. @see dialAndAdmit */
    private fun awaitDataOn(fake: FakeSidecar, link: Long, seconds: Long = 30) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            val message = fake.pollHostMessage(200) ?: continue
            if (message is HostMessage.Data && message.link == link) return
        }
        kotlin.test.fail("no DATA on link $link within ${seconds}s")
    }

    @Test
    fun `a second key resolving to a peered identity is admitted, supersedes the old key and never re-dials it`() {
        val k1 = freshNodeId()
        val k2 = freshNodeId()
        val side = sideWith(peer = PeerId("node"), binding = aliceUnder(k1, k2))
        FakeNode.start("N", freshNodeId(), side).use { n ->
            // ---- alice, under her first key.
            n.discover(k1)
            val link1 = dialAndAdmit(n, k1)
            await("k1 to be peered") { n.links(k1).firstOrNull()?.peered == true }
            assertEquals(PeerId("alice"), n.links(k1).single().attributedPeer)

            // ---- alice again, under a second key. Admitted, not refused.
            n.discover(k2)
            val link2 = dialAndAdmit(n, k2)
            await("k2 to be peered") { n.links(k2).firstOrNull()?.peered == true }
            await("the supersession to be counted") { n.peering.counters.superseded.count == 1L }

            assertEquals(0L, n.node.admissionDenialCount, "a rotation is not a refusal ([DSC2-ID-06])")
            assertTrue(n.peering.counters.refusedBy().isEmpty(), "and nothing is attributed to any reason")
            assertEquals(1L, n.peering.counters.superseded.count, "counted once")

            // Both links are up, and both carry the SAME identity — which is
            // the continuity claim: the binding resolved two keys to one name
            // and no second name was invented anywhere.
            val oldLink = n.links(k1).single()
            val newLink = n.links(k2).single()
            assertEquals(link1, oldLink.linkId)
            assertEquals(link2, newLink.linkId)
            assertTrue(oldLink.peered, "the old link stays up and peered until it drops on its own")
            assertEquals(
                oldLink.attributedPeer,
                newLink.attributedPeer,
                "both links are attributed to one identity",
            )
            // `LinkView.attributedPeer` is the Session's own `attributedPeer`,
            // which `bindAndAnnounce` passes as `Peering.hostIngress(fromPeer =
            // peer)` in the same statement — one value, not two, so this is the
            // stamp every delivery on the link carries. A delivery-level
            // assertion needs a hosted cell on both ends and belongs to the
            // real-sidecar half (computenet-qzr7n).
            assertEquals(PeerId("alice"), newLink.attributedPeer)

            // The table says the same, by name and by state.
            val oldView = assertNotNull(n.viewOf(k1))
            val newView = assertNotNull(n.viewOf(k2))
            assertEquals("Superseded(by=${NodeKey(k2).short})", oldView.state, "the old key names its successor")
            assertEquals("Peered(OUTBOUND)", newView.state)
            assertEquals("alice", oldView.attributedPeer)
            assertEquals("alice", newView.attributedPeer)

            // ---- the old link drops. Nothing dials it, then or ever.
            n.fake.send(SidecarMessage.LinkDown(link1, "the old device went away"))
            await("the old link to be gone") { n.links(k1).isEmpty() }
            assertEquals(
                0,
                dialsFor(drain(n.fake), k1),
                "a superseded key answers NoRedial: its drop puts it back on no schedule ([DSC2-ID-06])",
            )
            assertEquals(0, n.timer.pending(), "and nothing is armed for it")
            assertEquals("Superseded(by=${NodeKey(k2).short})", assertNotNull(n.viewOf(k1)).state)

            // Not even when the clock runs out to something absurd.
            n.advanceTo(10L * 365 * 24 * 60 * 60 * 1_000)
            assertEquals(0, dialsFor(drain(n.fake), k1), "ten years later, still no dial for a superseded key")

            // ---- and a fresh sighting does not make it dialable again.
            val suppressedBefore = n.peering.counters.duplicatesSuppressed.count
            n.discover(k1)
            await("the sighting to be suppressed") {
                n.peering.counters.duplicatesSuppressed.count == suppressedBefore + 1
            }
            assertEquals(0, dialsFor(drain(n.fake), k1), "a Suppressed sighting starts no dial")
            assertEquals("Superseded(by=${NodeKey(k2).short})", assertNotNull(n.viewOf(k1)).state)

            // The surviving peering is untouched by all of it.
            assertTrue(n.links(k2).single().peered, "alice is still peered, on her new key")
            assertEquals(1L, n.peering.counters.superseded.count, "and superseded moved exactly once")
        }
    }

    /**
     * The cancellation half of F3-D6: a superseded key's **armed retry** is
     * cancelled.
     *
     * Reaching it needs a key that is both peered and still holding a retry,
     * which happens exactly one way — a dial that failed and armed a retry,
     * followed by an INBOUND link from the same key that is admitted before
     * the retry comes due. A key peered by its own successful dial never has
     * one, so in the scenario above `cancelRetry` is a guard that fires on
     * nothing; here it is the difference between one armed timer and none.
     */
    @Test
    fun `superseding a key cancels the retry it still had armed`() {
        val k1 = freshNodeId()
        val k2 = freshNodeId()
        val side = sideWith(peer = PeerId("node"), binding = aliceUnder(k1, k2))
        FakeNode.start("N", freshNodeId(), side).use { n ->
            // A failed dial leaves k1 Retained with a retry armed.
            n.discover(k1)
            val dial = n.fake.nextDial()
            n.fake.send(SidecarMessage.Failure(dial.link, "unreachable"))
            await("the failed dial") { n.peering.counters.dialsFailed.count == 1L }
            await("a retry armed for k1") { n.timer.pending() == 1 }

            // alice arrives anyway, on an INBOUND link this node never dialled.
            n.fake.presentInbound(4_001, k1)
            n.fake.hello1From(4_001)
            await("the inbound link to be admitted") { n.links(k1).firstOrNull()?.peered == true }
            assertEquals(1, n.timer.pending(), "the retry is still armed: nothing cancelled it")

            // And then under a second key, which supersedes the first.
            n.fake.presentInbound(4_002, k2)
            n.fake.hello1From(4_002)
            await("the supersession") { n.peering.counters.superseded.count == 1L }
            await("the armed retry to be cancelled") { n.timer.pending() == 0 }

            assertEquals(0L, n.node.admissionDenialCount, "no refusal anywhere in a rotation")
            assertEquals(
                "Superseded(by=${NodeKey(k2).short})",
                assertNotNull(n.viewOf(k1)).state,
            )
            assertEquals(LinkDirection.INBOUND, n.links(k2).single().direction)
            assertEquals(
                1L,
                quiesced { n.fake.dials.get() },
                "the one dial is the one that failed; a superseded key is not dialled again",
            )
        }
    }
}
