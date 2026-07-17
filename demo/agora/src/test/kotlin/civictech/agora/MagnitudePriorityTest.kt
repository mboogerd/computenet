package civictech.agora

import civictech.agora.cell.Polarity.SUPPORT
import civictech.cell.CellRef
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The user-visible half of magnitude scheduling: on one host, a dramatic
 * credence shift races ahead of a staged-earlier micro-adjustment — the
 * derived claim of the loud chain reaches the read model first. With the
 * policy off, staging order (FIFO) wins instead. Deterministic single-host
 * simulation either way.
 */
class MagnitudePriorityTest {

    private fun hubOrder(magnitude: Boolean): Pair<Int, Int> {
        val log = mutableListOf<CellRef>()
        val h = Harness(seed = 7L, magnitude = magnitude, onCredence = { ref, _ -> log += ref })
        val a = h.service.createClaim("A")
        val a2 = h.service.createClaim("A2")
        val b = h.service.createClaim("B")
        val b2 = h.service.createClaim("B2")
        h.service.createEdge(a, a2, SUPPORT)
        h.service.createEdge(b, b2, SUPPORT)
        // pre-position B near where its micro-flip will land
        h.service.setStance(b, "u1", 0.50)
        h.runToIdle()
        log.clear()

        // staged first: the whisper; staged second: the shout
        h.service.setStance(b, "u1", 0.52)
        h.service.setStance(a, "u1", 0.99)
        h.runToIdle()

        return log.indexOf(a2) to log.indexOf(b2)
    }

    @Test
    fun `the dramatic chain reaches the read model first under magnitude scheduling`() {
        val (a2, b2) = hubOrder(magnitude = true)
        assertTrue(a2 in 0 until b2, "expected A2 (index $a2) before B2 (index $b2)")
    }

    @Test
    fun `without magnitude scheduling staging order wins`() {
        val (a2, b2) = hubOrder(magnitude = false)
        assertTrue(b2 in 0 until a2, "expected B2 (index $b2) before A2 (index $a2)")
    }
}
