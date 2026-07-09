package civictech.cell
import civictech.cell.host.ManagedHost

import civictech.cell.port.input
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FifoOrderTest {

    class CollectingCell(count: Int, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val latch = CountDownLatch(count)
        val received = Collections.synchronizedList(mutableListOf<Int>())

        @Suppress("unused")
        val inlet by input<Consumer<Int>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                    latch.countDown()
                }
            })
        }
    }

    @Test
    fun `equal-priority invocations preserve submission order`() {
        val count = 1000
        val host = ManagedHost()
        val hostApi = host.managementInlet.call
        val routerApi = host.routerInlet.call

        val collector = CollectingCell(count)
        hostApi.spawn(collector)

        val provide = Consumer::class.java.methods.find { it.name == "provide" }
        repeat(count) { i ->
            routerApi.route(collector.ref, "inlet", civictech.cell.proxy.Invocation.of(provide, arrayOf(i)))
        }

        collector.latch.await(10, TimeUnit.SECONDS) shouldBe true
        collector.received.toList() shouldBe (0 until count).toList()
    }
}
