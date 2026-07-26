# 96 — Incremental-Engines Plan (E-milestones)

**Status**: Proposed — no E-item is committed work until scheduled.
**Sources**: `doc/research/incremental-engines/` (deep research 2026-07-23: DBSP/Feldera,
Timely/Differential/Materialize, LASP/CRDT-lattice; per-fact provenance in docs 01-05);
backlog `06-or-map-tagged-map-delta.md` and `consistent-multiview-snapshot.md` (absorbed
here); 95 §R8 (promoted by E1).
**Implementation**: none — this document is the work list. Baseline pinned at commit
`125b9e0`; re-verify named seams if the pin drifts (parallel sessions land milestones
mid-run).

## How to read this plan

- Milestone ids are **E1-E6** (scoped to this document; they do not claim slots in 92's
  M-space — 92 carries a cross-reference). Work items are `E{milestone}.{n}` in the 94
  house format: heading `— P{1-3} · {High|Medium} · \`{code-path}\``, body fields
  **Spec** / **Spec change** / **Implement** (+ `Test:` clause) / **Depends** /
  **Unblocks**.
- **Spec-first**: the first item of each milestone makes the spec text exist; later items
  make it true of the code (AGENTS.md authority order).
- The governing borrowing rule from the research (05-gap-mapping): **weights,
  arrangements, and total orders live inside a replica; tags, lattices, and vector
  frontiers cross replicas.** Every item below sits on one side of that line.
- Research-gated corners are named-and-excluded per item, pointing at new 95 entries
  **R10-R17** (collected at the end of this document; appended to 95).
- Wire policy throughout: additive `@SerialName` payloads only; kernel stays
  transport-neutral; a new `@Contract` interface only where a genuinely new op surface
  exists (called out per item).

### Milestone parallelism

```
E1 (data/repl) ──────────────┐
E2 (glitchfree/host) ─┐      ├─→ E6 (data; last by design)
                      ├─→ E3 (repl; E3.4 needs E2.3's WaveFrontier)
E5 (context/link) ────┘      └─→ E4 (data; only the Replicable-eviction
                                       restriction waits on E3.7)
```

E1 ∥ E2 ∥ E5 are disjoint code paths and can run as concurrent waves.

---

## Milestone E1 — Convergent keyed structures (OR-map) ⚠ PROPOSED

