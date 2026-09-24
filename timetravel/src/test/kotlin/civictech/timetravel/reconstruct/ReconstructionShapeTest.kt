package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.timetravel.fidelity.Fidelity
import civictech.cell.durability.InMemoryJournal
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.MalformedRecord
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * TTD1 F4 (`computenet-6tm33.2`), the type-shape half: `[TTD1-34]` (no value on
 * `Unreconstructible`), the `Reconstruction`/`ResolvedPosition` construction invariants
 * (`computenet-6tm33` D7/D8), and `CellStateView.of`'s rendering rules (D13). The reconstructor
 * task (`computenet-6tm33.4`) appends its own `[TTD1-32]` method to this same file — no
 * `private` helpers here that it could not reuse.
 */
class ReconstructionShapeTest {

    private val ref = CellRef(UUID(11, 1))

    private fun position(replayedRecords: Int = 0) =
        ResolvedPosition(Position.Index(0), prefixEnd = replayedRecords, anchor = 0, replayedRecords = replayedRecords)

    // -- [TTD1-34]: Unreconstructible carries no value, Reconstructed carries one of each. --

    @Test
    fun unreconstructibleDeclaresNoSerializableOrCellStateViewProperty() {
        val forbidden = setOf(Serializable::class.java, CellStateView::class.java)
        val offending = CellReconstruction.Unreconstructible::class.java.declaredFields.filter { field ->
            field.type in forbidden
        }
        offending shouldBe emptyList()
    }

    // Exact-type equality above only catches a field literally typed `Serializable` or
    // `CellStateView`; a value smuggled in under any other type (a `Serializable` subtype, or
    // `Any`) would slip past it. This closes that gap: `Unreconstructible` must declare exactly
    // its two documented fields (`cellClass`, `fidelity`) and no other, of any type.
    @Test
    fun unreconstructibleDeclaresExactlyCellClassAndFidelityAndNoOtherField() {
        val fieldNames = CellReconstruction.Unreconstructible::class.java.declaredFields.map { it.name }.toSet()
        fieldNames shouldBe setOf("cellClass", "fidelity")
    }

    @Test
    fun reconstructedDeclaresBothASerializableAndACellStateViewProperty() {
        val fields = CellReconstruction.Reconstructed::class.java.declaredFields
        (fields.any { it.type == Serializable::class.java }) shouldBe true
        (fields.any { it.type == CellStateView::class.java }) shouldBe true
    }

    // -- Reconstructed refuses Unreconstructible fidelity. --

    @Test
    fun reconstructedRejectsUnreconstructibleFidelity() {
        shouldThrow<IllegalArgumentException> {
            CellReconstruction.Reconstructed(
                cellClass = "civictech.cell.data.SetCell",
                snapshot = "snap",
                view = CellStateView.of("snap"),
                fidelity = Fidelity.unreconstructible(Reason.NOT_STATEFUL),
            )
        }
    }

    @Test
    fun reconstructedAcceptsFaithfulFidelity() {
        CellReconstruction.Reconstructed(
            cellClass = "civictech.cell.data.SetCell",
            snapshot = "snap",
            view = CellStateView.of("snap"),
            fidelity = Fidelity.Faithful,
        )
    }

    @Test
    fun reconstructedAcceptsDegradedFidelity() {
        CellReconstruction.Reconstructed(
            cellClass = "civictech.cell.data.SetCell",
            snapshot = "snap",
            view = CellStateView.of("snap"),
            fidelity = Fidelity.degraded(Reason.EFFECTFUL_CELL),
        )
    }

    // -- Reconstruction's detail/reason converse invariants. --

    @Test
    fun reconstructionRejectsADetailWhoseReasonIsAbsentFromRun() {
        shouldThrow<IllegalArgumentException> {
            Reconstruction(position(), emptyMap(), Fidelity.Faithful, setOf(GraphMismatch(ref)))
        }
    }

    @Test
    fun reconstructionRejectsADetailBearingReasonWithNoDetail() {
        shouldThrow<IllegalArgumentException> {
            Reconstruction(position(), emptyMap(), Fidelity.degraded(Reason.GRAPH_MISMATCH), emptySet())
        }
    }

    @Test
    fun reconstructionAcceptsAMatchedDetailAndReason() {
        Reconstruction(
            position(),
            emptyMap(),
            Fidelity.degraded(Reason.GRAPH_MISMATCH),
            setOf(GraphMismatch(ref)),
        )
    }

