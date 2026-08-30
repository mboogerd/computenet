package civictech.inspect

import civictech.testkit.readOffCallerThread
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one instrument test for the shared SSE tap (computenet-0sfc), and the
 * discriminator for computenet-i45n.
 *
 * It replaces eight per-class copies of itself — one in each `:inspect` SSE test
 * class — which is the point of the hoist: eight copies of the helper were eight
 * copies of one defect, and the eight independent occurrences they produced are
 * what nobody could join up.
 *
 * The discrimination is the **already-complete** future, not the executor. An
 * already-complete future is the state `sendAsync`'s future is in whenever the
 * response headers won the race, and it is precisely the state in which
 * `thenAccept` runs the body inline on the caller (`uniAcceptNow`). A test that
 * merely observed that an async executor is async would pass against the broken
 * code's own happy path and pin nothing. Restoring the bare `thenAccept` in
 * [readOffCallerThread] turns this red on the `shouldNotBe` below,
 * deterministically and in milliseconds — no load, no repetition, and no
 * five-minute timeout needed to see it.
 *
 * It lives in `:inspect` rather than beside the fixture in `:testkit` because
 * `:inspect` is the module whose suite the defect actually hung and where the
 * eight copies it replaces lived — so the pin fails in the gate that would
 * otherwise pay for the regression.
 */
class SseTapInstrumentTest {

    @Test
    fun `an already-complete response future is never read on the thread that attached it`() {
        val caller = Thread.currentThread()
        val ranOn = CompletableFuture<Thread>()
        val readers = Executors.newVirtualThreadPerTaskExecutor()

        try {
            readOffCallerThread(CompletableFuture.completedFuture(Unit), readers) {
                ranOn.complete(Thread.currentThread())
            }

            ranOn.get(5, TimeUnit.SECONDS) shouldNotBe caller
        } finally {
            // shutdownNow(), never close(): an unbounded wait is this suite's
            // entire defect — see SseTap.close.
            readers.shutdownNow()
        }
    }
}
