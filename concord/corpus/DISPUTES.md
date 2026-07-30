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
need a deeper kernel capability (a wave-coalescing operator) and stayed filed
until wave B2 — the set-shaped half was resolved in R2 over `quorum-set`, and
the scalar half in B2/D-CONCORD once `CoalescingCombineCell` landed (D-COMBINE).

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

## Resolved by R1 (dispute-resolution wave)

Five entries below were closed in dispute-resolution wave R1 (two parallel
tickets, file-merged onto `main`). Each gap named a *real* kernel/oracle
mechanism that had no scenario surface; R1 built the missing surface (a catalog
cell, a schema descriptor, or a harness fold) without weakening any check. Their
full by-scenario entries below are now marked **RESOLVED (R1)**.

- `42-INTEREST-01` — **RESOLVED** (`schema-gap`). New `interest:` descriptor
  (`InterestSpec` on `CellSpec`, `Scenario.kt`); `CorpusRunner` lowers it to a
  neutral `Value`, `KernelDriverDist.spawnReplica` stages it via
  `LocationRegistry.setInterest(...)` **before** `Replication.replicate`. Two
  disjoint-slot replicas each hold exactly their admitted slice.
- `12-NEGOTIATE-01` — **RESOLVED** (`schema-gap`). New `nature-gate` catalog cell
  (`KernelAdapters.NatureGatedSinkCell`) registers a fixed inlet nature
  (`MergeClass.IDEMPOTENT`) via the public `ContractRegistry` seam, so a plain
  default-nature source's `connect` is refused by the kernel's own
  `NatureNegotiation` reconciler (CP-F3) — no schema field, no driver-side fake.
- `23-SPSC-01` — **RESOLVED, REJECT HALF ONLY** (`schema-gap`). New
  `exclusive-source`/`exclusive-sink` catalog cells
  (`KernelAdapters.ExclusiveSourceCell`/`ExclusiveSinkCell`) emit a genuine
  `Owned` payload, so a second `Consume` link is refused by the kernel's own
  `FanOutlet` exclusivity check (M5.6). **The observe/tap ADMIT half remains
  deferred to G-47** (the driver still does not differentiate `role: observe`
  from a Consume link — see `13-TAP-01`).
- `42-REPL-DEPART-01` — **RESOLVED** (`check-vocabulary-gap`).
  `Checks.replicasConverge` now scopes to *live* replicas — it drops any declared
  replica whose `readView` no longer resolves (the departed-cell signal `despawn`
  already yields), matching the kernel's departed-stream rule (spec 42 §G-45,
  `42-REPL-06`). Survivors converge; the departed frozen fold is excluded, not
  false-failed.
- `24-OP-PRESENCE-01` — **RESOLVED** (`oracle-gap`). `BatchOracle.presenceCountFold`
  now models `presence-count` as a per-element live-source-link count
  (`MapDelta<E,Int>`, group-death at 0) matching `PresenceCountCell`, so
  `incremental-equals-batch` is restored rather than omitted.

## Resolved by R2 (dispute-resolution wave — observability + windowing)

Five more entries closed in dispute-resolution wave R2 (two parallel tickets,
file-merged onto `main`). Each named a gap that on investigation turned out to be
a missing *harness/schema surface* over a real kernel capability — not a kernel
hole — so R2 built the surface without weakening any check. Their full
by-scenario entries below are now marked **RESOLVED (R2)**.

