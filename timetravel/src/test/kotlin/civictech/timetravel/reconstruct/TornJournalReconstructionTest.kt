package civictech.timetravel.reconstruct

import civictech.cell.durability.FileJournal
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

/**
 * TTD1 F4 (`computenet-6tm33.6`) BS-5 end-to-end, `[TTD1-36]` (owed to F4 by F3's review): a
 * torn journal file taints a reconstruction at EVERY legal position — the run and every
 * reconstructed cell carry `JOURNAL_TORN`, so no position of a torn journal reads `Faithful`.
 */
class TornJournalReconstructionTest {

    @Test
    fun `BS-5 every position of a torn journal reconstructs at most Degraded, carrying JOURNAL_TORN`(@TempDir dir: File) {
        val file = File(dir, "j.bin")
        val recording = DurableGraphFixture.record(
            seed = 5,
            sourceCount = 1,
            script = listOf(0 to "a", 0 to "b", 0 to "c"),
            journal = FileJournal(file),
        )
        // Tear the last record as ReadOnlyOfflineTest does: keep 2 bytes of its length prefix.
        val sizes = FileJournal(file).replay().map { it.size }
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        RandomAccessFile(file, "rw").use { it.setLength(offsetOfLast + 2) }

        val reading = JournalReader.open(JournalSource.File(file))
        val timeline = RunTimeline.of(reading).values.single()
        timeline.size shouldBeGreaterThanOrEqual 2
        val reconstructor = Reconstructor(reading, timeline, GraphSpecSource(recording.spec))

        for (i in 0 until timeline.size) {
            val reconstruction = reconstructor.stateAt(Position.Index(i))

            reconstruction.run shouldNotBe Fidelity.Faithful
            (Reason.JOURNAL_TORN in reconstruction.run.reasons) shouldBe true
            val reconstructed = reconstruction.cells.values.filterIsInstance<CellReconstruction.Reconstructed>()
            reconstructed.shouldNotBeEmpty()
            reconstructed.forEach { (Reason.JOURNAL_TORN in it.fidelity.reasons) shouldBe true }
        }
    }
}
