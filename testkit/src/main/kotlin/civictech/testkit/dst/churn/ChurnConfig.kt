package civictech.testkit.dst.churn

import civictech.testkit.dst.DepartureMode

/**
 * The generator's configuration surface ([CHA3-05]).
 *
 * Every knob here is **load-bearing in the generated plan** — there is no field the generator
 * reads and does nothing with. That is worth stating because a config surface is the easiest
 * place in a generative harness to ship a lie: a knob that is accepted, stored, printed in a
 * report and never consulted looks exactly like one that works, and no round-trip test can
 * tell the difference. `ChurnPlanTest` pins each of them against a plan.
 *
 * A `ChurnConfig` is a **value**, and half of [CHA3-01] rests on that: the plan is a pure
 * function of `(seed, config)`, so two configs that compare equal must generate the same plan.
 * The generator therefore never iterates [departureWeights] in map order — it walks
 * `DepartureMode.entries` and looks each mode up — because two equal maps can iterate
 * differently and would otherwise produce two different plans from one config.
 *
 * @property peerCount how many peers the mesh has. A *range*: the count itself is drawn from
 *   the seed, so a sweep over seeds varies mesh size without varying the config.
 * @property eventCount how many **membership** events (join / rejoin / depart) the plan
 *   carries. Interest reassignments are derived companions of those, not counted here — see
 *   [ChurnGenerator].
 * @property departureWeights the relative weight of each [DepartureMode] when a departure is
 *   drawn. A mode weighted 0 never appears; the weights need not sum to anything in
 *   particular, but at least one must be positive or no departure could be generated.
 * @property writeConcurrency the fraction of the op script issued *concurrently* with the
 *   write before it — that is, placed on the same controller step by a different peer. `0.0`
 *   serialises every write; `1.0` piles the whole script onto one step.
 * @property partitionOverlap the probability that a membership event is scheduled *inside* a
 *   still-open [DepartureMode.PARTITION_SUSPEND] window rather than after it closes. This is
 *   the knob that decides whether churn is observed one-at-a-time or while the mesh is already
 *   split — the case a reconvergence property actually cares about.
 * @property opScriptLength how many writes the workload script carries. The script is
 *   [ChurnPlan.writeSchedule]; the graph builder drives it from a step hook (`doc/dst-rig.md`
 *   §1 seam 4), because `DstRun.execute()` owns the drive loop.
 * @property stepBudget the activation horizon. Every generated step index is `< stepBudget`,
 *   so a plan cannot schedule an event the run's own budget could never reach ([CHA3-02]).
 *   Not one of [CHA3-05]'s six knobs — it is the clock the other six are expressed against,
 *   and there is nowhere else for it to live now that wall-clock time is excluded.
 * @property suspendWindow how many steps a [DepartureMode.PARTITION_SUSPEND] departure holds
 *   its window open for. Serves [partitionOverlap]: without a window length there is no
 *   "inside the window" for the overlap probability to name.
 */
data class ChurnConfig(
    val peerCount: IntRange = 3..5,
    val eventCount: Int = 8,
    val departureWeights: Map<DepartureMode, Int> = DEFAULT_DEPARTURE_WEIGHTS,
    val writeConcurrency: Double = 0.5,
    val partitionOverlap: Double = 0.25,
    val opScriptLength: Int = 16,
    val stepBudget: Int = 200,
    val suspendWindow: Int = 12,
) {
    init {
        require(peerCount.first >= 1) { "a mesh needs at least one peer, got peerCount=$peerCount" }
        require(peerCount.last >= peerCount.first) { "peerCount must be a non-empty range, got $peerCount" }
        require(eventCount >= 0) { "eventCount is a count of membership events, got $eventCount" }
        require(departureWeights.values.all { it >= 0 }) {
            "departure weights are non-negative, got $departureWeights"
        }
        require(departureWeights.values.sum() > 0) {
            "at least one DepartureMode must carry a positive weight, or no departure can be drawn; got " +
                "$departureWeights"
        }
        require(writeConcurrency in 0.0..1.0) { "writeConcurrency is a fraction, got $writeConcurrency" }
        require(partitionOverlap in 0.0..1.0) { "partitionOverlap is a probability, got $partitionOverlap" }
        require(opScriptLength >= 0) { "opScriptLength is a count of writes, got $opScriptLength" }
        require(stepBudget >= 2) { "stepBudget is the activation horizon and must leave room for step 1, got $stepBudget" }
        require(suspendWindow >= 1) { "suspendWindow is a step count, got $suspendWindow" }
    }

    /** The weight of [mode], defaulting to 0 for a mode the config does not mention. */
    fun weightOf(mode: DepartureMode): Int = departureWeights[mode] ?: 0

    companion object {

        /**
         * Equal weight on all four modes.
         *
         * Deliberately flat rather than "realistic": the four are four different experiments
         * (see [DepartureMode]), and a default that down-weighted the awkward ones would make
         * the *default* sweep quietly avoid the cases the harness exists to find.
         */
        val DEFAULT_DEPARTURE_WEIGHTS: Map<DepartureMode, Int> =
            DepartureMode.entries.associateWith { 1 }
    }
}