- `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — **RESOLVED** (`schema-gap` +
  `driver-binding-gap`, NOT a kernel gap). Window is a key-derivation group-by
  over the kernel's real `Windows.tumbling`/`sliding` functions: `CellSpec.window`
  (`WindowSpec` on `Scenario.kt`) is bound in `KernelCatalog`/`KernelAdapters`
  (`kind:tumbling`→`GroupByCell` with a `Windows.tumbling` `keyFn`;
  `kind:sliding`→`WindowSlidingCell`, a `FlatMapSetCell` over `Windows.sliding`
  into a `GroupByCell`), and `BatchOracle.windowFold` models the identical key
  derivation. Both scenarios pass golden + `incremental-equals-batch` +
  `no-dead-letters`.
- `22-WAVE-FANIN-01` — **RESOLVED** (`schema-gap`). The set-shaped per-wave
  predicate that was missing now exists: `observations-whole-waves`
  (`schema/Check.kt` + `check/Checks.kt`, R2-A) asserts that every observation on
  the view is a complete wave (no torn single-arm partial), evaluated over the
  real `quorum-set` (`QuorumSetCell`) glitch-free fan-in. The scenario positively
  asserts glitch-freedom again alongside `final-view` + `incremental-equals-batch`
  + `no-dead-letters`.
- `22-GF-DIAMOND-01` / `22-GF-NESTED-01` — **RESOLVED** (`schema-gap`). Re-modeled
  as SET fork-joins over `quorum-set` (a genuine kernel glitch-free operator whose
  `evaluate()` only `propagate()`s once quorum is met), so glitch-freedom is now
  positively asserted via `observations-whole-waves` (single diamond and nested
  double-diamond respectively) — no longer `final-view`-only.

## Category index (worklist, cheapest first)

**`schema-gap` — a descriptor surface the frozen W0 schema does not expose;
needs a between-waves schema-change ticket:**

*(empty — every entry filed under this category has been resolved.)*

*(`24-OP-COMBINE-01` (+ the `CTL-GF-01` control) — resolved in B2/D-CONCORD: the
wave-coalescing scalar combine landed in the kernel (D-COMBINE) and binds inside
the **existing** `combine-latest` id and `glitch-free` param, so this never
needed a schema change after all; `24-OP-COMBINE-02` now asserts scalar
glitch-freedom positively. See the "By scenario id" entry.)*

*(`24-OP-WINDOW-01` / `24-OP-WINDOW-02` — resolved in R2 (window = key-derivation
group-by over `Windows`); `22-WAVE-FANIN-01` — resolved in R2 (set-shaped
`observations-whole-waves` predicate landed). See the "Resolved by R2" section.)*

*(`12-NEGOTIATE-01`, `23-SPSC-01` (reject half), `24-OP-PRESENCE-01` — resolved
in R1, see the "Resolved by R1" section above.)*

**`kernel-gap` / `spec-gap` — capability absent from the kernel, or the decided
design is unimplemented; deep, implementation-ticket work:**

- `21-REBASE-01` / `15-RESTART-01` — no RESTART/re-baseline driver verb; the
  landed RESTART mechanism contradicts the decided `ReBaseline` design
  (conflict C-12). `kernel-gap` + `spec-gap`.

*(`24-OP-COMBINE-01` / `CTL-GF-01` (scalar `combine-latest`) — resolved in
B2/D-CONCORD: the wave-**coalescing scalar** combine now exists in the kernel
(`CoalescingCombineCell`, D-COMBINE — version-buffered, one delta per completed
wave), the driver binds it behind `glitch-free: true`, and `24-OP-COMBINE-02`
asserts glitch-freedom positively over a fork-join diamond. `24-REPLAY-01` —
resolved in B2/D-CONCORD: `QuorumSetCell` now installs a replayed baseline as
recovered arm state (D-REPLAY), and the dur scenario is authored. There is no
remaining glitch-free `kernel-gap`. See the "By scenario id" entries.)*

*(`24-OP-WINDOW-01` / `24-OP-WINDOW-02` — resolved in R2: on investigation this
was a schema/driver-binding gap, not a kernel one (`Windows.tumbling`/`sliding`
are real). `22-GF-DIAMOND-01` / `22-GF-NESTED-01` — resolved in R2: re-modeled as
SET fork-joins over the real `quorum-set` glitch-free join, so wave-coalescing is
no longer needed to observe them. See the "Resolved by R2" section.)*

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

### `22-WAVE-FANIN-01` — **RESOLVED (R2, `schema-gap`)**

- **Requirement**: `22-GF-01` (while a single-source wave is partially delivered
  across a fork-join, a glitch-free cell shall not expose derived state mixing
  pre-wave and post-wave inputs).
- **Wiring (resolved W3-4)**: `glitch-free: true` was inert; the W3-4 driver
  spawns a downstream kernel `GlitchFreeCell` and routes the operator's output
  through it over a real host link (the GlitchFreeOperatorSuiteTest construction).
  The wrapper is correctness-preserving: `final-view` / `incremental-equals-batch`
  / `no-dead-letters` pass *through* it.
- **How resolved (R2-A)**: the missing piece was a **set-shaped per-wave
  predicate**. R2 added the `observations-whole-waves` check (`schema/Check.kt`
  parse + `check/Checks.kt` evaluator/dispatch): over the real `quorum-set`
  (`QuorumSetCell`, `civictech.cell.data.op`) glitch-free fan-in — whose own
  `evaluate()` only `propagate()`s once quorum is met — it asserts that every
  observation on the view is a *whole wave*, never a torn single-arm partial.
  No scalar predicate is coerced onto a `ListVal`; the check reasons about wave
  completeness directly.
- **Filed scenario**: `22-WAVE-FANIN-01.yaml` (one source forked through two
  identity arms into a 2-of-2 glitch-free `quorum-set`) now positively asserts
  `observations-whole-waves` alongside `final-view` + `incremental-equals-batch`
  + `no-dead-letters`. Passes the multi-profile sweep.

### `22-GF-DIAMOND-01` / `22-GF-NESTED-01` — **RESOLVED (R2, `schema-gap`)**

- **Requirement**: `22-GF-01` / `22-GF-02` (glitch-freedom, composing across
  nested/chained fork-joins).
- **How resolved (R2-A)**: both were originally SCALAR diamonds over
  `combine-latest` (`ScalarSumCombineCell`), which cannot be glitch-free-observed
  (torn per-arm delta — see the scalar-combine residual entry below). R2
  **re-modeled them as SET fork-joins over `quorum-set`** (`QuorumSetCell`), a
  genuine kernel glitch-free operator whose `evaluate()` only `propagate()`s once
  quorum is met. Glitch-freedom is now **positively asserted** via the new
  `observations-whole-waves` check: `22-GF-DIAMOND-01.yaml` is a single set
  diamond (`s`→two identity arms→2-of-2 `quorum-set`), `22-GF-NESTED-01.yaml` a
  nested double-diamond (three chained `quorum-set` levels). Both drop the old
  `final-view`-only shape and assert `observations-whole-waves` +
  `incremental-equals-batch` + `no-dead-letters`. Pass the multi-profile sweep.
- **Note**: the scalar wave-coalescing gap these entries used to file is real but
  narrower than first thought — it blocks only the *scalar* `combine-latest`, not
  the glitch-free set joins. It is re-filed precisely as the scalar-combine
  residual entry below (`24-OP-COMBINE-01` / `CTL-GF-01`).

### `24-OP-COMBINE-01` / `CTL-GF-01` — **RESOLVED (B2 / D-CONCORD, `kernel-gap`; kernel fix by D-COMBINE)**

- **Requirement**: `22-GF-01` (glitch-freedom) for the **scalar** combine shape
  specifically.
- **Gap (as filed)**: a genuinely wave-aligned / wave-**coalescing scalar**
  combine (version-buffered, emitting one delta per completed wave) did not exist
  in the kernel. The only scalar `combine-latest` binding was
  `ScalarSumCombineCell`, which emits a `CounterDelta` per input arm; the two arms
  of one source wave arrived as distinct waves and `GlitchFreeCell` replays
  per-invocation, so an observer folded the torn intermediate sum before any
  wrapper could coalesce it. `CTL-GF-01.yaml` asserted the true invariant
  (`observations-all-satisfy(v, even)`) against that binding and
  **FAILED-as-asserted** — the sentinel that kept the gap visible. The entry's
  Resolves clause: *"Restore a positive scalar glitch-free assertion on
  `24-OP-COMBINE-01` once a wave-coalescing scalar combine lands in the kernel."*
- **How resolved (kernel, D-COMBINE)**: it landed —
  `civictech.cell.data.op.CoalescingCombineCell`, a version-buffered scalar
  combine over a single unrestricted `CounterDelta` fan-in. It buffers each wave's
  per-arm contributions and emits their **net** as one delta once the wave's
  expected-edge set is complete (the `WaveFrontier` completeness condition folded
  internally), absorb-acking a completed wave whose net is zero. Note it does
  **not** carry the PN-12 `GlitchFree` structural marker — a conscious ratchet
  decision (marking `.data` operators would mint a `data -> consistency` package
  cycle), the same precedent `QuorumSetCell` set — so the corpus asserts its
  observed behavior, never a manifest flag.
- **How resolved (driver, D-CONCORD)**: bound inside the **existing**
  `combine-latest` catalog id and the **existing** `glitch-free` descriptor param
  (no new id, no new field, no schema edit): `KernelCatalog.build` binds
  `fn: sum` + `glitch-free: true` → `CoalescingCombineCell`, and plain `fn: sum` →
  the retained `ScalarSumCombineCell`. Two consequences the driver reads off the
  new `Built.waveAligned` flag: the redundant downstream `GlitchFreeCell` wrapper
  is *not* spawned for a cell that coalesces at the operator, and `inletName`
  collapses both neutral arms onto the coalescing cell's single `inlet` (each
  still its own link, hence its own expected edge). The plain form is retained
  deliberately, not by inertia: the wave-aligned form is a *fork-join* operator
  whose completeness set is its open inlinks, so it aligns arms of one source,
  whereas `24-OP-COMBINE-01`'s shape is two genuinely **independent** inlets.
- **What the corpus now asserts**: `24-OP-COMBINE-01` keeps the independent-inlet
  case (`final-view`, `covers: [21-PROP-01]`) with its header rewritten — the
  omission it used to document no longer exists — and names its companion. The
  positive assertion lives in the new **`24-OP-COMBINE-02`** (`covers: [22-GF-01]`):
  a genuine fork-join diamond (one `counter-source` → two `map fn: identity` arms →
  `combine-latest fn: sum, glitch-free: true` → `value-view`), asserting
  `observations-all-satisfy(v, even)` + `final-view: 100` + `no-dead-letters` on
  every run of the sweep. Verified non-vacuous: the observation log carries one
  even value per completed wave (event #1 is already the coalesced `2`, on all 20
  runs), not a single settled value.
- **What became of `CTL-GF-01`**: it is no longer a **gap** sentinel — nothing in
  the repo now describes the wave-coalescing scalar combine as missing. It is
  retained as an ordinary `kind: control` in `CTL-GOLDEN-01`'s register (the
  wrongness in the *expectation*, not in the lineage): the graph asks for the
  plain, documented non-wave-aligned form and then asserts wave-aligned semantics
  of it, which still fails as asserted. Its header and narrative were rewritten to
  say exactly that, and to point at `24-OP-COMBINE-02` for the aligned form. It
  therefore pins something real going forward: that the two bound forms are
  observably different, and that the harness catches the difference.

### `12-NEGOTIATE-01` — **RESOLVED (R1, `schema-gap`)**

- **Requirement**: `13-LINK-05` (rejection reasons include schema/contract
  mismatch), plus the "admission… contract compatibility (`portName`,
  `contractId`)" prose in `13-links.md`.
- **How resolved**: the gap was "the `NatureNegotiation` (CP-F3) typed-refusal
  mechanism is real (`TypedRefusalTest`/`NegotiatedAttachmentTest`) but no
  catalog cell declared a non-default nature to trigger it through ordinary
  wiring." R1 added the `nature-gate` catalog cell
  (`KernelAdapters.NatureGatedSinkCell`): its companion registers a fixed inlet
  nature (`MergeClass.IDEMPOTENT` on the `MERGE_IDEMPOTENCE` axis) via the
  *public* `ContractRegistry.register(...)` seam — the same projection
  `PortNatures` reads at construction — so a plain `CounterCell` source
  (axis-default `NON_IDEMPOTENT`) is a real CP-F3 mismatch and its `connect` is
  refused by the kernel's own reconciler, no schema field required. Scenario
  `12-NEGOTIATE-01.yaml` authored (`connect … expect: rejected` + `final-view` on
  the undisturbed existing view + `no-dead-letters`); passes the sweep.

### `23-SPSC-01` — **RESOLVED (R1, `schema-gap`) — reject half only; ADMIT half still deferred to G-47**

- **Requirement**: `12-EXCL-01` (fan-out MUST be rejected at link time when the
  contract's payload carries exclusive ownership — `Owned`/`Leased`).
- **How resolved (reject half)**: the gap was "the M5.6 exclusive-bit mechanism
  is real and kernel-tested (`OwnershipTest`) but every catalog cell emitted a
  plain non-exclusive `Propagate<Delta>`, so no scenario surface." R1 added the
  `exclusive-source`/`exclusive-sink` catalog cells
  (`KernelAdapters.ExclusiveSourceCell`/`ExclusiveSinkCell`): the source's outlet
  genuinely carries an `Owned<Any>` payload (its companion registers the
  `Owned`-parameter contract via the same public `ContractRegistry` seam, so the
  `exclusive` flag `FanOutlet` reads at construction is set), and a second
  `Consume` link is refused by the kernel's own `FanOutlet.linkTo` exclusivity
  check — not a driver-side fake. Scenario `23-SPSC-01.yaml` authored (`connect …
  expect: rejected` + first consumer `final-view` + `no-dead-letters`); passes
  the sweep.
- **Still deferred (ADMIT half) — G-47**: the observe/tap ADMIT half (a
  `role: observe`-differentiated tap admitted onto the same exclusive outlet)
  remains unbuilt: the driver still does not distinguish `role: observe` from a
  `Consume` link at connect time (see `13-TAP-01`). Only the reject half is
  covered.

### `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — **RESOLVED (R2, `schema-gap` + `driver-binding-gap`)**

