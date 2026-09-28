package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.awaitDrained
import civictech.testkit.dst.JournalMutation
import civictech.testkit.dst.MutatingJournal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.Serializable
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * computenet-q5jzk.1 — the kernel's quiescence fence ([ManagedHost.quiescence],
 * [HostScheduler.quiescence]) and the [Recovery] handle [ManagedHost.recoverFrom]
 * returns.
 *
 * The recovery tests replay into a three-hop same-host cascade A → B → C where
 * only A is journaled: the journal holds exactly A's input frames, and every
 * frame B and C receive is minted *during* delivery — work that does not exist
 * when `recoverFrom` returns. That is the case a fence taken at the right moment
 * must still cover, and the case a snapshot of staged work cannot.
 */
class QuiescenceTest {

    /** A hop that re-emits every value it receives, scaled, through its own outlet. */
    private class RelayCell(override val ref: CellRef, private val factor: Int) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input * factor)
            })
        }
    }

    /** The end of the cascade: its fold is what "applied" is asserted against. */
    private class SinkCell(override val ref: CellRef) : Cell {
        val received: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) { received += input }
            })
        }
    }

    /** A `Stateful` sink, so a checkpoint can compact frames into a snapshot record. */
    private class TallyCell(override val ref: CellRef) : Cell, Stateful {
        val received: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) { received += input }
            })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            received.clear()
            received.addAll(state as List<Int>)
        }
    }

    interface ConsumerInletProxy {
        val inlet: Use<Consumer<Int>>
    }

    private fun inletOf(host: ManagedHost, ref: CellRef): Consumer<Int> =
        (HostedCellProxy.create(ref, host, ConsumerInletProxy::class.java) as ConsumerInletProxy).inlet.call

    private class Refs(val a: CellRef = fresh(), val b: CellRef = fresh(), val c: CellRef = fresh()) {
        companion object {
            fun fresh() = CellRef(UUID.randomUUID())
        }
    }

    /**
     * One incarnation of A → B → C on one host. Each link routes through the
     * host's intake (a hosted proxy as the subscriber), so every hop is its own
     * staged frame and data-band dispatch task — the path replayed work takes.
     */
    private class Chain(scheduler: HostScheduler, journal: Journal, refs: Refs) {
        val host = ManagedHost(scheduler = scheduler, journalFor = { if (it == refs.a) journal else null })
        val a = RelayCell(refs.a, factor = 10)
        val b = RelayCell(refs.b, factor = 10)
        val c = SinkCell(refs.c)

        init {
            host.managementInlet.call.spawn(c)
            host.managementInlet.call.spawn(b)
            host.managementInlet.call.spawn(a)
        }

        fun link(inletOf: (ManagedHost, CellRef) -> Consumer<Int>) {
            a.outlet.subscribe(Use.fixed(inletOf(host, b.ref), PortRef.generate()))
            b.outlet.subscribe(Use.fixed(inletOf(host, c.ref), PortRef.generate()))
        }
    }

    private fun chain(scheduler: HostScheduler, journal: Journal, refs: Refs) =
        Chain(scheduler, journal, refs).also { it.link(::inletOf) }

    private val frames = 50
    private val expected = (0 until frames).map { it * 100 }

    /**
     * Q1 on a live scheduler. The assertion on C's fold follows `awaitApplied`
     * directly — no `awaitUntil` — so a handle that returned before the cascade
     * finished reads a short fold and fails. Twenty fresh hosts make a premature
     * return visible; a flake here is a real bug, not noise.
     */
    @Test
    fun `recoverFrom handle completes only after a replayed cascade has been applied`() {
        repeat(20) { round ->
            val journal = InMemoryJournal()
            val refs = Refs()

            val liveScheduler = VirtualThreadScheduler("q5jzk-live-$round")
            val live = chain(liveScheduler, journal, refs)
            val feed = inletOf(live.host, refs.a)
            (0 until frames).forEach { feed.provide(it) }
            live.host.quiescence().await(30_000, "live writes, round $round")
            live.c.received.toList().sorted() shouldBe expected
            liveScheduler.shutdown()

            // CRASH: only the journal survives; rebuild the graph, then recover
            val recoveredScheduler = VirtualThreadScheduler("q5jzk-recovered-$round")
            val recovered = chain(recoveredScheduler, journal, refs)
            val recovery = recovered.host.recoverFrom(journal)
            recovery.replayedFrames shouldBe frames

            recovery.awaitApplied(30_000)

            recovery.isApplied.shouldBeTrue()
            recovered.c.received.toList().sorted() shouldBe expected
            recoveredScheduler.shutdown()
        }
    }

    /**
     * Q1 under simulation: `recoverFrom` stages the frames and returns before
     * anything is delivered, so the handle is not applied until the controller
     * steps — and is applied, with the fold complete, once it has. The handle
     * never blocks the stepping thread (q5jzk-D3).
     */
    @Test
    fun `recovery handle is not applied before runToIdle on a SimulationController and is after`() {
        val controller = SimulationController(seed = 7)
        val journal = InMemoryJournal()
        val refs = Refs()

        val live = chain(controller.scheduler(), journal, refs)
        controller.runToIdle()
        val feed = inletOf(live.host, refs.a)
        (0 until frames).forEach { feed.provide(it) }
        controller.runToIdle()
        live.c.received.toList().sorted() shouldBe expected

        val recovered = chain(controller.scheduler(), journal, refs)
        controller.runToIdle()
        val recovery = recovered.host.recoverFrom(journal)

        recovery.replayedFrames shouldBe frames
        recovery.isApplied.shouldBeFalse()
        recovered.c.received.size shouldBe 0 // staged, not delivered

        controller.runToIdle()

        recovery.isApplied.shouldBeTrue()
        recovered.c.received.toList().sorted() shouldBe expected
    }

    /**
     * Q1b: `replayedFrames` counts `Frame` records only. After a checkpoint the
     * journal holds a snapshot record (plus whatever frontier/outlet-wave records
     * it carries) followed by the tail, and only the tail's frames are counted.
     */
    @Test
    fun `replayedFrames counts frame records only, not checkpoint records`() {
        val controller = SimulationController(seed = 3)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(TallyCell(ref))
        controller.runToIdle()
        (1..5).forEach { inletOf(host, ref).provide(it) }
        controller.runToIdle()
        host.checkpoint(journal) // 1..5 leave the WAL as one snapshot record
        (6..8).forEach { inletOf(host, ref).provide(it) }
        controller.runToIdle()
        (journal.replay().size > 3).shouldBeTrue() // the checkpoint record is there to be excluded

        val rebuilt = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recoveredCell = TallyCell(ref)
        rebuilt.managementInlet.call.spawn(recoveredCell)
        controller.runToIdle()
        val recovery = rebuilt.recoverFrom(journal)
        controller.runToIdle()

        recovery.replayedFrames shouldBe 3
        recovery.isApplied.shouldBeTrue()
        recoveredCell.received.toList() shouldBe (1..8).toList()
    }

    /** A failed replay still throws [RecoveryIncomplete]: no handle for a partial replay. */
    @Test
    fun `a corrupted journal still throws RecoveryIncomplete from recoverFrom`() {
        val controller = SimulationController(seed = 5)
        val journal = InMemoryJournal()
        val refs = Refs()
        val live = chain(controller.scheduler(), journal, refs)
        controller.runToIdle()
        (0 until 5).forEach { inletOf(live.host, refs.a).provide(it) }
        controller.runToIdle()

        val recovered = chain(controller.scheduler(), journal, refs)
        controller.runToIdle()
        val failure = shouldThrow<RecoveryIncomplete> {
            recovered.host.recoverFrom(MutatingJournal(journal, JournalMutation.CorruptAt(2)))
        }
        failure.recordIndex shouldBe 2
    }

    /**
     * Q3: a wedged drain thread never runs the fence, so the wait times out
     * loudly naming the caller's `what` and the timeout — it never returns as if
     * drained. Releasing the thread lets a fresh fence complete.
     */
    @Test
    fun `quiescence await times out on a wedged host`() {
        val scheduler = VirtualThreadScheduler("q5jzk-wedged")
        val release = CountDownLatch(1)
        scheduler.submit(20) { release.await() }

        val fence = scheduler.quiescence()
        val timeout = shouldThrow<QuiescenceTimeout> { fence.await(200, "wedged") }
        timeout.shouldBeInstanceOf<IllegalStateException>()
        timeout.message!! shouldContain "wedged"
        timeout.message!! shouldContain "200"
        fence.isReached.shouldBeFalse()

        // testkit's wrapper surfaces the same timeout as an assertion failure
        val asserted = shouldThrow<AssertionFailedError> { scheduler.awaitDrained("still wedged", 150) }
        asserted.message!! shouldContain "still wedged"
        asserted.message!! shouldContain "150"
        asserted.cause.shouldBeInstanceOf<QuiescenceTimeout>()

        release.countDown()
        scheduler.quiescence().await(5_000)
        fence.isReached.shouldBeTrue()
        scheduler.shutdown()
    }

    /** Q2: an idle host drains at once. */
    @Test
    fun `quiescence on a drained host completes`() {
        val scheduler = VirtualThreadScheduler("q5jzk-idle")
        val host = ManagedHost(scheduler = scheduler)
        val fence = host.quiescence()
        fence.await(5_000, "idle host")
        fence.isReached.shouldBeTrue()
        fence.asFuture().isDone.shouldBeTrue()
        scheduler.shutdown()
    }
}
