package civictech.iroh.discover

import civictech.iroh.HostMessage
import civictech.iroh.LinkDirection
import civictech.iroh.SidecarMessage
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **BS-08**, `[DSC2-DIAL-05]`, aas-D7, ktn1l-D16: two nodes discover and dial
 * each other at the same instant, and exactly one peering survives — the same
 * physical link on both sides, closed on the other with no blame anywhere
 * (`computenet-ktn1l.4`).
 *
 * ## The invariant, and why it is one rule and not two
 *
 * `PeerTable.loserDirection` is evaluated identically on both sides: the
 * smaller endpoint id (unsigned) keeps its OUTBOUND link, the larger keeps its
 * INBOUND one. Since A's outbound link to B **is** B's inbound link from A,
 * that single rule makes both nodes name the same survivor without exchanging
 * a word about it. The rig sorts the two NodeIds so `a` is always the smaller,
 * which is what lets every assertion below be written as a fact rather than as
 * a case analysis.
 *
 * ## Order independence is the point, not a bonus
 *
 * The scenario is played in four relay orders — A's dial connected first, B's
 * first, A's hello pumped before B's link exists, and B's link admitted at A
 * before A's link exists with B's close of it overtaking B's hello to A. A real LAN picks one of
 * those and a test over real sidecars would sample it; here the test picks
 * each, and asserts the same end state from each. Nothing sleeps: frames move
 * only when [TwoNodeFakeRig.pump] moves them ([DSC2-DIAL-08], [DSC2-DIAL-09]).
 */
class MutualDialTest {

    /**
     * The end state BS-08 requires, asserted on both nodes.
     *
     * @param order only names the relay ordering in the failure messages; the
     *   assertions themselves are identical for every order, because that is
     *   the claim.
     */
    private fun assertOnePeeringEachWay(rig: TwoNodeFakeRig, order: String) {
        val a = rig.a
        val b = rig.b

        await("$order: A to hold exactly one peered link") { a.links(b.own).count { it.peered } == 1 }
        await("$order: B to hold exactly one peered link") { b.links(a.own).count { it.peered } == 1 }

        // aas-D7: the smaller id keeps OUTBOUND, the larger keeps INBOUND —
        // and the two are the same relayed link.
        val kept = rig.solePeering(a, b.own, LinkDirection.OUTBOUND)
        val mirror = rig.solePeering(b, a.own, LinkDirection.INBOUND)

        // One link each, not one link each of two peerings: the loser is gone
        // from both registries, so neither node is holding a second link open.
        assertEquals(listOf(kept.linkId), a.links(b.own).map { it.linkId }, "$order: A holds only the survivor")
        assertEquals(listOf(mirror.linkId), b.links(a.own).map { it.linkId }, "$order: B holds only the survivor")

        // Blame-free on both sides: a tie-break close is not a refusal, and the
        // loser never got far enough to be charged an unadmitted open or to
        // drop a frame it had nowhere to route.
        assertEquals(0L, a.node.admissionDenialCount, "$order: A refused nothing")
        assertEquals(0L, b.node.admissionDenialCount, "$order: B refused nothing")
        assertEquals(0L, a.node.preHelloDrops, "$order: nothing was dropped on A")
        assertEquals(0L, b.node.preHelloDrops, "$order: nothing was dropped on B")
        assertTrue(a.peering.counters.refusedBy().isEmpty(), "$order: no refusal is attributed on A: ${a.peering.counters.refusedBy()}")
        assertTrue(b.peering.counters.refusedBy().isEmpty(), "$order: no refusal is attributed on B: ${b.peering.counters.refusedBy()}")
        assertEquals(0, a.peering.connectionFor(NodeKey(b.own))?.unadmittedOpens ?: 0, "$order: A charged no unadmitted open")
        assertEquals(0, b.peering.connectionFor(NodeKey(a.own))?.unadmittedOpens ?: 0, "$order: B charged no unadmitted open")

        // Exactly one closed link per node, counted once however that node
        // learned of it (ktn1l-D16).
        await("$order: A's tie-break close to be counted") { a.peering.counters.tieBreakClosed.count == 1L }
        await("$order: B's tie-break close to be counted") { b.peering.counters.tieBreakClosed.count == 1L }
        assertEquals(1L, a.peering.counters.tieBreakClosed.count, "$order: A counted its one tie-break close once")
        assertEquals(1L, b.peering.counters.tieBreakClosed.count, "$order: B counted its one tie-break close once")

        // The loser is never dialled again: its key still holds a live link,
        // so `PeerTable.linkDown` answers NoRedial and nothing is armed.
        assertEquals(1L, quiesced { rig.dialsFrom(a) }, "$order: A dialled once and never re-dialled the loser")
        assertEquals(1L, quiesced { rig.dialsFrom(b) }, "$order: B dialled once and never re-dialled the loser")
        assertEquals(0, a.timer.pending(), "$order: A armed no retry")
        assertEquals(0, b.timer.pending(), "$order: B armed no retry")

        // The observability surface says the same thing as the registries.
        val viewOfB = a.viewOf(b.own)
        val viewOfA = b.viewOf(a.own)
        assertEquals("Peered(OUTBOUND)", viewOfB?.state, "$order: A's snapshot")
        assertEquals("Peered(INBOUND)", viewOfA?.state, "$order: B's snapshot")
        assertEquals(resolvedBy(a.side, b.own).name, viewOfB?.attributedPeer, "$order: A attributes the survivor")
        assertEquals(resolvedBy(b.side, a.own).name, viewOfA?.attributedPeer, "$order: B attributes the survivor")
        assertTrue(a.node.linkErrors.isEmpty(), "$order: A logged no link error: ${a.node.linkErrors}")
        assertTrue(b.node.linkErrors.isEmpty(), "$order: B logged no link error: ${b.node.linkErrors}")
    }

