package civictech.demo.tiering

import civictech.cell.Propagate
import civictech.cell.graph.lookup
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random
import civictech.cell.data.delta.MapDelta

/**
 * Seeded incremental-vs-batch equivalence over the exact pipeline the app
 * wires ([TierPipeline.build]): after random valuation/preference churn —
 * including agents re-tiering (retract old + add new) and full retractions —
 * the fused tier map equals a batch recompute from the final input sets on
 * every seed.
 */
class TieringPipelineTest {

    @Test
    fun `incremental equals batch recompute on every seed`() {
        val agents = listOf("ada", "bo", "cy")
        val items = listOf("pizza", "sushi", "taco", "ramen")

        for (seed in 0L until 10L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val refs = TierPipeline.build(host)

            val fused = mutableMapOf<String, Tiered>()
            host.lookup(refs.fused)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Tiered>> {
                    override fun propagate(value: MapDelta<String, Tiered>) {
                        fused.putAll(value.puts)
                        value.removals.forEach { fused.remove(it) }
                    }
                }, PortRef.generate())
            )

            val valOps = host.lookup(refs.vals)!!.inlet.call
            val prefOps = host.lookup(refs.prefs)!!.inlet.call

            val rnd = Random(seed)
            val heldVals = mutableMapOf<Pair<String, String>, Valuation>() // (agent,item) → current
            val heldPrefs = mutableSetOf<Pref>()
            repeat(100) {
                val agent = agents[rnd.nextInt(agents.size)]
                if (rnd.nextInt(2) == 0) { // valuation churn: set, re-set, or retract
                    val item = items[rnd.nextInt(items.size)]
                    val key = agent to item
                    val old = heldVals[key]
                    if (old != null && rnd.nextInt(10) < 3) {
                        valOps.remove(key); heldVals.remove(key)
                    } else {
                        // KeyedSetCell owns retract-old: a re-put retracts the
                        // previous valuation for this key by itself.
                        val v = Valuation(agent, item, rnd.nextInt(7).toLong())
                        valOps.put(key, v); heldVals[key] = v
                    }
                } else { // preference churn
                    val winner = items[rnd.nextInt(items.size)]
                    val loser = items[rnd.nextInt(items.size)]
                    if (winner != loser) {
                        val p = Pref(agent, winner, loser)
                        if (p in heldPrefs && rnd.nextInt(10) < 4) {
                            prefOps.remove(p); heldPrefs -= p
                        } else {
                            prefOps.add(p); heldPrefs += p
                        }
                    }
                }
                if (rnd.nextInt(5) == 0) controller.runToIdle()
            }
            controller.runToIdle()

            // batch recompute over the final inputs (same fusion math — the
            // test targets the propagation machinery, not parallel math)
            val batchTierAvg = heldVals.values.groupBy { it.item }
                .mapValues { (_, vs) -> vs.sumOf { it.score }.toDouble() / vs.size }
            val contribs = heldPrefs.flatMap {
                listOf(
                    Contribution(it.winner, it.agent, it.loser, +1),
                    Contribution(it.loser, it.agent, it.winner, -1),
                )
            }
            val batchPrefAvg = contribs.groupBy { it.item }
                .mapValues { (_, cs) -> cs.sumOf { it.sign }.toDouble() / cs.size }
            val batchFused = (batchTierAvg.keys + batchPrefAvg.keys).associateWith { item ->
                Tiering.fuse(batchTierAvg[item], batchPrefAvg[item])!!
            }

            assertEquals(batchFused, fused.toMap(), "seed=$seed fused tiers diverged")
        }
    }

    /**
     * The manual re-tier lane (feature computenet-j2x.5, task .1), proved
     * single-host — no JvmPeer, no socket. The chain under test is the one
     * decision j2x.5-D2 prescribes: `OrMapCell → UntagCell →
     * CombineLatestCell` override on top of the existing `fused` outlet, with
     * no demo-private merge anywhere in it.
     */
    @Test
    fun `a manual re-tier overrides the computed tier, and none restores it`() {
        val controller = SimulationController(7L)
        val host = ManagedHost(scheduler = controller.scheduler())
        val refs = TierPipeline.build(host)

        // both folds subscribed UP FRONT: a raw `subscribe` is not a host link,
        // so it gets no catch-up — a sink attached later would see only the
        // deltas that follow it.
        val board = mutableMapOf<String, Tiered>()
        val fused = mutableMapOf<String, Tiered>()
        fun fold(into: MutableMap<String, Tiered>): Use<Propagate<MapDelta<String, Tiered>>> = Use.fixed(
            object : Propagate<MapDelta<String, Tiered>> {
                override fun propagate(value: MapDelta<String, Tiered>) {
                    into.putAll(value.puts)
                    value.removals.forEach { into.remove(it) }
                }
            },
            PortRef.generate(),
        )
        host.lookup(refs.board)!!.outlet.subscribe(fold(board))
        host.lookup(refs.fused)!!.outlet.subscribe(fold(fused))

        val valOps = host.lookup(refs.vals)!!.inlet.call
        val manualOps = host.lookup(refs.manual)!!.inlet.call

        // ada values pizza at S: tierAvg 6 → 1.0 → tier S, tier-only signal.
        valOps.put("ada" to "pizza", Valuation("ada", "pizza", Tiering.SCORE_OF.getValue("S")))
        controller.runToIdle()
        assertEquals(Tiered(1.0, "S"), board["pizza"], "the computed tier should reach the board")

        // a manual pin wins over the computed tier
        manualOps.put("pizza", "D")
        controller.runToIdle()
        assertEquals(Tiering.manualTiered("D"), board["pizza"], "the manual pin should override the computed tier")
        assertEquals("D", board["pizza"]?.tier)

        // the manual map survives an unrelated valuation change: bo's F drops
        // the computed tier to (6+0)/2 = 3 → 0.5 → C, and the pin still wins.
        valOps.put("bo" to "pizza", Valuation("bo", "pizza", Tiering.SCORE_OF.getValue("F")))
        controller.runToIdle()
        assertEquals(Tiering.manualTiered("D"), board["pizza"], "an unrelated valuation must not clear the pin")

        // ... and the computed tier underneath really did move, so the
        // assertion above is about the override and not about a frozen board.
        assertEquals(Tiered(0.5, "C"), fused["pizza"], "the computed lane should have re-tiered underneath the pin")

        // `retier none` releases the pin and the computed tier comes back
        manualOps.remove("pizza")
        controller.runToIdle()
        assertEquals(Tiered(0.5, "C"), board["pizza"], "removing the pin should restore the computed tier")
    }
}
