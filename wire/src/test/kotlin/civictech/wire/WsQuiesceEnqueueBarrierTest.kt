package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WsQuiesceEnqueueBarrierTest {

    @Test
    @Timeout(10)
    fun `socket receipt leads bridge enqueue while the IO thread is held at that seam`() {
        val registry = LocationRegistry()
        val side = Peering.Side(registry, ManagedHost(registry = registry))
        val betweenReceiptAndEnqueue = CountDownLatch(1)
        val releaseEnqueue = CountDownLatch(1)
        val session = WsTransport.Session(
            side = side,
            send = {},
            refuse = {},
            beforeFrameEnqueue = {
                betweenReceiptAndEnqueue.countDown()
                releaseEnqueue.await()
            },
        )
        session.onText(session.hello())

        val io = thread(name = "ws-io-held-before-enqueue") {
            session.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        }
        try {
            assertTrue(betweenReceiptAndEnqueue.await(5, TimeUnit.SECONDS), "the IO thread never reached the enqueue seam")
            session.framesReceived shouldBe 1L
            session.framesEnqueued shouldBe 0L
        } finally {
            releaseEnqueue.countDown()
            io.join(5_000)
        }

        assertFalse(io.isAlive, "the held IO thread did not finish after release")
        session.framesEnqueued shouldBe 1L
    }
}
