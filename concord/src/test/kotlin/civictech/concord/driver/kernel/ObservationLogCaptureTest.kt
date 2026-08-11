package civictech.concord.driver.kernel

import civictech.cell.observe.ObservationSink
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import java.util.concurrent.CountDownLatch
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

    /**
     * The load-free regression guard for the capture *mechanism*, as opposed to
     * the stream's contents.
     *
     * The three content tests here can all pass against the pre-fix capture on a
     * quiet machine — the dispatcher usually keeps up — so on their own they
     * catch a revert to `sink.onChange` only by luck, which is the very property
     * this ticket exists to remove. This one catches it on any machine, because
     * it takes the dispatcher's opportunity to keep up away rather than hoping
     * it does not get one: a listener that never returns
     * occupies the sink's **single-thread** executor for the whole run, so every
     * submission queued behind it (a capture listener's included) is stalled
     * until after the checks have read the log.
     *
     * Pre-fix that leaves the log holding only the catch-up entry the driver's
     * own listener was submitted with first; the fold-side capture is unaffected
     * because it never touches the executor at all.
     */
    @Test
    fun `a wedged listener dispatcher does not truncate the observation log`() {
        val expected = (0..(2 * INCREMENTS)).map { Value.IntVal(it.toLong()) }
        val driver = diamond(0L)
        val wedge = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        (driver.cells.getValue("v").sink as ObservationSink<Any?>).onChange {
            wedge.await() // holds the sink's only dispatch thread for the whole run
        }
        try {
            repeat(INCREMENTS) { driver.apply("n", "increment", null) }
            driver.quiesce(BUDGET)
            driver.observationLog("v") shouldBe expected
        } finally {
            wedge.countDown()
        }
    }

    /**
     * The same capture change was made on the `dist` replica companion
     * ([KernelDriverDist], which now builds it through the catalog rather than by
     * hand) — and **no** corpus scenario currently declares an `observations-*`
     * check on a replica, so without this the whole path is unguarded: a
     * regression to `sink.onChange` there would be silent until the first
     * scenario that reads a replica's stream, and would then read as a vacuous
     * pass rather than a failure.
     */
    @Test
    fun `a dist replica's observation stream is whole and run-identical`() {
        val h1 = listOf(set(), set("from-h1"), set("from-h1", "from-h2"))
        val h2 = listOf(set(), set("from-h2"), set("from-h1", "from-h2"))

        for (run in 0 until RUNS) {
            val driver = KernelDriver(run.toLong()).apply {
                createHost("h1")
                createHost("h2")
                spawn("h1", "r1", "set-source", mapOf("replica-of" to Value.StrVal("shared")))
                spawn("h2", "r2", "set-source", mapOf("replica-of" to Value.StrVal("shared")))
                apply("r1", "add", Value.StrVal("from-h1"))
                apply("r2", "add", Value.StrVal("from-h2"))
                quiesce(BUDGET)
            }
            // Each replica observes its own write first and the sibling's merged
            // gossip second, so the two streams differ in their middle element and
            // meet at the converged fold — a truncated capture loses exactly that.
            inRun(run) { driver.observationLog("r1") shouldBe h1 }
            inRun(run) { driver.observationLog("r2") shouldBe h2 }
        }
    }

    /**
     * The `dur` driver keeps one log per *cell id* so it outlives the crash, and
     * rebuilds the fold over that surviving list ([KernelDriverDur]'s `build`).
     * This pins that the stream really is continuous across crash+recover —
     * nothing dropped, nothing duplicated — which is the one place the capture
     * change could have lost or double-counted an entry, and which no corpus
     * scenario reads either.
     */
    @Test
    fun `a dur view's observation stream is continuous across crash and recover`() {
        val expected = listOf(
            // pre-crash: the fold's catch-up, then one entry per accepted add
            set(), set("o1"), set("o1", "o2"), set("o1", "o2", "o3"), set("o1", "o2", "o3", "o4"),
            // the rebuilt (empty) fold's catch-up, then the journal tail replayed on
            // top of the silently restored checkpoint {o1,o2} — `ObserveCell.restore`
            // is a state assignment, not a fold, so it publishes no entry of its own
            set(), set("o1", "o2", "o3"), set("o1", "o2", "o3", "o4"),
        )

        for (run in 0 until RUNS) {
            val driver = KernelDriver(run.toLong()).apply {
                spawn("dur", "dsource", "journal-set-source", emptyMap())
                spawn("dur", "dview", "journal-set-view", emptyMap())
                spawn("dur", "ctl", "journal", emptyMap())
                connect("dsource", "dview", null, null, null)
                apply("dsource", "add", Value.StrVal("o1"))
                apply("dsource", "add", Value.StrVal("o2"))
                quiesce(BUDGET)
                snapshot("dsource")          // checkpoint the prefix
                apply("dsource", "add", Value.StrVal("o3"))
                apply("dsource", "add", Value.StrVal("o4"))
                quiesce(BUDGET)
                despawn("ctl")               // crash + recover
                quiesce(BUDGET)
            }
            inRun(run) { driver.observationLog("dview") shouldBe expected }
        }
    }

    /** A `set-view` observation, in the shape [KernelCatalog.readView] publishes. */
    private fun set(vararg items: String): Value =
        Value.ListVal(items.map { Value.StrVal(it) as Value }.sortedBy { it.toString() })

    private fun <T> inRun(run: Int, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("run $run (seed $run): ${e.message}", e)
        }
}
