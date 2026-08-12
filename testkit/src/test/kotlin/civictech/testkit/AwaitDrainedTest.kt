package civictech.testkit

import civictech.cell.host.VirtualThreadScheduler
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [awaitDrained] exists to replace a detector that CPU starvation could fake,
 * so the claim "starvation can only delay this, never counterfeit it" gets
 * assertions of its own rather than an argument in a comment.
 *
 * Both tests construct, deterministically and without needing a busy machine,
 * the two situations that defeat a poll-and-compare detector: a host that makes
 * no visible progress for far longer than any sampling window, and a host whose
 * remaining work does not exist yet at the moment of sampling. Neither test can
 * fail *because* the machine is loaded — load only makes the awaited event
 * arrive later, and the assertion is about what ran, not about when.
 */
class AwaitDrainedTest {

    /**
     * A quiet host is not a finished host. Three 250ms tasks leave the observable
     * world unchanged for windows several times longer than the 150ms sampling
     * interval the agora durability test used to trust; the fence is ordered
     * behind them by `(priority, submission)`, so it cannot report quiescence
     * until the last one has actually run.
     */
    @Test
    fun `awaitDrained returns only after every queued task has run`() {
        val scheduler = VirtualThreadScheduler("awaitDrained-ordering")
        val ran = AtomicInteger()
        repeat(3) {
            scheduler.submit(20) {
                Thread.sleep(250) // widens the quiet window; nothing asserts on the duration
                ran.incrementAndGet()
            }
        }

        scheduler.awaitDrained("three 250ms tasks")

        assertEquals(3, ran.get(), "the fence ran before the queue drained")
        scheduler.shutdown()
    }

    /**
     * The case that matters for a dataflow graph: most of the work does not
     * exist when the fence is submitted — each hop enqueues the next. A fence
     * at [Int.MAX_VALUE] sorts behind work at any band whenever it arrives, so
     * it clears the whole cascade, not just the queue's initial contents.
     */
    @Test
    fun `awaitDrained stays behind work enqueued while the queue drains`() {
        val scheduler = VirtualThreadScheduler("awaitDrained-cascade")
        val ran = AtomicInteger()
        fun cascade(remaining: Int) {
            scheduler.submit(20) {
                ran.incrementAndGet()
                if (remaining > 0) cascade(remaining - 1)
            }
        }
        cascade(20) // one task now, twenty more minted mid-drain

        scheduler.awaitDrained("a 21-deep cascade")

        assertEquals(21, ran.get(), "the fence ran mid-cascade")
        scheduler.shutdown()
    }
}
