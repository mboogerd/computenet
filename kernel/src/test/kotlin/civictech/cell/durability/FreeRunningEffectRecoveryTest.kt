package civictech.cell.durability

import civictech.cell.Cell
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
import civictech.cell.link.LinkOptions
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.AbstractList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

        init {
            inlet.serve(Propagate { effects.getValue(ref).incrementAndGet() })
        }
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
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
    )

    private fun runtime(journal: Journal): Runtime {
        val registry = LocationRegistry()
        lateinit var context: ApplyContext
        val host = ManagedHost(
            registry = registry,
            journalFor = { ref -> context.journalFor(ref) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        return Runtime(host, registry, context)
    }

    @Test
    @Timeout(60)
    fun `uncompacted recovery restores an effect frontier before replay can re-fire the sink`() {
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

        val pausingJournal = PauseBeforeFirstFrontier(journal)
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
                recovered.host.quiescence().await(30_000, "replay attempt before frontier restore")
            } finally {
                pausingJournal.releaseFrontier()
            }
            recovery.get(30, TimeUnit.SECONDS).awaitApplied(30_000)
        } finally {
            executor.close()
        }

        effectCount.get() shouldBe 1
    }
}
