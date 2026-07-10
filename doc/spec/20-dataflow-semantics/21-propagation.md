# 21 — Propagation: Push/Pull, Incremental/Complete

> **Status**: Partial (push+incremental specified and demonstrated; late-join catch-up implemented; on-demand pull protocol unbuilt)
> **Sources**: ADR 1 (§1, §2, §4), ADR — Cellular Software Development Process (incremental dataflow layer)
> **Implementation**: push+deltas in `civictech.cell.data` (SetCell → UnionSetCell chains); catch-up via `LinkSupport.onLinked`

## Push (default, implemented)

State changes propagate automatically along links as **invocations on
downstream contracts**. The canonical data-path contract is:

```kotlin
interface Propagate<D> { fun propagate(delta: D) }
```

A cell receives deltas on inlets, updates owned state, and emits *derived*
deltas on outlets — synchronously within its host (same call stack) or across
a host boundary (queue hop). Whether a hop is sync or async is a **hosting
decision, not a cell-logic decision** (P1): co-hosted chains fuse into direct
calls; cross-host chains pay exactly one enqueue.

## Incremental vs complete

- **Incremental** (default): unbounded/evolving structures propagate deltas
  (`SetDelta(adds, dels)`, counter increments, map diffs). Bandwidth and work
  scale with change size, not state size.
- **Complete**: small bounded values (configs, scalars, small structs)
  propagate whole values; the delta *is* the value. This is a degenerate case
  of incremental, not a separate mechanism.

Normative requirements on a delta type:

1. It has a deterministic application to state: `state × delta → state`.
2. Emission is **effective-only**: a cell emits the delta describing the
   *actual* state change, not the input (see `UnionSetCell`: ref-counting
   emits only when membership actually flips; empty deltas are not emitted).
3. If the cell accepts concurrent producers, deltas (or the cell's state) must
   declare merge semantics (20/24).

## Pull

ADR 1 requires on-demand reads and recomputation: a consumer asks for current
state (or a recompute) rather than waiting for pushes — needed for late
joiners, UI queries, and suspended subgraph reactivation.

**Late-join = catch-up on link** *(implemented, M4.2 — the core of G-18)*:
the post-install `onLinked` hook (13) fires once the new subscriber is
reachable; a data cell wires it to unicast its **state-as-delta-from-empty**
to just that subscriber (`outlet.at(link.to)`), after which the live stream
follows. No snapshot type exists — a snapshot *is* a delta, satisfying the
deterministic-application rule above. Observed-remove tags (24) make the
catch-up idempotent against replayed or duplicated live deltas. Validated:
a late joiner is indistinguishable from an early joiner at idle, 100 seeds,
with a control run proving the harness detects a missed prefix. The catch-up
emission is currently unwaved (glitch-free consumers pass it through); wave
alignment of snapshots waits for the request protocol below.

⚠ GAP (G-18, residual): **on-demand pull** — a consumer asking for a
recompute or state *without* relinking. *Proposal* (compatible with the
invocation model — pull is not a new mechanism): a generic **state-request
protocol** on the multiplex port (12, G-13): `RequestState(replyTo)`
traveling upstream, answered by a state-as-delta on the requester's inlet.
Recomputation-on-demand for derived cells = re-emission of current derived
state, never re-execution of history.

## Fusion and the critical path

Per P2, propagation MUST NOT introduce avoidable hops:

- Within a host, a chain `A → B → C` executes as nested direct calls
  (delegation flattening, 10/14, removes pass-through cells entirely).
- Backpressure stalls and context switches are minimized by fusing co-hosted
  work; the host queue is the only asynchronous boundary (30/31).

## Cycles

Graphs MAY contain cycles (feedback loops, UI↔model sync, learning).
Divergence is prevented by **magnitude-based throttling**: a cycle continues
only while deltas remain significant.

⚠ GAP (G-19): magnitude/throttling is unspecified — no delta-magnitude
interface, no threshold policy, no guarantee cycles quiesce. *Proposal*:
require delta types used in cycles to implement `Magnitude { fun size(): Double }`;
a cycle-participating cell declares a quiescence threshold; below it, it
absorbs instead of emitting. Fixpoint semantics (and interaction with
glitch-freedom timestamps in cycles) is an open research item — flagged, not
resolved, by this spec.
