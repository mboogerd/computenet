# Concord corpus disputes

Per CONCORD-PLAN §5: when a requirement cannot be checked honestly against the
current driver/kernel binding, that is filed here — never patched into a
scenario as a weakened check or a silently-omitted assertion. Each entry names
the scenario id, the requirement it would cover, the missing capability, and the
check to restore once the capability lands.

This file is the **consolidated worklist** for the W3 corpus wave — merged from
the three parallel corpus tickets (W3-1 propagation/consistency, W3-2 operators,
W3-3 ports/links/lifecycle/cycles/ownership/controls). Entries are organized by
scenario id and tagged with a category. W3-4 took the cheap
`driver-wiring-gap` / `driver-bug` items: the `intersect` port bug and the
single-writer wiring resolved cleanly (scenarios authored & passing); the
glitch-free *wiring* landed too, but its stream-invariant checks turned out to
need a deeper kernel capability (a wave-coalescing operator) and remain filed.

## Resolved by W3-4 (driver-wiring gaps)

- `24-OP-INTERSECT-01` — **RESOLVED** (`driver-bug`). `KernelCatalog.inletName`
  no longer collapses `intersect` to a nonexistent `"inlet"`; it routes the two
  inputs to the `left`/`right` ports `IntersectSetCell` exposes. Scenario
  `24-OP-INTERSECT-01.yaml` authored and passing (golden +
  `incremental-equals-batch` + `no-dead-letters`). `quorum-set`/`union` did NOT
  share the bug — they are genuinely single-`inlet` fan-ins (`QuorumSetCell`/
  `UnionSetCell` expose one `inlet`), so only `intersect` moved branches.
- `13-LINK-REJECT-01` — **RESOLVED** (`driver-wiring-gap`). `inlet-mode:
  single-writer` now binds a strict point-to-point (FU-6) `FanInlet` on the
  view's inlet (driver adapter `SingleWriterObserveCell`), so a second writer's
  `connect` is `Rejected`. Scenario authored per exemplar (d) and passing.
- **glitch-free wiring** (`driver-wiring-gap`) — **RESOLVED** for the wiring
  itself: `glitch-free: true` was inert (silently ignored); the W3-4 driver now
  spawns a downstream kernel `GlitchFreeCell` and routes the operator's output
  through it over a real host link (GlitchFreeOperatorSuiteTest construction),
  de-inerting the param. `22-WAVE-FANIN-01`'s `incremental-equals-batch` /
  `no-dead-letters` now pass *through* that wrapper. What did NOT resolve is the
  positive stream assertion (`observations-all-satisfy`) — a genuinely deeper
  gap, re-filed below.

## Category index (worklist, cheapest first)

**`schema-gap` — a descriptor surface the frozen W0 schema does not expose;
needs a between-waves schema-change ticket:**

- `12-NEGOTIATE-01` — no `nature:`/`requires:` descriptor to drive a
  `PortNatures.stamp` mismatch; contract/nature refusal is unexpressible.
- `23-SPSC-01` — no `outlet-mode: exclusive` descriptor and no exclusive-payload
  catalog cell; exclusive-outlet fan-out rejection is unexpressible.
- `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — no window-spec descriptor (also
  `kernel-gap`: no `window` cell binding — see entry).
- `22-WAVE-FANIN-01` — no set-shaped per-wave predicate in the frozen function
  catalog, so "this observation is a complete wave" is unassertable over a set
  stream (the glitch-free *wiring* landed W3-4; this check-shape gap did not —
  see entry).

**`oracle-gap` — the harness-side batch oracle's fold model is missing;
harness work, not corpus or kernel:**

- `24-OP-PRESENCE-01` — the oracle folds `presence-count` as a scalar
  cardinality, but the kernel cell is a per-element fan-in lane count;
  `incremental-equals-batch` is omitted until the oracle models it.

**`kernel-gap` / `spec-gap` — capability absent from the kernel, or the decided
design is unimplemented; deep, implementation-ticket work:**

- `21-REBASE-01` / `15-RESTART-01` — no RESTART/re-baseline driver verb; the
  landed RESTART mechanism contradicts the decided `ReBaseline` design
  (conflict C-12). `kernel-gap` + `spec-gap`.
- `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — no `window` cell in the kernel
  (`Windows` ships key functions only). `kernel-gap` (+ `schema-gap`).
