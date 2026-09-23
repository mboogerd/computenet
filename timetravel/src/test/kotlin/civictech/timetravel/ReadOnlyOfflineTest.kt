package civictech.timetravel

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.JournalUnreadable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * TTD1 F1 (computenet-wzbww.4): the reader half of BS-15, `[TTD1-12]` — the reader opens files
 * read-only and never writes, truncates, renames or locks a journal, and refuses an absent path
 * without creating anything.
 */
class ReadOnlyOfflineTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** Every record type the `SetCell` fixture can produce: frames, a checkpoint, outlet waves. */
    private fun writeFullJournal(journal: Journal, seed: Long = 61): CellRef {
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

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `TTD1-12 opening and fully, twice, reading a file and a directory changes nothing on disk`(
        @TempDir dir: File,
    ) {
        val journalsDir = File(dir, "journals")
        journalsDir.mkdirs()
        val file = File(journalsDir, "j.bin")
        writeFullJournal(FileJournal(file))

        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()
        val hashBefore = sha256(file)
        val listingBefore = journalsDir.listFiles()!!.map { it.name }.sorted()

        val fileReading = JournalReader.open(JournalSource.File(file))
        fileReading.records.toList() // hydrate
        fileReading.records.toList() // and again — re-iterable, still read-only

        val dirReading = JournalReader.open(JournalSource.Directory(journalsDir))
        dirReading.records.toList()
        dirReading.records.toList()
        dirReading.journals.map { it.journalId } // touch summaries too

        file.length() shouldBe sizeBefore
        file.lastModified() shouldBe mtimeBefore
        sha256(file) shouldBe hashBefore
        val listingAfter = journalsDir.listFiles()!!.map { it.name }.sorted()
        listingAfter shouldBe listingBefore
        listingAfter.any { it.endsWith(".tmp") || it.endsWith(".lock") }.shouldBeFalse()
    }

    @Test
    fun `TTD1-12 a non-existent file or directory path is refused without creating anything`(@TempDir dir: File) {
        val absentFile = File(dir, "absent")
        val refusal = shouldThrow<JournalUnreadable> {
            JournalReader.open(JournalSource.File(File(absentFile, "deeper/j.bin")))
        }
        refusal.path.endsWith("j.bin") shouldBe true
        absentFile.exists().shouldBeFalse()

        val absentDir = File(dir, "absent2")
        shouldThrow<JournalUnreadable> { JournalReader.open(JournalSource.Directory(absentDir)) }
        absentDir.exists().shouldBeFalse()
    }

    @Test
    fun `TTD1-12 a torn journal keeps its truncated length after being read`(@TempDir dir: File) {
        val file = File(dir, "torn.bin")
        writeFullJournal(FileJournal(file))
        val sizes = FileJournal(file).replay().map { it.size }
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        RandomAccessFile(file, "rw").use { it.setLength(offsetOfLast + 2) }
        val lengthBefore = file.length()

        JournalReader.open(JournalSource.File(file)).records.toList()

        file.length() shouldBe lengthBefore
    }
}
