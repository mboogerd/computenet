package civictech.demo.tiering

import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Seeded incremental-vs-batch equivalence over the exact pipeline the app
 * wires ([TierPipeline.build]): after random valuation/preference churn —
 * including agents re-tiering (retract old + add new) and full retractions —
 * the fused tier map equals a batch recompute from the final input sets on
 * every seed.
 */
class TieringPipelineTest {

    interface FusedOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<String, Tiered>>>
    }

    @Test
    fun `incremental equals batch recompute on every seed`() {
        val agents = listOf("ada", "bo", "cy")
        val items = listOf("pizza", "sushi", "taco", "ramen")

        for (seed in 0L until 10L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val refs = TierPipeline.build(host)

            val fused = mutableMapOf<String, Tiered>()
            host.lookup<FusedOutletProxy>(refs.fused)!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Tiered>> {
                    override fun propagate(value: MapDelta<String, Tiered>) {
                        fused.putAll(value.puts)
                        value.removals.forEach { fused.remove(it) }
                    }
                }, PortRef.generate())
            )

            val valOps = host.lookup<ValuationInletProxy>(refs.vals)!!.inlet.call
            val prefOps = host.lookup<PrefInletProxy>(refs.prefs)!!.inlet.call

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
}
