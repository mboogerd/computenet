package civictech.iroh

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail

/**
 * A [LinkListener] that records what the reader thread delivered, so a test can
 * assert arrival ORDER and the exactly-once-ness of `LINK_DOWN` rather than only
 * the final state.
 */
class RecordingLinkListener(private val label: String) : LinkListener {

    private val data = LinkedBlockingQueue<ByteArray>()
    private val downs = LinkedBlockingQueue<String>()

    /** Every `LINK_DOWN` seen, including any beyond the first. */
    val downCount = AtomicInteger(0)

    /** Every `ERROR` on this link, in arrival order. */
    val errors = CopyOnWriteArrayList<String>()

    override fun onData(link: SidecarLink, payload: ByteArray) {
        data.put(payload)
    }

    override fun onDown(link: SidecarLink, reason: String) {
        downCount.incrementAndGet()
        downs.put(reason)
    }

    override fun onError(link: SidecarLink, reason: String) {
        errors.add(reason)
    }

    /** The next frame in arrival order, or a test failure after [seconds]. */
    fun nextData(seconds: Long = 30): ByteArray =
        data.poll(seconds, TimeUnit.SECONDS) ?: fail("$label: no DATA within ${seconds}s")

    /** The next `LINK_DOWN` reason, or a test failure after [seconds]. */
    fun nextDown(seconds: Long = 30): String =
        downs.poll(seconds, TimeUnit.SECONDS) ?: fail("$label: no LINK_DOWN within ${seconds}s")

    /** True when nothing further arrived within [millis] — used to pin exactly-once. */
    fun noFurtherDown(millis: Long = 500): Boolean = downs.poll(millis, TimeUnit.MILLISECONDS) == null
}
