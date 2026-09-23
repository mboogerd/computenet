package civictech.timetravel.journal

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.durability.JournalFileScan
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.timetravel.fidelity.Reason
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

/**
 * TTD1 F1 (computenet-wzbww.4): a journal file whose tail is torn — a partial length prefix, or
 * a full length prefix with a short payload — is presented as an intact prefix plus a reported
 * tear, never an exception (BS-5, `[TTD1-11]`). Byte offsets follow the on-disk layout stated on
 * this bead: `MAGIC(4) + version int(4)`, then `int length + bytes` per record, so record `i`
 * starts at `8 + Σ_{j<i}(4 + len_j)`.
 */
class TornJournalTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** Three adds on a fresh `SetCell`, journaled — nothing else, so every record is a frame. */
    private fun writeFrames(journal: Journal, count: Int = 3, seed: Long = 41): CellRef {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(count) { api.add("e$it") }
        controller.runToIdle()
        return ref
    }

    private fun assertTornPrefix(copy: File, n: Int, offsetOfLast: Long) {
        val reading = JournalReader.open(JournalSource.File(copy))
        val records = reading.records.toList()
        records.size shouldBe (n - 1)
        records.forEachIndexed { i, record ->
            record.index shouldBe i
            record.shouldBeInstanceOf<FrameRecord>()
        }
        val summary = reading.journals.single()
        summary.tear shouldBe JournalFileScan.Tear(n - 2, copy.length() - offsetOfLast)
        (Reason.JOURNAL_TORN in summary.reasons) shouldBe true
        (Reason.JOURNAL_TORN in reading.reasons) shouldBe true
        // the kernel replays the same prefix — index parity with recovery holds on a torn file
        FileJournal(copy).replay().size shouldBe (n - 1)
    }

    @Test
    fun `BS-5 a torn tail is reported with the intact prefix, on a partial length prefix and on a short payload`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "j.bin")
        writeFrames(FileJournal(file))
        val sizes = FileJournal(file).replay().map { it.size }
        val n = sizes.size
        n shouldBeGreaterThanOrEqual 3
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        val lastSize = sizes.last()
        lastSize shouldBeGreaterThanOrEqual 2

        // partial length prefix: only 2 of the last record's 4-byte length prefix survive
        val partial = File(dir, "partial.bin")
        Files.copy(file.toPath(), partial.toPath())
        RandomAccessFile(partial, "rw").use { it.setLength(offsetOfLast + 2) }
        assertTornPrefix(partial, n, offsetOfLast)

        // full length prefix, short payload: the length is intact but the payload is cut short
        val short = File(dir, "short.bin")
        Files.copy(file.toPath(), short.toPath())
        RandomAccessFile(short, "rw").use { it.setLength(offsetOfLast + 4 + 1) }
        assertTornPrefix(short, n, offsetOfLast)
    }

    @Test
    fun `BS-5 non-vacuity — an untruncated file has no tear and is never marked JOURNAL_TORN`(@TempDir dir: File) {
        val file = File(dir, "clean.bin")
        writeFrames(FileJournal(file))

        val reading = JournalReader.open(JournalSource.File(file))
        reading.journals.single().tear shouldBe null
        (Reason.JOURNAL_TORN in reading.reasons) shouldBe false
        reading.journals.single().reasons.shouldBeEmpty()
    }
}
