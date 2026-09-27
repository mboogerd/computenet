package civictech.demo.slotfinder

import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.graph.lookup
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.view.SetView

/**
 * Seeded incremental-vs-batch equivalence over the exact pipeline the app
 * wires ([SlotPipeline.build]): after random slot churn, the incremental
 * common/filtered views equal a batch recompute from the final input sets on
 * every seed, and `byDay` equals it over `[24-WL-10]`'s domain (KE4.6) — plus
 * two deterministic cases for eviction (`[24-WL-06]`) and the late drop
 * (`[24-WL-07]`).
 */
class SlotFinderPipelineTest {

    /** One built pipeline with every observed outlet folded by a direct subscriber. */
    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val refs = SlotPipeline.build(host)

        val common = SetView<Slot>()
        val filtered = SetView<Slot>()
        val late = SetView<Slot>()
        val byDay = mutableMapOf<String, Long>()

        /** Every `byDay` key that ever left as a `MapDelta` removal, in order. */
        val byDayRemovals = mutableListOf<String>()

        val writers: Map<String, SetOps<Slot>>

        init {
            host.lookup(refs.common)!!.outlet.subscribe(
                Use.fixed(Propagate<SetDelta<Slot>> { common.apply(it) }, PortRef.generate())
            )
            host.lookup(refs.filtered)!!.outlet.subscribe(
                Use.fixed(Propagate<SetDelta<Slot>> { filtered.apply(it) }, PortRef.generate())
            )
            host.lookup(refs.byDay)!!.outlet.subscribe(
                Use.fixed(Propagate<MapDelta<String, Long>> { delta ->
                    byDay.putAll(delta.puts)
                    delta.removals.forEach { byDay.remove(it); byDayRemovals += it }
                }, PortRef.generate())
            )
            // `late` is not on GroupByApi: subscribe on the concrete cell's class-level port.
            refs.byDayCell.late.subscribe(
                Use.fixed(Propagate<SetDelta<Slot>> { late.apply(it) }, PortRef.generate())
            )
            writers = refs.participants.mapValues { (_, tref) -> host.lookup(tref)!!.inlet.call }
        }

        /** All three participants add [slot] in [PARTICIPANTS] order, one at a time: carol completes the quorum. */
        fun allAdd(slot: Slot) {
            PARTICIPANTS.forEach { p ->
                writers.getValue(p).add(slot)
                controller.runToIdle()
            }
        }

