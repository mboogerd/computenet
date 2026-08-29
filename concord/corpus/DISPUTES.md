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

*(empty — every entry filed under this category has been resolved.)*

*(`21-REBASE-01` / `15-RESTART-01` — resolved in B3/D-C12, and **reclassified**:
filed as `kernel-gap` + `spec-gap`, it turned out on adjudication to be a
`driver-surface-gap` + `spec-stale`. The kernel implements the decided RESTART
re-baseline on its real supervision path; what was missing was the conformance
surface (a `restart` driver verb, a `rebaseline-source` catalog id, a scenario),
and what was wrong was the "unimplemented" claim in the spec prose. Both closed;
`21-REBASE-01.yaml` authored. See the "By scenario id" entry.)*

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

### `21-REBASE-01` / `15-RESTART-01` — **RESOLVED (D-C12, `driver-surface-gap` + `spec-stale`)**

*(Filed independently by W3-1 (as `21-REBASE-01`) and W3-3 (as `15-RESTART-01`);
same root cause, consolidated here. Originally filed `kernel-gap` + `spec-gap`;
adjudication reclassified it — see the verdict below.)*

- **Requirement**: `21-REBASE-01` (WHEN a source re-baselines — RESTART or
  re-baseline — the framework SHALL reconcile downstream consumers so their
  folds converge to a value consistent with the restored state, equal to a
  delta-only twin).
- **Verdict (D-C12, adjudicated against the live kernel)**: the **kernel-gap
  half of this entry was wrong**, and had been for some time. Its sentence "the
  landed RESTART behaviour is the bare local rollback the decision forbids
  (conflict C-12)" transcribed the spec prose rather than the code. The live
  supervision path implements the decided design's whole C-12 core, and does so
  on the *real* path (`ManagedHost`'s invocation-failure handler,
  `SupervisionPolicy.RESTART` branch): host-held generation bump, per-outlet
  `FanOutlet.mintFreshEpoch()` collecting the superseded source ids, checkpoint
  restore, then `ReBaselineEmitting.reBaseline(supersedes, supersede = true)`
  over the ordinary catch-up path, with `TagState.applyReBaseline` dropping
  un-reasserted tags from the superseded sources and fencing them as dead lanes.
  Nothing about that is bare, local, or silent. What was genuinely missing was
  the **conformance surface** — a driver verb, a catalog source, and a scenario
  — which is a driver-surface gap, and the `unimplemented` claim in the spec
  prose, which was stale. Both are what D-C12 closed. (The *residuals* of the
  decided design remain open and unaffected: the freshest-checkpoint tiers and
  the pull-merge direction under G-43/93 I-25, epoch reclamation under G-42.)
- **Gap as filed (driver SPI) — real, and closed**: the driver SPI had no
  `restart` verb, and its only state verb, `restore(hostId, cellId, blob)`, is a
  **raw** `Stateful.restore(state)` on the live cell: it swaps internal maps
  with no propagate, no `ReBaseline` emission, and no downstream announcement.
  That reading was and is correct — `restore` is the right primitive for
  despawn/migration scenarios (`15-SNAPSHOT-01`, `33-MIGRATE-01`,
  `DUR-SNAPTAIL-01`) and the wrong one for a restart, and the two must stay
  distinct rather than one growing a mode.
- **Gap as filed (catalog) — real, and closed**: no catalog *source* implemented
  `ReBaselineEmitting`; only the `union` consumer (`UnionSetCell`) reacted to an
  incoming `ReBaseline`, so the property had a receiving half and no emitting
  one. The reason `set-source` could not stand in was sharper than the entry
  said: `SetCell`'s tag source is deliberately **replay-stable**, so it cannot
  exhibit the epoch succession half of the requirement at all.
