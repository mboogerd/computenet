package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * V1A-BE — the `state.summary` window: coalesced, published even when quiet,
 * one trailing window on release, then silence.
 *
 * Driven directly against [Observations] with an injected clock and explicit
 * [Observations.sample] calls, exactly as [ObservationsIdleTest] drives the
 * idle deadline: nothing here sleeps, and nothing asserts on scheduler timing.
 * The only asynchrony is the graph itself — a delta reaching the sink's fold —
 * and every test crosses that with `awaitUntil` on the fold's own reading
 * before it advances the clock and samples.
 *
 * [InspectorObserveTest] covers the same feed end to end over SSE, through the
 * server's `tickAll()` seam; this covers what a window *is*.
 */
class ObservationsWindowTest {

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val now = AtomicLong(1_000_000L)
    private val published = mutableListOf<StateSummary>()

    private val observations = Observations(
        registry = registry,
        onSummary = { summary -> synchronized(published) { published += summary } },
        clock = now::get,
    )

    private fun set(): SetCell<String> = SetCell<String>().also { host.managementInlet.call.spawn(it) }

    private fun add(cell: SetCell<String>, element: String) {
        cell.inlet.call.add(element)
        awaitUntil("the fold caught '$element'") {
            (observations.reading(cell.ref)?.value as? Set<*>)?.contains(element) == true
        }
    }

    private fun summaries(ref: CellRef): List<StateSummary> =
        synchronized(published) { published.filter { it.ref == ref } }

    private fun sizeOf(summary: StateSummary): Int = (summary.reading.value as Set<*>).size

    /** One window of wall clock, then one window published. */
    private fun tick() {
        now.addAndGet(Observations.WINDOW_MS)
        observations.sample()
    }

    // ------------------------------------------------------------ coalescing

    @Test
    fun `a burst of changes inside one window publishes one summary carrying the latest reading`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        tick() // drain the subscription's own catch-up window

        repeat(5) { add(cell, "e$it") }
        val before = summaries(cell.ref).size
        tick()

        val window = summaries(cell.ref).drop(before)
        // five settled effective changes, one summary — and it carries the
        // fold as it stands *now*, never an intermediate value from mid-window
        window.size shouldBe 1
        window.single().changes shouldBe 5L
        sizeOf(window.single()) shouldBe 5
    }

    @Test
    fun `an open observation publishes every window, quiet or not, with staleMs growing`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        add(cell, "ada")
        tick()

        val quiet = (1..3).map { tick(); summaries(cell.ref).last() }

        quiet.map { it.changes } shouldBe listOf(0L, 0L, 0L)
        // silence on the wire would be indistinguishable from a released
        // observation or a stopped server; a quiet *window* says "quiet"
        quiet.map { it.reading.staleMs }.zipWithNext().forEach { (earlier, later) ->
            later shouldBe earlier + Observations.WINDOW_MS
        }
        quiet.map { it.reading.frontier }.toSet().size shouldBe 1
        quiet.map { sizeOf(it) }.toSet() shouldBe setOf(1)
    }

    @Test
    fun `staleMs decreases in exactly the windows where a change settled`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        add(cell, "ada")
        tick()

        tick()
        val quiet = summaries(cell.ref).last()

        // half a window before the next publish: the change is announced with
        // the age it actually has at publish time, not the age it had when it
        // settled (which windowing would otherwise report a window late)
        now.addAndGet(Observations.WINDOW_MS / 2)
        add(cell, "grace")
        now.addAndGet(Observations.WINDOW_MS / 2)
        observations.sample()
        val changed = summaries(cell.ref).last()

        changed.reading.staleMs shouldBe Observations.WINDOW_MS / 2
        (changed.reading.staleMs < quiet.reading.staleMs) shouldBe true
        changed.changes shouldBe 1L
        quiet.changes shouldBe 0L
    }

    @Test
    fun `two cells observed concurrently each get their own window`() {
        val busy = set()
        val idle = set()
        observations.start(busy.ref) shouldBe true
        observations.start(idle.ref) shouldBe true
        tick()

        add(busy, "ada")
        add(busy, "grace")
        val seen = summaries(busy.ref).size to summaries(idle.ref).size
        tick()

        // neither window starves the other, and neither is coalesced into it
        summaries(busy.ref).size shouldBe seen.first + 1
        summaries(idle.ref).size shouldBe seen.second + 1
        summaries(busy.ref).last().changes shouldBe 2L
        summaries(idle.ref).last().changes shouldBe 0L
        sizeOf(summaries(idle.ref).last()) shouldBe 0
    }

    // ---------------------------------------------------------- P6 and release

    @Test
    fun `a cell nobody observed is never summarized`() {
        val unobserved = set()
        val observed = set()
        observations.start(observed.ref) shouldBe true

        add(observed, "ada")
        unobserved.inlet.call.add("noise")
        repeat(3) { tick() }

        // no polling, no synthesis: a window exists only where a client
        // explicitly asked for one (P6)
        summaries(unobserved.ref) shouldBe emptyList()
        summaries(observed.ref).isNotEmpty() shouldBe true
    }

    @Test
    fun `releasing publishes exactly one trailing window, then silence`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        add(cell, "ada")
        tick()
        val before = summaries(cell.ref).size

        observations.stop(cell.ref) shouldBe true

        summaries(cell.ref).size shouldBe before + 1
        summaries(cell.ref).last().changes shouldBe 0L
        sizeOf(summaries(cell.ref).last()) shouldBe 1

        // and then nothing at all — not one trailing frame short of silence,
        // and not one past it either
        repeat(3) { tick() }
        summaries(cell.ref).size shouldBe before + 1
    }

    @Test
    fun `the idle sweep publishes exactly one trailing window`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        tick()
        val before = summaries(cell.ref).size

        now.addAndGet(Observations.IDLE_RELEASE_MS)
        observations.sweep()

        observations.openRefs shouldBe emptySet()
        summaries(cell.ref).size shouldBe before + 1
        repeat(3) { tick() }
        summaries(cell.ref).size shouldBe before + 1
    }

    @Test
    fun `close publishes one trailing window per open observation, then falls silent`() {
        val first = set()
        val second = set()
        observations.start(first.ref) shouldBe true
        observations.start(second.ref) shouldBe true
        tick()
        val before = summaries(first.ref).size to summaries(second.ref).size

        observations.close()

        summaries(first.ref).size shouldBe before.first + 1
        summaries(second.ref).size shouldBe before.second + 1
        // a closed inspector is silent
        repeat(3) { tick() }
        summaries(first.ref).size shouldBe before.first + 1
        summaries(second.ref).size shouldBe before.second + 1
    }

    @Test
    fun `re-observing an open cell renews it without interrupting its window`() {
        val cell = set()
        observations.start(cell.ref) shouldBe true
        tick()
        val before = summaries(cell.ref).size

        // a second POST renews the idle deadline; the observation — and its
        // window — is the same one, so nothing trailing is owed. (The same
        // holds for the losing side of a genuinely concurrent POST, which is
        // released with its trailing window suppressed: the cell it names is
        // still observed by the winner, so announcing a release would be a
        // lie.)
        observations.start(cell.ref) shouldBe true

        summaries(cell.ref).size shouldBe before
        tick()
        summaries(cell.ref).size shouldBe before + 1
    }
}
