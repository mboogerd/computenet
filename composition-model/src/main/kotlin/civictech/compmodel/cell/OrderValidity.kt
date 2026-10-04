package civictech.compmodel.cell

import civictech.compmodel.check.Exploration
import civictech.compmodel.check.Explorer

/**
 * The order-validity experiment (brief: "enumerate layer orders ... compare the set of orders
 * that keep all invariants with the set the note's formation rules accept").
 *
 * **Reduction, and why it is sound for this model.** Exploring all 7! = 5040 orders of
 * `{D,O,P,F,A,X,S}` under one scenario that exercises every layer at once is out of reach
 * for exhaustive search. Instead each [Scenario] exercises a subset of layers and is
 * explored exhaustively for EVERY permutation of that subset. A layer a scenario does not
 * exercise is a pass-through with constant state in [CellModel] (no traffic of its kind,
 * no control signal for it), so inserting it anywhere in a stack changes no reachable
 * behaviour. Hence the verdict on a full 7-layer order is the conjunction of the verdicts
 * on its projections onto each scenario's subset. `OrderValidityTest` spot-checks this on
 * sampled full orders with a combined scenario under random walks.
 */
object OrderValidity {

    /** Scenarios of the experiment; each names the layer subset it exercises. */
    val scenarios: List<Scenario> = listOf(
        Scenario(
            "effect-core", setOf(Layer.DURABLE, Layer.SUSPENDABLE, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.EFFECT,
            perLane = 2, ownedFirst = true,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1),
        ),
        Scenario(
            "merge-core", setOf(Layer.DURABLE, Layer.SUSPENDABLE, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.SET,
            perLane = 2,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1),
        ),
        Scenario(
            "align", setOf(Layer.DURABLE, Layer.SUSPENDABLE, Layer.ALIGN, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.EFFECT,
            lanes = 2, perLane = 1, waves = true,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1),
        ),
        Scenario(
            "fence", setOf(Layer.DURABLE, Layer.FENCE, Layer.ALIGN, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.EFFECT,
            lanes = 2, perLane = 1, waves = true, staleLane = 1,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, designate = 1, checkpoint = 1),
        ),
        Scenario(
            "fence-dedup", setOf(Layer.DURABLE, Layer.FENCE, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.EFFECT,
            lanes = 2, perLane = 1, staleLane = 1,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, designate = 1, checkpoint = 1),
        ),
        Scenario(
            "outbox", setOf(Layer.DURABLE, Layer.OUTBOX, Layer.EFFECT_DEDUP, Layer.SUPERVISED), LeafKind.RELAY,
            perLane = 2,
            budget = Budget(crash = 1, reconnect = 1, restart = 1, suspend = 1, checkpoint = 1),
        ),
    )

    data class Verdict(val scenario: Scenario, val order: List<Layer>, val exploration: Exploration)

    /** Explore every permutation of every scenario's layer set. */
    fun runAll(): Map<String, Map<String, Verdict>> = scenarios.associate { sc ->
        val canonicalOrder = Layer.CANONICAL.filter { it in sc.layers }
        sc.name to permutations(canonicalOrder).associate { order ->
            order.code() to Verdict(sc, order, Explorer.bfs(CellModel(sc, order)))
        }
    }

    /** Model verdict on a full order: holds iff every scenario holds on the projection. */
    fun modelValid(full: List<Layer>, verdicts: Map<String, Map<String, Verdict>>): Pair<Boolean, List<Verdict>> {
        val failing = scenarios.mapNotNull { sc ->
            val proj = full.filter { it in sc.layers }.code()
            verdicts.getValue(sc.name).getValue(proj).takeIf { !it.exploration.holds }
        }
        return failing.isEmpty() to failing
    }
}
