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
unhandled") and the C-9 boundary; it is why `DUR-REPLAY-01` keeps the data-recovery path
(journaled/snapshot, `incremental-equals-batch`) and the effect-once path (`effect-count`)
as **two independent subgraphs**.

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
- **Not resolved by this entry**: `DUR-REPLAY-01`'s two-independent-subgraphs construction is
  untouched — the fold into one subgraph this entry's original "Resolves" bullet named as the
  natural consequence is a change to that scenario file, out of this ledger entry's scope.
  `[24-DUR-04]`'s emission-identity plane is now asserted head-on by `DUR-SRCID-01`/
  `DUR-SRCID-02`; its OR-set tag plane and wave-aligned-consumer plane are recorded
  separately under "Not covered" below.
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
