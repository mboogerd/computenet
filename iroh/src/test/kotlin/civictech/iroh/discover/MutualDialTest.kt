package civictech.iroh.discover

import civictech.iroh.HostMessage
import civictech.iroh.LinkDirection
import civictech.iroh.SidecarMessage
import civictech.iroh.await
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    /**
     * computenet-311xs, the interleaving CI hit in the A-first order: A's
     * reader has settled A's own dial — its OUTBOUND link is up — but the dial
     * thread has not yet returned to register it with the node when B's hello
     * on the losing INBOUND link is judged.
     *
     * The dial thread is held in that window ([FakeNode.holdDialThreads]), so
     * the order is forced rather than sampled. The gate must still see the
     * outbound link and close the inbound one quietly, BEFORE A writes a
     * single frame on it (`[DSC2-DIAL-05]`), and the one closed link must be
     * counted once. Unfixed, A judged against a registry that lacked its own
     * link, admitted the loser and announced on it; the far side then closed
     * it, and A's count ended at 0 (or, with the close queued behind the down
     * before #1008, at 2).
     *
     * Mutation: drop the not-yet-registered links from `DiscoveredPeering.seed`
     * — A answers on the loser and this fails on the first assertion.
     */
    @Test
    fun `A's hello-judging reader sees its own settled dial before the dial thread registers it`() {
        runScenario("A-first, A's dial thread held") { rig ->
            val a = rig.a
            val b = rig.b
            val release = a.holdDialThreads()
            try {
                val dialFromA = rig.dialFrom(a)
                val dialFromB = rig.dialFrom(b)
                rig.connect(dialFromA, from = a, to = b)
                rig.connect(dialFromB, from = b, to = a)
                await("B's link to be up at A") { a.links(b.own).any { it.direction == LinkDirection.INBOUND } }
                val aInbound = a.links(b.own).single { it.direction == LinkDirection.INBOUND }.linkId
                await("A to answer B's hello on its losing inbound link") {
                    rig.pump()
                    rig.written.any { (who, m) -> who == "A" && m.linkOf() == aInbound }
                }
                assertTrue(
                    a.links(b.own).none { it.direction == LinkDirection.OUTBOUND },
                    "the window was held: A's dial thread has not registered its outbound link",
                )
                val onLoser = rig.written.filter { (who, m) -> who == "A" && m.linkOf() == aInbound }.map { it.second }
                assertIs<HostMessage.CloseLink>(
                    onLoser.first(),
                    "A closes the losing inbound link before writing anything on it: $onLoser",
                )
                assertTrue(onLoser.none { it is HostMessage.Data }, "A announced nothing on the loser: $onLoser")
                rig.quiesce(still = 2)
                assertEquals(1L, a.peering.counters.tieBreakClosed.count, "A counted the loser at its verdict, once")
            } finally {
                release()
            }
        }
    }

    /**
     * computenet-311xs, the count-0 variant: B's hello reaches A BEFORE A's
     * own dial is settled, so A admits the inbound link — legitimately, it was
     * the only link — and answers on it. A's dial then settles, the dial
     * thread is held before it registers the link, and B closes its outbound
     * link as ITS loser on A's answer. That `LINK_DOWN` is the only place A
     * learns of the close, and it lands while A's winning link is up but not
     * yet registered: `onLinkDown` must count it as the opposite direction
     * being up. Unfixed, A's count stayed at 0 — B's later hello on the
     * winner finds nothing left to close.
     *
     * The same window also decides the re-dial: `PeerTable.linkDown` must see
     * the settled winner, or the loser's down reads as "no link left" and A
     * dials B a second time.
     *
     * Mutations: drop the not-yet-registered links from `onLinkDown`'s
     * `oppositeLinkUp` — A counts 0; drop the `seed` before `table.linkDown`
     * — A re-dials (2 dials, not 1).
     */
    @Test
    fun `a peered loser whose down lands before this node's own settled dial is registered is counted once`() {
        runScenario("B's hello first, A's dial thread held") { rig ->
            val a = rig.a
            val b = rig.b
            val release = a.holdDialThreads()
            try {
                val dialFromB = rig.dialFrom(b)
                val dialFromA = rig.dialFrom(a) // Written; A's dial thread is now held.

                rig.connect(dialFromB, from = b, to = a)
                await("B's link to be up at A") { a.links(b.own).isNotEmpty() }
                val aInbound = a.links(b.own).single().linkId
                val bOutbound = dialFromB.link

                // Relay B's hello to A; hold A's answer.
                var aAnswer: ByteArray? = null
                val deadline = System.currentTimeMillis() + 30_000
                while (aAnswer == null) {
                    if (System.currentTimeMillis() > deadline) fail("A never answered B's hello")
                    when (val m = b.fake.pollHostMessage(20)) {
                        null -> Unit
                        is HostMessage.Data -> a.fake.send(SidecarMessage.Data(aInbound, m.payload))
                        else -> fail("B wrote $m before A answered")
                    }
                    when (val m = a.fake.pollHostMessage(20)) {
                        null -> Unit
                        is HostMessage.Data -> if (m.link == aInbound) aAnswer = m.payload
                        else -> fail("A wrote $m before answering")
                    }
                }
                await("A to admit B's link") { a.links(b.own).single().peered }

                // A's own dial settles now, and its thread stays held. B's
                // LINK_UP for it is written before A's answer is relayed, so B
                // judges that answer with both directions up.
                rig.connect(dialFromA, from = a, to = b)
                b.fake.send(SidecarMessage.Data(bOutbound, aAnswer!!))
                var bClosed = false
                while (!bClosed) {
                    if (System.currentTimeMillis() > deadline) fail("B never closed its losing outbound link")
                    when (val m = b.fake.pollHostMessage(20)) {
                        null -> Unit
                        is HostMessage.CloseLink -> {
                            assertEquals(bOutbound, m.link, "B closes its OUTBOUND link, the tie-break loser at the larger id")
                            rig.deliverDown(b, bOutbound, "closed by B")
                            rig.deliverDown(a, aInbound, "peer closed the link")
                            bClosed = true
                        }
                        else -> fail("B wrote $m before closing its loser")
                    }
                }
                await("A's peered inbound link to be gone") { a.links(b.own).none { it.linkId == aInbound } }
                // Barrier: a sighting of A's own key is queued behind the down.
                a.discover(a.own)
                await("A's policy to have processed the down") { a.peering.counters.selfDropped.count == 1L }
                assertTrue(a.links(b.own).isEmpty(), "the window was held: A's dial thread has not registered its outbound link")
                assertEquals(1L, a.peering.counters.tieBreakClosed.count, "A counted the loser at its down, while its winner was settled but unregistered")
            } finally {
                release()
            }
        }
    }
}

/** The link a host message is about, or null for a control message. */
private fun HostMessage.linkOf(): Long? = when (this) {
    is HostMessage.Data -> link
    is HostMessage.CloseLink -> link
    else -> null
}
