# Per-node composition — tickets

> Baseline: `main` @ `809f25c`. Design: [COMPOSITION-PERNODE-PLAN.md](COMPOSITION-PERNODE-PLAN.md)
> (cited per ticket as "plan §…"). Sequencing/waves: [PERNODE-IMPLEMENTATION-PLAN.md](PERNODE-IMPLEMENTATION-PLAN.md).
> House style: each ticket = make the cited design/spec text true of the code;
> test named up front; 100-seed generative where applicable; **controls that must
> diverge**; no behavior change for non-opting graphs (single declared exception: PN-12).
>
> Orchestration metadata per ticket: **Context** (`FRESH` / `CONTINUE <ticket>`),
> **After** (merge prerequisites), **Parallel** (safe concurrently — disjoint in
> function *and* files), **Files**.

## At a glance

```
W0  PN-0a ∥ PN-0b ∥ PN-0c ∥ CP-G1 ∥ CP-G2          (all FRESH, one-shot)
W1  α:PN-1 ─────────────∥ β:PN-3a/c
W2  α:PN-2 (CONT) ──────∥ γ:PN-4 (FRESH) ∥ β:PN-3b (CONT, after G1)
W3  γ:PN-5 (CONT) ──────∥ ε:PN-9 (FRESH)
W4  γ:PN-6+G4 (CONT) ───∥ ε:PN-10 (CONT, after G2)
W5  δ:PN-7 (FRESH) ─────∥ ζ:PN-12 (FRESH)
W6  δ:PN-8 (CONT) ∥ ζ:PN-18→PN-13 (CONT) ∥ η:PN-14 (FRESH) ∥ PN-11 (FRESH) ∥ λ:PN-16 (FRESH)
W7  η:PN-17 (CONT) ─────∥ ι:PN-19 (FRESH)
W8  κ:PN-15 (FRESH, last)
```

Retired: CP-G3 (subsumed by PN-6 — do not run as written), CP-G5 (→ PN-15),
CP-G7 (→ PN-16). CP-G6 stays trigger-gated; new trigger = two independent
graphs installing the same ADMIT+GATE+ALIGN policy triple (watch PN-9 installs).

---

## Wave 0 — defect surfacing (all parallel, all FRESH, all small)

### PN-0a — Dead-letter the frontier's silent drop — P1 · Low · `consistency`
- **Context**: FRESH · **After**: — · **Parallel**: PN-0b, PN-0c, CP-G1, CP-G2
- **Files**: `kernel/.../cell/consistency/WaveFrontier.kt` (`offer`, ~:178), test.

