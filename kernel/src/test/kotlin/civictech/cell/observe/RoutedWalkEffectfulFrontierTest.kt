package civictech.cell.observe

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Cursor
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.durability.InMemoryJournal
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionAccounting
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * computenet-t6b.3.3.2 — BS-11 / `[KRD-07]`: a routed walk ([walkRouted]) over a
 * cell that is both [Effectful] and [BoundedStateful] never advances that cell's
 * processed-frontier, and the composition survives a crash: recovery from the
 * journal behaves exactly as a walk-free control run's does (`[24-DUR-05]`).
 *
 * The processed-frontier is private to `HostDurability`; per the feature's
 * D-BS11-observables it is observed only through its public consequences:
 *
 * - the journal record count (`InMemoryJournal.replay().size`) — a frontier
 *   advance is a `FrontierRecord` appended to the host's journal, so a read
 *   that advanced it would grow the journal;
 * - [ManagedHost.supervisionAccounting] — suppressions, contextless refusals,
 *   dead letters;
 * - the external effect target [world], outside any cell instance (the
 *   `EffectfulRecoveryTest` precedent), so a true re-fire is catchable;
 * - equality with a walk-free control run built in the same test.
 *
 * Crash/recovery follows `kernel/src/test/kotlin/civictech/cell/durability/EffectfulRecoveryTest.kt`
 * (feature decision D-BS11-crash): the [InMemoryJournal] is the disk; the host
 * is discarded; a new host is built over the same journal and registry; the
 * sink is re-spawned under the same logical [CellRef]; `recoverFrom` replays.
 *
 * **Two recovery shapes, because one cannot carry both halves.** `[24-DUR-05]`
 * drops a suppressed invocation *whole*: during `recoverFrom` replay the sink
 * does not run, so an `Effectful` cell's own state is NOT rebuilt from journaled
 * frames it already acted on — only a checkpoint's `snapshot()` carries it
 * across a crash (observed while writing this test: with no checkpoint the
 * recovered cell walks as `[N+1]`, not `1..N+1`). That loss is a kernel/spec
 * gap between `[24-DUR-02]` and `[24-DUR-05]`, not something a walk causes, and
 * is filed as computenet-rhhry; the frame-replay shape below therefore compares
 * its second walk with the walk-free control and does NOT claim state survives.
 *
 * - The checkpointed shape carries the acceptance clause "a second walk SHALL
 *   yield 1..N+1". Its checkpoint is taken *before* the walk, so anything the
 *   walk could have made durable (a `FrontierRecord`, a frame) lands in the
 *   journal tail that recovery replays and would change the post-recovery
 *   delivery of N+1. It leaves no already-acted frame in the journal to
 *   suppress on replay.
 * - The frame-replay shape keeps all N acted-on frames and their frontier
 *   records in the journal while the walk runs, so recovery suppresses all N
 *   on replay — the replay half of `[24-DUR-05]` — and that suppression count
 *   must equal the walk-free control's. Its second walk sees only what the
 *   kernel restores there, compared against the control rather than asserted
 *   as 1..N+1.
 */
