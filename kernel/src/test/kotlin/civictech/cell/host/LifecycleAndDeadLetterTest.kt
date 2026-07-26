package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class LifecycleAndDeadLetterTest {

    class ThrowingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = throw IllegalStateException("boom: $input")
            })
        }
    }

    class TrackingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        var activations = 0
        var deactivations = 0
        override fun onActivate(ctx: CellContext) {
            activations++
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations++
        }
    }

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    private val provide = Consumer::class.java.methods.find { it.name == "provide" }

    @Test
    fun `a throwing cell dead-letters and the host continues`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val thrower = ThrowingCell()
        val collector = civictech.cell.HostTest.CollectingConsumerCell()
        host.managementInlet.call.spawn(thrower)
        host.managementInlet.call.spawn(collector)

        host.routerInlet.call.route(thrower.ref, "inlet", Invocation.of(provide, arrayOf("x")))
        host.routerInlet.call.route(collector.ref, "inlet", Invocation.of(provide, arrayOf("still-alive")))
        controller.runToIdle()

        letters.size shouldBe 1
        letters[0].cause!!.message shouldContain "boom"
        collector.received shouldBe listOf("still-alive")
    }

    @Test
    fun `routing to an unknown port dead-letters instead of silence`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val cell = TrackingCell()
        host.managementInlet.call.spawn(cell)
        host.routerInlet.call.route(cell.ref, "nope", Invocation.of(provide, arrayOf("x")))
        controller.runToIdle()

        letters.size shouldBe 1
        letters[0].description shouldContain "nope"
    }

    @Test
    fun `despawn runs onDeactivate exactly once and later routes dead-letter`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val hostApi = host.managementInlet.call

        val cell = TrackingCell()
        hostApi.spawn(cell)
        cell.activations shouldBe 1

        hostApi.despawn(cell.ref)
        controller.runToIdle()
        cell.deactivations shouldBe 1

        // second despawn dead-letters (unknown ref), does not re-deactivate
        hostApi.despawn(cell.ref)
        // routing to the despawned cell dead-letters too
        host.routerInlet.call.route(cell.ref, "inlet", Invocation.of(provide, arrayOf("late")))
        controller.runToIdle()

        cell.deactivations shouldBe 1
        letters.size shouldBe 2
    }

    @Test
    fun `re-spawning a live ref is rejected`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val cell = TrackingCell()
        hostApi.spawn(cell)
        assertThrows<IllegalArgumentException> { hostApi.spawn(cell) }
        cell.activations shouldBe 1
    }
}
