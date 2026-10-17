package civictech.agora

import civictech.agora.BatchReference.NodeSpec
import civictech.agora.cell.ClaimCell
import civictech.agora.cell.EdgeCell
import civictech.agora.cell.Polarity
import civictech.agora.semantics.GatedLogOdds
import civictech.agora.semantics.GradualSemantics
import civictech.agora.semantics.JeffreyNaiveBayes
import civictech.agora.semantics.SemanticsCatalog
import civictech.agora.semantics.WeightedLogOdds
import civictech.cell.CellRef
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The catalog exit criterion for agora's scalar influence representation:
 * every product-energy layer must agree with the batch reference on DAGs,
 * while the layers that actually differ from DF-QuAD must show that the
 * test is running their own math.
 */
class CatalogExitTest {

    private val q = 1e-3

    @Test
    fun `product-energy catalog semantics match the batch fixpoint on acyclic graphs`() {
        val productEnergyIds = SemanticsCatalog.IDS.filter { id ->
            !SemanticsCatalog.of(id).separatesCredence
        }
        val divergenceSeen = productEnergyIds.associateWith { false }.toMutableMap()

        for (id in productEnergyIds) {
            val semantics = SemanticsCatalog.of(id)
            for (seed in 0L until 20L) {
                val h = Harness(seed, quiescence = q, semantics = semantics)
                val rnd = Random(seed)
                val model = mutableMapOf<CellRef, NodeSpec>()

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
                            if (!closes) {
                                val polarity = if (rnd.nextBoolean()) Polarity.ATTACK else Polarity.SUPPORT
                                val ref = h.service.createEdge(source, target, polarity)
                                model[ref] = NodeSpec(
                                    polarity = polarity,
                                    source = source,
                                    target = target,
                                )
                            }
                        }

                        in 6..8 -> if (model.isNotEmpty()) {
                            val node = anyNode()
                            val user = "u${rnd.nextInt(3)}"
                            val value = if (rnd.nextInt(5) == 0) null else rnd.nextInt(101) / 100.0
                            h.service.setStance(node, user, value)
                            val spec = model.getValue(node)
                            model[node] = spec.copy(
                                stances = if (value == null) {
                                    spec.stances - user
                                } else {
                                    spec.stances + (user to value)
                                },
                            )
                        }

                        else -> if (model.isNotEmpty() && rnd.nextInt(2) == 0) {
                            val node = anyNode()
                            h.service.remove(node)

                            // Mirror the service's cascading removal in the batch model.
                            val doomed = mutableSetOf(node)
                            var grew = true
                            while (grew) {
                                grew = doomed.addAll(model.filter { (ref, spec) ->
                                    ref !in doomed && spec.polarity != null &&
                                        (spec.source in doomed || spec.target in doomed)
                                }.keys)
                            }
                            doomed.forEach(model::remove)
                        }
                    }
                    if (rnd.nextInt(5) == 0) h.runToIdle()
                }
                h.runToIdle()

                val batch = BatchReference.solve(model, semantics)
                val dfQuadBatch = if (id == "dfquad") null else BatchReference.solve(model)
                model.keys.forEach { ref ->
                    val incremental = h.service.hub.credenceOf(ref) ?: 0.5
                    val expected = batch.getValue(ref)
                    assertTrue(
                        abs(incremental - expected) <= 1e-9,
                        "$id seed $seed node $ref: incremental $incremental vs batch $expected",
                    )
                    if (dfQuadBatch != null &&
                        abs(incremental - dfQuadBatch.getValue(ref)) > 1e-9
                    ) {
                        divergenceSeen[id] = true
                    }
                }
            }
        }

        productEnergyIds.filter { it != "dfquad" }.forEach { id ->
            assertTrue(
                divergenceSeen.getValue(id),
                "$id never diverged from the DF-QuAD batch — the catalog assertion has no teeth",
            )
        }
    }

    @Test
    fun `a credence-separating semantics is refused by the cells`() {
        val claimFailure = assertFailsWith<IllegalArgumentException> {
            ClaimCell(semantics = GatedLogOdds())
        }
        assertTrue(claimFailure.message.orEmpty().contains("separately"))

        val edgeFailure = assertFailsWith<IllegalArgumentException> {
            EdgeCell(Polarity.ATTACK, semantics = JeffreyNaiveBayes())
        }
        assertTrue(edgeFailure.message.orEmpty().contains("separately"))

        assertEquals(0.5, ClaimCell(semantics = WeightedLogOdds()).credence)
    }

    private fun reaches(model: Map<CellRef, NodeSpec>, from: CellRef, to: CellRef): Boolean {
        val seen = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node == to) return true
            if (!seen.add(node)) continue
            model[node]?.target?.let(stack::add)
            model.forEach { (ref, spec) ->
                if (spec.source == node) stack.add(ref)
            }
        }
        return false
    }
}