    /**
     * Announce each node to the other, connect the two dials in [order], then
     * let every frame flow.
     *
     * `connectFirst` decides which dial becomes a pair of links first; both
     * are up before any hello is pumped in every ordering but the third, which
     * deliberately lets A's hello travel before B's link even exists.
     */
    private fun runScenario(order: String, play: (TwoNodeFakeRig) -> Unit) {
        TwoNodeFakeRig.startSorted().use { rig ->
            rig.a.discover(rig.b.own)
            rig.b.discover(rig.a.own)
            play(rig)
            rig.quiesce()
            assertOnePeeringEachWay(rig, order)
        }
    }

    @Test
    fun `a mutual dial connected A-first leaves one peering, the smaller id's outbound link`() {
        runScenario("A-first") { rig ->
            rig.connect(rig.dialFrom(rig.a), from = rig.a, to = rig.b)
            rig.connect(rig.dialFrom(rig.b), from = rig.b, to = rig.a)
        }
    }

    @Test
    fun `the same mutual dial connected B-first leaves the same one peering`() {
        runScenario("B-first") { rig ->
            rig.connect(rig.dialFrom(rig.b), from = rig.b, to = rig.a)
            rig.connect(rig.dialFrom(rig.a), from = rig.a, to = rig.b)
        }
    }

    /**
     * The hardest ordering: A's hello is delivered and judged **before** B's
     * dial has become a link at all, so A admits an inbound link it will later
     * have to close — or, if the verdict is reached in time, closes it before
     * announcing on it. Either way the end state is the one above, which is
     * what `[DSC2-DIAL-05]` asks for.
     */
    @Test
    fun `the same mutual dial with A's hello pumped before B's link exists leaves the same one peering`() {
        runScenario("A's hello first") { rig ->
            rig.connect(rig.dialFrom(rig.a), from = rig.a, to = rig.b)
            rig.quiesce(still = 2)
            rig.connect(rig.dialFrom(rig.b), from = rig.b, to = rig.a)
        }
    }