- **Requirement ids**: `24-OP-WINDOW-01`, `24-OP-WINDOW-02`
  (`24-data-cells.md`, §Grouped aggregation "Windowing = key derivation").
- **Not a kernel gap after all**: the spec's own framing — "windowing = key
  derivation (M11.6)" — and the kernel's real `Windows.tumbling`/`sliding`
  event-time functions mean `window` is a group-by over a derived composite key,
  not a missing cell. What was missing was the schema descriptor and the driver
  binding.
- **How resolved (R2-B)**: added `WindowSpec` + `CellSpec.window` on
  `Scenario.kt`; `KernelCatalog`/`KernelAdapters` bind it honestly —
  `kind:tumbling`→a `GroupByCell` whose `keyFn` composes `Windows.tumbling`;
  `kind:sliding`→`WindowSlidingCell` (a real `FlatMapSetCell` over
  `Windows.sliding` linked into a real `GroupByCell`, packaged as one cell).
  `BatchOracle.windowFold` models the identical key derivation, and `CorpusRunner`
  lowers `window:` to a neutral `Value`. Windows never close: a late element is an
  ordinary add, retractions flow like any other group-by view (M11.6).
- **Filed scenarios**: `24-OP-WINDOW-01.yaml` (tumbling) and `24-OP-WINDOW-02.yaml`
  (sliding) assert golden + `incremental-equals-batch` + `no-dead-letters`; pass
  the multi-profile sweep against the oracle.

