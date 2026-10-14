package civictech.bench.macro

import civictech.cell.host.CoroutineScheduler
import civictech.cell.host.HostColor
import civictech.cell.host.HostScheduler
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldBeSameInstanceAs
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SchedulerFailureCaptureTest {

    @Test
    fun recordsAThrowUnderVirtualThreadSchedulerAndTheLoopSurvives() {
        val scheduler = InstrumentedScheduler(VirtualThreadScheduler("bench-capture-virtual"))
        val capturedErr = ByteArrayOutputStream()
        val originalErr = System.err
        try {
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
            scheduler.submit(0) { throw IllegalStateException(INJECTED) }
            awaitUntil("virtual scheduler failure is captured", timeoutMs = 5_000) {
                scheduler.failures.size == 1
            }

            val laterTask = CountDownLatch(1)
            scheduler.submit(0) { laterTask.countDown() }

            laterTask.await(5, TimeUnit.SECONDS) shouldBe true
            scheduler.failures.shouldHaveSize(1).single().message shouldBe INJECTED
            scheduler.awaitIdle(1_000) shouldBe true
            capturedErr.toString(Charsets.UTF_8) shouldContain INJECTED
        } finally {
            System.setErr(originalErr)
            scheduler.shutdown()
        }
    }

    @Test
    fun recordsAThrowUnderCoroutineSchedulerAndTheLoopSurvives() {
        val scheduler = InstrumentedScheduler(CoroutineScheduler("bench-capture-coroutine"))
        try {
            scheduler.submit(0) { throw IllegalStateException(INJECTED) }
            awaitUntil("coroutine scheduler failure is captured", timeoutMs = 5_000) {
                scheduler.failures.size == 1
            }

            val laterTask = CountDownLatch(1)
            scheduler.submit(0) { laterTask.countDown() }

            laterTask.await(5, TimeUnit.SECONDS) shouldBe true
            scheduler.failures.shouldHaveSize(1).single().message shouldBe INJECTED
            scheduler.awaitIdle(1_000) shouldBe true
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun idleIsFalseWhileATaskIsQueuedOrRunningAndTrueAfter() {
        val scheduler = InstrumentedScheduler(VirtualThreadScheduler("bench-capture-idle"))
        val release = CountDownLatch(1)
        try {
            scheduler.submit(0) { release.await() }

            scheduler.idle shouldBe false
            scheduler.awaitIdle(50) shouldBe false

            release.countDown()
            scheduler.awaitIdle(1_000) shouldBe true
            scheduler.submitted shouldBe 1L
            scheduler.completed shouldBe 1L
        } finally {
            release.countDown()
            scheduler.shutdown()
        }
    }

    @Test
    fun aTaskThatSubmitsATaskIsNotIdleUntilBothFinish() {
        val scheduler = InstrumentedScheduler(VirtualThreadScheduler("bench-capture-nested"))
        try {
            scheduler.submit(0) {
                scheduler.submit(0) { }
            }

            scheduler.awaitIdle(1_000) shouldBe true
            scheduler.submitted shouldBe 2L
            scheduler.completed shouldBe 2L
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun colorAwaitAndShutdownForwardToTheWrappedScheduler() {
        val inner = ForwardingProbeScheduler()
        val scheduler = InstrumentedScheduler(inner)
        val future = CompletableFuture.completedFuture("forwarded")

        scheduler.color shouldBe HostColor.SUSPENDING
        scheduler.await(future) shouldBe "forwarded"
        inner.awaited shouldBeSameInstanceAs future

        scheduler.shutdown()
        inner.shutdownCalled shouldBe true
    }

    private class ForwardingProbeScheduler : HostScheduler {
        override val color: HostColor = HostColor.SUSPENDING
        var awaited: CompletableFuture<*>? = null
        var shutdownCalled: Boolean = false

        override fun submit(priority: Int, action: suspend () -> Unit) = Unit

        override fun <T> await(future: CompletableFuture<T>): T {
            awaited = future
            return future.join()
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }

    private companion object {
        const val INJECTED = "bench: injected"
    }
}
