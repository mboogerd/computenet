package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.data.CounterDelta
import civictech.cell.data.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class SaturationTest {
    private class RelayStage(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())
        init { inlet.serve(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) = outlet.call.propagate(value)
        }) }
    }

    private class DeltaSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
        init { inlet.serve(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) { received += value.amount }
        }) }
    }

    private fun deltaInvocation(cell: CellRef, value: Long, context: MessageContext) =
        HostedPortInvocation(
            cell, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation("propagate", listOf("java.lang.Object"), listOf(CounterDelta(value)), context),
        )

    @Test
    fun `mergeable saturated traffic coalesces without silent loss under every seed`() {
        for (seed in 0L until 50L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val host = ManagedHost(
                scheduler = controller.scheduler(), registry = registry,
                intakeBound = IntakeBound(2, 0, SaturationPolicy.Coalesce),
            )
            val sink = DeltaSink()
            host.managementInlet.call.spawn(sink)
            val source = PortRef.generate()
            val context = MessageContext(Timestamp(source.id, 7), source)

            (1L..20L).forEach { registry.deliver(deltaInvocation(sink.ref, it, context)) }
            host.currentIntakeState shouldBe IntakeState.SATURATED
            controller.runToIdle()

            sink.received.sum() shouldBe (1L..20L).sum()
            sink.received.size shouldBe 2
            registry.parkedFor(sink.ref).shouldBeEmpty()
            host.currentIntakeState shouldBe IntakeState.OPEN
        }
    }

    interface OwnedSinkApi { fun accept(value: Owned<String>) }

    private class OwnedSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<OwnedSinkApi>())
        init { inlet.serve(object : OwnedSinkApi {
            override fun accept(value: Owned<String>) { received += value.take() }
        }) }
    }

    private fun ownedInvocation(cell: CellRef, value: Owned<String>) = HostedPortInvocation(
        cell, "inlet", HostedPortInvocation.Type.PORT_API,
        Invocation("accept", listOf(Owned::class.java.name), listOf(value)),
    )

    @Test
    fun `exclusive traffic parks at sender and replays exactly once`() {
        for (seed in 0L until 50L) {
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val host = ManagedHost(
                scheduler = controller.scheduler(), registry = registry,
                intakeBound = IntakeBound(1, 0, SaturationPolicy.Park),
            )
            val sink = OwnedSink()
            host.managementInlet.call.spawn(sink)
            val first = Owned("first")
            val second = Owned("second")

            registry.deliver(ownedInvocation(sink.ref, first))
            registry.deliver(ownedInvocation(sink.ref, second))
            registry.parkedFor(sink.ref).size shouldBe 1

            controller.runToIdle()

            sink.received shouldBe listOf("first", "second")
            registry.parkedFor(sink.ref).shouldBeEmpty()
            host.currentIntakeState shouldBe IntakeState.OPEN
        }
    }

    @Test
    fun `park policy parks mergeable traffic rather than coalescing it`() {
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(), registry = registry,
            intakeBound = IntakeBound(1, 0, SaturationPolicy.Park),
        )
        val sink = DeltaSink()
        host.managementInlet.call.spawn(sink)
        val source = PortRef.generate()
        val context = MessageContext(Timestamp(source.id, 1), source)

        registry.deliver(deltaInvocation(sink.ref, 1, context))
        registry.deliver(deltaInvocation(sink.ref, 2, context))

        registry.parkedFor(sink.ref).size shouldBe 1
        controller.runToIdle()
        sink.received shouldBe listOf(1, 2)
    }

    @Test
    fun `high and low water emit transitive saturation assertion and retraction`() {
        val controller = SimulationController(0)
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            intakeBound = IntakeBound(2, 0, SaturationPolicy.Coalesce),
        )
        val source = RelayStage()
        val middle = RelayStage()
        val sink = DeltaSink()
        host.managementInlet.call.spawn(source)
        host.managementInlet.call.spawn(middle)
        host.managementInlet.call.spawn(sink)
        source.outlet.linkTo(middle.inlet as LinkFrom<Propagate<CounterDelta>>)
        middle.outlet.linkTo(sink.inlet as LinkFrom<Propagate<CounterDelta>>)
        val notices = mutableListOf<SaturationSignal>()
        ProtocolSupport.of(source.outlet).handle(Protocols.Saturation) { _, message ->
            notices += message as SaturationSignal
        }
        val context = MessageContext(Timestamp(UUID.randomUUID(), 1), source.outlet.ref)

        host.enqueueHostedInvocation(deltaInvocation(sink.ref, 1, context))
        host.enqueueHostedInvocation(deltaInvocation(sink.ref, 2, context))
        notices.map { it.asserted } shouldBe listOf(true)

        controller.runToIdle()
        notices.map { it.asserted } shouldBe listOf(true, false)
    }
}
