# Concord scenario schema — v1

The human-facing reference for the L1 scenario language (CONCORD-PLAN §1.2). One
YAML document = one scenario. The serialization types live in
`concord/src/main/kotlin/civictech/concord/schema/`; this document is the
contract the testing agents author against. **Growing the schema, the step/verb
set, the check vocabulary, or the catalogs is a deliberate schema-change ticket
between waves — not a corpus-authoring convenience** (CONCORD-PLAN §4 seam rule,
P5).

Companion references:
- `cell-catalog.md` — the neutral cell ids usable as `type:`.
- `function-catalog.md` — the pure-function ids usable as `fn:` / predicate args.
- `provenance.md` — EARS id scheme (`covers:`) and the concordance format.

## Top-level document

```yaml
id:        24-OP-UNION-01          # unique scenario id (§ chapter prefix + slug + nn)
title:     Union of two set sources equals batch union
covers:    [24-OP-03, 21-PROP-01]  # ≥1 EARS requirement id (P6); empty = orphan (lint)
profile:   core                    # core | dist | dur
kind:      example                 # example | generative | control
narrative:                         # optional; BDD prose for humans + concordance
  given: ...
  when:  ...
  then:  ...
graph:     { cells, links, hosts? } # the topology as data (omit for kind: generative)
script:    [ ... ]                  # ordered steps (see below)
checks:    [ ... ]                  # closed check vocabulary (see below)
generator: { ... }                 # only for kind: generative
expect-failure: { ... }            # REQUIRED for kind: control, rejected otherwise
runs:      20                      # optional; schedule-sweep run count (default 20)
```

- **`profile`** gates optional capability (P9). `core` runs in every build; `dist`
  and `dur` are conformance levels a second implementation adopts wholly or not.
- **`kind: control`** scenarios carry deliberately wrong expectations and MUST
  fail; the runner asserts their failure (P7), and asserts that it is *the
  declared* failure — see `expect-failure` below.

## `expect-failure` (kind: control only)

```yaml
expect-failure:
  check: observations-all-satisfy         # the type: id of the declared check that must fail
  message-contains: fails the predicate   # a substring of the message it must fail with
```

| field | meaning |
|---|---|
| `check` | the `type:` id of the check that must fail; must name a check this scenario's `checks:` list actually declares |
| `message-contains` | a substring the failing check's message must contain — the *reason*, discriminating the provoked failure from every other way that same check can fail |

**Required on every `kind: control`, and rejected on every other kind.** A
control is a negative scenario: it asserts that the harness *detects* one
specific violation. "At least one declared check failed" is not proof of that. A
control whose check starts failing for an unrelated reason — a vacuity guard
firing on an empty observation log, an evaluator reporting a missing view, an
oracle that cannot model the graph — keeps failing, keeps satisfying a `!passed`
assertion, and has silently stopped covering anything, which from outside is
indistinguishable from the control still working. `CTL-GF-01` came within one
empty observation log of exactly that (computenet-qaz / computenet-dqy.18), so
the runner asserts the declared check **and** the declared reason, across every
run of the sweep: at least one run must fail, and every failure recorded on any
run must match: one wrong-reason failure is RED, even beside a sibling run that
failed correctly.

Choosing `message-contains`: pin wording that only the provoked failure produces,
never the check's own name. `fails the predicate` distinguishes
`observations-all-satisfy`'s torn-value failure from its empty-log guard;
`observations-all-satisfy` would match both. Where the message is deterministic
across the sweep, pin the values too (`v1=[2,4] but v2=[1,3]`), so the control
cannot drift into failing over a state it was never written about; where the
schedule varies the message (an event index, an observed value), pin only the
stable part.

On any other `kind` the block would declare a failure the runner never asserts —
every check on an example or generative scenario must PASS — so declaring it
there is an error, not a harmless annotation.

Both halves of that pairing are checked for **every** corpus file the runner
discovers, before the `profile:` filter is applied — so a `dist`/`dur` control
missing its declaration is caught by the `core`-only fast loop too, rather than
only by whichever build happens to activate its profile.

## `graph`

```yaml
graph:
  hosts: [h1, h2]                  # optional; only dist-profile scenarios place cells
  cells:
    - {id: a, type: set-source, of: string}
    - {id: u, type: union}
    - {id: v, type: set-view}
  links:
    - {from: a, to: u, inlet: left}      # inlet/outlet default to "inlet"/"outlet"
    - {from: u, to: v}
```