- `22-GF-DIAMOND-01` / `22-GF-NESTED-01` — no wave-**coalescing** operator in the
  kernel. The W3-4 glitch-free wiring wraps outputs in `GlitchFreeCell`, but that
  replays per-invocation and every catalog operator emits one delta per element,
  so a scalar observer still folds the torn intermediate (verified: `even` fails
  on event #1 of the diamond). A version-buffered combine that emits once per
  completed wave is absent. `kernel-gap`.

---

## By scenario id

### `24-OP-INTERSECT-01` — **RESOLVED (W3-4, `driver-bug`)**

- `KernelCatalog.inletName` no longer collapses `intersect` to a nonexistent
  `"inlet"` — it routes `intersect`'s two inputs to `left`/`right` (the ports
  `IntersectSetCell` exposes), alongside `combine-latest`/`join`/`semi-join`/
  `lookup-join`. `union`/`quorum-set` stayed on the single `inlet` branch (they
  are genuine single-port fan-ins, they did NOT share the bug).
- Scenario `24-OP-INTERSECT-01.yaml` authored (`a`={x,y,z} on `left`, `b`={y,z,w}
  on `right`, then `a` removes y → `[z]`) with `final-view` +
  `incremental-equals-batch` + `no-dead-letters`; passes the 20-run sweep.

### `13-LINK-REJECT-01` — **RESOLVED (W3-4, `driver-wiring-gap`)**

- `inlet-mode: single-writer` is now honoured: a view declaring it binds the
  driver adapter `SingleWriterObserveCell`, whose inlet is a strict
  point-to-point (FU-6) `FanInlet` (`singleWriter = true`). A second
  `LinkRole.Consume` producer's `connect` is `Rejected`; Observe taps stay
  unrestricted.
- Scenario `13-LINK-REJECT-01.yaml` authored per CONCORD-PLAN exemplar (d) (first
  writer flows, second writer `expect: rejected`, `final-view: [still-flows]` +
  `no-dead-letters`); passes the sweep.

### `22-WAVE-FANIN-01` — wiring **RESOLVED (W3-4)**, check-shape **`schema-gap`** remains

- **Requirement**: `22-GF-01` (while a single-source wave is partially delivered
  across a fork-join, a glitch-free cell shall not expose derived state mixing
  pre-wave and post-wave inputs).
- **Wiring (resolved)**: `glitch-free: true` was inert; the W3-4 driver now
  spawns a downstream kernel `GlitchFreeCell` and routes the operator's output
  through it over a real host link (so the frontier sees `EdgeOpen`/`Progress` —
  the GlitchFreeOperatorSuiteTest construction). The wrapper is
  correctness-preserving: this scenario's `final-view` /
  `incremental-equals-batch` / `no-dead-letters` pass *through* it.
- **Remaining gap (schema/check-vocabulary)**: asserting "no observation mixes
  pre-wave/post-wave inputs" over a **set-shaped** observation stream still needs
  a predicate the frozen function catalog lacks — the scalar
  `even`/`odd`/`mod-eq`/`eq`/`gt`/`lt` predicates all return false on a set
  (`ListVal`) observation, and `observations-all-satisfy(fn)` evaluates one
  element predicate per observation with no notion of wave completeness. A scalar
  recast does not rescue it either — see the `22-GF-DIAMOND-01` kernel-gap below.
- **Filed scenario**: `22-WAVE-FANIN-01.yaml` (one source forked through two
  identity arms into a 2-of-2 glitch-free `quorum-set`) keeps `final-view` +
  `incremental-equals-batch` + `no-dead-letters`. Restore
  `observations-all-satisfy` when a set-shaped per-wave predicate lands in the
  function catalog (schema-change ticket).

### `22-GF-DIAMOND-01` / `22-GF-NESTED-01` — wiring **RESOLVED (W3-4)**, wave-coalescing **`kernel-gap`** remains

- **Requirement**: `22-GF-01` / `22-GF-02` (glitch-freedom, composing across
  nested/chained fork-joins).
- **Wiring (resolved)**: `glitch-free: true` now spawns the downstream
  `GlitchFreeCell` wrapper (as above). But this does **not** make the scalar
  diamond glitch-free-observable: the only scalar `combine-latest` binding is
  `ScalarSumCombineCell`, which emits a `CounterDelta` per input arm; the two
  arms of one source wave arrive as distinct waves and `GlitchFreeCell` replays
  per-invocation, so an observer still folds the odd (torn) intermediate sum.
  **Verified**: adding `observations-all-satisfy(v, even)` back fails on every
  run with `event #1 = 1` (the torn intermediate).
