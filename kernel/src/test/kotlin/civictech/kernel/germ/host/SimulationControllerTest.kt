package civictech.kernel.germ.host

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture

class SimulationControllerTest {

    @Test
    fun `equal-priority tasks run in submission order`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()
        val order = mutableListOf<Int>()

        repeat(100) { i -> scheduler.submit(10) { order += i } }
        controller.runToIdle()

        order shouldBe (0 until 100).toList()
    }

    @Test
    fun `lower priority preempts parked higher-priority tasks`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()
        val order = mutableListOf<String>()

        scheduler.submit(10) { order += "router" }
        scheduler.submit(20) { order += "data" }
        scheduler.submit(0) { order += "management" }
        controller.runToIdle()

        order shouldBe listOf("management", "router", "data")
    }

    @Test
    fun `same seed produces identical cross-host traces`() {
        fun trace(seed: Long): List<String> {
            val controller = SimulationController(seed)
            val trace = mutableListOf<String>()
            val schedulers = listOf("a", "b", "c").map { name ->
                name to controller.scheduler()
            }
            schedulers.forEach { (name, scheduler) ->
                repeat(20) { i -> scheduler.submit(10) { trace += "$name$i" } }
            }
            controller.runToIdle()
            return trace
        }

        trace(42) shouldBe trace(42)
        // different seeds should interleave differently (not guaranteed for every pair, but 42 vs 43 differ)
        trace(42) shouldNotBe trace(43)
    }

    @Test
    fun `per-host FIFO holds under every seed`() {
        for (seed in 0L until 50L) {
            val controller = SimulationController(seed)
            val received = mutableMapOf("a" to mutableListOf<Int>(), "b" to mutableListOf())
            val a = controller.scheduler()
            val b = controller.scheduler()
            repeat(50) { i ->
                a.submit(10) { received.getValue("a") += i }
                b.submit(10) { received.getValue("b") += i }
            }
            controller.runToIdle()
            received.getValue("a") shouldBe (0 until 50).toList()
            received.getValue("b") shouldBe (0 until 50).toList()
        }
    }

    @Test
    fun `await drives the simulation to completion`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()
        val future = CompletableFuture<String>()

        scheduler.submit(10) { future.complete("done") }
        scheduler.await(future) shouldBe "done"
    }

    @Test
    fun `await throws when simulation goes quiescent without completing`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()
        val future = CompletableFuture<String>()

        scheduler.submit(10) { /* does not complete the future */ }
        assertThrows<IllegalStateException> { scheduler.await(future) }
    }

    @Test
    fun `managed host runs end-to-end on a simulated scheduler`() {
        val controller = SimulationController()
        val host = civictech.kernel.germ.ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val consumer = civictech.kernel.germ.HostTest.CollectingConsumerCell()
        hostApi.spawn(consumer) // await drives the simulation; no sleep needed

        val provide = civictech.kernel.germ.Consumer::class.java.methods.find { it.name == "provide" }
        routerApi.route(
            consumer.ref, "inlet",
            civictech.kernel.germ.proxy.Invocation.of(provide, arrayOf("simulated"))
        )
        controller.runToIdle()

        consumer.received shouldBe listOf("simulated")
    }

    @Test
    fun `tasks submitted during execution are picked up`() {
        val controller = SimulationController()
        val scheduler = controller.scheduler()
        val order = mutableListOf<String>()

        scheduler.submit(10) {
            order += "outer"
            scheduler.submit(10) { order += "inner" }
        }
        controller.runToIdle()

        order shouldBe listOf("outer", "inner")
    }
}
