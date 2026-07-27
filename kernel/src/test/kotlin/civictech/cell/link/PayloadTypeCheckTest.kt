package civictech.cell.link

import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.view.MapHubCell
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

    // ---- KNOWN GAP: the case T08's own Solution-A text claimed was covered ----

    /**
     * **This test asserts a defect, on purpose.** T08-A's ticket text said the
     * check "catches `SetDelta` vs `MapDelta`, not `SetDelta<A>` vs
     * `SetDelta<B>`". It does not catch the first case either: both ports
     * declare `Api` as `Propagate<D>`, and `Propagate<D>::class.java` is
     * `Propagate::class.java` for every `D`, so `checkPayload` compares two
     * identical classes and waves the link through. The delta then dies as a
     * `ClassCastException` on the dispatch thread at first delivery — exactly
     * the failure shape finding 1 set out to eliminate, for the commonest
     * shape of miswire there is (two delta ports of different payloads).
     *
     * Tracked in `doc/remediation/COVERAGE.md` under "same-wrapper payload
     * mismatch still unchecked". Closing it requires the port to carry a
     * declared payload class independent of `Api` erasure — see that row.
     *
     * **When this test starts failing, the gap is closed**: replace it with
     * the positive assertion (`Rejected`, message contains "payload
     * mismatch") and drop the COVERAGE row.
     */
    @Test
    fun `KNOWN GAP - a SetDelta outlet into a MapDelta inlet is NOT rejected (erasure)`() {
        val controller = SimulationController(seed = 5)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val source = SetCell<String>()                              // outlet: Propagate<SetDelta<String>>
        val sink = MapHubCell<String, Int>(onUpdate = {})           // inlet:  Propagate<MapDelta<String, Int>>
        mgmt.spawn(source)
        mgmt.spawn(sink)

        mgmt.connect(source.ref, "outlet", sink.ref, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
    }
}
