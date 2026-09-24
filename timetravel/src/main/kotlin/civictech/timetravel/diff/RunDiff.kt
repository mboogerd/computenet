package civictech.timetravel.diff

import civictech.cell.CellRef
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.reconstruct.CellReconstruction
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.Reconstruction
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.reconstruct.ReconstructorResult
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import java.io.File

/**
 * Diffs two recorded runs (TTD1 F6, `si0tl-D6`): record-level alignment ([align], `si0tl-D7`..`D9`),
 * a state comparison at the first divergence ([diff], `si0tl-D10`), and a per-journal diff of two
 * whole readings matched by journal name ([diffReadings], D5, `si0tl-D12`).
 *
 * `RunDiff` constructs no [JournalReading] and no [GraphSource]: the caller does. The only thing it
 * builds is a [Reconstructor] in [diffReadings], and it asks a reconstructor for state only when
 * both sides have one — a `null` side means no reconstruction host is constructed for either run.
 */
object RunDiff {

    /** Record-level alignment only: exactly [RecordAlignment.align]. */
    fun align(a: RunTimeline, b: RunTimeline): Alignment = RecordAlignment.align(a, b)

    /**
     * The full diff of [a] and [b]. Record-level fields come from [align] and are present whatever
     * the state comparison's outcome ([TTD1-44]). The state comparison (`si0tl-D10`) reconstructs
     * each run at `Position.Index(x)`, `x` = the aligned prefix when the run continues past it, else
     * its last index; a `null` reconstructor is that run's [Reason.NO_GRAPH_SOURCE].
     *
     * A side that is unavailable before reconstruction (no reconstructor, or no records) makes
     * [StateDiff.Unavailable] without reconstructing the other side. When both sides have a
     * reconstructor and records, both are reconstructed and every side whose run is
     * [Fidelity.Unreconstructible] is listed, A before B.
     */
    fun diff(
        a: RunTimeline,
        b: RunTimeline,
        reconstructorA: Reconstructor? = null,
        reconstructorB: Reconstructor? = null,
    ): RunDiffReport {
        val alignment = align(a, b)
        val p = alignment.alignedPrefix
        val xA = comparedIndex(a, p)
        val xB = comparedIndex(b, p)

        val stateDiff = stateDiff(xA, xB, reconstructorA, reconstructorB)

        val recordDivergence = alignment.divergence
        val divergence = when {
            recordDivergence != null -> Divergence(
                kind = recordDivergence.kind,
                indexA = recordDivergence.indexA,
                indexB = recordDivergence.indexB,
                labelA = recordDivergence.indexA?.let { a.labels(it) },
                labelB = recordDivergence.indexB?.let { b.labels(it) },
                keyA = recordDivergence.keyA?.render(),
                keyB = recordDivergence.keyB?.render(),
                detailA = recordDivergence.detailA,
                detailB = recordDivergence.detailB,
            )

            stateDiff is StateDiff.Compared && stateDiff.deltas.isNotEmpty() -> Divergence(
                kind = DivergenceClass.STATE_DIFFERS,
                indexA = stateDiff.positionA,
                indexB = stateDiff.positionB,
                labelA = a.labels(stateDiff.positionA),
                labelB = b.labels(stateDiff.positionB),
                keyA = null,
                keyB = null,
                detailA = null,
                detailB = null,
            )

            else -> null
        }

        return RunDiffReport(
            journalA = nameOf(a.journalId),
            journalB = nameOf(b.journalId),
            sizeA = a.size,
            sizeB = b.size,
            alignment = alignment.mode,
            alignmentNote = alignment.note,
            alignedPrefix = p,
            divergence = divergence,
            stateDiff = stateDiff,
        )
    }

    /**
     * The per-journal diff of two readings (D5, `si0tl-D12`): journals are matched by
     * `File(journalId).name` — never the host-local path — and walked in sorted-name order. A name
     * on one side only is `ONLY_IN_A`/`ONLY_IN_B` with no report; a matched pair is [diff]ed with a
     * reconstructor per side from [Reconstructor.of], a refusal (no graph source) being `null`.
     *
     * @throws IllegalArgumentException when two journals of one reading share a name.
     */
    fun diffReadings(
        a: JournalReading,
        b: JournalReading,
        graphA: GraphSource? = null,
        graphB: GraphSource? = null,
    ): MultiJournalDiffReport {
        val timelinesA = byName(a, RunSide.A)
        val timelinesB = byName(b, RunSide.B)
        val names = (timelinesA.keys + timelinesB.keys).sorted()
        val journals = names.map { name ->
            val tA = timelinesA[name]
            val tB = timelinesB[name]
            when {
                tA == null -> JournalDiff(name, JournalPresence.ONLY_IN_B, null)
                tB == null -> JournalDiff(name, JournalPresence.ONLY_IN_A, null)
                else -> JournalDiff(
                    name,
                    JournalPresence.BOTH,
                    diff(tA, tB, reconstructorOf(a, tA, graphA), reconstructorOf(b, tB, graphB)),
                )
            }
        }
        return MultiJournalDiffReport(journals)
    }