- **Remaining gap (kernel)**: a genuinely wave-aligned / wave-**coalescing**
  scalar combine (version-buffered, emitting one delta per completed wave) does
  not exist in the kernel (cell-catalog.md "the two honest gaps", gap 1). This is
  the same shape blocking the set case (every catalog operator emits one delta
  per element), so it is the general blocker for observing glitch-freedom.
- **Filed scenarios**: `22-GF-DIAMOND-01.yaml` and `22-GF-NESTED-01.yaml` keep
  `final-view` only. Disabled checks to restore verbatim once a wave-coalescing
  operator lands:
  ```yaml
  - {type: observations-all-satisfy, view: v, fn: even}       # 22-GF-DIAMOND-01
  - {type: observations-all-satisfy, view: v, fn: mod-eq(4,0)} # 22-GF-NESTED-01
  ```

### `12-NEGOTIATE-01` — **`schema-gap`**

- **Requirement**: `13-LINK-05` (rejection reasons include schema/contract
  mismatch), plus the "admission… contract compatibility (`portName`,
  `contractId`)" prose in `13-links.md`.
- **Gap**: the kernel has a genuine link-time typed-refusal mechanism —
  `NatureNegotiation` (CP-F3), exercised by `TypedRefusalTest` /
  `NegotiatedAttachmentTest` — but it operates on `NatureVector`s stamped onto
  ports via the kernel-internal `PortNatures.stamp(...)`. No `CellSpec`/`LinkSpec`
  field in the frozen W0 schema exposes a nature requirement/offering, and no
  catalog cell declares a non-default nature that would trigger a mismatch
  through ordinary wiring. Plain generic-contract mismatches are not rejected at
  `connect` either (erased-generic `Propagate<Any>` adapters accept whatever is
  routed).
- **Resolves**: a schema-change ticket exposing a `nature:`/`requires:`
  descriptor the driver translates into a `PortNatures.stamp(...)` call — or a
  catalog cell pair whose fixed natures already conflict (none at W3-0).

### `23-SPSC-01` — **`schema-gap`**

