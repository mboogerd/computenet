package civictech.concord.driver.kernel

import civictech.cell.wire.PeerAddress
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerStats
import civictech.cell.wire.PeerTransport
import civictech.cell.wire.Peering
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TransportQuiesceEnqueueBarrierTest {

    @Test
    @Timeout(10)
    fun `quiesce cannot settle while an IO thread is held after receipt and before enqueue`() {
        val transport = HeldReceiveTransport()
        val driver = KernelDriver(0L, transport)
        val releaseEnqueue = CountDownLatch(1)
        var io: Thread? = null
        try {
            driver.createHost("h1")
            driver.createHost("h2")

            val ioThread = thread(name = "fake-ws-io-held-before-enqueue") {
                transport.receiveThenEnqueue(releaseEnqueue)
            }
            io = ioThread
            transport.received.await(5, TimeUnit.SECONDS) shouldBe true

            val quiesced = CompletableFuture.supplyAsync { driver.quiesce(BUDGET) }
            transport.unenqueuedStatsRead.await(5, TimeUnit.SECONDS) shouldBe true
            assertThrows(TimeoutException::class.java) {
                quiesced.get(100, TimeUnit.MILLISECONDS)
            }

            releaseEnqueue.countDown()
            ioThread.join(5_000)
            quiesced.get(5, TimeUnit.SECONDS).settled shouldBe true
        } finally {
            releaseEnqueue.countDown()
            io?.join(5_000)
            driver.close()
        }
    }

    private class HeldReceiveTransport : PeerTransport {
        override val scheme: String = "ws"
        val received = CountDownLatch(1)
        val unenqueuedStatsRead = CountDownLatch(1)
        private val framesReceived = AtomicLong()
        private val framesEnqueued = AtomicLong()
        private val framesSentByDialer = AtomicLong()

        override fun parseAddress(text: String): PeerAddress = Address(text)

        override fun listen(address: PeerAddress, side: Peering.Side): PeerListener = object : PeerListener {
            override val side: Peering.Side = side
            override val boundAddress: PeerAddress = address
            override val stats: PeerStats
                get() {
                    val enqueued = framesEnqueued.get()
                    val received = framesReceived.get()
                    if (received > enqueued) unenqueuedStatsRead.countDown()
                    return PeerStats(0, received, 0, 0, enqueued)
                }

            override fun close() = Unit
        }

        override fun dial(address: PeerAddress, side: Peering.Side): PeerConnection = object : PeerConnection {
            override val side: Peering.Side = side
            override val stats: PeerStats get() = PeerStats(framesSentByDialer.get(), 0, 0, 0)
            override val isCarrying: Boolean = true
            override fun partition() = Unit
            override fun heal() = Unit
            override fun close() = Unit
        }

        fun receiveThenEnqueue(releaseEnqueue: CountDownLatch) {
            framesSentByDialer.incrementAndGet()
            framesReceived.incrementAndGet()
            received.countDown()
            releaseEnqueue.await()
            framesEnqueued.incrementAndGet()
        }
    }

    private data class Address(override val text: String) : PeerAddress {
        override val scheme: String = "ws"
    }

    private companion object {
        const val BUDGET = 5_000_000
    }
}
