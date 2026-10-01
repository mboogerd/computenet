package civictech.agora

import civictech.agora.cell.ClaimCell
import civictech.agora.cell.EdgeCell
import civictech.agora.cell.InfluenceDelta
import civictech.agora.cell.Polarity.ATTACK
import civictech.agora.cell.Polarity.SUPPORT
import civictech.agora.cell.credenceOf
import civictech.cell.CellRef
import civictech.cell.host.TopologyLink
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Acceptance coverage for agora's admitted, staged graph wiring. */
class AdmittedWiringTest {

    private fun Harness.edge(ref: CellRef): EdgeCell =
        service.cells().filterIsInstance<EdgeCell>().single { it.ref == ref }

    private fun Harness.claim(ref: CellRef): ClaimCell =
        service.cells().filterIsInstance<ClaimCell>().single { it.ref == ref }

    private fun linkTo(links: Set<TopologyLink>, from: CellRef, to: CellRef): TopologyLink =
        links.single { it.from.cell == from && it.to.cell == to }

    private fun portId(owner: Any, name: String) = PortRegistry.of(owner)[name]!!.ref.id

    @Test
    fun `service admits three staged links and lands cycle heads on feedback`() {
        val h = Harness(seed = 11L)
        val source = h.service.createClaim("source")
        val target = h.service.createClaim("target")

        val before = h.registry.localLinks()
        val first = h.service.createEdge(source, target, SUPPORT)
        val firstCell = h.edge(first)
        val firstAdded = h.registry.localLinks() - before

        assertEquals(3, firstAdded.size)
        assertFalse(h.service.nodeInfo(first)!!.head)
        assertEquals(portId(firstCell, "credenceOutlet"), linkTo(firstAdded, first, h.service.hub.ref).from.id)
        assertEquals(portId(h.claim(target), "influenceInlet"), linkTo(firstAdded, first, target).to.id)
        assertEquals(firstCell.sourceInlet.ref.id, linkTo(firstAdded, source, first).to.id)

        val beforeSecond = h.registry.localLinks()
        val second = h.service.createEdge(target, source, SUPPORT)
        val secondCell = h.edge(second)
        val secondAdded = h.registry.localLinks() - beforeSecond

        assertEquals(3, secondAdded.size)
        assertTrue(h.service.nodeInfo(second)!!.head)
        val secondSourceLink = linkTo(secondAdded, target, second)
        // computenet-lzcce: PropagateFeedbackInlet's registered ref is random;
        // assert the actual EdgeCell port, not PortRef.of(edge, "feedbackInlet").
        assertEquals(secondCell.feedbackInlet.ref.id, secondSourceLink.to.id)
        assertNotEquals(secondCell.sourceInlet.ref.id, secondSourceLink.to.id)

        h.runToIdle()
        assertEquals(0L, h.host.supervisionAccounting().deadLetters)
    }

    @Test
    fun `self loop lands on its feedback inlet`() {
        val h = Harness(seed = 12L)
        val claim = h.service.createClaim("self")
        val before = h.registry.localLinks()
        val edge = h.service.createEdge(claim, claim, SUPPORT)
        val edgeCell = h.edge(edge)

        val added = h.registry.localLinks() - before
        assertEquals(3, added.size)
        assertTrue(h.service.nodeInfo(edge)!!.head)
        assertEquals(edgeCell.feedbackInlet.ref.id, linkTo(added, claim, edge).to.id)

        h.runToIdle()
        assertEquals(0L, h.host.supervisionAccounting().deadLetters)
    }

    @Test
    fun `quiesced cycle records kernel weak tier absorption`() {
        val h = Harness(seed = 15L, quiescence = 1e-3)
        val a = h.service.createClaim("A")
        val b = h.service.createClaim("B")
        h.service.createEdge(a, b, ATTACK)
        val head = h.service.createEdge(b, a, ATTACK)
        h.service.setStance(a, "u", 0.99)
        h.service.setStance(b, "u", 0.99)

        h.runToIdle()

        assertEquals(
            true,
            h.edge(head).feedbackInlet.lastQuiescent,
            "the feedback inlet must record the thresholded lap that stopped the cycle",
        )
    }

    @Test
    fun `headless direct closing link is refused by admission`() {
        val h = Harness(seed = 13L)
        val source = h.service.createClaim("source")
        val target = h.service.createClaim("target")
        val edge = h.service.createEdge(source, target, SUPPORT)

        val result = h.host.managementInlet.call.connect(
            target,
            "credenceOutlet",
            edge,
            "sourceInlet",
            LinkOptions(staged = true),
        )

        assertTrue(result is LinkResult.Rejected)
        val reason = (result as LinkResult.Rejected).reason
        assertTrue(reason.contains("CycleWithoutHead"), reason)
    }

    @Test
    fun `remove drains staged edge frames before target retraction`() {
        val h = Harness(seed = 14L)
        val source = h.service.createClaim("source")
        val target = h.service.createClaim("target")
        val edge = h.service.createEdge(source, target, SUPPORT)
        h.runToIdle()

        h.service.setStance(target, "u", 0.8)
        h.runToIdle()
        val edgeCell = h.edge(edge)
        edgeCell.influenceOutlet.call.propagate(InfluenceDelta(edge, SUPPORT, 0.4, size = 0.1))
        assertTrue((h.host.stagedWorkDepth()[target] ?: 0) > 0)
        // An edge-derived influence is still staged for the target when the
        // edge is removed; its admitted close must trail that accepted frame.
        h.service.remove(edge)
        h.runToIdle()

        assertEquals(0L, h.host.supervisionAccounting().deadLetters)
        assertEquals(0.8, h.service.hub.credenceOf(target) ?: error("target credence missing"), 1e-9)
        assertTrue(h.registry.localLinks().none { it.from.cell == edge || it.to.cell == edge })
    }
}
