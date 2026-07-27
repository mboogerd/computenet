# Composition — follow-on tickets (the deferrals)

> One ticket per item in `doc/CHANGELOG-composition.md` §"What's still not there".
> Baseline: `main` @ `d40e4ad` (composition run complete, full gate green).
> House style follows `COMPOSITION-TICKETS.md` / `94-implementation-plan.md`:
> each ticket = *make the cited spec text true of the code*, test named up front,
> 100-seed generative where applicable, **controls that must diverge**.
>
> Orchestration metadata per ticket: **Context** (`FRESH` / `CONTINUE <ticket>`),
> **Parallel** (safe to run concurrently — disjoint in function *and* files),
> **Files** (for collision reasoning). Worktree/merge discipline as in
> `doc/ORCHESTRATION.md`.

## At a glance

```
LANE η (aggregates)     G1 ──────────────────────────────── independent
LANE θ (nature typing)  G2 ─────────────────── (G6 only on trigger)
LANE ι (router debt)    G3 → G4
LANE κ (evidence)       G5   (after G1, G3; CONTINUE the demo context)
LANE λ (research)       G7   (research spike, no merge commitment)
```

G1 ∥ G2 ∥ G3 are disjoint (data / gen+link / repl) and can run three-wide.
G5 is the join and must land last of the four.

---

## CP-G1 — Mergeable aggregates: `Replicable` GroupBy over a merge operator — P1 · High · `data`

- **Context**: FRESH (agent η) · **Parallel**: with G2, G3, G7
- **Files**: `kernel/.../cell/data/GroupByCell.kt`, `kernel/.../cell/data/MapCell.kt`
  (`MapDelta` merge contract), NEW `kernel/.../cell/data/MergeableGroupByCell.kt`,
  spec `doc/spec/20-dataflow-semantics/24-data-cells.md` §Aggregation.

**Problem.** `GroupByCell` recomputes accumulators from tags and is explicitly *not*
`Replicable` (its own KDoc says so: last-writer-wins on `MapDelta` would lose
concurrent partial sums). There is no kernel `MapDelta` merge, so a distributed
group-by must replicate the **input** and recompute per peer — O(input) gossip where
O(groups) would do. `:demo:exchange` works around it with a demo-side `MapMergeCell`,
which is the smell that says this belongs in the kernel.

**Implement.** An aggregate variant parameterized by a commutative-associative-
idempotent-or-counted merge on the accumulator, not by recompute:

```kotlin
MergeableGroupByCell(keyOf, accumulate, merge = { a, b -> a + b })   // Replicable
```

- `MapDelta<K, A>` gains a merge path that folds per key with the supplied operator
  instead of replacing the value; absent key = the operator's identity.
- The cell declares `Replicable`, which — for free, via CP-F2's KSP marker scan —
  makes its ports carry `MergeClass.IDEMPOTENT` on the `MERGE_IDEMPOTENCE` axis, so a
  non-idempotent accumulator wired to a replicated sink is refused at link time
  (CP-F3) instead of silently drifting.
- **Keep `GroupByCell` exactly as is.** It is the correct default for non-replicated
  use and must stay byte-identical; the new cell is a sibling, not a replacement.
- Migrate `demo/exchange`'s `MapMergeCell` onto it and delete the demo-side class.

**Test.** `MergeableGroupByTest` — merge laws (commutative, associative, idempotent
where declared) under 100 generative merge orders; a three-replica mesh converges to
the batch groupBy of the union of all inputs while gossiping only aggregates.
**Controls**: (a) the plain `GroupByCell` under the same replication loses a
concurrent partial sum on some seed; (b) a non-commutative merge operator diverges by
delivery order, proving the law is load-bearing.
`ExchangeCompositionExitTest` must stay green after the demo migration, and its gossip
volume should measurably drop (assert aggregate-sized, not input-sized, deltas).

---

## CP-G2 — Nature vectors cross the wire — P1 · Medium · `wire`+`link`

- **Context**: FRESH (agent θ; type-system context, reads CP-F1–F3)
- **Parallel**: with G1, G3, G7
- **Files**: `kernel/.../cell/wire/WireCodec.kt` (additive frame field),
  `kernel/.../cell/wire/WireEdgeLink.kt`, `kernel/.../cell/port/Link.kt`,
  `gen/.../wire/ContractDescriptor.kt` (vector serialization).

