package civictech.timetravel.fidelity

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.durability.JournalFileScan
import civictech.cell.proxy.HostedPortInvocation
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.FrontierRecord
import civictech.timetravel.journal.JournalRecord
import civictech.timetravel.journal.JournalSummary
import civictech.timetravel.journal.MalformedRecord
import civictech.timetravel.journal.UnknownRecord
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * TTD1 F3 (computenet-kxex2.2): [journalDefectsUpTo] names every record defect at or before a
 * position and nothing after it, and a tear at every position ([TTD1-36], epic BS-5).
 */
class JournalDefectsTest {

    private fun summary(
        journalId: String = "j",
        recordCount: Int,
        tear: JournalFileScan.Tear? = null,
        refusal: String? = null,
        reasons: Set<Reason> = emptySet(),
    ) = JournalSummary(
        journalId = journalId,
        declaredFormatVersion = 1,
        recordCount = recordCount,
        tear = tear,
        refusal = refusal,
        reasons = reasons,
    )

    private fun filler(index: Int, journalId: String = "j"): JournalRecord =
        FrontierRecord(
            index = index,
            journalId = journalId,
            cellRef = CellRef(UUID(0, index.toLong())),
            portName = "p",
            timestamp = Timestamp(sourceId = UUID(0, 0), counter = 0L),
            reasons = emptySet(),
        )

    private fun frame(index: Int, journalId: String = "j", reasons: Set<Reason>): JournalRecord =
        FrameRecord(
            index = index,
            journalId = journalId,
            cellRef = CellRef(UUID(0, index.toLong())),
            portName = "p",
            type = HostedPortInvocation.Type.PORT_API,
            contractId = 0L,
            methodId = 0L,
            context = null,
            args = JsonNull,
            wireVersion = null,
            hydrated = null,
            hydrationFailure = null,
            reasons = reasons,
        )

    @Test
    fun `record defects count only at or before the position`() {
        val journal = summary(recordCount = 8)
        val records = listOf(
            filler(0),
            filler(1),
            filler(2),
            UnknownRecord(index = 3, journalId = "j", typeByte = 9),
            filler(4),
            MalformedRecord(index = 5, journalId = "j", typeByte = 2, reason = Reason.CHECKPOINT_UNDESERIALIZABLE, message = "x"),
            filler(6),
            filler(7),
        )

        journalDefectsUpTo(journal, records, 2) shouldBe emptySet()
        journalDefectsUpTo(journal, records, 3) shouldBe setOf(Reason.UNKNOWN_RECORD)
        journalDefectsUpTo(journal, records, 5) shouldBe setOf(Reason.UNKNOWN_RECORD, Reason.CHECKPOINT_UNDESERIALIZABLE)
        journalDefectsUpTo(journal, records, 7) shouldBe setOf(Reason.UNKNOWN_RECORD, Reason.CHECKPOINT_UNDESERIALIZABLE)
    }

    @Test
    fun `the tear counts at every legal position, not only at the last intact record (BS-5)`() {
        val journal = summary(recordCount = 8, tear = JournalFileScan.Tear(lastIntactIndex = 7, trailingBytes = 3))
        val records = (0..7).map { filler(it) }

        for (position in -1..7) {
            journalDefectsUpTo(journal, records, position) shouldBe setOf(Reason.JOURNAL_TORN)
        }
    }

    @Test
    fun `an untorn journal is never marked JOURNAL_TORN`() {
        val journal = summary(recordCount = 8)
        val records = (0..7).map { filler(it) }

        for (position in -1..7) {
            journalDefectsUpTo(journal, records, position) shouldBe emptySet()
        }
    }

    @Test
    fun `a tear with no intact record counts at every legal position`() {
        val journal = summary(recordCount = 0, tear = JournalFileScan.Tear(lastIntactIndex = -1, trailingBytes = 2))

        journalDefectsUpTo(journal, emptyList(), -1) shouldBe setOf(Reason.JOURNAL_TORN)
    }

    @Test
    fun `journal-level reasons other than the tear apply at every legal position`() {
        val journal = summary(recordCount = 1, reasons = setOf(Reason.FORMAT_VERSION_MISMATCH))
        val records = listOf(filler(0))

        journalDefectsUpTo(journal, records, -1) shouldBe setOf(Reason.FORMAT_VERSION_MISMATCH)
        journalDefectsUpTo(journal, records, 0) shouldBe setOf(Reason.FORMAT_VERSION_MISMATCH)
    }

    @Test
    fun `records of another journal are ignored at every position`() {
        val journal = summary(journalId = "j", recordCount = 1)
        val records = listOf(UnknownRecord(index = 0, journalId = "other", typeByte = 9))

        journalDefectsUpTo(journal, records, -1) shouldBe emptySet()
        journalDefectsUpTo(journal, records, 0) shouldBe emptySet()
    }

    @Test
    fun `a frame defect is present from its index on`() {
        val journal = summary(recordCount = 2)
        val records = listOf(
            filler(0),
            frame(1, reasons = setOf(Reason.WIRE_VERSION_MISMATCH)),
        )

        journalDefectsUpTo(journal, records, 0) shouldBe emptySet()
        journalDefectsUpTo(journal, records, 1) shouldBe setOf(Reason.WIRE_VERSION_MISMATCH)
    }

    @Test
    fun `an out-of-range position throws`() {
        val journal = summary(recordCount = 8)
        val records = (0..7).map { filler(it) }

        assertThrows<IllegalArgumentException> { journalDefectsUpTo(journal, records, 8) }
        assertThrows<IllegalArgumentException> { journalDefectsUpTo(journal, records, -2) }
    }

    @Test
    fun `a refused journal throws`() {
        val journal = summary(recordCount = 0, refusal = "unreadable")

        assertThrows<IllegalArgumentException> { journalDefectsUpTo(journal, emptyList(), -1) }
    }
}
