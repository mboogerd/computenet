package civictech.demo.shell

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * computenet-4nxe8: [DemoShell.sse]'s `coalesce` window and [DemoShell.invalidate] —
 * a debounced alternative to calling [DemoShell.broadcast] directly, for a caller
 * (agora's `onCredence`) whose changes arrive in bursts and whose broadcast frame is
 * a whole-state snapshot, so only the newest state after a burst matters.
 *
 * Covers, per the bead's acceptance: a burst folds to one computed-and-broadcast
 * frame; the trailing edge always reflects the state as of the *last* `invalidate()`
 * in a window, including one that arrives while the previous coalesced frame is
 * still being computed (no lost update); `invalidate()` is a no-op after [DemoShell.stop];
 * and the default (`coalesce = null`) path is untouched — [DemoShellSseOrderingTest],
 * [DemoShellStalledClientTest], [DemoShellStopRaceTest] and
 * [DemoShellBindTest] all exercise that path unmodified and must keep passing.
 */
class DemoShellCoalesceTest {

    @Test
    fun `a burst of invalidate calls within one window computes and broadcasts the frame once`() {
        val computeCount = AtomicInteger()
        val value = AtomicInteger(0)
        val shell = DemoShell(0)
        shell.sse("/events", coalesce = Duration.ofMillis(WINDOW_MS)) {
            computeCount.incrementAndGet()
            "\"${value.get()}\""
        }
        shell.start()
        try {
            val received = connectReader(shell)
            awaitFrames(received, 1) // the initial frame; the client is now registered

            value.set(1)
            repeat(20) { shell.invalidate() } // a burst, well inside one window

            awaitFrames(received, 2) // initial + exactly one coalesced broadcast

            // Give any (wrongly) duplicated broadcast a chance to land before
            // asserting the burst folded to one compute.
            Thread.sleep(WINDOW_MS * 2)
            computeCount.get() shouldBe 2 // 1 initial connect + 1 coalesced compute
            received.last() shouldBe "\"1\""
        } finally {
            shell.stop()
        }
    }

    @Test
    fun `an invalidate that arrives while the coalesced frame is being computed is not lost`() {
        val computing = CountDownLatch(1)
        val releaseFirstCompute = CountDownLatch(1)
        val computeCount = AtomicInteger()
        val value = AtomicInteger(0)
        val shell = DemoShell(0)
        shell.sse("/events", coalesce = Duration.ofMillis(WINDOW_MS)) {
            val n = computeCount.incrementAndGet()
            // Snapshot the state at the START of this compute, before any
            // blocking below — this is what a real frame supplier does (it
            // reads current state), and it is what lets this test tell "the
            // in-flight compute's own frame" (n == 2, sees value == 1) apart
            // from "the frame the mid-compute invalidate() causes" (n == 3,
            // sees value == 2) rather than one call accidentally observing
            // both writes.
            val snapshot = value.get()
            if (n == 2) { // the coalesced compute triggered below, not the initial connect
                computing.countDown()
                check(releaseFirstCompute.await(5, TimeUnit.SECONDS)) { "never released" }
            }
            "\"$snapshot\""
        }
        shell.start()
        try {
            val received = connectReader(shell)
            awaitFrames(received, 1) // initial frame; client registered

            value.set(1)
            shell.invalidate()
            check(computing.await(5, TimeUnit.SECONDS)) { "the coalesced frame never started computing" }

            // The window has already fired (computeCount == 2, mid-compute),
            // so a fresh invalidate() here must start its own window rather
            // than being dropped — the guarantee under test.
            value.set(2)
            shell.invalidate()
            releaseFirstCompute.countDown()

            awaitFrames(received, 3) // initial "0", stale-in-flight "1", then "2"
            received shouldBe listOf("\"0\"", "\"1\"", "\"2\"")
        } finally {
            shell.stop()
        }
    }

    @Test
    fun `invalidate is a no-op after stop`() {
        val computeCount = AtomicInteger()
        val shell = DemoShell(0)
        shell.sse("/events", coalesce = Duration.ofMillis(WINDOW_MS)) {
            computeCount.incrementAndGet()
            "\"frame\""
        }
        shell.start()
        shell.stop()

        val before = computeCount.get()
        shell.invalidate() // must not throw, and must schedule nothing
        Thread.sleep(WINDOW_MS * 3)
        computeCount.get() shouldBe before
    }

    @Test
    fun `an endpoint registered without a coalesce window leaves invalidate a no-op and broadcast unchanged`() {
        val shell = DemoShell(0)
        shell.sse("/events") { "\"HELLO\"" } // no `coalesce` argument — today's default
        shell.start()
        try {
            val received = connectReader(shell)
            awaitFrames(received, 1)

            shell.invalidate() // nothing was registered for coalescing; must not throw or broadcast
            Thread.sleep(WINDOW_MS * 3)
            received.size shouldBe 1

            shell.broadcast { "\"WORLD\"" } // the pre-existing direct path still works
            awaitFrames(received, 2)
            received.last() shouldBe "\"WORLD\""
        } finally {
            shell.stop()
        }
    }

    private fun connectReader(shell: DemoShell): MutableList<String> {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val readerStarted = CountDownLatch(1)
        thread(name = "sse-reader", isDaemon = true) {
            val connection = URI("http://localhost:${shell.boundPort}/events").toURL()
                .openConnection() as HttpURLConnection
            connection.connectTimeout = BOUND_MS
            val body = connection.inputStream.bufferedReader()
            readerStarted.countDown()
            runCatching {
                body.lineSequence().forEach { line ->
                    if (line.startsWith("data: ")) received += line.removePrefix("data: ")
                }
            }
        }
        check(readerStarted.await(BOUND_MS.toLong(), TimeUnit.MILLISECONDS)) { "the SSE reader never connected" }
        return received
    }

    private fun awaitFrames(received: List<String>, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS.toLong())
        while (received.size < count) {
            check(System.nanoTime() < deadline) { "expected $count frame(s), saw ${received.size}: $received" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val BOUND_MS = 20_000
        const val WINDOW_MS = 150L
    }
}
