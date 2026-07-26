# Concord corpus disputes

Per CONCORD-PLAN §5: when a requirement cannot be checked honestly against the
current driver/kernel binding, that is filed here — never patched into a
scenario as a weakened check or a silently-omitted assertion. Each entry names
the scenario id, the requirement it would cover, the missing capability, and the
check to restore once the capability lands.

This file is the **consolidated worklist** for the W3 corpus wave — merged from
the three parallel corpus tickets (W3-1 propagation/consistency, W3-2 operators,
W3-3 ports/links/lifecycle/cycles/ownership/controls). Entries are organized by
scenario id and tagged with a category. The next ticket picks up the
`driver-wiring-gap` / `driver-bug` items first: they are cheap (the kernel
already has the capability, or the fix is a line or two of driver binding) and
each unblocks a real conformance check.

## Category index (worklist, cheapest first)

**`driver-bug` — a landed driver defect; fix and author the blocked scenario:**

- `24-OP-INTERSECT-01` — `KernelCatalog.inletName` collapses `intersect`'s
  `left`/`right` ports to a nonexistent `"inlet"` port; `intersect` is
  unconstructible from any scenario. One-line fix.

**`driver-wiring-gap` — kernel has the capability, driver doesn't wire the
descriptor param through; no schema or kernel change needed:**

- `13-LINK-REJECT-01` — `inlet-mode: single-writer` is inert; kernel gained the
  single-writer `FanInlet` (FU-6) but `KernelCatalog` never selects it.
- `22-WAVE-FANIN-01` — `glitch-free: true` is inert in `KernelCatalog.build`
  for every fan-in operator; kernel's `GlitchFreeCell` can wrap them. (Also
  carries a `schema-gap` for the wave-completeness check shape — see entry.)
- `22-GF-NESTED-01` (and pilot `22-GF-DIAMOND-01`) — the scalar `combine-latest`
  output is not wave-aligned; wrapping it in `GlitchFreeCell` is driver wiring,
  though a genuinely wave-aligned scalar combine binding may need constructing
  (borderline `kernel-gap` — see entry).

**`schema-gap` — a descriptor surface the frozen W0 schema does not expose;
needs a between-waves schema-change ticket:**

- `12-NEGOTIATE-01` — no `nature:`/`requires:` descriptor to drive a
  `PortNatures.stamp` mismatch; contract/nature refusal is unexpressible.
- `23-SPSC-01` — no `outlet-mode: exclusive` descriptor and no exclusive-payload
  catalog cell; exclusive-outlet fan-out rejection is unexpressible.
- `24-OP-WINDOW-01` / `24-OP-WINDOW-02` — no window-spec descriptor (also
  `kernel-gap`: no `window` cell binding — see entry).
- `22-WAVE-FANIN-01` — a "this observation is a complete wave" check shape is
  absent from the vocabulary (secondary tag; primary is `driver-wiring-gap`).

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
- `22-GF-NESTED-01` — a wave-aligned scalar combine may not exist in the kernel
  (`ScalarSumCombineCell` is quiescence-correct only). `kernel-gap`-adjacent.

---

## By scenario id

### `24-OP-INTERSECT-01` — **`driver-bug`**

- **Requirement**: `24-OP-INTERSECT-01`
  (`doc/spec/20-dataflow-semantics/24-data-cells.md`, "Operator library").
- **Defect**: `IntersectSetCell` is constructed correctly by
  `KernelCatalog.build("intersect", …)`, but link wiring is wrong.
  `KernelCatalog.inletName(targetType, scenarioInlet)` collapses
  `"union", "intersect", "quorum-set"` to a single hardcoded `"inlet"` port —
  correct for `union`/`quorum-set` (one collapsed fan-in port) but wrong for
  `IntersectSetCell`, whose contract has two distinct named ports `left` and
  `right` and no `"inlet"` port. Any scenario connecting two sources into an
  `intersect` fails at `connect` with `IllegalArgumentException: Inlet not found
  or not linkable: inlet on CellRef(...)`, regardless of how the scenario names
  its links. This contradicts the `cell-catalog.md` W3-0 "catalog-complete"
  claim for `intersect`. No scenario is authored (a scenario that always throws
  at `connect` is not a passing corpus entry).
- **Fix (one line, driver code —
  `concord/src/main/kotlin/.../driver/kernel/KernelCatalog.kt`)**: remove
  `"intersect"` from the `"union", "intersect", "quorum-set" -> "inlet"` branch
  and route it through the `left`/`right` branch alongside
  `combine-latest`/`join`/`semi-join`/`lookup-join`, i.e. `scenarioInlet ?:
  "left"`.
