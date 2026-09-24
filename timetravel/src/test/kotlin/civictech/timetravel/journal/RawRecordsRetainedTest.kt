package civictech.timetravel.journal

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.durability.scanJournalFile
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * TTD1 F4 (computenet-6tm33.1, feature D6): [JournalReading.rawRecords] retains each journal's
 * raw record bytes, index-aligned with [JournalReading.records].
 */
class RawRecordsRetainedTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /**
     * One `SetCell<String>` journaled to [journal] via a real durable host: three adds, a
     * checkpoint, then one more add — so the journal reads `[checkpoint, outletWave…, frame]`.
     */
    private fun writeJournal(journal: Journal, seed: Long = 71): CellRef {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(3) { api.add("e$it") }
        controller.runToIdle()
        host.checkpoint(journal)
        api.add("e3")
        controller.runToIdle()
        return ref
    }

    @Test
    fun `a File source's rawRecords are content-equal to the file scan's records, index-aligned`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "j.bin")
        writeJournal(FileJournal(file))

        val reading = JournalReader.open(JournalSource.File(file))
        val scanRecords = scanJournalFile(file).records
        val summary = reading.journals.single()
        summary.recordCount shouldBe scanRecords.size

        val raw = reading.rawRecords.getValue(file.path)
        raw.size shouldBe summary.recordCount
        raw.indices.forEach { k -> raw[k] shouldBe scanRecords[k] }
    }

    @Test
    fun `an InMemory source's rawRecords are content-equal to the journal's replay, index-aligned`() {
        val journal = InMemoryJournal()
        writeJournal(journal)

        val reading = JournalReader.open(JournalSource.InMemory(journal, "j"))
        val replay = journal.replay()
        val summary = reading.journals.single()
        summary.recordCount shouldBe replay.size

        val raw = reading.rawRecords.getValue("j")
        raw.size shouldBe summary.recordCount
        raw.indices.forEach { k -> raw[k] shouldBe replay[k] }
    }

    @Test
    fun `a Directory source's rawRecords has one entry per summary, a refused entry mapping to empty`(
        @TempDir dir: File,
    ) {
        val readable = File(dir, "a.bin")
        writeJournal(FileJournal(readable), seed = 72)
        // Deterministic refusal: an unreadable directory entry never reaches scanJournalFile and
        // is reported as a refused summary with no records (readDirectoryEntry's canRead guard).
        // Skipped instead of failing when the process runs as root, where setReadable(false) is a
        // no-op and the file stays readable.
        val unreadable = File(dir, "b_unreadable.bin")
        writeJournal(FileJournal(unreadable), seed = 73)
        val refusalTakesEffect = unreadable.setReadable(false) && !unreadable.canRead()

        val reading = JournalReader.open(JournalSource.Directory(dir))
        reading.journals.map { it.journalId }.toSet() shouldBe reading.rawRecords.keys

        val readableSummary = reading.journals.single { it.journalId == readable.path }
        reading.rawRecords.getValue(readable.path).size shouldBe readableSummary.recordCount

        if (refusalTakesEffect) {
            val refusedSummary = reading.journals.single { it.journalId == unreadable.path }
            refusedSummary.refusal shouldBe "not a readable regular file"
            reading.rawRecords.getValue(unreadable.path).shouldBeEmpty()
        }
        unreadable.setReadable(true) // leave the temp dir cleanly removable
    }

    @Test
    fun `a torn journal's rawRecords hold exactly the intact records, matching the scan and the record count`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "torn.bin")
        writeJournal(FileJournal(file))
        val sizes = FileJournal(file).replay().map { it.size }
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        RandomAccessFile(file, "rw").use { it.setLength(offsetOfLast + 2) }

        val scan = scanJournalFile(file)
        (scan.tear != null) shouldBe true // non-vacuity: the file really is torn

        val reading = JournalReader.open(JournalSource.File(file))
        val summary = reading.journals.single()
        val raw = reading.rawRecords.getValue(file.path)

        raw.size shouldBe scan.records.size
        raw.size shouldBe summary.recordCount
        raw.indices.forEach { k -> raw[k] shouldBe scan.records[k] }
    }
}
