package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Effectful
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.Recovery
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.host.quiescence
import civictech.cell.link.LinkOptions
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.AbstractList
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FreeRunningEffectRecoveryTest {

    private companion object {
        val cells = ConcurrentHashMap<CellRef, Cell>()
        val effects = ConcurrentHashMap<CellRef, AtomicInteger>()
    }

    private object RecordingSetFactory : CellFactory {
        override fun create(ref: CellRef): Cell = SetCell<String>(ref).also { cells[ref] = it }
    }

    private object EffectfulSetSinkFactory : CellFactory {
        override fun create(ref: CellRef): Cell = EffectfulSetSink(ref).also { cells[ref] = it }
    }

    private class EffectfulSetSink(override val ref: CellRef) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val deactivations = AtomicInteger()

        init {
            inlet.serve(Propagate { effects.getValue(ref).incrementAndGet() })
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations.incrementAndGet()
        }
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface IntInlet {
        fun provide(value: Int)
    }

    interface IntInletProxy {
        val inlet: Use<IntInlet>
    }

    private class BlockingIntSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val received = ConcurrentLinkedQueue<Int>()
        val deactivations = AtomicInteger()
        val deactivated = CountDownLatch(1)
        val inlet = registerPort("inlet", FanInlet.create<IntInlet>())

        init {
            inlet.serve(object : IntInlet {
                override fun provide(value: Int) {
                    if (value == 1) {
                        firstEntered.countDown()
                        check(releaseFirst.await(30, TimeUnit.SECONDS)) {
                            "test did not release the first delivery within 30 seconds"
                        }
                    }
                    received += value
                }
            })
        }

        override fun onDeactivate(ctx: CellContext) {
            deactivations.incrementAndGet()
            deactivated.countDown()
        }
    }

    private class PauseOnReplay(private val delegate: Journal = InMemoryJournal()) : Journal by delegate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun replay(): List<ByteArray> {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) {
                "test did not release journal replay within 30 seconds"
            }
            return delegate.replay()
        }

        fun awaitPaused() {
            check(entered.await(30, TimeUnit.SECONDS)) {
                "recovery did not reach journal replay within 30 seconds"
            }
        }

        fun releaseReplay() = release.countDown()
    }

    private class PauseBeforeFirstFrontier(private val delegate: Journal) : Journal by delegate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val paused = AtomicBoolean()

        override fun replay(): List<ByteArray> {
            val records = delegate.replay()
            val pauseAt = records.indexOfFirst { JournalRecords.decode(it) is DecodedJournalRecord.Frontier }
            check(pauseAt >= 0) { "fixture journal contains no processed-frontier record" }
            return object : AbstractList<ByteArray>() {
                override val size: Int get() = records.size

                override fun get(index: Int): ByteArray {
                    if (index == pauseAt && paused.compareAndSet(false, true)) {
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) {
                            "test did not release the journal frontier within 30 seconds"
                        }
                    }
                    return records[index]
                }
            }
        }

        fun awaitPaused() {
            check(entered.await(30, TimeUnit.SECONDS)) {
                "recovery did not reach the frontier pause within 30 seconds"
            }
        }

        fun releaseFrontier() = release.countDown()
    }

    private data class Runtime(
        val host: ManagedHost,
        val registry: LocationRegistry,
        val context: ApplyContext,
        val scheduler: VirtualThreadScheduler,
    )

    private fun runtime(journal: Journal): Runtime {
        val registry = LocationRegistry()
        val scheduler = VirtualThreadScheduler("free-running-effect-recovery")
        lateinit var context: ApplyContext
        val host = ManagedHost(
            scheduler = scheduler,
            registry = registry,
            journalFor = { ref -> context.journalFor(ref) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        return Runtime(host, registry, context, scheduler)
    }

    private data class RecordedEffect(
        val journal: Journal,
        val sinkRef: CellRef,
        val effectCount: AtomicInteger,
    )

    private fun recordOneEffect(): RecordedEffect {
        val sourceRef = CellRef(UUID.randomUUID(), 1)
        val sinkRef = CellRef(UUID.randomUUID(), 2)
        val effectCount = AtomicInteger()
        effects[sinkRef] = effectCount

        val journal = InMemoryJournal()
        val before = runtime(journal)
        GraphSpec(
            listOf(
                SpawnStep("source", RecordingSetFactory, IdentityBinding.Exact(sourceRef), journalId = "j"),
                SpawnStep("sink", EffectfulSetSinkFactory, IdentityBinding.Exact(sinkRef), journalId = "j"),
                ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
            ),
        ).apply(before.context)

        val source = HostedCellProxy.create(sourceRef, before.registry, SetInletProxy::class.java) as SetInletProxy
        source.inlet.call.add("apple")
        before.host.quiescence().await(30_000, "live effect")
        effectCount.get() shouldBe 1
        return RecordedEffect(journal, sinkRef, effectCount)
    }

    private fun awaitRecoveryRecordLoop(host: ManagedHost) {
        val dataLock = ManagedHost::class.java.getDeclaredField("dataLock").apply { isAccessible = true }.get(host)
        val loops = ManagedHost::class.java.getDeclaredField("recoveryRecordLoops").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (synchronized(dataLock) { loops.getInt(host) } > 0) return
            Thread.onSpinWait()
        }
        error("recovery record-loop gate did not rise within 30 seconds")
    }

    private fun awaitRecoveryGateOrCompletion(host: ManagedHost, recovery: Future<*>): Boolean {
        val dataLock = ManagedHost::class.java.getDeclaredField("dataLock").apply { isAccessible = true }.get(host)
        val loops = ManagedHost::class.java.getDeclaredField("recoveryRecordLoops").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (recovery.isDone) return false
            if (synchronized(dataLock) { loops.getInt(host) } > 0) return true
            Thread.onSpinWait()
        }
        error("recovery neither raised its record-loop gate nor completed within 30 seconds")
    }

    private fun awaitSchedulerQueueSize(scheduler: VirtualThreadScheduler, minimum: Int) {
        val queue = VirtualThreadScheduler::class.java.getDeclaredField("queue")
            .apply { isAccessible = true }
            .get(scheduler) as PriorityBlockingQueue<*>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (queue.size >= minimum) return
            Thread.onSpinWait()
        }
        error("scheduler did not retain $minimum queued task(s) within 30 seconds")
    }

    @Test
    @Timeout(60)
    fun `uncompacted recovery restores an effect frontier before replay can re-fire the sink`() {
        val recorded = recordOneEffect()
        val pausingJournal = PauseBeforeFirstFrontier(recorded.journal)
        val recovered = runtime(pausingJournal)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val recovery: Future<Recovery> = executor.submit<Recovery> {
                recovered.context.recover(pausingJournal)
            }
            try {
                pausingJournal.awaitPaused()
                // The replayed sink frame is staged, but its following frontier record is
                // deliberately not visible yet. Draining the scheduler here deterministically
                // exposes any delivery that races ahead of the journal restore.
                recovered.scheduler.quiescence().await(30_000, "replay attempt before frontier restore")
            } finally {
                pausingJournal.releaseFrontier()
            }
            recovery.get(30, TimeUnit.SECONDS).awaitApplied(30_000)
        } finally {
            executor.close()
        }

        recorded.effectCount.get() shouldBe 1
    }

    @Test
    @Timeout(60)
    fun `external quiescence is rejected while recovery holds staged frames`() {
        val recorded = recordOneEffect()
        val pausingJournal = PauseBeforeFirstFrontier(recorded.journal)
        val recovered = runtime(pausingJournal)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val recovery = executor.submit<Recovery> { recovered.context.recover(pausingJournal) }
            try {
                pausingJournal.awaitPaused()
                shouldThrow<IllegalStateException> { recovered.host.quiescence() }
            } finally {
                pausingJournal.releaseFrontier()
            }
            recovery.get(30, TimeUnit.SECONDS).awaitApplied(30_000)
        } finally {
            executor.close()
        }
        recorded.effectCount.get() shouldBe 1
    }

    @Test
    @Timeout(60)
    fun `drain begun during recovery cannot deactivate a cell ahead of its staged frame`() {
        val recorded = recordOneEffect()
        val pausingJournal = PauseBeforeFirstFrontier(recorded.journal)
        val recovered = runtime(pausingJournal)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val recovery = executor.submit<Recovery> { recovered.context.recover(pausingJournal) }
            try {
                pausingJournal.awaitPaused()
                val recoveredSink = cells.getValue(recorded.sinkRef) as EffectfulSetSink
                recovered.host.managementInlet.call.drainHost()
                recovered.scheduler.quiescence().await(30_000, "drain attempted during recovery")

                recoveredSink.deactivations.get() shouldBe 0
                recovered.host.isDrained shouldBe false
            } finally {
                pausingJournal.releaseFrontier()
            }
            recovery.get(30, TimeUnit.SECONDS).awaitApplied(30_000)
        } finally {
            executor.close()
        }
    }

    @Test
    @Timeout(60)
    fun `recovery cannot overtake a pending host drain phase two`() {
        val scheduler = VirtualThreadScheduler("recovery-pending-host-drain")
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry)
        val sink = BlockingIntSink()
        host.managementInlet.call.spawn(sink)
        scheduler.quiescence().await(30_000, "spawn blocking sink")
        val inlet = (HostedCellProxy.create(sink.ref, registry, IntInletProxy::class.java) as IntInletProxy).inlet.call

        val schedulerBlocked = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val journal = PauseOnReplay()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        scheduler.submit(-1) {
            schedulerBlocked.countDown()
            check(releaseScheduler.await(30, TimeUnit.SECONDS)) {
                "test did not release the scheduler blocker within 30 seconds"
            }
        }
        try {
            check(schedulerBlocked.await(30, TimeUnit.SECONDS)) {
                "scheduler blocker did not start within 30 seconds"
            }
            inlet.provide(1)
            inlet.provide(2)
            host.managementInlet.call.drainHost()
            releaseScheduler.countDown()
            check(sink.firstEntered.await(30, TimeUnit.SECONDS)) {
                "first accepted delivery did not enter after drain phase one"
            }

            val recovery = executor.submit<Recovery> { host.recoverFrom(journal) }
            val overlapped = awaitRecoveryGateOrCompletion(host, recovery)
            sink.releaseFirst.countDown()
            if (overlapped) {
                journal.awaitPaused()
                check(sink.deactivated.await(30, TimeUnit.SECONDS)) {
                    "pending drain phase two did not run while recovery was paused"
                }
                sink.deactivations.get() shouldBe 0
            }
            journal.releaseReplay()

            val failure = shouldThrow<ExecutionException> { recovery.get(30, TimeUnit.SECONDS) }
            failure.cause.shouldBeInstanceOf<IllegalStateException>()
            scheduler.quiescence().await(30_000, "host drain after refused recovery")
            sink.received.toList() shouldBe listOf(1, 2)
        } finally {
            releaseScheduler.countDown()
            sink.releaseFirst.countDown()
            journal.releaseReplay()
            executor.close()
            scheduler.shutdown()
        }
    }

    @Test
    @Timeout(60)
    fun `recovery refuses while an external quiescence fence is in flight`() {
        val scheduler = VirtualThreadScheduler("recovery-pending-quiescence")
        val host = ManagedHost(scheduler = scheduler)
        val schedulerBlocked = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        scheduler.submit(-1) {
            schedulerBlocked.countDown()
            check(releaseScheduler.await(30, TimeUnit.SECONDS)) {
                "test did not release the scheduler blocker within 30 seconds"
            }
        }
        try {
            check(schedulerBlocked.await(30, TimeUnit.SECONDS)) {
                "scheduler blocker did not start within 30 seconds"
            }
            val externalFence = host.quiescence()
            val recovery = executor.submit<Recovery> { host.recoverFrom(InMemoryJournal()) }
            awaitRecoveryGateOrCompletion(host, recovery)
            releaseScheduler.countDown()

            val failure = shouldThrow<ExecutionException> { recovery.get(30, TimeUnit.SECONDS) }
            failure.cause.shouldBeInstanceOf<IllegalStateException>()
            externalFence.await(30_000, "external quiescence after refused recovery")
        } finally {
            releaseScheduler.countDown()
            executor.close()
            scheduler.shutdown()
        }
    }

    @Test
    @Timeout(60)
    fun `recovery refuses while a cell drain barrier is in flight`() {
        val scheduler = VirtualThreadScheduler("recovery-pending-cell-drain")
        val host = ManagedHost(scheduler = scheduler)
        val sink = BlockingIntSink()
        host.managementInlet.call.spawn(sink)
        scheduler.quiescence().await(30_000, "spawn cell for draining")

        val schedulerBlocked = CountDownLatch(1)
        val releaseScheduler = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        scheduler.submit(-1) {
            schedulerBlocked.countDown()
            check(releaseScheduler.await(30, TimeUnit.SECONDS)) {
                "test did not release the scheduler blocker within 30 seconds"
            }
        }
        try {
            check(schedulerBlocked.await(30, TimeUnit.SECONDS)) {
                "scheduler blocker did not start within 30 seconds"
            }
            val drain = executor.submit<Unit> { host.drainCellThenDespawn(sink.ref) }
            awaitSchedulerQueueSize(scheduler, 1)
            val recovery = executor.submit<Recovery> { host.recoverFrom(InMemoryJournal()) }
            awaitRecoveryGateOrCompletion(host, recovery)
            releaseScheduler.countDown()

            val failure = shouldThrow<ExecutionException> { recovery.get(30, TimeUnit.SECONDS) }
            failure.cause.shouldBeInstanceOf<IllegalStateException>()
            drain.get(30, TimeUnit.SECONDS)
            scheduler.quiescence().await(30_000, "cell drain after refused recovery")
        } finally {
            releaseScheduler.countDown()
            executor.close()
            scheduler.shutdown()
        }
    }

    @Test
    @Timeout(60)
    fun `a failed recovery fence re-arms every frame staged behind the gate`() {
        val scheduler = VirtualThreadScheduler("failed-recovery-fence")
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = scheduler, registry = registry, dispatchBatch = 2)
        val sink = BlockingIntSink()
        host.managementInlet.call.spawn(sink)
        scheduler.quiescence().await(30_000, "spawn blocking sink")
        val inlet = (HostedCellProxy.create(sink.ref, registry, IntInletProxy::class.java) as IntInletProxy).inlet.call

        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val recoveryThread = AtomicReference<Thread>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            inlet.provide(1)
            check(sink.firstEntered.await(30, TimeUnit.SECONDS)) {
                "first delivery did not enter within 30 seconds"
            }

            val recovery = executor.submit<Recovery> {
                recoveryThread.set(Thread.currentThread())
                host.recoverFrom(InMemoryJournal())
            }
            awaitRecoveryRecordLoop(host)

            inlet.provide(2)
            inlet.provide(3)
            scheduler.submit(-1) {
                blockerEntered.countDown()
                check(releaseBlocker.await(30, TimeUnit.SECONDS)) {
                    "test did not release the scheduler blocker within 30 seconds"
                }
            }
            sink.releaseFirst.countDown()
            check(blockerEntered.await(30, TimeUnit.SECONDS)) {
                "scheduler blocker did not run within 30 seconds"
            }

            recoveryThread.get().interrupt()
            val failure = shouldThrow<ExecutionException> { recovery.get(30, TimeUnit.SECONDS) }
            failure.cause.shouldBeInstanceOf<InterruptedException>()

            releaseBlocker.countDown()
            scheduler.quiescence().await(30_000, "dispatch after failed recovery fence")

            sink.received.toList() shouldBe listOf(1, 2, 3)
            host.stagedWorkTotal() shouldBe 0
        } finally {
            sink.releaseFirst.countDown()
            releaseBlocker.countDown()
            executor.close()
            scheduler.shutdown()
        }
    }
}
