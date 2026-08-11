package civictech.concord.driver.kernel

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.test.Test

/**
 * The harness-integrity gate behind `CTL-GF-01` (computenet-dqy.18).
 *
 * `CTL-GF-01` is a **control**: the corpus runner asserts it FAILS a declared
 * check, because the graph asks for the plain (non-wave-aligned) scalar combine
 * over a fork-join diamond and then asserts wave-aligned semantics of it. The
 * torn, odd intermediate sum that makes that assertion false is only visible on
 * the *observation stream*, so the control is only a control while the driver's
 * observation log is complete.
 *
 * It was not. The log used to be collected through
 * [civictech.cell.observe.ObservationSink.onChange], and the kernel's
 * `ObserveCell` deliberately invokes listeners on a per-sink single-thread
 * executor rather than inline (Observe.kt, T08 finding 4). The runner quiesces
 * the [civictech.cell.host.SimulationController] on its own thread and reads the
 * log with no happens-before edge to that executor, so the log was whatever the
 * dispatcher thread had managed to append — 92 to 101 entries run to run on an
 * idle machine, and **zero** when the dispatcher was starved by an oversubscribed
 * one (reproduced by re-running this graph's sweep alongside 2000 runnable
 * threads: every one of the 20 runs read an empty log). An empty log makes
 * `observations-all-satisfy` vacuously true, every check passes, and the control
 * silently stops controlling — exactly the CI failure at `CorpusRunner.kt:142`.
 *
 * The fix records the stream at the fold ([RecordedView]) instead, on the thread
 * that applies the delta. This test pins the property that makes the control
 * honest: the stream is **whole** and **identical on every run**, so the odd
 * intermediate is always there to be caught. It deliberately asserts the exact
 * expected sequence rather than "an odd value appears somewhere" — a weaker
 * assertion would pass again on a truncated log.
 */
class ObservationLogCaptureTest {

    private companion object {
        const val BUDGET = 5_000_000
        const val RUNS = 20
        const val INCREMENTS = 50
    }

    /** `CTL-GF-01`'s graph: one counter source forked through two identity arms into the plain summing combine. */
    private fun diamond(seed: Long): KernelDriver = KernelDriver(seed).apply {
        spawn("", "n", "counter-source", emptyMap())
        spawn("", "l", "map", mapOf("fn" to Value.StrVal("identity")))
        spawn("", "r", "map", mapOf("fn" to Value.StrVal("identity")))
        spawn("", "s", "combine-latest", mapOf("fn" to Value.StrVal("sum")))
        spawn("", "v", "value-view", emptyMap())
        connect("n", "l", null, null, null)
        connect("n", "r", null, null, null)
        connect("l", "s", "left", null, null)
        connect("r", "s", "right", null, null)
        connect("s", "v", null, null, null)
    }

    @Test
    fun `the observation stream is whole and run-identical, so CTL-GF-01's torn sum is always observed`() {
        // Each increment reaches the combine down both arms in turn, so the plain
        // (non-coalescing) form emits +1 twice per increment: the view walks every
        // integer from its catch-up 0 up to 2 * INCREMENTS, torn odd values included.
        val expected = (0..(2 * INCREMENTS)).map { Value.IntVal(it.toLong()) }

        val logs = (0 until RUNS).map { run ->
            val driver = diamond(run.toLong())
            repeat(INCREMENTS) { driver.apply("n", "increment", null) }
            driver.quiesce(BUDGET)
            driver.observationLog("v")
        }

        logs.forEachIndexed { run, log ->
            inRun(run) { log shouldBe expected }
            // The control's provocation, named explicitly: a wave-aligned combine
            // would never publish an odd partial sum, and CTL-GF-01 exists to catch
            // the plain form doing so.
            inRun(run) {
                log.filterIsInstance<Value.IntVal>().filter { it.value % 2L != 0L } shouldHaveSize INCREMENTS
            }
        }
    }

    private fun <T> inRun(run: Int, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("run $run (seed $run): ${e.message}", e)
        }
}
