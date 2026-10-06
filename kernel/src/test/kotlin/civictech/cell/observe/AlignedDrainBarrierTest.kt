package civictech.cell.observe

import civictech.cell.CellContext
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlignedDrainBarrierTest {

    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private fun emptySink() = AlignedCompositeCell(mapOf("items" to View.set<Int>()))

    private fun populatedSink(): AlignedCompositeCell {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        host.managementInlet.call.spawn(source)
        val sink = host.observeAligned { set("items", source.ref) }
        host.lookup<IntSetInlet>(source.ref)!!.inlet.call.add(1)
        controller.runToIdle()
        sink.composite().frontier.isEmpty() shouldBe false
        return sink
    }

    @Test
    fun `direct close reports closed promptly for empty and populated frontiers`() {
        listOf(emptySink(), populatedSink()).forEach { sink ->
            sink.close()

            sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
        }
    }

    @Test
    fun `repeated deactivation is stable and activation reopens paused sink`() {
        val ctx = object : CellContext {}
        val sink = emptySink()

        sink.onDeactivate(ctx)
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Deactivated

        sink.onDeactivate(ctx)
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Deactivated

        sink.onActivate(ctx)
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Drained
        sink.close()
    }

    @Test
    fun `direct close after deactivation is terminal and activation does not reopen`() {
        val ctx = object : CellContext {}
        val sink = emptySink()

        sink.onDeactivate(ctx)
        sink.close()
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
        sink.awaitTermination(1_000) shouldBe true

        sink.onActivate(ctx)
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
    }

    @Test
    fun `deactivation and activation after direct close preserve terminal lifecycle`() {
        val sink = emptySink()
        val ctx = object : CellContext {}

        sink.close()
        sink.onDeactivate(ctx)

        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
        sink.onActivate(ctx)
        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
    }

    @Test
    fun `accepted barrier drains every earlier callback even when close races after registration`() {
        val sink = emptySink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        sink.onChange {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            returned.countDown()
        }
        check(entered.await(30, TimeUnit.SECONDS)) { "listener did not enter" }

        // Registration is synchronous: the marker is accepted under the same
        // monitor close needs, and therefore precedes this close deterministically.
        val barrier = sink.drainBarrier()
        sink.close()
        barrier.await(1) shouldBe AlignedDrainResult.TimedOut

        release.countDown()
        barrier.await(30_000) shouldBe AlignedDrainResult.Drained
        returned.count shouldBe 0L
    }

    @Test
    fun `close winning before registration returns closed instead of false drain`() {
        val sink = emptySink()

        sink.close()

        sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Closed
    }

    @Test
    fun `wedged open listener times out distinctly and the same barrier can later drain`() {
        val sink = emptySink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sink.onChange {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        check(entered.await(30, TimeUnit.SECONDS)) { "listener did not enter" }

        val barrier = sink.drainBarrier()
        barrier.await(1) shouldBe AlignedDrainResult.TimedOut

        release.countDown()
        barrier.await(30_000) shouldBe AlignedDrainResult.Drained
        sink.close()
    }

    @Test
    fun `barrier after reactivation waits for callbacks accepted by the predecessor dispatcher`() {
        val ctx = object : CellContext {}
        val sink = emptySink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sink.onChange {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        check(entered.await(30, TimeUnit.SECONDS)) { "listener did not enter" }

        sink.onDeactivate(ctx)
        sink.onActivate(ctx)
        val barrier = sink.drainBarrier()
        barrier.await(1) shouldBe AlignedDrainResult.TimedOut

        release.countDown()
        barrier.await(30_000) shouldBe AlignedDrainResult.Drained
        sink.close()
    }

    @Test
    fun `barriers do not change observation state retain handles listeners or an idle dispatcher`() {
        val dispatchersBefore = threadsNamed("aligned-observe-")
        val sink = populatedSink()
        val beforeComposite = sink.composite()
        val beforeCurrent = sink.current()
        val beforeHandles = sink.outstandingHandles
        val beforeBuffered = sink.bufferedWaves
        val beforeViolations = sink.violations
        val beforeUnmatched = sink.unmatchedDeltas

        repeat(3) {
            sink.drainBarrier().await(1_000) shouldBe AlignedDrainResult.Drained
        }

        sink.composite() shouldBe beforeComposite
        sink.current() shouldBe beforeCurrent
        sink.outstandingHandles shouldBe beforeHandles
        sink.bufferedWaves shouldBe beforeBuffered
        sink.violations shouldBe beforeViolations
        sink.unmatchedDeltas shouldBe beforeUnmatched
        (threadsNamed("aligned-observe-") - dispatchersBefore).shouldBeEmpty()
        sink.close()
    }

    private fun threadsNamed(prefix: String): Set<Long> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith(prefix) }
        .mapTo(mutableSetOf()) { it.threadId() }
}