    /**
     * BS-08 HI_FIRST, the order `MutualDialSidecarTest` samples over real
     * sidecars and computenet-i74gh found miscounted: the LARGER id's link is
     * admitted at A (the smaller id) first, so A is Peered on an INBOUND link
     * when its own OUTBOUND link comes up. B judges A's hello, keeps its
     * INBOUND link and closes its OUTBOUND one — A's peered inbound link.
     *
     * The rig then does what the network did in the losing real-sidecar
     * trials: it delivers that `LINK_DOWN` to A **before** B's hello on A's
     * outbound link. A's gate therefore never sees two links at once and
     * cannot count the close; the down is the only place A learns of it, and
     * the down is of a link that was PEERED. The landed `onLinkDown` counted
     * an accepted link only when it was never admitted, so A read 0 here.
     *
     * The self-sighting barrier makes A's policy thread process that down
     * before the hello is replayed, so A's table cannot still hold the dead
     * link when the hello is judged — the other interleaving, which the gate
     * counts itself.
     *
     * Mutation: restore `!view.peered` in `onLinkDown`'s accepted-link
     * predicate — A's count stays 0 and the tie-break assertion times out.
     */
    @Test
    fun `the larger id's link admitted first, with its close overtaking its hello, leaves the same one peering and one count each`() {
        runScenario("B's link admitted first") { rig ->
            val a = rig.a
            val b = rig.b
            val dialFromB = rig.dialFrom(b)
            val dialFromA = rig.dialFrom(a) // Issued, held by the rig: no link exists for it yet.

            rig.connect(dialFromB, from = b, to = a)
            rig.quiesce(still = 2)
            await("B's link to be admitted at A") { a.links(b.own).singleOrNull()?.peered == true }
            val aInbound = a.links(b.own).single().linkId
            val bOutbound = dialFromB.link

            rig.connect(dialFromA, from = a, to = b)
            await("A's link to be up at B") { b.links(a.own).any { it.direction == LinkDirection.INBOUND } }
            val bInbound = b.links(a.own).single { it.direction == LinkDirection.INBOUND }.linkId

            // Relay by hand until B has closed its losing link and written its
            // hello, HOLDING everything B writes on its winning inbound link.
            // The close is written by B's policy thread and the hello by its
            // reader thread, so either can reach the fake first.
            val heldForA = mutableListOf<ByteArray>()
            var bClosedItsLoser = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!bClosedItsLoser || heldForA.isEmpty()) {
                if (System.currentTimeMillis() > deadline) {
                    fail("B closed its loser: $bClosedItsLoser; frames held for A: ${heldForA.size}")
                }
                when (val m = a.fake.pollHostMessage(20)) {
                    null -> Unit
                    is HostMessage.Data -> when (m.link) {
                        dialFromA.link -> b.fake.send(SidecarMessage.Data(bInbound, m.payload))
                        aInbound -> if (!bClosedItsLoser) b.fake.send(SidecarMessage.Data(bOutbound, m.payload))
                        else -> fail("A wrote on an unknown link ${m.link}")
                    }
                    else -> fail("A wrote $m while B's hello was held")
                }
                when (val m = b.fake.pollHostMessage(20)) {
                    null -> Unit
                    is HostMessage.Data -> when (m.link) {
                        bInbound -> heldForA += m.payload
                        bOutbound -> if (!bClosedItsLoser) a.fake.send(SidecarMessage.Data(aInbound, m.payload))
                        else -> fail("B wrote on an unknown link ${m.link}")
                    }
                    is HostMessage.CloseLink -> {
                        assertEquals(bOutbound, m.link, "B closes its OUTBOUND link, the tie-break loser at the larger id")
                        rig.deliverDown(b, bOutbound, "closed by B")
                        rig.deliverDown(a, aInbound, "peer closed the link")
                        bClosedItsLoser = true
                    }
                    else -> fail("B wrote $m while its hello was held")
                }
            }
            await("A's peered inbound link to be gone") { a.links(b.own).none { it.linkId == aInbound } }
            // Barrier: a sighting of A's own key is queued behind the down.
            a.discover(a.own)
            await("A's policy to have processed the down") { a.peering.counters.selfDropped.count == 1L }

            heldForA.forEach { a.fake.send(SidecarMessage.Data(dialFromA.link, it)) }
        }
    }
}
