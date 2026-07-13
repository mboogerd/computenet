package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.LinkResult
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.port.use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W2.8 (G-55): the admission (structural, from construction) vs activation
 * (behavioral, handler-establishment) split. Admission is phase-independent
 * and binding from construction (10/15, 10/13); dispatch requires HOT. A link
 * admitted against a cold, not-yet-activated cell is live topology with a
 * parked tail — inbound invocations park in order in the Buffering primitive
 * and replay at activation, before any post-activation send lands.
 */
class AdmissionActivationTest {

    class EmitterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<String>>()
        fun emit(value: String) = outlet.use { provide(value) }
    }

    class ReceiverCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()
        val received = mutableListOf<String>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    @Test
    fun `cold-composed graph replays pre-activation sends in order at activation`() {
        val emitter = EmitterCell()
        val receiver = ReceiverCell()

        // Admission: both cells are cold (never spawned) — the handshake is
        // structural-only and phase-independent, so it succeeds regardless of
        // phase (a cold cell admits).
        val result = receiver.inlet.linkFrom(emitter.outlet)
        check(result is LinkResult.Connected) { "cold admission must succeed structurally: $result" }

        // Pre-activation traffic: the receiver's handler is not installed yet,
        // so these invocations MUST park in order rather than dispatching or
        // throwing (10/15 §Admission vs activation, parked-tail window).
        emitter.emit("first")
        emitter.emit("second")
        emitter.emit("third")
        receiver.received shouldBe emptyList()

        // Activation: spawning installs the handler and MUST replay the parked
        // tail, in order, before any post-activation send is observed.
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        host.managementInlet.call.spawn(receiver)
        controller.runToIdle()

        receiver.received shouldBe listOf("first", "second", "third")

        emitter.emit("fourth")
        receiver.received shouldBe listOf("first", "second", "third", "fourth")
    }
}