- **Requirement**: `12-EXCL-01` (fan-out MUST be rejected at link time when the
  contract's payload carries exclusive ownership — `Owned`/`Leased`).
- **Gap**: the M5.6 exclusive-bit mechanism is real and kernel-tested
  (`OwnershipTest`: a second subscriber on an `Owned`/`Leased`-carrying
  `FanOutlet` is rejected), but every catalog-bound cell emits a plain
  non-exclusive `Propagate<Delta>`, and the schema has no `outlet-mode`
  descriptor requesting an exclusive outlet (unlike `inlet-mode`). So "second
  consume-link on an exclusive outlet rejected" is unexpressible in catalog
  vocabulary (P5) without a new catalog cell or descriptor param. The observe/tap
  **admit** half is separately deferred to G-47 (`role: observe`/`consume` is not
  yet differentiated by the driver — see `13-TAP-01`).
- **Resolves**: a schema-change ticket adding an exclusive-payload catalog cell
  (or `outlet-mode: exclusive` bound to an existing source) plus driver wiring,
  deferred alongside G-47.

### `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — **`kernel-gap`** + **`schema-gap`**

- **Requirement ids**: `24-OP-WINDOW-01`, `24-OP-WINDOW-02`
  (`24-data-cells.md`, §Grouped aggregation "Windowing = key derivation").
- **Gap**: the `window` cell-catalog id has no honest kernel binding —
  `KernelCatalog.build("window", …)` throws `UnsupportedCatalogBinding`
  (`Windows` ships event-time key functions, tumbling/sliding, not a cell), and
  no window-spec descriptor is frozen on the scenario schema. `BatchOracle`
  folds `window` as an untested pass-through for the same reason. Authoring a
  scenario would either hit `UnsupportedCatalogBinding` at construction or fake a
  pass-through against an oracle flagged untested — the iron rule forbids both.
- **Resolves**: a schema-change ticket freezing a window-spec descriptor
  (tumbling/sliding params) **and** a real kernel windowing binding (composite
  key derivation over `Windows.tumbling`/`sliding`) the oracle can model
  identically. Open coverage gap until then.

### `24-OP-PRESENCE-01` — **`oracle-gap`** (harness-side)

- **Requirement**: no dedicated EARS id (see the coverage note in
  `24-OP-PRESENCE-01.yaml`); a genuine oracle/driver semantic mismatch, not a
  missing-id gap.
- **Gap**: `BatchOracle` folds both `count` and `presence-count` to "current
  membership cardinality" (a documented v1 simplification). That is a reasonable
  stand-in for `count`, but `PresenceCountCell` shares its `PresenceLanes`
  substrate with `QuorumSetCell` — it is a **fan-in** cell keeping one
  `TagState` per open source link, emitting `MapDelta<E, Int>` keyed by element
  (value = number of distinct live source links asserting that element,
  group-death at 0). It is not a scalar count.
- **Resolved without forcing**: `24-OP-PRESENCE-01.yaml` was redesigned around
  the real fan-in shape (two set sources into one `presence-count`, read through
  a `map-view`); `incremental-equals-batch` is deliberately omitted (only
  `final-view` + `no-dead-letters` asserted) because the oracle's scalar model
  has no fold for the per-element lane-count semantics.
- **Resolves**: an oracle-model update (`BatchOracle.presenceFold` / a
  `Fold.MapF`) folding `presence-count` as a per-element live-source-link count,
  at which point `incremental-equals-batch` can be restored. Oracle code lives
  in `concord/src/main/kotlin/civictech/concord/oracle/` — harness work, out of
  the corpus scope fence.

### `21-REBASE-01` / `15-RESTART-01` — **`kernel-gap`** + **`spec-gap`**

*(Filed independently by W3-1 (as `21-REBASE-01`) and W3-3 (as `15-RESTART-01`);
same root cause, consolidated here.)*

- **Requirement**: `21-REBASE-01` (WHEN a source re-baselines — RESTART or
  re-baseline — the framework SHALL reconcile downstream consumers so their
  folds converge to a value consistent with the restored state, equal to a
  delta-only twin).
- **Gap (driver SPI)**: the twelve-verb driver SPI
  (`createHost`/`spawn`/`connect`/`disconnect`/`apply`/`quiesce`/`readView`/
  `observationLog`/`snapshot`/`restore`/`despawn`/`deadLetters`+`effectLog`) has
  no `restart`/`rebaseline` verb. The only state verb, `restore(hostId, cellId,
  blob)`, is implemented as a **raw** `Stateful.restore(state)` on the live cell
  (confirmed against `SetCell.restore`): it swaps internal maps with no
  propagate, no `ReBaseline` emission, and no downstream announcement — a
  downstream view never learns the source was restored. `restore` is the right
  primitive for despawn/migration scenarios (`15-SNAPSHOT-01`, `33-MIGRATE-01`,
  `DUR-SNAPTAIL-01`), not for RESTART re-baseline.
- **Gap (kernel/spec)**: no catalog source cell implements `ReBaselineEmitting`
  (only the `UnionSetCell` *consumer* reacts to an incoming `ReBaseline`). The
  kernel's actual RESTART re-baseline path is exercised only by a bespoke test
  cell (`RestartReBaselineTest`'s `TaggedProducerCell`), not in the neutral
  catalog, and the driver never calls
  `ManagedHost.supervise(ref, SupervisionPolicy.RESTART)`. `21-propagation.md`
  records the decided design (fresh per-epoch `sourceId`, `ReBaseline` notice,
  catch-up reconciliation) as **unimplemented**; the landed RESTART behaviour is
  the bare local rollback the decision forbids (conflict C-12, recorded at 30/31
  and 20/22) — it would not honestly pass a scenario asserting the decided
  semantics.
- **No scenario authored** on either ticket. `15-SNAPSHOT-01` is the closest
  honest coverage of "restore state across a lifecycle event" and is explicit
  that it does not exercise RESTART's sourceId/tag-epoch semantics.
- **Resolves**: land the decided RESTART re-baseline mechanism (93 I-22) — a
  catalog source implementing `ReBaselineEmitting` plus a supervision-RESTART
  trigger — then add a `restart`/`rebaseline` driver verb (or a `RestoreStep`
  mode). Then author `21-REBASE-01.yaml`: a rebased source reconciling
  mid-stream vs a delta-only twin, `{type: views-converge, views:
  [<rebased-view>, <twin-view>]}`.

---

## W4-A additions (dist profile — distribution, 41/42/33)

W4-A bound the honestly-drivable dist scenarios (`42-REPL-01`, `42-REPL-LATE-01`,
`41-SPLIT-01`, `33-MIGRATE-01`) against the kernel's real multi-host mesh
(`SimulationController` N-host, `cell.replication.Replication` gossip, routed
`streamTo` cross-host edges, `ManagedHost.migrate`). Two of the plan's §3 rows
resist the *frozen driver SPI / schema*, not the kernel, and are filed here.

### `42-REPL-DEPART-01` — **`check-vocabulary-gap`** (+ SPI-gap)

- **Requirement**: `42-REPL-06` (IF a replica departs orderly while peers keep
  accepting writes, THEN survivors converge and the departed replica's frozen
  stream is not counted as a divergence — spec 42 §G-45 departed-stream rule).
- **Gap**: the `replicas-converge(logical)` check reads `readView` over **every
  cell declared `replica-of: <logical>` in the graph** — a static set. Departing
  a replica through the SPI means `despawn` (or evict), which removes it from the
  driver's cell table, so `readView(departed)` throws; and even if its last fold
  were retained, the check would compare that frozen value against advancing
  survivors and **false-fail** — the exact G-45 false-positive the requirement
  says must not happen. The check has no notion of a *live* replica subset, and
  the departed-stream rule that fixes this lives in a kernel-internal harness
  (`cell.verify.ReplicaConvergence`, 50/52), below the boundary (P1). A
  no-post-departure-writes variant would pass but exercise nothing — the whole
  point is survivor advance after departure — so it is not authored (the iron
  rule forbids a check that asserts nothing).
- **Resolves**: a schema-change ticket giving `replicas-converge` a live/survivor
  scope (e.g. `replicas-converge(logical, excluding: [<departed>])`, or a
  driver-reported liveness set), after which author `42-REPL-DEPART-01.yaml`:
  three replicas, despawn one, keep writing to the survivors,
  `{type: replicas-converge, logical: shared}` over the live set.

### `42-INTEREST-01` — **`schema-gap`**

- **Requirement**: `42-INT-01` (WHERE an instance declares a partial `Interest`,
  it holds exactly the interest-admitted subset — spec 42 §Interest-scoped
  instance sets).
- **Gap**: the **kernel supports this fully** — `LocationRegistry.setInterest(ref,
  Interest.Slots/Ranges/…)` before `Replication.replicate` gives an
  interest-scoped replica that links (and holds) only the admitted slice
  (`InterestScopedGossipTest`/`ShardedReplicationTest`). But the **frozen W0
  scenario schema has no `interest` descriptor param** on `CellSpec` (only `of`,
  `fn`, `agg`, `k`, `glitch-free`, `inlet-mode`, `host`, `replica-of`), and the
  parser drops unknown keys, so a scenario cannot express an interest assignment
  and the driver never receives one. Adding an `interest:` field (with a neutral
  interest sub-grammar — total/empty/slots/ranges) is a schema-types change,
  outside the W4-A corpus/driver fence.
- **Resolves**: a schema-change ticket freezing an `interest:` descriptor param
  and its neutral value grammar, plus a `final-view`-vs-filtered-oracle check
  (or reuse `final-view` against a hand-computed slice). The driver binding is
  ready (`registry.setInterest(replica.ref, …)` in `KernelDriverDist.spawnReplica`
  before `replicate`); only the scenario surface is missing. Then author
  `42-INTEREST-01.yaml`: two disjoint-slot replicas of one logical set,
  `final-view` on each equal to its filtered slice.

---

## W4-B durability (`profile: dur`) — what landed, and the one honest boundary

The `dur` profile is genuinely drivable against the kernel's real durability
machinery (per-cell `journalFor` selector, `checkpoint`/`recoverFrom`, and the
`Effectful` processed-frontier; kernel `EffectfulRecoveryTest`/`CrashRecoveryTest`).
Two scenarios pass the 20-run sweep under `-Pconcord.profiles=core,dur`:

- `DUR-REPLAY-01` (`24-DUR-01/02/05`) — crash → journal replay → continue.
  `effect-count(esink, exactly: 1)` is **honestly exercised, not trivial**:
  instrumentation confirmed the WAL held the sink's frames + processed-frontier
  records, and `recoverFrom` re-delivered them while the restored frontier
  suppressed every already-applied `(sourceId, counter)` — the external effect
  log stayed at its pre-crash size across replay, then advanced only for the
  post-recovery key. `incremental-equals-batch(dview)` recovers the data view
  through its own snapshot/restore (the checkpoint half of durable recovery).
- `DUR-SNAPTAIL-01` (`24-DUR-02/03`) — checkpoint + journal-tail replay of a
  journaled source→view equals an uninterrupted twin (`views-converge`).

### The boundary (`kernel-gap` / design ceiling, G-59 / C-9) — not faked, respected

The `Effectful` frontier keys on `MessageContext.timestamp.sourceId`, which the
**producing `FanOutlet` mints with a random per-instance `sourceId`** (not
ref-derived; `SetCell.restore` restores its OR-set tag counter but *not* its
outlet wave state). Consequence, verified by construction: a **journaled source
that feeds an effectful sink would double-fire** on recovery — its replayed
re-emission carries a fresh `sourceId` the sink's restored frontier cannot match.
So exactly-once effect delivery is drivable only when the effect subgraph's source
is **volatile** (it dies on the crash and is re-delivered nothing — exactly how
`EffectfulRecoveryTest`'s unhosted source is discarded), and the sink recovers
from its *own* journaled frames. This is the recorded G-59 gap ("spontaneously-
emitting sources … `Effectful` sinks without idempotency keys are unhandled") and
the C-9 boundary; the M10 mechanism is sound for the replay-stable idempotent
vocabulary, and the "external-idempotency ceiling" (93 I-7) is a stated limit, not
a bug. It is why `DUR-REPLAY-01` keeps the data-recovery path (journaled/snapshot,
`incremental-equals-batch`) and the effect-once path (`effect-count`) as **two
independent subgraphs**: a single cell cannot be both frontier-suppressed (never
re-applied) *and* state-rebuilt-by-replay.

- **Resolves**: an output-mode / ref-derived wave identity for spontaneously-
  emitting sources (or a captured-entropy WAL record), so a journaled source's
  replayed emissions carry the identity the sink's frontier already recorded —
  then a journaled source could feed an effectful sink and re-emit without
  double-firing, and `DUR-REPLAY-01` could fold both concerns onto one subgraph.

### Not covered (deferred, honestly out of reach at W4-B)

- `24-DUR-04` (replay-stable identity, no resurrected removals) is exercised
  *indirectly* — `DUR-SNAPTAIL-01`'s recovered `SetCell` re-mints ref-derived
  tags, so its recovered membership equals the twin's with no double-count — but
  it is not asserted head-on (a directed add/remove/replay control belongs in a
  kernel unit test; the OR-set tag plane is not boundary-observable per P1).
- `24-REPLAY-01` (a journaled mid-graph cell's replayed frames flagged
  `MessageContext.baseline` so a downstream **glitch-free join** installs them as
  arm state) is **not** authored: the `dur` corpus has no glitch-free join, and
  the scalar/set glitch-free observation gap is itself filed above
  (`22-GF-DIAMOND-01`). Author it once a wave-coalescing operator lands and a
  durable arm can feed it.
- `FileJournal` segmentation/rotation and **cross-host** recovery-frontier drift
  are single-in-process-host out of scope here (the driver runs the durable
  subgraph on one reserved host; cross-host is W4-A `dist` territory).

### How it is driven (modeling notes, not disputes)

No new script verbs or schema fields (the W0 seam holds). A durable subgraph
lives on the reserved host id `dur`; a `journal`-typed controller pseudo-cell is
the crash handle (`despawn` of it = crash + `recoverFrom` in one step); a
`snapshot` of a journaled cell lowers to `host.checkpoint`. Catalog additions
(driver-only, `KernelDriverDur.kt`): `journal-set-source`, `journal-set-view`,
`effect-sink`, `journal`. Durable links are wired **through the host intake** (a
`HostedCellProxy` subscribed to the source outlet), never a raw
`managementInlet.connect`: the kernel journals + enforces the `Effectful`
frontier only at `enqueueHostedInvocation`, and a raw port `linkTo` bypasses that
funnel entirely (a silent no-journal path — caught only because instrumentation
showed a zero-length WAL). Reserved-host caveat for the merge: `host: dur` is a
`dur`-profile convention; a `dist` scenario (W4-A) must not name a host `dur`.