- **Author when fixed**: `24-OP-INTERSECT-01.yaml` — two set sources
  (`a`: add x,y,z; `b`: add y,z,w; then `a` removes y) into `intersect` on
  `left`/`right`, expecting `[z]` (design drafted and oracle-verified during
  W3-2).

### `13-LINK-REJECT-01` — **`driver-wiring-gap`**

- **Requirement**: `13-LINK-05` (a connect violating the inlet's admission
  policy — a second writer on a single-writer inlet — SHALL be `Rejected`).
- **Gap**: `CellSpec.inlet-mode: single-writer` (schema, CONCORD-PLAN exemplar
  (d)) round-trips through the schema and `CorpusRunner` forwards it into
  `spawn` params, but `KernelCatalog.build` never reads `params["inlet-mode"]`
  for any catalog cell — the param is inert. Every catalog inlet binds to a
  plain multi-writer `FanInlet`. The kernel now has an opt-in single-writer
  `FanInlet` (FU-6, strict point-to-point write side), so this is pure driver
  wiring, not a kernel gap. Verified: authoring exemplar (d) verbatim gives
  `Connected` for the second writer, not `Rejected`.
- **Fix**: wire `inlet-mode: single-writer` in `KernelCatalog`/the driver onto
  the single-writer `FanInlet` admission policy. Then author
  `13-LINK-REJECT-01.yaml` (CONCORD-PLAN exemplar (d)) with
  `{connect: {from: w2, to: sink}, expect: rejected}`.

### `22-WAVE-FANIN-01` — **`driver-wiring-gap`** (+ `schema-gap`)

- **Requirement**: `22-GF-01` (while a single-source wave is partially delivered
  across a fork-join, a glitch-free cell shall not expose derived state mixing
  pre-wave and post-wave inputs).
- **Gap (driver-wiring)**: `CellSpec.glitchFree` is plumbed into
  `CorpusRunner.params` (`cell.glitchFree?.let { put("glitch-free", ...) }`),
  but `KernelCatalog.build` never reads `glitch-free` for **any** catalog id —
  `union`, `intersect`, `quorum-set`, `join`, `semi-join`, `lookup-join` are all
  built as plain non-wave-buffered cells regardless of `glitch-free: true`. The
  kernel supports wrapping an arbitrary `Propagate<X>` in a `GlitchFreeCell`
  (see `kernel/.../GlitchFreeOperatorSuiteTest.kt`), so this is driver wiring
  the corpus cannot fix.
- **Gap (schema/check-vocabulary)**: even once wired, asserting "no observation
  mixes pre-wave/post-wave inputs" over a **set-shaped** observation stream
  needs a check the vocabulary lacks — `observations-all-satisfy(fn)` evaluates
  one element predicate per observation and has no notion of wave completeness.
- **Filed scenario**: `22-WAVE-FANIN-01.yaml` is authored (one source forked
  through two identity arms into a 2-of-2 `quorum-set`) but narrowed to
  `final-view` + `incremental-equals-batch`. Restore when `KernelCatalog.build`
  wraps a fan-in set operator in `GlitchFreeCell` on `params["glitch-free"] ==
  true` **and** a wave-completeness check shape lands.

### `22-GF-NESTED-01` — **`driver-wiring-gap`** (borderline `kernel-gap`)

- **Requirement**: `22-GF-02` (glitch-freedom composes across nested/chained
  fork-joins).
- **Gap**: the kernel driver's only `combine-latest` binding is
  `ScalarSumCombineCell` (`KernelAdapters.kt`) — order-independent at quiescence
  but explicitly **not** wave-aligned (intermediate, non-multiple-of-4 sums may
  be observed mid-wave); `combine-latest` with any `fn` other than `sum` throws
  `UnsupportedCatalogBinding`. Wrapping the scalar output in `GlitchFreeCell` is
  driver wiring, but a genuinely wave-aligned scalar combine (version-buffered,
  evaluating once per completed wave) may need constructing in the kernel first —
  hence borderline `kernel-gap` (cell-catalog.md "the two honest gaps", gap 1).
- **Filed scenario**: `22-GF-NESTED-01.yaml` (double-diamond: two inner diamonds
  into an outer combine) is authored but narrowed to `final-view` only.
  Disabled check to restore verbatim:
  ```yaml
  - {type: observations-all-satisfy, view: v, fn: mod-eq(4,0)}
  ```
- **Also restore**: pilot `22-GF-DIAMOND-01.yaml` narrows the same way one
  nesting level up; its disabled check:
  ```yaml
  - {type: observations-all-satisfy, view: v, fn: even}
  ```
- **Restore when**: a `combine-latest` binding wraps (or replaces
  `ScalarSumCombineCell` with) an actually wave-aligned scalar combine.

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
