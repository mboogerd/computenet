package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.ReBaselineNotice
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V3-BE part 1 — [WaveHealth]'s decision table, driven directly.
 *
 * Every input this class has is a lambda ([WaveHealth]'s constructor), so the
 * whole heuristic is exercised here without a graph, without a scheduler and
 * without a clock that moves on its own: the tap readings, the observed set,
 * the frontier stamps, the coldness verdict and the clock are all set by the
 * test between [WaveHealth.evaluate] calls. That is deliberate — the guards are
 * a *decision table* over (wave, frontier, epoch, liveness, heat, lifetime),
 * and covering it through a real topology would mean either sleeping through
 * five-second grace periods or asserting on scheduler timing, both of which the
 * ticket forbids. `InspectorWaveHealthTest` covers the same conditions once
 * each end to end, through real cells and the real HTTP/SSE surface.
 *
 * Timings are always expressed against the named constants rather than against
 * literals, so a threshold change moves these tests with it instead of breaking
 * them into a re-tuning exercise.
 */
class WaveHealthTest {

    private val sourceA: UUID = UUID.randomUUID()
    private val sourceB: UUID = UUID.randomUUID()

    private val producerCell = CellRef(UUID.randomUUID())
    private val producer = PortRef.of(producerCell, "outlet")
    private val consumer = CellRef(UUID.randomUUID())
    private val edge: UUID = UUID.randomUUID()

    /** Everything the evaluator reads, mutable between passes. */
    private var readings: List<TapReading> = emptyList()
    private var observed: Set<CellRef> = setOf(consumer)
    private var frontiers: Map<CellRef, Timestamp?> = emptyMap()
    private var cold: Set<CellRef> = emptySet()
    private var now: Long = 1_000L

    private val emitted = CopyOnWriteArrayList<WaveHealthRow>()

    private fun health(maxOpen: Int = WaveHealth.WAVE_HEALTH_MAX_OPEN) = WaveHealth(
        sites = { readings },
        observed = { observed },
        frontierOf = { ref -> frontiers[ref] },
        isCold = { ref -> ref in cold },
        onRow = { emitted += it },
        maxOpen = maxOpen,
        clock = { now },
    )

    // ------------------------------------------------------------ scaffolding

    /** One tapped outlet feeding [edge] -> [consumer], last live wave [wave]. */
    private fun site(wave: Timestamp?, target: CellRef = consumer, id: UUID = edge) =
        TapReading(producer = producer, edges = listOf(id to target), lastWave = wave)

    private fun wave(counter: Long, source: UUID = sourceA) = Timestamp(source, counter)

    private fun frontier(counter: Long?, source: UUID = sourceA) {
        frontiers = mapOf(consumer to counter?.let { Timestamp(source, it) })
    }

    private val rowId get() = "${WaveHealthRow.FRONTIER_LAG}:$edge:${InspectorServer.encodeRef(consumer)}"
    private val stallId get() = "${WaveHealthRow.STALLED_WAVE}:$edge:${InspectorServer.encodeRef(consumer)}"

    private fun opened() = emitted.filter { it.state == WaveHealthRow.OPEN }
    private fun cleared() = emitted.filter { it.state == WaveHealthRow.CLEARED }

    /**
     * Drive a lag past [WaveHealth.LAG_THRESHOLD_WAVES] to a matured, open row:
     * one pass that starts the run, then a second past the grace period with the
     * producer's wave moved on (the liveness gate, guard 7's second half).
     */
    private fun openLagRow(subject: WaveHealth) {
        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES)))
        now += WaveHealth.LAG_GRACE_MS
        subject.evaluate()
        subject.openRows().size shouldBe 1
    }

    // -------------------------------------------------------- (a) frontierLag

    @Test
    fun `a same-source lag past the threshold opens exactly one row, after the grace period`() {
        val subject = health()

        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()
        // the run has only just started: the grace period is what makes
        // "continuously" mean something, so nothing opens on first sight
        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()

        // one millisecond short of the grace period is still short of it
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES)))
        now += WaveHealth.LAG_GRACE_MS - 1
        subject.evaluate()
        subject.openRows().shouldBeEmpty()

        readings = listOf(site(wave(3 + WaveHealth.LAG_THRESHOLD_WAVES)))
        now += 1
        subject.evaluate()

        val row = subject.openRows().single()
        row.id shouldBe rowId
        row.kind shouldBe WaveHealthRow.FRONTIER_LAG
        row.state shouldBe WaveHealthRow.OPEN
        row.ref shouldBe InspectorServer.encodeRef(consumer)
        row.edge shouldBe edge.toString()
        row.heuristic shouldBe true
        row.description.startsWith("heuristic") shouldBe true
        row.wave.shouldNotBeNull().counter shouldBe 3 + WaveHealth.LAG_THRESHOLD_WAVES
        row.frontier.shouldNotBeNull().counter shouldBe 1L
        row.lagWaves shouldBe 2 + WaveHealth.LAG_THRESHOLD_WAVES
        row.heldMs shouldBe WaveHealth.LAG_GRACE_MS
        emitted.map { it.id to it.state } shouldContainExactly listOf(rowId to WaveHealthRow.OPEN)
    }

    @Test
    fun `the row clears the moment the frontier catches up`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        frontier(2 + WaveHealth.LAG_THRESHOLD_WAVES)
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        val clear = emitted.single()
        clear.id shouldBe rowId
        clear.state shouldBe WaveHealthRow.CLEARED
        // the cleared row still carries what resolved, so a client can render
        // *what* went away rather than only that something did
        clear.kind shouldBe WaveHealthRow.FRONTIER_LAG
        clear.atMs shouldBe now
    }

    /**
     * The guard-4/5 shape as it actually occurs: a downstream that keeps up but
     * stays a bounded number of waves behind — a filter whose longest
     * *consecutive* drop run is short, or a fold that absorbs a few deltas
     * between effective ones. It never opens a row, for as long as it runs.
     *
     * The frontier ratchets forward each pass, which is also what keeps (b)
     * quiet: every pinned wave lands before [WaveHealth.STALL_WINDOW_MS].
     */
    @Test
    fun `a bounded lag that keeps up never opens a row, however long it runs`() {
        val subject = health()
        val behind = WaveHealth.LAG_THRESHOLD_WAVES - 1

        var landed = 1L
        repeat(40) {
            frontier(landed)
            readings = listOf(site(wave(landed + behind)))
            subject.evaluate()
            landed += behind
            now += WaveHealth.LAG_GRACE_MS
        }

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /**
     * "Continuously" means what it says: a run that dips under the threshold
     * serves the whole grace period again rather than resuming where it left
     * off. Total elapsed here stays under [WaveHealth.STALL_WINDOW_MS] so this
     * asserts on (a) alone.
     */
    @Test
    fun `a run that dips under the threshold starts its grace period over`() {
        val subject = health()
        val third = WaveHealth.LAG_GRACE_MS / 3

        // t0 — the run starts
        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()

        // t0 + 1/3 grace — the frontier closes the gap: the run is over
        now += third
        frontier(2 + WaveHealth.LAG_THRESHOLD_WAVES)
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()

        // t0 + 2/3 grace — it opens up again, so a *fresh* run starts here
        now += third
        frontier(2 + WaveHealth.LAG_THRESHOLD_WAVES)
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES * 2)))
        subject.evaluate()

        // t0 + grace + 1/3: past the grace period counted from t0, and short of
        // it counted from the restart — which is the one that governs
        now += WaveHealth.LAG_GRACE_MS - third
        readings = listOf(site(wave(3 + WaveHealth.LAG_THRESHOLD_WAVES * 2)))
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    // ------------------------------------------------------- (b) stalledWave

    @Test
    fun `a pinned wave the frontier never reaches opens a stalledWave row, and clears when it lands`() {
        val subject = health()

        // a lag of one: far under LAG_THRESHOLD_WAVES, so only (b) can fire
        frontier(1)
        readings = listOf(site(wave(2)))
        subject.evaluate()
        subject.openRows().shouldBeEmpty()

        readings = listOf(site(wave(3)))
        now += WaveHealth.STALL_WINDOW_MS
        subject.evaluate()

        val row = subject.openRows().single()
        row.id shouldBe stallId
        row.kind shouldBe WaveHealthRow.STALLED_WAVE
        row.heuristic shouldBe true
        row.description.startsWith("heuristic") shouldBe true
        // the *pinned* wave, not the site's latest: the row is about the wave
        // that never landed, and re-taking it every pass would never mature
        row.wave.shouldNotBeNull().counter shouldBe 2L
        row.frontier.shouldNotBeNull().counter shouldBe 1L
        row.lagWaves shouldBe 1L
        row.heldMs shouldBe WaveHealth.STALL_WINDOW_MS

        emitted.clear()
        frontier(3)
        readings = listOf(site(wave(3)))
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.single().let {
            it.id shouldBe stallId
            it.state shouldBe WaveHealthRow.CLEARED
        }
    }

    // --------------------------------------------- false-positive guards 1-7

    /** Guard 7 (liveness half): nothing anywhere moved, so the graph is idle, not stuck. */
    @Test
    fun `a quiet graph never opens a row however long the lag holds`() {
        val subject = health()

        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()

        // hours of wall clock, and not one new wave anywhere
        repeat(20) {
            now += WaveHealth.LAG_GRACE_MS * 10
            subject.evaluate()
        }

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /** Guard 2: a freshly opened observation reports a null frontier by design. */
    @Test
    fun `a null frontier is never a subject`() {
        val subject = health()

        frontier(null)
        readings = listOf(site(wave(1_000)))
        subject.evaluate()
        readings = listOf(site(wave(2_000)))
        now += WaveHealth.STALL_WINDOW_MS * 4
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /** Guards 1 and 7: two `sourceId`s are incomparable — there is no ordering between them. */
    @Test
    fun `two different sources are never compared, however far apart their counters are`() {
        val subject = health()

        frontier(1, source = sourceB)
        readings = listOf(site(wave(10_000, source = sourceA)))
        subject.evaluate()
        readings = listOf(site(wave(20_000, source = sourceA)))
        now += WaveHealth.STALL_WINDOW_MS * 4
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /**
     * Guard 1, the whole point: a supervision restart mints a fresh per-outlet
     * `sourceId`, so every comparison against the dead epoch is meaningless.
     * The open row goes, and the fresh epoch — which trivially "leads" a
     * frontier still stamped with the dead one — starts nothing.
     */
    @Test
    fun `a fresh source epoch clears the open row and opens no new one`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        // the restart: same outlet, same edge, brand-new source at counter 1
        readings = listOf(site(wave(1, source = sourceB)))
        now += WaveHealth.LAG_GRACE_MS
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.single().let {
            it.id shouldBe rowId
            it.state shouldBe WaveHealthRow.CLEARED
        }

        // and it stays clear: nothing about the new epoch is comparable to a
        // frontier stamped with the old one, however long that lasts
        repeat(10) {
            readings = listOf(site(wave(it + 2L, source = sourceB)))
            now += WaveHealth.STALL_WINDOW_MS
            subject.evaluate()
        }
        subject.openRows().shouldBeEmpty()
        cleared().size shouldBe 1
        opened().shouldBeEmpty()
    }

    /** Guard 6: a suspended or drained cell is intentionally not propagating. */
    @Test
    fun `a cold consumer opens no row, and an open row clears when the cell goes cold`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        cold = setOf(consumer)
        now += WaveHealth.LAG_GRACE_MS
        readings = listOf(site(wave(9 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.single().state shouldBe WaveHealthRow.CLEARED

        // and while it stays cold, nothing re-opens
        emitted.clear()
        repeat(5) {
            now += WaveHealth.LAG_GRACE_MS
            readings = listOf(site(wave(20 + it + WaveHealth.LAG_THRESHOLD_WAVES)))
            subject.evaluate()
        }
        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /** Guard 6, producer half: a cold producer's frozen last wave is not evidence. */
    @Test
    fun `a cold producer is not evidence of anything downstream`() {
        val subject = health()
        cold = setOf(producerCell)

        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES)))
        now += WaveHealth.LAG_GRACE_MS * 4
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.shouldBeEmpty()
    }

    /** P6: a cell nobody asked to observe is never read and never a subject. */
    @Test
    fun `an unobserved cell is never a subject, and releasing an observation clears its row`() {
        val subject = health()

        observed = emptySet()
        frontier(1)
        readings = listOf(site(wave(1 + WaveHealth.LAG_THRESHOLD_WAVES)))
        subject.evaluate()
        readings = listOf(site(wave(2 + WaveHealth.LAG_THRESHOLD_WAVES)))
        now += WaveHealth.LAG_GRACE_MS * 4
        subject.evaluate()
        subject.openRows().shouldBeEmpty()

        observed = setOf(consumer)
        openLagRow(subject)
        emitted.clear()

        observed = emptySet()
        subject.evaluate()
        subject.openRows().shouldBeEmpty()
        emitted.single().state shouldBe WaveHealthRow.CLEARED
    }

    // ------------------------------------------------------------- clearing

    @Test
    fun `unbinding the tapped edge clears its row`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        readings = emptyList()
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.single().state shouldBe WaveHealthRow.CLEARED
    }

    @Test
    fun `an edge re-bound to a different consumer clears the row it opened for the old one`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        readings = listOf(site(wave(9 + WaveHealth.LAG_THRESHOLD_WAVES), target = CellRef(UUID.randomUUID())))
        subject.evaluate()

        subject.openRows().shouldBeEmpty()
        emitted.single().state shouldBe WaveHealthRow.CLEARED
    }

    /**
     * The TTL backstop, on the one enumerated path that reaches it: the site is
     * still bound and the cell still observed, but the last emission observed
     * there was a baseline, so the site has no readable wave position and the
     * condition can be neither re-confirmed nor re-derived.
     */
    @Test
    fun `a row nothing can re-confirm clears itself after the TTL`() {
        val subject = health()
        openLagRow(subject)
        emitted.clear()

        readings = listOf(site(wave = null))
        now += WaveHealth.ROW_TTL_MS - 1
        subject.evaluate()
        subject.openRows().size shouldBe 1
        emitted.shouldBeEmpty()

        now += 1
        subject.evaluate()
        subject.openRows().shouldBeEmpty()
        emitted.single().state shouldBe WaveHealthRow.CLEARED
    }

    @Test
    fun `closing forgets every open row`() {
        val subject = health()
        openLagRow(subject)

        subject.close()

        subject.openRows().shouldBeEmpty()
    }

    // ------------------------------------------------------------- bounding

    @Test
    fun `the open set is capped, and a capped-out eviction emits that row's cleared event`() {
        val subject = health(maxOpen = 2)
        val consumers = List(3) { CellRef(UUID.randomUUID()) }
        val edges = List(3) { UUID.randomUUID() }
        observed = consumers.toSet()
        frontiers = consumers.associateWith { Timestamp(sourceA, 1) }

        fun sites(counter: Long) = consumers.mapIndexed { index, target ->
            TapReading(
                producer = PortRef.of(CellRef(UUID.randomUUID()), "outlet"),
                edges = listOf(edges[index] to target),
                lastWave = Timestamp(sourceA, counter),
            )
        }

        readings = sites(1 + WaveHealth.LAG_THRESHOLD_WAVES)
        subject.evaluate()
        readings = sites(2 + WaveHealth.LAG_THRESHOLD_WAVES)
        now += WaveHealth.LAG_GRACE_MS
        subject.evaluate()

        // three conditions matured, two rows survive
        subject.openRows().size shouldBe 2
        opened().size shouldBe 3
        // the evicted one is the oldest, and the client is told it is gone
        val evicted = cleared().single()
        evicted.id shouldBe "${WaveHealthRow.FRONTIER_LAG}:${edges[0]}:${InspectorServer.encodeRef(consumers[0])}"
        subject.openRows().none { it.id == evicted.id } shouldBe true
    }

    // ---------------------------------------------------- guard 3, at source

    /**
     * Guard 3, where it is actually enforced — [FlowCollector.liveWaveOf], the
     * one function that turns a tap's last observed context into a site's wave
     * position. A baseline is deliberately not a wave position, and the first
     * wave of a re-baseline's brand-new epoch is the worst possible reference
     * point for a comparison against a frontier stamped with the dead one.
     */
    @Test
    fun `a baseline or re-baseline emission is never taken as a site's wave position`() {
        val port = PortRef.of(producerCell, "outlet")
        val stamp = Timestamp(sourceA, 7)

        FlowCollector.liveWaveOf(MessageContext(stamp, port)) shouldBe stamp
        FlowCollector.liveWaveOf(null) shouldBe null
        FlowCollector.liveWaveOf(
            MessageContext(stamp, port, baseline = TagFrontier(mapOf(sourceA to 7L))),
        ) shouldBe null
        FlowCollector.liveWaveOf(
            MessageContext(stamp, port, reBaseline = ReBaselineNotice(setOf(sourceB), supersede = true)),
        ) shouldBe null
    }
}