Closes **G-23** (promotes 95 §R8 direction 1 to decided). `MapDelta` carries no causal
tags, so every map-shaped edge (`MapCell`, `CombineLatestCell` — F-1, `LookupJoinCell`,
`GroupByCell` outputs) is single-writer-or-diverge; this is the ceiling on distributing
any map feature (backlog 06). The research verdict (research 03 §4, 05 gap 2): the
Riak-map/delta-ORMap design is the settled answer, and ComputeNet is closer than it
looks — `KeyedSetCell` already does per-key observed-remove with atomic retract+add, and
`Timestamp(sourceId, counter)` tags are already dot-shaped. **Decision recorded here
(resolves backlog 06's open choice): additive new delta type** — `MapDelta` and its
single-writer cells untouched, `KeyedSetCell` untouched. The OR-map follows `SetCell`'s
tombstoned idiom (dels stored as covered dots), which makes Riak's deferred-operations
list unnecessary: a remove's dots arriving before their put simply sit in `dels` and
cover the put on arrival, exactly as `SetCell.applyRemote` already behaves. The
tombstone-free (context-only) wire form is deliberately *not* in this milestone — it
requires the causal-merging condition (research 03 §2) whose delivered-watermark
prerequisite is E3 (→ R10).

### E1.1 — Spec: the OR-map and the tagged-map convergence class (G-23, R8) — P1 · High · `spec`
**Spec**: 20/24 §Required next steps + a new §Tagged maps subsection; 40/42 §Design as
implemented (mergeable-class roster); 91 (G-23 row gains the planned-realization pointer);
95 (R8 annotated as promoted).
**Spec change**: define `TaggedMapDelta<K, V>(puts: Map<K, Map<Timestamp, V>>,
dels: Map<K, Set<Timestamp>>)` — per-key live *dots* carrying values, tombstoned
observed-remove dots; merge = pointwise union (idempotent because a dot's value is
immutable); presence = any live dot (add-wins); value = LWW **by dot order**
`(counter, sourceId)`, never wall clock; `remove(k)` = reset-remove (tombstone all
observed dots; concurrent puts survive as their unobserved dots). Record the decided
points from research 03 §4: one shared causal namespace for the whole map (per-key
contexts re-admit stale values on key re-creation — cite Riak); tombstoned dels subsume
deferred context ops; embedded values restricted to the idempotent-mergeable
(`MergeablePayload`) class (Riak's embedded-counter anomaly is the documented
counterexample); dot-metadata bloat is a codec-layer concern from day one. Record the
Lasp determinism caveat (research 03 §1) as normative for downstream adopters:
tag-precise removes are what keep value-keyed derivation deterministic.
**Implement**: spec text only; cite `doc/research/incremental-engines/03` §2, §4 and 05
§Gap 2. **Unblocks** E1.2-E1.6.

### E1.2 — `TaggedMapDelta` + `OrMapCell` core (G-23) — P1 · High · `data`
**Spec**: 20/24 §Tagged maps (E1.1).
**Implement**: `kernel/.../cell/data/OrMapCell.kt`: `@Serializable
@SerialName("TaggedMapDelta")` data class implementing `MergeablePayload` — additive wire
encoding (new `@SerialName` payload registered beside `SetDelta`; no frame-type change).
`class OrMapCell<K, V> : Cell, Stateful` with `inlet: Use<MapOps<K, V>>` (reuses the
existing `@Contract MapOps` — **no new contract interface, no gen/ descriptor work**) and
`outlet: Subscribe<Propagate<TaggedMapDelta<K, V>>>`. Local `put` mints a dot from the
derived replay-stable source
(`UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}")`, counter in
snapshot — the `SetCell`/`MintedTags` M10.1 pattern) and tombstones the key's
previously-observed dots in the **same delta** (`KeyedSetCell`'s atomic retract+add,
lifted to dots); `remove` tombstones observed dots only (effective-only no-op otherwise).
`membership()`/`value(k)` per E1.1. `onLinked` catch-up as delta-from-empty, tombstones
included; snapshot/restore incl. counter; compact dot encoding in the serializer (group
dots by sourceId — the Riak bloat lesson). Test: `OrMapCellTest` property-style — any
interleaving/duplication/reordering of a fixed dot set yields identical membership and
per-key values (mirror `SetCellTest`); concurrent put/remove → add-wins; re-put atomicity
(never two live values, never zero); divergence control: the same schedule through
`MapDelta`/`MapView` order-flips.
**Depends**: E1.1.

### E1.3 — `OrMapCell` replication: gossip, baseline, re-origination (G-23; C-10 rule) — P1 · High · `repl`
**Spec**: 20/24 §Tagged maps; 40/42 §Design as implemented (the cell joins the mergeable
class); 20/21 §Pull.
**Implement**: make `OrMapCell` `Replicable<TaggedMapDelta<K, V>>`: `deltaInlet` +
`applyRemote` re-emitting **only new dot information** (echo termination),
re-**originating** per the C-10 rule (`outlet.originate`), tags byte-identical;
`StateRequest(since: TagFrontier?)` handler with `sinceFilter`/`currentFrontier` (copy
`SetCell`'s, dels included); `ReBaseline` dead-source fencing in the fold (the
`TagState.deadSources` discipline). Test: `OrMapConvergenceTest` mirroring
`SetConvergenceTest`/`ReplicatedSessionTest` — seeded `SimulationController` runs over
3-replica meshes with partition/heal, invariant `∀ replicas: membership & values equal at
idle`, duplicate delivery across a diamond dedups, replica wave-id assertion
(re-emissions carry the replica's own sourceId); divergence controls: `applyRemote`
without the new-dots filter loops; untagged application diverges.
**Depends**: E1.2. **Unblocks** E1.4, E1.5, E1.6, E3.3.

### E1.4 — Embedded mergeable values (G-23 residual; Riak counter-anomaly guard) — P2 · Medium · `data`
**Spec**: 20/24 §Tagged maps (embedded-value paragraph, E1.1).
**Implement**: when `V : MergeablePayload`, concurrent live dots for one key expose the
`mergeWith`-folded value instead of the LWW pick (the Riak "embedded CRDT" mode,
restricted to the idempotent-mergeable class — `PnCounterDelta`-shaped values compose;
plain counters are rejected by a runtime check pointing at the documented anomaly).
Opt-in multi-value read `values(k): Set<V>` for the non-mergeable case so apps can
resolve concurrency themselves. Test: two replicas concurrently `put(k, PnCounterDelta…)`
→ converged merged value on all replicas (seeded runs); control: LWW pick drops an
increment.
**Depends**: E1.3 (needs the gossip path to exercise concurrency).

### E1.5 — Adoption seams: `TaggedMapView` + `UntagCell` + join family (F-1) — P2 · High · `data`
**Spec**: 20/24 §Tagged maps (adapter paragraph) + §Operator library rows.
**Implement**: `TaggedMapView` fold (dots → current `Map<K, V>`) + `View.taggedMap()` in
`host/Observe.kt`; an `UntagCell<K, V>` adapter (inlet
`Propagate<TaggedMapDelta<K, V>>`, outlet `Propagate<MapDelta<K, V>>`, effective-only
per-key value changes) so `CombineLatestCell`/`LookupJoinCell`/`JoinCell` consume
**converged** tagged maps unmodified — the composition the research prescribes (05 gap 3:
feed convergent inputs to the deterministic recompute cells; do not rewrite each join).
KDoc on each join cell: the G-23 caveat is discharged when inputs arrive via
`OrMapCell`+`UntagCell`. Test: two replicas write an `OrMapCell`, both peers run
`UntagCell → CombineLatestCell`; outputs equal each other and a batch recompute over
converged state on every seed; control: raw `MapCell` inputs under the same schedule
diverge.
**Depends**: E1.3.

### E1.6 — Replicated `:demo:tiering` proof (G-23 acceptance; backlog 06) — P2 · High · `demo`
**Spec**: none new (realizes 20/24 §Tagged maps acceptance bullet).
**Implement**: a two-host bridged tiering variant where the manual re-tier edge is a
replicated `OrMapCell<String, Tier>` fused with the computed board through `UntagCell`;
two peers concurrently re-tier the same item and converge to one board — the map analogue
of shopping's convergence story. Test: mirror `demo/shopping`'s two-JVM convergence test
(real wire, seeded op schedules, final-board equality across JVMs, plus a same-item
concurrent-write case asserting one deterministic winner).
**Depends**: E1.3, E1.5.

**Dependency note (E1)**: E1.1 → E1.2 → E1.3 → {E1.4, E1.5} → E1.6. Entirely on
`data`/`repl`/`demo` paths; runs concurrently with E2.

---

## Milestone E2 — Vector-frontier observation edge ⚠ PROPOSED

Realizes backlog `consistent-multiview-snapshot.md` (**F-5**) and the gap-4 borrow;
advances **G-13**'s frontier residual and **G-40**. Target guarantee (research 04 §3,
Feldera phrasing): *every emitted composite output is the correct output for some
per-source vector frontier of this replica's inputs* — the coordination-free dual of
Materialize's scalar virtual time, which the research explicitly rejects (04 §4). Today
`CompositeSink` (host/Observe.kt) is point-consistent per outlet and honestly documents
its two blockers: name erasure through `GlitchFreeCell`'s replay, and absorbing edges
that never advance watermarks. This milestone extracts the frontier fold into a reusable
component, closes the absorb-ack hole, ships the wave-aligned multi-view sink, gates
non-monotone (absence-asserting) emission on the same frontier — CALM says some sealing
is unavoidable there (research 03 §5), and per-wave sealing is the cheapest ComputeNet
has — and lands the balanced-transfer benchmark as the acceptance suite.

### E2.1 — Spec: the observation frontier and internal consistency (F-5, G-13 partial, G-40) — P1 · High · `spec`
**Spec**: 20/22 new §The observation frontier (after §Local glitch-freedom); 20/22
§Completeness over silent or stuck edges (absorb-ack rule made normative for operator
cells); 20/24 §Operator library (SemiJoin/CombineLatest emission-gating paragraphs);
backlog `consistent-multiview-snapshot.md` absorbed (marker note).
**Spec change**: (1) the guarantee statement: internal consistency ("every output is the
correct output for some subset of the inputs provided so far", cite research 04 §3)
achieved as per-source-**vector**-frontier alignment; scalar virtual time rejected (04
§4, one-line rationale). (2) The aligned-composite rule: a composite for wave `(s, t)` is
assembled only after every contributing view has settled every wave ≤ the shared
frontier; per-name inlets preserve view identity (dissolving `observeAll`'s blocker 1).
(3) The absorb-ack rule: an operator cell whose waved input yields no effective output
MUST advance downstream watermarks via `Progress(sourceId, thru)` (dissolving blocker 2;
G-40 family). (4) Non-monotone emission gating: antijoin membership flips and outer-join
null-extensions are absence assertions — non-monotone (CALM) — and emit only at wave
completeness, coalesced to the wave's net effect. The balanced-transfer suite is the
named acceptance benchmark.
**Implement**: spec text only; cite research 04 §3-4, 02 §7, 01 §6, 03 §5.
**Unblocks** E2.2-E2.6.

### E2.2 — Absorb-acks from absorbing operator cells (G-40 residual) — P1 · High · `data`
**Spec**: 20/22 §Completeness over silent or stuck edges (E2.1 rule 3).
**Implement**: a small shared helper (`data/AbsorbAck.kt`) invoked at the end of each
operator cell's waved handler: if the wave produced no outlet emission, send
`Progress(ctx.timestamp.sourceId, ctx.timestamp.counter)` down the outlet's links via
`Protocols.sendDownstream` (the `Progress` lane `GlitchFreeCell` already folds). Adopt in
the absorbing cells: filter, `SemiJoinCell`, `IntersectSetCell`, `GroupByCell`
(effective-only swallow), `CombineLatestCell`/`LookupJoinCell` (value-equal swallow). No
wire work — `Progress` rides existing protocol machinery. Test: a
source→filter→join diamond where the filter drops one arm's wave completes without
waiting for the next write; control: helper disabled, the last wave stalls (the
documented slotfinder failure).
**Depends**: E2.1. **Unblocks** E2.3, E2.4.

### E2.3 — `WaveFrontier` extraction + the aligned multi-view sink (F-5 core) — P1 · Medium · `glitchfree`+`host`
**Spec**: 20/22 §The observation frontier (E2.1 rule 2).
**Implement**: extract `GlitchFreeCell`'s frontier fold (edges/floors,
`EdgeOpen`/`EdgeClose` handling, per-edge per-source watermarks, suspended-edge set,
`WaveMode` WAIT/DEGRADE, RE-SCOPE hook, flushed high-water) into an internal
`consistency/WaveFrontier.kt`; `GlitchFreeCell` delegates (behavior byte-identical — the
existing glitch-free suites are the regression harness). New `host/AlignedObserve.kt`:
`AlignedCompositeCell` with one **named** inlet per view (each `Propagate<D>` + its
`View<D, S>` fold — name erasure solved structurally), per-wave delta buffers, one
`WaveFrontier` spanning all inlets' edges; at wave completeness apply the wave's buffered
deltas per-source-counter-ordered, then emit **one** composite snapshot (effective-only;
baselines install as arm state exactly as `GlitchFreeCell` does). Host API
`ManagedHost.observeAligned { set("items", refs.items); … }: ObservationSink<…>` beside
the honest point-consistent `observeAll` (kept; its KDoc deferral updated to point here).
Test: mirror `GlitchFreeDiamondTest` — one source fans to two derived cells into the
aligned sink; ≥200 seeded schedules; invariant: no composite assembled from mixed waves;
control: `CompositeSink` under the same seeds trips; plus a two-JVM bridged variant
(EdgeOpen/EdgeClose and `Progress` already cross as frames) or a documented limitation.
**Depends**: E2.1, E2.2. **Unblocks** E2.4, E2.5, E2.6, E3.4 (shares `WaveFrontier`).

### E2.4 — Frontier-gated antijoin / outer-join emission (gap-3 borrow; SemiJoin flicker) — P1 · Medium · `data`
**Spec**: 20/24 §Operator library (E2.1 rule 4).
**Implement**: an opt-in `emitOnFrontier` mode on `SemiJoinCell` (and the null-extension
path of `CombineLatestCell`) built on `WaveFrontier`: buffer the wave's input deltas
across both inlets, reconcile at completeness, emit the wave's **net** minted enter/exit
set — a transient enter+exit within one wave cancels before the wire, so opposing
in-flight updates no longer flicker and later-retracted null-extensions (the
internal-consistency essay's exact outer-join failure, research 04 §3) are never emitted.
Default stays ungated (wrapper semantics unchanged). Explicitly *not* a smarter
convergent cell — the research rejects that (05 gap 3): absence-based emission is
non-monotone; sealing is unavoidable. Test: seeded opposing-update schedules through a
shared-source diamond; invariant: the outlet never shows enter-then-exit within one wave
and no null-extended row is later retracted by the same wave; control: ungated mode
flickers.
**Depends**: E2.3.

### E2.5 — Balanced-transfer internal-consistency acceptance suite (gap-4 benchmark) — P1 · High · `data` (test-only)
**Spec**: 20/22 §The observation frontier (names this suite as acceptance).
**Implement**: `kernel/src/test/.../InternalConsistencyTest.kt`, transcribing the
balanced-transfer experiment (research 04 §3): a generative stream of balanced transfers
(every debit paired with a credit in one wave) driven through (a) a credit/debit join,
(b) a grouped aggregate, (c) an outer self-join — each observed through `observeAligned`
and the E2.4 gated cells; invariant checked at **every** observed output, not just idle:
total == 0 / no output corresponds to no input prefix. Controls: point-consistent
`CompositeSink` and the ungated outer join both violate the invariant (the suite has
teeth).
**Depends**: E2.3, E2.4.

### E2.6 — `demo/shopping` adoption (F-5 acceptance) — P2 · High · `demo`
**Spec**: none new.
**Implement**: replace shopping's four-hub `broadcast()` with one `observeAligned` sink
pushing one SSE frame per settled wave; delete the documented skew. Test: exactly one
frame per op; no frame shows a `produce`/`wanted` item absent from `items` (the backlog's
acceptance bullets), including two-JVM peer mode.
**Depends**: E2.3.

**Dependency note (E2)**: E2.1 → E2.2 → E2.3 → {E2.4, E2.6} → E2.5. Independent of E1;
E2.2 (`data`) and the extraction half of E2.3 (`glitchfree`) are disjoint and can run as
one wave.

---

## Milestone E3 — Delivered-watermark substrate (one primitive, two consumers) ⚠ PROPOSED

The layering insight of the research (05, gap 7 note): cross-replica frontier
coordination (**G-39/G-40** residuals) and causal stability for tag/tombstone GC
(**G-42**, gap 6) are *the same primitive at two freshness levels* — "how far has each
replica delivered", read at wave granularity, is JoinBarrier coordination; its terminal
state ("all replicas past τ") is the GC trigger. Naiad's progress protocol is the only
verified no-consensus design in this space, but its signed accumulator is non-idempotent
and membership-fixed (research 02 §3), so ComputeNet takes the semilattice reformulation:
each replica gossips a per-source delivered low-watermark row, merged by pointwise max —
idempotent, redelivery-proof, the exact shape of `PnCounterDelta`. Costs accepted up
front (research 03 §3): membership completeness (`replicasOf` is eventually consistent —
gated, R13), idle-replica liveness (heartbeat — Bauwens' fix), and frozen stability on
unclean departure (R13, relates R1/G-45).

### E3.1 — Spec: delivered watermarks, replica frontiers, causal stability (G-39/G-40 residuals, G-42) — P1 · High · `spec`
**Spec**: 40/42 new §Delivered watermarks and causal stability; 20/22 §Completeness…
(cross-replica extension paragraph); 20/24 §Tag continuity (compaction-trigger note,
E3.7 forward-pointer); 95 (new R13/R14; cross-link R1).
**Spec change**: (1) the primitive: per replica-set, each instance owns one row
`replicaSlot → (sourceId → deliveredThru)` where `deliveredThru` is the **contiguous**
delivered prefix per source (tag sources and wave sources share the `(UUID, Long)`
monotone shape — one vocabulary serves both); rows merge by pointwise max — a
join-semilattice, gossiped like `PnCounterDelta`; rows are never retracted (progress is
monotone; the precision lost vs Naiad is named). (2) The two reads: *frontier read* —
wave `(s, t)` is replica-complete when every open membership row has `row[s] ≥ t`;
*stability read* — τ is causally stable (research 03 §3, Def 5.1) when no concurrent op
can still arrive, i.e. the frontier read's terminal state; safety property carried from
Naiad: no local read may run ahead of the true global frontier. (3) The three named costs
and dispositions: membership read gated on the `replicasOf` fold with the completeness
caveat (R13); idle liveness via heartbeat rows; clean departure closes a row, unclean
departure freezes stability (R13). (4) Epoch interaction: a `ReBaseline`-superseded
source's watermark column is fenced with the source (R14).
**Implement**: spec text only; cite research 02 §2-3, 03 §3, 05 gaps 6-7.
**Unblocks** E3.2-E3.7.

### E3.2 — `WatermarkDelta` + `WatermarkCell` (the gossiped lattice) — P1 · High · `repl` ✅ LANDED (CP-B1)
**Status**: the pointwise-max lattice + `Replicable` cell landed via composition
ticket CP-B1 (`kernel/.../cell/data/Watermark.kt`), with the merge-law harness
(commutative / idempotent / monotone / associative / gossip-no-regression). The
seeded mesh-convergence run + signed-delta control remain open for E3.3+.
**Spec**: 40/42 §Delivered watermarks (E3.1 point 1).
**Implement**: `kernel/.../cell/data/WatermarkCell.kt`: `@Serializable
@SerialName("WatermarkDelta") data class WatermarkDelta(rows: Map<UUID, Map<UUID, Long>>,
closed: Set<UUID>) : MergeablePayload` — pointwise-max merge per (replica, source),
grow-only `closed` (a cleanly departed replica's row stops constraining reads); additive
wire encoding; **no new `@Contract`** (the cell is written locally via `advance`, not a
contract inlet — nothing flows through gen/). `WatermarkCell : Cell, Stateful,
Replicable<WatermarkDelta>` with a derived replay-stable slot id
(`nameUUIDFromBytes("watermark-slot:…")`), `applyRemote` re-emitting only raised entries
(echo termination), `onLinked` full-state catch-up, snapshot/restore. Local surface:
`advance(sourceId, thru)` (monotone, effective-only) and `close()`. Test:
`WatermarkCellTest` + a `PnCounterReplicationTest`-style seeded mesh run — pointwise-max
convergence under duplication/reorder/partition-heal; control: a signed-delta
(Naiad-verbatim) accumulator under gossip redelivery double-counts (the documented
reject).
**Depends**: E3.1. **Unblocks** E3.3-E3.7.

### E3.3 — Delivered-tracking seams + idle heartbeat (Bauwens liveness) — P2 · Medium · `data`+`repl`
**Spec**: 40/42 §Delivered watermarks (E3.1 points 1, 3).
**Implement**: (a) contiguity tracking where remote deltas fold: a `DeliveredFrontier`
helper (per-source max-contiguous prefix + out-of-order holdback set) updated in
`SetCell.applyRemote`, `PnCounterCell.applyRemote`, and E1.3's `OrMapCell.applyRemote`
(that edge optional/non-blocking), plus local mints (trivially contiguous);
(b) `Replication` spawns one `WatermarkCell` per replicated logical id
(`watermark:{logicalId}`), replicated alongside, and wires each cell's
`DeliveredFrontier` in; (c) heartbeat: an idle replica republishes its unchanged row on a
host-scheduler cadence (and acks on every fold) so silence does not freeze the stability
read. Certainty Medium: holdback representation and heartbeat cadence are implementer's
choice. Test: seeded runs asserting `row[s]` never exceeds the truly delivered contiguous
prefix (safety) and reaches it at idle (liveness); an idle-but-heartbeating replica does
not block stability; control: heartbeat off → stability provably frozen.
**Depends**: E3.2 (+ E1.3 for the OrMap seam).

### E3.4 — Consumer (a): wave-granularity replica frontier — the JoinBarrier read (G-39/G-40 residuals) — P2 · Medium · `glitchfree`+`repl`
**Spec**: 20/22 §Completeness… cross-replica extension (E3.1 point 2).
**Implement**: a `ReplicaFrontier` read-view over the local `WatermarkCell`
(`completeAt(s, t): Boolean` = every open, non-closed membership row ≥ t) and an opt-in
hook on `WaveFrontier` (E2.3): an edge declared replica-fed adds
`ReplicaFrontier.completeAt` to its settlement predicate — a glitch-free join or aligned
sink spanning inputs arriving via *different* replicas of one logical source no longer
treats "my replica delivered it" as "it is complete". No new coordination messages (the
lattice is the only traffic). Test: two peers each feed one arm of a join from different
replicas of one logical `SetCell`; invariant: no join output at a wave a replica-set
member hasn't delivered; control: hook off → mixed-frontier output; departure runs
(closed row → barrier releases; vanished row → WAIT holds, Stall surfaces — the honest
R13 boundary).
**Depends**: E3.2, E2.3 (the only cross-milestone code edge — schedule after E2.3 lands,
or stub the hook behind an interface); coordinate with E3.3 (wave-source rows).

> **Amendment (PN-7 — interest-scoped settlement).** E3.4's `completeAt(s, t)`
> quantified over *every* membership row, which collides with PN-6's disjoint-
> `Interest` instance sets (plan F2): a disjoint-interest member never delivers
> waves outside its slice, so its row stays at bottom and a WAIT consumer stalls
> forever once shards join the mesh. PN-7 generalizes the read to
> `completeAt(source, counter, key)` — the quorum is the **covering subset** of
> members whose interest *admits* `key` (plan §3 Rule of settlement, spec 22
> §Interest-scoped settlement). All-`Total` collapses to E3.4's original behavior;
> a `null` key (no origin-key extractor) is unfiltered ⇒ byte-identical defaults.
> `LocationRegistry.instancesOf` is now an indexed membership read (perf cliff).
> **R13** is promoted from optional to **blocking** and lands here: filtering
> *shrinks* quorums, so a rowless joining covering member must **hold** the wave
> (the fence, on by default) rather than be skipped (premature release). The
> converged-membership barrier / DEGRADE quorum-shrink residual is **sequenced to
> PN-19**. Test: `ShardedReplicaFrontierTest` (board over a 2-slot × 2-copy
> instance set; 120 seeds) with controls (a) pre-PN-7 `members.all` never
> releases, (b) trivial frontier tears the board, (c) fence off releases early for
> a joining covering member.

### E3.5 — Consumer (b): the causal-stability read + GC proof harness (G-42 trigger) — P1 · Medium · `repl`
**Spec**: 40/42 §Delivered watermarks (E3.1 point 2); 20/24 compaction-trigger note.
**Implement**: `stableFrontier(logicalId): TagFrontier` on the
`Replication`/`WatermarkCell` surface = pointwise min over all open membership rows
(absent row ⇒ bottom — completeness-gated per R13); plus the invariant harness E3.7's
compaction will run under: (i) stability ≤ every replica's delivered watermark at all
times (the Naiad safety analog), (ii) stability is monotone, (iii) the GC safety
property — a mock reclaimer discarding del-tags ≤ `stableFrontier` never breaks
convergence (no later delta ever references a reclaimed tag as *new* information) across
seeded partition/heal/churn schedules; control: reclaiming at the merely-*locally*-
delivered frontier resurrects a removed element on at least one seed (proving stability,
not delivery, is the correct trigger — research 02 §4, 03 §3). No actual compaction here
— that is E3.7; this item delivers the read plus the proof harness.
**Depends**: E3.2, E3.3.

### E3.6 — Membership and departure gates (G-45 seam) — P2 · Medium · `repl`
**Spec**: 40/42 §Delivered watermarks (E3.1 point 3).
**Implement**: (a) membership for both reads = the `replicasOf` fold with its
announcement seams, snapshotted per read with the documented eventual-consistency caveat;
(b) clean departure: `Replication.evict`'s drain path calls `WatermarkCell.close()` for
the evicted slot before despawn, so stability resumes without it; a partition-suspended
replica does **not** close — its frozen row is correct (it may hold unique state);
(c) unclean departure (crash without close, mesh churn) leaves the frontier frozen —
surfaced as a `Stall`-family notice on the reads, **not** worked around: lease-based row
eviction is R13, explicitly out of scope. Test: 3-peer seeded mesh — evict mid-run →
stability advances past the closed row; kill without evict → stability frozen and the
notice observed (control asserts no silent unfreeze).
**Depends**: E3.2, E3.5.

### E3.7 — Stability-scoped tag/tombstone reclamation (G-42; G-25 rider) — P2 · Medium · `data`+`repl`
**Spec**: 20/24 §Tag continuity (compaction paragraph, E3.1 forward-pointer); 40/42
§Delivered watermarks.
**Implement**: consume E3.5: `SetCell`/`OrMapCell` compact `dels` (and fully-covered
`adds` entries) at or below `stableFrontier` at checkpoint time (riding the G-25
checkpoint compaction path); baseline/`StateRequest(since)` answers reaching below the
stable frontier fall back to full-state (the R10 direction-2 disposition). Test: run
E3.5's harness with the real reclaimer; a long-running seeded session with churn shows
bounded tombstone count; a late straggler below the stable frontier is impossible by the
harness's safety invariant (asserted); the full-state fallback path is exercised.
**Depends**: E3.5.

**Dependency note (E3)**: E3.1 → E3.2 → {E3.3, E3.4, E3.6}; E3.5 after E3.3; E3.7 after
E3.5. `repl`/`data` paths disjoint from E1's cell files and E2's observation edge except
the E3.4↔E2.3 seam.

---

## Milestone E4 — Lateness / waterline eviction ⚠ PROPOSED

Gap 6, event-time state: windowing shipped as key derivation with the honest caveat
"windows never evict" — window-keyed `TagState` and `GroupByCell` state grow forever.
Feldera's lateness → waterline design (research 01 §5) fits without coordination:
lateness is a per-inlet declaration; the waterline is a monotone max-minus-lateness
computed *in the dataflow itself*, riding per-source waves. What the research refutes
(0-3) is the comfortable framing: eviction is **not** a pure optimization. The decision
made here: eviction is destructive **and emits ordinary retractions** — a cell's state
remains equal to its integrated output, keeping catch-up, `Stateful` snapshots, and
per-peer recompute correct with zero new machinery; late-below-waterline arrivals are
dropped at the guarded inlet and side-channeled on a `late` outlet. Honest equivalence:
**incremental == batch over the late-filtered input**, conditional on the waterline being
derivable from a monotone event-time attribute — checked per pipeline, never assumed.
Restriction: destructive eviction of `Replicable` state is forbidden until E3.7 exists (a
replica evicting locally while a peer still gossips below the floor re-admits ghosts);
single-instance cells (`GroupByCell`, the join family) evict freely.

### E4.1 — Spec: lateness, waterline, and destructive eviction (gap 6, G-42 partial) — P2 · High · `spec`
**Spec**: 20/24 new §Lateness and waterlines (replacing the deferred-trigger bullet in
§Grouped aggregation); 20/21 §Incremental vs complete (the late-drop rule as a stated,
declared exception to "late elements are ordinary adds"); 20/22 (the waterline floor is
one more use of the monotone-clock shape — never a wave, never part of any completeness
set).
**Spec change**: (1) *lateness* is a per-inlet declaration bound to the event-time
extractor (`timeFn: (E) -> Long`, `lateness: Long`), a named `Serializable` value like
the `Windows` assigners so it survives graph-spec capture. (2) The *waterline* is min
over contributing sources of (max observed event time − lateness), monotone
non-decreasing, computed by an ordinary data cell — no wall clock, no coordination
(research 01 §5: Feldera's z⁻¹-delayed max; the delay here is the waterline edge itself).
(3) *Eviction is destructive and semantic*: state below the waterline drops **through the
ordinary retraction path** (dels flow, groups die, `MapDelta` removals emit), preserving
state = integrated output. (4) *Late arrivals below the waterline are dropped* at the
guarded inlet and re-emitted verbatim on a `late` side-channel outlet; record the refuted
Feldera claim (research 01 §5) and the conditional equivalence statement.
(5) *Replication restriction*: no destructive eviction of `Replicable` state until E3.7.
**Implement**: spec text only; cite research 01 §5, 04 §1-2, 05 gap 6.
**Unblocks** E4.2-E4.6.

### E4.2 — `WaterlineCell` + `WaterlineDelta` (gap 6) — P2 · High · `data`
**Spec**: 20/24 §Lateness and waterlines (E4.1).
**Implement**: `WaterlineDelta(floor: Long)` — `@Serializable
@SerialName("WaterlineDelta")`, `MergeablePayload` merging by max (idempotent — the one
weight-adjacent delta that IS gossip-safe); additive wire registration.
`WaterlineCell<E>(timeFn, lateness)`: inlet `Propagate<SetDelta<E>>`, outlet
`Propagate<WaterlineDelta>`; folds per-source max event time (source = the arriving
wave's `sourceId`), emits effective-only monotone floor advances; `Stateful` (per-source
maxima + last floor); late-join catch-up emits the current floor. No new `@Contract` —
delta-only cell. Test: seeded run over interleaved multi-source streams; invariants:
floor monotone on every prefix, floor ≤ min-source promise, duplicated delivery converges
identically; control: a max-over-sources variant admits a violation seed.
**Depends**: E4.1.

### E4.3 — Eviction inlet on `GroupByCell` + late-drop guard (gap 6) — P2 · High · `data`
**Spec**: 20/24 §Lateness and waterlines, §Grouped aggregation.
**Implement**: `TagState.evictBelow(predicate: (E) -> Boolean): SetDelta<E>` —
destructively drops live tags of matching elements, returning the dels-delta.
`GroupByCell` gains an optional `waterline` inlet (`Propagate<WaterlineDelta>`) plus
`keyTime: (K) -> Long` (window end); on floor advance, evict all elements whose window
key lies entirely below the floor via the ordinary retraction fold — groups die,
`MapDelta` removals emit under the waterline delta's wave. Main-inlet guard: an add with
`timeFn(e) < floor` is dropped from the fold and forwarded **verbatim** (original tags)
on a new `late` outlet; dels below floor are no-ops. Test: seeded windowed pipeline
(writers → union → tumbling group-by) with shuffled event times; invariants: post-idle
state equals batch recompute over late-filtered input on every seed; state bounded by the
lateness horizon; `late` carries exactly the dropped elements; control:
evict-without-retract leaves a late subscriber diverging from an old subscriber.
**Depends**: E4.2.

### E4.4 — Waterline lifecycle under source churn (G-42 partial) — P2 · Medium · `data`+`glitchfree`
**Spec**: 20/24 §Lateness and waterlines (source-retirement paragraph); 20/22 §Source
identity.
**Implement**: `WaterlineCell` retires a source's max contribution on (a) `EdgeClose` for
its edge and (b) a `ReBaselineNotice` superseding it (a RESTART'd producer's stale
pre-restart maximum must not gate the floor forever; its fresh epoch re-contributes from
scratch). Document the residual honestly: an idle-but-open source freezes the waterline —
ships frozen-but-correct, with an explicit `retire(sourceId)` management op as the manual
escape hatch (→ R15). Test: seeded churn run (source unlink, RESTART with re-baseline,
idle source) asserting the floor resumes after retirement and never advances past a live
source's promise.
**Depends**: E4.2; 94 W1.7 (edge events); coordinates with the `ReBaselineNotice` fold.

### E4.5 — `Evictable` seam across the join family (gap 6) — P3 · Medium · `data`
**Spec**: 20/24 §Lateness and waterlines (family-wide paragraph).
**Implement**: extract the E4.3 pattern into an internal seam shared by the other
`TagState`/`MintedTags` holders — `JoinSetCell` (per-side rows + minted pairs),
`SemiJoinCell`, `IntersectSetCell` — so a windowed join evicts both input indexes and
minted pairs below the floor (the DD reader-frontier analog scoped to the waterline: late
input below the floor is inadmissible, so no reader can distinguish the dropped history —
research 02 §4). Same destructive-with-retraction rule; same `late` guard on inlets
declaring lateness. Test: windowed equi-join == batch over late-filtered inputs;
minted-tag count bounded; control: evicting minted pairs without exit-tag emission leaves
tombstone-folding consumers dead (the M11.2 tag-hygiene control inverted).
**Depends**: E4.3.

### E4.6 — Generative lateness harness + demo adoption (gap 6; F-2 adjacent) — P3 · High · `data`
**Spec**: 50/52 (new invariant rows); 20/24 §Lateness and waterlines.
**Implement**: a seeded generative harness over the E4.2-E4.5 pipeline shapes with
configurable disorder and lateness violations; promote "incremental == batch over
late-filtered input" and "memory bounded by lateness horizon" to standing 52 invariants.
Adopt in `:demo:slotfinder`'s `byDay` fold (its natural event-time window) as the first
real consumer. Test: is the item — 100 seeds, with the E4.3/E4.5 controls kept
red-capable.
**Depends**: E4.3, E4.5.

**Dependency note (E4)**: E4.1 → E4.2 → E4.3 → {E4.4, E4.5} → E4.6. Needs nothing from
E1/E2; the single E3 coupling is the `Replicable`-eviction restriction (lifted by E3.7).
Reader-frontier compaction of consumer views is explicitly not here: `View`/`ObserveCell`
folds hold no history to compact; replicated-tag GC is E3.7.

---

## Milestone E5 — Cycles and incremental fixpoints ⚠ PROPOSED

Gap 5; **G-19** residual, **G-41**, I-5/I-6/I-23. The cycle-head model is decided and
partially built (`CycleHead`/`FeedbackInlet`, two-tier quiescence, fresh-wave
re-origination, hop guard, link-time admission) but termination-honest and
semantics-poor: an iteration has no identity beyond the head's private counter, escaping
outputs carry no association with the triggering outer wave, and nothing prevents
non-monotone operators inside a loop. The research supplies the missing package (05 gap
5): Naiad's ingress/feedback/egress structural triple (research 02 §1) gives iteration a
place in the context; DBSP's cycle rule (research 01 §4) proves the incremental loop is
the loop of the incremental body, makes semi-naive evaluation a corollary of the
delta-circulation ComputeNet already does, and defines termination as *a full iteration
producing only empty effective deltas* — which effective-only emission already detects.
**Decision**: the iteration dimension is a **nested context**
(`LoopContext(outerWave, iteration)` on `MessageContext`), not a `Timestamp` extension —
the wave id stays one-dimensional and per-source (20/22 rules untouched, wire additive);
the head's fresh-`Timestamp`-per-lap remains the wave mechanism. Weak-tier *convergence*
stays research-gated (95 R2): every item ships the honest bounded-lap contract. Target
adopter: `:demo:agora`'s grounded-extension fixpoint — set-valued (idempotent-merge,
strong-tier) recursion, terminating threshold-free.

### E5.1 — Spec: iteration context, cycle-rule semantics, stratification (G-19 residual, G-41; R2-gated corners named) — P2 · High · `spec`
**Spec**: 20/21 §Cycles (extend the decided model); 20/22 §MessageContext (third-field
extension) + §Topology versioning (cycle-region note); 20/24 (operator monotonicity
classification); 00/03 glossary (LoopContext, iteration).
**Spec change**: (1) Naiad's structural triple mapped onto admission (research 02 §1):
admitting a `FeedbackInlet`-closed cycle identifies the cycle region (SCC); edges
entering are **ingress** (stamp `LoopContext(outerWave = incoming wave, iteration = 0)`),
the `FeedbackInlet` is **feedback** (fresh head wave per lap, iteration+1, outerWave
preserved), edges leaving are **egress** (strip). Nested cycles rejected at admission —
single-level `LoopContext` only (→ R16). (2) `MessageContext.loop: LoopContext?` —
additive, default null, never part of the wave join key; transparent flow copies it; the
head's re-origination is its only writer. (3) DBSP cycle-rule semantics adopted (research
01 §4): delta-circulating cells inside a cycle *are* semi-naive evaluation by
construction; **termination = a full iteration produces only empty effective deltas**;
guarantee scope carried verbatim: stratified queries, finite domains, strong
(idempotent-merge) tier — the weak tier keeps only the bounded-lap contract (R2).
(4) Stratification: non-monotone operators (antijoin/difference; E6's `negate`) are
inadmissible on a cycle path (`CycleRequiresStratification`). (5) Egress modes: eager
pass-through default (head fan-out never gated — existing rule); opt-in **settled
egress** releasing downstream only at fixpoint, re-associated to `outerWave` — the
glitch-free composition story for cycles (I-23).
**Implement**: spec text only; cite research 01 §4, 02 §1-2, 05 gap 5.
**Unblocks** E5.2-E5.7.

### E5.2 — `LoopContext` on `MessageContext` (I-5) — P2 · High · `context`
**Spec**: 20/22 §MessageContext (E5.1).
**Implement**: `@Serializable @SerialName("LoopContext") data class LoopContext(outerWave:
Timestamp?, iteration: Long)`; additive `loop: LoopContext? = null` on `MessageContext`
(absent field decodes null — wire-additive, no version bump); `FeedbackInlet.provide`
stamps `loop = LoopContext(incoming.loop?.outerWave, (incoming.loop?.iteration ?: 0) + 1)`
alongside its existing fresh `Timestamp`; transparent flow copies `loop` like `hop`. No
`@Contract` change — core context type. Test: extend the cyclic-graph suite: every
in-loop delivery carries monotone `iteration` per `outerWave`; hop still resets; wire
round-trip of a loop-stamped invocation against an old-decoder fixture.
**Depends**: E5.1.

### E5.3 — Cycle-region ingress/egress at admission (G-41 partial) — P2 · Medium · `link`+`host`
**Spec**: 20/21 §Cycles (E5.1 point 1); 10/13 (admission wording).
**Implement**: extend `ManagedHost.wouldCloseCycle` from a boolean DFS to SCC capture on
successful `FeedbackInlet` admission; record the region in the topology index; classify
region-crossing edges: ingress edges get a stamping wrapper
(`loop = LoopContext(currentWave, 0)`), egress edges a stripping wrapper (and, under
settled egress, a hold-until-settled buffer keyed by `outerWave`, released by E5.4's
probe). Region membership updates on later connects/unlinks touching region cells.
Cross-host cycles remain invisible to admission — the hop guard stays the backstop
(→ R16). Certainty Medium: the wrapper seam (link-install proxy vs port decoration) is
implementer's choice. Test: seeded topology-churn run — every in-region delivery
loop-stamped, every egress delivery stripped, headless closure still rejected; existing
cycle suites green.
**Depends**: E5.2.

### E5.4 — Iteration-cut fixpoint detection at the head (G-19 mechanics; R2-gated guarantees) — P2 · Medium · `data`
**Spec**: 20/21 §Cycles (termination paragraph, E5.1 point 3); 50/53 (promotion-gate
wording).
**Implement**: with `LoopContext`, `FeedbackInlet` accounts per (outerWave, iteration): a
lap delivering **no effective delta** to any of the region's feedback inlets is the
fixpoint cut — DBSP's "differentiated change stream is empty", detected by the
effective-only rule the emitters already enforce. Generalize `lastQuiescent: Boolean?` to
`settled: LoopContext?` (non-null names the outer wave and final iteration). Strong tier:
exact, threshold-free. Weak tier: the existing `quiescence` damper feeds the same probe —
a *damped* cut, honest per the bounded-lap contract; no convergence claim (R2). The
probe drives the 53 promotion gate and E5.3's settled-egress release. Test: cyclic
graphs, strong-tier (`SetDelta`) and weak-tier (`Magnitude`) variants; strong tier
settles on every seed with `settled.iteration` ≤ the batch solver's count; a divergent
control (non-contractive weak loop) never reports settled and dead-letters at the hop
bound.
**Depends**: E5.2, E5.3.

### E5.5 — Incremental re-entry: fixpoint adjustment under new input (gap 5) — P2 · Medium · `data`
**Spec**: 20/21 §Cycles (re-entry paragraph), citing research 01 §4 (Thm 5.4 shape).
**Implement**: a new outer input reaching a settled region starts a fresh
`LoopContext(newOuterWave, 0)`; loop cells circulate deltas **against their integrated
state** (verify the association; fix anything that resets state per lap); the resulting
lap sequence is the stream of fixpoint *adjustments*, settling by E5.4's cut per outer
wave; concurrent outer waves interleave safely because iteration identity is
per-outerWave (the two-dimensional time). Test: grow/shrink a transitive-closure-style
relation through a cycle; after each settle, loop output == batch fixpoint over current
input (semi-naive equivalence); controls: a from-scratch-per-wave variant blows a
delta-count budget; a state-resetting variant diverges.
**Depends**: E5.4.

### E5.6 — Stratification admission check (G-41 partial; E6 forward-dep) — P3 · Medium · `link`+`gen`
**Spec**: 20/21 §Cycles (E5.1 point 4); 20/24 (monotonicity classification); 10/12
(descriptor-bit note).
**Implement**: a `NonMonotone` marker (runtime `is`-check like `Magnitude`, or a
`CellDescriptor` bit if 94 W1.3's scan family has landed — prefer the bit) on the
re-entry-minting cells (`SemiJoinCell` in negated/difference mode; later E6's `negate`);
admission rejects a region containing one on the cycle path with
`CycleRequiresStratification`. Test: connect-time rejection suite; control: the
unchecked graph on an adversarial seed oscillates membership until the hop guard fires.
**Depends**: E5.3; 94 W1.3 (bit form only).

### E5.7 — Agora grounded-extension fixpoint (gap-5 payoff; strong-tier adopter) — P2 · Medium · `demo` (`:demo:agora`)
**Spec**: none new (consumes E5.1-E5.5); 20/21 §Cycles cross-references the demo.
**Implement**: grounded labelling (IN/OUT/UNDEC) as a **set-valued least fixpoint** over
the agora argument graph: `SetDelta`-carrying cells (idempotent-merge → strong tier)
around a kernel `CycleHead`, replacing the app-level head approximation for this view
(the weak-tier credence loop stays as-is, R2-gated); exact, threshold-free termination
via E5.4. Test: the agora exit-test idiom — 100 seeds including cyclic graphs;
incremental labelling equals the batch grounded-extension reference **exactly** (no
threshold bound — the strong-tier point, and the observable improvement over today's
bounded cyclic assertion); a retraction-blind control diverges.
**Depends**: E5.4, E5.5.

**Dependency note (E5)**: E5.2 → E5.3 → {E5.4, E5.6}; E5.4 → E5.5 → E5.7. Independent of
E1-E3 (touches `context`/`link`/`data`, none of the replication or observation-edge
substrate). E2's aligned observation composes *with* settled egress (a settled cycle
output is just another per-source arm); neither blocks the other.

---

## Milestone E6 — Weighted (Z-set) cell family ⚠ PROPOSED

Gap 1, deliberately last (05 §priority): the tag↔weight boundary should be informed by
E1's OR-map and E3's GC work. DBSP's Z-sets give EXCEPT ALL and bag semantics, O(|Δ|)
`dist`, and — most valuably — the LTI/bilinear taxonomy that *predicts which cells carry
state* plus the delta-join form as the correctness template for every future binary
operator (research 01 §2-3). The structural tension is settled by the borrowing rule:
`WeightedSetDelta` merges by pointwise addition — commutative, associative, **not
idempotent** — so it is classified exactly like `CounterDelta`: single-instance, never
`Replicable`, double-counted by gossip redelivery (control-tested here). Two boundary
adapters make the family useful anyway: tags→weights (the effective-only rule already
computes DBSP's H sign-change function) and weights→tags (a `dist`-like boundary cell
minting tags). The replicable bag (per-source cumulative weights) is recorded as R17,
**not scheduled**.

### E6.1 — Spec: weighted family + LTI/bilinear operator classification (gap 1) — P3 · High · `spec`
**Spec**: 20/24 new §Weighted (Z-set) family; 20/24 §Required next steps (retire the
weighted-family deferral text); 20/21 (delta-join form as the normative binary-operator
template); 20/22 (weights never cross replicas — the classification sentence beside
`CounterCell`'s).
**Spec change**: (1) `WeightedSetDelta<E>` — element → signed count, merge by pointwise
addition dropping zeros; not idempotent → single-instance, non-`Replicable`,
gossip-inadmissible (raw-weights-on-the-wire is the research's consolidated reject, 05
table). (2) The two boundary adapters as the *only* sanctioned tag↔weight crossings, with
the H-function correspondence recorded ("the effective-only rule is DBSP's H").
(3) **Operator classification as spec-level vocabulary** (research 01 §3), retrofitted
over the existing family as a 20/24 table: *linear/LTI* (filter, flatMap, projection,
sum-like — stateless, `Q^Δ = Q`), *bilinear* (equi-join — delta-join form, O(|DB|) state
per input: `JoinSetCell`), *non-linear* (`dist`, min/max — support-multiset state:
exactly the M11 observation, now with its theorem citation). The table is normative for
future cells: state needs are predicted before code. (4) The delta-join form
`Δ(a⋈b) = Δa⋈Δb + a⋈Δb + Δa⋈b` written into 20/21 as the correctness template every new
binary operator must be derived from. (5) The replicable bag recorded research-gated →
R17.
**Implement**: spec text only; cite research 01 §2-3, 05 gap 1. **Unblocks** E6.2-E6.6.

### E6.2 — `WeightedSetDelta` + `WeightedSetCell` (gap 1) — P3 · High · `data`
**Spec**: 20/24 §Weighted family (E6.1).
**Implement**: `@Serializable @SerialName("WeightedSetDelta") data class
WeightedSetDelta<E>(weights: Map<E, Long>) : MergeablePayload` — merge = pointwise
addition dropping zeros; additive wire registration. New `@Contract interface BagOps<E>
{ fun add(element: E, count: Long); fun remove(element: E, count: Long) }` — flows
through the gen/ KSP descriptor scan like `CounterOps`; explicitly **not**
`idempotentMerge` (which keeps it off the mesh and out of the cycle strong tier).
`WeightedSetCell<E>` on the `CounterCell` template: single-instance, `Stateful`
(integrated `Map<E, Long>`), effective-only (net-zero emits nothing), late-join catch-up
= integrated weights as delta-from-zero. Test: seeded op-interleaving suite; integrated
state == batch sum per seed; **red control: duplicate delivery of the same delta
double-counts** — documenting why the type is non-`Replicable`.
**Depends**: E6.1.

### E6.3 — Boundary adapters: tags↔weights (gap 1; the H function) — P3 · High · `data`
**Spec**: 20/24 §Weighted family (adapter paragraphs).
**Implement**: (a) `TagsToWeightsCell<E>`: inlet `Propagate<SetDelta<E>>`, outlet
`Propagate<WeightedSetDelta<E>>`; folds a `TagState` — because `TagState.apply` already
returns only new/killed tags, it emits `+|newAdds[e]| − |newDels[e]|` per element: the H
sign-change function with zero extra bookkeeping. Modes: *multiplicity* (weight = |live
tags|, default) and *dist* (0/1 membership). (b) `WeightsToTagsCell<E>` (the `dist`
boundary back): integrates weights; a ≤0→>0 transition mints an entry tag (`MintedTags` —
re-entry after retraction needs a fresh tag, the M11.2 hygiene rule), >0→≤0 exits the
minted tag; output is an ordinary convergent `SetDelta` stream, fit for replication
downstream. Both `Stateful`, late-join capable, single-instance. Test: round-trip — tag
stream → weights → tags equals the `dist` of the original membership at idle on every
seed; the M11.2 tag-reuse control applied to (b).
**Depends**: E6.2.

### E6.4 — Linear weighted operators + EXCEPT ALL (gap 1) — P3 · High · `data`
**Spec**: 20/24 §Weighted family (linear row of the E6.1 table).
**Implement**: stateless-by-theorem cells: `negate` (pointwise), weighted sum/union
(n-ary merge), weighted filter/map (LTI ⇒ own incremental version, no state).
`exceptAll(a, b) = sum(a, negate(b))` with the positive-part clamp applied at the
*rendering* boundary only (a `positive()` mode on `WeightsToTagsCell` / a weighted view
in `host/Observe.kt`), so intermediate negative weights remain first-class retraction
credit, exactly as in DBSP. `negate` is marked `NonMonotone` (E5.6 interop — recursion
through negation stays inadmissible). Test: seeded EXCEPT ALL pipeline vs batch multiset
difference, including multiplicity > 1 seeds where EXCEPT DISTINCT (`differenceSet`)
provably differs — that divergence IS the feature under test.
**Depends**: E6.2.

### E6.5 — Bilinear weighted join: the delta-join template cell (gap 1; template for backlog 01/02 shapes) — P3 · Medium · `data`
**Spec**: 20/21 (delta-join template, E6.1 point 4); 20/24 §Weighted family.
**Implement**: `WeightedJoinCell<K, A, B, C>` — keyed bilinear join over two weighted
streams: per-side integrated indexes (`K → Map<row, weight>`, the indexed-Z-set form),
emission per input delta computed as `Δa⋈b + a⋈Δb + Δa⋈Δb` (output weight = product),
effective-only on net zero. Written deliberately as the **reference implementation of the
template**: the deliverable includes a doc block mapping each term to code so future
binary operators copy the shape rather than re-derive it. Certainty Medium: per-side
index representation is implementer's choice under the O(|DB|)-state budget. Test: seeded
weighted-join suite — equals batch join-with-multiplicities per seed; per-delta work
proportional to |Δ|·|matching keys| (budget assertion); control: a naive full-recompute
variant blows the budget.
**Depends**: E6.2.

### E6.6 — Cross-representation equivalence harness (gap-1 exit) — P3 · High · `data`
**Spec**: 50/52 (invariant row).
**Implement**: the milestone's exit harness: seeded runs driving the *same* logical
pipeline in two forms — tagged (`SetDelta` operators) and weighted (through E6.3 adapters
at the boundaries) — asserting idle-state equality on every seed; plus the two standing
controls (gossip-redelivery double-count from E6.2; EXCEPT ALL vs DISTINCT from E6.4).
Promote to 52 as the weighted family's convergence-class evidence.
**Depends**: E6.3, E6.4, E6.5.

**Dependency note (E6)**: E6.1 → E6.2 → {E6.3, E6.4} → {E6.5, E6.6}. Sequenced after E1
and E3 by design; E6.3's adapter classification is reviewed against E1's embedding rules
before landing; any weighted-state eviction reuses E4.5's seam. E6.4's `negate` feeds
E5.6. Nothing in E6 touches replication paths — the family is per-replica by
classification.

---

## New research entries (appended to 95)

### R10 — Context-only removal (tombstone-free OR structures) — from E1
**Question**: can `SetDelta`/`TaggedMapDelta` dels ship as causal context only
(delta-AWSet, research 03 §2), eliminating tombstone payloads, given the causal-merging
condition (`Xᵢ ⊒ Xⱼᵃ` before join) must then hold on every replica link?
**Blocks**: the wire-size half of compaction; nothing in E1-E3 (the tombstoned form is
correct, unbounded until E3.7).
**Directions**: (1) per-peer delta-intervals over the E3 delivered-watermark rows +
`StateRequest(since)` full-state fallback — the mapping named in research 03 §2;
(2) tombstones stay on the wire, reclaimed only at rest via causal stability (E3.7) —
smaller step, preferred first.

### R11 — Frontier alignment across origination points — from E2
**Question**: the aligned sink's shared wave vocabulary assumes sibling views see the
same sourceIds; a re-origination point on one branch only (a `Replicable` cell's
post-merge re-emission per C-10, a future `CycleHead`) breaks it — what provenance must
cross an origination point for cross-view alignment to survive?
**Blocks**: `observeAligned` over pipelines whose branches diverge before a replicated
cell; the cross-replica case is E3.4's territory.
**Directions**: (1) require the origination point to be a common ancestor of all aligned
views (structural check at build, loud rejection) — smallest honest step; (2) carry a
`TagFrontier` provenance summary through origination; (3) fold with E3's
delivered-watermark rows.

### R12 — Observation-edge triggering policy — from E2
**Question**: the Dataflow Model's where/when/how decoupling (research 04 §2) suggests a
trigger knob — emit per settled wave, on demand, or on cadence with accumulating panes;
which modes does the observation edge need, and is retraction-on-refire (native via tags)
sufficient for all of them?
**Blocks**: nothing scheduled; UX of high-rate dashboards.
**Directions**: (1) per-wave only until a demo demands otherwise (YAGNI); (2) cadence
panes as a sink-local concern, never a kernel one.

### R13 — Membership completeness & departed-replica liveness for the stability read — from E3
**Question**: the stability read is only safe if the membership view is *complete* (an
unknown replica could hold concurrent ops) and only *live* if departed replicas' rows are
closed or evictable — is the `replicasOf` announcement fold sufficient under join/churn,
and is lease-fenced row eviction sound given it can race a partitioned-but-alive replica?
**Blocks**: E3.7/E4 compaction liveness under churn; unclean-failover interplay with R1.
**Directions**: (1) creation-fenced membership: a new replica's row must exist (bottom)
before its first delta is admitted to any peer's fold; (2) lease-fenced eviction reusing
the R1 epoch vocabulary (one shared membership/heartbeat substrate); (3) never evict —
stability freezes until manual `Replication.evict` — the shipped default; measure how
often it hurts.

### R14 — Watermark columns across emission epochs — from E3
**Question**: a `ReBaseline` supersedes a sourceId; delivered-watermark columns are keyed
by sourceId — when may a superseded source's column (and the `deadSources` fence set it
mirrors) be dropped from rows and reads without a window where a straggler delta from the
dead epoch is re-admitted as new?
**Blocks**: the epoch-hygiene corner of G-42 (already research-flagged in 94 W2.1);
unbounded-but-correct is the shipped behavior.
**Directions**: (1) a dead source's column is reclaimable once the `ReBaseline` itself is
causally stable (the substrate eating its own dog food); (2) fold with R10's
delta-interval bookkeeping.

### R15 — Waterline liveness under idle sources — from E4 (cross-links R13)
**Question**: the min-over-sources waterline freezes when a linked, open source stops
emitting — eviction stalls for everyone. What retires or ages an idle source's
contribution without wall clocks; are `EdgeClose` retirement + `ReBaseline` supersession
(E4.4) plus a manual `retire` op sufficient in practice?
**Blocks**: nothing scheduled (E4 ships frozen-but-correct); the same idle-participant
hole gates E3's stability read — one shared answer strongly preferred.
**Directions**: (1) reuse E3's heartbeat/ack substrate so "idle" is distinguishable from
"silent" — one liveness mechanism for both consumers; (2) per-inlet (not per-source)
lateness, collapsing the vector to the declaring edge; (3) lease-based contribution
expiry from the registry's membership view (shares R13's question).

### R16 — Cross-host and nested cycle structure — from E5
**Question**: link-time admission cannot see a cycle closed across hosts, and
single-level `LoopContext` forbids nested loops — what distributed cycle-visibility
mechanism (if any) is worth its coordination cost, and is the single-level restriction
acceptable long-term?
**Blocks**: nothing scheduled (the hop guard is the decided backstop; E5.3 is explicitly
in-process). Gates future multi-host recursive apps.
**Directions**: (1) registry-fed topology fold: the reverse-topology index gossiped
enough to run `wouldCloseCycle` over the announced global edge set —
eventually-consistent admission with the hop guard unchanged as the safety net;
(2) Naiad's answer for nesting — one counter per enclosing context, lexicographic
product order (research 02 §1) — as a `LoopContext` list, wire-additive; (3) keep the
restriction and lint: cross-host feedback edges declared explicitly, nesting rejected
forever.

### R17 — Replicable weighted bag: per-source cumulative weights — from E6 (extends R8)
**Question**: a per-source cumulative-weights map (`sourceId → element → cumulative
pos/neg`, merged pointwise max — the PN-counter generalized per element) would be a
gossipable bag. Is the vector-width-per-element cost acceptable, and can per-replica
weighted circuits consume/emit OR-set tag deltas through the E6.3 boundary without
double-counting under gossip redelivery?
**Blocks**: nothing scheduled (E6 ships weights replica-local by classification);
replicated bag semantics / EXCEPT ALL across replicas.
**Directions**: (1) PN-column per element (R8 direction 2 sharpened), with codec-layer
per-source metadata dedup as the cost mitigation; (2) replicate only tag streams at the
boundary, keep every weighted interior per-replica — E6's default, promoted to permanent
if (1) is unaffordable; (3) hybrid: cumulative columns only for elements crossing a
declared replication boundary, lazily materialized.

---

## Dependency summary

```
E1.1 → E1.2 → E1.3 → {E1.4, E1.5} → E1.6
E2.1 → E2.2 → E2.3 → {E2.4, E2.6} → E2.5
E3.1 → E3.2 → {E3.3, E3.6}         E2.3 → E3.4
              E3.3 → E3.5 → {E3.6, E3.7}
              E1.3 → E3.3 (OrMap seam; optional, non-blocking)
E4.1 → E4.2 → E4.3 → {E4.4, E4.5} → E4.6      E3.7 gates Replicable eviction only
E5.1 → E5.2 → E5.3 → {E5.4, E5.6}; E5.4 → E5.5 → E5.7
E6.1 → E6.2 → {E6.3, E6.4} → {E6.5, E6.6}     after E1 + E3 by design
```

## Priorities at a glance

- **P1**: E1.1-E1.3, E2.1-E2.5, E3.1-E3.2, E3.5.
- **P2**: E1.4-E1.6, E2.6, E3.3-E3.4, E3.6-E3.7, E4.1-E4.4, E5.1-E5.5, E5.7.
- **P3**: E4.5-E4.6, E5.6, E6.1-E6.6.
