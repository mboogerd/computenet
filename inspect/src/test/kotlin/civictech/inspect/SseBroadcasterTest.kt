package civictech.inspect

import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Invariant 6 of `10-target-v3.md`: the viz never blocks the graph. A client
 * that stops reading must cost the producer nothing but its own stalest frames.
 */
class SseBroadcasterTest {

    @Test
    fun `a stalled client drops its oldest frames and never blocks the producer`() {
        val broadcaster = SseBroadcaster(capacity = 4)
        val received = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        broadcaster.attach({ frame ->
            received += frame
            if (received.size == 1) {
                entered.countDown()
                release.await()
            }
        })

        broadcaster.publish("f1")
        // the pump is now inside the first write, holding the stream open
        entered.await(5, TimeUnit.SECONDS) shouldBe true

        // 20 frames into a queue of 4, with the only consumer stalled: the
        // producer is a graph thread and must return regardless
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            (2..21).forEach { broadcaster.publish("f$it") }
        }
        broadcaster.droppedFrames shouldBe 16L

        release.countDown()
        awaitUntil("stalled client drained", timeoutMs = 5_000) { received.size == 5 }

        // drop-oldest: the frame in flight, then the newest capacity frames.
        // The client sees the gap (f1 -> f18) and refetches the snapshot.
        received.toList() shouldBe listOf("f1", "f18", "f19", "f20", "f21")
        broadcaster.close()
    }

    @Test
    fun `a client whose write fails detaches itself`() {
        val broadcaster = SseBroadcaster(capacity = 2)
        val detached = CountDownLatch(1)
        broadcaster.attach({ error("client is gone") }, onDetach = { detached.countDown() })

        broadcaster.publish("f1")

        detached.await(5, TimeUnit.SECONDS) shouldBe true
        broadcaster.clientCount shouldBe 0
        // a detached client is not a producer error
        broadcaster.publish("f2")
        broadcaster.close()
    }

    @Test
    fun `every attached client gets every frame`() {
        val broadcaster = SseBroadcaster(capacity = 8)
        val a = CopyOnWriteArrayList<String>()
        val b = CopyOnWriteArrayList<String>()
        broadcaster.attach({ a += it })
        broadcaster.attach({ b += it })

        broadcaster.publish("f1")
        broadcaster.publish("f2")

        awaitUntil("both clients drained", timeoutMs = 5_000) { a.size == 2 && b.size == 2 }
        a.toList() shouldBe listOf("f1", "f2")
        b.toList() shouldBe listOf("f1", "f2")
        broadcaster.close()
    }
}