### `24-OP-PRESENCE-01` — **RESOLVED (R1, `oracle-gap`)** (harness-side)

- **Requirement**: no dedicated EARS id (see the coverage note in
  `24-OP-PRESENCE-01.yaml`); a genuine oracle/driver semantic mismatch, not a
  missing-id gap.
- **How resolved**: the gap was "`BatchOracle` folded `presence-count` to a
  scalar membership cardinality, but `PresenceCountCell` (a `PresenceLanes`
  fan-in sharing the `QuorumSetCell` substrate) emits `MapDelta<E, Int>` keyed by
  element — the count of distinct live source links asserting it, group-death at
  0 — so `incremental-equals-batch` had to be omitted." R1 added
  `BatchOracle.presenceCountFold`, folding `presence-count` as exactly that
  per-element live-source-link count, so the oracle now matches the kernel cell.
  `24-OP-PRESENCE-01.yaml` keeps its real fan-in shape (two set sources into one
  `presence-count`, read through a `map-view`) and now asserts
  `incremental-equals-batch` alongside `final-view` + `no-dead-letters`; passes
  the sweep. `BatchOracleTest` gained coverage of the new fold.

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

### `42-REPL-DEPART-01` — **RESOLVED (R1, `check-vocabulary-gap`)**

- **Requirement**: `42-REPL-06` (IF a replica departs orderly while peers keep
  accepting writes, THEN survivors converge and the departed replica's frozen
  stream is not counted as a divergence — spec 42 §G-45 departed-stream rule).
