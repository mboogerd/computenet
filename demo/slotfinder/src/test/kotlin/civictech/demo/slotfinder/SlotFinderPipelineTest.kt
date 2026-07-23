package civictech.demo.slotfinder

import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetDelta
import civictech.cell.data.SetView
import civictech.cell.graph.lookup
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Seeded incremental-vs-batch equivalence over the exact pipeline the app
 * wires ([SlotPipeline.build]): after random slot churn, the incremental
 * common/filtered/byDay views equal a batch recompute from the final input
 * sets on every seed.
 */
class SlotFinderPipelineTest {

    @Test
    fun `incremental equals batch recompute on every seed`() {
        for (seed in 0L until 10L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val refs = SlotPipeline.build(host)

            val common = SetView<Slot>()
            val filtered = SetView<Slot>()
            val byDay = mutableMapOf<String, Long>()
            host.lookup(refs.common)!!.outlet.subscribe(
                Use.fixed(Propagate<SetDelta<Slot>> { common.apply(it) }, PortRef.generate())
            )
            host.lookup(refs.filtered)!!.outlet.subscribe(
                Use.fixed(Propagate<SetDelta<Slot>> { filtered.apply(it) }, PortRef.generate())
            )
            host.lookup(refs.byDay)!!.outlet.subscribe(
                Use.fixed(Propagate<MapDelta<String, Long>> { delta ->
                    byDay.putAll(delta.puts)
                    delta.removals.forEach { byDay.remove(it) }
                }, PortRef.generate())
            )

            val writers = refs.participants.mapValues { (_, tref) ->
                host.lookup(tref)!!.inlet.call
            }

            val rnd = Random(seed)
            val held = PARTICIPANTS.associateWith { mutableSetOf<Slot>() }
            repeat(80) {
                val user = PARTICIPANTS[rnd.nextInt(PARTICIPANTS.size)]
                val slot = Slot(Slot.DAYS[rnd.nextInt(Slot.DAYS.size)], Slot.HOURS.random(kotlin.random.Random(rnd.nextLong())))
                val mine = held.getValue(user)
                if (slot in mine && rnd.nextInt(10) < 4) {
                    writers.getValue(user).remove(slot); mine -= slot
                } else {
                    writers.getValue(user).add(slot); mine += slot
                }
                if (rnd.nextInt(5) == 0) controller.runToIdle()
            }
            controller.runToIdle()

            // batch recompute over the final inputs
            val batchCommon = PARTICIPANTS.map { held.getValue(it) as Set<Slot> }.reduce { a, b -> a intersect b }
            val batchFiltered = batchCommon.filter { it.hour in Slot.BUSINESS_HOURS }.toSet()
            val batchByDay = batchFiltered.groupBy { it.day }.mapValues { it.value.size.toLong() }

            assertEquals(batchCommon, common.current(), "seed=$seed common diverged")
            assertEquals(batchFiltered, filtered.current(), "seed=$seed filtered diverged")
            assertEquals(batchByDay, byDay.toMap(), "seed=$seed byDay diverged")
        }
    }
}