    @Test
    fun reconstructionAcceptsADetailFreeReasonWithNoDetail() {
        Reconstruction(position(), emptyMap(), Fidelity.degraded(Reason.JOURNAL_TORN), emptySet())
    }

    // -- ResolvedPosition's bounds. --

    @Test
    fun resolvedPositionAcceptsAnchorAndReplayWithinPrefix() {
        ResolvedPosition(Position.Index(3), prefixEnd = 4, anchor = 0, replayedRecords = 4)
    }

    @Test
    fun resolvedPositionRejectsAnchorPastPrefixEnd() {
        shouldThrow<IllegalArgumentException> {
            ResolvedPosition(Position.Index(3), prefixEnd = 4, anchor = 5, replayedRecords = 0)
        }
    }

    @Test
    fun resolvedPositionRejectsReplayedRecordsExceedingWhatAnchorLeaves() {
        shouldThrow<IllegalArgumentException> {
            ResolvedPosition(Position.Index(3), prefixEnd = 4, anchor = 0, replayedRecords = 5)
        }
    }

    // -- CellStateView.of rendering, pinned by D13's examples. --

    @Test
    fun ofRendersAMapSortedByKeyString() {
        val view = CellStateView.of(HashMap(mapOf("b" to 2, "a" to 1)))
        view shouldBe CellStateView("map", "2 entries", listOf("a" to "1", "b" to "2"))
    }

    @Test
    fun ofRendersASetSortedByElementString() {
        val view = CellStateView.of(HashSet(setOf("y", "x")))
        view shouldBe CellStateView("set", "2 rows", listOf("0" to "x", "1" to "y"))
    }

    @Test
    fun ofRendersAListInIterationOrder() {
        val view = CellStateView.of(ArrayList(listOf(3, 1)))
        view shouldBe CellStateView("list", "2 rows", listOf("0" to "3", "1" to "1"))
    }

    @Test
    fun ofRendersAScalar() {
        val view = CellStateView.of(42)
        view shouldBe CellStateView("scalar", "42", listOf("value" to "42"))
    }

    // -- [TTD1-32]: a recoverFrom that aborts surfaces as RecoveryIncomplete, never silently. --

    // computenet-6tm33.4: a proxy frame replaced by bytes that are a frame by type (1) but not
    // JSON makes the kernel's `recoverFrom` throw `RecoveryIncomplete` at that record; the
    // reconstruction reports it at its timeline index, and the reader's own classification of the
    // same record (FRAME_UNPARSEABLE) reaches the verdict through `journalDefectsUpTo`.
    @Test
    fun anAbortedRecoveryIsReportedAsRecoveryIncompleteAtTheBadRecordsTimelineIndex() {
        val recording = DurableGraphFixture.record(
            seed = 11,
            sourceCount = 1,
            script = listOf(0 to "a", 0 to "b", 0 to "c"),
        )
        val bad = recording.steps[1].proxyIndex
        val records = recording.journal.replay().toMutableList()
        records[bad] = byteArrayOf(1) + "{not json".encodeToByteArray()
        val journal = InMemoryJournal().apply { reset(records) }
        val reading = JournalReader.open(JournalSource.InMemory(journal, "j"))
        val timeline = RunTimeline.of(reading).getValue("j")
        val last = timeline.size - 1

        val malformed = reading.records.toList()[bad].shouldBeInstanceOf<MalformedRecord>()
        malformed.reason shouldBe Reason.FRAME_UNPARSEABLE

        val reconstruction = Reconstructor(reading, timeline, GraphSpecSource(recording.spec))
            .stateAt(Position.Index(last))

        val detail = reconstruction.details.single().shouldBeInstanceOf<RecoveryIncomplete>()
        detail.recordIndex shouldBe bad
        detail.total shouldBe last + 1 - reconstruction.position.anchor
        detail.total shouldBe last + 1
        detail.cause.shouldNotBeBlank()
        reconstruction.position.replayedRecords shouldBe last + 1
        reconstruction.cells.keys shouldBe recording.refs.all
        reconstruction.cells.values.forEach { cell ->
            cell.fidelity.reasons shouldContain Reason.RECOVERY_INCOMPLETE
            cell.fidelity shouldNotBe Fidelity.Faithful
        }
        reconstruction.run.reasons shouldContainAll listOf(Reason.RECOVERY_INCOMPLETE, Reason.FRAME_UNPARSEABLE)
    }
}