- **How resolved**: the gap was "`replicas-converge(logical)` read `readView`
  over every statically-declared `replica-of` cell, so a `despawn`ed replica
  either threw on `readView` or false-failed its frozen fold against advancing
  survivors — the exact G-45 false-positive." R1 gave
  `Checks.replicasConverge` a *live* scope: it `mapNotNull`s the declared
  replicas, dropping any whose `readView` no longer resolves (the departed-cell
  signal the neutral SPI already carries once `despawn` retires a cell — no new
  SPI verb), matching the kernel's own departed-stream rule
  (`cell.verify.ReplicaConvergence.liveRefs`, kept below the P1 boundary).
  Scenario `42-REPL-DEPART-01.yaml` authored (three replicas, orderly `despawn`
  of one, survivors keep writing incl. a remove of the departed replica's own
  contribution, `replicas-converge` + `views-converge` + `no-dead-letters`);
  passes the `dist` sweep.

### `42-INTEREST-01` — **RESOLVED (R1, `schema-gap`)**

- **Requirement**: `42-INT-01` (WHERE an instance declares a partial `Interest`,
  it holds exactly the interest-admitted subset — spec 42 §Interest-scoped
  instance sets).
- **How resolved**: the gap was "the kernel supports interest-scoped replicas
  fully (`LocationRegistry.setInterest` before `Replication.replicate`,
  `InterestScopedGossipTest`/`ShardedReplicationTest`) but the frozen W0 schema
  had no `interest:` descriptor, so the driver never received one." R1 froze the
  `interest:` field on `CellSpec` (`InterestSpec` in `Scenario.kt`) with a
  neutral closed sub-grammar (total/empty/slots/ranges); `CorpusRunner` lowers it
  to a neutral `Value`, and `KernelDriverDist.spawnReplica` parses it and stages
  it via `registry.setInterest(replica.ref, …)` **before** `replicate` (the
  gossip linker then consults it). Scenario `42-INTEREST-01.yaml` authored (two
  disjoint-slot replicas of one logical set — pairwise-disjoint slots form no
  gossip link — each `final-view` equal to its filtered slice + `no-dead-letters`);
  passes the `dist` sweep. Absent `interest:` stays byte-identical to `Interest.Total`.

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
- `24-REPLAY-01` — **RESOLVED (B2 / D-CONCORD, `kernel-gap`; kernel fix by
  D-REPLAY)**. *As filed (R3, empirically confirmed):* the PN-2 baseline path
  worked for the *generic* glitch-free join — `HostDurability.recoverFrom` stamps
  replayed frames as `MessageContext.baseline` and `WaveFrontier.offer()` releases
  a baseline delivery immediately, bypassing wave-completeness
  (`DurableGlitchFreeReplayTest`, 100 seeds) — **but `QuorumSetCell`/
  `PresenceLanes` never consulted `MessageContext.baseline`** (grep: zero
  references). They are a lane-counting fan-in, an architecturally different
  mechanism, and PN-2 patched only the former: one journaled arm + one volatile
  arm into a `quorum-set` (k=2) recovered to an **empty** view, the replayed
  frames stuck at lane-count 1 < 2 forever.
  - **How resolved (kernel, D-REPLAY)**: `QuorumSetCell.onInlet` now reads
    `CurrentContext.get()?.baseline`; when set (a PN-2 replay) the elements the
    replayed frame *added* to its lane are passed to `evaluate` as `recovered` and
    admitted regardless of `threshold(liveSources)` — the SET-fan-in analogue of
    `WaveFrontier.offer()`'s baseline branch. What a baseline *removes* stays on
    the live rule. Installed elements are **not** remembered: the next live delta
    touching one, or an `EdgeOpen`/`EdgeClose` shifting `n`, re-evaluates it under
    the ordinary threshold. `PresenceCountCell` needed **no** change and got none
    — it has no threshold to bypass, and `DurableQuorumReplayTest` proves its
    recovered counts identical with and without the stamp.
  - **How resolved (driver, D-CONCORD)**: `KernelDriverDur` gained a `quorum-set`
    arm in `build()` (volatile, never journaled — it is the *consumer* of a
    journaled arm's baseline; constructed under the recorded `CellRef` because the
    dur driver rebuilds every cell under its own ref, which `KernelCatalog.build`'s
    random ref would break) and a `linkEdge()` wiring path. **One modeling
    deviation, recorded here rather than glossed**: an edge incident to a
    lane-counting fan-in is installed through the kernel's own link admission
    (`ManagedHost.connect`), not the intake subscription every other durable link
    uses. A lane opens on the `EdgeOpen` a link handshake announces, and a
    `subscribe(Use.fixed(…))` is not a link — wired that way the quorum would open
    no lane, attribute nothing, and silently fold *nothing*. Nothing is bypassed:
    the intake funnel's two guarantees (WAL tee, `Effectful` frontier) are about
    the **destination**, and a `quorum-set` is neither journaled nor `Effectful`;
    the journaled endpoint is the arm **source**, whose accepted ops still ride the
    intake through `apply`. This mirrors `DurableQuorumReplayTest`, where the
    root→journaled-relay edge goes through the host queue while both fan-in arms
    are ordinary links.
  - **Filed scenario**: `concord/corpus/24-data-cells/24-REPLAY-01.yaml`
    (`profile: dur`, `covers: [24-REPLAY-01]`): one `journal-set-source` arm + one
    volatile `set-source` arm into a `quorum-set k: 2` on the reserved host `dur`,
    read through a `set-view`, with the `journal` controller as the crash handle;
    `final-view: [e1, e2, e3]` + `no-dead-letters`; passes every run of the sweep
    under `-Pconcord.profiles=core,dur` and the default set. The informative
    threshold is kept — no downgrade to `k: 1`, which would pass regardless of
    baseline handling. Verified non-vacuous: with the arm made volatile the same
    scenario reads `[e3]` on all 20 runs.
  - **The limitation the scenario encodes rather than papers over**: the baseline
    install is *arm* state and the quorum itself is volatile — it holds no record
    of its own pre-crash view, so the replayed arm's state is the only recovery
    information it has. Where the two arms had **diverged** before the crash (an
    element the durable arm asserted alone, below quorum), the recovered view
    **over-approximates**, installing that element too, until a live delta
    re-touches it. That is the decided behavior of this entry's own Resolves
    clause, not a defect: baseline-installed elements are not pinned. So the
    scenario does not assert exact pre-crash equality over a divergent state. It
    keeps the arms in **agreement** on the elements whose recovery it asserts
    (`e1`, `e2` — for those, recovered == pre-crash exactly), and carries one
    deliberately divergent element (`e4`) whose over-approximation converges back
    out under a live re-evaluation, which the final golden pins. Both halves are
    empirically confirmed: dropping the re-touch step makes the view read
    `[e1,e2,e3,e4]` on all 20 runs.
