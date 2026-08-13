package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-hdq: [ManagedHost.stagedWorkDepth] sees the one silent outcome on
 * the announcement path that [LocationRegistry.parkedFor] structurally cannot.
 *
 * The two depths answer different questions, and the announcement diagnostics
 * only ever printed the second one:
 *
 * - `parkedFor` counts invocations parked **before** a host accepted them — no
 *   published location yet, or intake closed.
 * - `stagedWorkDepth` counts invocations a host **did** accept, staged in the
 *   attention scheduler's per-cell queues, whose `dispatchOne` task has not run.
 *
 * So an invocation that made it past `enqueueHostedInvocation` and then never
 * ran is parked nowhere `parkedFor` can see. That is what the 2026-08-13
 * Linux/aarch64 announcement loss (recorded on computenet-dqy.40) would look
 * like to every pre-existing instrument: `parked for awaited ref: 0`, every drop
 * counter zero, stderr silent. Nothing here claims that event *was* an
 * undispatched delivery — the point is that until this accessor existed, nobody
 * could tell it apart from "nothing was ever sent".
 *
 * A stalled scheduler is a [SimulationController] that is simply not stepped:
 * staging happens synchronously on the caller's thread inside
 * `enqueueHostedInvocation`, while the `dispatchOne` task it submits waits in
 * the simulated queue. No new stalling mechanism is needed, and no wall-clock
 * wait is involved — the assertions are on state, not on timing.
 */
class StagedWorkDepthTest {

    private class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    private fun invocation(cell: CellRef, value: String) = HostedPortInvocation(
        cell, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("provide", listOf("java.lang.Object"), listOf(value)),
    )

    @Test
    fun `work staged against a stalled scheduler is counted here and invisible to parkedFor`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val sink = CollectingCell()
        host.managementInlet.call.spawn(sink)
        // the spawn's own management traffic must not be left standing, or the
        // depth below would be measuring the fixture instead of the deliveries
        controller.runToIdle()
        host.stagedWorkDepth().shouldBeEmpty()

        // stall: from here on the simulation is never stepped, so every
        // dispatchOne task submitted below stays queued and unrun.
        (1..3).forEach { registry.deliver(invocation(sink.ref, "m$it")) }

        // the contrast this test exists for — the same three invocations,
        // read by the two instruments:
        host.stagedWorkDepth() shouldContainExactly mapOf(sink.ref to 3)
        host.stagedWorkTotal() shouldBe 3
        registry.parkedFor(sink.ref).shouldBeEmpty()

        // and nothing ran: staged is not delivered
        sink.received.shouldBeEmpty()

        // no behaviour change — the staged work still dispatches, in order,
        // and the depth returns to zero once it has.
        controller.runToIdle()
        sink.received shouldBe listOf("m1", "m2", "m3")
        host.stagedWorkTotal() shouldBe 0
        host.stagedWorkDepth().shouldBeEmpty()
    }

    @Test
    fun `the two depths are complementary - an unpublished ref parks where staging shows nothing`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val unpublished = CellRef(UUID.randomUUID())

        registry.deliver(invocation(unpublished, "m1"))

        // the mirror image of the first test: this one never reached a host, so
        // the registry sees it and the host's staging does not. Neither depth
        // subsumes the other, which is why the announcement report needs both.
        registry.parkedFor(unpublished).size shouldBe 1
        host.stagedWorkDepth().shouldBeEmpty()
        host.stagedWorkTotal() shouldBe 0
        controller.runToIdle()
    }
}
