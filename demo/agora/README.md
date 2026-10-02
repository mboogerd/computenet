# agora — a collaborative argumentation graph on the cell kernel

The first application module (M17): claims with a credence in [0,1],
recursively determined by per-user **stances** and by **attack/support
edges** whose influence is weighted by the edge's own credence.

## The non-naive mapping: relations are cells

The naive encoding of an argumentation graph — arguments as cells, relations
as links — cannot express the thing that makes real argumentation
interesting: **attacking the relation**. "B attacks A" is itself a claim, and
someone can argue that *the attack doesn't hold* without arguing against B.

So every edge is an `EdgeCell : ClaimCell`: it has its own stances, its own
incoming edges (including edges from other edges), and its own credence. Its
influence at the target is `credence(edge) × credence(source)` — attack the
edge and its influence fades, recursively.

## Semantics

`DfQuad` (DF-QuAD adapted to weighted edges, `semantics/Semantics.kt`):
probabilistic-sum aggregation of attack/support energies, base score = the
stance mean clamped into `[0.01, 0.99]`, and the winning side's surplus moves
the base toward its extreme. Pure functions — the incremental cells and the
batch reference solver in the exit test share them, so incremental == batch
tests the propagation machinery, never parallel math.

## Cycles (spec 21 §Cycles)

Mutual attacks are legal and common. The service owns the topology and uses
the kernel's admitted staged links and feedback inlets for the decided cycle
model (93 I-5/I-6):

- **Emission is exact** — no per-cell ε-gate (I-6 explicitly rejects outlet
  gates: they silence fan-out subscribers).
- The service owns the topology, so it detects the edge that closes each
  cycle and designates it a **head**: that edge's admitted source link lands
  on its kernel `PropagateFeedbackInlet` at threshold `quiescence` (default
  1e-3). The kernel weak tier absorbs a returning lap whose `size()` is at or
  under it, gating re-origination and never the outbound broadcast.
- Termination physics: the base clamp keeps single-cycle loop gain < 1, so
  laps contract geometrically; the head threshold stops them ~5× earlier
  than floating-point resolution would. Non-contractive multi-cycle
  interleavings remain the open G-19 residual — the step-budgeted 100-seed
  exit test is its empirical probe.
- Every claim and edge hop is a `HostManagementApi.connect(...,
  LinkOptions(staged = true))` link, so it is queued and visible to topology
  admission while preserving magnitude scheduling.

## Magnitude scheduling (spec 34 decision 7)

Every credence/influence delta implements `Magnitude` with
`size = |change|`; the host is constructed with
`AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS)`, so cells
holding dramatic staged changes dispatch before micro-adjustments
(`MagnitudePriorityTest` proves the ordering both ways). All wiring is
**admitted and staged** through the host queue, so co-hosted links do not fuse
around the scheduler and cycle admission sees the complete graph.

## Run

```
./gradlew :demo:agora:run                                  # volatile, port 8080
./gradlew :demo:agora:run --args="8090 --journal /tmp/agora"   # kill -9 safe
```

- `GET /` — minimal debug UI (create claims/edges, set stances, live SSE bars)
- `POST /op` — `action=claim|edge|stance|remove` (form-encoded; edges and
  stances address any node id — edges are claims)
- `GET /graph` — nodes + credences as JSON; `GET /events` — SSE stream

Durability = one file in the journal dir, `host.journal`, carrying the
write-ahead topology records and data frames. Each claim or edge creation is
one `GraphSpec` delta; restart re-applies its topology under the recorded refs
and restores the links before replaying frames (with catch-up baselines
suppressed — the journal holds the originals, and re-emitting them would
clobber recovered state). Replay re-transmits idempotent duplicates in-process
but re-journals nothing (computenet-xy7w4.1): the journal grows with live
traffic only, and a startup checkpoint taken right after
`Recovery.awaitApplied()` compacts the replayed tail while carrying the live
topology fold. `remove` cascades over dangling edges; a crash mid-cascade can
leave a dangling influence until the next remove — accepted for v1 (single
host).

## Tests

- `DfQuadTest` — the pure math.
- `AgoraExitTest` — the M17 exit criterion: 100 seeds, random graph churn
  (edge-on-edge, cycles on odd seeds, removals), incremental == batch
  fixpoint (exact on DAG seeds, head-threshold-bounded on cyclic ones), with
  a retraction-blind reference that must diverge.
- `CycleQuiescenceTest` — mutual/self/3-cycles + the headless FP probe.
- `MagnitudePriorityTest` / kernel `MagnitudeSchedulingTest` — scheduling order.
- `DurabilityTest` — codec round-trip (K2 seam) + kill -9 recovery.
- `RestartJournalGrowthTest` — repeated restarts with no new traffic leave
  the journal's record count unchanged and credences equal (computenet-vcrc7,
  computenet-xy7w4.4).
- `AgoraServerTest` — HTTP/SSE smoke, including attacking an attack.