- `FileJournal` segmentation/rotation and **cross-host** recovery-frontier drift
  are single-in-process-host out of scope here (the driver runs the durable
  subgraph on one reserved host; cross-host is W4-A `dist` territory).

### How it is driven (modeling notes, not disputes)

No new script verbs or schema fields (the W0 seam holds). A durable subgraph
lives on the reserved host id `dur`; a `journal`-typed controller pseudo-cell is
the crash handle (`despawn` of it = crash + `recoverFrom` in one step); a
`snapshot` of a journaled cell lowers to `host.checkpoint`. Catalog additions
(driver-only, `KernelDriverDur.kt`): `journal-set-source`, `journal-set-view`,
`effect-sink`, `journal`. B2/D-CONCORD added one more driver-only type binding, `quorum-set`
(volatile). Durable links are wired **through the host intake** (a
`HostedCellProxy` subscribed to the source outlet), never a raw
`managementInlet.connect`: the kernel journals + enforces the `Effectful`
frontier only at `enqueueHostedInvocation`, and a raw port `linkTo` bypasses that
funnel entirely (a silent no-journal path — caught only because instrumentation
showed a zero-length WAL). **One documented exception** (B2/D-CONCORD, see the
`24-REPLAY-01` entry): an edge incident to a **lane-counting fan-in**
(`quorum-set`/`PresenceLanes`) is installed through the kernel's own link
admission instead, because such a cell opens its per-source lane on the
`EdgeOpen` a link handshake announces and a subscription announces none — and
because the funnel's two guarantees are about the destination, which on that edge
is neither journaled nor `Effectful`. Reserved-host caveat for the merge:
`host: dur` is a `dur`-profile convention; a `dist` scenario (W4-A) must not name
a host `dur`.

