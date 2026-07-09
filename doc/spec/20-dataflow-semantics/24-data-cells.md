# 24 — Standard Data Cells, Merge Semantics, Partitioning

> **Status**: Partial (set family implemented; map/list present; partitioning unbuilt)
> **Sources**: ADR 1 (§3, §5, §14), ADR — Cellular Software Development Process (incremental dataflow layer; LASP/Differential Dataflow inspirations)
> **Implementation**: `civictech.cell.data`: `SetCell`, `UnionSetCell`, `MapCell`, `ListCell`, `Propagate`

## Role

Data cells are the standard library of the incremental dataflow layer:
stateful cells whose contracts are operations + delta streams, composing via
operators (union, intersect, map, …) into incrementally-maintained derived
state. They are ordinary cells — no kernel privileges.

## Established pattern (normative template)

`SetCell` is the reference shape:

```kotlin
interface SetOps<E> { fun add(element: E); fun remove(element: E) }
data class SetDelta<E>(val adds: Set<E>, val dels: Set<E>) {
    fun mergeAddWins(other: SetDelta<E>): SetDelta<E>
    fun mergeDelWins(other: SetDelta<E>): SetDelta<E>
}
interface SetApi<E> {
    val inlet: Use<SetOps<E>>                       // commands in
    val outlet: Subscribe<Propagate<SetDelta<E>>>   // deltas out
}
```

Elements of the pattern:

1. **Command contract** on the inlet (semantic operations, not raw deltas) —
   the cell derives effective deltas from owned state.
2. **Delta contract** on the outlet; emissions are effective-only (21).
3. **Merge functions on the delta type** with named bias (add-wins /
   del-wins) — concurrent-update resolution is *declared*, per the
   mutability classes of 10/11. This is the CRDT-style ingredient for
   decentralized replication (40/42) without imposing CRDTs everywhere.
4. **Derived cells consume delta contracts**: `UnionSetCell` takes
   `Propagate<SetDelta<E>>` fan-in from multiple sources, maintains
   ref-counts, emits only effective membership changes.

## Required next steps in the family

- ⚠ GAP (G-22): **State + catch-up**: data cells expose current state to
  late joiners via the pull/snapshot protocol (21). Today state is trapped in
  private fields.
- ⚠ GAP (G-23): **Causal tagging**: deltas do not carry MessageContext (22);
  concurrent-merge bias (add-wins) is currently *order-of-arrival* bias, which
  is not stable across replicas. Merge semantics become principled only once
  deltas are wave/actor-tagged. For true multi-writer convergence the set
  family should either adopt observed-remove semantics (OR-Set style tags) or
  document its convergence limits.
- **Operator library**: intersect, filter/map (exists as `MapperCell` for
  scalars), join, count — each as a cell with declared incremental semantics.

## Partitioned state

ADR 1 §5: large keyed datasets shard by key for concurrency, locality, and
scale-out; non-partitioned is for atomic structures.

⚠ GAP (G-24): nothing exists. *Proposal sketch* (kernel-untouched, per P1): a
**PartitionedCell** is a composite cell whose organelles each own a key range;
its inlet routes commands by key (a routing proxy — same mechanism as
`HostRoutingApi`); its outlet merges child delta streams. Placement of
partitions across hosts is then ordinary cell placement (30/33, 40/42) —
partitioning must not become a second distribution mechanism.

## Durability spectrum

ADR 1 §3 requires in-memory / durable / hybrid state.
⚠ GAP (G-25): no persistence anywhere. *Proposal*: durability as a host
concern (30/31): a durable host journals applied invocations (they are
serializable — P9 pays off) and snapshots cell state on suspension (30/33
already needs state capture). Replay = recovery. Cells stay oblivious.
