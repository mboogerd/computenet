package civictech.agora

import civictech.agora.BatchReference.NodeSpec
import civictech.agora.cell.ClaimCell
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The M17 exit criterion (the DataflowSuiteExitTest idiom): on every seed,
 * a randomly grown-and-churned argumentation graph — claims, attack/support
 * edges including edge-on-edge and (on odd seeds) cycle-closing edges,
 * stance churn, cascading removals — driven through the deterministic
 * simulation equals a plain batch fixpoint over the final state. DAG seeds
 * must match to FP exactness; cyclic seeds within a bound of the head
 * threshold. A retraction-blind reference (removed edges still counted)
 * must diverge, proving the assertions have teeth.
 */
class AgoraExitTest {

    private val q = 1e-3

    @Test
    fun `random argumentation graphs match the batch fixpoint on every seed`() {
        var removalDivergenceSeen = false

        for (seed in 0L until 100L) {
            val cyclic = seed % 2 == 1L
            val h = Harness(seed, quiescence = q)
            val rnd = Random(seed)
            val model = mutableMapOf<CellRef, NodeSpec>()
            val removedEdges = mutableMapOf<CellRef, NodeSpec>()

            fun anyNode(): CellRef = model.keys.toList()[rnd.nextInt(model.size)]

            repeat(60) { step ->
                when (rnd.nextInt(10)) {
                    in 0..2 -> {
                        val ref = h.service.createClaim("c$step")
                        model[ref] = NodeSpec()
                    }

                    in 3..5 -> if (model.size >= 2) {
                        val source = anyNode()
                        val target = anyNode()
                        val closes = source == target || reaches(model, from = target, to = source)
                        if (!closes || cyclic) {
                            val pol = if (rnd.nextBoolean()) Polarity.ATTACK else Polarity.SUPPORT
                            val ref = h.service.createEdge(source, target, pol)
                            model[ref] = NodeSpec(polarity = pol, source = source, target = target)
                        }
                    }

                    in 6..8 -> if (model.isNotEmpty()) {
                        val node = anyNode()
                        val user = "u${rnd.nextInt(3)}"
                        val value = if (rnd.nextInt(5) == 0) null else rnd.nextInt(101) / 100.0
                        h.service.setStance(node, user, value)
                        val spec = model.getValue(node)
                        model[node] = spec.copy(
                            stances = if (value == null) spec.stances - user else spec.stances + (user to value)
                        )
                    }

                    else -> if (model.isNotEmpty() && rnd.nextInt(2) == 0) {
                        val node = anyNode()
                        h.service.remove(node)
                        // mirror the service's cascade exactly
                        val doomed = mutableSetOf(node)
                        var grew = true
                        while (grew) {
                            grew = doomed.addAll(model.filter { (ref, spec) ->
                                ref !in doomed && spec.polarity != null &&
                                    (spec.source in doomed || spec.target in doomed)
                            }.keys)
                        }
                        doomed.forEach { ref ->
                            model.remove(ref)?.let { if (it.polarity != null) removedEdges[ref] = it }
                        }
                    }
                }
                if (rnd.nextInt(5) == 0) h.runToIdle()
            }
            h.runToIdle()

            val batch = BatchReference.solve(model)
            val tolerance = if (cyclic) 25 * q else 1e-9
            model.keys.forEach { ref ->
                val incremental = h.service.hub.credenceOf(ref) ?: 0.5
                assertTrue(
                    abs(incremental - batch.getValue(ref)) <= tolerance,
                    "seed $seed node $ref: incremental $incremental vs batch ${batch.getValue(ref)} (tol $tolerance)"
                )
            }

            // retraction-blind control: re-admit removed edges whose endpoints
            // survive; if that reference ever disagrees, retraction handling
            // is load-bearing and the harness would catch its absence.
            if (removedEdges.isNotEmpty()) {
                val blind = model.toMutableMap()
                var grew = true
                while (grew) {
                    grew = false
                    removedEdges.forEach { (ref, spec) ->
                        if (ref !in blind && spec.source in blind && spec.target in blind) {
                            blind[ref] = spec; grew = true
                        }
                    }
                }
                if (blind.size > model.size) {
                    val blindBatch = BatchReference.solve(blind)
                    removalDivergenceSeen = removalDivergenceSeen || model.keys.any { ref ->
                        abs((h.service.hub.credenceOf(ref) ?: 0.5) - blindBatch.getValue(ref)) > tolerance
                    }
                }
            }
        }

        assertTrue(removalDivergenceSeen, "retraction-blind control never diverged — the harness has no teeth")
    }

    private fun reaches(model: Map<CellRef, NodeSpec>, from: CellRef, to: CellRef): Boolean {
        val seen = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n == to) return true
            if (!seen.add(n)) continue
            model[n]?.target?.let { stack.add(it) }
            model.forEach { (ref, spec) -> if (spec.source == n) stack.add(ref) }
        }
        return false
    }
}
