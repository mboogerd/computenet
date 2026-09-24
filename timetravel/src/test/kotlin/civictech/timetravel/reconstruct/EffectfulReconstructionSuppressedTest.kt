package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.durability.InMemoryJournal
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.nature.ContractDescriptor
import civictech.nature.ContractModule
import civictech.nature.ContractRegistry
import civictech.nature.MethodDescriptor
import civictech.nature.ModuleId
import civictech.nature.StableHash
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.FrontierRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * TTD1 F5 (computenet-yhvlz.1) BS-7: an offline reconstruction NoOp-serves effect inlets through
 * `Shadow` before replay, so it never acts on the world a second time (`[TTD1-26]`), and an
 * `Effectful` cell's reconstructed state is the checkpoint's, not the run's, at most
 * `Degraded(EFFECTFUL_CELL)` (`[TTD1-27]`). Fixture: yhvlz-D10; the effect-contract half without
 * KSP: yhvlz-D11.
 *
 * Why the burst: the three acted-on frames are suppressed on replay by the kernel's own
 * processed-frontier whether or not reconstruction suppresses anything (`recoverFrom` applies
 * each `FrontierRecord` synchronously in its record loop, before the preceding frame is
 * delivered). Only frames journaled but never delivered — a crash-shaped, undrained burst — lie
 * past the restored frontier, so only they discriminate: with suppression removed the kernel
 * delivers them and `world` becomes `[1, 2, 3, 4, 5]`.
 */
