package civictech.oracle.run

import civictech.cell.data.SetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.PresenceCountCell
import civictech.oracle.model.ModelState
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
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
}
