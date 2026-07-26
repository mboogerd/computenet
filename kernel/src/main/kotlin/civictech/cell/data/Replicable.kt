package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.Propagate
import civictech.cell.port.Subscribe
import civictech.cell.port.Use

/**
 * A cell whose state replicates by delta gossip (spec 42, session delta 4):
 * it exposes its effective-delta stream on [outlet] and merges peer replicas'
 * deltas on [deltaInlet]. Implementors' deltas MUST declare idempotent merge
 * semantics (tag union, pointwise max, …) — that is what lets mesh echoes
 * terminate and catch-up replays stay harmless. The mergeable class today:
 * the tagged set family ([SetCell]) and [PnCounterCell]; plain [CounterCell]
 * does not qualify (addition is not idempotent).
 */
interface Replicable<D> : Cell {
    val outlet: Subscribe<Propagate<D>>
    val deltaInlet: Use<Propagate<D>>
}