        fun waterlineFloor(): Long? = refs.waterlineCell.floor()
    }

    @Test
    fun `incremental equals batch recompute on every seed`() {
        var evictedSomewhere = false
        var droppedSomewhere = false
        for (seed in 0L until 10L) {
            val rig = Rig(seed)
            val rnd = Random(seed)
            val held = PARTICIPANTS.associateWith { mutableSetOf<Slot>() }
            repeat(80) {
                val user = PARTICIPANTS[rnd.nextInt(PARTICIPANTS.size)]
                val slot = Slot(Slot.DAYS[rnd.nextInt(Slot.DAYS.size)], Slot.HOURS.random(kotlin.random.Random(rnd.nextLong())))
                val mine = held.getValue(user)
                if (slot in mine && rnd.nextInt(10) < 4) {
                    rig.writers.getValue(user).remove(slot); mine -= slot
                } else {
                    rig.writers.getValue(user).add(slot); mine += slot
                }
                if (rnd.nextInt(5) == 0) rig.controller.runToIdle()
            }
            rig.controller.runToIdle()

            // batch recompute over the final inputs
            val batchCommon = PARTICIPANTS.map { held.getValue(it) as Set<Slot> }.reduce { a, b -> a intersect b }
            val batchFiltered = batchCommon.filter { it.hour in Slot.BUSINESS_HOURS }.toSet()

            assertEquals(batchCommon, rig.common.current(), "seed=$seed common diverged")
            assertEquals(batchFiltered, rig.filtered.current(), "seed=$seed filtered diverged")

            // [24-WL-10]: byDay is compared over the lateness-restricted domain. The batch
            // side counts the late-filtered input (every [24-WL-07]-dropped slot removed),
            // and both sides keep only the days whose window end is strictly above the
            // final floor read from the WaterlineCell.
            val finalFloor = rig.waterlineFloor()
            val lateSlots = rig.late.current()
            fun live(day: String) = finalFloor == null || DayEnd(day) > finalFloor
            val batchByDay = (batchFiltered - lateSlots).groupBy { it.day }
                .mapValues { it.value.size.toLong() }
                .filterKeys(::live)
            assertEquals(batchByDay, rig.byDay.filterKeys(::live), "seed=$seed byDay diverged over [24-WL-10]'s domain")
            // ... and nothing at or below the floor survives (no exclusives here, so nothing is refused)
            assertTrue(rig.byDay.keys.all(::live), "seed=$seed byDay holds a passed day: ${rig.byDay} floor=$finalFloor")
            assertTrue(
                lateSlots.all { finalFloor != null && SlotTime(it) < finalFloor },
                "seed=$seed a late slot is not below the final floor: $lateSlots floor=$finalFloor",
            )
            // byDay's own floor has caught up with the waterline's at idle
            assertEquals(finalFloor, rig.refs.byDayCell.floor(), "seed=$seed byDay's floor lags the waterline at idle")
            // every distinct late slot was counted at least once (a slot may be dropped more than once)
            assertTrue(
                rig.refs.byDayCell.droppedBelowFloor >= lateSlots.size,
                "seed=$seed droppedBelowFloor=${rig.refs.byDayCell.droppedBelowFloor} < late=${lateSlots.size}",
            )
            if (finalFloor != null && Slot.DAYS.any { DayEnd(it) <= finalFloor }) evictedSomewhere = true
            if (lateSlots.isNotEmpty()) droppedSomewhere = true
        }
        // Non-vacuity of the restriction: at least one seed passed a day's window end.
        assertTrue(evictedSomewhere, "no seed of 0..9 advanced the floor past a day — the [24-WL-10] restriction is vacuous")
        // ... and at least one seed dropped a slot, so "batch minus late" is exercised too.
        assertTrue(droppedSomewhere, "no seed of 0..9 dropped a late slot — the late-filtered batch input is vacuous")
    }

    @Test
    fun `the floor passing a day evicts its count as a MapDelta removal and does not re-create it`() {
        val rig = Rig(seed = 1L)
        assertNull(rig.waterlineFloor(), "no source has contributed yet: the floor is the identity")

        rig.allAdd(Slot("Mon", 10))
        // [24-WL-02] one contributing source: floor = max − lateness = 10 − 24
        assertEquals(-14L, rig.waterlineFloor())
        assertEquals(-14L, rig.refs.byDayCell.floor())
        assertEquals(mapOf("Mon" to 1L), rig.byDay.toMap())

        rig.allAdd(Slot("Wed", 10))
        // Wed-10 = 2*24 + 10 = 58; floor = 58 − 24 = 34 >= DayEnd(Mon) = 24 → Mon passed
        assertEquals(34L, rig.waterlineFloor())
        assertEquals(34L, rig.refs.byDayCell.floor())
        assertEquals(mapOf("Wed" to 1L), rig.byDay.toMap(), "[24-WL-06] Mon's window is evicted")
        assertEquals(listOf("Mon"), rig.byDayRemovals, "[24-OP-GROUPBY-02] Mon left as a MapDelta removal")
        // eviction is not a drop: nothing on `late`, nothing counted
        assertEquals(emptySet<Slot>(), rig.late.current())
        assertEquals(0L, rig.refs.byDayCell.droppedBelowFloor)
        // the upstream views are untouched by lateness
        assertEquals(setOf(Slot("Mon", 10), Slot("Wed", 10)), rig.filtered.current())
    }

    @Test
    fun `a common slot arriving after its day closed is dropped onto late and counted, not folded`() {
        val rig = Rig(seed = 2L)
        rig.allAdd(Slot("Mon", 10))
        assertEquals(-14L, rig.waterlineFloor())
        rig.allAdd(Slot("Wed", 10))
        assertEquals(34L, rig.waterlineFloor())
        assertEquals(mapOf("Wed" to 1L), rig.byDay.toMap())

        rig.allAdd(Slot("Mon", 11))
        // Mon-11 = 11 < 34: the floor does not move (a lower max never lowers it) ...
        assertEquals(34L, rig.waterlineFloor())
        assertEquals(34L, rig.refs.byDayCell.floor())
        // ... the slot is common and business-hours upstream ...
        assertTrue(Slot("Mon", 11) in rig.filtered.current())
        // ... but byDay is unchanged and Mon is not re-created ([24-WL-07], B1)
        assertEquals(mapOf("Wed" to 1L), rig.byDay.toMap())
        assertEquals(listOf("Mon"), rig.byDayRemovals, "no further removal, and no Mon put in between")
        assertEquals(setOf(Slot("Mon", 11)), rig.late.current(), "[KE4-39] the drop is visible on `late`")
        assertEquals(1L, rig.refs.byDayCell.droppedBelowFloor)
    }
}