**Problem** (plan §2 F1). `offer`'s unmatched-edge path (`edges.values.singleOrNull
{...} ?: return`) discards invocations with no diagnostic — replayed journal
frames, `streamTo`/tap producers, duplicate edges all vanish silently.

**Implement.** Route the unmatched case to the host's dead-letter path (or a
counted diagnostic if dead-letter is unreachable from the policy), preserving
today's *observable* behavior (still not delivered downstream). Not the fix —
the tripwire; PN-1/PN-2 remove the cause.

**Test.** `FrontierUnmatchedDropTest` — a producer linked without `EdgeOpen`
into a frontier inlet yields a dead-letter/diagnostic per emission, zero
deliveries. **Control**: matched edge → zero diagnostics, normal delivery.

### PN-0b — Checkpoint refuses a journal with no `Stateful` contributor — P1 · Low · `host`
- **Context**: FRESH · **After**: — · **Parallel**: PN-0a, PN-0c, CP-G1, CP-G2
- **Files**: `kernel/.../cell/host/ManagedHost.kt` (`checkpoint`, ~:506-520), test.

**Problem** (plan §2 F4). `checkpoint` snapshots only `Stateful` cells then
unconditionally `journal.reset(...)` — a journal whose selected cells are all
non-`Stateful` gets its WAL truncated and the data destroyed.

**Implement.** `require` (or typed refusal) that the snapshot set is non-empty
when the journal has selected cells; `MixedDurabilityTest` byte-identity stays
green.

**Test.** Extend `MixedDurabilityTest` — checkpoint over a journal serving only
a non-`Stateful` cell → refusal, WAL intact, replay still works. **Control**:
the `require` removed → shard/cell returns empty after recovery (the data loss,
demonstrated).

### PN-0c — Wire `WatermarkCell.close()` into evict/unpublish — P1 · Low · `repl`
- **Context**: FRESH · **After**: — · **Parallel**: PN-0a, PN-0b, CP-G1, CP-G2
- **Files**: `kernel/.../cell/replication/Replication.kt` (`evict`, unpublish hook), test.

**Problem** (plan §2 F3). `close()` exists (`Watermark.kt:118`) and is called
from nowhere in main; a departed member's watermark row constrains
`replicaFrontier` forever — every departure wedges downstream replica-fed
frontiers.

**Implement.** `Replication.evict` and the despawn/unpublish path close the
member's slot.

**Test.** `MemberDepartureFrontierTest` — 3-replica mesh, glitch-free consumer
via `useReplicaFrontier`; evict one member mid-run; waves keep settling.
**Control**: close-call removed → consumer stops producing on every seed.

### CP-G1 — Mergeable aggregates (existing ticket, amended) — P1 · High · `data`
As written in [COMPOSITION-TICKETS-NEXT.md](COMPOSITION-TICKETS-NEXT.md) §CP-G1, with two amendments
(plan §4 resequencing): **Files add** `demo/exchange/.../Main.kt` (where
`MapMergeCell`, the class it deletes, actually lives); **coordinate with
PN-3b** — `MapDelta`'s new merge path is the same file PN-3b makes `Scoped`;
CP-G1 merges first, β continues on top.
- **Context**: FRESH · **Parallel**: PN-0a/0b/0c, CP-G2, and W1 (not PN-3b).

### CP-G2 — Nature vectors cross the wire (existing ticket, unchanged) — P1 · Medium · `wire`+`link`
As written in [COMPOSITION-TICKETS-NEXT.md](COMPOSITION-TICKETS-NEXT.md) §CP-G2. Gates PN-10 and PN-12.
- **Context**: FRESH · **Parallel**: everything in W0/W1 (owns `Link.kt` +
  `WireCodec.kt` + `ContractDescriptor.kt` until merged).

---

## Lane α — identity & recovery (one agent, two tickets)

### PN-1 — Replay-stable port identity — P1 · Medium · `port`
- **Context**: FRESH (agent α) · **After**: W0 merges touching `WaveFrontier` (PN-0a)
- **Parallel**: PN-3a/c, CP-G1/G2 tails
- **Files**: `kernel/.../cell/port/PortRef.kt`, `PortIdentity.kt`, port
  construction sites in `FanInlet.kt`/`FanOutlet.kt`, spec `12-ports.md`.

**Problem** (plan §2 F1 root, §4 PN-1). `PortRef.generate()` is a fresh random
UUID; `MessageContext.sourcePort` is therefore ephemeral, while the durable
plane keys on `(cellRef, portName)`. Wave identity does not survive a restart —
the root cause of the frontier's silent drop of replayed frames.

**Implement.** `PortRef.of(cellRef, name)` = `nameUUIDFromBytes("port:$name:${ref.id}:${ref.instanceId}")`
— the exact derivation pattern of `SetCell.tagSource`, `MintedTags`,
`watermarkRef`. Ports owned by a hosted cell get derived refs at stamp time;
anonymous/test ports keep `generate()`. No caller API change.

**Test.** `StablePortIdentityTest` — rebuild the same graph twice (fresh JVM
objects, same refs/names): every hosted port's ref is equal across builds;
`WaveFrontier` edge-matching succeeds against a context minted pre-rebuild.
**Control**: derivation reverted to `generate()` → cross-build match fails
(today), proving the derivation is load-bearing.

### PN-2 — Journal replay is a baseline — P1 · High · `host`+`consistency`
- **Context**: CONTINUE PN-1 (same agent — reuses the identity/call-site map)
- **After**: PN-1 · **Parallel**: PN-4, PN-3b (declared `ManagedHost` function
  disjointness with PN-4: `recoverFrom` vs `checkpoint`)
- **Files**: `kernel/.../cell/host/ManagedHost.kt` (`recoverFrom`),
  `port/MessageContext.kt` (`ReplayScope`, `Baseline` shape), `port/FanOutlet.kt`
  (stamping), `port/CatchUp.kt` (`baselineTo` instead of bare propagate),
  `consistency/WaveFrontier.kt` (baseline path only), spec `22-consistency.md`
  §Recovery, `24-data-cells.md`.

**Problem** (plan §3 Rule of recovery, §4 PN-2). Replay re-enters the intake as
ordinary live waves — neither `baseline` nor `reBaseline` — so it either drops
(pre-PN-1) or stalls asymmetric diamonds (post-PN-1: transient floors make every
sibling arm expected while a volatile arm never advances). The exchange demo
survives only via the unwritten "journal only context-free roots" invariant.

**Implement.** `ReplayScope` thread-local (analogue of `PendingReBaseline`):
during `recoverFrom`, every emission in the replayed cone is stamped
`baseline = <recovering cell's tag frontier>`, taking the frontier's existing
exclusion path. Unify the state-transfer sites on one
`Baseline(frontier, scope: Interest?, epoch: Long?)`; switch `catchUpOnLinked`
to `baselineTo` so push and pull catch-up are marked identically. `Effectful`
processed-frontier consultation is unchanged (replay still dedups against it).

**Test.** `DurableGlitchFreeReplayTest` (kernel, **not** demo — κ owns the demo)
— WAIT diamond join; one arm fed by a journaled *mid-graph* cell (non-null
context frames — the untested case), other arm volatile; kill, rebuild,
`recoverFrom`, resume live traffic. 100 seeds: post-recovery released sequence
equals batch recompute; assert `released + suppressed == journal.replay().size`
(silent drops fail loudly). **Controls**: (a) `ReplayScope` off → stall
(step-budget) or wholesale drop on every seed; (b) PN-1's derivation reverted
while `ReplayScope` stays on → still green — proving the two halves are
independently load-bearing. **Closes matrix cell A–D.**

