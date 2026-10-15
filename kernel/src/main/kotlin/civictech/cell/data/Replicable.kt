package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.Propagate
import civictech.cell.port.Subscribe
import civictech.cell.port.Use

/**
 * A cell whose state replicates by delta gossip on [gossipOutlet]/[deltaInlet],
 * whatever its ordinary data outlet carries. The gossip delta MUST have
 * idempotent merge semantics so mesh echoes terminate and catch-up replays are
 * harmless. [replicationRefusal] lets a cell reject replication for a
 * construction-time state mode before the replication layer mutates any
 * bookkeeping.
 */
interface Gossiping<D> : Cell {
    val gossipOutlet: Subscribe<Propagate<D>>
    val deltaInlet: Use<Propagate<D>>
    val replicationRefusal: String? get() = null
}

/**
 * The idempotent-merge replication class (spec 42, session delta 4): it uses
 * its ordinary [outlet] as the effective-delta gossip stream and merges peer
 * replicas' deltas on [deltaInlet]. Implementors' deltas MUST declare idempotent merge
 * semantics (tag union, pointwise max, …) — that is what lets mesh echoes
 * terminate and catch-up replays stay harmless. The mergeable class today:
 * the tagged set family ([SetCell]) and [PnCounterCell]; plain [CounterCell]
 * does not qualify (addition is not idempotent).
 *
 * T05 finding 6 — joining a **non-[civictech.cell.link.Interest.Total]** mesh
 * additionally requires [D] to implement [civictech.cell.link.Scoped]: the
 * gossip linker's [civictech.cell.link.sliceTo] refuses (drops, counted) a
 * non-`Scoped` delta rather than shipping it whole across a partial-interest
 * link — silently shipping it whole would break "a delta a peer has no
 * interest in never crosses" (spec 40/42 §Interest-scoped instance sets), the
 * guarantee partitioning/disclosure reasoning rests on. Only
 * `SetDelta`/`MapDelta` implement `Scoped` today; a `Replicable` whose delta
 * doesn't (`PnCounterDelta`, `WatermarkDelta`, `CounterDelta`, `ListDelta`)
 * is safe only on a `Total`-interest (pure replication) mesh.
 */
interface Replicable<D> : Gossiping<D> {
    val outlet: Subscribe<Propagate<D>>
    override val gossipOutlet: Subscribe<Propagate<D>> get() = outlet
}
