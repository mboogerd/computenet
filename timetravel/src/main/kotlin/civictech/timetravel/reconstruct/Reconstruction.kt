package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.timeline.Position
import java.io.Serializable

/**
 * Where a [Position] resolved to when replaying a prefix (TTD1 F4, `computenet-6tm33` D8):
 * [requested] is what the caller asked for, [prefixEnd] is `timeline.resolve(requested)` (the
 * record count `n`), [anchor] is the replay start (0 unless a checkpoint moved it), and
 * [replayedRecords] is how many records were actually handed to `recoverFrom` — `0` when a
 * [ReconstructionDetail.GraphSourceIncomplete] refusal stopped replay before it began. This is
 * how a caller (and a test) proves "the record was fed, not silently dropped" without a kernel
 * accessor for the dead-letter count.
 */
data class ResolvedPosition(
    val requested: Position,
    val prefixEnd: Int,
    val anchor: Int,
    val replayedRecords: Int,
) {
    init {
        require(anchor in 0..prefixEnd) { "anchor ($anchor) must lie in [0, prefixEnd] ($prefixEnd)" }
        require(replayedRecords in 0..(prefixEnd - anchor)) {
            "replayedRecords ($replayedRecords) must lie in [0, prefixEnd - anchor] (${prefixEnd - anchor})"
        }
    }
}

/**
 * Detail behind a [Reason] that a [Fidelity] verdict alone cannot carry (`Reason`'s own KDoc:
 * "the details a reason needs … ride on the reporting structures"). TTD1 F4 (`computenet-6tm33`
 * D7); `[TTD1-33]`. `sealed` — not `open` — so this file need not change when F5 adds its own
 * subclass (`EFFECTFUL_CELL`) in another file of this package.
 */
sealed interface ReconstructionDetail {
    val reason: Reason
}

/** The record's declared [ref] is not among the graph's current members ([Reason.GRAPH_MISMATCH]). */
data class GraphMismatch(val ref: CellRef) : ReconstructionDetail {
    override val reason: Reason = Reason.GRAPH_MISMATCH
}

/**
 * The [GraphSource] spawned cells onto the host that it did not return in its [GraphBuild];
 * [unreturned] is those refs ([Reason.GRAPH_SOURCE_INCOMPLETE], 6tm33-D2/D11). A ref the journal
 * touches but the graph lacks is [GraphMismatch], not this.
 */
data class GraphSourceIncomplete(val unreturned: Set<CellRef>) : ReconstructionDetail {
    override val reason: Reason = Reason.GRAPH_SOURCE_INCOMPLETE
}

/**
 * Recovery over the replayed prefix did not fully complete: [recordIndex] is the **timeline**
 * index of the record recovery stopped at (`anchor + e.recordIndex`, since `recoverFrom` numbers
 * the seeded prefix from 0), [total] is the number of records fed (`n - anchor`), and [cause] is
 * the underlying failure's message ([Reason.RECOVERY_INCOMPLETE]).
 */
data class RecoveryIncomplete(val recordIndex: Int, val total: Int, val cause: String) : ReconstructionDetail {
    override val reason: Reason = Reason.RECOVERY_INCOMPLETE
}

/**
 * A single cell's reconstructed state, or the refusal to produce one ([TTD1-34]:
 * `Unreconstructible` carries no value at all — enforced by construction, not by convention).
 * TTD1 F4 (`computenet-6tm33` D3/D7).
 */
sealed interface CellReconstruction {
    /** The reconstructed class name, when known. `null` only when the class itself is unknown. */
    val cellClass: String?
    val fidelity: Fidelity

    /**
     * A cell whose state was rebuilt, with its verdict on how faithfully. [fidelity] can never be
     * [Fidelity.Unreconstructible] — that verdict has no value to attach and is [Unreconstructible]'s
     * shape instead.
     */
    data class Reconstructed(
        override val cellClass: String,
        val snapshot: Serializable,
        val view: CellStateView,
        override val fidelity: Fidelity,
    ) : CellReconstruction {
        init {
            require(fidelity !is Fidelity.Unreconstructible) {
                "Reconstructed cannot carry an Unreconstructible fidelity; use CellReconstruction.Unreconstructible"
            }
        }
    }

    /**
     * No state could be reconstructed for this cell. Declares **no** property of type
     * [Serializable] or [CellStateView] — that absence, checked by reflection in
     * `ReconstructionShapeTest`, is `[TTD1-34]` by construction, not by discipline.
     */
    data class Unreconstructible(
        override val cellClass: String?,
        override val fidelity: Fidelity.Unreconstructible,
    ) : CellReconstruction
}

/**
 * The result of reconstructing a run's state at a resolved [position]: one [CellReconstruction]
 * per cell, the overall [run] verdict ([civictech.timetravel.fidelity.rollUp] of the cells'
 * fidelities), and the [details] backing every reason in [run] that needs one. TTD1 F4
 * (`computenet-6tm33` D7/D8/D12), `[TTD1-33]`/`[TTD1-34]`.
 *
 * The two `init` checks are each other's converse: no detail may name a reason [run] does not
 * carry, and none of the three detail-bearing reasons ([Reason.GRAPH_MISMATCH],
 * [Reason.GRAPH_SOURCE_INCOMPLETE], [Reason.RECOVERY_INCOMPLETE]) may appear in [run] without a
 * matching detail, or vice versa.
 */
data class Reconstruction(
    val position: ResolvedPosition,
    val cells: Map<CellRef, CellReconstruction>,
    val run: Fidelity,
    val details: Set<ReconstructionDetail>,
) {
    init {
        val detailReasons = details.mapTo(mutableSetOf()) { it.reason }
        require(detailReasons.all { it in run.reasons }) {
            "details name reasons absent from run.reasons: ${detailReasons - run.reasons}"
        }
        for (reason in DETAIL_BEARING_REASONS) {
            val inRun = reason in run.reasons
            val hasDetail = details.any { it.reason == reason }
            require(inRun == hasDetail) {
                "reason $reason must appear in run.reasons exactly when a matching detail is present " +
                    "(inRun=$inRun, hasDetail=$hasDetail)"
            }
        }
    }

    private companion object {
        val DETAIL_BEARING_REASONS = setOf(Reason.GRAPH_MISMATCH, Reason.GRAPH_SOURCE_INCOMPLETE, Reason.RECOVERY_INCOMPLETE)
    }
}
