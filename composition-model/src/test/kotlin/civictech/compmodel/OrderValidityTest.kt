package civictech.compmodel

import civictech.compmodel.cell.Budget
import civictech.compmodel.cell.CellModel
import civictech.compmodel.cell.FormationCheck
import civictech.compmodel.cell.LeafKind
import civictech.compmodel.cell.Layer
import civictech.compmodel.cell.OrderValidity
import civictech.compmodel.cell.Scenario
import civictech.compmodel.cell.Variant
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
 * Order validity: all 5040 orders of {D,O,P,F,A,X,S}, judged by exhaustive fault exploration of
 * each order's projection onto every scenario's layer subset ([OrderValidity]) and by the
 * transcribed formation rules F1-F6.
 *
 * The asserted property is the intended one, not the present answer:
 * - **soundness**: every order the rules accept keeps every invariant (accepted ⊆ valid);
 * - **explained completeness**: every order the model finds valid but the rules refuse is
 *   matched by a NAMED witness below, which states why the rule is stricter than correctness
 *   requires. An unexplained one fails the test (it is a new finding to record).
 * The projected verdicts are then confirmed on the FULL 7-layer stacks (`full-stack` test).
 */
class OrderValidityTest {

    /** One exploration of every projection per run, shared by both tests. */
    private object Shared { val verdicts by lazy { OrderValidity.runAll() } }

    private val O = Layer.OUTBOX
    private val X = Layer.EFFECT_DEDUP
    private val S = Layer.SUPERVISED
    private val D = Layer.DURABLE

    private fun moveDirectlyInsideD(order: List<Layer>, l: Layer): List<Layer> {
        val rest = order - l
        val d = rest.indexOf(D)
        return rest.take(d + 1) + l + rest.drop(d + 1)
    }

    /** Named reasons an order the rules refuse may keep every invariant. */
    private val witnesses: List<Pair<String, (List<Layer>) -> Boolean>> = listOf(
        "OV-2: O between X and S. O holds outbound frames only, so F3's reason (a frame held between " +
            "X and the leaf while its duplicate passes X) does not apply; COH fixes O directly inside D " +
            "for a deterministic manifest, not for correctness" to { o ->
                o.indexOf(O) in (o.indexOf(X) + 1) until o.indexOf(S) && FormationCheck.accepts(moveDirectlyInsideD(o, O))
            },
    )

    @Test
    fun `formation-accepted orders all keep the invariants, and every model-only order has a named witness`() {
        val t0 = System.nanoTime()
        val verdicts = Shared.verdicts
        verdicts.forEach { (sc, m) ->
            val states = m.values.sumOf { it.exploration.states.toLong() }
            val holding = m.values.filter { it.exploration.holds }
            Report.line("[order/$sc] ${m.size} orders, ${holding.size} hold ${holding.map { it.order.code() }}; states explored $states")
            holding.forEach { withClue("${it.order.code()} in $sc must be exhaustive") { it.exploration.complete shouldBe true } }
            // Non-vacuity: the canonical projection holds, and the scenario refutes something.
            val canonical = Layer.CANONICAL.filter { it in m.values.first().scenario.layers }.code()
            withClue("$sc: canonical projection $canonical must hold") { m.getValue(canonical).exploration.holds shouldBe true }
            withClue("$sc: some order must be refuted, or the scenario discriminates nothing") { (holding.size < m.size) shouldBe true }
        }
        val all = permutations(Layer.CANONICAL)
        val model = all.filter { OrderValidity.modelValid(it, verdicts).first }.map { it.code() }.toSet()
        val formation = all.filter { FormationCheck.accepts(it) }.map { it.code() }.toSet()
        Report.line("[order] model-valid ${model.size}: ${model.sorted()}")
        Report.line("[order] formation-accepted ${formation.size}: ${formation.sorted()}")
        val unexplained = ArrayList<String>()
        for (o in (model - formation).sorted()) {
            val w = witnesses.firstOrNull { it.second(Layer.parse(o)) }
            Report.line("[order] model-only $o refused by ${FormationCheck.check(Layer.parse(o)).map { it.rule + ": " + it.reason }}; witness: ${w?.first ?: "NONE"}")
            if (w == null) unexplained.add(o)
        }
        for (o in (formation - model).sorted()) {
            val (_, failing) = OrderValidity.modelValid(Layer.parse(o), verdicts)
            failing.forEach { Report.line("[order] formation-only $o:\n" + it.exploration.counterexample!!.render()) }
        }
        Report.line("[order] time ${(System.nanoTime() - t0) / 1_000_000} ms")
        withClue("orders the rules accept but the model refutes (soundness)") { (formation - model).shouldBeEmpty() }
        withClue("model-valid orders the rules refuse with no named witness (new findings)") { unexplained.shouldBeEmpty() }
    }