### Cell descriptor params

`type` is a **cell-catalog id** (`cell-catalog.md`). Every other field is an
optional descriptor param the driver binds. The v1 named params:

| param | meaning |
|---|---|
| `of` | element/scalar type hint (`string`, `int`) |
| `fn` | pure-function id (filter/map/join/group-by cells) — see `function-catalog.md` |
| `agg` | aggregator id (`count`\|`sum`\|`min`\|`max`) a `group-by`/`partition` folds each group with — see `function-catalog.md`. **Optional, default `count`** (W3-0) |
| `k` | the `k` of a `quorum-set`'s k-of-n admission — an element is emitted once `k` of the `n` live source links assert it. **Optional, default `n`** (all live sources ⇒ an intersection) (W3-0) |
| `glitch-free` | request wave-aligned semantics on a fan-in cell (`true`) |
| `inlet-mode` | inlet admission policy (`single-writer`, `fan-in`) |
| `host` | host placement (dist profile) |
| `replica-of` | logical replica-group id (dist profile) |
| `interest` | interest-scoped instance-set assignment (dist profile) — see below |
| `window` | window descriptor for a `window` cell (`{kind: tumbling\|sliding, size, slide?}`) — see below |

`agg`, `k`, and `window` are **additive** (W3-0 / R2-B): existing files deserialize
unchanged (all optional; `window` absent on every non-`window` cell). The parser
stays lenient — an unknown key is ignored — so promoting a further param to a
typed field remains a schema-change ticket.

#### `window` (R2-B, `24-OP-WINDOW-01`/`-02`)

Spec 24 §Grouped aggregation, M11.6: **"windowing = key derivation"** — there is no
wall clock, so a window is an explicit function of an integer event-time/sequence
field carried on the element, never of arrival order or wave id. A `window` cell's
elements are `[at, value]` pairs (`at` the event-time/sequence field, `value` the
payload — the same `[k, v]` convention `join`/`group-by` use); its `agg` field
(above) folds each window's value components exactly as `group-by` does.

```yaml
- {id: w, type: window, agg: sum, window: {kind: tumbling, size: 10}}
- {id: w, type: window, agg: sum, window: {kind: sliding, size: 10, slide: 5}}
```

| field | meaning |
|---|---|
| `kind` | `tumbling` — one composite window-start key per element; or `sliding` — the element expands into every window of `size` it falls in, `slide` apart, then groups |
| `size` | the window length, in event-time/sequence units (no wall clock) |
| `slide` | the hop between successive window starts; **required** for `sliding`, ignored for `tumbling` |

Windows never close (`24-OP-WINDOW-02`): a late element is an ordinary add and a
retraction flows into the window aggregate exactly as any other `group-by` view —
there is no eviction, no timer, no watermark (deferred with trigger, spec 24).

#### `interest` (W4-A followup, `42-INTEREST-01`)

A `replica-of` cell may additionally declare an interest-scoped instance-set
assignment (spec 40/42 §Interest-scoped instance sets) — the demand predicate the
kernel's gossip linker consults to decide whether a link forms between two
replicas of the same logical id, and to filter each emission to the target's
interest. Absent ⇒ the kernel default, `Interest.Total` (plain replication,
byte-identical to a `replica-of` cell with no `interest:`).

```yaml
- {id: r1, type: set-source, of: string, host: h1, replica-of: shared, interest: {slots: [0], total-slots: 2}}
```

A small, closed neutral grammar mirroring the kernel's `Interest` algebra —
exactly one of these per cell:

