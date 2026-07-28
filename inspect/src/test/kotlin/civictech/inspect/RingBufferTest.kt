package civictech.inspect

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * M2-BE ticket: "Ring-buffer eviction test" — [RingBuffer] is the bounded
 * retention [Errors] uses for dead letters and restarts (contract's "current
 * dead letters retained in a bounded ring buffer (cap 200, oldest evicted)").
 */
class RingBufferTest {

    @Test
    fun `stays within capacity, evicting the oldest entry first`() {
        val ring = RingBuffer<Int>(3)

        (1..5).forEach { ring.add(it) }

        ring.snapshot() shouldBe listOf(3, 4, 5)
    }

    @Test
    fun `an empty buffer snapshots empty`() {
        RingBuffer<String>(10).snapshot() shouldBe emptyList()
    }

    @Test
    fun `under capacity, nothing is evicted and order is preserved`() {
        val ring = RingBuffer<String>(5)

        ring.add("a")
        ring.add("b")

        ring.snapshot() shouldBe listOf("a", "b")
    }
}
