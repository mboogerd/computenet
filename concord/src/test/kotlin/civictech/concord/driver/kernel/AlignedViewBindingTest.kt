package civictech.concord.driver.kernel

import civictech.cell.observe.AlignedCompositeCell
import civictech.concord.value.Value
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.assertThrows

/**
 * The kernel binding of catalog `aligned-view` (computenet-5ubdv.2, KE2 §5.8,
 * `[22-OBS-01]`/`[22-OBS-02]`): an [AlignedCompositeCell] whose composite
 * observation stream is **whole and run-identical** at quiescence.
 *
 * The stream is captured through the sink's asynchronous listener, the path
 * [ObservationLogCaptureTest] documents as having truncated single-view logs
 * (computenet-dqy.18). What makes it honest here is the drain barrier
 * [KernelDriver.quiesce] ends with (5ubdv-D1); the wedged-dispatcher case pins
 * that the barrier fails loudly rather than returning a short log, on any
 * machine and at any load — the load-free guard a revert to an unbarriered
 * listener fails.
 */
class AlignedViewBindingTest {

    private companion object {
        const val BUDGET = 5_000_000
        const val RUNS = 20
    }

    private fun int(n: Long) = Value.IntVal(n)
    private fun set(vararg n: Long) = Value.ListVal(n.map { int(it) })
    private fun composite(vararg members: Pair<String, Value>) = Value.MapVal(linkedMapOf(*members))

    private fun views(vararg members: Pair<String, String>): Map<String, Value> =
        mapOf("views" to Value.MapVal(members.associate { (name, id) -> name to Value.StrVal(id) }))

    private fun <T> withDriver(seed: Long, body: KernelDriver.() -> T): T {
        val driver = KernelDriver(seed)
        try {
            return driver.body()
        } finally {
            driver.close()
        }
    }

    // ---- graph A: [22-OBS-01]'s shape (BS-1) ----------------------------------

    private fun KernelDriver.graphA() {
        spawn("", "s", "set-source", emptyMap())
        spawn("", "l", "map", mapOf("fn" to Value.StrVal("identity")))
        spawn("", "f", "filter", mapOf("fn" to Value.StrVal("even")))
        spawn("", "c", "aligned-view", views("items" to "set-view", "evens" to "set-view"))
        connect("s", "l", null, null, null)
        connect("s", "f", null, null, null)
        connect("l", "c", "items", null, null)
        connect("f", "c", "evens", null, null)
    }

    private fun KernelDriver.scriptA() {
        listOf(1L, 2, 3, 4).forEach { apply("s", "add", int(it)) }
        apply("s", "remove", int(2))
        listOf(5L, 6).forEach { apply("s", "add", int(it)) }
        apply("s", "remove", int(4))
        listOf(7L, 8).forEach { apply("s", "add", int(it)) }
    }

    @Test
    fun `build - an aligned-view is one AlignedCompositeCell with an inlet per member and an all-empty first log entry`() {
        val built = KernelCatalog.build("aligned-view", views("items" to "set-view", "evens" to "set-view"))
        val cell = assertIs<AlignedCompositeCell>(built.cell)
        try {
            cell.inlets.keys shouldBe setOf("items", "evens")
            built.viewKind shouldBe KernelCatalog.ViewKind.COMPOSITE
            built.observations!!.first() shouldBe composite("items" to set(), "evens" to set())
        } finally {
            cell.close()
        }
    }

    @Test
    fun `22-OBS-01 - every observed composite is whole, and the stream is identical on every seed`() {
        val expectedFinal = composite("items" to set(1, 3, 5, 6, 7, 8), "evens" to set(6, 8))
        val logs = (0 until RUNS).map { run ->
            withDriver(run.toLong()) {
                graphA()
                scriptA()
                quiesce(BUDGET).settled shouldBe true
                inRun(run) { readView("c") shouldBe expectedFinal }
                observationLog("c")
            }
        }
        logs.forEachIndexed { run, log ->
            inRun(run) {
                log.first() shouldBe composite("items" to set(), "evens" to set())
                log.last() shouldBe expectedFinal
                log.forEachIndexed { i, entry ->
                    val members = (entry as Value.MapVal).entries
                    val items = (members.getValue("items") as Value.ListVal).items
                    val evens = (members.getValue("evens") as Value.ListVal).items
                    withClue("entry $i $entry is torn") {
                        evens.toSet() shouldBe items.filter { (it as Value.IntVal).value % 2 == 0L }.toSet()
                    }
                }
            }
        }
        // The catch-up plus one composite per effective wave: every op of the
        // ten-op script changes `items`, so every wave publishes.
        logs.first().size shouldBe 11
        logs.forEachIndexed { run, log -> inRun(run) { log shouldBe logs.first() } }
    }

