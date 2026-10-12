package civictech.agora

import civictech.agora.cell.Polarity.ATTACK
import civictech.agora.cell.Polarity.SUPPORT
import civictech.cell.CellRef
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cycles quiesce because (a) the base clamp keeps single-cycle loop gain
 * below 1 and (b) the cycle-closing edge is a head whose feedback inlet
 * absorbs sub-threshold laps (spec 21 §Cycles, app-side). The headless probe
 * documents what the head buys: floating-point resolution is the only
 * remaining threshold, so laps multiply by orders of magnitude.
 */
class CycleQuiescenceTest {

    private val q = 1e-3

    private fun Harness.assertMatchesBatch(model: Map<CellRef, BatchReference.NodeSpec>, tolerance: Double) {
        val batch = BatchReference.solve(model)
        model.keys.forEach { ref ->
            val inc = service.hub.credenceOf(ref) ?: 0.5
            val ref9 = batch.getValue(ref)
            assertTrue(abs(inc - ref9) <= tolerance, "node $ref: incremental $inc vs batch $ref9")
        }
    }

    @Test
    fun `mutual attack at extreme stances quiesces near the fixpoint`() {
        val h = Harness(seed = 1L, quiescence = q)
        val a = h.service.createClaim("A")
        val b = h.service.createClaim("B")
        val e1 = h.service.createEdge(a, b, ATTACK)
        val e2 = h.service.createEdge(b, a, ATTACK) // closes the cycle → head
        h.service.setStance(a, "u1", 0.99)
        h.service.setStance(b, "u1", 0.99)
        h.runToIdle()
        h.assertMatchesBatch(
            mapOf(
                a to BatchReference.NodeSpec(stances = mapOf("u1" to 0.99)),
                b to BatchReference.NodeSpec(stances = mapOf("u1" to 0.99)),
                e1 to BatchReference.NodeSpec(polarity = ATTACK, source = a, target = b),
                e2 to BatchReference.NodeSpec(polarity = ATTACK, source = b, target = a),
            ),
            tolerance = 25 * q,
        )
    }

    @Test
    fun `self-attack through an edge on the edge quiesces`() {
        // A attacks B; the attack is then attacked by an edge sourced at B:
        // B's fate feeds back into what weakens it.
        val h = Harness(seed = 2L, quiescence = q)
        val a = h.service.createClaim("A")
        val b = h.service.createClaim("B")
        val e1 = h.service.createEdge(a, b, ATTACK)
        val e2 = h.service.createEdge(b, e1, ATTACK) // b ⇝ e1 ⇝ b: head
        h.service.setStance(a, "u1", 0.9)
        h.service.setStance(b, "u1", 0.9)
        h.runToIdle()
        h.assertMatchesBatch(
            mapOf(
                a to BatchReference.NodeSpec(stances = mapOf("u1" to 0.9)),
                b to BatchReference.NodeSpec(stances = mapOf("u1" to 0.9)),
                e1 to BatchReference.NodeSpec(polarity = ATTACK, source = a, target = b),
                e2 to BatchReference.NodeSpec(polarity = ATTACK, source = b, target = e1),
            ),
            tolerance = 25 * q,
        )
    }

    @Test
    fun `three-cycle with mixed polarities quiesces`() {
        val h = Harness(seed = 3L, quiescence = q)
        val a = h.service.createClaim("A")
        val b = h.service.createClaim("B")
        val c = h.service.createClaim("C")
        val e1 = h.service.createEdge(a, b, ATTACK)
        val e2 = h.service.createEdge(b, c, SUPPORT)
        val e3 = h.service.createEdge(c, a, ATTACK) // head
        listOf(a, b, c).forEach { h.service.setStance(it, "u1", 0.95) }
        h.runToIdle()
        h.assertMatchesBatch(
            mapOf(
                a to BatchReference.NodeSpec(stances = mapOf("u1" to 0.95)),
                b to BatchReference.NodeSpec(stances = mapOf("u1" to 0.95)),
                c to BatchReference.NodeSpec(stances = mapOf("u1" to 0.95)),
                e1 to BatchReference.NodeSpec(polarity = ATTACK, source = a, target = b),
                e2 to BatchReference.NodeSpec(polarity = SUPPORT, source = b, target = c),
                e3 to BatchReference.NodeSpec(polarity = ATTACK, source = c, target = a),
            ),
            tolerance = 25 * q,
        )
    }

    @Test
    fun `headless probe - without the absorb gate the loop runs to FP resolution`() {
        // What the head buys, measured: a headed loop stops when laps fall
        // below the ε threshold (~3 decades of contraction); a headless loop
        // contracts all the way to the FP ulp (~16 decades) — a ~5× lap
        // multiplier here, and the ONLY reason it terminates at all is that
        // the base clamp makes every single loop contractive; the decided
        // kernel hop guard (spec 21 §Cycles, unbuilt) is the backstop this
        // app-level model still lacks.
        fun stepsFor(quiescence: Double): Int {
            val h = Harness(seed = 4L, quiescence = quiescence)
            val a = h.service.createClaim("A")
            val b = h.service.createClaim("B")
            h.service.createEdge(a, b, ATTACK)
            h.service.createEdge(b, a, ATTACK)
            h.runToIdle()
            h.service.setStance(a, "u1", 0.99)
            h.service.setStance(b, "u1", 0.99)
            return h.runToIdle(budget = 2_000_000)
        }
        val headed = stepsFor(q)
        val headless = stepsFor(0.0)
        assertTrue(
            headless > 3 * headed,
            "expected headless ($headless steps) to dwarf headed ($headed steps)"
        )
    }
}
