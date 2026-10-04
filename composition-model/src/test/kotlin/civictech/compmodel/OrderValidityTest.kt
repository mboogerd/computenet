package civictech.compmodel

import civictech.compmodel.cell.Budget
import civictech.compmodel.cell.CellModel
import civictech.compmodel.cell.FormationCheck
import civictech.compmodel.cell.LeafKind
import civictech.compmodel.cell.Layer
import civictech.compmodel.cell.OrderValidity
import civictech.compmodel.cell.Scenario
import civictech.compmodel.cell.code
import civictech.compmodel.cell.permutations
import civictech.compmodel.check.Explorer
import civictech.compmodel.check.Report
import civictech.compmodel.check.Seeds
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Order validity: all 5040 orders of {D,O,P,F,A,X,S}, judged by exhaustive fault exploration
 * (via the projection argument in [OrderValidity]) and by the transcribed formation rules
 * F1-F6. Disagreements are FINDINGS and are pinned here as data, so a model change that moves
 * them fails this test instead of silently re-tuning the comparison.
 */
class OrderValidityTest {

    /** The disagreement recorded in model-results.md §4 (model-valid, refused by the rules). */
    private val expectedModelOnly = setOf("DPFAXOS", "DFPAXOS", "PDOFAXS", "PDFOAXS", "PDFAOXS", "PDFAXOS")

    @Test
    fun `formation-accepted orders all keep the invariants, and the disagreement is exactly the recorded findings`() {
        val t0 = System.nanoTime()
        val verdicts = OrderValidity.runAll()
        verdicts.forEach { (sc, m) ->
            val states = m.values.sumOf { it.exploration.states.toLong() }
            Report.line("[order/$sc] ${m.size} orders, ${m.values.count { it.exploration.holds }} hold " +
                "${m.values.filter { it.exploration.holds }.map { it.order.code() }}; states explored $states; " +
                "every holding exploration exhaustive=${m.values.filter { it.exploration.holds }.all { it.exploration.complete }}")
            m.values.filter { it.exploration.holds }.forEach { it.exploration.complete shouldBe true }
        }
        val all = permutations(Layer.CANONICAL)
        val model = all.filter { OrderValidity.modelValid(it, verdicts).first }.map { it.code() }.toSet()
        val formation = all.filter { FormationCheck.accepts(it) }.map { it.code() }.toSet()
        Report.line("[order] model-valid ${model.size}: ${model.sorted()}")
        Report.line("[order] formation-accepted ${formation.size}: ${formation.sorted()}")
        for (o in (model - formation).sorted()) Report.line("[order] model-only $o refused by ${FormationCheck.check(Layer.parse(o)).map { it.rule + ": " + it.reason }}")
        for (o in (formation - model).sorted()) {
            val (_, failing) = OrderValidity.modelValid(Layer.parse(o), verdicts)
            failing.forEach { Report.line("[order] formation-only $o:\n" + it.exploration.counterexample!!.render()) }
        }
        Report.line("[order] time ${(System.nanoTime() - t0) / 1_000_000} ms")
        withClue("orders the rules accept but the model refutes (soundness)") { (formation - model).shouldBeEmpty() }
        (model - formation) shouldBe expectedModelOnly
    }

    /**
     * Spot check of the projection argument: random walks on full 7-layer stacks under a
     * combined scenario. A violation on a full order must be matched by an invalid projection
     * verdict (the reduction never declares valid an order that the full model refutes).
     */
    @Test
    fun `projection argument spot check on full stacks`() {
        val verdicts = OrderValidity.runAll()
        val combined = Scenario(
            "combined", Layer.entries.toSet(), LeafKind.EFFECT, lanes = 2, perLane = 1, waves = true, staleLane = 1,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1, designate = 1),
        )
        val rnd = Random(7)
        val all = permutations(Layer.CANONICAL)
        val sample = (all.filter { OrderValidity.modelValid(it, verdicts).first } + List(40) { all[rnd.nextInt(all.size)] }).distinct()
        var refuted = 0
        for (order in sample) {
            val w = Explorer.walks(CellModel(combined, order), Seeds.all().take(maxOf(20, Seeds.count() / 4)), maxSteps = 60)
            if (!w.holds) {
                refuted++
                withClue("full order ${order.code()} refuted by a walk but its projections all hold:\n${w.counterexample!!.render()}") {
                    OrderValidity.modelValid(order, verdicts).first shouldBe false
                }
            }
        }
        Report.line("[order/spot-check] ${sample.size} full orders walked, $refuted refuted, all refutations matched by projection verdicts")
    }
}