    // ---- graph B: [22-OBS-02]'s shape (BS-2/BS-3) ------------------------------

    @Test
    fun `22-OBS-02 - two members on one outlet and an absorbing arm settle every wave, the last one included`() {
        val expectedFinal = composite("a" to set(1, 3, 5, 6), "b" to set(1, 3, 5, 6), "evens" to set(6))
        repeat(RUNS) { run ->
            withDriver(run.toLong()) {
                spawn("", "s", "set-source", emptyMap())
                spawn("", "f", "filter", mapOf("fn" to Value.StrVal("even")))
                spawn("", "c", "aligned-view", views("a" to "set-view", "b" to "set-view", "evens" to "set-view"))
                connect("s", "c", "a", null, null)
                connect("s", "c", "b", null, null)
                connect("s", "f", null, null, null)
                connect("f", "c", "evens", null, null)
                listOf(1L, 2, 3, 4).forEach { apply("s", "add", int(it)) }
                apply("s", "remove", int(2))
                apply("s", "add", int(6))
                apply("s", "remove", int(4))
                // Odd: the filter absorbs it (CP-A3 absorb-ack), so this wave
                // settles on the `evens` arm by Progress alone.
                apply("s", "add", int(5))
                quiesce(BUDGET).settled shouldBe true
                inRun(run) {
                    readView("c") shouldBe expectedFinal
                    deadLetters() shouldBe emptyList()
                    // BS-3: the last wave was published — it did not wait forever on the absorbing arm.
                    val last = (observationLog("c").last() as Value.MapVal).entries
                    (last.getValue("a") as Value.ListVal).items shouldContainValue int(5)
                    (last.getValue("b") as Value.ListVal).items shouldContainValue int(5)
                }
            }
        }
    }

    // ---- the drain barrier fails loudly ---------------------------------------

    @Test
    fun `a wedged listener dispatcher makes quiesce throw instead of returning a truncated log`() {
        val release = CountDownLatch(1)
        withDriver(0) {
            compositeDrainTimeout = 1.seconds
            graphA()
            (cells.getValue("c").cell as AlignedCompositeCell).onChange { release.await() }
            try {
                scriptA()
                val failure = assertThrows<IllegalStateException> { quiesce(BUDGET) }
                failure.message!! shouldContain "drain barrier"
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `a closed bound sink makes quiesce fail promptly instead of waiting for the drain timeout`() {
        withDriver(0) {
            compositeDrainTimeout = 1.seconds
            graphA()
            scriptA()
            val sink = cells.getValue("c").cell as AlignedCompositeCell
            sink.close()

            val failure = assertThrows<IllegalStateException> { quiesce(BUDGET) }
            failure.message!! shouldContain "closed or deactivated"
        }
    }

    // ---- refusals ----------------------------------------------------------------

    @Test
    fun `a link into an aligned-view must name its member inlet`() {
        withDriver(0) {
            graphA()
            val failure = assertThrows<UnsupportedCatalogBinding> { connect("s", "c", null, null, null) }
            failure.message!! shouldContain "inlet:"
        }
    }

    @Test
    fun `an aligned-view refuses a member id it cannot fold, and a missing or empty views map`() {
        withDriver(0) {
            assertThrows<UnsupportedCatalogBinding> { spawn("", "x", "aligned-view", views("x" to "list-view")) }
                .message!! shouldContain "list-view"
            assertThrows<UnsupportedCatalogBinding> { spawn("", "y", "aligned-view", views("y" to "aligned-view")) }
                .message!! shouldContain "aligned-view"
            assertThrows<UnsupportedCatalogBinding> { spawn("", "z", "aligned-view", emptyMap()) }
                .message!! shouldContain "views"
            assertThrows<UnsupportedCatalogBinding> { spawn("", "w", "aligned-view", views()) }
                .message!! shouldContain "non-empty"
        }
    }

    @Test
    fun `close is idempotent`() {
        val driver = KernelDriver(0)
        driver.graphA()
        driver.close()
        driver.close()
    }

    private infix fun List<Value>.shouldContainValue(v: Value) = (v in this) shouldBe true

    private fun inRun(run: Int, body: () -> Unit) = withClue("run $run", body)
}