class RoutedWalkEffectfulFrontierTest {

    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        fun emit(n: Int) = outlet.call.provide(n)
    }

    /**
     * Test-local `Effectful` + `BoundedStateful` fixture (none exists on main):
     * each `provide(n)` acts on the external [world] AND appends to the cell's
     * own ordered [state]; [readBounded] pages over [state] from an `Int`
     * offset cursor (the `PagingCountingCell` shape in `StateWalkTest`).
     */
    class EffectfulLedgerCell(override val ref: CellRef, private val world: MutableList<Int>) :
        Cell, Effectful, BoundedStateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        private var state = ArrayList<Int>()
        val reads = AtomicInteger()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    world += input
                    state += input
                }
            })
        }

        override fun readBounded(request: StateRead): StatePage {
            reads.incrementAndGet()
            val start = request.cursor?.token as? Int ?: 0
            val end = minOf(start + request.limit, state.size)
            val entries = state.subList(start, end).map { it as Serializable }
            return StatePage(entries = entries, next = if (end < state.size) Cursor(end) else null)
        }

        override fun snapshot(): Serializable = ArrayList(state)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            this.state = ArrayList(state as ArrayList<Int>)
        }
    }

    interface LedgerProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** What one run of the scenario leaves behind, compared between the walked run and its control. */
    private data class RunResult(
        val worldAfterRecovery: List<Int>,
        val accountingAfterRecovery: SupervisionAccounting,
        val worldAfterLiveDelivery: List<Int>,
        val accountingAfterLiveDelivery: SupervisionAccounting,
        val secondWalkEntries: List<Serializable>,
    )

    /**
     * One full scenario on its own controller, registry, journal and world.
     * With [walk] true the pre-crash walk is driven step by step and every
     * observable is re-checked after each step; with [walk] false that call is
     * simply absent — the control. [checkpoint] picks the recovery shape (class KDoc).
     */
    private fun runScenario(walk: Boolean, checkpoint: Boolean): RunResult {
        val controller = SimulationController(seed = 7)
        val registry = LocationRegistry()
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val world = mutableListOf<Int>() // the external effect target, outside any cell instance
        val sinkRef = CellRef(UUID.randomUUID())

        var host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)
        var sink = EffectfulLedgerCell(sinkRef, world)
        host.managementInlet.call.spawn(sink)
        val source = SourceCell()

        fun routeToSink(targetHost: ManagedHost): Consumer<Int> =
            (HostedCellProxy.create(sinkRef, targetHost, LedgerProxy::class.java) as LedgerProxy).inlet.call

        var currentLink: PortRef? = null
        fun rewire(targetHost: ManagedHost) {
            currentLink?.let { source.outlet.unsubscribe(it) }
            val ref = PortRef.generate()
            source.outlet.subscribe(Use.fixed(routeToSink(targetHost), ref))
            currentLink = ref
        }

        rewire(host)
        controller.runToIdle()

        // pre-crash traffic: journaled, acted on the world, frontier advanced
        (1..N).forEach(source::emit)
        controller.runToIdle()
        world shouldBe (1..N).toList()
        // routed through stamped frames, so the frontier path is the one under test —
        // not the [24-DUR-06] contextless refusal masquerading as a working fixture
        host.supervisionAccounting().effectfulContextlessRefusals shouldBe 0L
        host.supervisionAccounting().deadLetters shouldBe 0L

        // carries the cell's state (1..N) and frontier across the crash; see class KDoc
        if (checkpoint) host.checkpoint(journal)

        if (walk) {
            val journalBefore = journal.replay().size
            val accountingBefore = host.supervisionAccounting()
            val worldBefore = world.toList()

            fun assertUntouched() {
                journal.replay().size shouldBe journalBefore
                host.supervisionAccounting() shouldBe accountingBefore
                world shouldBe worldBefore
            }

            val handle = walkRouted(registry, sinkRef, StateRead(limit = PAGE))
            assertUntouched()
            var steps = 0
            while (!handle.outcome.isDone) {
                check(steps++ < STEP_GUARD) { "walk did not complete within $STEP_GUARD steps" }
                controller.step().shouldBeTrue()
                assertUntouched()
            }
            val outcome = handle.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            outcome.termination shouldBe StateWalkOutcome.Termination.Completed
            outcome.entries shouldBe (1..N).toList()
            outcome.pages shouldBe (N + PAGE - 1) / PAGE
            sink.reads.get() shouldBe outcome.pages
            // nothing left queued behind the walk that could still act
            controller.runToIdle() shouldBe 0
            assertUntouched()
        }

        // CRASH: host and the live sink instance vanish — only the journal survives
        host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)
        sink = EffectfulLedgerCell(sinkRef, world)
        host.managementInlet.call.spawn(sink)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()

        val worldAfterRecovery = world.toList()
        val accountingAfterRecovery = host.supervisionAccounting()

        // one further real delivery after recovery
        rewire(host)
        source.emit(N + 1)
        controller.runToIdle()
        val worldAfterLive = world.toList()
        val accountingAfterLive = host.supervisionAccounting()

        val second = walkRouted(registry, sinkRef, StateRead(limit = PAGE))
        controller.runToIdle()
        val secondOutcome = second.outcome.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        secondOutcome.termination shouldBe StateWalkOutcome.Termination.Completed

        return RunResult(
            worldAfterRecovery = worldAfterRecovery,
            accountingAfterRecovery = accountingAfterRecovery,
            worldAfterLiveDelivery = worldAfterLive,
            accountingAfterLiveDelivery = accountingAfterLive,
            secondWalkEntries = secondOutcome.entries,
        )
    }

    /**
     * Given an EffectfulLedgerCell that has acted on 1..N; When it is walked at
     * limit 3 and the host is then crashed and recovered from the journal; Then
     * the journal, the accounting and the world are unchanged after every walk
     * step, the world is still 1..N after recovery, the post-recovery
     * accounting and world equal the walk-free control's, and N+1 emitted
     * afterwards lands exactly once and is seen by a second walk.
     */
    @Test
    fun `a routed walk leaves the Effectful frontier untouched and recovery matches a walk-free control`() {
        N shouldBeGreaterThan 19 // the bead's N >= 20, so the walk is genuinely multi-page

        val walked = runScenario(walk = true, checkpoint = true)
        val control = runScenario(walk = false, checkpoint = true)

        // [24-DUR-05]: recovery neither re-fired nor lost anything
        walked.worldAfterRecovery shouldBe (1..N).toList()
        walked.accountingAfterRecovery.effectfulContextlessRefusals shouldBe 0L

        // "recovery behaves exactly as it would have without it"
        walked.worldAfterRecovery shouldBe control.worldAfterRecovery
        walked.accountingAfterRecovery shouldBe control.accountingAfterRecovery

        // the walk neither suppressed the later real delivery nor doubled it
        walked.worldAfterLiveDelivery shouldBe (1..N + 1).toList()
        walked.accountingAfterLiveDelivery.effectfulSuppressionsDischarged shouldBe
            walked.accountingAfterRecovery.effectfulSuppressionsDischarged
        walked.accountingAfterLiveDelivery.effectfulContextlessRefusals shouldBe 0L
        walked.accountingAfterLiveDelivery shouldBe control.accountingAfterLiveDelivery
        walked.worldAfterLiveDelivery shouldBe control.worldAfterLiveDelivery

        // the read after recovery still sees the recovered state plus the live delivery
        walked.secondWalkEntries shouldBe (1..N + 1).toList()
        walked.secondWalkEntries shouldBe control.secondWalkEntries
    }

    /**
     * The replay half of `[24-DUR-05]`: the walk runs over a journal still
     * holding all N acted-on frames and their frontier advances; recovery
     * replays and suppresses every one of them, exactly as the walk-free
     * control's does. A walk that had advanced the frontier would append to the
     * journal during the walk (caught per step); one that had perturbed it any
     * other way would change the suppression count or re-fire into [world].
     */
    @Test
    fun `across a frame-replay recovery the walked run suppresses exactly what the walk-free control suppresses`() {
        val walked = runScenario(walk = true, checkpoint = false)
        val control = runScenario(walk = false, checkpoint = false)

        // every pre-crash frame was replayed and suppressed, none re-fired
        walked.worldAfterRecovery shouldBe (1..N).toList()
        walked.accountingAfterRecovery.effectfulSuppressionsDischarged shouldBe N.toLong()
        walked.accountingAfterRecovery.effectfulContextlessRefusals shouldBe 0L
        walked.accountingAfterRecovery shouldBe control.accountingAfterRecovery
        walked.worldAfterRecovery shouldBe control.worldAfterRecovery

        // N+1 is ahead of the restored frontier: it lands once, nothing further suppressed
        walked.worldAfterLiveDelivery shouldBe (1..N + 1).toList()
        walked.accountingAfterLiveDelivery shouldBe walked.accountingAfterRecovery
        walked.accountingAfterLiveDelivery shouldBe control.accountingAfterLiveDelivery
        walked.worldAfterLiveDelivery shouldBe control.worldAfterLiveDelivery

        // no checkpoint carried state, so both see only what replay could restore
        // (today [N+1], not 1..N+1 — computenet-rhhry; deliberately not pinned here)
        walked.secondWalkEntries shouldBe control.secondWalkEntries
    }

    private companion object {
        const val N = 20
        const val PAGE = 3
        const val STEP_GUARD = 10_000
        const val TIMEOUT_MS = 30_000L
    }
}
