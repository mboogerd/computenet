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
runs:      20                      # optional; schedule-sweep run count (default 20)
```

- **`profile`** gates optional capability (P9). `core` runs in every build; `dist`
  and `dur` are conformance levels a second implementation adopts wholly or not.
- **`kind: control`** scenarios carry deliberately wrong expectations and MUST
  fail; the runner asserts their failure (P7).

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
| despawn | `{type: despawn, on: c}` | `despawn(cell)` |
| read-state | `{type: read-state, on: s, limit: 2}` | `readState(cell, cursor, limit)`, looped to completion |

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
| effect-count | `{type: effect-count, sink: s, key?: k, exactly: 1}` | effectful sink acted exactly N times per key (dur) |
| observations-whole-waves | `{type: observations-whole-waves, view: v, source: a}` | every observation equals the source's fold at some whole op prefix (no torn fork-join) |
| wave-plane-unchanged | `{type: wave-plane-unchanged, cell: s}` | every `read-state` walk on `s` left `s`'s wave plane exactly where it found it |
| pages-equal-view | `{type: pages-equal-view, cell: s, view: v}` | every `read-state` walk on `s` was stamped, non-duplicating, and unions to `v`'s fold |

Inline construction-time expectations use `expect:` on the `connect`/`disconnect`
step, not a check entry.

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