## Structural gap: 13 normative chapters carry no requirement ids at all (T02-D)

**Category: `spec-gap` (id-authoring backlog).** Not a per-scenario dispute
against a driver/kernel binding — filed because `provenance.md`'s "cannot be
checked honestly" rule applies at the corpus level too: the L4 concordance
matrix (`doc/spec/CONCORDANCE.md`) can only arbitrate a requirement that has an
`[NN-SLUG-nn]` id, and 13 of the 22 normative chapters under
`doc/spec/{00,10,20,30,40,50}-*` mint none. For those chapters the matrix is
not "clean" or "gap-free" — it simply has no row to be either, so silence there
is structural exclusion, not conformance. `:concord:concordance`'s new
denominator preamble (T02-D) makes this explicit at the top of
`CONCORDANCE.md`; this entry is the corresponding ledger record.

Zero-id chapters as of `742f7ca` (13):

- `00-foundations/01-vision.md`
- `00-foundations/02-design-principles.md`
- `00-foundations/03-glossary.md`
- `10-programming-model/11-cells.md`
- `10-programming-model/14-invocations.md`
- `20-dataflow-semantics/23-ownership.md`
- `30-execution-model/31-hosts.md`
- `30-execution-model/32-concurrency-colors.md`
- `30-execution-model/34-scheduling.md`
- `40-distribution/43-security.md`
- `50-development-process/51-construction.md`
- `50-development-process/52-verification.md`
- `50-development-process/53-evolution.md`

