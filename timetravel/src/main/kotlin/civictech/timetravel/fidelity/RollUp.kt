package civictech.timetravel.fidelity

import civictech.cell.CellRef

/**
 * The run-level roll-up ([TTD1-37], `computenet-kxex2` D3): the worst of every cell's
 * verdict, worsened further by any run-level [run] reasons (e.g. a journal defect that
 * applies to the whole run rather than one cell). Never better than any input: an empty
 * [cells] map with empty [run] is [Fidelity.Faithful]; non-empty [run] makes the result
 * at least [Fidelity.Degraded]; every reason in every input appears in the result.
 */
fun rollUp(cells: Map<CellRef, Fidelity>, run: Set<Reason>): Fidelity {
    val perCell = cells.values.fold(Fidelity.Faithful as Fidelity) { acc, v -> worst(acc, v) }
    return if (run.isEmpty()) perCell else worst(perCell, Fidelity.Degraded(run))
}
