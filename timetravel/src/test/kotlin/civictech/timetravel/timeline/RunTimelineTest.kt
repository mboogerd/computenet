package civictech.timetravel.timeline

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.data.SetOps
import civictech.timetravel.journal.CheckpointRecord
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.OutletWaveRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * TTD1 F2 (computenet-56io0.1): [RunTimeline] on a real checkpointed [SetCell] journal —
 * [TTD1-15], [TTD1-17], [TTD1-19], the `Index` bounds rule, `labels`, and 56io0-D5 (`of`).
 */
class RunTimelineTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Written(val ref: CellRef, val wavesAtCheckpoint: Map<String, Pair<UUID, Long>>)

    /**
     * One `SetCell<String>` journaled to [journal]: three proxy adds, a checkpoint, then one more
     * add — same shape as `JournalReaderTest.writeJournal(checkpoint = true)`, not imported
     * because it is private there.
     */
    private fun writeJournal(journal: Journal, seed: Long = 7): Written {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(3) { api.add("e$it") }
        controller.runToIdle()
        host.checkpoint(journal)
        val ports = PortRegistry.of(cell)
        val waves = ports.names().mapNotNull { name ->
            (ports[name] as? FanOutlet<*>)?.waveState()?.let { name to (it.sourceId to it.highWater) }
        }.toMap()
        api.add("e3")
        controller.runToIdle()
        return Written(ref, waves)
    }

    private fun read(journal: Journal): List<civictech.timetravel.journal.JournalRecord> =
        JournalReader.open(JournalSource.InMemory(journal, "j")).records.toList()

    @Test
    fun `TTD1-15 one TimelinePosition per record with wave and touches`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal)
        val records = read(journal)
        val timeline = RunTimeline("j", records)

        timeline.positions.size shouldBe records.size
        timeline.positions.forEachIndexed { index, position ->
            position.index shouldBe index
            (position.record === records[index]) shouldBe true
        }
        timeline.positions[0].touches shouldBe setOf(written.ref)
        timeline.positions.filter { it.record is OutletWaveRecord }.forEach { it.touches shouldBe setOf(written.ref) }
        timeline.positions.filter { it.record is FrameRecord }.forEach { it.touches shouldBe setOf(written.ref) }
    }

    @Test
    fun `TTD1-17 TTD1-19 a contextless proxy frame has no wave, a checkpoint at record 0 is the only anchor`() {
        val journal = InMemoryJournal()
        writeJournal(journal)
        val records = read(journal)
        val timeline = RunTimeline("j", records)
        val last = timeline.size - 1

        val lastPosition = timeline.positions[last]
        lastPosition.record.shouldBeInstanceOf<FrameRecord>()
        lastPosition.wave shouldBe null
        timeline.resolve(Position.Index(last)) shouldBe timeline.size
        timeline.frontierAt(last).values.shouldBeEmpty()
        timeline.labels(last) shouldContain "#$last"
        timeline.labels(last) shouldContain "inlet"
        timeline.labels(last) shouldNotContain "wave"

        timeline.positions[0].isAnchor shouldBe true
        (records[0] is CheckpointRecord) shouldBe true
        timeline.positions.drop(1).forEach { it.isAnchor shouldBe false }
        (0 until timeline.size).forEach { k -> timeline.nearestAnchorAtOrBefore(k) shouldBe 0 }
    }

    @Test
    fun `resolve Index bounds throw IndexOutOfBoundsException naming the total`() {
        val journal = InMemoryJournal()
        writeJournal(journal)
        val timeline = RunTimeline("j", read(journal))
        val total = timeline.size

        val low = shouldThrow<IndexOutOfBoundsException> { timeline.resolve(Position.Index(-1)) }
        low.message!! shouldContain total.toString()
        val high = shouldThrow<IndexOutOfBoundsException> { timeline.resolve(Position.Index(total)) }
        high.message!! shouldContain total.toString()
    }

    @Test
    fun `56io0-D5 of yields one timeline per journal, keyed by journalId, holding only its own records`(
        @TempDir dir: File,
    ) {
        val b = writeJournal(FileJournal(File(dir, "b.bin")), seed = 2)
        val a = writeJournal(FileJournal(File(dir, "a.bin")), seed = 1)

        val reading = JournalReader.open(JournalSource.Directory(dir))
        val timelines = RunTimeline.of(reading)

        timelines.keys.toList() shouldBe reading.journals.map { it.journalId }
        reading.journals.forEach { summary ->
            val timeline = timelines.getValue(summary.journalId)
            timeline.journalId shouldBe summary.journalId
            timeline.size shouldBe summary.recordCount
            timeline.positions.forEach { it.record.journalId shouldBe summary.journalId }
        }
        timelines.values.flatMap { it.positions }.mapNotNull { (it.record as? FrameRecord)?.cellRef }.toSet() shouldBe
            setOf(a.ref, b.ref)
    }
}
