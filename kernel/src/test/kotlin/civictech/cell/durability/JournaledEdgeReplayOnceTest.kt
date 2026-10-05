package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.wire.WireCodec
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * qfi22-D7/D8: a journaled edge's replayed tail is delivered once
 * (`[22-REC-01]`, `[24-DUR-03]`).
 *
 * A journal shared by [RelayCell] and [SinkCell] contains both the relay's
 * accepted root frames and the relay-to-sink frames they produced. Recovery
 * replays the latter directly and re-derives them from the former. The host
 * intake suppresses only a re-derived frame whose exact position was already
 * replayed for that target port; the sink is deliberately non-idempotent so a
 * duplicate cannot hide in a merge.
 */
class JournaledEdgeReplayOnceTest {

    private class RelayCell(override val ref: CellRef) : Cell, Stateful {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.call.provide(input)
                }
            })
        }

        // The relay has no fold beyond its durable outlet epoch. An explicit
        // empty snapshot makes checkpoint compaction honest while leaving the
        // non-idempotent sink deliberately non-Stateful.
        override fun snapshot(): Serializable = 0

        override fun restore(state: Serializable) {
            require(state == 0) { "unexpected relay snapshot: $state" }
        }
    }

    private class SinkCell(override val ref: CellRef, deliveries: MutableList<Int>) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    deliveries += input
                }
            })
        }
    }

    private interface RelayProxy {
        val inlet: Use<Consumer<Int>>
    }

    private interface SinkProxy {
        val inlet: Use<Consumer<Int>>
    }

    private class World(
        controller: SimulationController,
        journal: InMemoryJournal,
        relayRef: CellRef,
        sinkRef: CellRef,
        deliveries: MutableList<Int>,
        edgeCopies: Int = 1,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        private val relay = RelayCell(relayRef)
        private val sink = SinkCell(sinkRef, deliveries)

        init {
            host.managementInlet.call.spawn(sink)
            host.managementInlet.call.spawn(relay)
            val sinkInlet = (HostedCellProxy.create(sinkRef, host, SinkProxy::class.java) as SinkProxy)
                .inlet.call
            repeat(edgeCopies) {
                relay.outlet.subscribe(Use.fixed(sinkInlet, PortRef.generate()))
            }
        }

        fun feed(value: Int) {
            (HostedCellProxy.create(relay.ref, host, RelayProxy::class.java) as RelayProxy)
                .inlet.call.provide(value)
        }
    }

    private fun frameCount(journal: Journal): Int =
        journal.replay().count { JournalRecords.decode(it) is DecodedJournalRecord.Frame }

    private fun withoutLastSinkFrame(journal: Journal, sinkRef: CellRef): InMemoryJournal {
        val records = journal.replay()
        val omitted = records.indexOfLast { record ->
            val decoded = JournalRecords.decode(record)
            decoded is DecodedJournalRecord.Frame && WireCodec.decode(decoded.payload).let { frame ->
                frame.cellRef == sinkRef && frame.portName == "inlet"
            }
        }
        check(omitted >= 0) { "journal contains no sink frame to omit" }
        return InMemoryJournal().also { copy ->
            records.forEachIndexed { index, record ->
                if (index != omitted) copy.append(record)
            }
        }
    }

    @Test
    fun `no checkpoint - each replayed tail frame reaches the non-idempotent sink exactly once`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val journal = InMemoryJournal()
            val deliveries = mutableListOf<Int>()
            val relayRef = CellRef(UUID(seed, 1L))
            val sinkRef = CellRef(UUID(seed, 2L))

            val before = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            (1..3).forEach(before::feed)
            controller.runToIdle()
            deliveries shouldBe listOf(1, 2, 3)

            // The external observation log survives the crash; clear its live-run
            // entries so this assertion isolates deliveries into the new sink instance.
            deliveries.clear()
            val framesBeforeRecovery = frameCount(journal)
            val after = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            val recovery = after.host.recoverFrom(journal)
            controller.runToIdle()

            deliveries shouldBe listOf(1, 2, 3)
            recovery.suppressedReplayDuplicates shouldBe 3
            recovery.isApplied.shouldBeTrue()
            after.host.supervisionAccounting().deadLetters shouldBe 0L
            frameCount(journal) shouldBe framesBeforeRecovery
        }
    }

    @Test
    fun `checkpoint - the tail frame reaches the sink exactly once`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val journal = InMemoryJournal()
            val deliveries = mutableListOf<Int>()
            val relayRef = CellRef(UUID(seed, 1L))
            val sinkRef = CellRef(UUID(seed, 2L))

            val before = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            before.feed(1)
            before.feed(2)
            controller.runToIdle()
            before.host.checkpoint(journal)
            before.feed(3)
            controller.runToIdle()

            deliveries.clear()
            val after = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            val recovery = after.host.recoverFrom(journal)
            controller.runToIdle()

            deliveries shouldBe listOf(3)
            recovery.suppressedReplayDuplicates shouldBe 1

            after.feed(4)
            controller.runToIdle()
            deliveries shouldBe listOf(3, 4)
            recovery.suppressedReplayDuplicates shouldBe 1
        }
    }

    @Test
    fun `crash window - a derived frame the journal never recorded is delivered once from the re-derivation`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val journal = InMemoryJournal()
            val deliveries = mutableListOf<Int>()
            val relayRef = CellRef(UUID(seed, 1L))
            val sinkRef = CellRef(UUID(seed, 2L))

            val before = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            (1..3).forEach(before::feed)
            controller.runToIdle()

            val crashWindowJournal = withoutLastSinkFrame(journal, sinkRef)
            deliveries.clear()
            val after = World(controller, crashWindowJournal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            val recovery = after.host.recoverFrom(crashWindowJournal)
            controller.runToIdle()

            deliveries shouldBe listOf(1, 2, 3)
            recovery.suppressedReplayDuplicates shouldBe 2
        }
    }

    @Test
    fun `crash window - repeated exact positions retain their legitimate edge multiplicity`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val journal = InMemoryJournal()
            val deliveries = mutableListOf<Int>()
            val relayRef = CellRef(UUID(seed, 1L))
            val sinkRef = CellRef(UUID(seed, 2L))

            val before = World(controller, journal, relayRef, sinkRef, deliveries, edgeCopies = 2)
            controller.runToIdle()
            (1..3).forEach(before::feed)
            controller.runToIdle()
            deliveries shouldBe listOf(1, 1, 2, 2, 3, 3)

            // One of the two edge copies for value 3 is absent. Recovery must
            // suppress only the five copies J already holds and re-derive the sixth.
            val crashWindowJournal = withoutLastSinkFrame(journal, sinkRef)
            deliveries.clear()
            val after = World(
                controller,
                crashWindowJournal,
                relayRef,
                sinkRef,
                deliveries,
                edgeCopies = 2,
            )
            controller.runToIdle()
            val recovery = after.host.recoverFrom(crashWindowJournal)
            controller.runToIdle()

            deliveries shouldBe listOf(1, 1, 2, 2, 3, 3)
            recovery.suppressedReplayDuplicates shouldBe 5
        }
    }

    @Test
    fun `control - with the predicate disabled the sink counts the tail twice`() {
        forEachSeed(0L until 20L) { seed ->
            val controller = SimulationController(seed)
            val journal = InMemoryJournal()
            val deliveries = mutableListOf<Int>()
            val relayRef = CellRef(UUID(seed, 1L))
            val sinkRef = CellRef(UUID(seed, 2L))

            val before = World(controller, journal, relayRef, sinkRef, deliveries)
            controller.runToIdle()
            (1..3).forEach(before::feed)
            controller.runToIdle()

            deliveries.clear()
            val after = World(controller, journal, relayRef, sinkRef, deliveries)
            after.host.suppressReplayedDuplicates = false
            controller.runToIdle()
            val recovery = after.host.recoverFrom(journal)
            controller.runToIdle()

            deliveries shouldBe listOf(1, 2, 3, 1, 2, 3)
            recovery.suppressedReplayDuplicates shouldBe 0
        }
    }
}