**Problem.** `handshake()` already takes `counterpart: NatureVector` and reconciles it
location-transparently — but *every caller passes `NatureVector.DEFAULT`*
(`Link.kt:288`, the bridged overload's own comment names this a follow-on). So a
bridged link agrees on a verdict only because both ends independently resolve the same
local descriptor; a genuine cross-host nature mismatch is not detected. The seam
exists and is unused.

**Implement.** Ship the endpoint's `NatureVector` in the link-request frame (additive
`WireFrame` field, absent ⇒ `DEFAULT` ⇒ today's behavior verbatim) and pass it as
`counterpart` from `bridgeTo`/`bridgeFrom`. Sparse encoding — only declared axes ride;
a fully-default vector adds zero bytes. Unknown axis ordinal from a newer peer =
ignore that axis, never refuse (forward compatibility: an old peer must not reject a
new peer's link over an axis it cannot name).

**Test.** `BridgedNatureRefusalTest` — a producer on host A whose port genuinely lacks
a level the consumer on host B requires is `Rejected(mismatch)` at link time, with the
same `NatureMismatch` on both sides; the mirrored-compatible case links. Extend
`BridgedHandshakeTest`'s `localVerdict == remoteVerdict` assertion to a case where the
vectors actually differ. **Controls**: (a) `counterpart` forced to `DEFAULT` (today)
accepts the bad link and drops deltas silently at first emission; (b) a peer that
omits the frame field still links, proving the additive default.

---

## CP-G3 — Shard-to-shard `StateRequest` replay; retire the router's `routed` ledger — P1 · High · `repl`+`data`

- **Context**: FRESH (agent ι) · **Parallel**: with G1, G2, G7
- **Files**: `kernel/.../cell/data/PartitionedCell.kt` (`routed`/`ledger` `TagState`,
  `repartition`, `beginRepartition`/`endRepartition`),
  `kernel/.../cell/replication/Replication.kt`, spec
  `doc/spec/40-distribution/42-replication.md` §Interest-scoped instance sets.

**Problem.** CP-D3 promised "`repartition` = interest reassignment + `StateRequest`-
driven replay (retiring the bespoke `routed` ledger)". Half landed: the interest
reassignment is real, but replay still reads a router-local total-interest `TagState`
(`PartitionedCell.kt:83` `routed`, `:301` `ledger`). That means the router holds a full
copy of every shard's membership — O(total state) in one place, which is exactly what
partitioning is supposed to avoid, and it only works because the router is in-process.

**Implement.** On a flip, the gaining shard pulls the moved range from the losing shard
via `StateRequest(since)` over the ordinary link path — the same machinery a re-announce
drives — instead of the router replaying its own ledger. Tags are carried verbatim,
never re-minted (the existing invariant). Delete `routed`; keep `ledger` only if
`memberships()`/catch-up genuinely needs it, and if so scope it to interest rather than
total. The router keeps the routing table and the epoch, and stops keeping the data.

**Test.** `ShardStateRequestReplayTest` — repartition across shards on *different hosts*
with the router holding no membership; final board equals batch groupBy on 100 seeds.
Assert the router's retained state is O(shard count), not O(elements) — a direct
memory/size assertion, so the regression is caught if the ledger creeps back.
**Controls**: (a) `StateRequest` suppressed → the moved range vanishes; (b) re-minted
tags → duplicate counting. `PartitionedShardsAcrossHostsTest` and
`PartitionedCellTest` stay green unchanged.

---

## CP-G4 — Repartition across a real bridge without a co-located router — P2 · Medium · `repl`

- **Context**: CONTINUE CP-G3 (same agent — this is the payoff of removing the ledger)
- **Parallel**: NONE within ι · **Files**: `PartitionedCell.kt`,
  `kernel/.../cell/host/LocationRegistry.kt`.

**Problem.** With the ledger gone, the standing note from the D-tail review can finally
be closed: today the control plane holds direct `ShardCell` references and only the data
plane crosses the wire, so a repartition has never actually been exercised with the
router and its shards on opposite sides of a bridge.

**Implement.** Route the control plane (`assign`, `beginRepartition`/`endRepartition`
epoch announcements) through the same bridged link path as the data plane, so a shard is
addressed by ref, not by object. No new protocol — reuse the routed-command path.

**Test.** `BridgedRepartitionTest` — router on host A, two shards on hosts B and C over
real bridges, mid-run repartition racing a shard migration, zero loss on 100 seeds.
**Control**: control-plane messages dropped while data-plane flows → the flip half-
applies and the board forks, proving the control path is really on the wire.

---

## CP-G5 — Close A–F, A–C, A–O *inside* the exchange graph — P2 · Medium · `demo`

- **Context**: FRESH-ish (agent κ; a demo-builder context, like CP-E1's) — **after
  G1 and G3 merge**; rebase onto main.
- **Parallel**: NONE (this is the evidence join) · **Files**: `demo/exchange/**` only.

**Problem.** `ExchangeCompositionExitTest` exercises the replication × partition ×
durability pairings end-to-end, and C–B / C–F / A–B strongly. But glitch-free × wire
(A–F), glitch-free × absorbing-operator (A–C) and A–O are today proven only by their
own unit tests (`GlitchFreeBridgedDiamondTest`, `GlitchFreeOperatorSuiteTest`) — not by
the one combined graph that is supposed to be the composition evidence. The changelog
says so honestly; this ticket removes the caveat.

**Implement.** Two additions to the demo graph, both consumer-shaped rather than
test-shaped:
- a **bridged arm** into the glitch-free board (an aggregate arriving from the peer
  host, so the board's frontier settles across a real bridge — A–F/A–O);
- a **filtered arm** (e.g. a min-size order filter) upstream of the board, so an
  absorbing operator's `Progress` ack is on the critical path of the board's wave
  completion — A–C.

**Test.** Extend `ExchangeCompositionExitTest` — same 100 seeds, same batch-recompute
equality, now with both new arms live through the repartition / migration / kill -9 /
late-joiner sequence. **Controls** (both must still diverge, and are now *also* the
proof the new arms are load-bearing): (a) frontier off → torn board; (b) absorb-ack
suppressed on the filtered arm → the board's final wave stalls. Update the pair matrix
in `doc/COMPOSITION-STATUS.md`.

---

## CP-G6 — Adapter synthesis: the `Adapt` arm — P3 · High · `link` **(TRIGGER-GATED — do not start)**

- **Context**: FRESH · **Files**: `kernel/.../cell/port/NatureNegotiation.kt`,
  NEW rank table, `doc/spec/20-dataflow-semantics/` §Negotiation.

**Entry condition (the only reason to open this).** Two or more *independent* graphs in
the repo hand-stack the same adapter sequence to satisfy the same axis mismatch. CP-E2's
probe produced zero instances, which is why `reconcile()` deliberately has no `Adapt`
arm, no planner and no auto-insertion. **This ticket stays closed until the trigger
fires** — it is written down so the design intent survives, not so someone builds it.

**If it fires.** `reconcile()` gains `Adapt(plan)` beside `Direct`/`Refuse`; adapters
are ranked, the plan is deterministic and inspectable, and auto-insertion is opt-in per
link. Not a planner over arbitrary chains — a rank table over the ~4 scoped axes.

**Test.** `AdapterSynthesisTest` — each triggering mismatch yields the same plan every
run; **control**: rank table off → `Refuse`, i.e. today's behavior, so the feature is
strictly additive.

---

## CP-G7 — Research: upstream frontier traversal over multiplex ports — P3 · High · `research`

- **Context**: FRESH (agent λ) · **Parallel**: with everything (spike, no merge
  commitment) · **Files**: `doc/spec/90-roadmap/95-research-plan.md`, throwaway spike
  branch. Reads `kernel/.../cell/consistency/WaveFrontier.kt:50`.

**Problem.** `WaveFrontier` computes wave completeness from a **static link set** — the
edges present when the wave opened. The `ponytail:` marker at `WaveFrontier.kt:50` names
the ceiling: real "describe your frontier" upstream traversal needs multiplex ports
(G-13). Consequence today: unwaved traffic passes straight through, and a frontier
cannot be computed through a cell that does not itself participate in the protocol.

**Deliverable.** Not code — a decision. Does the frontier need transitive upstream
traversal, or is the static link set plus absorb-acks (CP-A3) sufficient for every
structure we actually build? Answer with: a concrete graph that the static frontier gets
*wrong* (or a proof there isn't one), the multiplex-port shape G-13 would need, and a
cost estimate. **Exit**: either a ticket for the real thing, or a spec paragraph
promoting the current design from "ponytail shortcut" to "decided".

---

## Explicitly NOT ticketed

- **COLOR in link reconciliation.** Excluded by design, not by omission: execution color
  is a placement/co-host property validated at spawn, and a link legitimately crosses
  colors (a blocking producer feeding a pure consumer on another host is normal).
  Reconciling it at the link would manufacture false refusals. Trigger to revisit: a
  real graph where a crossed color at a link causes a silent drop.
- Carried forward unchanged from `COMPOSITION-TICKETS.md`: membrane couplings (G-53),
  leader election (R1), placement engine (R5), partition-structure taxonomy,
  promotion × replication, the weighted operator family (E6).
