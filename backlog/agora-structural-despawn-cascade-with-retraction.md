# Structural despawn-cascade with automatic contribution retraction

**Origin**: agora demo (`AgoraService.remove`). Removing a claim/edge is one of the
gnarliest, most manual parts of the service: it hand-computes a transitive "doomed"
set, then hand-retracts each dying edge's last influence from any surviving target,
then despawns. The README already flags a correctness hole in it.

## The observation

`remove(id)` does three things by hand:

1. **Transitive structural closure** — grow a `doomed` set over every edge whose
   `source` or `target` is doomed, recursively (edges targeting a removed edge go
   too):
   ```kotlin
   grew = doomed.addAll(nodes.filter { (ref, info) ->
       ref !in doomed && info.kind == Kind.EDGE && (info.source in doomed || info.target in doomed)
   }.keys)
   ```
2. **Manual retraction** — for each doomed edge whose target survives, re-emit an
   `InfluenceDelta(ref, polarity, next = null, size = credenceOf(ref))` so the
   target subtracts the contribution that is about to disappear.
3. **Despawn** — `manage.despawn`, drop from `cells`/`nodes`.

And the README concedes the seam is fragile:

> `remove` cascades over dangling edges; a crash mid-cascade can leave a dangling
> influence until the next remove — accepted for v1 (single host).

So every removal reconstructs graph-topology bookkeeping the service already owns,
plus a retraction protocol encoded ad hoc in the payload (`next = null`), with no
atomicity.

## What it is

Two related framework primitives:

1. **Structural dependency between cells** — a declared "cell B exists only while
   cell A does" edge, so despawning A cascades to B (transitively) as one host
   operation.
2. **Retract-on-despawn for fan-in contributors** — when a cell that contributes to
   a downstream fan-in is despawned, the host emits its retraction (the inverse of
   its last delivered contribution) to surviving consumers automatically, as part
   of the same cascade.

## Why it's a proper fit for the framework

- Cascading teardown + "undo the last contribution of a departing input" is a
  generic incremental-dataflow concern, not an argumentation concern. Any fan-in
  cell whose inputs come and go (joins, set intersections in slotfinder/skillmatch,
  the shopping list's vote tallies) faces the same "a contributor left — subtract
  it" problem and today each solves it locally.
- The host already owns spawn/despawn and the link graph; it is the only place a
  cascade can be made **atomic** (the crash-mid-cascade hole the README accepts is
  unfixable from application code, but natural for the host to guarantee).
- It turns a stringly/nullably-encoded retraction convention (`next = null`) into a
  first-class lifecycle event, so consumers get a well-typed "input N withdrawn"
  rather than each inventing a sentinel.

## Solution sketch

Declare structural dependents at wire time, and let despawn cascade + retract:

```kotlin
// edge B depends on claims A (source) and T (target): remove either and B goes too
host.dependsOn(edge, on = listOf(source, target))

// fan-in target opts into automatic retraction of a departing contributor's
// last value (host replays the inverse delta on despawn):
val influenceInlet = FanInlet.create<InfluenceDelta>(retractOnContributorDespawn = true)

// then removal is just:
host.despawnCascade(id)   // transitive dependents despawned; contributions retracted; atomic
```

The host computes the transitive dependent set from the declared `dependsOn`
edges (replacing agora's hand-rolled closure), retracts each despawned
contributor's last contribution to surviving fan-in consumers, and completes the
whole cascade as one scheduled unit (no partially-applied intermediate state
visible to observers).

## Expected inputs / outputs

- **Input**: a root `CellRef` to despawn; the declared structural-dependency edges;
  per-inlet opt-in for auto-retraction.
- **Output**: all transitively-dependent cells despawned; every surviving fan-in
  consumer has received exactly one retraction per despawned contributor that fed
  it; the graph is left in the same state a batch recompute over the survivors
  would produce (no dangling influence).
- **Ordering**: retractions precede despawns (so the inverse delta can still read
  the contributor's last value), and the cascade is glitch-free to observers.

## Acceptance criteria

- agora's `remove` reduces to `host.despawnCascade(id)`: the manual `doomed`-set
  loop and the `next = null` retraction re-emission are **deleted**, with
  `AgoraExitTest` (which includes cascading removals and a retraction-blind control
  that must diverge) still green.
- After any removal, no surviving node retains influence from a removed edge —
  asserted by the existing incremental == batch check, now with no app-side
  retraction code backing it.
- A cascade interrupted partway (simulated fault) either fully applies or fully
  rolls back — closing the "crash mid-cascade leaves a dangling influence" hole the
  README currently accepts.
- Declaring `dependsOn` cycles (A↔B) does not loop forever; the closure terminates.

## Related

- The concurrent set-algebra / materialized-view backlog items handle retraction on
  the *data* side (elements leaving a set); this item is the *lifecycle* side (a
  contributor *cell* being despawned). They should share one retraction concept.
