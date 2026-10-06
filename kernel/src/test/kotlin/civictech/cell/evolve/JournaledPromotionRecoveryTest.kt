package civictech.cell.evolve

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.TopologyFold
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.graph
import civictech.cell.host.DeadLetter
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkOptions
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortNatures
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.verify.InvariantCell
import civictech.cell.verify.Violation
import civictech.nature.NatureVector
import civictech.nature.Ownership
import civictech.testkit.forEachSeed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JournaledPromotionRecoveryTest {

    private companion object {
        @Suppress("UNCHECKED_CAST")
        val consumerInt = Consumer::class.java as Class<Consumer<Int>>
        val cells = ConcurrentHashMap<CellRef, Cell>()
        val creations = ConcurrentHashMap<CellRef, Int>()

        fun remember(cell: Cell) {
            cells[cell.ref] = cell
            creations.compute(cell.ref) { _, count -> (count ?: 0) + 1 }
        }
    }

    class RelayCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input)
            })
        }
    }

    class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.propagate(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum

        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    class SummerV2(override val ref: CellRef) : Cell, Stateful, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var representation = "sum=0"

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    representation = "sum=${sum() + input}"
                    outlet.call.propagate(sum())
                }
            })
        }

        private fun sum(): Long = representation.removePrefix("sum=").toLong()

        override fun snapshot(): Serializable = representation

        override fun restore(state: Serializable) {
            representation = state as String
        }

        override fun importFrom(prior: Serializable) {
            representation = "sum=${prior as Long}"
        }
    }

    class FailingSummerV2(override val ref: CellRef) : Cell, Stateful, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.propagate(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum

        override fun restore(state: Serializable) {
            sum = state as Long
        }

        override fun importFrom(prior: Serializable) {
            throw IllegalStateException("candidate state transfer boom")
        }
    }

    class CollectorCell(override val ref: CellRef) : Cell, Stateful {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Long>>())

        init {
            inlet.serve(Propagate { received += it })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        override fun restore(state: Serializable) {
            @Suppress("UNCHECKED_CAST")
            received.apply {
                clear()
                addAll(state as List<Long>)
            }
        }
    }

    private class OwnedSink(override val ref: CellRef) : Cell {
        val taken = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<ShadowOwnedPush>())

        init {
            inlet.serve(object : ShadowOwnedPush {
                override fun push(value: Owned<String>) {
                    taken += value.take()
                }
            })
        }
    }

    private object RelayFactory : TypedCellFactory<RelayCell> {
        override fun create(ref: CellRef): RelayCell = RelayCell(ref).also(::remember)
    }

    private object GateFactory : TypedCellFactory<TrafficLightCell<Consumer<Int>>> {
        override fun create(ref: CellRef): TrafficLightCell<Consumer<Int>> =
            TrafficLightCell(consumerInt, ref).also(::remember)
    }

    private object SummerV1Factory : TypedCellFactory<SummerV1> {
        override fun create(ref: CellRef): SummerV1 = SummerV1(ref).also(::remember)
    }

    private object SummerV2Factory : TypedCellFactory<SummerV2> {
        override fun create(ref: CellRef): SummerV2 = SummerV2(ref).also(::remember)
    }

    private object FailingSummerV2Factory : TypedCellFactory<FailingSummerV2> {
        override fun create(ref: CellRef): FailingSummerV2 = FailingSummerV2(ref).also(::remember)
    }

    private object CollectorFactory : TypedCellFactory<CollectorCell> {
        override fun create(ref: CellRef): CollectorCell = CollectorCell(ref).also(::remember)
    }

    private object OwnedGateFactory : TypedCellFactory<TrafficLightCell<ShadowOwnedPush>> {
        override fun create(ref: CellRef): TrafficLightCell<ShadowOwnedPush> =
            TrafficLightCell(ShadowOwnedPush::class.java, ref).also { gate ->
                PortNatures.stamp(gate.dataOutlet, NatureVector.of(Ownership.EXCLUSIVE))
                remember(gate)
            }
    }

    private object OwnedSinkFactory : TypedCellFactory<OwnedSink> {
        override fun create(ref: CellRef): OwnedSink = OwnedSink(ref).also(::remember)
    }

    private object InvariantFactory : TypedCellFactory<InvariantCell<Long, Pair<Long, Long>>> {
        override fun create(ref: CellRef): InvariantCell<Long, Pair<Long, Long>> =
            InvariantCell<Long, Pair<Long, Long>>(
            name = "candidate sum never regresses",
            initial = 0L to 0L,
            fold = { (_, current), value -> current to value },
            check = { (prior, current), _ -> if (current < prior) "$current < $prior" else null },
            ref = ref,
        ).also(::remember)
    }

    private interface RelayProxy {
        val inlet: Use<Consumer<Int>>
    }

    private data class Refs(
        val relay: CellRef,
        val gate: CellRef,
        val incumbent: CellRef,
        val candidate: CellRef,
        val collector: CellRef,
        val invariant: CellRef,
    )

    private data class World(
        val controller: SimulationController,
        val host: ManagedHost,
        val context: ApplyContext,
        val deadLetters: MutableList<DeadLetter>,
    )

    private fun world(seed: Long, journal: Journal): World {
        val controller = SimulationController(seed)
        lateinit var context: ApplyContext
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journalFor = { ref -> context.journalFor(ref) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        val deadLetters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(Propagate { deadLetters += it }, PortRef.generate()))
        return World(controller, host, context, deadLetters)
    }

    private fun build(
        world: World,
        refs: Refs,
        failingCandidate: Boolean = false,
        candidateJournalId: String? = "j",
    ) {
        graph(world.context) {
            val relay = spawn("relay", IdentityBinding.Exact(refs.relay), journalId = "j", factory = RelayFactory)
            val gate = spawn("gate", IdentityBinding.Exact(refs.gate), factory = GateFactory)
            val incumbent = spawn(
                "incumbent", IdentityBinding.Exact(refs.incumbent), journalId = "j", factory = SummerV1Factory,
            )
            val candidate = if (failingCandidate) {
                spawn(
                    "candidate", IdentityBinding.Exact(refs.candidate), journalId = candidateJournalId, shadow = true,
                    factory = FailingSummerV2Factory,
                )
            } else {
                spawn(
                    "candidate", IdentityBinding.Exact(refs.candidate), journalId = candidateJournalId, shadow = true,
                    factory = SummerV2Factory,
                )
            }
            val collector = spawn(
                "collector", IdentityBinding.Exact(refs.collector), journalId = "j", factory = CollectorFactory,
            )
            val invariant = spawn("invariant", IdentityBinding.Exact(refs.invariant), factory = InvariantFactory)
            val staged = LinkOptions(staged = true)
            connect(relay, "outlet", gate, "dataInlet", staged)
            connect(gate, "dataOutlet", incumbent, "inlet", staged)
            connect(gate, "dataOutlet", candidate, "inlet", staged)
            connect(incumbent, "outlet", collector, "inlet", staged)
            connect(candidate, "outlet", invariant, "inlet", staged)
        }
        gate(world, refs).controlInlet.call.setGreen()
        world.controller.runToIdle()
    }

    private fun gate(world: World, refs: Refs): TrafficLightCell<Consumer<Int>> {
        @Suppress("UNCHECKED_CAST")
        return cells.getValue(refs.gate) as TrafficLightCell<Consumer<Int>>
    }

    private fun candidate(refs: Refs): SummerV2 = cells.getValue(refs.candidate) as SummerV2

    private fun incumbent(refs: Refs): SummerV1 = cells.getValue(refs.incumbent) as SummerV1

    private fun collector(refs: Refs): CollectorCell = cells.getValue(refs.collector) as CollectorCell

    private fun feed(world: World, refs: Refs, input: Int) {
        world.host.lookup<RelayProxy>(refs.relay)!!.inlet.call.provide(input)
    }

    private fun refs(): Refs {
        val logicalId = UUID.randomUUID()
        return Refs(
            relay = CellRef(UUID.randomUUID()),
            gate = CellRef(UUID.randomUUID()),
            incumbent = CellRef(logicalId, 0),
            candidate = CellRef(logicalId, 1),
            collector = CellRef(UUID.randomUUID()),
            invariant = CellRef(UUID.randomUUID()),
        )
    }

    private fun topologyShape(fold: TopologyFold): Any = listOf(
        fold.spawns.mapValues { (_, spawn) ->
            listOf(
                spawn.handle, spawn.ref, spawn.parent, spawn.replicated,
                spawn.journalId, spawn.shadow, spawn.factory.javaClass.name,
            )
        },
        fold.families,
        fold.links,
        fold.handles,
    )

    private fun drive(world: World, refs: Refs, inputs: IntRange, random: Random) {
        inputs.forEach { input ->
            feed(world, refs, input)
            repeat(random.nextInt(4)) { world.controller.step() }
        }
        world.controller.runToIdle()
    }

    private fun controlStream(seed: Long): List<Long> {
        val refs = refs()
        val control = world(seed, InMemoryJournal())
        build(control, refs)
        drive(control, refs, 1..16, Random(seed))
        return collector(refs).received.toList()
    }

    private fun decoded(journal: Journal): List<DecodedJournalRecord> =
        journal.replay().map(JournalRecords::decode)

    private fun promotionRecords(records: List<DecodedJournalRecord>) = records.withIndex().filter { (_, record) ->
        record is DecodedJournalRecord.Topology && record.events.any { it is TopoEvent.Promote }
    }

    private fun recoverPromoted(compacted: Boolean): Triple<World, Refs, List<Long>> {
        val seed = if (compacted) 902L else 901L
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(seed, journal)
        build(before, refs)
        drive(before, refs, 1..5, Random(seed))
        before.context.promote(
            gate = refs.gate,
            incumbent = refs.incumbent,
            candidate = refs.candidate,
            outletName = "outlet",
            downstream = listOf(refs.collector to "inlet"),
        )
        drive(before, refs, 6..7, Random(seed + 1))
        if (compacted) before.host.checkpoint(journal)
        drive(before, refs, 8..9, Random(seed + 2))
        val preCrash = collector(refs).received.toList()
        preCrash.last() shouldBe 45L

        val recovered = world(seed, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash
        recovered.deadLetters.shouldBeEmpty()
        return Triple(recovered, refs, preCrash)
    }

    private fun assertRecovery(seed: Long) {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(seed, journal)
        build(before, refs)
        val emittedBeforeCrash = mutableListOf<MessageContext>()
        candidate(refs).outlet.observe(PortRef.generate()) { emittedBeforeCrash += it }
        val random = Random(seed)

        drive(before, refs, 1..10, random)
        before.context.promote(
            gate = refs.gate,
            incumbent = refs.incumbent,
            candidate = refs.candidate,
            outletName = "outlet",
            downstream = listOf(refs.collector to "inlet"),
        )
        drive(before, refs, 11..15, random)
        val preCrashWave = candidate(refs).outlet.waveState()
        emittedBeforeCrash.none { it.reBaseline != null }.shouldBeTrue()
        val preCrashStream = collector(refs).received.toList()
        val liveAfterPromotion = before.context.live()

        val recovered = world(seed, journal)
        val recovery = recovered.context.recover(journal)
        val emitted = mutableListOf<MessageContext>()
        candidate(refs).outlet.observe(PortRef.generate()) { emitted += it }
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)

        cells.getValue(refs.candidate).shouldBeInstanceOf<SummerV2>()
        recovered.host.portAt(refs.incumbent, "inlet") shouldBe null
        recovered.context.live().spawns.getValue(refs.candidate).shadow shouldBe false
        recovered.context.live().links.values.any {
            it.from == refs.candidate && it.to == refs.collector
        } shouldBe true
        topologyShape(recovered.context.live()) shouldBe topologyShape(liveAfterPromotion)
        collector(refs).received shouldBe preCrashStream
        candidate(refs).outlet.waveState().sourceId shouldBe preCrashWave.sourceId
        emitted.none { it.reBaseline != null }.shouldBeTrue()

        candidate(refs).inlet.call.provide(16)
        recovered.controller.runToIdle()
        val firstLiveAfterRecovery = emitted.last()
        firstLiveAfterRecovery.timestamp.sourceId shouldBe preCrashWave.sourceId
        check(firstLiveAfterRecovery.timestamp.counter > preCrashWave.highWater) {
            "post-recovery counter ${firstLiveAfterRecovery.timestamp.counter} did not exceed " +
                "pre-crash high-water ${preCrashWave.highWater}; candidateWave=$preCrashWave"
        }
        firstLiveAfterRecovery.reBaseline shouldBe null
        collector(refs).received shouldBe controlStream(seed)

        val records = decoded(journal)
        val promoteRecords = promotionRecords(records)
        promoteRecords.size shouldBe 1
        val promotionIndex = promoteRecords.single().index
        val checkpointIndex = records.indexOfLast { it is DecodedJournalRecord.Checkpoint }
        val firstPostSwapFrame = records.withIndex().firstOrNull { (index, record) ->
            index > promotionIndex && record is DecodedJournalRecord.Frame
        }?.index ?: -1
        (checkpointIndex in 0 until promotionIndex).shouldBeTrue()
        (firstPostSwapFrame > promotionIndex).shouldBeTrue()
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a journaled promotion recovers the candidate topology state and epoch under 100 seeds`() {
        forEachSeed(0L until 100L, ::assertRecovery)
    }

    @Test
    fun `a post-promotion checkpoint boots the candidate directly from the compacted fold`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(101, journal)
        build(before, refs)
        drive(before, refs, 1..5, Random(101))
        before.context.promote(
            gate = refs.gate,
            incumbent = refs.incumbent,
            candidate = refs.candidate,
            outletName = "outlet",
            downstream = listOf(refs.collector to "inlet"),
        )
        drive(before, refs, 6..8, Random(102))
        val expected = collector(refs).received.toList()
        before.host.checkpoint(journal)

        val compacted = decoded(journal)
        promotionRecords(compacted).size shouldBe 1
        val topology = compacted.filterIsInstance<DecodedJournalRecord.Topology>().single()
        topology.events.filterIsInstance<TopoEvent.Promote>().single().let { promote ->
            promote.incumbent shouldBe refs.incumbent
            promote.candidate shouldBe refs.candidate
        }
        topology.events.filterIsInstance<TopoEvent.Spawn>().map { it.ref }.let { spawned ->
            (refs.candidate in spawned).shouldBeTrue()
            (refs.incumbent !in spawned).shouldBeTrue()
        }
        val incumbentCreations = creations.getValue(refs.incumbent)
        val candidateCreations = creations.getValue(refs.candidate)

        val recovered = world(101, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)

        creations.getValue(refs.incumbent) shouldBe incumbentCreations
        creations.getValue(refs.candidate) shouldBe candidateCreations + 1
        cells.getValue(refs.candidate).shouldBeInstanceOf<SummerV2>()
        recovered.host.portAt(refs.incumbent, "inlet") shouldBe null
        collector(refs).received shouldBe expected
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a recovered red gate suppresses replayed history before reopening`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(911, journal)
        build(before, refs)
        drive(before, refs, 1..5, Random(911))
        val preCrash = collector(refs).received.toList()
        preCrash shouldBe listOf(1L, 3L, 6L, 10L, 15L)

        val recovered = world(911, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash

        feed(recovered, refs, 6)
        recovered.controller.runToIdle()
        gate(recovered, refs).controlInlet.call.setGreen()
        recovered.controller.runToIdle()

        collector(refs).received shouldBe preCrash + 21L
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a recovered red gate remains exactly once across a post-recovery checkpoint`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(911, journal)
        build(before, refs)
        drive(before, refs, 1..5, Random(911))
        val preCrash = collector(refs).received.toList()
        preCrash shouldBe listOf(1L, 3L, 6L, 10L, 15L)

        val firstRecovered = world(911, journal)
        val firstRecovery = firstRecovered.context.recover(journal)
        firstRecovered.controller.runToIdle()
        firstRecovery.awaitApplied(30_000)
        firstRecovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash

        firstRecovered.host.checkpoint(journal)

        val recovered = world(911, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash

        gate(recovered, refs).controlInlet.call.setGreen()
        recovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash

        feed(recovered, refs, 6)
        recovered.controller.runToIdle()
        collector(refs).received shouldBe preCrash + 21L
        firstRecovered.deadLetters.shouldBeEmpty()
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a checkpoint carries live red gate work across a crash`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(912, journal)
        build(before, refs)
        gate(before, refs).controlInlet.call.setRed()
        feed(before, refs, 1)
        before.controller.runToIdle()
        collector(refs).received.shouldBeEmpty()

        before.host.checkpoint(journal)

        val recovered = world(912, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()
        collector(refs).received.shouldBeEmpty()

        gate(recovered, refs).controlInlet.call.setGreen()
        recovered.controller.runToIdle()
        collector(refs).received shouldBe listOf(1L)
        feed(recovered, refs, 2)
        recovered.controller.runToIdle()
        collector(refs).received shouldBe listOf(1L, 3L)
        before.deadLetters.shouldBeEmpty()
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a checkpoint carries a red gate owned payload without dropping or duplicating it`() {
        val journal = InMemoryJournal()
        val gateRef = CellRef(UUID.randomUUID())
        val sinkRef = CellRef(UUID.randomUUID())
        val before = world(914, journal)
        graph(before.context) {
            val gate = spawn("owned-gate", IdentityBinding.Exact(gateRef), factory = OwnedGateFactory)
            val sink = spawn(
                "owned-sink", IdentityBinding.Exact(sinkRef), journalId = "j", factory = OwnedSinkFactory,
            )
            connect(gate, "dataOutlet", sink, "inlet", LinkOptions(staged = true))
        }
        before.controller.runToIdle()

        @Suppress("UNCHECKED_CAST")
        val gate = cells.getValue(gateRef) as TrafficLightCell<ShadowOwnedPush>
        gate.dataInlet.call.push(Owned("parked-owned"))
        before.host.checkpoint(journal)

        val recovered = world(914, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()
        val sink = cells.getValue(sinkRef) as OwnedSink
        sink.taken.shouldBeEmpty()

        @Suppress("UNCHECKED_CAST")
        val recoveredGate = cells.getValue(gateRef) as TrafficLightCell<ShadowOwnedPush>
        recoveredGate.controlInlet.call.setGreen()
        recoveredGate.controlInlet.call.setGreen()
        recovered.controller.runToIdle()

        sink.taken shouldBe listOf("parked-owned")
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a journaled promotion commits with traffic parked in its red window`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(913, journal)
        build(before, refs)
        gate(before, refs).controlInlet.call.setRed()
        gate(before, refs).dataInlet.call.provide(7)

        before.context.promote(
            gate = refs.gate,
            incumbent = refs.incumbent,
            candidate = refs.candidate,
            outletName = "outlet",
            downstream = listOf(refs.collector to "inlet"),
        )
        before.controller.runToIdle()

        collector(refs).received shouldBe listOf(7L)
        before.host.portAt(refs.incumbent, "inlet") shouldBe null
        cells.getValue(refs.candidate).shouldBeInstanceOf<SummerV2>()
        before.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a recovered promoted gate stays green across checkpoint compaction`() {
        listOf(false, true).forEach { compacted ->
            withClue("post-promotion checkpoint compacted=$compacted") {
                val (recovered, refs, preCrash) = recoverPromoted(compacted)

                gate(recovered, refs).snapshot() shouldBe true
                gate(recovered, refs).controlInlet.call.setGreen()
                recovered.controller.runToIdle()
                collector(refs).received shouldBe preCrash

                feed(recovered, refs, 10)
                recovered.controller.runToIdle()
                collector(refs).received shouldBe preCrash + 55L
                recovered.deadLetters.shouldBeEmpty()
            }
        }
    }

    /**
     * The shadow candidate normally sees the incumbent's whole input history, so its own
     * checkpointed state equals the transferred one and cannot tell replay-side transfer from
     * re-derivation. Here the incumbent alone absorbs one extra input before the swap: only the
     * T0/T1 transfer (live at COMMIT, and again when replay applies the Promote record) carries it.
     */
    @Test
    fun `recovery carries the transferred incumbent state when the shadow history differs`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(501, journal)
        build(before, refs)
        drive(before, refs, 1..5, Random(501))
        incumbent(refs).inlet.call.provide(100)
        before.controller.runToIdle()
        before.context.promote(
            gate = refs.gate,
            incumbent = refs.incumbent,
            candidate = refs.candidate,
            outletName = "outlet",
            downstream = listOf(refs.collector to "inlet"),
        )
        drive(before, refs, 6..8, Random(502))
        // 1..5 = 15, +100 on the incumbent only = 115, then the candidate continues: 121, 128, 136.
        val preCrash = collector(refs).received.toList()
        preCrash shouldBe listOf(1L, 3L, 6L, 10L, 15L, 115L, 121L, 128L, 136L)

        val recovered = world(501, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        recovered.controller.runToIdle()

        collector(refs).received shouldBe preCrash
        candidate(refs).inlet.call.provide(9)
        recovered.controller.runToIdle()
        collector(refs).received.last() shouldBe 145L
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a failed state import journals no promotion and recovers the incumbent topology`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(201, journal)
        build(before, refs, failingCandidate = true)
        drive(before, refs, 1..1, Random(201))
        val topologyBefore = topologyShape(before.context.live())

        shouldThrow<Promotion.PromotionAborted> {
            before.context.promote(
                gate = refs.gate,
                incumbent = refs.incumbent,
                candidate = refs.candidate,
                outletName = "outlet",
                downstream = listOf(refs.collector to "inlet"),
            )
        }
        topologyShape(before.context.live()) shouldBe topologyBefore
        promotionRecords(decoded(journal)).shouldBeEmpty()
        drive(before, refs, 2..2, Random(202))
        collector(refs).received shouldBe listOf(1L, 3L)

        val recovered = world(201, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        cells.getValue(refs.incumbent).shouldBeInstanceOf<SummerV1>()
        recovered.context.live().spawns.getValue(refs.candidate).shadow shouldBe true
        topologyShape(recovered.context.live()) shouldBe topologyBefore
        val recoveredCount = collector(refs).received.size
        incumbent(refs).inlet.call.provide(3)
        recovered.controller.runToIdle()
        collector(refs).received.size shouldBe recoveredCount + 1
        collector(refs).received.last() shouldBe 6L
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `an invariant rejection journals no promotion and recovers the incumbent serving`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(301, journal)
        build(before, refs)
        val invariant = cells.getValue(refs.invariant)
            as InvariantCell<Long, Pair<Long, Long>>
        val judge = PromotionJudge(
            PromotionPolicy(
                gates = listOf(invariant.name),
                window = ObservationWindow(waves = 2),
                judge = "candidate-sum-judge",
            ),
        )
        val violations = mutableListOf<Violation>()
        invariant.violations.subscribe(Use.fixed(Propagate { violation ->
            violations += violation
            judge.observeCandidateViolation(violation)
        }, PortRef.generate()))

        listOf(2, -3).forEach { input ->
            feed(before, refs, input)
            before.controller.runToIdle()
            judge.observeCandidateWave()
        }
        violations.isNotEmpty().shouldBeTrue()
        val topologyBefore = topologyShape(before.context.live())
        shouldThrow<Promotion.PromotionAborted> {
            before.context.promote(
                gate = refs.gate,
                incumbent = refs.incumbent,
                candidate = refs.candidate,
                outletName = "outlet",
                downstream = listOf(refs.collector to "inlet"),
                judge = judge,
            )
        }
        topologyShape(before.context.live()) shouldBe topologyBefore
        promotionRecords(decoded(journal)).shouldBeEmpty()
        collector(refs).received shouldBe listOf(2L, -1L)

        val recovered = world(301, journal)
        val recovery = recovered.context.recover(journal)
        recovered.controller.runToIdle()
        recovery.awaitApplied(30_000)
        incumbent(refs)
        recovered.context.live().spawns.getValue(refs.candidate).shadow shouldBe true
        val recoveredCount = collector(refs).received.size
        incumbent(refs).inlet.call.provide(4)
        recovered.controller.runToIdle()
        collector(refs).received.size shouldBe recoveredCount + 1
        collector(refs).received.last() shouldBe 3L
        recovered.deadLetters.shouldBeEmpty()
    }

    @Test
    fun `a volatile candidate is refused before a journaled incumbent is touched`() {
        val journal = InMemoryJournal()
        val refs = refs()
        val before = world(401, journal)
        build(before, refs, candidateJournalId = null)
        drive(before, refs, 1..2, Random(401))
        val topologyBefore = topologyShape(before.context.live())
        val recordCountBefore = journal.replay().count()

        shouldThrow<Promotion.PromotionAborted> {
            before.context.promote(
                gate = refs.gate,
                incumbent = refs.incumbent,
                candidate = refs.candidate,
                outletName = "outlet",
                downstream = listOf(refs.collector to "inlet"),
            )
        }

        topologyShape(before.context.live()) shouldBe topologyBefore
        journal.replay().count() shouldBe recordCountBefore
        promotionRecords(decoded(journal)).shouldBeEmpty()
        collector(refs).received shouldBe listOf(1L, 3L)
        cells.getValue(refs.incumbent).shouldBeInstanceOf<SummerV1>()
    }
}