    /**
     * Scenarios that exercise every layer at once, for the full 7-layer stacks. Exhaustive runs
     * use the budgets below (each scenario twice, with fault sets chosen to stay exhaustive);
     * walks use [walkBudget], every fault at once.
     */
    private val all = Layer.entries.toSet()
    private val exhaustive: List<Scenario> = listOf(
        Scenario("full-effect-waves/crash,reconnect,suspend,designate", all, LeafKind.EFFECT, lanes = 2, perLane = 1, waves = true, staleLane = 1, ownedFirst = true,
            budget = Budget(crash = 1, reconnect = 1, suspend = 1, designate = 1)),
        Scenario("full-effect-waves/crash,restart,suspend,designate", all, LeafKind.EFFECT, lanes = 2, perLane = 1, waves = true, staleLane = 1, ownedFirst = true,
            budget = Budget(crash = 1, restart = 1, suspend = 1, designate = 1)),
        Scenario("full-stale-then-valid/crash,restart,suspend", all, LeafKind.EFFECT, lanes = 2, perLane = 1, staleLane = 1, staleThenValid = true,
            budget = Budget(crash = 1, restart = 1, suspend = 1)),
        Scenario("full-merge/crash,reconnect,restart,suspend", all, LeafKind.SET, perLane = 2,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1)),
        Scenario("full-relay/crash,reconnect,restart,suspend", all, LeafKind.RELAY, perLane = 2,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1)),
        Scenario("full-relay/crash,restart,suspend,checkpoint", all, LeafKind.RELAY, perLane = 2,
            budget = Budget(crash = 1, restart = 1, suspend = 1, checkpoint = 1)),
    )
    private val walkBudget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1, designate = 1)
    private val walked: List<Scenario> = listOf(
        Scenario("walk-effect-waves", all, LeafKind.EFFECT, lanes = 2, perLane = 1, waves = true, staleLane = 1, ownedFirst = true, budget = walkBudget),
        Scenario("walk-stale-then-valid", all, LeafKind.EFFECT, lanes = 2, perLane = 1, staleLane = 1, staleThenValid = true, budget = walkBudget.copy(designate = 0)),
        Scenario("walk-merge", all, LeafKind.SET, lanes = 2, perLane = 2, budget = walkBudget.copy(designate = 0)),
        Scenario("walk-relay", all, LeafKind.RELAY, perLane = 2, budget = walkBudget.copy(designate = 0)),
    )

    /**
     * Full stacks run with the REPLAY-1 candidate fix ([Variant.logPRelease]): without it the
     * canonical stack itself fails (`CellModelTest`, FINDING REPLAY-1), which is a defect of
     * `P`'s replay, not of any order.
     */
    private val fullVariant = Variant(logPRelease = true)

    /**
     * The projection argument confirmed on full stacks: the canonical stack holds exhaustively in
     * every [exhaustive] scenario; every order the model judges valid (accepted and witnessed
     * alike) holds under seeded walks in every [walked] scenario; and on a random sample of other
     * orders, a full-stack refutation is always matched by a refuted projection.
     */
    @Test
    fun `full-stack check of every model-valid order, and projection spot check`() {
        val verdicts = Shared.verdicts
        val orders = permutations(Layer.CANONICAL)
        val valid = orders.filter { OrderValidity.modelValid(it, verdicts).first }
        for (sc in exhaustive) holds("order/full-exhaustive/${sc.name}/${Layer.CANONICAL.code()}", CellModel(sc, Layer.CANONICAL, fullVariant))
        var steps = 0L
        for (order in valid) for (sc in walked) {
            val w = Explorer.walks(CellModel(sc, order, fullVariant), Seeds.all(), maxSteps = 80)
            steps += w.steps
            withClue("full order ${order.code()} (${sc.name}) is valid by projection but a walk refutes it:\n${w.counterexample?.render()}") { w.holds shouldBe true }
        }
        Report.line("[order/full-walks] ${valid.size} model-valid orders x ${walked.size} scenarios x ${Seeds.count()} seeds: $steps steps, all HOLD")
        val rnd = Random(7)
        val sample = List(40) { orders[rnd.nextInt(orders.size)] }.distinct()
        var refuted = 0
        for (order in sample) for (sc in walked) {
            val w = Explorer.walks(CellModel(sc, order, fullVariant), Seeds.all().take(maxOf(20, Seeds.count() / 4)), maxSteps = 80)
            if (!w.holds) {
                refuted++
                withClue("full order ${order.code()} refuted by a walk but its projections all hold:\n${w.counterexample!!.render()}") {
                    OrderValidity.modelValid(order, verdicts).first shouldBe false
                }
            }
        }
        Report.line("[order/spot-check] ${sample.size} sampled orders x ${walked.size} scenarios: $refuted refutations, each matched by a refuted projection")
        withClue("the spot check must refute something, or it checks nothing") { (refuted > 0) shouldBe true }
    }
}