    /** `si0tl-D10`: the first divergent index when the run continues past [p], else its last index. */
    private fun comparedIndex(t: RunTimeline, p: Int): Int = if (p < t.size) p else t.size - 1

    private fun stateDiff(
        xA: Int,
        xB: Int,
        reconstructorA: Reconstructor?,
        reconstructorB: Reconstructor?,
    ): StateDiff {
        val statically = listOfNotNull(
            staticallyUnavailable(RunSide.A, xA, reconstructorA),
            staticallyUnavailable(RunSide.B, xB, reconstructorB),
        )
        if (statically.isNotEmpty()) return StateDiff.Unavailable(statically)

        val runA = reconstructorA!!.stateAt(Position.Index(xA))
        val runB = reconstructorB!!.stateAt(Position.Index(xB))
        val unreconstructible = listOfNotNull(
            unreconstructible(RunSide.A, xA, runA),
            unreconstructible(RunSide.B, xB, runB),
        )
        if (unreconstructible.isNotEmpty()) return StateDiff.Unavailable(unreconstructible)

        return StateDiff.Compared(xA, xB, FidelityDto.of(runA.run), FidelityDto.of(runB.run), deltas(runA, runB))
    }

    /** `x < 0` iff the run has no records. */
    private fun staticallyUnavailable(side: RunSide, x: Int, reconstructor: Reconstructor?): RunUnavailable? = when {
        x < 0 -> RunUnavailable(side, emptyList(), "run $side has no records")
        reconstructor == null ->
            RunUnavailable(side, listOf(Reason.NO_GRAPH_SOURCE), Reconstructor.NO_GRAPH_SOURCE_MESSAGE)

        else -> null
    }

    private fun unreconstructible(side: RunSide, x: Int, reconstruction: Reconstruction): RunUnavailable? {
        val run = reconstruction.run as? Fidelity.Unreconstructible ?: return null
        return RunUnavailable(side, run.reasons.sortedBy { it.name }, "run $side at #$x is Unreconstructible")
    }

    /**
     * One [CellDelta] per ref present on one side only, or whose views differ, sorted by the ref's
     * string. An `Unreconstructible` cell has no view (`null`); a `Reconstructed` vs
     * `Unreconstructible` pair is a delta even though neither view alone says so.
     */
    private fun deltas(runA: Reconstruction, runB: Reconstruction): List<CellDelta> {
        val refs = (runA.cells.keys + runB.cells.keys).sortedBy { it.id.toString() }
        return refs.mapNotNull { ref -> deltaOf(ref, runA.cells[ref], runB.cells[ref]) }
    }

    private fun deltaOf(ref: CellRef, cellA: CellReconstruction?, cellB: CellReconstruction?): CellDelta? {
        val viewA = (cellA as? CellReconstruction.Reconstructed)?.view
        val viewB = (cellB as? CellReconstruction.Reconstructed)?.view
        val same = (cellA == null) == (cellB == null) &&
            (cellA is CellReconstruction.Reconstructed) == (cellB is CellReconstruction.Reconstructed) &&
            viewA == viewB
        if (same) return null
        return CellDelta(
            cellRef = ref.id.toString(),
            viewA = viewA?.let { StateView.of(it) },
            viewB = viewB?.let { StateView.of(it) },
            fidelityA = cellA?.let { FidelityDto.of(it.fidelity) },
            fidelityB = cellB?.let { FidelityDto.of(it.fidelity) },
        )
    }

    private fun reconstructorOf(reading: JournalReading, timeline: RunTimeline, graph: GraphSource?): Reconstructor? =
        (Reconstructor.of(reading, timeline, graph) as? ReconstructorResult.Ready)?.reconstructor

    /** Each of [reading]'s timelines keyed by its journal's base name (`si0tl-D12`). */
    private fun byName(reading: JournalReading, side: RunSide): Map<String, RunTimeline> {
        val timelines = RunTimeline.of(reading).values
        val byName = timelines.associateBy { nameOf(it.journalId) }
        require(byName.size == timelines.size) {
            "reading $side has two journals with one name: " +
                timelines.groupBy { nameOf(it.journalId) }.filterValues { it.size > 1 }.keys.sorted()
        }
        return byName
    }

    /** `si0tl-D12`: a journal's name is its id's last path segment, so no host path reaches a report. */
    private fun nameOf(journalId: String): String = File(journalId).name
}
