package civictech.cell.data.delta

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.replication.Replication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-1 (epic `computenet-9sm` §7; [KE3-08]/[KE3-09]; spec `doc/spec/40-distribution/42-replication.md`
 * §Delivered watermarks and causal stability, `[42-WM-01]`): the missing test
 * for [DeliveredFrontier] — a hole in a source's counters holds the delivered
 * prefix back at the last contiguous position, and the fold does not report
 * an advance past it, until the hole closes.
 *
 * Two halves, both named by BS-1's Design section:
 *
 * 1. Unit: [DeliveredFrontier.deliver] directly, with the feature's own
 *    worked example plus one independence case (a hole under one source must
 *    not hold back an unrelated source).
 * 2. Integrated: a real [SetCell] under [Replication], fed peer deltas
 *    through its `deltaInlet` exactly as [civictech.cell.replication.StabilityAdvanceTest]'s
 *    `Rig.inject` drives a companion — so the fold under test is
 *    `SetCell.applyRemote`'s real one, not a hand-rolled stand-in, and the
 *    companion [civictech.cell.data.WatermarkCell] row is read after each
 *    delivery to show it never reports the frontier at the hole's counter.
 *
 * Non-vacuousness (mutation-check.md route 2, test-only task — this task's
 * `metadata.files` claim is this file alone, and `DeliveredFrontier.kt`,
 * `SetCell.kt` and `Replication.kt` are outside it): each assertion below is
 * traced to the production conditional it pins.
 *
 * - `deliver(s, 3) == null` after `deliver(s, 1)` pins
 *   `if (thru == current) return null` in [DeliveredFrontier.deliver] — the
 *   holdback loop's `while (pending.remove(thru + 1)) thru++` cannot advance
 *   `thru` past `1` while `2` is missing, so `thru == current` and the method
 *   returns null instead of reporting `3`.
 * - `deliver(s, 2) == 3` pins the holdback loop itself: admitting `2` lets
 *   `pending.remove(2)` succeed, then `pending.remove(3)` succeed (the `3`
 *   parked by the earlier call), advancing `thru` from `1` to `3` in one
 *   step — the loop's raison d'etre, not a single increment.
 * - `deliver(s, 2)` again returning `null` pins
 *   `if (counter <= current) return null` — the "already covered" branch,
 *   distinct from the "hole above" branch the first assertion pins.
 * - the two-source independence assertion pins the per-source `HashMap`
 *   keying in both `prefix` and `holdback`: a hole recorded under `s` must
 *   leave `t`'s independent `current`/`pending` state untouched.
 * - the integrated half's "never observed at 2" assertion pins the same
 *   `thru == current` branch as above, but reached through
 *   `SetCell.applyRemote`'s fold and `notifyDelivered(advanced)` call
 *   (SetCell.kt) rather than by calling `deliver` directly — showing the
 *   production wiring, not just the class in isolation, withholds the
 *   companion row at the hole.
 * - the integrated half's "reads 3 once the hole closes" assertion pins that
 *   same `notifyDelivered` call firing with a non-null `advanced` map once
 *   `applyRemote`'s fold closes the hole, which is what lets
 *   `Replication.trackDeliveries`'s `cell.onDeliver { source, thru ->
 *   companion.advance(source, thru) }` (Replication.kt) raise the companion
 *   row at all.
 *
 * No sibling mutation evidence over these exact branches exists to cite; the
 * reviewer runs the real mutation (mutation-check.md, "When the task is
 * TEST-ONLY").
 */
class DeliveredFrontierTest {

    // ---- 1. Unit: DeliveredFrontier.deliver directly ----

    @Test
    fun `a hole holds the delivered prefix back, and closing it advances past the hole in one step`() {
        val frontier = DeliveredFrontier()
        val s = UUID.randomUUID()

        frontier.deliver(s, 1) shouldBe 1L
        frontier.deliver(s, 3) shouldBe null // hole at 2: thru stays 1, no advance to report
        frontier.deliver(s, 2) shouldBe 3L // closes the hole: 2 then the parked 3 admit in one fold
        frontier.deliver(s, 2) shouldBe null // already covered
        frontier.deliver(s, 4) shouldBe 4L
    }

    @Test
    fun `a hole under one source does not hold back an unrelated source`() {
        val frontier = DeliveredFrontier()
        val s = UUID.randomUUID()
        val t = UUID.randomUUID()

        frontier.deliver(s, 1) shouldBe 1L
        frontier.deliver(s, 3) shouldBe null // s has a hole at 2

        // t is a wholly independent source: its own counters advance normally,
        // unaffected by s's holdback state.
        frontier.deliver(t, 1) shouldBe 1L
        frontier.deliver(t, 2) shouldBe 2L
    }

    // ---- 2. Integrated: a real SetCell under Replication ----

    /**
     * One peer, one replicated [SetCell]<String>, fed peer deltas straight
     * into `deltaInlet` — the shape of `StabilityAdvanceTest.Rig`, minus the
     * phantom watermark slots BS-1 does not need.
     */
    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId: UUID = UUID.randomUUID()
        val cell = SetCell<String>(CellRef(logicalId, 0))
        val ownSlot: UUID

        init {
            replication.replicate(cell, host)
            controller.runToIdle()
            ownSlot = civictech.cell.data.WatermarkCell.slotId(replication.watermarkRef(cell.ref))
        }

        /** Inject a peer delta tagged `(source, counter)` and drain the scheduler. */
        fun deliver(source: UUID, counter: Long) {
            cell.deltaInlet.call.propagate(
                SetDelta(adds = mapOf("e$source-$counter" to setOf(Timestamp(source, counter))))
            )
            controller.runToIdle()
        }

        /** This replica's own companion row for [source], or null if absent. */
        fun row(source: UUID): Long? =
            replication.watermarkOf(logicalId)!!.rows()[ownSlot]?.get(source)
    }

    @Test
    fun `the companion row rises only at contiguous prefixes, never at the hole`() {
        val rig = Rig()
        val src = UUID.randomUUID()

        rig.deliver(src, 1)
        rig.row(src) shouldBe 1L

        rig.deliver(src, 3)
        // The hole at 2 means the fold never reports an advance for this
        // delivery: the companion row must stay at 1, never jump to 3 early.
        rig.row(src) shouldBe 1L

        rig.deliver(src, 2)
        // Closing the hole admits 2 then the parked 3 in the same fold, so the
        // row rises directly to 3 — it must never have read 2 on the way.
        rig.row(src) shouldBe 3L

        // Cross-check against the internal control seam: the same value, read
        // through Replication.localDeliveredFrontier rather than the
        // companion's own rows.
        rig.replication.localDeliveredFrontier(rig.logicalId).perSource[src] shouldBe 3L
    }
}