class EffectfulReconstructionSuppressedTest {

    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        fun emit(n: Int) = outlet.call.provide(n)
    }

    /**
     * An effect-boundary sink that is also [Stateful]: every `provide` acts on the external
     * [world] and counts itself in [applied], its snapshot. `Stateful` is what makes "the
     * checkpoint's state, not the run's" observable as a `Reconstructed` value.
     */
    class CountingSink(override val ref: CellRef, private val world: MutableList<Int>) : Cell, Effectful, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        private var applied = 0

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    world += input
                    applied++
                }
            })
        }

        override fun snapshot(): Serializable = applied

        override fun restore(state: Serializable) {
            applied = state as Int
        }
    }

    interface SinkProxy {
        val inlet: Use<Consumer<Int>>
    }

    /** A plain interface with no generated descriptor: `:timetravel` has no KSP (yhvlz-D11). */
    interface TestEffectApi {
        fun fire(n: Int)
    }

    /** NOT `Effectful`: its inlet is suppressed only when its contract carries the effect bit. */
    class ContractEffectCell(override val ref: CellRef, private val world: MutableList<Int>) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<TestEffectApi>())

        init {
            inlet.serve(object : TestEffectApi {
                override fun fire(n: Int) {
                    world += n
                }
            })
        }
    }

    private class Recorded(
        val journal: InMemoryJournal,
        val world: MutableList<Int>,
        val sinkRef: CellRef,
        val sourceId: UUID,
    )

    /**
     * yhvlz-D10's script: three acted-on emits, drained; optionally a checkpoint; then a
     * two-frame burst that is journaled but never drained.
     */
    private fun record(checkpoint: Boolean): Recorded {
        val controller = SimulationController(seed = 7)
        val journal = InMemoryJournal()
        val world = mutableListOf<Int>()
        val sinkRef = CellRef(UUID(7, 1))
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry(), journal = journal)
        host.managementInlet.call.spawn(CountingSink(sinkRef, world))
        controller.runToIdle()

        val source = SourceCell()
        val sink = (HostedCellProxy.create(sinkRef, host, SinkProxy::class.java) as SinkProxy).inlet.call
        source.outlet.subscribe(Use.fixed(sink, PortRef.generate()))

        source.emit(1)
        source.emit(2)
        source.emit(3)
        controller.runToIdle()
        world shouldBe listOf(1, 2, 3)
        if (checkpoint) host.checkpoint(journal)

        source.emit(4)
        source.emit(5) // the burst: accepted and journaled, never drained — the "crash"

        return Recorded(journal, world, sinkRef, source.outlet.waveState().sourceId)
    }

    private fun reconstruct(recorded: Recorded): Pair<JournalReading, Reconstruction> {
        val reading = JournalReader.open(JournalSource.InMemory(recorded.journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        val graph = GraphSource { host ->
            val sink2 = CountingSink(recorded.sinkRef, recorded.world) // the SAME external world
            host.managementInlet.call.spawn(sink2)
            GraphBuild(listOf(sink2))
        }
        return reading to Reconstructor(reading, timeline, graph).stateAt(Position.Index(timeline.size - 1))
    }

    private fun assertEffectVerdict(reconstruction: Reconstruction, sinkRef: CellRef, snapshot: Int) {
        val cell = reconstruction.cells.getValue(sinkRef).shouldBeInstanceOf<CellReconstruction.Reconstructed>()
        cell.snapshot shouldBe snapshot
        cell.fidelity.shouldBeInstanceOf<Fidelity.Degraded>()
        cell.fidelity.reasons shouldContain Reason.EFFECTFUL_CELL
        reconstruction.details shouldContain EffectInletsSuppressed(sinkRef, setOf("inlet"))
        reconstruction.run.reasons shouldContain Reason.EFFECTFUL_CELL
    }

    @Test
    fun `reconstruction does not fire the undrained burst, and the sink's state is not the run's`() {
        val recorded = record(checkpoint = false)
        val (reading, reconstruction) = reconstruct(recorded)

        // non-vacuity: the journal holds the burst past the last applied frontier
        val records = reading.records.toList()
        val sinkFrames = records.filterIsInstance<FrameRecord>().filter { it.cellRef == recorded.sinkRef }
        sinkFrames shouldHaveSize 5
        sinkFrames.forEach { it.context shouldNotBe null }
        val frontiers = records.filterIsInstance<FrontierRecord>()
            .filter { it.cellRef == recorded.sinkRef && it.portName == "inlet" }
        frontiers shouldHaveSize 3
        records.takeLast(2).forEach { it.shouldBeInstanceOf<FrameRecord>() }

        recorded.world shouldBe listOf(1, 2, 3)
        assertEffectVerdict(reconstruction, recorded.sinkRef, snapshot = 0)

        // the run's applied frontier is still surfaced through the reader
        frontiers.map { it.timestamp } shouldBe sinkFrames.take(3).map { it.context!!.timestamp }
        frontiers.forEach { it.timestamp.sourceId shouldBe recorded.sourceId }
    }

    @Test
    fun `after a checkpoint the sink reconstructs to the checkpoint's state and the burst still does not fire`() {
        val recorded = record(checkpoint = true)
        val (_, reconstruction) = reconstruct(recorded)

        recorded.world shouldBe listOf(1, 2, 3)
        assertEffectVerdict(reconstruction, recorded.sinkRef, snapshot = 3)
    }

    @Test
    fun `an effect-contract inlet of a non-Effectful cell is NoOp-served and reported, and only when registered`() {
        // control first: no descriptor registered, so the contract carries no effect bit
        val controlWorld = mutableListOf<Int>()
        val control = ContractEffectCell(CellRef(UUID(7, 2)), controlWorld)
        EffectSuppression.apply(GraphBuild(listOf(control))).shouldBeEmpty()
        control.inlet.call.fire(7)
        controlWorld shouldBe listOf(7)

        val module = ModuleId("timetravel-effect-test")
        val fqn = TestEffectApi::class.java.name.replace('$', '.') // ContractRegistry.descriptor's key
        ContractRegistry.register(
            object : ContractModule {
                override val contracts = listOf(
                    ContractDescriptor(
                        contractId = StableHash.of(fqn),
                        fqn = fqn,
                        management = false,
                        effect = true,
                        methods = listOf(MethodDescriptor(StableHash.of("$fqn#fire(I)V"), "fire", "(I)V")),
                    ),
                )
            },
            module,
        )
        try {
            val world = mutableListOf<Int>()
            val cell = ContractEffectCell(CellRef(UUID(7, 3)), world)

            EffectSuppression.apply(GraphBuild(listOf(cell))) shouldBe mapOf(cell.ref to setOf("inlet"))
            cell.inlet.call.fire(7)
            world.shouldBeEmpty()
        } finally {
            ContractRegistry.unregister(module)
        }
    }
}
