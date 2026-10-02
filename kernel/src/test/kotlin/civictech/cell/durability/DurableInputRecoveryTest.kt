package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Owned
import civictech.cell.Stateful
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Durable-input kill points (`computenet-12qyp.1`, decisions D1-D3): the external cursor and
 * every frame in its batch are one journal record, so recovery sees all or none of the batch.
 */
class DurableInputRecoveryTest {

    interface FoldProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface ExclusiveProxy {
        val inlet: Use<Consumer<Owned<String>>>
    }

    private class FoldCell(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val received = mutableListOf<Int>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            received.clear()
            received += state as List<Int>
        }
    }

    private class ExclusiveCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Owned<String>>>())
        val received = mutableListOf<String>()

        init {
            inlet.serve(object : Consumer<Owned<String>> {
                override fun provide(input: Owned<String>) {
                    received += input.take()
                }
            })
        }
    }

    private class World(
        val controller: SimulationController,
        val journal: Journal,
        val ref: CellRef = INPUT_REF,
    ) {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = FoldCell(ref)
        val proxy: FoldProxy
        val input: civictech.cell.host.DurableInput

        init {
            host.managementInlet.call.spawn(cell)
            proxy = HostedCellProxy.create(ref, host, FoldProxy::class.java) as FoldProxy
            input = host.durableInput(ref, INPUT_NAME)
        }

        fun driveBatch(index: Int): Int = input.commit {
            BATCHES[index].forEach(proxy.inlet.call::provide)
            index
        } as Int

        fun resume() {
            val first = ((input.committed() as? Int) ?: -1) + 1
            for (index in first until BATCHES.size) driveBatch(index)
            controller.runToIdle()
        }
    }

    /** A crash inside append: put a length prefix plus half the record on disk, then fail. */
    private class TearNextAppendJournal(
        private val delegate: Journal,
        private val file: File,
    ) : Journal {
        override val formatVersion: Int get() = delegate.formatVersion
        override val durability: DurabilityClass get() = delegate.durability
        var tearNext = false

        override fun append(record: ByteArray) {
            if (!tearNext) return delegate.append(record)
            tearNext = false
            val framed = ByteBuffer.allocate(Int.SIZE_BYTES + record.size)
                .putInt(record.size)
                .put(record)
                .array()
            FileOutputStream(file, true).use { output ->
                output.write(framed, 0, framed.size / 2)
                output.fd.sync()
            }
            throw IOException("simulated torn durable-input record")
        }

        override fun replay(): List<ByteArray> = delegate.replay()
        override fun reset(records: List<ByteArray>) = delegate.reset(records)
    }

    private fun recover(file: File): World {
        val world = World(SimulationController(seed = 12), FileJournal(file))
        val recovery = world.host.recoverFrom(world.journal)
        world.controller.runToIdle()
        recovery.awaitApplied()
        world.resume()
        world.input.committed() shouldBe 2
        world.cell.received shouldBe BATCHES.flatten()
        return world
    }

    @Test
    fun `K1 kill after reading but before committing resumes at the last committed cursor`(@TempDir dir: File) {
        val file = File(dir, "input.journal")
        val live = World(SimulationController(seed = 1), FileJournal(file))
        live.driveBatch(0)
        live.controller.runToIdle()
        BATCHES[1] // the source read the next batch, but never entered commit before the crash

        recover(file)
    }

    @Test
    fun `K2 a torn commit record restores neither its cursor nor any of its frames`(@TempDir dir: File) {
        val file = File(dir, "input.journal")
        val journal = TearNextAppendJournal(FileJournal(file), file)
        val live = World(SimulationController(seed = 2), journal)
        live.driveBatch(0)
        live.controller.runToIdle()
        journal.tearNext = true

        shouldThrow<IOException> { live.driveBatch(1) }
            .message shouldContain "torn durable-input"
        live.input.committed() shouldBe 0

        recover(file)
    }

    @Test
    fun `K3 kill after commit before delivery replays the batch once`(@TempDir dir: File) {
        val file = File(dir, "input.journal")
        val live = World(SimulationController(seed = 3), FileJournal(file))
        live.driveBatch(0)
        live.controller.runToIdle()
        live.driveBatch(1)
        live.input.committed() shouldBe 1
        live.cell.received shouldBe BATCHES[0]
        // No scheduler step: batch 1 is durable but none of its frames was delivered.

        recover(file)
    }

    @Test
    fun `K4 checkpoint carries the cursor and a later committed batch remains in the tail`(@TempDir dir: File) {
        val file = File(dir, "input.journal")
        val live = World(SimulationController(seed = 4), FileJournal(file))
        live.driveBatch(0)
        live.controller.runToIdle()
        live.driveBatch(1)
        live.controller.runToIdle()
        live.host.checkpoint(live.journal)
        live.driveBatch(2)
        // No scheduler step: the checkpoint holds batches 0+1, and the tail input holds batch 2.

        val inputs = live.journal.replay()
            .map(JournalRecords::decode)
            .filterIsInstance<DecodedJournalRecord.Input>()
        inputs.map { it.cursor } shouldBe listOf(1, 2)
        inputs.map { it.frames.size } shouldBe listOf(0, 1)

        recover(file)
    }

    @Test
    fun `a batch spanning two cell journals is refused without journaling or staging`() {
        val firstJournal = InMemoryJournal()
        val secondJournal = InMemoryJournal()
        val controller = SimulationController(seed = 5)
        val firstRef = CellRef(UUID(5, 1))
        val secondRef = CellRef(UUID(5, 2))
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journalFor = { ref -> if (ref == firstRef) firstJournal else secondJournal },
        )
        val first = FoldCell(firstRef)
        val second = FoldCell(secondRef)
        host.managementInlet.call.spawn(first)
        host.managementInlet.call.spawn(second)
        val firstProxy = HostedCellProxy.create(firstRef, host, FoldProxy::class.java) as FoldProxy
        val secondProxy = HostedCellProxy.create(secondRef, host, FoldProxy::class.java) as FoldProxy
        val input = host.durableInput(firstRef, "mixed")

        val refusal = shouldThrow<IllegalArgumentException> {
            input.commit {
                firstProxy.inlet.call.provide(1)
                secondProxy.inlet.call.provide(2)
                0
            }
        }
        refusal.message shouldContain secondRef.toString()
        refusal.message shouldContain "inlet"
        firstJournal.replay().shouldBeEmpty()
        secondJournal.replay().shouldBeEmpty()
        controller.runToIdle()
        first.received.shouldBeEmpty()
        second.received.shouldBeEmpty()
        input.committed() shouldBe null
    }

    @Test
    fun `a throwing drive journals and stages nothing and discharges captured exclusives`() {
        val journal = InMemoryJournal()
        val controller = SimulationController(seed = 6)
        val ref = CellRef(UUID(6, 1))
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = ExclusiveCell(ref)
        host.managementInlet.call.spawn(cell)
        val proxy = HostedCellProxy.create(ref, host, ExclusiveProxy::class.java) as ExclusiveProxy
        val input = host.durableInput(ref, "exclusive")
        val owned = Owned("payload")

        shouldThrow<IllegalStateException> {
            input.commit {
                proxy.inlet.call.provide(owned)
                error("source failed")
            }
        }.message shouldContain "source failed"

        journal.replay().shouldBeEmpty()
        controller.runToIdle()
        cell.received.shouldBeEmpty()
        shouldThrow<IllegalStateException> { owned.take() }
    }

    @Test
    fun `a re-entrant commit on the same host is refused`() {
        val journal = InMemoryJournal()
        val host = ManagedHost(journal = journal)
        val cell = FoldCell(INPUT_REF)
        host.managementInlet.call.spawn(cell)
        val input = host.durableInput(INPUT_REF, INPUT_NAME)

        shouldThrow<IllegalStateException> {
            input.commit { input.commit { 0 }; 0 }
        }.message shouldContain "re-entrant"
        journal.replay().shouldBeEmpty()
    }

    @Test
    fun `durableInput refuses a volatile cell by name`() {
        val host = ManagedHost()
        host.managementInlet.call.spawn(FoldCell(INPUT_REF))

        val refusal = shouldThrow<IllegalStateException> { host.durableInput(INPUT_REF, INPUT_NAME) }
        refusal.message shouldContain INPUT_REF.toString()
        refusal.message shouldContain INPUT_NAME
    }

    private companion object {
        val BATCHES = listOf(listOf(1, 2), listOf(3, 4, 5), listOf(6))
        val INPUT_REF = CellRef(UUID.nameUUIDFromBytes("durable-input".encodeToByteArray()))
        const val INPUT_NAME = "spend"
    }
}
