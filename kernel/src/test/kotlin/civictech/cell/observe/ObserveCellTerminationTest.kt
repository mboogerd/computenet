package civictech.cell.observe

import civictech.cell.CellContext
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(60)
class ObserveCellTerminationTest {

    private var tagCounter = 0L

    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    private fun liveThreadsNamed(name: String): Set<Long> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name == name }
        .mapTo(mutableSetOf()) { it.threadId() }

    @Test
    fun `observe close can be awaited through a blocked listener`() {
        val sink = ObserveCell(View.set<Int>())
        assertBlockedClose(sink::onChange, sink::close, sink::awaitTermination)
    }

    @Test
    fun `aligned close can be awaited through a blocked listener`() {
        val sink = AlignedCompositeCell(mapOf("items" to View.set<Int>()))
        assertBlockedClose(sink::onChange, sink::close, sink::awaitTermination)
    }

    @Test
    fun `composite close can be awaited through a blocked listener`() {
        val sink = CompositeSink(emptyMap())
        assertBlockedClose(sink::onChange, sink::close, sink::awaitTermination)
    }

    @Test
    fun `await termination before terminal close is refused`() {
        val observe = ObserveCell(View.set<Int>())
        val aligned = AlignedCompositeCell(mapOf("items" to View.set<Int>()))
        val composite = CompositeSink(emptyMap())

        assertThrows<IllegalStateException> { observe.awaitTermination(0) }
        assertThrows<IllegalStateException> { aligned.awaitTermination(0) }
        assertThrows<IllegalStateException> { composite.awaitTermination(0) }

        observe.close()
        aligned.close()
        composite.close()
    }

    @Test
    fun `observe terminal close survives a restart cycle without minting a dispatcher`() {
        val sink = ObserveCell(View.set<Int>())
        val ownThread = "observe-cell-${sink.ref.id}"
        val firstFire = CountDownLatch(1)
        val postCloseFire = CountDownLatch(1)
        val releaseUnexpected = CountDownLatch(1)
        val fires = AtomicInteger()
        sink.onChange {
            if (fires.incrementAndGet() == 1) {
                firstFire.countDown()
            } else {
                postCloseFire.countDown()
                releaseUnexpected.await(30, TimeUnit.SECONDS)
            }
        }
        check(firstFire.await(30, TimeUnit.SECONDS)) { "initial observe catch-up did not run" }

        sink.close()
        sink.awaitTermination(30_000).shouldBeTrue()
        liveThreadsNamed(ownThread).shouldBeEmpty()

        try {
            val ctx = object : CellContext {}
            sink.onDeactivate(ctx)
            sink.onActivate(ctx)
            sink.inlet.call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))

            sink.current() shouldBe setOf(1)
            postCloseFire.await(1, TimeUnit.SECONDS).shouldBeFalse()
            fires.get() shouldBe 1
            liveThreadsNamed(ownThread).shouldBeEmpty()
        } finally {
            releaseUnexpected.countDown()
            sink.close()
        }
    }

    @Test
    fun `aligned terminal close survives a restart cycle without minting a dispatcher`() {
        val sink = AlignedCompositeCell(mapOf("items" to View.set<Int>()))
        val ownThread = "aligned-observe-${sink.ref.id}"
        val firstFire = CountDownLatch(1)
        val postCloseFire = CountDownLatch(1)
        val releaseUnexpected = CountDownLatch(1)
        val fires = AtomicInteger()
        sink.onChange {
            if (fires.incrementAndGet() == 1) {
                firstFire.countDown()
            } else {
                postCloseFire.countDown()
                releaseUnexpected.await(30, TimeUnit.SECONDS)
            }
        }
        check(firstFire.await(30, TimeUnit.SECONDS)) { "initial aligned catch-up did not run" }

        sink.close()
        sink.awaitTermination(30_000).shouldBeTrue()
        liveThreadsNamed(ownThread).shouldBeEmpty()

        try {
            val ctx = object : CellContext {}
            sink.onDeactivate(ctx)
            sink.onActivate(ctx)
            sink.inlets.getValue("items").call.propagate(SetDelta(adds = mapOf(1 to setOf(freshTag()))))

            sink.current() shouldBe mapOf("items" to setOf(1))
            postCloseFire.await(1, TimeUnit.SECONDS).shouldBeFalse()
            fires.get() shouldBe 1
            liveThreadsNamed(ownThread).shouldBeEmpty()
        } finally {
            releaseUnexpected.countDown()
            sink.close()
        }
    }

    @Test
    fun `terminal close awaits a predecessor parked by deactivation`() {
        val ctx = object : CellContext {}

        val observe = ObserveCell(View.set<Int>())
        val observeEntered = CountDownLatch(1)
        val observeRelease = CountDownLatch(1)
        observe.onChange {
            observeEntered.countDown()
            observeRelease.await(30, TimeUnit.SECONDS)
        }
        check(observeEntered.await(30, TimeUnit.SECONDS)) { "observe listener did not enter" }
        observe.onDeactivate(ctx)
        observe.onActivate(ctx)
        observe.close()

        val aligned = AlignedCompositeCell(mapOf("items" to View.set<Int>()))
        val alignedEntered = CountDownLatch(1)
        val alignedRelease = CountDownLatch(1)
        aligned.onChange {
            alignedEntered.countDown()
            alignedRelease.await(30, TimeUnit.SECONDS)
        }
        check(alignedEntered.await(30, TimeUnit.SECONDS)) { "aligned listener did not enter" }
        aligned.onDeactivate(ctx)
        aligned.onActivate(ctx)
        aligned.close()

        try {
            observe.awaitTermination(1).shouldBeFalse()
            aligned.awaitTermination(1).shouldBeFalse()
        } finally {
            observeRelease.countDown()
            alignedRelease.countDown()
        }
        observe.awaitTermination(30_000).shouldBeTrue()
        aligned.awaitTermination(30_000).shouldBeTrue()
    }

    private fun <S> assertBlockedClose(
        register: ((S) -> Unit) -> Unit,
        close: () -> Unit,
        awaitTermination: (Long) -> Boolean,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var closed = false
        register {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            returned.countDown()
        }
        try {
            check(entered.await(30, TimeUnit.SECONDS)) { "listener did not enter" }
            close()
            closed = true
            awaitTermination(1).shouldBeFalse()
            returned.count shouldBe 1L
        } finally {
            release.countDown()
            if (!closed) close()
        }
        awaitTermination(30_000).shouldBeTrue()
        returned.count shouldBe 0L
    }
}
