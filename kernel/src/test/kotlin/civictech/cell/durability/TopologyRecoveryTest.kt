package civictech.cell.durability

import civictech.cell.CellRef
import civictech.cell.Cell
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Effectful
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.UnlinkStep
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TopologyRecoveryTest {

    private companion object {
        val cells = ConcurrentHashMap<CellRef, Cell>()
        val effectCounts = ConcurrentHashMap<CellRef, AtomicInteger>()
    }

    private object RecordingSetFactory : CellFactory {
        override fun create(ref: CellRef): Cell = SetCell<String>(ref).also { cells[ref] = it }
    }

    private object RecordingObserveFactory : CellFactory {
        override fun create(ref: CellRef): Cell =
            ObserveCell(View.set<String>(), ref).also { cells[ref] = it }
    }

    private object RecordingEffectfulSetSinkFactory : CellFactory {
        override fun create(ref: CellRef): Cell = EffectfulSetSink(ref).also { cells[ref] = it }
    }

    private class EffectfulSetSink(override val ref: CellRef) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(Propagate { effectCounts.getValue(ref).incrementAndGet() })
        }
    }

    private object RejectingReconnectObserveFactory : CellFactory {
        override fun create(ref: CellRef): Cell =
            ObserveCell(View.set<String>(), ref).also { cell ->
                var handshakes = 0
                cell.inlet.linking.onLink = { link ->
                    handshakes++
                    if (handshakes > 1) {
                        LinkResult.Rejected("checkpoint reconnect denied")
                    } else {
                        LinkResult.Connected(link)
                    }
                }
                cells[ref] = cell
            }
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private val sourceRef = CellRef(UUID.randomUUID(), 1)
    private val sinkRef = CellRef(UUID.randomUUID(), 2)
    private val detachedRef = CellRef(UUID.randomUUID(), 3)
    private val despawnedRef = CellRef(UUID.randomUUID(), 4)

    private data class Runtime(
        val host: ManagedHost,
        val registry: LocationRegistry,
        val context: ApplyContext,
        val deadLetters: MutableList<DeadLetter>,
    )

    private fun runtime(journal: Journal): Runtime {
        val registry = LocationRegistry()
        lateinit var context: ApplyContext
        val host = ManagedHost(
            registry = registry,
            journalFor = { ref -> context.journalFor(ref) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        val deadLetters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(Propagate { deadLetters += it }, PortRef.generate()),
        )
        return Runtime(host, registry, context, deadLetters)
    }

    private fun sourceOps(runtime: Runtime): SetOps<String> =
        (HostedCellProxy.create(sourceRef, runtime.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun source(runtime: Runtime): SetCell<String> {
        @Suppress("UNCHECKED_CAST")
        return cells.getValue(sourceRef) as SetCell<String>
    }

    private fun sink(runtime: Runtime): ObserveCell<*, *> =
        cells.getValue(sinkRef) as ObserveCell<*, *>

    private fun initialSpec() = GraphSpec(
        listOf(
            SpawnStep(
                "source",
                RecordingSetFactory,
                IdentityBinding.Exact(sourceRef),
                journalId = "j",
            ),
            SpawnStep(
                "sink",
                RecordingObserveFactory,
                IdentityBinding.Exact(sinkRef),
            ),
            SpawnStep(
                "detached",
                RecordingObserveFactory,
                IdentityBinding.Exact(detachedRef),
            ),
            SpawnStep(
                "doomed",
                RecordingObserveFactory,
                IdentityBinding.Exact(despawnedRef),
            ),
            ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
            ConnectStep("source", "outlet", "detached", "inlet", LinkOptions(staged = true)),
            // (d): the despawned cell holds a live link, so replaying its Despawn must unlink first.
            ConnectStep("source", "outlet", "doomed", "inlet", LinkOptions(staged = true)),
        ),
    )

    private fun removeSpec() = GraphSpec(
        listOf(
            UnlinkStep("source", "outlet", "detached", "inlet"),
            DespawnStep("doomed"),
        ),
    )

    private fun assertRecovered(runtime: Runtime, expected: Set<String>) {
        runtime.host.portAt(sourceRef, "outlet") shouldBe source(runtime).outlet
        runtime.host.portAt(sinkRef, "inlet") shouldBe (cells.getValue(sinkRef) as ObserveCell<*, *>).inlet
        runtime.host.portAt(detachedRef, "inlet") shouldBe (cells.getValue(detachedRef) as ObserveCell<*, *>).inlet
        source(runtime).membership() shouldBe expected
        sink(runtime).current() shouldBe expected
        cells.getValue(detachedRef).shouldBeInstanceOf<ObserveCell<*, *>>()
        runtime.host.portAt(despawnedRef, "inlet") shouldBe null
        runtime.context.live().spawns.keys shouldBe setOf(sourceRef, sinkRef, detachedRef)
        runtime.context.live().links.size shouldBe 1
        runtime.context.handles.keys shouldBe setOf("source", "sink", "detached")
        runtime.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `journal topology rebuilds old refs before frames and checkpoint carries the folded graph`() {
        val journal = InMemoryJournal()
        val before = runtime(journal)
        initialSpec().apply(before.context)
        removeSpec().apply(before.context)
        sourceOps(before).add("apple")
        sourceOps(before).add("banana")
        before.host.quiescence().await(30_000, "pre-crash writes")
        assertRecovered(before, setOf("apple", "banana"))

        val uncompacted = runtime(journal)
        uncompacted.context.recover(journal).awaitApplied(30_000)
        assertRecovered(uncompacted, setOf("apple", "banana"))

        before.host.checkpoint(journal)
        JournalRecords.decode(journal.replay().first())
            .shouldBeInstanceOf<DecodedJournalRecord.Topology>()
            .events.none { it is TopoEvent.Unlink || it is TopoEvent.Despawn } shouldBe true

        val compacted = runtime(journal)
        compacted.context.recover(journal).awaitApplied(30_000)
        assertRecovered(compacted, setOf("apple", "banana"))
    }

    @Test
    fun `checkpoint topology recovery does not re-fire an effectful sink`() {
        val journal = InMemoryJournal()
        val effects = AtomicInteger()
        effectCounts[sinkRef] = effects
        val before = runtime(journal)
        GraphSpec(
            listOf(
                SpawnStep(
                    "source",
                    RecordingSetFactory,
                    IdentityBinding.Exact(sourceRef),
                    journalId = "j",
                ),
                SpawnStep(
                    "sink",
                    RecordingEffectfulSetSinkFactory,
                    IdentityBinding.Exact(sinkRef),
                    journalId = "j",
                ),
                ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
            ),
        ).apply(before.context)

        sourceOps(before).add("apple")
        before.host.quiescence().await(30_000, "pre-checkpoint effect")
        effects.get() shouldBe 1
        before.deadLetters.shouldBeEmpty()

        before.host.checkpoint(journal)

        val recovered = runtime(journal)
        recovered.context.recover(journal).awaitApplied(30_000)

        // The folded-link re-handshake uses ordinary onLinked catch-up, which is
        // contextless today. The Effectful inlet therefore refuses it under
        // [24-DUR-06] instead of acting on the restored source state again.
        effects.get() shouldBe 1
        recovered.deadLetters.single().description shouldContain
            "PORT_API invocation carries no MessageContext"
    }

    @Test
    fun `missing journal binding dead letters the topology record and aborts recovery loudly`() {
        val journal = InMemoryJournal()
        val writer = ManagedHost()
        writer.journalTopology(
            journal,
            listOf(
                TopoEvent.Spawn(
                    "source", sourceRef, RecordingSetFactory, null,
                    replicated = false, journalId = "j", shadow = false,
                ),
            ),
        )
        val host = ManagedHost()
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(Propagate { letters += it }, PortRef.generate()))

        val failure = shouldThrow<RecoveryIncomplete> { host.recoverFrom(journal) }
        failure.cause!!.message shouldContain "'j'"
        host.quiescence().await(30_000, "topology recovery dead letter")
        letters.size shouldBe 1
    }

    @Test
    fun `unregistered family key dead letters the topology record and aborts recovery loudly`() {
        val journal = InMemoryJournal()
        ManagedHost().journalTopology(journal, listOf(TopoEvent.FamilyKey("missing", "key")))
        val host = ManagedHost()
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(Propagate { letters += it }, PortRef.generate()))

        val failure = shouldThrow<RecoveryIncomplete> { host.recoverFrom(journal) }
        failure.cause!!.message shouldContain "unregistered namespace 'missing'"
        host.quiescence().await(30_000, "topology recovery dead letter")
        letters.size shouldBe 1
    }

    @Test
    fun `trailing checkpoint reconnect failure dead letters and aborts recovery loudly`() {
        val journal = InMemoryJournal()
        val before = runtime(journal)
        GraphSpec(
            listOf(
                SpawnStep(
                    "source",
                    RecordingSetFactory,
                    IdentityBinding.Exact(sourceRef),
                    journalId = "j",
                ),
                SpawnStep("sink", RejectingReconnectObserveFactory, IdentityBinding.Exact(sinkRef)),
                ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
            ),
        ).apply(before.context)
        before.host.checkpoint(journal)

        val recovering = runtime(journal)
        val failure = shouldThrow<RecoveryIncomplete> { recovering.context.recover(journal) }

        failure.cause!!.message shouldContain "checkpoint reconnect denied"
        recovering.host.quiescence().await(30_000, "post-checkpoint reconnect dead letter")
        recovering.deadLetters.single().description shouldContain "checkpoint reconnect denied"
    }

    @Test
    fun `a host refuses a second topology context for the same journal`() {
        val journal = InMemoryJournal()
        val runtime = runtime(journal)

        val failure = shouldThrow<IllegalStateException> {
            ApplyContext(runtime.host, topology = journal)
        }

        failure.message shouldContain "topology provider already registered"
        failure.message shouldContain journal.javaClass.name
        failure.message shouldContain Integer.toHexString(System.identityHashCode(journal))
    }

    @Test
    fun `GraphSpec topology append precedes the delta's first host operation`() {
        val journal = InMemoryJournal()
        val runtime = runtime(journal)
        val firstRef = CellRef(UUID.randomUUID(), 9)
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    "first",
                    CellFactory { throw IllegalStateException("first factory boom") },
                    IdentityBinding.Exact(firstRef),
                ),
            ),
        )

        shouldThrow<IllegalStateException> { spec.apply(runtime.context) }.message shouldContain "first factory boom"

        JournalRecords.decode(journal.replay().single())
            .shouldBeInstanceOf<DecodedJournalRecord.Topology>()
            .events.single().shouldBeInstanceOf<TopoEvent.Spawn>().ref shouldBe firstRef
    }

    @Test
    fun `GraphSpec topology append is write ahead of a later throwing factory`() {
        val journal = InMemoryJournal()
        val runtime = runtime(journal)
        val firstRef = CellRef(UUID.randomUUID(), 7)
        val secondRef = CellRef(UUID.randomUUID(), 8)
        val spec = GraphSpec(
            listOf(
                SpawnStep("first", RecordingSetFactory, IdentityBinding.Exact(firstRef)),
                SpawnStep(
                    "second",
                    CellFactory { throw IllegalStateException("factory boom") },
                    IdentityBinding.Exact(secondRef),
                ),
            ),
        )

        shouldThrow<IllegalStateException> { spec.apply(runtime.context) }.message shouldContain "factory boom"

        val topology = JournalRecords.decode(journal.replay().single())
            .shouldBeInstanceOf<DecodedJournalRecord.Topology>()
        topology.events.size shouldBe 2
        cells.getValue(firstRef).shouldBeInstanceOf<SetCell<*>>()
    }
}