- **How resolved (D-C12)**:
  - a `restart(cellId)` verb on the driver SPI, stated in spec vocabulary
    (recover from the freshest checkpoint; succeed the emission epochs;
    re-announce over the catch-up path) and explicitly distinguished from
    `restore`; a `{type: restart, on: c}` step verb; one new catalog id,
    `rebaseline-source` → `ReBaselineSourceCell` (a `Stateful`,
    `ReBaselineEmitting` tagged source built from public kernel seams, the
    catalog twin of `RestartReBaselineTest`'s `TaggedProducerCell`). No kernel
    source was modified.
  - the kernel binding drives the **real** supervision path: it supervises the
    cell `RESTART` through the public host-management API and then delivers a
    failing invocation through a `HostedCellProxy`, so `ManagedHost`'s own
    RESTART branch is what runs. (The proxy matters: the driver's `route` path
    invokes a served handler inside the router's own scheduler task, where a
    throw is caught by the host's generic task guard and never attributed to
    the target cell — so it would never be supervised. A restart induced that
    way would have silently been no restart at all.)
  - `21-REBASE-01.yaml` authored: a `rebaseline-source → union → set-view`
    pipeline restarted mid-stream against an identical delta-only twin, with
    `{type: views-converge, views: [v, x]}` plus a `final-view` golden so the
    convergence cannot be satisfied by two empty folds. It passes every run of
    the sweep. Three separate probes (suppressing the `ReBaseline`
    announcement; making the `restart` verb inert; giving the source a
    replay-stable tag source) each fail it on 20 of 20 runs with exactly the
    C-12 symptom — the pre-restart adds surviving downstream.
  - **`no-dead-letters` is deliberately absent**, with a header comment saying
    why: a restart is a failure event and spec 30/31 rule 5 requires it to be
    reported under every supervision policy, so the one dead letter is the
    specification working. Asserting zero would assert the opposite of the
    spec; "exactly one" is not in the check vocabulary and growing it for this
    would be an unasked-for schema change.
- **Spec prose corrected** (the `spec-stale` half): the ⚠ CONFLICT C-12 blocks
  at `21-propagation.md`, `22-consistency.md`, `31-hosts.md` and
  `24-data-cells.md`, the "decided … unimplemented" lines, and the C-12 row of
  `91-gap-analysis.md` now record the adjudicated state; the ⚠ EARS-GAP
  self-doubt at `24-data-cells.md` (§Tag continuity — "this 'unimplemented'
  claim appears stale … a spec editor with fuller context should confirm or
  retract") is answered rather than left dangling.
- `15-SNAPSHOT-01` keeps its scope unchanged: it covers "restore state across a
  lifecycle event" and is still explicit that it does not exercise a restart's
  sourceId/tag-epoch semantics. That is now a division of labour between two
  scenarios rather than a gap.

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
  *Amended (computenet-61w).* As written, that "honestly exercised" argument held
  only for the **double-fire** direction: the unkeyed evaluator grouped the effect
  log and quantified over the keys the sink had *produced*, so an element that fired
  **zero** times was absent from the grouping and passed vacuously. The instrumented
  evidence above is a statement about the log's size, which is exactly the half that
  was checked. The evaluator now derives the expected key set from the scenario (the
  adds on the source linked into the sink) and unions it with the produced one, so
  `effect-count(esink, exactly: 1)` covers the effect-**loss** direction here too —
  `DUR-REPLAY-01` passes the sweep unchanged under the strengthened reading, which
  is itself the evidence that k1/k2/k3 each really do fire once rather than merely
  not-twice.
- `DUR-SNAPTAIL-01` (`24-DUR-02/03`) — checkpoint + journal-tail replay of a
  journaled source→view equals an uninterrupted twin (`views-converge`).

### The boundary (`kernel-gap` / design ceiling, G-59 / C-9) — RESOLVED for the journaled-source double-fire (KFX, commit `34892d9`)

*As filed*: the `Effectful` frontier keys on `MessageContext.timestamp.sourceId`, and the
producing `FanOutlet` minted a random per-instance `sourceId` (not ref-derived;
`SetCell.restore` restored its OR-set tag counter but *not* its outlet wave state).
Consequence, verified by construction: a **journaled source that feeds an effectful sink
would double-fire** on recovery — its replayed re-emission carried a fresh `sourceId` the
sink's restored frontier could not match. Exactly-once effect delivery was drivable only
when the effect subgraph's source was **volatile** (it dies on the crash and is
re-delivered nothing — exactly how `EffectfulRecoveryTest`'s unhosted source is discarded),
and the sink recovered from its *own* journaled frames. This was the recorded G-59 gap
("spontaneously-emitting sources … `Effectful` sinks without idempotency keys are
unhandled") and the C-9 boundary; it is why `DUR-REPLAY-01` originally kept the
data-recovery path (journaled/snapshot, `incremental-equals-batch`) and the effect-once
path (`effect-count`) as **two independent subgraphs** — a shape folded onto one by
`computenet-yh6.1.9`, two bullets below.

**How resolved**: commit `34892d9` (`computenet-yh6.1.2`, "A recovered outlet re-emits under
replay-stable wave identity") made a durable outlet's `sourceId` ref-derived
(`OutletWaveState.durable`, `UUID.nameUUIDFromBytes`) instead of `UUID.randomUUID()`,
installed at spawn for journaled cells only (`HostDurability.installDurableEpochs`), and
carried the outlet's whole epoch — `sourceId` **and** counter high-water — across a crash.
Stated precisely, because the two halves are *different* mechanisms and the two scenarios
below separate them: with **no** checkpoint nothing is written per emission and there is
nothing to read — the rebuilt outlet *re-derives* the same `sourceId` from its ref, and
replaying the frame tail deterministically walks the counter back through the pairs it
already emitted. **With** a checkpoint, compaction removes exactly those frames, so
`checkpoint()` writes one `RECORD_OUTLET_WAVE` record per journaled outlet beside the
`Stateful` snapshot — the epoch in force at checkpoint time, carried rather than re-derived
because RESTART's `mintFreshEpoch` or a drain/migration/promotion adoption may have rotated
the outlet off its derived epoch — and `restoreOutletWave` rewinds the outlet to it before
the surviving tail replays. Either way a recovered outlet's replayed re-emission now carries
exactly the identity its sink's restored processed-frontier already recorded, so the
double-fire this entry recorded no longer occurs — and, since a naive "restore the identity
but forget the counter" fix
would instead suppress live post-recovery traffic as already-acted, that opposite failure
(silent effect loss) is checked for too, not just the fixed one.

- **Filed scenarios**: `DUR-SRCID-01` (`covers: [24-DUR-04, 24-DUR-05]`) drives exactly the
  construction this entry recorded as broken — a journaled source feeding an effectful sink,
  crash, journal replay — asserting both directions: `effect-count(sink, exactly: 1)` over
  the pre-crash pair (the double-fire this entry named) and a keyed
  `effect-count(sink, key: k3, exactly: 1)` for the post-recovery element (ruling out silent
  effect loss, which an unkeyed check alone could not see when these were authored — see the
  computenet-61w amendment above; the keyed checks remain, now as the local statement of which
  elements the narrative singles out rather than as the only way to see loss at all).
  `DUR-SRCID-02` repeats the same
  construction across a checkpoint, where the epoch must survive via the checkpoint's
  `RECORD_OUTLET_WAVE` records (not `CheckpointRecord`, which carries only `state` and
  `frontier`) rather than by replaying frames compaction removed. Both discriminate
  genuinely: with `installDurableEpochs`/`restoreOutletWave` neutered they fail 20 of 20
  runs on a double-fired effect.
- **Kernel reproductions (CHA2 evidence lane, `computenet-umx.1.3`, `[CHA2-51]`)**: this
  entry's construction is now also pinned outside the corpus, in
  `kernel/src/test/kotlin/civictech/cell/repro/EffectReplayReproTest.kt` — the reproduction
  suite the milestone plan's CHA2 row asks for, which the concord scenario language cannot
  express because it carries no crash/replay fault verbs at scenario-authoring level.
  Three tests, no `@ExpectedFailure` on any of them, and all three observed passing at
  `bf18284`:
  `BS-1 a mid-drain crash replays the journal without re-firing or losing an effect`
  (`[CHA2-10]`, a volatile source and a journaled `Effectful` sink, plus two undelivered
  retransmits journaled at intake when the crash lands);
  `BS-4 a journaled source feeding an Effectful sink fires each logical invocation once across a crash`
  (`[CHA2-13]`, **this entry's own construction**, written unweakened and un-narrowed —
  CHA2 filed it expecting a standing expected failure and `34892d9` had already fixed it,
  so its PASS is the evidence `computenet-yh6.1.5`/`[KFX-22]` consumes); and
  `BS-5 a PN-2 replay-baseline at or behind the restored frontier is suppressed, not exempted`
  (`[CHA2-14]`, the `[24-DUR-07]`/`[24-DUR-08]` decision of `computenet-yh6.1.3.4`).
  They discriminate: with `installDurableEpochs` neutered BS-4 fails
  `expected:<[1, 2, 3, 4, 5, 6, 7]> but was:<[1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7]>`
  — this entry's double-fire, verbatim — and with `alreadyProcessed` forced false all three
  fail. Reasoning, mutation transcript and the recorded BS-5 answer:
  `doc/evidence-lane-findings.md` → "`computenet-umx.1.3` — C-9 reproductions".
  **Re-observed at this feature's own commit** (`6ebbcff`) by `computenet-yh6.1.5.1`
  (`[KFX-22]`), because a record made on another branch is not evidence about this tree: all
  three PASS unweakened — no `@ExpectedFailure` (the run's own standing count is 0), no
  re-seeding, no narrowed assertion, and the file byte-identical to the one CHA2 shipped —
  and BS-4's pass re-confirmed mutation-discriminating *there*, reproducing the double-fire
  signature above verbatim. Transcript: `doc/evidence-lane-findings.md` →
  "`computenet-yh6.1.5` — `[KFX-22]` acceptance run". The reconciliation that entry deferred
  to `computenet-yh6.1.5`/`[KFX-23]` is done, and is the two bullets below.
- **The fold was attempted and is NOT taken — the blocker is a harness limit, not a
  durability residual** (`computenet-yh6.1.5.2`, `[KFX-23]`). This entry's original
  "Resolves" bullet named folding `DUR-REPLAY-01`'s two independent subgraphs onto one as
  the natural consequence of the fix. That fold was **driven rather than reasoned about**:
  `DUR-REPLAY-01` was rewritten to one subgraph — a `journal-set-source` feeding **both** the
  volatile data view and the journaled `effect-sink`, so a single replayed emission stream
  must be re-applied idempotently at the view and suppressed as already-acted at the sink at
  the same time — and put through the `core,dur` sweep. Its durability half holds: with
  `incremental-equals-batch(dview)` removed the folded scenario passes **20 of 20** runs on
  `effect-count(esink, exactly: 1)` (the strengthened unkeyed reading, so both the
  double-fire and the effect-loss direction) plus `no-dead-letters` — a third independent
  confirmation, at corpus level and in this scenario's own shape, that the construction this
  entry recorded as broken is not. What fails is `incremental-equals-batch(dview)`, 20 of 20,
  with `source type 'journal-set-source' has no oracle fold`: the harness-side
  `BatchOracle.sourceFold` models `set-source` and not its durable binding, so a view fed by
  a journaled source has no batch oracle to compare against — which is also why
  `DUR-SNAPTAIL-01`, the corpus's other journaled-source→view scenario, asserts
  `views-converge` against a twin instead. Since `[24-DUR-01]`/`[24-DUR-02]`'s data-recovery
  half *in this scenario* is exactly that check, folding would trade a durability assertion
  for nothing. The probe was reverted and never shipped; `DUR-REPLAY-01` kept its
  two-subgraph shape — **for that stated reason, not the retired one**. Filed as
  `computenet-yh6.1.9`, and **taken there**: see the bullet below. Every count in this
  bullet is against the in-process effect log the `effect-sink` writes; no end-to-end
  external exactly-once claim is made or implied here, and the external actuator's
  idempotency remains 93 I-7's stated ceiling and CON1's territory (`[KFX-24]`).
- **The fold is now TAKEN; the omission was an oversight, not a deliberate modelling
  refusal** (`computenet-yh6.1.9`). The open question the bullet above left — whether a
  journaled source's *replayed* op history can diverge from the script's accepted-op
  multiset `BatchOracle` folds, which if it could would have made the omission
  deliberate and the fold case actively wrong — was answered **no**, and answered before
  the case was added. Five structural reasons, each checkable in the tree: both catalog
  ids lower to the same `SetCell` (`KernelDriverDur.build`); the journal tee is
  write-ahead and *inside the staging lock*
  (`ManagedHost.enqueueHostedInvocation` appends before `attentionScheduler.stage` in one
  `synchronized(dataLock)` block, the coalesce branch included), so journal order **is**
  acceptance order and nothing accepted is missing or reordered; both append sites are
  guarded by `!hostDurability.recovering`, so replay cannot grow the history either; a
  checkpoint substitutes a `Stateful` snapshot for exactly the prefix it compacts
  (`[24-DUR-02]`), so checkpoint + tail folds to the whole history; and the set fold is
  idempotent under `add` regardless, the only op besides `remove` the binding admits.
  The corpus already asserted the conclusion from two directions, which is why this is an
  observation rather than an argument: `DUR-SNAPTAIL-01` pins a journaled source→view
  against an **uninterrupted volatile twin driven with the identical op history**, and
  `DUR-ATOMIC-01`'s `final-view` golden is literally the fold of its script's adds across
  checkpoint, compaction, tail replay and live post-recovery traffic. The riskier case was
  in the fold table all along: whether a driver's read matches the whole-history fold
  across a crash is a property of the *scenario's* recovery construction, not the source's
  binding, and a **volatile** `set-source` on `host: dur` — which the oracle has always
  folded — loses its state outright (`[24-DUR-03]`), which is precisely why the pre-fold
  `DUR-REPLAY-01` had to snapshot and restore its view. So `BatchOracle.sourceFold` now
  folds `journal-set-source` as the `SetCell` it is (`journal-set-view` likewise a view
  pass-through), and `DUR-REPLAY-01` is folded onto **one** subgraph with
  `incremental-equals-batch(dview)` restored: one journaled source feeding both the
  volatile view and the journaled `effect-sink`, passing the `core,dur` sweep (20 runs per
  scenario, 69 scenarios, 0 failures) and the full `core,dist,dur` corpus (75 scenarios, 0
  failures) — no other scenario's verdict moved, and none could, since `DUR-REPLAY-01` is
  the only scenario in the corpus that pairs an `incremental-equals-batch` with a
  `journal-*` cell. The fold also made that check **stronger**: the view's own
  snapshot/restore was dropped, so `dview` holds its pre-crash prefix at the end only
  because `recoverFrom` replayed the frame tail into a rebuilt graph, and the check can no
  longer be satisfied by a second, independent route back to that state. Two consequences
  in files outside that item's claim were left recorded rather than done silently — the
  check layer's half (`Values.VIEW_TYPES`, `Values.canonicalForView`) and
  `DUR-ATOMIC-01`'s then-stale "WHY `final-view` AND NOT `incremental-equals-batch`"
  rationale. Both are now **closed, by `computenet-yh6.1.10`**: `Values.VIEW_TYPES` widened
  to include `journal-set-view`, so `view: '*'` no longer skips a journaled view;
  `Values.canonicalForView` now consults `Values.SET_VIEW_TYPES` (which also gained
  `journal-set-view`), so a journaled set view compares order-insensitively instead of by
  the driver's `Value.toString()` order; and `DUR-ATOMIC-01`'s rationale comment was
  rewritten to describe the closed state rather than the open one. The decision to widen
  the quantifier — and its measured zero blast radius against the corpus at the time —
  is recorded in `Values.VIEW_TYPES`' KDoc; `BatchOracle.DURABLE_SET_VIEW`'s KDoc points
  there rather than re-arguing it. As above, every count here is against the in-process effect
  log the `effect-sink` writes; nothing in this bullet claims end-to-end external
  exactly-once, which stays 93 I-7's ceiling and CON1's territory (`[KFX-24]`).
- **Rig-gated kernel sweeps (CHA2 evidence lane, `computenet-umx.1.6`, `[CHA2-51]`)**: the
  same construction is now also swept across *every journal prefix a crash could land on*,
  in `kernel/src/test/kotlin/civictech/cell/repro/EffectReplaySweepTest.kt`, driven by
  CHA1's DST rig (`civictech.testkit.dst`, `doc/dst-rig.md`) rather than by hand. Four
  reproductions, all seeds pinned and none re-rolled (`[CHA2-47]`):
  `BS-2 a restart from every journal prefix acts on each source counter position at most once`
  (`[CHA2-11]`, seed 101),
  `BS-3 rolling the processed frontier back re-delivers and re-fires the invocations past it`
  (`[CHA2-12]`, seed 202),
  `BS-6 a torn tail replays clean minus exactly the torn record and fires no effect for it`
  and
  `BS-6 a corrupted record aborts replay at its index and fires no effect at or beyond it`
  (`[CHA2-15]`, seeds 303 and 404), plus
  `BS-17 a reproduction writes a replayable artifact and a copy-pasteable replay command`
  (`[CHA1-50]`, `[CHA1-51]`).
  **BS-2 fails and stands as an `@ExpectedFailure`**, owner **`computenet-xxeo`**: at 6 of
  17 prefixes — every odd `k` in `[1, 3, 5, 7, 9, 11]` — a single `(sourceId, counter)` is
  acted on twice. The frame for counter `c` is journaled at record `2(c - 1)` and the
  `Effectful` frontier advance recording that it was acted on at `2(c - 1) + 1`, so an odd
  prefix is precisely a crash landing *between* the effect firing and the record saying it
  fired; recovery reports `recovery-complete@k` in all six, so this is a clean replay, not a
  damaged one. BS-3's frontier rollback reaches the same state through a different fault and
  shares the owner, which is what makes it more than an artificial injury. The residual is
  **not** the journaled-source double-fire this heading retired — that construction is
  `34892d9`-fixed and passes — but the write-ahead ordering *around* the frontier record;
  what it bounds is the scope of `[24-DUR-05]`, whose exactly-once effect delivery is exactly
  as durable as the frontier journal and no stronger. Whether that is the intended guarantee
  or a defect is `computenet-xxeo`'s decision to make; no kernel change was taken here
  (`[CHA2-50]`), and no scenario or schema change either (`[CHA2-51]`) — the concord scenario
  language carries no crash/replay fault verbs at authoring level, which is why these live
  outside the corpus. BS-6's two halves both hold, unannotated. Sweep transcript, mechanism,
  pinned seeds and how to regenerate a replay command:
  `doc/evidence-lane-findings.md` → "`computenet-umx.1.6` — rig-gated C-9 sweeps".
  ***Decided (`computenet-xxeo`, 2026-08-25): it is the intended guarantee — a design
  ceiling, not a defect.*** The window is **at-least-once**, now stated normatively as
  `[24-DUR-09]` (spec 24 §Effectful, "The write-ahead window is at-least-once"), and this
  sub-entry is therefore closed rather than left standing. The decision applies a criterion the
  corpus and spec had already decided, to a window none of them had named, rather than choosing
  a fresh one: `[24-DUR-07]` fixed the criterion for
  exactly this trade — a duplicate is loud and bounded, a suppression is a silent
  unrecoverable omission — and `[24-DUR-08]`'s eviction bound re-applies it in the same
  direction ("eviction only ever *shrinks* the suppression set"). Journaling the advance
  first (at-most-once) inverts that criterion: it converts this bounded duplicate into a
  durable "already acted on" record for an effect that never happened. A two-phase
  construction needs the external effect and its dedupe record to commit together, which is
  93 I-7's stated external-effect idempotency ceiling and remains CON1's territory, not the
  kernel seam's. **No kernel behaviour changed**: `ManagedHost`/`HostDurability` already
  implement the decided guarantee, and `[24-DUR-05]` was never violated — a position whose
  advance never became durable is not "at or behind" the restored frontier, so the rule's
  antecedent does not hold for it. What changed is BS-2, whose `@ExpectedFailure` is removed
  and whose property is rewritten from at-most-once to the decided one: **no position is
  lost across any prefix restart, and duplication is bounded to the single delivery the
  crash caught in flight** (`c9-at-least-once-bounded-refire`), plus a whole-log restart on the
  same pinned seed asserting that the invocation the crash caught in flight fires **exactly
  once** when its journal record does survive — the assertion an at-most-once ordering would
  redden, and the only live guard against that flip. Seed 101 and the full `0..R` range are
  unchanged. **Residual, stated rather than rounded up**: `[24-DUR-09]` enters
  `CONCORDANCE.md` as a `gap` row, for the same structural reason `[24-DUR-06]` does — the
  concord scenario language carries no crash/replay fault verbs at authoring level, so the
  kernel reproductions carry the assertions and this ledger carries the honesty. BS-3 is re-read and keeps passing unchanged, as
  `doc/evidence-lane-findings.md` predicted for this branch: its fault deletes advances the
  host had already made durable, which is a different mechanism from this ordering window,
  and only a resolution changing *replay-time* delivery would have reddened it. BS-6's
  corrupted half likewise keeps its recorded behaviour — its single re-delivery re-fires,
  which is now `[24-DUR-09]` rather than an owned defect.
- **Also not resolved by this entry**: `[24-DUR-04]`'s emission-identity plane is now
  asserted head-on by `DUR-SRCID-01`/`DUR-SRCID-02`; its OR-set tag plane and
  wave-aligned-consumer plane are recorded separately under "Not covered" below.
- **The G-59 / C-9 gap rows themselves are not closed by this.** What is retired is the
  slice this entry recorded — a spontaneously-emitting *journaled* source double-firing an
  `Effectful` sink. G-59's row (`91-gap-analysis.md`) also spans non-deterministic
  (wall-clock/random) cell logic, glitch-free partial-wave buffers (research-gated; named
  under "Not covered" below) and cross-host recovery-frontier drift (also below, out of
  scope for the single-host `dur` driver); `34892d9` touched none of those. C-9's "effects
  re-fire on replay" statement kept a live residual too — the second boundary immediately
  below, where a frame carrying no `MessageContext` had no frontier position at all and
  re-fired on `recoverFrom`; that residual has since been retired in its own right, by
  refusing such a frame rather than by deduping it (see there). Read this heading as
  retiring *this* boundary, not those rows.

### The second boundary (`kernel-gap` / design ceiling, KFX-16, `24-DUR-05`) — the frame with no frontier position — RETIRED by an admission rule (`[24-DUR-06]`, `computenet-yh6.1.3.5`)

**How retired, in one sentence**: the hole was closed by *refusing* the frame
rather than by finding a way to dedup it — a contextless `PORT_API` invocation is
undeliverable at an `Effectful` inlet (`[24-DUR-06]`, spec 24 §Effectful), so past
that refusal every frame the sink acts on has a `(sourceId, counter)` position and
`[24-DUR-05]`'s antecedent is evaluable for all of them. The entry as filed is kept
below — verbatim apart from its closing `**Resolves**` bullet, which is rewritten in
place — because two of its claims are load-bearing elsewhere (the corrected
stamping argument, and the extent of the limit) and because the *reason* the guard
could not close it from inside is exactly why the closure had to change shape.

What the kernel now does (`ManagedHost.deliver`, `PORT_API` branch): a frame at an
`Effectful` inlet with `invocation.context == null` is not delivered; its exclusive
payloads are discharged explicitly (`Owned.take` / `Leased.release`, the same
no-silent-drop rule KFX-20 applied to the suppression branch), the refusal is
counted (`SupervisionAccounting.effectfulContextlessRefusals`) and reported as a
dead letter. The frontier advance beside the delivery is now unconditional for
`Effectful` cells (`checkNotNull(timestamp)`, deliberately an assertion rather than
a condition, so a future path that reintroduces the contextless case fails loudly
instead of silently reinstating this hole). Direct drivers stamp their own actor
lane through `civictech.cell.host.ActorIngress`. Asserted by kernel
`EffectfulInletGuardTest` — *"a contextless external drive is refused and the
stamped path fires exactly once across replay"* (the inversion of this entry's
original assertion, which ended `world shouldBe listOf(1, 1)`), plus the `Owned`,
`Leased` and non-`Effectful`-scope arms.

**Three residuals, stated because retiring an entry is not a licence to round up**:

1. **No corpus scenario covers the externally-driven case** (`coverage-gap`, still
   open — re-diagnosed 2026-08-15 on `computenet-109f`). `[24-DUR-06]` enters
   `CONCORDANCE.md` as a `gap` row, and the `dur` profile asserts nothing about
   it. As filed, this residual named the retransmit / duplicate-delivery verb
   gated in `computenet-yh6.1.3.3` as the missing capability. **That verb has
   since landed** (documented in `concord/schema/scenario.md`, bound by
   `computenet-yh6.1.8`) **and it does not unblock this residual.** The reason is
   structural, not incidental: a `retransmit` step *states* a wave position
   (`{type: retransmit, on: c, source: s, counter: N, …}`), while the frame
   `[24-DUR-06]` is about is defined by having **none** — a verb that names a
   position cannot drive the path whose defining property is the absence of one.
   `scenario.md`'s own `retransmit` subsection says as much ("That one this verb
   does not resolve: a retransmit states a position"); what the retransmit verb
   did retire is the *third* boundary below (`[24-DUR-02]`'s checkpoint
   frontier), and the two were being conflated.

   What this residual actually needs is **two further gated `concord/schema`
   additions**, filed as `computenet-em9i`:

   - a **contextless-drive step verb** — drive a named cell's `PORT_API` inlet
     with no message context, the shape `HostedCellProxy` produces off the data
     path. `apply` cannot: it drives an op through a cell's own outlet and the
     driver mints the next wave position. `retransmit` cannot: it states a
     position, and its kernel binding admits an `effect-sink` target only.
     Reaching a contextless delivery by exploiting the fact that the *kernel*
     binding's `apply` happens to enter a source's inlet unstamped would be an
     accident of that binding rather than neutral semantics — another conforming
     driver may stamp — i.e. exactly the invented binding this residual and
     `computenet-yh6.1.3.5` both excluded;
   - a **refusal-accounting observable**. `[24-DUR-06]` requires the refusal to
     be *accounted*, not merely non-acting, and the check vocabulary's only
     dead-letter surface is `no-dead-letters` (zero across all hosts) — the
     inverse of what must be asserted here, since the refusal *is* reported as a
     dead letter. `{type: effect-count, sink: s, exactly: 0}` states only the
     other half (the effect did not fire) and on its own is satisfied by a silent
     drop, which is the very failure the rule forbids.

   The stamped-lane half the follow-up also wants (the same drive through an
   `ActorIngress` lane firing exactly once across crash/replay, and once more
   when re-delivered live) is blocked on the same absence: no verb creates an
   actor lane. Until those land, the kernel `EffectfulInletGuardTest` carries the
   assertions and this ledger carries the honesty — deliberately no scenario,
   per the same rule that governed the entry as filed. KFX-17's prohibition is
   discharged either way: what it forbade was a scenario asserting the *weaker*
   rule, and there is no longer a weaker rule to assert.
2. **The per-actor durable identity is not implemented here.** The kernel enforces
   the refusal and supplies the stamping seam; *minting and persisting* an actor
   identity that means the same thing across a restart and across peers is the
   connector ingress's job (CON1). Until then a caller that passes a fresh id per
   process is admitted and correct across replay, but opens one frontier lane per
   session — bounded by actors only when the actor id is genuinely stable. That is
   the caller's choice now, not a silent kernel behaviour, which is the difference
   that made this retirable.
3. **Enforcement is at delivery, not at link admission.** The decision named link
   time as the preferred point "where the shape is knowable"; in today's kernel
   there is nothing there to check — every *linked* producer stamps a context by
   construction (`FanOutlet` mints one even for a spontaneous emission), and the
   only contextless producers are direct proxy drives, which admit no link. A
   link-admission check would therefore be unreachable code, so the delivery-time
   guard carries the rule alone. If a future producer can emit into a link without
   a context, this is the paragraph that has to change.

---

*The entry as originally filed follows, unedited — except its closing
`**Resolves**` bullet, which is rewritten in place as `**Resolved by**` plus a
`**Follow-up**` bullet, since a forward-looking pointer that has been resolved
would otherwise still read as outstanding work.*

`[24-DUR-05]` is written unconditionally ("IF an invocation at an `Effectful`
inlet is at or behind that inlet's processed-frontier … THEN it SHALL be
suppressed"), but the frontier is keyed on `MessageContext.timestamp` — a
`(sourceId, counter)` wave position. **A frame that carries no `MessageContext`
has no position on that frontier at all**, so the antecedent can never be
evaluated for it. `Invocation.context` is "null on management paths and
spontaneous calls", and `HostedCellProxy` stamps `CurrentContext.get()`, so this
is exactly the *externally-driven root* case: an `Effectful` cell driven directly
by an outside caller (the shape a connector ingress produces). Consequence,
asserted by construction in kernel `EffectfulInletGuardTest`: such a frame is
journaled, is never deduped, never advances the frontier, and **its effect
re-fires on `recoverFrom` replay**.

The decision recorded for KFX (feature `computenet-yh6.1.3`) is to keep that
behaviour as an explicit bounded limit rather than close it in the guard, for the
same reason the G-59/C-9 boundary above stood before its resolution: a *bounded*
closure needs a real ingress *identity* the kernel does not have, and minting one
is wider than this guard. That earlier boundary was closed by restoring an
*existing* identity a crash-surviving outlet already carried; this one has no
such identity to restore — an externally-driven frame has none to begin with.
Read the next two paragraphs together — the point is not that the hole cannot be
closed, but that every way of closing it from inside this guard costs more than
the limit does.

*Stamping* a synthetic wave position at ingress does close the replay double-fire,
and — stated precisely, because the imprecise version of this is tempting — it
does **not** need the minted id to be crash-stable to do so: the stamp rides the
journaled frame, and the frontier advance is itself journaled
(`FrontierRecord`/`CheckpointRecord`), so a replayed frame matches the restored
frontier even for an id minted fresh per call. Asserted, not assumed: kernel
`EffectfulInletGuardTest`, *"the same external drive carrying a per-call minted
context IS deduped across replay"* — which also fixes the extent of this entry's
limit at *"a frame with no frontier position"*, not *"a frame from outside"*.
What rules stamping out here is its cost, not its efficacy. (i) It
fabricates wave identity on **every** externally-driven path — wire ingress
included — so every external call becomes a wave to every downstream consumer,
and PN-2's replay-baseline stamping, which today deliberately leaves root frames
untouched to keep byte-for-byte behaviour for non-opting graphs
(`HostDurability.baselined`), would begin applying to them. (ii) A *per-call*
minted id makes the frontier grow without bound: one `(sourceId → counter)` entry
per external call, each with its own journaled `FrontierRecord`, none of them
ever collapsible. A bounded stamp therefore needs a per-host (or per-connector)
source id with a monotonic counter that survives a crash — real ingress identity,
which is exactly the **Resolves** below, and exactly the epic's risk-5 boundary.

*Refusing to journal* an `Effectful`-bound frame with no timestamp would deny
legitimate live traffic that works correctly today, trading a replay-time
double-fire for a delivery-time denial.

So this sits under the same "external-idempotency ceiling" 93 I-7 already states:
un-suppressed replay is safe exactly for the replay-stable idempotent vocabulary,
and an external effect driven by an unidentified external frame is outside it.

Deliberately **no corpus scenario** accompanies this entry: a scenario asserting
"the effect re-fires" would state a weaker rule than `[24-DUR-05]` as though it
were the decided one. The kernel test carries the assertion; this ledger carries
the honesty.

- **Resolved by** `computenet-yh6.1.3.5`, but *not* in the shape this bullet
  predicted. As filed it asked for a crash-stable ingress identity minted inside
  the kernel — a durable per-host (or per-connector) source id plus a journaled
  monotonic counter — after which every frame reaching an `Effectful` inlet would
  have a frontier position. The human decision of 2026-08-10 reframed it: the
  identity belongs to the **external actor**, not the host, and is minted by the
  connector ingress (CON1); the kernel's part is the **admission rule** above,
  which reaches "every frame reaching an `Effectful` inlet has a frontier
  position" by refusing the ones that do not, and is therefore not waiting on
  CON1. See the retirement note at the head of this entry.
- **Follow-up**: the corpus scenario for the externally-driven `[24-DUR-05]` /
  `[24-DUR-06]` case — filed as `computenet-109f`, which established (2026-08-15)
  that the `computenet-yh6.1.3.3` retransmit verb it was gated on cannot express
  the case, and re-filed the real capability as `computenet-em9i` (a
  contextless-drive step verb plus a refusal-accounting check). See residual 1
  above for the argument.

### The third boundary (`coverage-gap`, `[24-DUR-02]`, KFX BS-12) — the checkpoint's *frontier* half asserts nothing — RETIRED by `DUR-CKPT-FRONTIER-01` (`computenet-yh6.1.8`)

**How retired, in one sentence**: the duplicate-delivery surface this entry asked
for is the `retransmit` step verb (`concord/schema/scenario.md`, documented by
`computenet-yh6.1.3.3` and bound by `computenet-yh6.1.8`), and the construction
that discriminates the checkpoint's frontier copy is a checkpoint with **no
journal tail after it**, so that copy is the only thing left that can suppress
the duplicate. The entry as filed is kept below — verbatim apart from its closing
`**Resolves**` bullet, which is rewritten in place — because its negative
result — that the frontier restore was unobservable, and *why* — is the reason
the discriminating construction has the shape it does.

Measured, not asserted: with `restoreCheckpoint` patched to skip
`record.frontier`, `DUR-CKPT-FRONTIER-01` fails 20 of 20 runs
(`effect-count(sink, key=k2): expected 1 but observed 2`) while every other
scenario in the `core,dur` profiles stays green and the kernel
`civictech.cell.durability.*` suite stays green at 37 tests (34 before
`origin/main`'s `02ac610` added three `EffectfulInletGuardTest` cases; the count
here is re-measured on the merged tree) — reproduced independently by the task
reviewer and again by the feature reviewer, not only by the implementer. That is exactly
the perturbation `DUR-ATOMIC-01`'s own sweep recorded as changing nothing
observable, so the corpus has gained discriminating power it did not have. The
frontier half of `[24-DUR-02]` is now asserted head-on.

`CheckpointRecord` carries `state` **and** `frontier` together, and `[24-DUR-02]`'s
atomicity claim covers both halves. `DUR-ATOMIC-01` (KFX, feature
`computenet-yh6.1.4`) discriminates the pair that matter observably — the
`Stateful` snapshot half and the frame tail — but its own perturbation sweep
recorded an honest negative for the third: **deleting the checkpoint's frontier
restore entirely changes nothing anyone can see.** Patch `restoreCheckpoint` to
skip `record.frontier` (keeping the `RECORD_FRONTIER` tail records) and the
whole `dur` profile stays green 20 of 20, *and* — re-verified during review —
so does the entire kernel `civictech.cell.durability.*` suite, `EffectfulRecoveryTest`
*"checkpoint compaction preserves the processed-frontier across recovery"*
included, despite its name.

It is not a weak test, it is the shape of the mechanism. Compaction removes
exactly the frames whose frontier advance the checkpoint absorbed, so nothing the
checkpoint frontier would suppress is ever replayed; every frame that *is*
replayed arrives with its own `RECORD_FRONTIER` record in the same tail, and
`recoverFrom` stages frames while restoring frontier records synchronously, so
the tail's own frontier is fully in place before any replayed frame is delivered.
The checkpoint's frontier copy is therefore dead weight on every recovery path
this repo can currently build.

The construction that *would* discriminate it needs an upstream that survives the
crash and **re-delivers** a frame whose `(sourceId, counter)` is at or behind the
checkpoint frontier — a duplicate live delivery, not a replay. Neither the corpus
(no re-delivery/duplicate-frame verb) nor the kernel durability fixtures build
that today. Recorded here rather than claimed: `DUR-ATOMIC-01` covers
`[24-DUR-02]` on the two halves its perturbations actually move, and this ledger
carries the third.

- **Resolved by** `computenet-yh6.1.8`, in the shape this bullet predicted: the
  duplicate-delivery surface is the `retransmit` corpus verb, and
  `DUR-CKPT-FRONTIER-01` (`concord/corpus/15-durability/`) is the scenario that
  re-sends an already-delivered frame to an `Effectful` inlet after recovery at a
  position the checkpoint frontier covers and the replayed tail does not.
  Dropping `record.frontier` in `restoreCheckpoint` is now a failing
  perturbation. See the retirement note at the head of this entry.
  That surface is already filed as `computenet-yh6.1.3.3` (a gated
  `concord/schema` change proposing a retransmit verb that re-sends a prior
  invocation, or replays an explicit `(sourceId, counter)` live rather than via
  journal replay), raised there for the live half of `[24-DUR-05]`. This entry is
  its second consumer: the same verb discharges both, so whoever lands it should
  author the `[24-DUR-02]` frontier perturbation above alongside the
  `[24-DUR-05]` one.

### The fourth boundary (`schema-gap`, `[24-DUR-07]`/`[24-DUR-08]`, `computenet-yh6.1.3.4.1`) — no driver path stamps `MessageContext.baseline` on an I-24 pull — CLOSED by `retransmit`'s optional baseline anchor (`computenet-yh6.1.12`), scenarios `DUR-BASELINE-01`/`DUR-BASELINE-02`

`computenet-yh6.1.3.4` decided the normative rule for an `Effectful` inlet
receiving a frame stamped `MessageContext.baseline` (spec 24 §Effectful,
`kernel/src/test/kotlin/civictech/cell/durability/EffectfulBaselineGuardTest.kt`):
one rule per baseline *kind*, not one rule for "baseline" generically —

1. An **I-24 pull-baseline** (a `StateRequest` catch-up reply, stamped by
   `FanOutlet.baselineTo`) fires the effect and **never** advances the
   processed-frontier from its timestamp (`[24-DUR-07]`) — a baseline's
   timestamp is anchored at the link-install event, not a wave position.
2. Because (1) opens a hole no wave-frontier check closes, the sink gets its
   **own** journaled discharge record, keyed on the baseline's exact position,
   separate from the wave frontier — an exact match, not a high-water — so a
   crash-replay or a live re-delivery of the same catch-up position is
   suppressed without re-firing the effect (`[24-DUR-08]`).
3. A **PN-2 replay-baseline** (`HostDurability.recoverFrom`'s replayed
   re-emission stamp) is a *different* baseline and keeps `[24-DUR-05]`
   exactly — suppressed at-or-behind the restored frontier, journal-tail
   fires — with no new discharge record involved. This is the mechanism
   `DUR-REPLAY-01` and `DUR-SRCID-01`/`-02` already exercise.

This task (`computenet-yh6.1.3.4.1`) was scoped to author corpus scenarios for
`[24-DUR-07]`/`[24-DUR-08]`. It could not: **no path reachable from any concord
driver, in any profile, ever stamps a delivered frame's `MessageContext.baseline`
from the I-24 pull-baseline mechanism.** Both places in the kernel that set
`.baseline` were checked directly:

- `FanOutlet.baselineTo`, invoked only from `pullServe`'s `StateRequest`
  handler (`kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt`,
  `SetCell.kt:192`, `OrMapCell.kt:436`) — reached only by an actual
  `Protocols.StateRequest` protocol message arriving at the producing outlet.
  **No concord driver ever issues one.** `grep -rn "StateRequest\|pullServe"
  concord/src/main/kotlin` finds it named only in a `KernelDriverDist` comment
  (line 135, describing `catchUpOnLinked`, not `pullServe`) — never invoked.
- Ordinary push catch-up on link install (`FanOutlet.catchUpOnLinked`, what
  every `connect` step in every profile actually drives) is, by its own KDoc,
  **not** stamped as a baseline today: "the plan calls for this push catch-up
  to ride `FanOutlet.baselineTo` … That change is deferred" (`CatchUp.kt:24-26`).
  So even a scenario that connects a fresh `Effectful` consumer to a source
  already holding state — the ordinary "late join" shape — gets a plain
  (`baseline == null`) catch-up delivery, not an I-24 pull-baseline one.
- The `dur` profile's `effect-sink` wiring (`KernelDriverDur.wire`) makes this
  doubly unreachable for the one catalog type that is actually `Effectful`:
  a durable link is installed by `outlet.subscribe(Use.fixed(sinkInlet, …))`
  (`KernelDriverDur.kt:359-364`), a raw subscription that bypasses link
  admission entirely — no `EdgeOpen`, no protocol handshake, nothing a
  `StateRequest` could ride even if a driver sent one. (The one durable edge
  that *does* go through real link admission, `linkEdge` for `quorum-set`, is
  neither journaled nor `Effectful` — see the `24-REPLAY-01` entry below.)
- `retransmit` — the verb `computenet-yh6.1.3.3`/`computenet-yh6.1.8` built
  for exactly this family of gap (live duplicate delivery to an `effect-sink`)
  — cannot express it either. Its kernel binding hardcodes the context it
  stamps: `CurrentContext.with(MessageContext(position, outlet.ref)) { … }`
  (`KernelDriverDur.kt:468`) — `MessageContext`'s `baseline` parameter is left
  at its `null` default, and neither `RetransmitStep` (`Step.kt:186-194`,
  fields `on/inlet/source/counter/op/value`, no `baseline`) nor the `Driver`
  SPI's `retransmit(...)` signature carries anywhere to put one.

**PN-2 replay-baseline is reachable (crash + `recoverFrom`, already exercised
elsewhere) but does not discriminate this pair.** A scenario built only on
crash/replay would deliver rule-3 baselines, which already pass under
`[24-DUR-05]`'s pre-existing ordinary-frontier suppression — the ledger's own
"third boundary" and `DUR-REPLAY-01`/`DUR-SRCID-*` cover that mechanism
already. It would prove nothing about the *new* mechanism `[24-DUR-07]`/
`[24-DUR-08]` add: acting-without-advancing-the-frontier (rule 1) and the
baseline's own separate discharge record (rule 2), both specific to the I-24
pull kind. A scenario that used the reachable (replay) baseline to stand in
for the unreachable (pull) one would pass unmodified against a guard that
never implements rules 1/2 at all — exactly the "weakened into a passing
scenario" AGENTS.md forbids, not a genuine `[24-DUR-07]`/`[24-DUR-08]` check.

**What would actually unblock it**, narrowest first:

1. **Grow `retransmit`** with an optional inline baseline anchor — a
   `(sourceId, counter)` timestamp (as today) plus a `TagFrontier` anchor,
   mirroring `EffectfulBaselineGuardTest`'s own `baseline(source, counter,
   anchor)` helper — so a scenario can state "this delivery carries
   `MessageContext.baseline = <anchor>`" the same explicit way it already
   states a duplicate's position. This is the same verb `computenet-yh6.1.3.3`
   grew for the live-duplicate case and `computenet-yh6.1.8` bound, and
   `DUR-CKPT-FRONTIER-01` was already that verb's "second consumer" — this
   would be a third, gated the same way.
2. **Wire a real pull.** Route the `dur` profile's `effect-sink` connection
   through actual link admission plus `pullServe`/`StateRequest`, and add a
   step that triggers a catch-up pull. Materially larger: it reopens the
   `effect-sink` "raw subscribe, not a link" modeling decision the `quorum-set`
   entry above already flagged as deliberate, for every durable link, not just
   this one.

(1) is the narrower, verb-consistent option and does not touch the
"deliberately deferred" push-catch-up-as-baseline switch `CatchUp.kt` names —
that switch is a kernel-side decision this task's scope excludes, not a
concord-driver one.

**CLOSED (`computenet-yh6.1.12`), by route (1) exactly as filed.** Everything
above stands as the record of *why* the gap existed — it was real, and it was
not authorable around. What changed is the verb surface, not the finding: the
gated `concord/schema/scenario.md` change grew `retransmit` with an **optional**
`baseline:` anchor (a merge-tag frontier stated as scenario-local cell ids to tag
counters, resolved by the driver the same way `source:` is), threaded through
`RetransmitStep`, the `Driver` SPI's `retransmit(...)`, the kernel bindings and
the `CorpusRunner` dispatch arm. Route (2) — rewiring the `dur` profile's
`effect-sink` through real link admission plus `pullServe`/`StateRequest` — was
not taken, and the `CatchUp.kt` "deferred" push-catch-up-as-baseline switch is
untouched; both remain exactly as described above.

- **Optionality, demonstrated rather than asserted**: an omitted anchor produces
  the `MessageContext(position, outlet.ref)` this binding stamped before the
  parameter existed. `DUR-LIVE-01` and `DUR-CKPT-FRONTIER-01` pass **unmodified**
  across the change, and `RetransmitBindingTest`'s "an omitted baseline anchor
  advances the frontier, a stated one does not" pins the contrast head-on.
- **Resolves**: `[24-DUR-07]` by `DUR-BASELINE-01` — a baseline delivered ahead
  of any live traffic fires, and the two live frames that arrive *below* its
  counter fire too. Collapsing `ManagedHost`'s baseline branch so a baseline
  advances the processed-frontier fails it 20 of 20
  ("effect-count(sink, key=k1): expected 1 but observed 0; … key=k2 … observed
  0") with every other corpus scenario green. And `[24-DUR-08]` by
  `DUR-BASELINE-02` — one catch-up position delivered four times (fire, live
  re-delivery, crash+replay, post-recovery re-delivery) acts exactly once; the
  live half fails 20 of 20 with `alreadyDischargedBaseline` forced false
  ("expected 1 but observed 5") and the replay half 20 of 20 with
  `restoreBaselineDischarge` neutered ("expected 1 but observed 2"), each with
  the rest of the corpus green.
- **What is still NOT covered, and is not claimed**: the anchor's *contents*.
  `MessageContext.baseline` also carries dedup/incremental-pull currency (a
  `StateRequest.since` frontier), and nothing here observes a tag counter — the
  two scenarios assert the baseline's *kind* and its *position*, which is what
  `[24-DUR-07]`/`[24-DUR-08]` are written about. A requirement about merge-tag
  currency across a pull would still need route (2)'s real `StateRequest` path,
  and would be a new boundary entry, not this one.
- **Coverage today**: `[24-DUR-07]`/`[24-DUR-08]` are corpus-covered by
  `DUR-BASELINE-01`/`DUR-BASELINE-02` in `doc/spec/CONCORDANCE.md`, alongside
  the kernel-level `EffectfulBaselineGuardTest.kt` and the CHA2 evidence-lane
  reproduction `BS-5` (`EffectReplayReproTest`, cited above under the
  journaled-source double-fire boundary, `[CHA2-14]`).

### Not covered (deferred, honestly out of reach at W4-B)

- `24-DUR-04` (replay-stable identity, no resurrected removals) — **NARROWED
  (KFX).** The requirement spans three planes; one is now asserted head-on and
  two remain uncovered, for different reasons.
  - The **emission-identity plane is now asserted head-on**: `DUR-SRCID-01` and
    `DUR-SRCID-02` pin that a recovered source's re-emission carries the identity
    (and counter high-water) the network already observed, so an `Effectful`
    sink's restored processed-frontier matches it — once by replaying the frame
    tail, once across a checkpoint that compacted those frames away. These two
    genuinely discriminate: with `HostDurability.installDurableEpochs` and
    `restoreOutletWave` both neutered they fail 20 of 20 runs on a double-fired
    effect.
  - The **OR-set tag plane remains covered only indirectly**, with its original
    rationale unchanged: `DUR-SNAPTAIL-01`'s recovered `SetCell` re-mints
    ref-derived tags, so its recovered membership equals the twin's with no
    double-count, but the directed add/remove/replay control belongs in a kernel
    unit test — the tag plane itself is not boundary-observable per P1.
  - The **wave-aligned-consumer plane is NOT boundary-discriminable, and is
    therefore recorded here rather than claimed by a scenario.** BS-40 asked for
    head-on `24-DUR-04` coverage via a glitch-free consumer downstream of a
    recovered source, on the reasoning that such a consumer keys per-source wave
    completeness on emission identity and so must be sensitive to it. On the
    replay path it is not. `WaveFrontier.offer` takes its `ctx.baseline != null`
    branch and releases the invocation *before* any edge/lane lookup, so a
    replayed cone is released without the frontier ever consulting the source id;
    PN-2's baseline stamp, not replay-stable identity, is what carries the
    recovered cone. Two independent results pin this: kernel
    `DurableGlitchFreeReplayTest` control (b) ("PN-1 derivation reverted but
    baseline on — recovery still green") holds over 100 seeds; and `DUR-GF-01`,
    the corpus scenario authored for BS-40, still passes 20 of 20 with the
    kernel's whole replay-stable identity mechanism disabled. `DUR-GF-01`
    therefore carries `covers: [24-DUR-02]` only. It remains a real composition
    gate — a wave-aligned consumer downstream of a recovered source must not
    regress the recovery outcome, and making its journaled arm volatile fails it
    20 of 20 — but it is not a `24-DUR-04` check, and claiming the id would have
    made the concordance assert coverage no run could falsify.
  - **Resolves**: nothing at the corpus boundary — this is a property of the
    mechanism, not a gap in the scenario vocabulary. A wave-aligned consumer
    becomes identity-sensitive across recovery only if its own frontier survives
    the crash (a journaled `GlitchFreeCell`, so the restored frontier has a lane
    to disagree with). That is a kernel/durability design question adjacent to
    G-59's research-gated partial-wave-buffer slice, not a scenario to author.
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

- topology snapshot/delta `seq` monotonicity (`20-api-contract.md` § DTOs,
  the `TopologySnapshot` DTO's `seq` field:
  `"seq": 412, // monotonic; SSE events carry seq > this`)
- `Edge.fused` meaning (`20-api-contract.md` § DTOs, the `Edge` DTO's `fused`
  field: `"fused": false // true: the producing endpoint has no emission
  point at all` — a delegating pass-through with genuinely no message to
  observe)
- `flow.rates` cadence and the rate-0-omitted rule (`20-api-contract.md`
  § SSE events, the `flow.rates` row: 1 Hz batch; edges with no traffic that
  window are omitted, never sent as `rate: 0`)
- the cold predicate (`20-api-contract.md` § DTOs, the `GraphList` DTO's
  `lifecycle` field: `"lifecycle": "hot" | "cold" // cold iff every member
  cell reports Node.lifecycle SUSPENDED`)
- `data`-mode search bounds (`20-api-contract.md` § Endpoints, the
  `GET /api/inspect/search` row: bounded — 50 cells / 2s deadline / cold
  components skipped — and always returns a non-null `cost`,
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

---

## V1C-CONCORD (the bounded state read): `21-PULL-03` filed, not covered

The bounded-read schema change (`read-state` step, `wave-plane-unchanged` and
`pages-equal-view` checks) covered three of the four requirements it landed:
`21-PULL-02` by `21-PULL-02.yaml`, `24-BOUND-01` by `24-BOUND-01.yaml`, and
`24-BOUND-02` by `24-BOUND-02.yaml`'s limit sweep. The fourth is filed here.

### `21-PULL-03` (cross-page stability) — **`cell-catalog-gap` + `script-model-gap`**

- **Requirement** (`doc/spec/20-dataflow-semantics/21-propagation.md` §Pull):
  *WHEN a bounded read over a state family in which every state change mints or
  absorbs a tag is walked to completion and every page carries an equal frontier
  stamp, the union of its pages SHALL equal that cell's state at that frontier.*
- **Scenario it would have carried**: `21-PULL-03.yaml`, in
  `concord/corpus/21-propagation/` — a settled source, a mutation accepted while
  a walk is in flight, then the assertion that equal opening and closing stamps
  imply the union is exactly the snapshot at that frontier.
- **Why it cannot be checked honestly — two independent reasons.**
  1. **No qualifying state family exists in the cell catalog.** The family
     qualification in the requirement is load-bearing, not decoration: comparing
     a walk's opening and closing stamps detects *tag gains, and only tag gains*.
     Every tag-frontier-carrying family in the standard library is an
     observed-remove set, whose retraction copies the add-tags it already holds
     into its del-map and mints nothing — so a mid-walk retraction of an element
     the walk has already paged leaves both stamps equal while the union still
     names that element present. For those families equal stamps are *necessary
     but not sufficient*, and the shipped primitive says so on its own read
     (`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`, `StatePage`'s
     stability contract). A scenario over `set-source` therefore could not make
     the requirement's antecedent true; it would read as covered while asserting
     something the requirement does not claim. The derived (operator) families
     are further away, not closer: their frontier is not even monotone, so a
     retraction can *lower* the stamp.
  2. **The script model cannot interleave a mutation with a walk.** A
     `read-state` step is a *whole walk* by construction (the harness loops the
     driver until the cursor terminates), and steps on one cell apply in file
     order, so there is no way to order an `apply` against a page boundary. Even
     for a qualifying family, the only instance a scenario could build is the
     quiescent one — where the antecedent is trivially satisfied and the
     consequent is exactly what `24-BOUND-02` already asserts, at every limit.
- **What was NOT done instead** (this is the point of the filing): no
  `21-PULL-03.yaml` was authored over `set-source` at quiescence. Such a
  scenario would pass, and would appear as `covered` in `CONCORDANCE.md`, while
  asserting only the requirement's trivial instance and nothing about the
  mid-walk case the requirement exists for. The requirement text was likewise
  not softened to drop its family qualification — that qualification is what
  makes it true.
- **What is not lost.** The property is pinned implementation-side by
  `V1C-KERNEL`'s own kernel tests (`BoundedReadWaveNeutralityTest` and the
  bounded-read suite around it). What this filing forgoes is the
  *cross-implementation* obligation: a second, non-kernel binding would not be
  held to it by the corpus.
- **Resolves**: either (a) a state family whose retraction mints or absorbs a
  tag (the research item the shipped `StatePage` contract names — an OR-set
  variant whose observed-remove moves the frontier), bound as a catalog cell,
  **and** (b) a way for a scenario to order an operation against a page boundary
  — e.g. a paged form of the step (`{type: read-page, on: s, limit: N, as: w}`
  plus a `resume` step) that the current whole-walk form deliberately does not
  provide. (a) alone would still leave only the quiescent instance reachable.
  With both, author `21-PULL-03.yaml`: walk, mutate mid-walk, resume to
  completion, and assert stamps-equal ⟹ union-equals-snapshot over a family for
  which that implication actually holds.

---

## SEC1 (boundary-policy denial accounting): BS-8 filed, not covered — `43-security.md` mints no ids

### BS-8 (disclosure covers catch-up and live alike, and accounts what it suppresses) — **`spec-gap` (no requirement id) + `schema-gap`**

- **Category**: `spec-gap` (id-authoring backlog) first, `schema-gap` second.
  This is the per-behaviour instance of the "13 zero-id chapters" structural
  entry above: `doc/spec/40-distribution/43-security.md` is one of those
  thirteen, so the behaviour BS-8 names has **no `[NN-SLUG-nn]` id to cover**.
  Filed here per this file's opening rule and per `[SEC1-30]`, which is
  explicit that a SEC1 concord candidate lands as a kernel test **plus** this
  entry — never as an invented `covers:` id, and never as a hand-edit of
  `doc/spec/CONCORDANCE.md` (which is generated).
- **Behaviour it would carry** (epic `computenet-usd`, BS-8; spec
  `43-security.md` §BoundaryPolicy with `20-dataflow-semantics/21-propagation.md`
  §Pull and `90-roadmap/93-feature-interactions.md` I-28 — "a snapshot IS a
  delta, one filter covers both"): *Given a mediated outlet with
  `disclosure = Project(<transform>)` and pre-existing state, when a new
  subscriber links and a live delta is then emitted, then the `onLinked`
  catch-up state-as-delta and the live delta are filtered by the SAME
  transform, no un-redacted value is observable on either path, and each
  suppressed delivery attempt on either path produces one denial record and one
  counter increment.*
- **Scenario it would have been**: `43-DISCLOSURE-01.yaml`, in a
  `concord/corpus/43-security/` directory that does not exist.
- **Why it cannot be checked honestly today — two independent reasons.**
  1. **No id to cover.** `covers:` is checked against the generated requirement
     inventory; `43-security.md` mints none, so an authored scenario would
     either carry a dangling `covers:` id (which fails `./gradlew
     :concord:check`) or carry none and appear as an orphan. Minting `[43-*]`
     ids is documentation maintenance and is explicitly **not** authorized by
     the epic that produced this behaviour.
  2. **No corpus surface for a membrane.** The scenario schema has no
     vocabulary for a `CompositeCell` exposure, a `BoundaryPolicy`, a
     `ProjectionId`, or a per-boundary denial counter: there is no cell-catalog
     entry that mediates an outlet, no step that declares a disclosure policy,
     and no check that reads a denial count. Binding one would mean reaching
     into `civictech.cell.membrane.*` from the corpus — which only
     `civictech.concord.driver.kernel` may do, and which would make the
     "scenario" a kernel test wearing YAML.
- **What was NOT done instead** (the point of filing): no scenario was authored
  over a nearby *unmediated* outlet asserting only that catch-up and live
  deliveries agree. Such a scenario would pass, would read as coverage of BS-8
  in `CONCORDANCE.md`, and would assert nothing about disclosure at all — the
  filter, the redaction, and the denial accounting are precisely what it could
  not reach. The behaviour text was likewise not weakened to drop its
  accounting half.
- **What is not lost.** The behaviour is pinned implementation-side, by name,
  in `kernel/src/test/kotlin/civictech/cell/membrane/BoundaryPolicyTest.kt`:
  - `BS-8 catch-up and live emission are redacted by the same transform, with
    denials accounted on both paths` — the whole behaviour, including one
    denial for the suppressed catch-up unicast and one for the suppressed live
    delivery, and no `secret`-prefixed element observable on either path;
  - `one denial record per suppressed delivery attempt - per consumer, per tap,
    per observer` — the decided counting unit (the delivery **attempt**: an
    emission broadcast to three attachments records three denials; an emission
    with no attachment records none);
  - `BS-9 disclosure Deny still links and still clamps an attention assertion`
    — the management-/attention-only peering twin.
  What this filing forgoes is the *cross-implementation* obligation: a second,
  non-kernel binding of the model would not be held to BS-8 by the corpus.
- **Resolves**: (a) an id-authoring pass over `43-security.md` (the W1-C-shaped
  pass the structural entry above already names) mints requirement ids for the
  boundary-policy seams, **and** (b) the scenario schema gains a membrane
  surface — a mediated-exposure catalog cell with a declarable disclosure
  policy and a `denial-count` check. (a) alone leaves the behaviour
  unexpressible; (b) alone leaves it uncoverable. With both, author
  `43-DISCLOSURE-01.yaml` as described.

---

## SEC1 (wire crossing): BS-16 filed, not covered — same zero-id chapter, plus no peering surface

### BS-16 (the bridge crossing consults the Exposure's `BoundaryPolicy`, keyed on the ingress-stamped `PeerId`) — **`spec-gap` (no requirement id) + `schema-gap`**

- **Category**: `spec-gap` (id-authoring backlog) first, `schema-gap` second —
  the same pair as the BS-8 entry above, for the same chapter. Filed per
  `[SEC1-30]`: a SEC1 concord candidate lands as a kernel test **plus** an entry
  here, never as an invented `covers:` id and never as a hand-edit of the
  generated `doc/spec/CONCORDANCE.md`.
- **Behaviour it would carry** (epic `computenet-usd`, feature
  `computenet-usd.4`, BS-16 plus the wire halves of BS-8/BS-9; spec
  `40-distribution/43-security.md` header and §"The three seams",
  `40-distribution/41-location.md` §Transport point 4, and
  `90-roadmap/93-feature-interactions.md` I-28 §4.1/§6): *Given two peers joined
  over a bridge and a mediated exposure declaring `disclosure = Project(p)`,
  when the remote peer links across that bridge and receives the `onLinked`
  catch-up plus a live delta, then both are filtered by the SAME transform, the
  `Principal` the predicate sees at the remote-triggered evaluation is
  `Peer(<the ingress-stamped PeerId>, TransportVouched)` — never re-derived and
  never a stronger claim — and the wire frame form is unchanged (ids-only, with
  signature/peer/counter as envelope fields).*
- **Scenario it would have been**: `43-BOUNDARY-PEER-01.yaml`, near
  `concord/corpus/41-location/`, in a `concord/corpus/43-security/` directory
  that does not exist.
- **Why it cannot be checked honestly today — three reasons.**
  1. **No id to cover**, exactly as for BS-8: `43-security.md` mints no
     `[NN-SLUG-nn]` ids, so an authored scenario would carry either a dangling
     `covers:` (which fails `./gradlew :concord:check`) or none (an orphan).
     Minting `[43-*]` ids is documentation maintenance, explicitly **not**
     authorized by the epic that produced this behaviour.
  2. **No corpus surface for a membrane** — the BS-8 entry's reason 2, verbatim:
     the schema has no vocabulary for a `CompositeCell` exposure, a
     `BoundaryPolicy`, a `ProjectionId` or a per-boundary denial counter.
  3. **No corpus surface for a peering.** BS-16 is about *identity at a
     crossing*, so it needs two peers, a bridge between them, and an assertion
     about which `Principal` a predicate observed. The `dist` profile can place
     replicas on separate hosts, but nothing in the scenario schema declares a
     `Peering.Side`, an admitted `PeerId`, or reads back the principal a
     boundary evaluated — and the last of those is not a state check at all: it
     is an observation of *what the runtime knew during one evaluation*.
- **What was NOT done instead** (the point of filing): no scenario was authored
  over a cross-host link asserting only that a projected feed arrives projected.
  That would pass, would read as coverage of BS-16 in `CONCORDANCE.md`, and
  would assert nothing about identity — which is the entire behaviour. The
  behaviour text was likewise not weakened to drop its principal half.
- **What is not lost.** The behaviour is pinned implementation-side, by name, in
  `kernel/src/test/kotlin/civictech/cell/wire/BridgeBoundaryPolicyTest.kt`, over
  two `Peering.Side`s joined by `Peering.loopback` under a
  `SimulationController`:
  - `BS-16 loopback - catch-up and live deltas cross projected, and the catch-up
    sees Peer(q, TransportVouched)` — the whole behaviour on the loopback path,
    with the catch-up unicast and every live delta encoded/decoded across the
    real bridge cells before the assertions read them;
  - `BS-9 wire half - Deny discloses nothing across the bridge yet the peering
    stays usable` — the `disclosure = Deny` twin: link established, no catch-up
    and no live delta across the wire, metadata-plane traffic still delivered
    and clamped to the exposure's declared ceiling;
  - `Deny suppressions on this path move the exposure's denial counter and
    restart nothing` — the accounting and the BS-14 not-a-fault half.
  What this filing forgoes is the *cross-implementation* obligation: a second,
  non-kernel binding of the model would not be held to BS-16 by the corpus.
- **Resolves**: (a) an id-authoring pass over `43-security.md`; (b) a membrane
  surface in the scenario schema (BS-8's item (b)); **and** (c) a peering
  surface — a scenario-declarable peer boundary plus a check that can read the
  `Principal` a boundary predicate evaluated under. All three are needed:
  without (c), (a)+(b) can express a projected crossing but not whose it was.

### Resolved while pinning BS-16: a `PORT_PROTOCOL` frame carried no ambient peer

Not a dispute about a scenario — a **kernel gap**, found by the BS-9 wire-half
work and fixed in the same task (`computenet-usd.4.3`). Recorded here so the
measurement is not re-discovered, and because the BS-16 entry above cites it.

`BridgeIngressCell` stamps the authenticated `PeerId` onto every decoded
`HostedPortInvocation`, whatever its type. `ManagedHost`, however, installed
`CurrentPeer.with(hostedInvocation.peer)` on its **`PORT_MANAGEMENT` branch
only**. So `currentPrincipal()` answered `LocalTrusted` for a `PORT_PROTOCOL`
delivery that had demonstrably come off the wire, and
`BoundaryPolicy.protocolAuthority` — whose filter short-circuits on
`LocalTrusted` by design (93 I-28 §4.2, "local crossings carry `LocalTrusted`
and every predicate is a no-op") — did not clamp, floor or throttle a
*remotely* asserted attention level that arrived over a bridge. Measured: an
`Attention(HIGH)` frame crossing `Peering.loopback` into an exposure declaring
`ceiling = LOW` was applied at `HIGH`, so `[SEC1-18]`/BS-9's "arriving clamped"
half did not hold at the wire.

**Fixed**: the `PORT_PROTOCOL` delivery branch now runs under
`CurrentPeer.with(hostedInvocation.peer)`, as its `PORT_MANAGEMENT` sibling
does. The local fast path is untouched, and the distinction is carried by the
data rather than inferred — `HostedPortInvocation.peer` is non-null iff a bridge
ingress decoded the frame, and is never serialized, so an in-process
`Protocols.sendUpstream` still evaluates at `LocalTrusted`. Both halves are
pinned together by `the ceiling clamps a bridge-arriving assertion and stays a
no-op for a local one` in `BridgeBoundaryPolicyTest.kt`, alongside
`BoundaryPolicyTest`'s `local attention crossing is never attenuated, even under
a strict policy`.

What this does **not** change: no `[43-*]` id is minted, no scenario is
authored, and BS-16 itself stays filed above — the fix makes the behaviour true,
not coverable.

**One consequence it did widen, recorded rather than glossed** (reviewer, same
task): the new frame is ambient for *everything* an inbound protocol frame
synchronously causes. That is a unicast pull reply (`pullServe` -> `baselineTo`),
plainly remote-triggered — but also a **fan-out the frame merely unblocks**: a
`Protocols.Progress` ack completing a wave in `CoalescingCombineCell`/`WaveGate`
calls `flushReady()` -> `outlet.call.propagate(...)` on the same thread, so a
mediated outlet's `disclosureFilter` there evaluated under the *acking* peer for
a delta addressed to every attached observer. The identity was never fabricated
(`HostedPortInvocation.peer` is non-null only for a bridge-decoded frame), but
it is not the identity of each recipient's own crossing.

**Decided and closed, 2026-08-16 (`computenet-usd.8`): a fan-out carries
`Principal.LocalTrusted`.** The acking peer is the upstream *producer* of one
arm — neither the requester nor a recipient of the released emission — and the
fan-out is not one peer-scoped crossing at all: each attachment is a `PortRef`
from which no peer identity is derivable at this seam, and an attachment that
is itself remote gets its own stamped ingress one hop further down. So the
emission has no single rightful principal, and `LocalTrusted` — the "no peer in
scope" reading `CompositeCell`'s `denyDisclosure` already documents — is what it
carries. Realized as a scope reset at the fan-out (`FanOutlet.call`'s broadcast
loops run under `CurrentPeer.with(null)`), **not** at the `ManagedHost` frame,
so the unicast reply through `FanOutlet.at` keeps `Peer`. Both halves are pinned
by `kernel/src/test/kotlin/civictech/cell/membrane/PeerUnblockedFanOutPrincipalTest.kt`.

**What that decision does NOT settle, stated so it is not read as more than it
is.** It fixes *scope* — which principal is ambient during a broadcast — and
nothing about *cross-hop composition*. Whether a disclosure decision could ever
be evaluated **per recipient** remains 93 I-28 §8, open and explicitly outside
SEC1: `[SEC1-19]` computes one disclosure verdict per emission and shares it
with every attachment, and `[SEC1-20]` discharges a refused exclusive exactly
once against that single evaluation — so a per-principal verdict is not merely
unimplemented, it is incompatible with two landed decisions and would have to
reopen both. A `Projection` is still an opaque `(Any) -> Any?` free to read
`currentPrincipal()` itself; under this decision it reads `LocalTrusted` during
a broadcast, which is honest but is not a per-recipient answer and must not be
used as one. Nothing here mints a `[43-*]` id or authors a scenario.

---

## SEC1 (wave completeness + reconvergence): BS-12/BS-13 are kernel-test-covered only, and `[SEC1-28]` is UNVERIFIED

Two entries, filed together by `computenet-usd.3.2` (feature `computenet-usd.3`).
The first is the `[SEC1-30]` coverage record for the pair; the second is the
honest verdict on `[SEC1-28]` itself.

### BS-12 / BS-13 (`[SEC1-27]`, `[SEC1-28]`) — coverage — **`spec-gap` (no requirement id)**

- **Category**: `spec-gap` (id-authoring backlog). The third instance of the
  same structural fact the BS-8 and BS-16 entries above record for this chapter:
  `doc/spec/40-distribution/43-security.md` mints no `[NN-SLUG-nn]` ids, so the
  behaviours BS-12 and BS-13 name have **no legal `covers:` target**. Filed per
  `[SEC1-30]`: a SEC1 concord candidate lands as a kernel test **plus** an entry
  here — never as an invented `covers:` id (which fails `./gradlew
  :concord:check` as a dangling id), never as a scenario carrying none (an
  orphan, which fails the same gate), and never as a hand-edit of the generated
  `doc/spec/CONCORDANCE.md`.
- **Behaviours** (epic `computenet-usd`, feature `computenet-usd.3`; spec
  `40-distribution/43-security.md` §BoundaryPolicy with `20-dataflow-semantics/22-consistency.md`
  §"Completeness over silent or stuck edges", `30-execution-model/31-hosts.md` rule 5,
  and `90-roadmap/93-feature-interactions.md` I-18 / I-28 §6):
  - **BS-12** — *Given a downstream wave depending on two upstream edges, one
    crossing a boundary that refuses a delta mid-wave, then the wave reaches
    completeness within a bounded wait via the I-18 edge-will-not-deliver
    classification, the frontier advances, the edge stays open, and the run does
    not hang.*
  - **BS-13** — *Given a reconvergent diamond with one filtered and one
    unfiltered arm, when the filtered arm is denied mid-wave, then the
    reconvergence point never observes a mixed pre/post-denial combination.*
- **Scenarios they would have been**: `43-DENIAL-COMPLETENESS-01.yaml` and
  `43-DENIAL-DIAMOND-01.yaml`, in a `concord/corpus/43-security/` directory that
  does not exist.
- **Why they cannot be authored today**: reason 1 above (no id to cover), plus
  the BS-8 entry's reason 2 verbatim — the scenario schema has no vocabulary for
  a `CompositeCell` exposure, a `BoundaryPolicy`, a `ProjectionId`, an
  `IntegrityPolicy`, or a per-boundary denial counter, and binding one would
  mean reaching into `civictech.cell.membrane.*` from the corpus, which only
  `civictech.concord.driver.kernel` may do.
- **What was NOT done instead**: no scenario was authored over a nearby
  *unmediated* two-edge join or diamond asserting only that waves group
  correctly. Such a scenario passes today, would read as coverage of BS-12/BS-13
  in `CONCORDANCE.md`, and asserts nothing about a denial — which is the entire
  behaviour. `22-WAVE-FANIN-01` already covers the undenied fan-in; adding a
  denial-shaped *name* to an undenied scenario is precisely the weakening this
  file exists to refuse.
- **What is not lost.** Both behaviours are pinned implementation-side, by name:
  - `kernel/src/test/kotlin/civictech/cell/consistency/BoundaryDenialWaveCompletenessTest.kt`
    (BS-12, `computenet-usd.3.1`) — integrity and disclosure variants of the
    two-edge join, each asserting the *poisoned wave itself* releases before any
    later wave exists to retroactively complete it, plus the guard that a denial
    naming no wave position classifies nothing and leaves the edge open;
  - `kernel/src/test/kotlin/civictech/cell/consistency/BoundaryDenialDiamondGlitchTest.kt`
    (BS-13, `computenet-usd.3.2`) — the diamond, over seeds `0 until 40`, with
    its own control run proving the harness tears without the frontier.
  What this filing forgoes is the *cross-implementation* obligation: a second,
  non-kernel binding of the model would not be held to BS-12/BS-13 by the
  corpus.
- **Resolves**: normative `[43-*]` ids land in `doc/spec/40-distribution/43-security.md`
  (the SEC1 feature-6 id-authoring authorization — documentation maintenance
  **not** authorized by the epic that produced these behaviours), **and** the
  scenario schema gains the membrane surface BS-8's item (b) already names. With
  both, author the two scenarios above.

### BS-13 / `[SEC1-28]` — **UNVERIFIED** (`requirement-gap`): the reconvergence point forms no combination

Filed as the explicitly-allowed outcome of `computenet-usd.3.2`, not as a
failure of it. `[SEC1-28]` is **not** weakened, and the kernel test above is not
softened to make this entry go away: it asserts the narrower properties that are
genuinely checkable and leaves the rest here.

- **Category**: `requirement-gap`. Unlike the entries above, the obstacle is not
  the corpus schema or a missing id — it is that the requirement's subject has
  no referent in the landed model, so no binding, kernel or corpus, could check
  it as written.
- **The requirement**: "*a boundary denying mid-wave in a reconvergent diamond
  with one filtered and one unfiltered arm SHALL never yield an observed mixed
  pre/post-denial combination*".
- **Why it cannot be checked as stated — the reconvergence point forms no
  combination.** `GlitchFreeCell` is a *grouping* pass-through: a completed
  wave's buffered invocations are replayed to the consumer **individually**
  (`WaveFrontier.flushReady`). That is not an accident of this test's shape but
  the structural fact `civictech.cell.data.op.CoalescingCombineCell`'s KDoc
  records as the reason a coalescing operator cannot compose the frontier
  policy. So at the reconvergence point there is no combined value to inspect
  for a pre/post-denial mixture; there is a *group* of arrivals.
- **What was checked instead, and is asserted** (`BoundaryDenialDiamondGlitchTest`,
  a diamond whose projecting arm redacts `M:secret-n -> M:n` and suppresses
  every third wave, swept over seeds `0 until 40`):
  1. **wave-contiguity** — the arrival log partitions into exactly one
     contiguous run per wave, in strictly increasing counter order; no wave's
     contributions are split by another's;
  2. **no stale substitution** — each run carries contributions of that wave
     only; on a denied wave the surviving arm rides alone, and the denied arm's
     pre-denial value is never paired into the release;
  3. **the flagged RE-SCOPE release, exactly once per denied wave** — one
     `GlitchViolation` naming `DEAD_LETTERED` and that wave's `Timestamp`, with
     the boundary's denial counter moved and no supervision restart
     (`[SEC1-29]`, BS-14);
  4. **the regimes genuinely differ** — no unredacted value is observable at the
     reconvergence point.
  The test's name and KDoc claim exactly these four and no more. A `control -`
  test runs the same graph, seeds and denials without the frontier and requires
  that the full property (1)+(2) does tear, so none of it is "no glitch was
  observed".
- **What could NOT be checked, measured rather than asserted away.** One hop
  past the join, any consumer that folds the two arms into a single value does
  hold the mixture `[SEC1-28]` names, and nothing refuses, flags or tags it:
  - **at a denied wave** the fold retains the denied arm's *previous* value
    beside the surviving arm's fresh one — literally a pre/post-denial pair;
  - **within every wave, denied or not**, the fold is torn between the group's
    two arrivals. So the denial is not what creates the hazard: it is the
    ordinary partial-fold transient of a grouping join, which means a check of
    `[SEC1-28]` at that level would fail for reasons having nothing to do with
    boundaries.
  Both are pinned as measurements in the test (`the fold one hop past the join
  does form the mixed combination nothing refuses`) so the gap cannot rot
  silently; a failure there means something started refusing the mixture and
  **this entry is stale and must be revisited**.
- **The stance this is judged under, and where it is recorded.** 93 I-28 §9 open
  question 1 is decided by `computenet-usd.3.2` as **stance (ii)**: a projected
  value is a distinct logical value, so reconvergence across disclosure regimes
  is a modelling error rather than a runtime hazard the frontier can rescue. The
  reasoning, the rejection of stance (i), and the enforceable-today-vs-deferred
  split are recorded as a design record in the `DisclosurePolicy` KDoc,
  `kernel/src/main/kotlin/civictech/cell/membrane/BoundaryPolicy.kt`. Under that
  stance the fold above is precisely the modelling error — and it is **nobody's
  job to detect today**, which is why this entry exists rather than a passing
  scenario.
- **Why neither stance is enforceable on the landed machinery** (verified at
  this branch's base, `5e56586`):
  - **no cross-hop policy visibility** — a policy is a property of the exposure
    it is declared on, nothing propagates it, and identity does not transit hops
    (`BoundaryPolicy`'s multi-hop block, `computenet-usd.4.3`). A join cannot
    ask, at link time, which regime an arm's upstream crossing was under.
  - **no regime tagging on values** — a projected delta is indistinguishable
    downstream from a full one, so the reconvergence point cannot detect that
    its arms disagree, let alone refuse it.
  - and, for the reconvergence shape that *does* combine: `CoalescingCombineCell`
    registers a handler for `Protocols.Progress` only — it installs **no**
    `Protocols.Suspension` handler, so the I-18 classification a boundary denial
    emits does not reach it at all. A denied wave there is retired only by a
    later wave's monotone watermark or by `EdgeClose`. (Read from
    `kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt`; its
    KDoc says as much — "Deliberately *not* mirrored ... terminal-stall
    RE-SCOPE". Not measured here: staging a `CounterDelta` diamond over that
    operator is outside `computenet-usd.3.2`'s scope, and this bullet is a
    code-reading fact, not a test result.)
- **Resolves**: (a) a value-level **disclosure-regime tag** that survives
  re-emission through intermediate cells — a provenance carrier the wave/tag
  plane deliberately lacks (predicates never mint or carry waves, 93 I-28 §6 /
  G-20) — plus a decided rule for what a cell derived from two regimes *is*;
  that is 93 I-28 §8's open cross-hop composition question, which SEC1 does not
  settle. **And** (b) a decision about whether a combining reconvergence point
  (`CoalescingCombineCell`) must handle terminal stalls, since without it the
  denial classification never reaches the only landed cell that forms a
  combination. With both, restore the check as: stage the diamond over a
  combining join, and assert the emitted per-wave value for a denied wave is
  either refused as regime-crossed or carries no pre-denial contribution.

## ORA1/ORA2 marker ids are NOT EARS requirements, so their absence from this corpus is not a gap (computenet-4ru.22)

The two entries below cite `ORA1 §DIFF-09`, `ORA2 §DIFF-07` and their neighbours. Read that
citation form correctly, because a reviewer already read it wrongly once and it cost a verdict.

`ORA1 §…` and `ORA2 §…` are **acceptance clauses of the beads items that built the `:oracle`
harness** — ORA1's in epic `computenet-4ru` §4, ORA2's in feature `computenet-4ru.1` §4. They are
not EARS requirement ids. Neither family has normative text under `doc/spec/`, no scenario
`covers:` one, and none ever will: they constrain the **tester** (what the reference model may
import, how a sweep reports density, which controls must redden), not the runtime that `doc/spec`
specifies and this corpus checks. "No scenario covers `ORA2 §MODEL-12`" is therefore a category
statement, not a dispute, and does not belong in the body of this file.

Checked, not assumed (2026-08-25, base `3d190aaff`): `git grep 'ORA1-' doc/` and
`git grep 'ORA2-' doc/` are both empty, and this file is `concord/corpus`'s only mention of either
family. There is **no asymmetry** between ORA1 and ORA2 — the bead that raised this
(computenet-4ru.22) warned against assuming one, and there is none to assume.

What changed under computenet-4ru.22, and under computenet-gmld which finished it:

- ORA2's markers were renamed from a square-bracketed `ORA2-MODEL-12` to `ORA2 §MODEL-12`, the repo's
  `<document> §<section>` idiom (`96 §E1.5`, `epic computenet-4ru §2.3`). The square brackets are
  this repo's mark of an EARS id in `doc/spec` (`[24-TMAP-03]`, `[42-REPL-04]`); dropping them is
  the point. `civictech.oracle.MarkerFormTest` stops the old shape returning.
- ORA1's markers were renamed the same way, in a follow-up (computenet-gmld) after
  computenet-4ru.22: its 465 citations reached outside that item's file claim (`:kernel` tests,
  two `build.gradle.kts` files, `.claude/skills/work/SKILL.md`). The `SKILL.md` occurrence is not
  a citation — a single illustrative example inside a skill-authoring bullet — and is split out
  as computenet-yiof rather than renamed here, since a work session must not edit the skill it
  is executing under.
- The one stale ORA2 citation at
  `kernel/src/test/kotlin/civictech/cell/data/SetConvergenceTest.kt:31` (a square-bracketed
  `ORA2-CONV-01..04`) was also renamed by computenet-gmld.

The rejected alternative was to give ORA2 a home under `doc/spec/` with corpus coverage. It was
rejected on the merits, not on cost: writing harness obligations into the runtime's normative spec
would make `doc/spec` describe the tester, and a scenario cannot express an import-boundary or a
sweep-density claim at all.

## ORA1 (the divergence control): `ORA1 §DIFF-09`/BS-12 is filed, not built — the reference model and the kernel disagree about `[24-SET-03]`'s observer

Filed by `computenet-4ru.10.4` (feature `computenet-4ru.10`, epic `computenet-4ru`) as the
honesty-ledger deliverable of that feature, on the human decision of 2026-08-20 (option (a) on
`computenet-4ru.10.1`'s comment thread). This is not a corpus scenario gap: it is a
**disagreement between two landed artifacts about what a normative requirement means**, recorded
here because the epic's rule is that a requirement which cannot be checked honestly is filed,
never weakened into a passing scenario.

### `ORA1 §DIFF-09` / BS-12 (the divergence control) — **`oracle-gap`** (reference-model semantics), blocked on the `[24-SET-03]` observer disagreement that `computenet-eeys` settled

**Read the blocker as the disagreement, not as the bead.** The human decision of 2026-08-20
worded this "blocked on `computenet-eeys`" while that bead was still in review; it has since
**closed** (2026-08-20, PR #365), and its closure does *not* unblock BS-12 — it is what
established that the reference model, rather than the kernel, is the wrong side, which is
precisely why the control cannot fire. Nothing is waiting on eeys to report. What would make
BS-12 buildable is the **Resolves** bullet at the end of this entry.

- **Category**: `oracle-gap`. The obstacle is neither the corpus schema nor a missing id: it is
  that the instrument BS-12 specifies cannot exist while the reference model and the kernel read
  `[24-SET-03]` differently. Nothing in the corpus is weakened by this entry, and no scenario is
  authored, renamed or softened on account of it.

- **The normative requirement in dispute**: `[24-SET-03]`
  (`doc/spec/20-dataflow-semantics/24-data-cells.md`) — *"A remove SHALL only retract the tags it
  observed, such that a concurrent add's tag — never observed by that remove — survives the merge
  (add-wins as a consequence of tag-set union, Ubiquitous)."* The dispute is over the word
  **observed**: whose observation the requirement means.

- **Which side each artifact takes.**
  - **The kernel — the observer is the CELL.** `SetCell.inletHandler.remove`
    (`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt`) retracts `liveTags(element)`:
    *every* un-tombstoned tag the cell holds for that element. It never consults the removing
    writer's causal history, because `SetOps.remove(element)` carries no writer at all. A single
    `SetCell` driven through its own `inlet` is one serialization point and has observed every
    add that reached it.
  - **The oracle's reference model — the observer is the WRITER.**
    `civictech.oracle.model.Membership` (`oracle/src/main/kotlin/civictech/oracle/model/Membership.kt`,
    `ORA1 §MODEL-04`/`ORA1 §MODEL-05`) counts an add as *covered* only if the removing writer
    had observed it — it issued the add itself, or a `ScriptEvent.Observe` by that writer sits
    between the add and the remove. A remove of an element another writer added, whose add the
    remover never observed, is a no-op in this model.
  - **Settled verdict (`computenet-eeys`, closed 2026-08-20, PR #365): the reference model is the
    wrong side, and no kernel defect is implied.** Writer identity has no counterpart on the
    generated drive path — `CaseExecution` funnels every writer's op through one inlet and
    `ScriptEvent.Observe` injects nothing — so a generated script's "concurrent writers" are in
    fact sequential against one replica. Real concurrency in this kernel is across *replicas*
    (`SetCell.deltaInlet`/`applyRemote`, spec 40/42), and a generated case builds one. The full
    reasoning is recorded in `civictech.oracle.run.WavePrefixOracle`'s KDoc, next to the numbers.

- **What was measured** (Darwin arm64, 2026-08-20). The first two bullets rest on **tests** in
  `WavePrefixTest`, not on a session transcript — with one qualification: those tests assert the
  *shape* (the three-event case diverges with a single-writer control that does not; some
  two-writer seed carries a kernel-effective, model-inert remove and no single-writer seed
  does), while the exact counts below are recorded in KDoc rather than asserted, deliberately,
  so that a generator change moves the numbers without reddening a test. The third — the
  `Membership.observes`
  mutation — is a **one-off run recorded in `civictech.oracle.run.WavePrefixOracle`'s KDoc**
  next to the numbers, reverted and never committed, as an exhaustiveness mutation necessarily
  is: it is evidence a reader has to take from that record, not a test that re-runs.
  - The disagreement reproduces in **three events, with no generator and no seed**: `w0` adds
    `ab`, `w1` adds `ab`, `w0` removes `ab`. At the probe diamond's terminal the model answers
    `{ab, a, b}` and the kernel the empty set (`WavePrefixTest`, "a remove of an element another
    writer added is applied by the kernel and ignored by the model", with a single-writer control
    that succeeds).
  - Over `WavePrefixTest.generatedSweepConfig`, seeds `0..59`: **22 of 60** two-writer scripts
    carry such a step and 9 surface as a failure (the other 13 are masked downstream by a
    `filter`, `quorumSet` or `count`); **0 of 60** single-writer scripts carry one.
  - Mutating `Membership.observes` to `return true` — i.e. adopting `SetCell`'s reading — clears
    the entire population in one step (`settledMismatch`, `plateauFlicker`, `chainArtifact`,
    `glitchCandidate` all empty). There is no residual, which is what rules out a second
    independent cause.

- **Why BS-12 cannot be built on top of that.** `ORA1 §DIFF-09`/BS-12 asks for a *divergence
  control*: a deliberately wrong reference — a naive arrival-order fold, membership by last
  add/remove event per element, tag coverage ignored — that **reddens on at least one seed** of
  the fixed range `0..59` while the catalog-resolved real reference stays green on all sixty. The
  two halves are mutually exclusive against today's kernel, because the naive fold is not a wrong
  description of `SetCell` — it is a **more faithful** one than the spec-faithful `Membership`
  fold is:
  - naive red on >= 1 seed requires `naive != kernel` on that seed;
  - real green on all 60 requires `real == kernel` on every seed;
  - but `naive == kernel` identically, so `naive != kernel` never happens.

  Measured over eight probed generator configurations x the fixed range `0..59`: at one writer
  (P1/P2/P5/P7) zero seeds differ between the two references and both columns are green; at two
  or more writers (P3/P4/P6/P8) the naive fold is `Success` on all sixty while the real reference
  mismatches on 42/34/43/32 of them — and the mismatching seed set **equals** the differing seed
  set. So the only divergence a control could fire on is `computenet-eeys` itself, which BS-12's
  third acceptance bullet explicitly excludes.

- **What was NOT done instead.** No naive fold was registered into `OperatorCatalog`; no seed
  range was rotated to a friendlier one; no `>= 1 seed diverges` assertion was written against a
  constant-wrong reference that would redden for a reason unrelated to arrival-order semantics.
  Any of those would have produced a green "divergence control" in `:oracle:test` that
  demonstrates nothing about the sweep's discriminating power — precisely the weakening this file
  exists to refuse.

- **What is not lost.** `oracle/src/test/kotlin/civictech/oracle/run/DivergenceControlTest.kt`
  (`computenet-4ru.10.1`) pins the **measurement** in place of the control, in two tests: that a
  single writer makes the naive fold indistinguishable from the real reference, and that the
  naive fold agrees with the kernel on **exactly** the seeds the real reference fails on (an
  order-exact set equality, not a containment). The second is a **tripwire**: it goes red the
  moment a `SetCell` remove becomes writer-scoped — i.e. the moment the naive fold becomes
  genuinely wrong and BS-12 becomes implementable. Do not repair the assertion when that happens;
  implement BS-12 proper. The sweep's remaining discriminating-power evidence is
  `MutationCheckTest` (`ORA1 §DIFF-10`/BS-13), which covers the derived-operator half of the
  vocabulary; `civictech.oracle.run.OracleSweep`'s `ORA1 §HONEST-01` KDoc says at the module's
  entry point that this defense is currently the weakest of the four.

- **Recorded, not resolved.** This entry does not settle what `[24-SET-03]` should say, and does
  not ask the kernel to change: the verdict is that the requirement's observer is the cell and
  the kernel already implements it. What is unresolved is the reference model's reading of it,
  and the consequence for BS-12.

- **Resolves**: (a) `computenet-i3vo` — the `ScriptGenerator` post-condition that no generated
  remove may leave its element live in `Membership`, which removes the disagreement from the
  generated population (it does **not** make BS-12 satisfiable: with it, naive and `Membership`
  coincide on generated scripts too). And, for BS-12 itself, (b) a wrongness instrument whose
  wrongness is not the arrival-order/causal distinction — a source model wrong in a way this
  kernel genuinely does not share — **or** a kernel in which a `SetCell` remove is writer-scoped,
  which is the tripwire above. With either, build the control BS-12 specifies and delete this
  entry.

## ORA2 (the wave-prefix diamond): BS-9 / `ORA2 §DIFF-07` is narrowed, not built — no operator in the vocabulary consumes a `TaggedMapDelta` outlet

Filed by `computenet-4ru.1.8` on the same `ORA2 §HONEST-03` clause, carrying `computenet-valh`'s
finding from the review of `computenet-4ru.1.6`. It is recorded here rather than only in a test's
KDoc because a reader arriving from the requirement side — `doc/spec/CONCORDANCE.md`, or this file —
otherwise reads `ORA2 §DIFF-07` as covered at the shape BS-9 states, which it is not.

### BS-9 / `ORA2 §DIFF-07` (the tagged wave-prefix diamond) — **`oracle-gap`** (vocabulary/kernel typing), blocked on `96 §E1.5`'s `UntagCell`/`TaggedMapView`

- **Category**: `oracle-gap`. No corpus scenario is weakened, renamed or softened by this entry; the
  landed test is green on its own narrower claim and stays exactly as it is.

- **The clause in dispute**: BS-9 / `ORA2 §DIFF-07`'s `Given` is *"a tagged map feeding a glitch-free
  consumer through two paths"* — the `WavePrefixTest` diamond, where a source fans into two operators
  that reconverge at a fan-in. That shape is not constructible for `orMap` against today's kernel.

- **Why it is unconstructible** (read from kernel source at this branch's base, `0d59d674`):
  - `OrMapCell`'s outlet is `Subscribe<Propagate<TaggedMapDelta<K, V>>>`
    (`kernel/src/main/kotlin/civictech/cell/data/OrMapCell.kt`).
  - every registered `MapOf`-consuming operator is typed to `Propagate<MapDelta<K, V>>` on **every**
    input port — `CoreOperators`' `join` -> `JoinCell.left`/`right`, `combineLatest` ->
    `CombineLatestCell.left`/`right`, `lookupJoin` -> `LookupJoinCell.fact`/`dimension`
    (`kernel/src/main/kotlin/civictech/cell/data/op/{JoinCell,CombineLatestCell,LookupJoinCell}.kt`).
    Wiring an `OrMapCell` outlet into one is a genuine kernel type violation, not a restriction that
    could be relaxed by configuration.
  - `civictech.oracle.bind.TaggedOperators` registers `orMap` as `ShapeRule.source(MapOf(Scalar,
    Scalar))` — **arity 0** — and `GraphGenerator.generate` requires a nonzero-arity entry
    (`check(operatorEntries.isNotEmpty())`), so no generated case can place anything downstream of a
    tagged terminal either.
  - the tagged-aware downstream adapters that would bridge the two delta types — `96 §E1.5`'s
    `UntagCell` / `TaggedMapView` — **do not exist**: verified 2026-08-21, the only file under
    `kernel/src/main` naming either is `OrMapCell.kt`'s own prose.

- **What is covered instead.** `civictech.oracle.tagged.TaggedWavePrefixTest` applies
  `civictech.oracle.run.WavePrefixOracle` **unchanged** to a **bare `orMap` source observed as its own
  terminal**, walking every intermediate observation through `DifferentialRunner`'s real per-step
  observer, with non-vacuity and discrimination controls so a reader can see how much the case does
  cover. It is honestly narrower than the diamond: with **no fan-in there is no glitch the case could
  exhibit**. That was already stated in the test's own KDoc; what this entry adds is the same
  statement on the requirement side.

- **Adjacent, and deliberately NOT filed here**: `computenet-880k` — the generator's `ElementShape`
  system cannot tell `TaggedMapDelta` from `MapDelta`, so a vocabulary naming `orMap` alongside
  `join`/`combineLatest`/`lookupJoin` emits an edge the kernel refuses at run time (a
  `ClassCastException` surfacing as `RunOutcome.DeadLetterFailure`). That is the same typing bound
  seen from the generator side, but it is a **soundness defect with a fix pending**, not a behaviour
  excluded as uncheckable, so it belongs in the tracker until it is fixed rather than in this file. It
  is named here only so a reader does not take this entry for its filing.

- **What was NOT done instead.** No operator was registered that would type-erase the tagged outlet to
  make the diamond constructible; no adapter cell was added to the kernel to manufacture a fan-in; and
  `TaggedWavePrefixTest`'s green is not restated anywhere as coverage of BS-9's stated shape.

- **Resolves**: `96 §E1.5`'s `UntagCell` / `TaggedMapView` landing in the kernel and being registered
  as a nonzero-arity catalog entry that consumes a tagged outlet. With either, build BS-9 at its
  stated shape — a tagged map feeding a glitch-free consumer through two paths, reconverging at a
  fan-in — and delete this entry.

## CHA3-42-stall-notice-unclean-departure: an unclean departure's `Stall`-family notice is not observable on any read `testkit`'s churn harness has

Filed by `computenet-umx.2.5` per the decided fallback named on its own bead
(`computenet-umx.2`'s breakdown comment, "umx.2-D7"): `[CHA3-42]` requires the harness assert
BOTH halves of an unclean departure's effect — the frontier freezes, AND a `Stall`-family
notice ([civictech.cell.control.StallNotice]) is observed on the reads. The task's own text
required the premise to be probed first, honestly, before writing the second half; it was, and
the negative held. This entry is that probe's record, plus a second, narrower finding measured
while building the frontier-freeze half itself.

### Half 1 — the `Stall`-family notice: **`kernel-gap`** (no mechanism reaches a churn peer's crash at all)

- **Category**: `kernel-gap`. Nothing here is worked around; `DepartureGatesTest`'s BS-10
  crash test asserts only the frontier-freeze marker (below) and says explicitly, in its own
  KDoc, that it does not assert the notice.

- **The clause in dispute**: `[CHA3-42]` / `96 §E3.6 (c)` — "surfaced as a `Stall`-family
  notice on the reads, not worked around" — for `DepartureMode.CRASH_UNCLEAN`.

- **Why it is not constructible**, traced from source rather than assumed:
  - `StallNotice.Stall` is constructed at six call sites in `kernel` main, five of them in
    `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — `:738` (`SUSPENDED`, on the
    host-level suspend cascade), `:1096` (`DEAD_LETTERED`, on a dead-lettered invocation under
    `SupervisionPolicy.PROPAGATE`), `:1101`/`:1138` (`RESTARTING`/`SUSPENDED`, under
    `SupervisionPolicy.RESTART`/`SUSPEND`) and `:1326` (`SUSPENDED`, from
    `HostManagementApi.suspend(ref)`) — plus `CompositeCell.kt:455` (`DEAD_LETTERED`, from
    `stallDeniedEdges` on a `BoundaryPolicy` refusal). Every one of them requires the AFFECTED
    CELL's OWN HOST to still be alive and routing a suspend, a supervision decision or an
    invocation through it; `CompositeCell`'s additionally requires the cell to be a composite
    boundary, which a churn-mesh data replica is not. (Enumeration corrected in review: the
    original filing said "exactly three ... `:738`, `:1096`, `:1101`/`:1138`" and both omitted
    `:1326`/`CompositeCell.kt:455` and mislabelled `:738` as the `HostManagementApi.suspend`
    site. The conclusion is unchanged and the wider enumeration strengthens it — none of the six
    is reachable from `HostSlot.crash`.)
  - `MeshPeer.crash()` (`testkit/src/main/kotlin/civictech/testkit/dst/churn/PeerHandles.kt`)
    calls `HostSlot.crash()` (`testkit/src/main/kotlin/civictech/testkit/dst/DstWorld.kt`),
    which shuts down the crashed generation's scheduler and rebuilds a fresh `ManagedHost` from
    the graph's own build lambda. It calls none of `suspend`, `restart`, or any invocation path
    that could dead-letter — it discards the host outright. `ChurnMeshTest`'s own control
    confirms this at the churn layer: `peer1.lastEvictDespawned` is `null` after a crash — "no
    eviction ran: nothing was announced or drained".
  - Even granting a notice fired somehow, it travels `notifyDownstream` — to cells LINKED
    downstream of the crashed replica **on its own host**. The churn mesh's replicas are plain
    data cells with no such downstream consumer wired to them, and — as `ReconvergenceCheck`'s
    own KDoc already states — "the kernel has no failure detector, so a crashed peer never
    unpublishes": a SURVIVING peer has no notification path for another peer's crash at all.

- **What was NOT done instead**: no downstream consumer was wired onto a churn replica purely
  to catch a notice that the crash path never emits; no assertion was written that would pass
  vacuously against an empty read.

- **Resolves**: either (a) a churn-reachable path that routes an unclean departure through
  `ManagedHost`'s own suspend/restart/dead-letter machinery instead of discarding the host
  outright (the "verified-first" premise `computenet-umx.2.2` built `CRASH_UNCLEAN` against
  explicitly rejected this — see its own bead comment on the crash-and-rebuild design), or (b) a
  future E3.6 milestone (per the task's own text, "the property becomes checkable when E3.6
  lands") that gives a departed-peer notice a path independent of the crashed host's own
  lifecycle. With either, add the notice assertion to `DepartureGatesTest`'s BS-10 crash test
  and delete this half of the entry.

### Half 2 — the frontier-freeze half itself is honest only for a marker read, not for `stabilityCovers` on a new wave: **`rig-gap`** (synchronous gossip delivery outlives a crashed peer's host)

- **Category**: `rig-gap`. `DepartureGatesTest`'s BS-10 crash test asserts the marker read
  (`StabilityObservables.rowClosed`/`rowSuspended` stay `false` across a bounded further drain)
  and does **not** assert `StabilityObservables.stabilityCovers` is `false` for a brand-new
  post-departure wave — unlike BS-11 (`EVICT_NO_CLOSE`), which asserts exactly that and is
  genuinely red without a fix.

- **Measured directly, not assumed**: building the crash test's `stabilityCovers` assertion the
  same way as BS-11's, it did **not** fail — a brand-new wave from a live peer, issued and
  drained well after the crash, read as COVERED across all three members' rows, including the
  crashed peer's. Two independent seeds reproduced this.

- **The mechanism, traced from source**: `Replication`'s own KDoc states the rig's delivery
  ceiling plainly — "link wiring calls streamTo on the local outlet directly (same ceiling as
  the M5.7 streamTo idiom — fine single-threaded/simulated; production wiring wants a
  host-queue hop)". Gossip delivery between linked replicas is therefore **synchronous** and
  does not route through a host's task queue. `MeshPeer.crash()` shuts down only the crashed
  generation's *scheduler* and discards `MeshPeer`'s own references (`replica = null`, a fresh
  `Replication`); it does not sever the underlying cell objects' links. `MeshPeer`'s class KDoc
  says as much: "`registry`, `bridgeHost` and `side` survive" a crash. So a crashed peer's OLD
  data cell and its delivered-watermark companion stay fully linked and continue to merge and
  relay inbound deltas — as zombies no `MeshPeer` reaches any more — for as long as the
  surviving `side`/`bridgeHost` keep them wired, because that relay path never touches the dead
  scheduler. `Replication.evict`'s `despawn` has no such gap: it explicitly unpublishes the
  DATA cell (`hostOf.remove`, `linked` pruned), which is exactly why BS-11's row genuinely
  freezes for the same check.

- **What was NOT done instead**: no assertion was narrowed to make `stabilityCovers` pass by
  picking a different wave or a different observer; the coverage assertion was dropped for the
  crash case entirely rather than weakened, and the marker-only assertion that remains is
  exactly what BS-9/BS-31's own row-state reads already established as honestly checkable.

- **Resolves**: a churn-reachable crash primitive that also drops the crashed replica's wire
  attachment (not only its scheduler) — at which point `stabilityCovers` would freeze for
  `CRASH_UNCLEAN` the same way it does for `EVICT_NO_CLOSE`, and the assertion can be added
  back to `DepartureGatesTest`'s BS-10 crash test and this half deleted. Until then, a suite
  relying on "a crashed peer's delivered-watermark row stops accepting new coverage" is relying
  on something this rig does not model.

## B14-promotion-glitch-freedom: the promotion swap has no corpus surface — no step verb, no gate/candidate catalog cell, and no L0 id in chapter 53

Filed by `computenet-051.5.5` (epic `computenet-051` JAR1, feature
`computenet-051.5`), which owns that feature's concord decision: *"evaluate whether
B14's glitch-freedom-across-swap is expressible in the existing corpus vocabulary.
If it IS expressible with existing verbs, write the scenario carrying `covers:`
against the 53 promotion requirement it exercises — never a JAR1-local id. If it is
NOT, record the gap honestly here — never substitute an easier scenario."* It is
not. This entry is that evaluation's record, with the four independent blockers
traced from source rather than assumed.

- **Category**: `schema-gap` (step vocabulary + cell catalog) **and**
  `provenance-gap` (chapter 53 carries no L0 id to cover). Nothing was worked
  around: no weakened scenario was authored, and the property is instead pinned as
  a kernel/loader test —
  `loader/src/test/kotlin/civictech/loader/B14ModulePromotionTest.kt`, alongside
  the host-classpath twin `kernel/src/test/kotlin/civictech/cell/evolve/ShadowPromotionTest.kt`.

- **The clause in dispute**: `doc/spec/50-development-process/53-evolution.md`
  §"The promotion swap" — PRECHECK → PREPARE → COMMIT → RETIRE, with the buffered
  window's guarantee that downstream observes no torn, duplicate or missing delta
  across the swap. Scenario B14 adds that the candidate may come from a **loaded
  module** (`[JAR1-SPAWN-04]`, `[JAR1-UNL-02]`).

- **Blocker 1 — the closed step vocabulary has no verb that swaps one instance for
  another.** `concord/schema/scenario.md` §"`script` — the step verbs" enumerates
  the complete set: `apply`, `quiesce`, `connect`, `disconnect`, `snapshot`,
  `restore`, `restart`, `despawn`, `read-state`, `retransmit`. None introduces a
  *second* instance of a cell at run time, and the schema's own header states that
  "growing the schema, the step/verb set, the check vocabulary, or the catalogs is
  a deliberate schema-change ticket between waves — not a corpus-authoring
  convenience". The nearest neighbour, `restart`, is explicitly ruled out by that
  same document: it "recovers a cell from its freshest available checkpoint" and
  the scenario "names no blob and no failure" — same class, same code, new epoch.
  A promotion is the opposite claim: a *different* implementation takes over the
  logical position, and the whole point of B14 is that downstream sees the
  behavioural difference without seeing a seam. (`Driver.spawn` does exist in the
  SPI — `concord/src/main/kotlin/civictech/concord/driver/Driver.kt:28` — but only
  as graph *construction*, driven from `graph.cells`; no step reaches it, so a
  scenario cannot name a candidate that was not in the topology from the start.)

- **Blocker 2 — the cell catalog has no membrane gate and no candidate pair.**
  `concord/schema/cell-catalog.md` carries sources, operators, views, and the
  specialised controls (`nature-gate`, `exclusive-source`/`-sink`, `feedback`,
  `journal`, `effect-sink`). There is no traffic-light / buffering membrane, so a
  scenario cannot express the red-drain-green window at all — and that window *is*
  the mechanism the requirement is about. Nor is there any pair of catalog ids
  standing in the incumbent/candidate relation (contract-identical outlet,
  observably different fold), which is what makes a swap detectable rather than a
  no-op.

- **Blocker 3 — chapter 53 has no L0 requirement id, so `covers:` would dangle
  (fatal lint).** `concord/schema/provenance.md` §3 makes a `covers:` id that
  matches no L0 requirement a build failure, and P6 makes an empty `covers:` an
  orphan — equally fatal. Measured on this branch:
  `grep -rnoE '\[5[0-9]-[A-Z]+-[0-9]+\]' doc/spec/` returns **nothing**, and the
  chapters present in `doc/spec/CONCORDANCE.md` are 12, 13, 15, 21, 22, 24, 33, 41
  and 42 only. So the bead's own premise — "the 53 promotion requirement it
  exercises" — does not yet exist: minting one is a W1-C-class spec edit
  (`provenance.md` §1, including the "checkable through the SPI" L0 gate, which
  blockers 1 and 2 currently fail), not something a corpus author may do on the
  way past.

- **Blocker 4, specific to B14's module half.** Even with 1–3 resolved, the
  *module-supplied* candidate is unreachable from any driver: `:concord` depends on
  `:kernel` alone (`concord/build.gradle.kts:11`), never on `:loader`, and only
  `civictech.concord.driver.kernel` may import `civictech.cell.*` at all. A
  conformance corpus is implementation-neutral by construction, so "the candidate
  class came from a jar this runtime loaded" is not a neutral claim it can state —
  which suggests that even a future promotion scenario would cover spec 53's swap,
  with B14's loader-provenance half staying a `:loader` test.

- **What was NOT done instead.** No easier scenario was substituted for the hard
  one: nothing was authored that promotes nothing, that swaps a cell for itself, or
  that reads "restart" as "promotion". No JAR1-local id was minted into `covers:`.
  No `53-*` id was invented in the spec to make a `covers:` line resolve. No
  catalog cell or step verb was added under cover of a corpus-authoring task.

- **Resolves**: a schema-change ticket that does all three of (a) mint EARS ids for
  `53-evolution.md` §"The promotion swap" that pass the L0 "checkable through the
  SPI" gate, (b) grow the step vocabulary with a promotion verb naming an
  incumbent, a candidate and the outlet being rebound, and (c) grow the cell
  catalog with a buffering-membrane gate plus an incumbent/candidate pair whose
  outlets are contract-identical and whose folds differ observably. With all three,
  author the scenario over the existing `observations-whole-waves` /
  `incremental-equals-batch` / `no-dead-letters` checks — which are adequate to the
  glitch-freedom claim once the swap itself is expressible — and delete this entry.

## KE1-F4 (the OR-map laws): the CONCURRENT halves of `[24-TMAP-03]` and `[24-TMAP-04]` are not scenario-statable (`script-model-gap` + `schema-gap`, `[KE1-37]`)

`computenet-j2x.4.3` authored one scenario per tagged-map law —
`24-TMAP-MERGE-01` (`[24-TMAP-01]`, dist convergence), `24-TMAP-PRESENCE-01`
(`[24-TMAP-02]`), `24-TMAP-LWW-01` (`[24-TMAP-03]`) and `24-TMAP-RESET-01`
(`[24-TMAP-04]`) — so all four rows of `doc/spec/CONCORDANCE.md` are covered.
Two of those four cover the law's **single-stream** half only. This entry records
what the other half would need, with the probe evidence, rather than letting the
covered rows imply more than they check. Nothing was weakened to make a row go
green: no golden was relaxed, no check dropped, and no scenario asserts an
interleaving it did not produce.

### Half A — `[24-TMAP-03]`'s concurrent value: **measured unsatisfiable**

The law: a key's exposed value is its live dot with the greatest `(counter,
sourceId)` order. On one source that order is file order (dot counters mint
monotonically per put, steps on one cell apply in file order), which is what
`24-TMAP-LWW-01` states and why its golden is legitimate. Across two replicas the
`sourceId` tiebreak decides, and **a scenario cannot name a sourceId** — source
identities are the implementation's, and `concord/schema/scenario.md` gives the
author no vocabulary for one (the closest, `retransmit`'s `source:`, names a
scenario-local *cell id* and is explicitly documented as carrying no claim about
the identity it resolves to).

Probe (run on `task/computenet-j2x.4.3`, `24-TMAP-LWWCONC-PROBE`, deleted after
measuring — two `ormap-source` replicas of one logical id, `put k1 from-h1` on
`r1` and `put k1 from-h2` on `r2` between the same barriers, golden
`final-view v1 = {k1: from-h1}`):

```
24-TMAP-LWWCONC-PROBE: check(s) failed on 8 of 20 run(s).
First failing run (0): final-view(v1): expected {k1=from-h1} but read {k1=from-h2}
```

Either golden loses on roughly half the schedule sweep. This is not a bug: the
sweep is quantifying over delivery orders, the dots' `(counter, sourceId)` order
differs run to run with the seeded driver, and the corpus rule is that a scenario
passes only if every check holds on **every** run (P2 — properties, never
traces). So the concurrent half has no expressible golden, and the only
order-independent statement left over that topology is convergence — which is
what `24-TMAP-MERGE-01` already asserts (`replicas-converge` +
`views-converge`, no golden).

### Half B — `[24-TMAP-04]`'s concurrent reset-remove: **passes, and is still a trace**

The law: `remove(k)` tombstones every dot *observed live* at `k`, so a concurrent
put's unobserved dot survives. `24-TMAP-RESET-01` states the sequential form (a
remove covers both of the key's live dots; a later put's fresh dot is not
covered). The concurrent form was probed in both issuance orders across two
replicas — `remove k1` on `r1` with `put k1 b` on `r2`, once with the remove
issued first and once with the put issued first, golden `{k1: b}` on both views —
and **both probes passed on 20 of 20 runs**.

It was still **not authored**, and that is the honest call rather than the
conservative one. What makes the probe pass is a fact about this driver, not
about the specification: the harness applies a script step into the kernel cell
before issuing the next, so `r1`'s remove computes its observed set at a moment
when `b`'s dot either does not exist yet or has not been gossiped in. The
specification grants an implementation the opposite freedom —
`concord/schema/scenario.md` §Script semantics puts delivery interleaving between
barriers entirely in the implementation's hands — and a conforming driver that
delivered `b`'s dot to `r1` before the remove applied would tombstone it
*correctly*, whereupon the golden `{k1: b}` is red against a correct
implementation. A conformance corpus may not ship a file that a second conforming
implementation can legitimately fail, so the passing probe is evidence about the
kernel driver's scheduling and not a check. (Same hazard `scenario.md` names for
`read-state`: a scenario "would be asserting an interleaving it never produced".)

### The missing capability (what would retire this entry)

Both halves need the same thing, which the corpus deliberately does not have: a
way for a scenario to **pin the observation relation between two replicas'
writes** — to state "this remove had, or had not, observed that put's dot when it
applied" — plus, for half A only, a neutral way to state a dot-order tiebreak
without naming an implementation's source identity. Neither exists in the closed
step and check vocabulary, and growing either is a deliberate schema-change
ticket between waves, which `computenet-j2x.4`'s decision **j2x.4-D4** explicitly
forbids this feature from taking (it is a parked question on epic
`computenet-j2x`, not a corpus-authoring convenience).

- **Resolves (half A)**: a step or descriptor that fixes replica dot order
  neutrally — e.g. a scenario-declarable total order over replica write
  identities that a conforming driver must honour when minting dots — after which
  the concurrent LWW golden becomes statable and the `24-TMAP-LWWCONC` probe
  above becomes a scenario.
- **Resolves (half B)**: a delivery-ordering primitive (a directed barrier:
  "deliver everything `r2` has emitted to `r1`, then continue", and its negation)
  so a scenario can construct a provably-unobserved concurrent put rather than
  relying on the harness's step-at-a-time application. With it, the passing probe
  above becomes an honest scenario and this half is deleted.

### Not a shortfall of this feature, recorded to stop the question recurring

- **Embedded `MergeablePayload` values** (spec 24 §Tagged maps, decided point 3)
  are not corpus-expressible at all: the neutral `Value` vocabulary has scalars,
  lists and maps, and no embedded-mergeable type. Those live as kernel tests under
  feature `computenet-j2x.1`, not here.
- **Null map values are avoided on purpose** in all four scenarios.
  `TaggedMapView.apply` can miss a null-valued put and leave the key present
  (`computenet-4d8k`), and a scenario using one would be asserting that behaviour
  as correct. The neutral vocabulary offers no null map value to state it with in
  any case, so this is a fence, not a limitation being worked around.
