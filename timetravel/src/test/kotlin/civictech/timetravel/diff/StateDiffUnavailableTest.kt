package civictech.timetravel.diff

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.Reconstructor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TTD1 F6 (computenet-si0tl.4) `[TTD1-44]`: a side with no reconstructor, or whose reconstruction is
 * run-`Unreconstructible`, makes the state comparison honestly `Unavailable` while every record-level
 * field of the report is unchanged.
 *
 * `AMENDS computenet-si0tl.4`: this test kills the third mutation .3's reviewer left surviving on
 * `RunDiff.kt` — "never list an unreconstructible run" — via the `Opaque` case below, which expects
 * `[RunUnavailable(B, [NOT_STATEFUL], ...)]`.
 */
class StateDiffUnavailableTest {

    /** A cell with no state at all (mirrors `ReconstructAtWaveTest.Opaque`): not `Stateful`. */
    private class Opaque(override val ref: CellRef) : Cell

    private val opaqueRef = CellRef(UUID(11, 998))

    private val scriptA = listOf(0 to "a", 0 to "b", 0 to "c")
    private val scriptB = scriptA + (0 to "d")

    private fun fixtures(): Quad {
        val recA = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptA)
        val recB = RoutedRunFixture.record(seed = 11, sourceCount = 1, script = scriptB)
        val readingA = RoutedRunFixture.reading(recA, "a")
        val readingB = RoutedRunFixture.reading(recB, "b")
        val tA = RoutedRunFixture.timeline(readingA)
        val tB = RoutedRunFixture.timeline(readingB)
        return Quad(recA, recB, tA, tB, readingA, readingB)
    }

    private class Quad(
        val recA: RoutedRunFixture.Recording,
        val recB: RoutedRunFixture.Recording,
        val tA: civictech.timetravel.timeline.RunTimeline,
        val tB: civictech.timetravel.timeline.RunTimeline,
        val readingA: civictech.timetravel.journal.JournalReading,
        val readingB: civictech.timetravel.journal.JournalReading,
    )

    @Test
    fun `TTD1-44 a null reconstructor makes the state diff Unavailable, record-level fields unchanged`() {
        val f = fixtures()
        val reconstructorA = RoutedRunFixture.reconstructor(f.recA, f.readingA, f.tA)
        val reconstructorB = RoutedRunFixture.reconstructor(f.recB, f.readingB, f.tB)

        val full = RunDiff.diff(f.tA, f.tB, reconstructorA, reconstructorB)
        // Negative control: the fully-supplied diff is Compared, never Unavailable.
        full.stateDiff.shouldBeInstanceOf<StateDiff.Compared>()

        // (i) A is null.
        val aNull = RunDiff.diff(f.tA, f.tB, null, reconstructorB)
        aNull.copy(stateDiff = full.stateDiff) shouldBe full
        aNull.stateDiff shouldBe StateDiff.Unavailable(
            listOf(RunUnavailable(RunSide.A, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE)),
        )

        // (ii) B's reconstruction is run-Unreconstructible: an Opaque cell added to B's graph.
        val opaqueGraph = GraphSource { host ->
            val base = RoutedRunFixture.graphSource(f.recB).build(host)
            val opaque = Opaque(opaqueRef)
            host.managementInlet.call.spawn(opaque)
            GraphBuild(base.cells + opaque)
        }
        val reconstructorBOpaque = Reconstructor(f.readingB, f.tB, opaqueGraph)
        val bOpaque = RunDiff.diff(f.tA, f.tB, reconstructorA, reconstructorBOpaque)
        bOpaque.copy(stateDiff = full.stateDiff) shouldBe full
        val unavailable = bOpaque.stateDiff.shouldBeInstanceOf<StateDiff.Unavailable>()
        unavailable.runs.size shouldBe 1
        val runUnavailable = unavailable.runs.single()
        runUnavailable.run shouldBe RunSide.B
        runUnavailable.reasons shouldBe listOf(Reason.NOT_STATEFUL)
        runUnavailable.message shouldContain "#9"

        // (iii) both null: two entries, A then B, both NO_GRAPH_SOURCE.
        val bothNull = RunDiff.diff(f.tA, f.tB, null, null)
        bothNull.copy(stateDiff = full.stateDiff) shouldBe full
        bothNull.stateDiff shouldBe StateDiff.Unavailable(
            listOf(
                RunUnavailable(RunSide.A, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE),
                RunUnavailable(RunSide.B, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE),
            ),
        )

        // (iv) toText() of the Opaque case names the run and the reason.
        val text = bOpaque.toText()
        text shouldContain "state diff unavailable for run B"
        text shouldContain "NOT_STATEFUL"
    }
}
