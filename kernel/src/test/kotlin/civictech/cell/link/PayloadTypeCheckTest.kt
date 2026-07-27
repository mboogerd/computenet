package civictech.cell.link

import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * T08 finding 1: `Handshake.handshake` now compares the outlet's and inlet's
 * declared payload class ([FanOutlet.clazz]/[FanInlet.clazz]) before
 * `onLink`/`install` — closing the gap where `ManagedHost.connect`'s
 * `(outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)` cast away every
 * static payload guarantee. `SetCell.outlet` (`Propagate<SetDelta<E>>`) onto
 * `MapCell.inlet` (`MapOps<K, V>`) is a genuinely different top-level port
 * interface on each side — a wrong-shaped connect the class-level (erasure)
 * check can and does catch (see the KDoc on `checkPayload` for what it
 * cannot: same-wrapper generic-argument swaps like `SetDelta` vs `MapDelta`).
 */
class PayloadTypeCheckTest {

    @Test
    fun `connecting a Set outlet to a Map inlet is rejected with a payload-mismatch diagnostic`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val source = SetCell<String>()
        val target = MapCell<String, Int>()
        mgmt.spawn(source)
        mgmt.spawn(target)

        val result = mgmt.connect(source.ref, "outlet", target.ref, "inlet")

        val rejected = result.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.reason shouldContain "payload mismatch"
        rejected.reason shouldContain "Propagate"
        rejected.reason shouldContain "MapOps"
    }

    @Test
    fun `the reverse direction (Map outlet onto Set inlet) is rejected the same way`() {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val source = MapCell<String, Int>()
        val target = SetCell<String>()
        mgmt.spawn(source)
        mgmt.spawn(target)

        val result = mgmt.connect(source.ref, "outlet", target.ref, "inlet")

        result.shouldBeInstanceOf<LinkResult.Rejected>()
    }

    @Test
    fun `a well-typed connect still succeeds (additive, zero behavior change)`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        // SetCell.inlet is Use<SetOps<E>> — the *write* API, not a delta
        // consumer — so a delta outlet only ever connects into another
        // delta-consuming inlet, e.g. UnionSetCell's Serve<Propagate<SetDelta<E>>>.
        val a = SetCell<String>()
        val union = UnionSetCell<String>()
        mgmt.spawn(a)
        mgmt.spawn(union)

        mgmt.connect(a.ref, "outlet", union.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
    }

    // ---- the same laxness, previously unguarded, on GraphSpec replay ----------

    @Test
    fun `the same mismatch is caught on GraphSpec applyTo replay (previously unguarded)`() {
        val controller = SimulationController(seed = 4)
        val host = ManagedHost(scheduler = controller.scheduler())

        val spec = GraphSpec(
            listOf(
                SpawnStep("source", CellFactory { ref -> SetCell<String>(ref = ref) }),
                SpawnStep("target", CellFactory { ref -> MapCell<String, Int>(ref = ref) }),
                ConnectStep("source", "outlet", "target", "inlet"),
            ),
        )

        val error = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        error.message shouldContain "payload mismatch"
    }
}
