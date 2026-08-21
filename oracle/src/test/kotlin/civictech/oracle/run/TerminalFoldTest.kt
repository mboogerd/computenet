package civictech.oracle.run

import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.port.Use
import civictech.oracle.model.ModelState
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Width pinning at the comparison boundary (`[ORA1-DIFF-01]`'s fold slice): a scalar fold
 * over `CounterDelta` (`CountCell`) sums to a `Long`, and a map fold over `PresenceCountCell`
 * keeps `Int` values — the mismatch that "looks semantic" the task's own KDoc warns about.
 * Both are driven hosted, with [SimWorld], so the fold is exercised against the real kernel
 * delta stream rather than a hand-built one.
 */
class TerminalFoldTest {

    @Test
    fun `hosted SetCell into CountCell folds to a Long-valued ScalarState`() {
        val world = SimWorld(seed = 1L)
        val source = SetCell<String>()
        val counted = CountCell<String>()
        val fold = ScalarTerminalFold()

        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(counted)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(source.ref, "outlet", counted.ref, "inlet")
        world.host.managementInlet.call.connect(counted.ref, "outlet", fold.ref, "inlet")

        source.inlet.call.add("a")
        source.inlet.call.add("b")
        world.runToIdle()

        fold.current() shouldBe ModelState.ScalarState(2L)
    }

    @Test
    fun `ScalarState(2L) is not equal to ScalarState(2) - width is part of the value`() {
        // Documents the negative deliberately: a Long-2 count and an Int-2 count are
        // different ModelState values under structural equality, even though they print the
        // same. A fold that returned Int here would mismatch on every case for a reason that
        // looks semantic (the task's own KDoc, and [ORA1-DIFF-01]'s fold slice).
        (ModelState.ScalarState(2L) == ModelState.ScalarState(2)) shouldBe false
    }

    @Test
    fun `hosted PresenceCountCell folds to an Int-valued MapState`() {
        val world = SimWorld(seed = 2L)
        val source = SetCell<String>()
        val presence = PresenceCountCell<String>()
        val fold = MapTerminalFold<String, Int>()

        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(presence)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(source.ref, "outlet", presence.ref, "inlet")
        world.host.managementInlet.call.connect(presence.ref, "outlet", fold.ref, "inlet")

        source.inlet.call.add("x")
        world.runToIdle()

        fold.current() shouldBe ModelState.MapState(mapOf("x" to 1))
    }

    // =====================================================================
    // ORA2's replicable families ([ORA2-CONV-04], [ORA2-MODEL-10])
    // =====================================================================

    /** The inlet a hosted `OrMapCell` exposes. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    @Test
    fun `a hosted OrMapCell's outlet stream folds to the dot-order value, not the arrival-order one`() {
        val world = SimWorld(seed = 3L)
        val source = OrMapCell<String, String>()
        val fold = TaggedMapTerminalFold<String, String>()

        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(source.ref, "outlet", fold.ref, "inlet")

        val ops = (
            HostedCellProxy.create(source.ref, world.registry, OrMapInletProxy::class.java)
                as OrMapInletProxy
            ).inlet.call
        ops.put("k", "first")
        ops.put("k", "second")
        ops.put("j", "only")
        world.runToIdle()

        // One writer, so the two puts at `k` are causally ordered and the second tombstones the
        // first — the fold reads the surviving dot, and the key never shows two live values.
        fold.current() shouldBe ModelState.MapState(mapOf("k" to "second", "j" to "only"))
        // and the reading is a pure function of the merged delta, which is what lets a convergence
        // check take the SAME reading over a ReplicaConvergence fold
        TaggedMapTerminalFold.stateOf(fold.merged()) shouldBe fold.current()
    }

    @Test
    fun `the tagged fold absorbs a duplicated delivery - merge is idempotent, unlike the scalar fold's sum`() {
        // `[24-TMAP-01]`: re-delivering a delta a fold already holds changes nothing. This is the
        // property that makes a gossip mesh foldable at all, and the reason a tagged stream must
        // NOT go through ScalarTerminalFold's addition or MapTerminalFold's arrival order.
        val world = SimWorld(seed = 4L)
        val source = OrMapCell<String, String>()
        val fold = TaggedMapTerminalFold<String, String>()
        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(source.ref, "outlet", fold.ref, "inlet")
        (
            HostedCellProxy.create(source.ref, world.registry, OrMapInletProxy::class.java)
                as OrMapInletProxy
            ).inlet.call.put("k", "v")
        world.runToIdle()

        val once = fold.current()
        fold.inlet.call.propagate(source.state())
        fold.inlet.call.propagate(source.state())
        fold.current() shouldBe once
    }

    @Test
    fun `a hosted PnCounterCell's outlet stream folds to a Long-valued ScalarState by pointwise max`() {
        val world = SimWorld(seed = 5L)
        val source = PnCounterCell()
        val fold = PnCounterTerminalFold()

        world.host.managementInlet.call.spawn(source)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(source.ref, "outlet", fold.ref, "inlet")

        source.inlet.call.increment(5L)
        source.inlet.call.decrement(2L)
        world.runToIdle()

        fold.current() shouldBe ModelState.ScalarState(3L)
        // idempotent under a repeat, where ScalarTerminalFold's addition would double-count —
        // the whole reason PnCounterCell replicates and CounterCell does not
        val carried = fold.merged()
        fold.inlet.call.propagate(carried)
        fold.inlet.call.propagate(carried)
        fold.current() shouldBe ModelState.ScalarState(3L)
        fold.current() shouldNotBe ModelState.ScalarState(3)
    }
}
