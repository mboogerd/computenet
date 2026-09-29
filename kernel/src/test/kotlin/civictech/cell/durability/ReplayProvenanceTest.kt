package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.ReplayProvenance
import civictech.cell.SuspendingCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.CoroutineScheduler
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeBound
import civictech.cell.host.IntakeSaturatedException
import civictech.cell.host.JournalRecords
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.ManagedHost
import civictech.cell.host.SaturationPolicy
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * computenet-xy7w4.1 (feature computenet-xy7w4 rules R-A, R-B, design xy7w4-D1/D2): replay
 * identity is carried **per frame** and names the journal being replayed, replacing the
 * host-wide, time-windowed `recovering` flag that caused two bugs:
 *
 * - computenet-00om8 — a LIVE frame accepted from another thread while `recoverFrom`'s
 *   synchronous loop ran was not journaled (and bypassed the saturation gate).
 * - computenet-vcrc7 — a replayed frame is DELIVERED by a later scheduler task, after the
 *   flag had reset, so the delivery's same-host re-emissions were appended again: every
 *   restart re-journaled the whole derived history.
 *
 * The one append rule under test (D2): a frame is appended to `journalSelector(target)`
 * unless its provenance is "replay of J" and `journalSelector(target) === J`.
 *
 * Frames are counted by decoding the journal through [JournalRecords.decode] and counting
 * [DecodedJournalRecord.Frame] — frontier / outlet-wave / baseline records are not frames.
 */
class ReplayProvenanceTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val deltaInlet: Use<Propagate<SetDelta<String>>>
    }

    private fun ops(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun deltas(host: ManagedHost, ref: CellRef): Propagate<SetDelta<String>> =
        (HostedCellProxy.create(ref, host, DeltaInletProxy::class.java) as DeltaInletProxy).deltaInlet.call

    private fun frameCount(journal: Journal): Int =
        journal.replay().count { JournalRecords.decode(it) is DecodedJournalRecord.Frame }

    /**
     * A [Journal] whose [replay] can be made to block on a latch (R-A: hold `recoverFrom`
     * inside its synchronous replay loop while a second thread writes) or run a hook on the
     * replaying thread (the saturation variant: a live write that lands *inside* the loop,
     * deterministically, under a [SimulationController]). Everything else delegates, so the
     * host's selector returns THIS instance and `===` against the replay provenance holds.
     */
    private class HoldingJournal(val inner: InMemoryJournal = InMemoryJournal()) : Journal {
        override val durability: DurabilityClass get() = inner.durability

        @Volatile
        var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)

        @Volatile
        var onReplay: (() -> Unit)? = null

        override fun append(record: ByteArray) = inner.append(record)

        override fun replay(): List<ByteArray> {
            gate?.let { held ->
                gate = null
                entered.countDown()
                held.await(30, TimeUnit.SECONDS)
            }
            onReplay?.let { hook ->
                onReplay = null
                hook()
            }
            return inner.replay()
        }

        override fun reset(records: List<ByteArray>) = inner.reset(records)
    }

    /**
     * Example R-A (rule R-A, fixes computenet-00om8): a live `add` accepted from another
     * thread while `recoverFrom` is blocked inside its replay loop is journaled exactly as
     * outside recovery. Before the fix the host-wide flag was `true` for that whole window,
     * so the live frame was accepted, delivered — and never written: J held 3, and a crash
     * after it would silently lose "live".
     */
    @Test
    fun `R-A a live write accepted during recoverFrom is journaled`() {
        val journal = HoldingJournal()
        val aRef = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = { if (it == aRef) journal else null }

        val liveScheduler = VirtualThreadScheduler("xy7w4.1-RA-live")
        val live = ManagedHost(scheduler = liveScheduler, journalFor = selector)
        live.managementInlet.call.spawn(SetCell<String>(aRef))
        live.quiescence().await(30_000, "spawn")
        listOf("a", "b", "c").forEach { ops(live, aRef).add(it) }
        live.quiescence().await(30_000, "live writes")
        liveScheduler.shutdown()
        frameCount(journal.inner) shouldBe 3

        // CRASH: rebuild, then recover on T1 while T2 (this thread) writes live
        val scheduler = VirtualThreadScheduler("xy7w4.1-RA-recovered")
        val host = ManagedHost(scheduler = scheduler, journalFor = selector)
        val recovered = SetCell<String>(aRef)
        host.managementInlet.call.spawn(recovered)
        host.quiescence().await(30_000, "respawn")

        val release = CountDownLatch(1)
        journal.gate = release
        var recovery: civictech.cell.host.Recovery? = null
        val t1 = Thread { recovery = host.recoverFrom(journal) }.also { it.start() }
        journal.entered.await(30, TimeUnit.SECONDS).shouldBeTrue()

        ops(host, aRef).add("live") // T2, while T1 is inside recoverFrom

        release.countDown()
        t1.join(30_000)
        checkNotNull(recovery) { "recoverFrom did not return" }.awaitApplied(30_000)

        frameCount(journal.inner) shouldBe 4
        recovered.membership() shouldBe setOf("a", "b", "c", "live")
        scheduler.shutdown()
    }

    /**
     * Rule R-A, saturation half: a live frame accepted during `recoverFrom`'s loop is subject
     * to the intake's saturation gate exactly as outside recovery. The live write is issued
     * from inside the replay loop (the journal's `replay()` hook) against an intake already
     * SATURATED by two staged frames: it is refused, while the replayed frames that follow it
     * still pass (a replayed frame carries replay provenance and is never re-rejected, T05
     * finding 4). Before the fix the time-window flag waved the live frame through.
     */
    @Test
    fun `R-A a live write during recoverFrom meets the saturation gate as outside recovery`() {
        val controller = SimulationController(seed = 11)
        val journal = HoldingJournal()
        val aRef = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = { if (it == aRef) journal else null }
        val bound = IntakeBound(highWater = 2, lowWater = 0, policy = SaturationPolicy.Park)

        val live = ManagedHost(scheduler = controller.scheduler(), journalFor = selector, intakeBound = bound)
        live.managementInlet.call.spawn(SetCell<String>(aRef))
        controller.runToIdle()
        listOf("a", "b", "c").forEach {
            ops(live, aRef).add(it)
            controller.runToIdle()
        }
        frameCount(journal.inner) shouldBe 3

        val host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector, intakeBound = bound)
        val recovered = SetCell<String>(aRef)
        host.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        ops(host, aRef).add("d")
        ops(host, aRef).add("e") // two staged: the intake is SATURATED now
        frameCount(journal.inner) shouldBe 5

        var refused: Throwable? = null
        journal.onReplay = {
            refused = runCatching { ops(host, aRef).add("during") }.exceptionOrNull()
        }
        val recovery = host.recoverFrom(journal)
        recovery.replayedFrames shouldBe 5 // every replayed frame passed the saturated gate
        controller.runToIdle()

        (refused is IntakeSaturatedException).shouldBeTrue()
        frameCount(journal.inner) shouldBe 5
        recovered.membership() shouldBe setOf("a", "b", "c", "d", "e")
    }

    /**
     * One incarnation of A → B on one host, both journaled. A is a [SetCell] driven through
     * its `inlet`; B is a [SetCell] fed A's deltas through its `deltaInlet`, and the link
     * routes through the host's intake (a hosted proxy as A's subscriber) — so every derived
     * delta is its own staged, journaled frame, the shape computenet-vcrc7 measured.
     */
    private class Graph(
        scheduler: HostScheduler,
        selector: (CellRef) -> Journal?,
        aRef: CellRef,
        bRef: CellRef,
        intakeBound: IntakeBound? = null,
    ) {
        val host = ManagedHost(scheduler = scheduler, journalFor = selector, intakeBound = intakeBound)
        val a = SetCell<String>(aRef)
        val b = SetCell<String>(bRef)

        init {
            host.managementInlet.call.spawn(b)
            host.managementInlet.call.spawn(a)
        }

        fun link(deltas: (ManagedHost, CellRef) -> Propagate<SetDelta<String>>) {
            a.outlet.subscribe(Use.fixed(deltas(host, b.ref), PortRef.generate()))
        }
    }

    /**
     * Example R-B (rule R-B, fixes computenet-vcrc7): replay re-journals nothing. A and B
     * share one journal J; 20 live adds leave 20 A-frames + 20 derived B-frames = 40. Three
     * successive crash/rebuild/recover cycles each leave J at 40 and B's fold at its pre-crash
     * value. Before the fix every restart appended the 20 re-derived B-frames again: 60, 80,
     * 100 — because each replayed A-frame is delivered by a later scheduler task, after the
     * host-wide flag had already reset.
     */
    @Test
    fun `R-B three successive recoveries leave the journal's frame count unchanged`() {
        val controller = SimulationController(seed = 5)
        val journal = InMemoryJournal()
        val aRef = CellRef(UUID.randomUUID())
        val bRef = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = { if (it == aRef || it == bRef) journal else null }

        val live = Graph(controller.scheduler(), selector, aRef, bRef).also { it.link(::deltas) }
        controller.runToIdle()
        (1..20).forEach { ops(live.host, aRef).add("e$it") }
        controller.runToIdle()
        val preCrashFold = live.b.membership()
        preCrashFold shouldBe (1..20).map { "e$it" }.toSet()
        frameCount(journal) shouldBe 40

        repeat(3) { restart ->
            val g = Graph(controller.scheduler(), selector, aRef, bRef).also { it.link(::deltas) }
            controller.runToIdle()
            val recovery = g.host.recoverFrom(journal)
            recovery.replayedFrames shouldBe 40
            controller.runToIdle()
            recovery.isApplied.shouldBeTrue()

            withClue("restart ${restart + 1}") {
                frameCount(journal) shouldBe 40
                g.b.membership() shouldBe preCrashFold
                g.a.membership() shouldBe preCrashFold
            }
        }
    }

    /**
     * Example R-B' (rule R-B, the cross-journal direction — pinned so nobody "optimises" it
     * away). A tees to J_A, B to J_B. Only J_A is recovered: each replayed A-frame's delivery
     * derives a B-frame, and because B's journal is NOT the journal being replayed, that
     * derived frame is appended to J_B as live — 5 originals + 5 duplicates = 10.
     *
     * Why the duplicates are deliberate (design xy7w4-D2). J_B may LACK the derived frame: a
     * crash between A's frame reaching J_A and the derived frame reaching J_B leaves it in
     * flight, recorded nowhere but in J_A's ability to re-derive it. If recovery suppressed
     * the append to J_B, and J_A then checkpointed (compacting A's frame into a snapshot of A
     * alone), a later restart of J_B would lose that derived frame for good — a silent
     * omission. Appending it again costs a bounded, tag-idempotent duplicate on J_B per
     * restart, compacted by J_B's next checkpoint: the direction `[24-DUR-07]` already chose
     * ("a duplicate is loud and bounded, a suppression is a silent omission"). The same-journal
     * case (R-B) is different: J's checkpoint snapshots A and B together, so (J ∪ snapshot)
     * always reconstructs B, and replaying A re-derives it before any checkpoint.
     */
    @Test
    fun `R-B' a derived frame teeing to a different journal is appended there as live`() {
        val controller = SimulationController(seed = 9)
        val journalA = InMemoryJournal()
        val journalB = InMemoryJournal()
        val aRef = CellRef(UUID.randomUUID())
        val bRef = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = {
            when (it) {
                aRef -> journalA
                bRef -> journalB
                else -> null
            }
        }

        val live = Graph(controller.scheduler(), selector, aRef, bRef).also { it.link(::deltas) }
        controller.runToIdle()
        (1..5).forEach { ops(live.host, aRef).add("e$it") }
        controller.runToIdle()
        val preCrashFold = live.b.membership()
        frameCount(journalA) shouldBe 5
        frameCount(journalB) shouldBe 5

        val g = Graph(controller.scheduler(), selector, aRef, bRef).also { it.link(::deltas) }
        controller.runToIdle()
        g.host.recoverFrom(journalA)
        controller.runToIdle()

        frameCount(journalA) shouldBe 5
        frameCount(journalB) shouldBe 10
        g.b.membership() shouldBe preCrashFold
    }

    /**
     * D2's saturation clause, derived half: the SATURATED bypass keys on the frame's replay
     * provenance AFTER the intake has inherited it, so a same-host frame derived from
     * delivering a replayed frame passes a SATURATED intake like the replayed frame itself
     * (it is re-derived, already-accepted history — T05 finding 4's reason), while a live
     * frame is gated (the R-A saturation test). A journals to J; B is a volatile view fed
     * only by A's deltas, so B's post-recovery fold exists only if every derived frame was
     * accepted. Recovering 5 frames under `highWater = 2` leaves the intake SATURATED while
     * they are delivered. Gating the derived frames instead (as the time-window flag did once
     * it had reset) refuses them into A's handler: under that mutation B recovered only `e5`,
     * the one delivered after the intake drained.
     */
    @Test
    fun `R-B a replay-derived frame passes a SATURATED intake`() {
        val controller = SimulationController(seed = 13)
        val journal = InMemoryJournal()
        val aRef = CellRef(UUID.randomUUID())
        val bRef = CellRef(UUID.randomUUID())
        val selector: (CellRef) -> Journal? = { if (it == aRef) journal else null }
        val bound = IntakeBound(highWater = 2, lowWater = 0, policy = SaturationPolicy.Park)

        val live = Graph(controller.scheduler(), selector, aRef, bRef, bound).also { it.link(::deltas) }
        controller.runToIdle()
        (1..5).forEach {
            ops(live.host, aRef).add("e$it")
            controller.runToIdle()
        }
        val preCrashFold = live.b.membership()
        preCrashFold shouldBe (1..5).map { "e$it" }.toSet()
        frameCount(journal) shouldBe 5

        val g = Graph(controller.scheduler(), selector, aRef, bRef, bound).also { it.link(::deltas) }
        controller.runToIdle()
        g.host.recoverFrom(journal).replayedFrames shouldBe 5
        controller.runToIdle()

        g.b.membership() shouldBe preCrashFold
        frameCount(journal) shouldBe 5
    }

    interface TriggerApi {
        suspend fun trigger()
    }

    /**
     * Rule R-C, suspension half: `ManagedHost.deliver` re-installs a frame's replay provenance
     * with `ReplayProvenance.withSuspending`, so a `SuspendingCell` handler that suspends and
     * resumes on a DIFFERENT worker thread still emits under it — and the intake downstream
     * inherits it. The emission runs on a dedicated resume thread the host never touched, so a
     * plain thread-local set on the delivering thread would read null there.
     */
    @Test
    fun `R-C replay provenance survives a suspending handler resuming on another worker thread`() {
        val resumeOn = Executors.newSingleThreadExecutor { Thread(it, "xy7w4.1-RC-resume") }
        try {
            val host = ManagedHost(scheduler = CoroutineScheduler("xy7w4.1-RC"))
            val cell = object : Cell, SuspendingCell {
                override val ref = CellRef(UUID.randomUUID())
                val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())
                val inlet = registerPort("inlet", FanInlet(TriggerApi::class.java))

                init {
                    inlet.serve(object : TriggerApi {
                        override suspend fun trigger() {
                            withContext(resumeOn.asCoroutineDispatcher()) { outlet.call.propagate("emitted") }
                        }
                    })
                }
            }
            host.managementInlet.call.spawn(cell)

            val seen = CompletableFuture<Pair<String, Any?>>()
            cell.outlet.subscribe(
                Use.fixed(object : Propagate<String> {
                    override fun propagate(value: String) {
                        seen.complete(Thread.currentThread().name to ReplayProvenance.get())
                    }
                }, PortRef.generate()),
            )

            val token = InMemoryJournal()
            host.enqueueHostedInvocation(
                HostedPortInvocation(
                    cell.ref, "inlet", HostedPortInvocation.Type.PORT_API,
                    Invocation("trigger", emptyList(), emptyList()),
                    replayOf = token,
                ),
            )

            val (thread, provenance) = seen.get(15, TimeUnit.SECONDS)
            thread.startsWith("xy7w4.1-RC-resume").shouldBeTrue()
            (provenance === token).shouldBeTrue()
        } finally {
            resumeOn.shutdown()
        }
    }
}
