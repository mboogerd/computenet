package civictech.cell.control

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The three operations of the PN-11 primitive under the five hand-rolled park
 * sites: append-in-order, hold, drain-once (plus the resumable [ParkQueue.drainWhile]
 * the location-park uses). The real regression guard is the unchanged pin suite
 * (TrafficLight / migration / saturation / flip); this pins the primitive itself.
 */
class ParkQueueTest {

    @Test
    fun `append-in-order then drain-once returns everything in park order, then nothing`() {
        val q = ParkQueue<String>()
        q.park("a")
        q.park("b")
        q.park("c")

        q.drain() shouldBe listOf("a", "b", "c")
        // drain-once: the tail is gone after a single drain (the control's target).
        q.drain() shouldBe emptyList()
    }

    @Test
    fun `hold accumulates without releasing until drained`() {
        val q = ParkQueue<Int>()
        q.park(1)
        q.park(2)

        // held: nothing has been released; the items are still parked, in order.
        q.snapshot() shouldBe listOf(1, 2)
        q.size shouldBe 2

        q.drain() shouldBe listOf(1, 2)
        q.isEmpty() shouldBe true
    }

    @Test
    fun `drainWhile stops at the first rejection and retains the remainder in order`() {
        val q = ParkQueue<Int>()
        listOf(1, 2, 3, 4).forEach(q::park)

        val sent = mutableListOf<Int>()
        // accept heads below 3, refuse at 3 (a saturated/closed intake mid-drain)
        q.drainWhile { head -> (head < 3).also { if (it) sent += head } }

        sent shouldBe listOf(1, 2)
        // the rejected head and its successors stay parked, in order, for next time
        q.snapshot() shouldBe listOf(3, 4)
        // a later attempt resumes from where it stopped, then drains clean
        q.drainWhile { head -> true.also { sent += head } }
        sent shouldBe listOf(1, 2, 3, 4)
        q.isEmpty() shouldBe true
    }
}
