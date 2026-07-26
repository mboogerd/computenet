package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.SaturationSignal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ProtocolRelayTest {
    private class Stage : Cell {
        override val ref = CellRef(UUID.randomUUID())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())
    }

    private fun prepare(stage: Stage, terminal: (Any) -> Boolean = { false }) {
        ProtocolSupport.bind(stage)
        PortRegistry.of(stage).names().forEach { name ->
            ProtocolSupport.of(PortRegistry.of(stage)[name]!!).relay(Protocols.Saturation, terminal)
        }
    }

    private fun link(from: Stage, to: Stage): Link =
        (from.outlet.linkTo(to.inlet as LinkFrom<Propagate<String>>) as LinkResult.Connected).link

    @Test
    fun `upstream descriptor relays saturation across stateless intermediaries`() {
        val source = Stage().also(::prepare)
        val middle = Stage().also(::prepare)
        val sink = Stage().also(::prepare)
        link(source, middle)
        val downstream = link(middle, sink)
        val received = mutableListOf<SaturationSignal>()
        ProtocolSupport.of(source.outlet).handle(Protocols.Saturation) { _, message ->
            received += message as SaturationSignal
        }

        val signal = SaturationSignal(sink.inlet.ref, true)
        Protocols.sendUpstream(downstream, Protocols.Saturation, signal)

        received shouldBe listOf(signal)
    }

    @Test
    fun `terminal predicate delivers locally and stops traversal`() {
        val source = Stage().also(::prepare)
        val middle = Stage().also { prepare(it) { message -> (message as SaturationSignal).asserted } }
        val sink = Stage().also(::prepare)
        link(source, middle)
        val downstream = link(middle, sink)
        var middleDeliveries = 0
        var sourceDeliveries = 0
        ProtocolSupport.of(middle.outlet).handle(Protocols.Saturation) { _, _ -> middleDeliveries++ }
        ProtocolSupport.of(source.outlet).handle(Protocols.Saturation) { _, _ -> sourceDeliveries++ }

        Protocols.sendUpstream(
            downstream, Protocols.Saturation, SaturationSignal(sink.inlet.ref, true)
        )

        middleDeliveries shouldBe 1
        sourceDeliveries shouldBe 0
    }
}