---

## Lane β — interest algebra (one agent, two tickets)

### PN-3a/c — Interest closes; `StateRequest` gains scope + vector `since` — P1 · Medium · `repl`+`port`
- **Context**: FRESH (agent β) · **After**: — · **Parallel**: PN-1, CP-G1/G2
- **Files**: `kernel/.../cell/replication/Interest.kt`,
  `port/StateRequestProtocol.kt`, `data/SetCell.kt` (handler + catch-up),
  spec `42-replication.md` §Interest-scoped instance sets.
  **Not** `PartitionedCell.kt` (γ replaces the anonymous combinators when it
  adopts the algebra) and **not** `MapCell.kt` (PN-3b, after CP-G1).

**Problem** (plan §2 F6, §4 PN-3). The interest algebra is open: anonymous
non-serializable combinators with `overlaps = true` lies; `StateRequest`
returns whole state regardless of the requester's interest; a merged
pointwise-max `since` across instances silently loses tags (shard holdings are
non-contiguous).

**Implement.** (a) `Empty`/`Union`/`Intersect`/`Complement`/`Ranges` as data
classes with honest `overlaps`; `Total`/`Slots` bit-identical. (b) `StateRequest`
gains `scope: Interest?` (absent ⇒ Total ⇒ today's reply verbatim); `SetCell`'s
handler filters by it. (c) Consumer-side retained `since` becomes per-instance
(`Map<instanceRef, TagFrontier>`), never merged.

**Test.** `InterestAlgebraTest` (overlaps symmetry, algebraic = predicate
evaluation, serialization round-trip of every arm) +
`InterestScopedCatchUpTest` (partial-interest requester receives exactly its
slice; Total requester byte-identical to today). **Controls**: (a) anonymous
`predicateInterest` fails the round-trip (why the algebra must close — CP-G4's
blocker demonstrated); (b) scope omitted → over-delivery observable (feeds
PN-12's `INSTANCE_SCOPING` axis); (c) merged scalar `since` → lost tags on some
seed.

### PN-3b — `MapDelta` is `Scoped` — P1 · Low · `data`
- **Context**: CONTINUE PN-3a/c (agent β) · **After**: CP-G1 merged
- **Parallel**: PN-2, PN-4
- **Files**: `kernel/.../cell/data/MapCell.kt`, `MergeableGroupByCell.kt` (G1's
  new cell), spec `24-data-cells.md`.

**Problem** (plan §2 F7). `Replication.scopeToInterest` rides a non-`Scoped`
delta *whole* to a partial-interest target; `MapDelta` (and CP-G1's merge
delta) is not `Scoped` — so no aggregate can be interest-sliced: a `Replicable`
that cannot be sharded.

**Implement.** `MapDelta : Scoped` (keyOf = map key), including the merge path
CP-G1 added. `SetDelta` semantics untouched.

**Test.** Extend `InterestScopedCatchUpTest` + `MergeableGroupByTest` — an
aggregate delta to a partial-interest peer carries only admitted keys.
**Control**: `Scoped` impl removed → whole-map over-delivery (today).

---

## Lane γ — partition substrate (one agent, three tickets)

### PN-4 — `ShardCell` grows up — P1 · High · `data`
- **Context**: FRESH (agent γ) · **After**: PN-3a/c, PN-0b
- **Parallel**: PN-2, PN-3b
- **Files**: `kernel/.../cell/data/PartitionedCell.kt` (ShardCell, router
  companion combinators → β's algebra, `rebuildFrom`), spec `24-data-cells.md`
  §Partitioned state.

**Problem** (plan §1 layer 2, §4 PN-4). `ShardCell` is a write-only sink: no
outlet, not `Stateful`, not `Replicable`; output escapes via the direct object
call `membership()`. Partitioned+durable/replicated/pull are unbuildable; a
journaled shard's shed is invisible to recovery and its interest resurrects
from the constructor arg.

**Implement.** `ShardCell : Cell, Stateful, Replicable` — outlet + `deltaInlet`
+ `Protocols.StateRequest` handler (the `SetCell` six-liner) + `snapshot() =
(TagState, interest, assignedEpoch)`. Replace the anonymous interest
combinators with β's algebra. `PartitionedShardSet.rebuildFrom(shards)`
recomputes epoch/table from restored shards. Single-host `PartitionedCell`
byte-identical (degenerate placement).

**Test.** `ShardJournalReplayTest` — 3 shards × 3 hosts × per-shard WALs;
traffic → repartition → traffic → checkpoint one shard → kill all → recover →
`rebuildFrom`. 100 seeds: board equals batch group-by; memberships pairwise
disjoint (double-count detector). **Controls**: (a) rebuild from
`initialInterest` (today) → shed range resurrects, double-count; (b) PN-0b's
guard removed → recovered shard empty. **Closes durable+partitioned.**
`PartitionedCellTest`/`PartitionedShardsAcrossHostsTest` green unchanged.

### PN-5 — Scatter-gather pull — P1 · Medium · `data`+`repl`
- **Context**: CONTINUE PN-4 (agent γ) · **After**: PN-4, PN-2 (baseline shape)
- **Parallel**: PN-9
- **Files**: `PartitionedCell.kt` (router fan-out), `host/LocationRegistry.kt`
  (hold-path reuse only), spec `42-replication.md`.

**Problem** (plan §4 PN-5). A pull against a partitioned logical id has no
answerer; the router answering from its own ledger would be O(total state) at
one node — the thing partitioning exists to avoid.

**Implement.** Router fans `StateRequest(scope, since-per-instance)` to every
interest-overlapping shard; each replies `baselineTo` with its own frontier
(PN-2's `Baseline`, `scope` = the overlap). Freshness contract: per-shard-
consistent, cross-shard-arbitrary — a baseline is never a wave (spec text to
add). Consumer retains per-shard `since` (β's vector form).

**Test.** `PartitionedPullTest` — late joiner pulls a 3-shard cell (some behind
bridges, one `hold`-ed mid-migration); assembled state equals union; a second
*incremental* pull returns only unseen tags. 100 seeds. **Controls**: (a)
merged scalar `since` → silent tag loss; (b) router answers from `ledger` →
functionally green but fails the O(shard-count) retained-state assertion.
**Closes pull+partitioned; makes retiring the ledger (PN-6) safe.**

### PN-6 — One linker, one assignment (subsumes CP-G3; includes CP-G4's exit) — P1 · High · `repl`+`data`
- **Context**: CONTINUE PN-5 (agent γ — the payoff of the lane)
- **After**: PN-5 · **Parallel**: PN-10
- **Files**: `PartitionedCell.kt`, `replication/Replication.kt` (linker),
  NEW `replication/InstanceSet.kt`, `host/ManagedHost.kt` (assignment as hosted
  invocation), `host/LocationRegistry.kt`, `wire/WireCodec.kt` (deprecate
  `RoutedCommand.epoch` sniffing — field ignored, decoded for one release),
  spec `42-replication.md` §Interest-scoped instance sets.

**Problem** (plan §3, §4 PN-6). The gossip linker and the shard router are the
same slice-and-route logic written twice; the epoch rides in payloads but is
decorative at the point of use (admission checks current interest); assignment
is a plain method call — unjournaled, unaddressable (CP-G4's blocker); the
router's `routed`/`ledger` hold O(total state).

**Implement.** `InstanceSet`: per-instance `(interest, epoch)` max-register,
applied as a hosted management invocation (journaled, replayed, ref-addressed)
and gossiped as a `Replicable` lattice on the existing mesh. Admission rule
everywhere: older epoch → filter through current interest; newer → adopt, then
apply. One linker (`maybeLink` generalized with `keyOf`); router re-expressed
on it; delete `routed`, scope-or-delete `ledger`; router state O(instances).
Coverage invariant: shrink only after the gainer's watermark covers the shed
range. Flip window keeps a single routing authority (leaderless = R1, out of
scope — spec paragraph says so).

**Test.** `InstanceSetSubstrateTest` — one logical id × three instances ×
assignment ∈ {all-Total, disjoint, overlapping}: batch-oracle convergence;
links formed == overlap count; journaled instance crash+replay preserves a
shed. Plus **CP-G4's exit folded in**: `BridgedRepartitionTest` — router on
host A, shards on B/C over real bridges, repartition racing migration, zero
loss, 100 seeds. **Controls**: (a) shed as direct call → replay resurrects;
(b) overlap + non-commutative merge → order divergence (the merge is
load-bearing); (c) control-plane frames dropped while data flows → flip
half-applies, board forks (G4's control). Gate: the four partition/gossip pin
tests + `ExchangeCompositionExitTest` green unchanged.

---

## Lane δ — settlement (one agent, two tickets)

### PN-7 — Interest-scoped settlement — P1 · High · `consistency`+`repl`
- **Context**: FRESH (agent δ — consistency context; reads PN-2, PN-6 outcomes)
- **After**: PN-2, PN-6 · **Parallel**: PN-12
- **Files**: `consistency/WaveFrontier.kt` (`ReplicaFrontier`, `ReplicaGate`,
  `ready`), `consistency/GlitchFree.kt` (opt-in methods),
  `replication/Replication.kt` (~:97-107), `host/LocationRegistry.kt`
  (index `instancesOf` by logicalId — perf cliff), spec `22-consistency.md` +
  `96-incremental-engines-plan.md` E3 amendment.

**Problem** (plan §2 F2 — the latent conflict). Completeness quantifies over
*all* members; a disjoint-interest instance never delivers waves outside its
slice, so its row stays bottom and a WAIT consumer stalls forever the moment
shards join the mesh (i.e. the moment PN-6 lands).

**Implement.** `completeAt(source, counter, key)`: quorum = live, open members
whose interest admits the key. Defaults (empty `originKeys`) ⇒ unfiltered ⇒
today verbatim. **R13 lands inside this ticket** (creation-fenced membership: a
new instance's watermark row must exist before its first delta is admitted to
any peer's fold) — filtering *shrinks* quorums, so an unannounced member means
premature release, not conservative hold. DEGRADE's missing quorum-shrink is
documented as sequenced to PN-19 (not hidden).

**Test.** `ShardedReplicaFrontierTest` — board fed by an instance set both
sharded (2 slots) and replicated (2 copies/slot): never surfaces an uncovered
value; liveness via forced watermark + `recheck()`. 120 seeds
(`MixedArmGlitchFreeTest`'s count — this is its generalization). **Controls**:
(a) today's `members.all` → **the wave never releases** (the F2 conflict,
executable); (b) trivial frontier → board tears; (c) creation fence off → a
joining instance's first deltas release a wave early on some seed.

### PN-8 — Sharded replication end-to-end — P1 · Medium · `repl`
- **Context**: CONTINUE PN-7 (agent δ) · **After**: PN-7, CP-G1, PN-3b
- **Parallel**: PN-18/PN-13, PN-14, PN-11, PN-16
- **Files**: kernel test only + `port/NatureNegotiation.kt` (one refusal rule:
  overlap without declared merge).

**Problem** (plan §4 PN-8). "Overlapping interest = sharded replication" has
zero call sites and zero tests; overlap without a merge silently double-counts
— partition-with-overlap IS replication, and replication requires a merge.

**Implement.** Refusal: assigning overlapping interest to a non-mergeable
structure is `Rejected(mismatch)` on `MERGE_IDEMPOTENCE`. Then the end-to-end
proof over G1's mergeable aggregate.

**Test.** `ShardedReplicationTest` — 3 shards × 2 replicas, overlapping range,
repartition racing a replica failover, real bridges; board equals batch
group-by; no key counted twice. 100 seeds. **Controls**: (a) epoch-blind
adoption → moved range forks; (b) non-mergeable + overlap → refused at
formation, not silently wrong; (c) PN-0c reverted → failover wedges the board.
**Closes partitioned+replicated (convergent half).**

---

## Lane ε — port stratum (one agent, two tickets)

### PN-9 — Policy tiers on inlets; policy lists on outlets — P2 · High · `port`
- **Context**: FRESH (agent ε) · **After**: PN-2 (α owns `FanOutlet`/`CatchUp`
  until then) · **Parallel**: PN-5
- **Files**: `port/FanInlet.kt`, NEW `port/InletPolicy.kt`, `port/FanOutlet.kt`,
  `port/CatchUp.kt`, `consistency/WaveFrontier.kt` (declare `tier = ALIGN` —
  one line), `host/ManagedHost.kt` (`hasFrontierPolicy` → `hasPolicy(ALIGN)`),
  spec `12-ports.md` §Policies (new).

**Problem** (plan §4 PN-9). One nature migrated to a policy slot
(`frontierPolicy`); the rest are single slots that stomp (`catchUpOnLinked`
*assigns* `onLinked`; `Replication` works around it by re-firing the hook), or
are welded together (pull-request lives inside `WaveFrontier`; pull-serve is
hand-rolled once in `SetCell`). No stacking discipline exists.

**Implement.** Inlet: ordered chain with fixed tiers **ADMIT** (may drop,
never hold; must declare `mintsProgressAck` — the CP-A3 law) → **GATE** (hold
FIFO) → **ALIGN** (reorder; at most one — install-time `require`) →
**ACTIVATE** (cold-park). Install order irrelevant; tier order authoritative.
`frontierPolicy` stays as deprecated sugar. Outlet: FILTER tier (interest
slicing ∪ disclosure projection unified, disclosure pinned last) and ON-LINK
multicast via the existing `onLinkedListeners`. Extract pull-request
(`PullOnOpen`) out of `WaveFrontier` and pull-serve (`PullServe`) out of
`SetCell` as installable policies; `GlitchFreeCell` sugar installs
`WaveFrontier` + `PullOnOpen` together (emitted `StateRequest` sequence
identical). `FanInlet.at()` routes through the chain (or is documented
policy-exempt — decide, don't leave latent).

**Test.** `InletPolicyStackTest` — ADMIT+GATE+ALIGN stack ≡ the same three
wrapper cells in series, 100 seeds; install-order permutations identical;
double-ALIGN throws. `PullPolicyCompositionTest` — catch-up + pull-serve +
replication re-announce all retained (today: last wins); `PullOnOpen` without
ALIGN issues `StateRequest` on `EdgeOpen` (today impossible). **Controls**:
(a) reverted single-slot → catch-up lost when replication installs its hook
(the documented stomp); (b) a dropping ADMIT with `mintsProgressAck = false`
above an ALIGN → downstream stall, proving the law.

### PN-10 — `Link.role`; handshake bypasses become negotiable (opt-in) — P2 · Medium · `port`+`link`
- **Context**: CONTINUE PN-9 (agent ε) · **After**: PN-9, CP-G2
- **Parallel**: PN-6
- **Files**: `port/Link.kt` (`val role: LinkRole get() = Consume`),
  `port/StreamTo.kt`, `port/FanOutlet.kt` (`tap`),
  `consistency/WaveFrontier.kt` (`expectedLocalEdges` filters to `Consume`).

**Problem** (plan §2 F5). `streamTo` and `tap` skip `handshake()` — no
policies, no allowlist, no `reconcile`, no `EdgeOpen`; `LinkRole.Observe` is
passed nowhere. The exchange mesh is `streamTo`-built, so nature negotiation
has never run on it. Once taps announce, they must not gate waves — `Link` has
no role field to filter on.

**Implement.** Role on `Link` (default `Consume` — zero caller churn);
`expectedLocalEdges` counts `Consume` only; `tap(port, negotiated = false)` /
`streamTo(target, at, negotiated = false)` route through
`handshake(role = Observe)` when `true`. Default stays `false` — byte-for-byte
today; PN-12 flips it.

**Test.** `NegotiatedAttachmentTest` — a negotiated tap is refused by an
allowlist policy and by a nature mismatch; appears in `linking.links` as
Observe; absent from `expectedLocalEdges`. **Controls**: (a) `negotiated =
false` admitted despite deny-all (today's bypass, demonstrated); (b) Observe
edges not filtered → the join never releases.

---

## Lane ζ — vocabulary & declaration (one agent, three tickets)

### PN-12 — Two refusing axes, the `CellManifest`, and the default flip — P2 · High · `gen`+`link` **(the one behavior change)**
- **Context**: FRESH (agent ζ — type-system context; reads CP-F1..F3, CP-G2)
- **After**: CP-G2, PN-10 · **Parallel**: PN-7
- **Files**: `gen/.../wire/ContractDescriptor.kt`, `gen/.../wire/ContractProcessor.kt`,
  `port/NatureNegotiation.kt` (`LINK_FLOW_AXES`), `host/ManagedHost.kt` (spawn
  validation), `port/FanOutlet.kt` + `port/StreamTo.kt` (flip `negotiated`
  default to `true`), spec `20-dataflow-semantics` §Negotiation.

**Problem** (plan §4 PN-12). Structural natures are undetectable; but making
them *refuse* at links would repeat the COLOR mistake (a volatile consumer of a
durable producer is normal — the demo is exactly that). Exactly two mismatches
fail silently today and clear the refusal bar: unwaved-producer→ALIGN-inlet
(drop) and non-`Scoped`-delta→partial-interest (over-delivery).

**Implement.** (a) Refusing axes: `WAVE_PARTICIPATION` (UNWAVED < WAVED) and
`INSTANCE_SCOPING` (SINGLETON < INTEREST_SCOPED), KSP-derived from existing
markers/`Scoped` — no new annotations. (b) `CellManifest` — sparse
{GLITCH_FREE, DURABLE, REPLICATED, PARTITIONED, PULL_SERVING, GATED} on
`CellDescriptor`; consumed by spawn checks (a `DURABLE` cell on a host whose
selector yields null = refusal — today silent data loss), diagnostics, wire
(free on G2's unknown-axis-ignore rule); never by `reconcile`. (c) Flip
taps/`streamTo` to `negotiated = true` — the declared behavior change, gated on
the demo suite.

**Test.** `ComposedNatureManifestTest` (exchange cells report their composed
natures: board {GLITCH_FREE}, writers {DURABLE}, union {REPLICATED}, shards
{PARTITIONED}) + `ManifestDriftTest` (declared == installed across kernel +
demos). **Controls**: (a) manifest axes moved into `LINK_FLOW_AXES` → the
demo's durable→volatile link is refused — empirically proving structural
natures must not refuse; (b) `WAVE_PARTICIPATION` removed → `streamTo` into a
frontier inlet drops silently (today's F1). Gate: `ExchangeCompositionExitTest`
100 seeds + `ExchangeScaffoldTest` two-JVM kill -9, both green post-flip.

### PN-18 — Ownership × instance set: refusal by existing vocabulary — P2 · Low · `link`+`repl`
- **Context**: CONTINUE PN-12 (agent ζ) · **After**: PN-12, PN-6
- **Parallel**: PN-8, PN-14, PN-11, PN-16
- **Files**: `port/NatureNegotiation.kt`, `replication/InstanceSet.kt`, spec
  `23-ownership.md` §SPSC corollary.

**Problem** (plan §3b). An `Owned`/`Leased`-carrying port under Total or
overlapping interest delivers one exclusive to N instances — violating
consumed-exactly-once. Disjoint routing is a legal move-by-serialize.

**Implement.** Instance-set formation refuses exclusive-carrying ports unless
the assignment is disjoint, on the **existing** `OWNERSHIP` axis; `Leased`
refused across any instance boundary (already the wire/cycle rule). Flip-window
buffering preserves exclusivity (spec 23 already permits Buffering).

**Test.** `OwnedRoutedShardTest` — `Owned` payloads routed through a
repartition: each consumed exactly once, none lost (dead-letter contract on
the parked window). **Controls**: (a) overlap admitted → double-consume
detected; (b) disjoint case green, proving the refusal is scoped precisely.

### PN-13 — `InstanceSetStep` in GraphSpec — P2 · Medium · `graph`
- **Context**: CONTINUE PN-18 (agent ζ) · **After**: PN-6 (assignment is a
  management invocation — the DSL rule "parameters, not verbs" holds)
- **Parallel**: PN-8, PN-14, PN-16
- **Files**: `graph/GraphDsl.kt`, spec `51-construction.md`.

**Problem** (plan §4 PN-13). No declaration surface produces a heterogeneous
instance set; hand-wiring N mechanisms is the status quo the plan exists to end.

**Implement.** `InstanceSetStep(handle, logicalId, factory, instances)` where
`instances = f(interestPartition, replicationFactor)` and each `InstanceSpec`
carries (interest, placement hint, journal id, frontier policy, instanceId).
Lowers to N × `SpawnStep(NewInstanceOf)` + N assignment invocations — nothing
the host doesn't already accept.

**Test.** `InstanceSetDeclarationTest` — a declared set replays from its
`GraphSpec` onto fresh hosts with identical memberships and link sets, 100
seeds of step orderings; mis-compositions (partition a SINGLETON cell; DURABLE
on journal-less host) are `Rejected`/refused naming the axis. **Control**: a
fully-default declaration lowers to a `GraphSpec` `equals` to the hand-written
one — the parameters-not-verbs check.

---

## Lane η — evolve & effects (one agent, two tickets)

### PN-14 — Rolling replicated (and partitioned) promotion — P2 · Medium · `evolve`+`repl`
- **Context**: FRESH (agent η) · **After**: PN-0c, PN-7 (δ owns `Replication.kt`
  through W5) · **Parallel**: PN-8, PN-18/13, PN-11, PN-16
- **Files**: `evolve/Evolution.kt`, `replication/Replication.kt` (`rebind` —
  additive), spec `53-evolution.md` §Replicated promotion (new).

**Problem** (plan §4 PN-14; matrix K–B empty). `promote` is single-instance:
gossip links aren't in `downstream`, the candidate's fresh `CellRef` re-mints
tag/watermark identity, and the retired incumbent's row holds frontiers
forever. Set-atomic promotion is consensus — out of scope.

**Implement.** Rolling, by constraint: one instance at a time; candidate
**reuses the incumbent's `CellRef`** (every mesh identity derives from the ref
— the swap is indistinguishable from crash-recovery, which is the mechanism);
surviving replicas play the retained incumbent; PRECHECK refuses T2
(fresh-epoch) for replicated cells and refuses a candidate with a different
ref; COMMIT re-points gossip links via `Replication.rebind`. Same rolling form
extends shard-by-shard to partitioned nodes (the G-50 "partitioned rolling
promotion" residual), ordering/abort as `PromotionPolicy` data; each swap
re-runs link-time authority (promotion is a rebind).

**Test.** `ReplicatedPromotionTest` — 3-peer mesh; shadow → judge → promote on
peer 0 while 1–2 write; roll to peer 1. 100 seeds: convergence to batch union;
a `useReplicaFrontier` consumer never surfaces an undelivered element across
swaps. **Controls**: (a) T2 allowed → double-count or wedged frontier; (b)
fresh `CellRef` → removes fail to cover post-promotion adds; resurrections.
**Closes K–B.**

### PN-17 — Effect authority on an instance set — P2 · Medium · `host`+`repl`
- **Context**: CONTINUE PN-14 (agent η — same evolve/replication context; reuses
  Shadow's NoOp-serve knowledge) · **After**: PN-14, PN-6
- **Parallel**: PN-19
- **Files**: `host/ManagedHost.kt` (`Effectful` delivery path),
  `replication/SingleWriterReplication.kt`, `evolve/` (NoOp-serve reuse), spec
  `31-hosts.md` §Effects on instance sets (new).

**Problem** (plan §3b/PN-17). Effectful × replicated is undefined: every
replica fires the external effect. Disjoint interest is effect-once *by
construction* (one covering instance + per-inlet processed-frontier dedup);
Total/overlap needs an authority.

**Implement.** Effectful + Total/overlapping interest requires a declared
effect authority: the `SingleWriterReplication` leader fires; followers
suppress via the Shadow NoOp-serve machinery; `LeaderMark` fencing covers
handoff. No authority declared → refused at instance-set formation.

**Test.** `ReplicatedEffectTest` — 3-replica effectful sink under
single-writer; external effect fires exactly once per logical delta across a
leader handoff. **Controls**: (a) authority off → effect fires N times (today,
made visible); (b) disjoint variant with no authority → still exactly-once,
proving the by-construction half.

---

## Lane ι — attention (one agent, one ticket)

### PN-19 — Attention scatter, per-instance park, and the `Stall` family — P2 · High · `attention`+`consistency`
- **Context**: FRESH (agent ι — attention/scheduling context; reads spec 34
  decisions 3/5 and 93 I-16/I-18) · **After**: PN-7, PN-10, PN-14 (η owns
  `Replication.kt` in W6 via `rebind`; PN-17's files are disjoint)
- **Parallel**: PN-17
- **Files**: `attention/**`, `consistency/WaveFrontier.kt` (Stall disposition),
  `replication/Replication.kt` (Stall/Resume notices), `data/PartitionedCell.kt`
  (router treats parked ≠ dead), spec `34-scheduling.md` (I-18 family lands).

**Problem** (plan §3b). Decided-unimplemented design (typed
`Stall(reason, recoverable)`/`Resume`) is exactly the quorum-shrink vocabulary
PN-7 documented as missing; attention currently has no interest-scoped
scattering, and a parked covering-quorum member wedges WAIT consumers with no
DEGRADE path.

**Implement.** (a) Attention scatters by interest overlap (metadata plane
reuses the data plane's rule; per-key economics stays G-62/M16 — out). (b) An
unattended instance parks like any cell; router/mesh treat it as stalled, not
dead. (c) The `Stall`/`Resume` family: suspended/departing covering instance
publishes `Stall`; WAIT holds (today), DEGRADE removes it from the covering
quorum and restores on `Resume` (post-resume replay = catch-up baseline, PN-2's
primitive); terminal stalls → RE-SCOPE + `GlitchViolation`. PN-0c's `close()`
becomes the degenerate terminal case.

**Test.** `PartitionedAttentionTest` — interest in one key range parks only
non-covering shards; a DEGRADE board keeps producing across a covering
instance's park/resume, no torn value; 100 seeds. **Controls**: (a) WAIT
variant stalls during the park (correct, documented); (b) `Stall` suppressed →
DEGRADE board tears or wedges. **Closes PN-7's documented DEGRADE gap; closes
attention × partition/replication.**

---

## Lane solo + λ + κ

### PN-11 — `ParkQueue` extraction — P3 · Low · `port`+`host`
- **Context**: FRESH (mechanical; needs no lane context) · **After**: PN-6
  (γ's `PartitionedCell` work done) · **Parallel**: PN-8, PN-18/13, PN-14, PN-16
- **Files**: `port/Buffering.kt`, `port/FanInlet.kt`,
  `membrane/TrafficLightCell.kt`, `host/LocationRegistry.kt`,
  `host/ManagedHost.kt` (suspendedCells), `data/PartitionedCell.kt` (flipBuffer).

**Problem** (plan §4 PN-11; STATUS §3 undercounts ×3 — it's five sites at three
type levels). One primitive (append-in-order / hold / drain-once) hand-rolled
five times.

**Implement.** One `ParkQueue<T>`; the five sites become instantiations.
`WaveFrontier.pending` is *not* one (it reorders) — leave it. Behavior
byte-identical everywhere.

**Test.** Existing pin tests (TrafficLight, migration, saturation, flip) green
unchanged — that *is* the test; plus one `ParkQueueTest` for the primitive's
three operations. **Control**: drain-twice or hold-leak in the primitive →
migration/flip tests diverge.

### PN-16 — Research spike: re-scoped frontier-traversal question — P3 · Medium · `research`
- **Context**: FRESH (agent λ; reads PN-7's result) · **After**: PN-7
- **Parallel**: everything (no merge commitment)
- **Files**: `doc/spec/90-roadmap/95-research-plan.md`, throwaway branch.

**Problem** (plan §4 PN-16). CP-G7 as written asks whether the static link set
suffices. The landscape changed: PN-1/PN-10 remove the non-announcing-link
counterexamples; PN-7 already makes settlement quantify over a
registry-discovered instance set (dynamic, without multiplex ports); and the
asymmetric-durability diamond is the concrete graph the static frontier gets
wrong (an edge that structurally never carries a source can never settle it).

**Deliverable.** A decision, not code: is static links + absorb-acks +
interest-scoped quorum + per-edge declared source sets sufficient for every
structure we build? **Exit**: a ticket for the real thing (multiplex G-13), or
a spec paragraph promoting the design from shortcut to decided. Supersedes
CP-G7.

### PN-15 — The evidence join — P2 · Medium · `demo`
- **Context**: FRESH (agent κ — demo-builder context, CP-E1-style) ·
  **After**: PN-8, PN-12, PN-17 (last merge of the run) · **Parallel**: none
- **Files**: `demo/exchange/**` only.

**Problem** (plan §4 PN-15). The composition evidence graph must exercise the
new per-node combinations, or the run's claim is unit-test-deep — the same
honesty rule CP-G5 encoded.

**Implement.** CP-G5's two arms (bridged arm A–F/A–O; filtered arm A–C) **plus**
a sharded-AND-replicated arm into the board (Phase 3's payoff in the evidence
graph) and the manifest assertion (board {GLITCH_FREE}, writers {DURABLE},
union {REPLICATED}, shards {PARTITIONED}). Update the pair matrix in
`COMPOSITION-STATUS.md` (A–D, C–D, C–M, K–B + effectful/ownership/attention
rows → covered, with test names).

**Test.** Extend `ExchangeCompositionExitTest` — same 100 seeds, same
batch-recompute equality, all arms live through repartition / migration /
kill -9 / late join / (new) replica failover. **Controls**: frontier off →
torn board; absorb-ack suppressed → stall; covering-quorum member evicted
without close → wedge (all three must diverge).