Several of these hold invariants `AGENTS.md` calls core (ownership, scheduling,
concurrency colors, construction, evolution) — the id gap is not concentrated
in peripheral chapters. `00-foundations` is vision/principles/glossary prose
that may never carry EARS-shaped ids (P1's "checkable through the driver SPI"
gate is a poor fit for a naming-decision or a design-principle statement); the
`10`/`20`/`30`/`40`/`50` entries are narrower and more plausibly mintable —
`23-ownership`, `34-scheduling`, and `32-concurrency-colors` in particular
already carry RFC-2119 `MUST`/`SHALL` language that reads as boundary-observable
per P1 but was never templated into an id.

**Resolves**: a follow-on id-authoring pass (W1-C-shaped) over the 13 chapters
above — per chapter, decide id-worthy statements per the EARS templates
(`concord/schema/provenance.md` §1), or record a P1/P4 boundary-observability
exclusion (per `provenance.md` §4) for statements that genuinely resist a
driver-SPI check (internals, scheduling order, protocol frames). Until that
pass lands, `CONCORDANCE.md`'s coverage percentage must always be read against
the denominator preamble, never against the row count alone.

## Structural gap: inspector subsystem semantics carry no requirement ids and no scenario coverage

**Category: `spec-gap` (subsystem-level exclusion, not a per-scenario
dispute).** Per this file's opening rule, filed rather than checked
dishonestly: the inspector (`:inspect`, delivered via the
`doc/spec/90-roadmap/97-inspector-plan/` run) ships five semantics that user
code relies on, specified only as prose in `97-inspector-plan/20-api-contract.md`,
none with an EARS `[NN-SLUG-nn]` id and none with a `concord/corpus/` scenario:

- topology snapshot/delta `seq` monotonicity (`20-api-contract.md:38`:
  `"seq": 412, // monotonic; SSE events carry seq > this`)
- `Edge.fused` meaning (`20-api-contract.md:72`: `"fused": false // true: the
  producing endpoint has no emission point at all` — a delegating pass-through
  with genuinely no message to observe)
- `flow.rates` cadence and the rate-0-omitted rule (`20-api-contract.md:176`:
  1 Hz batch; edges with no traffic that window are omitted, never sent as
  `rate: 0`)
- the cold predicate (`20-api-contract.md:127`: `"lifecycle": "hot" | "cold"
  // cold iff every member cell reports Node.lifecycle SUSPENDED`)
- `data`-mode search bounds (`20-api-contract.md:30`: bounded — 50 cells / 2s
  deadline / cold components skipped — and always returns a non-null `cost`,
  including on a zero-hit or blank-query result)

**Why this can't be checked honestly**: `civictech.concord.driver.kernel` is
the only package permitted to import `civictech.cell.*` (AGENTS.md's concord
boundary rule), and the twelve-verb driver SPI has no observation verbs over
the inspector's HTTP/SSE surface (topology snapshots, SSE event feeds, search).
There is only one binding of the inspector API contract today (`:inspect`
itself), so nothing could author an honest scenario against these semantics
without either inventing a second, parallel implementation to bind against or
reaching into `civictech.cell.*` from outside the kernel driver — both out of
scope for a corpus scenario. Per `CONCORDANCE.md`'s denominator-honesty
doctrine (see the preceding "Structural gap" entry and `CONCORDANCE.md`'s own
generated preamble), leaving these semantics unlisted would read as silently
clean coverage rather than as a known, deliberate exclusion — this entry is
the fix, not a weakened scenario with an omitted assertion.

**Revisit trigger**: a second binding of the inspector API contract appears
(e.g. an alternate implementation or a driver-level harness that exercises the
HTTP/SSE surface independent of `:inspect`), or the inspector subsystem
becomes product surface rather than an internal debugging tool — either would
make an honest scenario authorable against these five semantics.
