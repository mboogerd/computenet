package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.timeline.Position
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
}
