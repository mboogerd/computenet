package civictech.iroh.discover

import civictech.iroh.LinkDirection
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * The scenario is played in three relay orders — A's dial connected first, B's
 * first, and both connected before either hello moves. A real LAN picks one of
 * those and a test over real sidecars would sample it; here the test picks all
 * three, and asserts the same end state from each. Nothing sleeps: frames move
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
}