| form | meaning |
|---|---|
| `{total: true}` | every key, every delta (the replication setting; same as omitting `interest:`) |
| `{empty: true}` | no key, no delta |
| `{slots: [..], total-slots: N}` | a hash-slot subset out of `N` slots (the partitioning setting when two instances' slot sets are pairwise disjoint) |
| `{ranges: [[lo, hi], ...]}` | half-open `[lo, hi)` integer ranges over a numeric key |

The driver (`KernelDriverDist.spawnReplica`) parses this into a real
`civictech.cell.link.Interest` and calls `LocationRegistry.setInterest` **before**
the replica joins the replication mesh — matching the ordering
`InterestScopedGossipTest`'s own harness uses ("assign, then replicate").

The parser runs **lenient** (unknown keys ignored) so a future param does not
break older files; promoting a new param to a typed field is a schema-change
ticket.

### Link params

`{from, to, inlet?, outlet?, role?}`. `role` selects consume vs observe
(23-ownership). Endpoints are cell `id`s.

## `script` — the step verbs

The step model is **verb-complete** for the whole corpus. **Canonical YAML is a
`type`-discriminated map** (kaml native sealed polymorphism, discriminator key
`type`). The illustrative sugar in CONCORD-PLAN §1.2 (bare `quiesce`,
`connect:`-keyed maps) is *not* the wire form — author the canonical form below.

| verb | YAML | driver verb |
|---|---|---|
| apply | `{type: apply, on: a, op: add, value: apple}` (also `times: N`) | `apply(cell, op)` |
| quiesce | `{type: quiesce}` (also `budget: N`) | `quiesce(budget)` barrier |
| connect | `{type: connect, from: s, to: late, inlet?, outlet?, role?, expect?}` | `connect(...)` |
| disconnect | `{type: disconnect, from: s, to: late, inlet?, outlet?, expect?}` | `disconnect(linkRef)` |
| snapshot | `{type: snapshot, on: c, as: blob1}` | `snapshot(cell)` |
| restore | `{type: restore, on: c, from: blob1, host?}` | `restore(host, cell, blob)` |
| restart | `{type: restart, on: s}` | `restart(cell)` |
| despawn | `{type: despawn, on: c}` | `despawn(cell)` |
| read-state | `{type: read-state, on: s, limit: 2}` | `readState(cell, cursor, limit)`, looped to completion |
| retransmit | `{type: retransmit, on: c, inlet?: in, source: s, counter: N, op: add, value?: apple, baseline?: {s: N}}` | `retransmit(cell, inlet, source, counter, op, value?, baseline?)` |

- **`op`** is a neutral op verb the cell catalog defines (`add`, `remove`, `put`,
  `remove-key`, `increment`, `decrement`, …).
- **`value`** is a JSON-shaped [value](#value-model); omit for value-less ops
  (`increment`).
- **`times`** repeats the op (drives long wave streams, e.g. `increment` ×50).
- **`expect`** on connect/disconnect pins the construction-time result:
  `connected` (default) or `rejected` (§1.2 exemplar (d), 13-LINK-REJECT).
- **`snapshot … as`** names a scenario-local blob handle a later `restore … from`
  consumes; `restore … host:` re-materializes on another host (migration/durability).
- **`read-state … limit:`** is the per-page cap on **whole entries**; optional,
  default 200.
- **`retransmit … source:` / `counter:`** name the explicit `(sourceId, counter)`
  wave position the injected delivery carries — see below.
- **`retransmit … baseline:`** is optional; present, it makes the delivery a
  **catch-up baseline** rather than an ordinary live frame — see below.

#### `restart` (D-C12, spec 21 §RESTART re-baselines / spec 30/31 rule 5)

A **restart** recovers a cell from its freshest available checkpoint and
reconciles its downstream consumers with the recovered state. It is not
`restore`: `restore` re-materializes a cell from a blob the scenario captured
with `snapshot` — a state-plane operation with no downstream announcement, which
is what a despawn/migration/durability scenario wants and exactly what a restart
must not be.

**What a conforming driver must do.** Three things, all boundary-observable:

1. the cell's state reverts to the recovered checkpoint;
2. the cell's outlets **succeed their emission epochs** — no post-restart wave
   position or merge tag aliases a pre-restart one (spec 20/22 §Source identity);
3. the recovered state is re-announced downstream over the ordinary catch-up
   path, carrying the superseded epochs, so a convergent consumer drops what the
   restart did not re-assert and rejects later deltas stamped by the superseded
   epochs (spec 20/24 §Tag continuity).

The scenario names no blob and no failure: **which** checkpoint is freshest
(durable tail, imported baseline, peer catch-up, or the local one) and **how**
the restart is induced are the implementation's, and neither is asserted. Only
the reconciliation is — which is what `[21-REBASE-01]` states.

**Restarts and `no-dead-letters`.** A restart is a failure event, and spec 30/31
rule 5 requires the failure to be reported observably under every supervision
policy ("observability is not a policy"). A scenario driving a restart therefore
**cannot** also assert `no-dead-letters` — the report is required, not a defect.
Say so in a header comment rather than dropping the check silently; the check
vocabulary has no dead-letter *count*, and growing it for this would be a schema
change no requirement asks for.

The catalog source that witnesses a restart is `rebaseline-source`
(`cell-catalog.md`), not `set-source`: a replay-stable tag source cannot exhibit
epoch succession, which is half of what the requirement is about.

#### `read-state` (V1C-CONCORD, spec 21 §Pull / spec 24 §Required next steps)

A **bounded state read** of a cell's own state — the read an instrument makes,
as distinct from `snapshot` (an opaque blob captured for a later `restore`,
which no check can inspect) and from `readView` (a *view* cell's settled fold,
which exists only where the scenario linked one). It is answered without
emitting, without linking and without moving the cell's wave plane, and it is
bounded in size, so reading a large cell is affordable.

**A step is a whole walk, not a page.** Cursor threading is the driver's, not
the scenario's: the harness calls `readState` until the page it returns carries
no resume token, and records the walk for the two read-side checks below. So a
scenario cannot express a *partial* walk, an abandoned one, or one interleaved
with an operation. That is deliberate — the script model has no way to order a
mutation against a page boundary (steps on one cell apply in file order, and a
walk is one step), so a scenario that appeared to test a mid-walk mutation would
be asserting an interleaving it never produced.

Sweeping `limit` across several `read-state` steps on one cell is how a scenario
probes page-boundary behaviour; every recorded walk is checked, so one check
entry covers the whole sweep (`24-BOUND-02`).

#### `retransmit` (KFX followup, `computenet-yh6.1.3.3` + `computenet-yh6.1.8`)

**Status.** Landed, both halves. The gated schema change was approved in
principle (human answer, 2026-08-10, on `computenet-yh6.1.3.3`), and this
subsection — which freezes the verb's shape and semantics — was the
single-writer review of `concord/schema/scenario.md` itself. The matching
`@SerialName("retransmit")` `Step`, the `Driver` SPI verb, the
`civictech.concord.driver.kernel` binding and the `CorpusRunner` dispatch arm
followed under `concord/src/` in `computenet-yh6.1.8`, per D-C12's rule that a
step verb's seams move together or the module does not compile. Two corpus
scenarios drive it: `DUR-LIVE-01` (the live half of `[24-DUR-05]`) and
`DUR-CKPT-FRONTIER-01` (the checkpoint-frontier half of `[24-DUR-02]`).

**One driver-capability note.** Which cells can receive a duplicate is a driver
capability like any other. The kernel binding admits an `effect-sink` target
only: an effect boundary reads a delta's added *elements* and decides on the
message context, both of which a `retransmit` states, whereas re-delivering to a
tag-algebra fold would additionally need the original delivery's tag identity —
which a scenario does not name and the binding will not fabricate. Every other
target is a loud refusal, never a silently weaker delivery.

**What it is for.** No other verb in the closed vocabulary re-delivers an
already-processed invocation to a *running* host — every other path to a
duplicate goes through `recoverFrom` journal replay. Two requirements needed
exactly that live half, and could not be corpus-expressed without it:

- `[24-DUR-05]` (`doc/spec/20-dataflow-semantics/24-data-cells.md:815-825`,
  BS-11/KFX-05): the `Effectful` processed-frontier guard suppresses "an
  invocation … encountered during `recoverFrom` replay **or post-recovery live
  delivery**." Only the replay half was corpus-covered (`DUR-REPLAY-01`,
  `DUR-SRCID-01`, `DUR-SRCID-02`); the live half was proven only by a kernel
  test,
  `kernel/src/test/kotlin/civictech/cell/durability/EffectfulLiveDeliveryTest.kt`
  (`computenet-yh6.1.3.2`). `DUR-LIVE-01` now carries it in the corpus. (Not to
  be confused with `concord/corpus/DISPUTES.md`'s "second boundary", which is a
  *different* `[24-DUR-05]` residual — a frame carrying no `MessageContext` at
  all, so it has no frontier position to be judged by. That one this verb does
  not resolve: a retransmit states a position. Its `Resolves` is
  `computenet-yh6.1.3.5`'s crash-stable ingress identity.)
- `[24-DUR-02]`'s checkpoint-atomicity claim, frontier half: `DUR-ATOMIC-01`'s
  perturbation sweep found that deleting `CheckpointRecord.frontier` on restore
  changes nothing observable, because compaction removes exactly the frames a
  checkpoint's frontier would have suppressed, and every frame that *is*
  replayed carries its own `RECORD_FRONTIER` record. Discriminating it needs "an
  upstream that survives the crash and re-delivers a frame whose `(sourceId,
  counter)` is at or behind the checkpoint frontier — a duplicate live
  delivery, not a replay" (`concord/corpus/DISPUTES.md`, "the third boundary").
  Filed there as this same verb's second consumer, and now built as
  `DUR-CKPT-FRONTIER-01`: it checkpoints with **no journal tail after it**, so
  the checkpoint's own frontier copy is the only thing that can suppress the
  duplicate. Dropping `record.frontier` in `restoreCheckpoint` is a failing
  perturbation there, and green everywhere else — which is what the third
  boundary asked for.

**Shape, and why it is the explicit-position form.** The alternative considered
was a verb that re-sends a *remembered* prior invocation (the driver retains a
log and a scenario names an earlier step by reference). This form is rejected:
it would require every driver binding to retain invocation history the neutral
model does not otherwise ask for, and it could not state the coordinate being
duplicated in the scenario itself. Instead `retransmit` states everything a
conforming driver needs inline, so no driver-side memory of prior deliveries is
required:

- **`on`** is the target cell — the `Effectful`-guarded cell whose inlet
  receives the injected delivery. Same convention as `restart`/`despawn`/
  `snapshot`'s `on`: it names the cell under test, not a producer.
- **`inlet`** selects which inlet receives it; optional, default `"inlet"` (the
  same default `connect`/`disconnect` use).
- **`source`** names the *scenario-local cell id* whose per-source wave
  identity this delivery carries — typically the same source an earlier
  `apply` step used to produce the invocation being duplicated. The driver
  resolves it to that cell's real per-source identity (spec 20/22 §Structural
  changes: "wave ids are per-source monotonic counters, minted by the emitting
  outlet") — exactly the identity an ordinary delivery from that source would
  stamp, so the retransmit is indistinguishable, at the frontier, from a
  genuine second arrival of the same message.
- **`counter`** is the integer position, within `source`'s monotonic sequence,
  this delivery claims. To construct an actual duplicate — one the processed
  frontier has already recorded — a scenario names the same `(source,
  counter)` an earlier `apply` from that source already produced.
- **`op`** / **`value`** are exactly `apply`'s fields: the payload this
  (re)delivery carries. `value` is omittable for value-less ops, as `apply`'s
  is.

**What it is not.** Not `apply`: `apply` drives an op through a cell's own
outlet along the graph's existing links, and the driver mints the next wave
position for that outlet in sequence. `retransmit` injects directly at a named
inlet under an **explicit** position, bypassing the graph's routing entirely —
the same bypass `EffectfulLiveDeliveryTest` uses (a direct proxy call wrapped
in an explicit `MessageContext`, rather than a live outlet link), because a
genuine duplicate delivery is a re-arrival of the same message, not a second
op newly driven through the topology. Not `restart` or `restore`: neither the
target's state nor its checkpoint is touched, and nothing is recovered — only
whether its `Effectful` processed-frontier suppresses this one delivery is at
stake. No `times:` — a `retransmit` step is one specific duplicate; repeating
one means another `retransmit` step, since each must name its own `(source,
counter)`.

**`baseline:` — the optional catch-up anchor** (second gated schema change to
this verb, `computenet-yh6.1.12`).

*Status.* Landed. This paragraph is the single-writer review of the extension;
the matching `RetransmitStep` field, `Driver` SPI parameter,
`civictech.concord.driver.kernel` binding and `CorpusRunner` dispatch moved with
it in the same ticket, per D-C12's rule that a step verb's seams move together.
Two corpus scenarios drive it: `DUR-BASELINE-01` (`[24-DUR-07]`) and
`DUR-BASELINE-02` (`[24-DUR-08]`).

*Why the verb had to grow.* Spec 24 §Effectful gives a frame carrying a
**catch-up baseline** (`MessageContext.baseline`, 93 I-24) its own rule: the
sink ACTS on it, its timestamp NEVER advances the processed-frontier, and its
exact position is recorded separately so a replay or a live re-delivery of that
position is suppressed without re-firing. `concord/corpus/DISPUTES.md`'s "fourth
boundary" established, with file:line evidence, that **no** driver path in any
profile ever stamped one: the pull that mints a baseline is answered by
`FanOutlet.baselineTo` from `pullServe`'s `StateRequest` handler, which no
concord driver issues; push catch-up on link install is explicitly not
baseline-stamped; and the `dur` profile's `effect-sink` edge is a raw
`outlet.subscribe` that bypasses link admission. The same entry named this
extension as the narrowest unblocking route, over the materially larger
alternative of rewiring `effect-sink` through real link admission plus a pull.

*Shape.* `baseline:` is a **merge-tag frontier**: scenario-local **cell ids**
mapped to tag counters, e.g. `baseline: {source: 4}`. Each id is resolved by the
driver exactly as `source:` is — to that cell's own per-source identity — so a
scenario never invents an implementation identifier, and the same file anchors
at the same frontier on every run. A cell the driver does not hold, or one with
no outlet identity to anchor on, is a **loud refusal**, like every other
capability refusal on this verb.

*Optional means unchanged.* Omitted — the default — the step is byte-for-byte
what it was before this field existed: `MessageContext(position, sourcePort)`
with `baseline` at its `null` default, an ordinary live duplicate. `DUR-LIVE-01`
and `DUR-CKPT-FRONTIER-01` are unmodified by the extension and still pass, and
`RetransmitBindingTest` pins the contrast directly — the same position delivered
with and without an anchor, where only the anchorless one advances the frontier.

*What it does NOT assert.* The anchor's **contents** are observed by nothing. A
conforming receiver keys on the frame's *kind* (a baseline is present) and on
its `(source, counter)` position; no check in the corpus reads a tag counter,
and none may be authored as though it did. Stating the counters is what makes
the frame well-formed and the run reproducible — not a claim about merge-tag
currency or incremental-pull dedup. A scenario needing THAT would need a real
`StateRequest` path, which is the route this extension deliberately did not
take.

**No new check is needed.** The existing keyed `effect-count` (`{type:
effect-count, sink: s, key: k1, exactly: 1}`) already states "this key fired
exactly once even though it was delivered twice," and `no-dead-letters` is
compatible with a scenario using this verb — unlike `restart`, a suppressed
retransmit is not a failure event: the guard's live suppression path discharges
the invocation's payload and counts the suppression, without dead-lettering it
(`ManagedHost.kt:848-865`, KFX-20). A scenario built on `retransmit` needs no
schema growth beyond the step itself.

### Script semantics (normative, all drivers)

Steps targeting the **same** cell apply in file order. Steps on **different**
cells are concurrent unless separated by a `quiesce` barrier. Delivery
interleaving between barriers is implementation freedom — the schedule sweep
(default 20 runs) quantifies over it; a scenario passes only if all checks hold
on **every** run (P2 — properties, never traces).

## `checks` — the closed check vocabulary

`type`-discriminated, same as steps. The declarative forms here map 1:1 to the
executable evaluators in `civictech.concord.check` (§1.4).

| check | YAML | semantics |
|---|---|---|
| final-view | `{type: final-view, view: v, equals: <value>}` | at quiescence `readView(v)` equals the golden |
| views-converge | `{type: views-converge, views: [a, b]}` | all listed views equal at quiescence |
| incremental-equals-batch | `{type: incremental-equals-batch, view: v}` | view equals the harness batch oracle (catalog semantics over the accepted-op multiset) |
| late-join-equals-early | `{type: late-join-equals-early, early?: e, late?: l}` | late- and early-linked folds equal |
| observations-all-satisfy | `{type: observations-all-satisfy, view: v, fn: even}` | every observation-stream event satisfies a catalog predicate |
| observations-monotone | `{type: observations-monotone, view: v, order?: ...}` | stream never regresses under the order |
| replicas-converge | `{type: replicas-converge, logical: shared}` | all live replicas of the logical id hold equal folds (dist) |
| no-dead-letters | `{type: no-dead-letters}` | zero dead letters across all hosts |
| effect-count | `{type: effect-count, sink: s, key?: k, exactly: 1}` | effectful sink acted exactly N times per key (dur) — unkeyed, per key the script *fed* it (see below) |
| observations-whole-waves | `{type: observations-whole-waves, view: v, source: a}` | every observation equals the source's fold at some whole op prefix (no torn fork-join) |
| wave-plane-unchanged | `{type: wave-plane-unchanged, cell: s}` | every `read-state` walk on `s` left `s`'s wave plane exactly where it found it |
| pages-equal-view | `{type: pages-equal-view, cell: s, view: v}` | every `read-state` walk on `s` was stamped, non-duplicating, and unions to `v`'s fold |

Inline construction-time expectations use `expect:` on the `connect`/`disconnect`
step, not a check entry.

### `effect-count`: what the unkeyed form quantifies over

An unkeyed `effect-count` asserts the count for **every key the script fed the
sink**, not merely every key the sink's log happens to contain. The two differ in
exactly one direction and it is the load-bearing one: an element that fired *zero*
times is absent from a grouping of the log, so quantifying over the log alone
passes over it vacuously — the check would see double-fires and be blind to silent
effect **loss**, which `24-DUR-04`/`24-DUR-05` regressions land on just as often
(computenet-61w; demonstrated on a first cut of `DUR-SRCID-02`, which passed with
the mechanism it was written to pin disabled).

The expected key set is derived from the scenario: the elements of every `add` the
script applied to a source the graph links **straight into** the sink (`remove`
contributes nothing — the effect fired at the `add`, and retracting the element
neither drives a new one nor unmakes the recorded one). The derivation is narrow on
purpose, and where it does not hold the unkeyed form **fails with a message asking
you to name the keys** rather than silently weakening to the log-only reading. A
*partial* derivation would be that same weakening, so the refusals are stated over
the sink's whole upstream **cone** — every cell that transitively reaches it.

The derivation applies to **one shape only**: every cell linked straight into the
sink is a `set-source` or `journal-set-source` whose scripted `add`s are its only
input, linked in once, and the sink is declared `effect-sink` (whose contract is one
effect per delivered added element, keyed by the element). Anything else refuses:

- the sink's or a direct upstream's declared **`type:`** is outside that list — a
  `map` re-keys, a `filter` drops, a join pairs, a view folds, a `rebaseline-source`
  re-announces, so what it puts into the sink is a *transformation* of the script's
  adds that a one-hop derivation cannot name. This is what refuses a **diamond**
  (`src → sink` *and* `src → mid → sink`): no `apply` targets `mid`, so the in-cone
  rules never fire on it, yet the keys it should have driven are unnameable
  (computenet-61w.1);
- a direct upstream is itself fed by a link, or the same feeder is linked into the
  sink twice, so its emissions are not one-per-scripted-add;
- the topology into the cone moves mid-script (`connect`/`disconnect` whose `to` is
  in the cone);
- any cell in the cone is `despawn`ed, `restart`ed or `restore`d;
- an `apply` targets a cell in the cone that is *not* a direct upstream;
- a direct upstream takes an op outside `add`/`remove`, an `add` with no `value:`, an
  `add` with `times:`, or an `add` of an element **already added** (including a
  re-add after a `remove`). Each of those fires once *per add*, and one `exactly:`
  states a single count for every derived key, so "twice for `k1` and once for the
  rest" is not expressible — name the keys instead.

`exactly: 0` needs no derivation — it asserts the log is empty.

**`snapshot` is deliberately not a refusal, `restart` and `restore` are.** A
`snapshot` is a pure read of the cell it names and cannot change which elements
reached the sink, so `DUR-SRCID-02` and `DUR-ATOMIC-01` snapshot a direct upstream
mid-script and keep resolving. A `restart` re-baselines and re-announces, and a
`restore` re-materializes the cell from a blob that need not be its own — a restore
can therefore feed the sink an element that no `add` on a direct upstream ever
named, which is a vacuous pass, not merely an imprecise one (measured in
computenet-61w.1). Both refuse anywhere in the cone.

A keyed `effect-count` is unconditional and always available: `{type: effect-count,
sink: s, key: k4, exactly: 1}` fails with `observed 0` when `k4` never fired. Use it
for any sink shape the derivation gives up on, and for the specific elements a
scenario's narrative singles out.

### What a conforming driver must observe (the two read-side checks)

A check is only a conformance check if a **second, non-kernel** implementation
could evaluate it from the specification alone. The two checks added with
`read-state` (V1C-CONCORD) each require one observation beyond the existing
verbs, and both are stated here in the spec's vocabulary, not any
implementation's.

**`wave-plane-unchanged`** requires the driver to report, for a named cell, the
**wave plane that cell has reached**: for every wave source visible at that
cell, the position `(source, counter)` that source's wave sequence has advanced
to there. This is the model's own clock — spec 20/22 §Structural changes: "wave
ids are per-source monotonic counters, minted by the emitting outlet" — and any
implementation of this specification already maintains it, because it is what
stamps a delivery and what decides wave completeness. The check asserts nothing
about the *values*: source handles are opaque and the only assertion is that two
readings, one immediately before a bounded read and one immediately after, are
**equal**. Since every delivery carries a freshly minted position, a plane that
did not move is a delivery that did not happen — which is what `[21-PULL-02]`
asks. Nothing about scheduling, threading, frames or internal identifiers is
observed, and a driver that reported a constant plane would fail the corpus
rather than pass it, because the very same scenarios prove other requirements
that a real emission must move it for.

*Why not a downstream consumer's stream instead.* It looks more direct and it is
not honest: a materialized view's stream is published off the producing cell's
execution context, so its length immediately after a read states something about
notifier timing, not about the graph. A scenario that also wants the settled end
state pinned adds a `final-view`, which is a statement about state rather than
about timing.

**`pages-equal-view`** requires the driver to report each page of a bounded read
as **whole entries in the neutral value model** — an entry's key, the value the
state associates with it (absent where the key *is* the state, as in a set), and
whether the entry contributes to the cell's current state — plus an opaque
**frontier stamp** per page whose only asserted property is equality with
another stamp from the same walk. A convergent state family pages entries its
own algebra has retracted (a tombstoned set element is a real entry with a real
tag set), which is why an entry says whether it is live rather than the driver
silently filtering: the check compares a *walk* against a *fold*, and only the
live entries were ever in the fold.

The equal-stamp requirement is `[21-PULL-03]`'s antecedent, asserted rather than
assumed. A walk whose stamps differ is a *smeared* read for which the union is
claimed to equal nothing at all, so the check reports it as a failure instead of
passing on a vacuously false antecedent.

Both checks **fail** when the scenario recorded no `read-state` walk on the
named cell, or when a recorded walk returned no page. "Nothing was observed"
must never read as "the property held" — these are the two checks in the
vocabulary that are trivially satisfiable by not doing anything.

## `generator` (kind: generative)

Stands up random pipelines instead of a fixed `graph` (§1.2 exemplar (f)); harness
support lands in W4-C. W0 freezes the shape:

```yaml
generator:
  pipeline-depth: [1, 4]
  vocabulary: [set-source, filter, map, union, intersect, join, group-by, count-view]
  ops: 200
  late-joiner: true
  instances: 100
```

Note: the generative `view: '*'` wildcard form for checks is a deferred W4-C
schema extension; fixed scenarios name views explicitly.

## Value model

Golden expectations (`equals:`), op payloads (`value:`), and `readView` returns
are the neutral JSON-shaped `Value` (`civictech.concord.value.Value`): scalars,
arrays, objects only — no Kotlin types (P5). YAML scalar typing follows the YAML
core schema and is widened **int → real → bool → string**, so `100` is an integer
golden, `100.0` a real, `true` a boolean, `apple` a string, `[pear, plum]` a list.
A golden of `100` does **not** equal `100.0` (integer folds stay integer).

## Discriminator convention (why `type:`)

kaml's native sealed-class polymorphism keys on a `type` property, giving a clean,
round-trippable form with no custom step/check serializers. Only the free-form
`Value` needs a bespoke serializer (test-source `ValueYamlSerializer`), because a
golden can be a scalar, a list, or an object under one field. The YAML front end
(kaml) is test-scope — all scenario parsing happens in the runner (a JUnit
harness); `main` carries only the pure data types.
