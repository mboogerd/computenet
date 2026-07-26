# Concord — the executable specification & acceptance test framework

Analysis pinned at commit `39e9636` (2026-07-26). File references are as of that
commit; always locate by type/function name, never trust line numbers.

**Name.** *Concord*: the suite's job is agreement — between spec and implementation,
between independent implementations, between replicas and views inside one run. The
generated traceability matrix is, fittingly, a *concordance*. Module `:concord`,
scenario corpus under `concord/corpus/`.

## 0. Purpose and fit

Three consumers, in order of arrival:

1. **Parallel test/implementation agents.** A testing agent authors scenarios from
   the spec (`doc/spec/`) alone; an implementation agent builds from the same spec.
   Concord is where they meet: if the spec was unambiguous, independently developed
   scenarios pass against the independently developed implementation. Divergence is
   a spec bug before it is a code bug.
2. **Refactor confidence.** The corpus asserts only boundary-observable properties,
   so it survives internal restructuring untouched (see §2.3 — the corpus is immune
   to `doc/RESTRUCTURE-PLAN.md` by construction). It is the acceptance gate the
   restructure run and later refactors push against.
3. **The second implementation.** When a Rust (or other) implementation starts, the
   corpus is its driving test suite from day one. The only per-implementation work
   is a thin driver (§2.4). We deliberately build the corpus *before* that
   implementation, and defer the cross-process driver *until* it (W5, §4).

Relationship to existing machinery:

- `:testkit` (RESTRUCTURE-PLAN RS-9) is **internal** test scaffolding (SimWorld,
  awaitUntil, HttpProbe) for kernel/demo unit and exit tests. Concord is
  **spec-facing** and implementation-neutral. The kernel driver may reuse testkit
  internals; nothing in Concord's corpus or harness may.
- `GenerativeGraphTest` (G-31) is the embryo of Concord's generative layer; it gets
  re-pointed to emit corpus-shaped scenarios in W4-C.
- Spec 52 ("invariants over examples") is the philosophy Concord operationalizes:
  every check is a property at quiescence or over an observation stream, never a
  trace.

---

## 1. Conceptual layering

Five layers, each consumed by the one below it:

| Layer | Artifact | Author | Language |
|---|---|---|---|
| L0 Requirements | EARS-tagged normative statements inside `doc/spec/` chapters | spec author | English (RFC-2119 + EARS) |
| L1 Scenario language | value model, cell catalog, function catalog, scenario schema | framework (this plan) | YAML schema, versioned |
| L2 Corpus | scenario files in `concord/corpus/` | testing agent | L1 |
| L3 Execution | harness (check semantics, oracles, schedule sweep) + driver SPI | framework | Kotlin; drivers per implementation |
| L4 Provenance | generated concordance matrix + lints | generated | Markdown, CI-checked |

The load-bearing property: **everything above L3 contains nothing
Kotlin-specific**. Cells are named by catalog id, functions come from a closed
catalog, values are JSON-shaped. That is the portability guarantee, purchased now
at near-zero cost; the cross-process transport is purchased later.

### 1.1 L0 — Requirements (EARS ids in the spec chapters)

No new documents. Chapters under `doc/spec/` already carry RFC-2119 language; we
add stable ids and, where a statement is vague, tighten it into one of the five
EARS templates. Id scheme: `«chapter»-«slug»-«nn»`, immutable once assigned,
deprecated-never-reused.

Examples spanning the template diversity:

- **Ubiquitous** — `[21-PROP-01]` The graph SHALL deliver every delta accepted by a
  source to every transitively linked consumer, such that at quiescence each
  consumer's fold equals the fold of the source's accepted-op multiset.
- **Event-driven** — `[21-CATCHUP-02]` WHEN a subscriber links to an outlet after
  deltas have already flowed, the outlet SHALL bring the subscriber current
  (baseline or replay) such that its subsequent fold is indistinguishable from an
  early subscriber's.
- **State-driven** — `[22-GF-01]` WHILE a wave from a single source is partially
  delivered across a fork-join, a glitch-free cell SHALL NOT expose derived state
  that mixes pre-wave and post-wave inputs.
- **Unwanted behavior** — `[13-LINK-05]` IF a connect request violates the inlet's
  admission policy (e.g. a second writer on a single-writer inlet), THEN the link
  SHALL be rejected with a stated reason and the existing topology SHALL be
  unaffected.
- **Optional feature** — `[42-REPL-04]` WHERE replication is supported, replicas of
  one logical cell SHALL converge to equal folds at quiescence regardless of which
  replica accepted each write.

Rule: an EARS statement enters L0 only when it is checkable through the driver SPI
(§2.4). Statements about internals (scheduling order, protocol frames, memory) stay
normative prose without ids — they are implementation guidance, not conformance
surface.

### 1.2 L1 — Scenario language

One YAML document per scenario. Schema (versioned `concord/schema/scenario.md` +
serialization types):

```
id, title, covers[], profile (core|dist|dur), kind (example|generative|control)
narrative: {given, when, then}        # BDD prose, for humans and the concordance
graph:    cells[], links[], hosts[]?  # topology as data
script:   steps[]                     # ops, barriers, topology mutations
checks:   []                          # drawn from the closed check vocabulary (§2.4)
```

**Value model**: JSON scalars/arrays/objects only. **Cell catalog**: neutral ids
(`set-source`, `map-source`, `filter`, `union`, `join`, `group-by`,
`combine-latest`, `count-view`, `set-view`, `map-view`, `pn-counter`, …) that each
driver binds to its own cells — the kernel driver binds via `ContractRegistry`
descriptors. **Function catalog**: a closed set of pure functions referenced by id
(`gt(n)`, `mod-eq(m,r)`, `concat(s)`, `key-of`, `sum`, `even`, …) with semantics
defined once in the schema doc and implemented once in the harness (for oracles)
and once per driver binding. Keep it under ~20 entries; growing it is a spec
change, not a test convenience.

**Script semantics** (normative for all drivers): steps targeting the same cell are
applied in file order; steps targeting different cells are concurrent unless
separated by a `quiesce` barrier. Delivery interleaving between barriers is
implementation freedom — which is exactly what the schedule sweep (§2.4)
quantifies over.

Six examples exercising the representational diversity:

**(a) Operator algebra — golden + oracle** (`corpus/24-data-cells/op-union-01.yaml`):

```yaml
id: 24-OP-UNION-01
title: Union of two set sources equals batch union
covers: [24-OP-03, 21-PROP-01]
profile: core
kind: example
narrative:
  given: two set sources feeding a union operator, observed through a set view
  when: adds and removes are applied concurrently to both sources
  then: at quiescence the view equals the batch union of the final source sets
graph:
  cells:
    - {id: a, type: set-source, of: string}
    - {id: b, type: set-source, of: string}
    - {id: u, type: union}
    - {id: v, type: set-view}
  links:
    - {from: a, to: u, inlet: left}
    - {from: b, to: u, inlet: right}
    - {from: u, to: v}
script:
  - {on: a, op: add, value: apple}
  - {on: b, op: add, value: pear}
  - {on: a, op: add, value: plum}
  - {on: a, op: remove, value: apple}
checks:
  - {check: final-view, view: v, equals: [pear, plum]}
  - {check: incremental-equals-batch, view: v}
  - {check: no-dead-letters}
```

**(b) Glitch-freedom — invariant over the observation stream, not a final value**
(`corpus/22-consistency/gf-diamond-01.yaml`):

```yaml
id: 22-GF-DIAMOND-01
title: Fork-join observer never sees a torn wave
covers: [22-GF-01]
profile: core
kind: example
narrative:
  given: one counter source forked through two identity arms into a summing join
  when: the source is incremented repeatedly
  then: every observed sum is even — no observation mixes arms across a wave
graph:
  cells:
    - {id: n, type: counter-source}
    - {id: l, type: map, fn: identity}
    - {id: r, type: map, fn: identity}
    - {id: s, type: combine-latest, fn: sum, glitch-free: true}
    - {id: v, type: value-view}
  links:
    - {from: n, to: l}
    - {from: n, to: r}
    - {from: l, to: s, inlet: left}
    - {from: r, to: s, inlet: right}
    - {from: s, to: v}
script:
  - {on: n, op: increment, times: 50}
checks:
  - {check: observations-all-satisfy, view: v, fn: even}
  - {check: final-view, view: v, equals: 100}
```

**(c) Phased script with barriers + late joiner** (`corpus/21-propagation/catchup-01.yaml`):

```yaml
id: 21-CATCHUP-01
title: Late subscriber is indistinguishable from an early one
covers: [21-CATCHUP-02]
profile: core
kind: example
narrative:
  given: a source with an early view; deltas flow; then a late view links
  when: further deltas flow after the late link
  then: both views hold equal folds at quiescence
graph:
  cells:
    - {id: s, type: set-source, of: string}
    - {id: early, type: set-view}
    - {id: late, type: set-view}
  links:
    - {from: s, to: early}
script:
  - {on: s, op: add, value: before-1}
  - {on: s, op: add, value: before-2}
  - quiesce
  - {connect: {from: s, to: late}}
  - {on: s, op: add, value: after-1}
checks:
  - {check: views-converge, views: [early, late]}
  - {check: final-view, view: late, equals: [before-1, before-2, after-1]}
```

**(d) Negative / construction-time expectation** (`corpus/13-links/link-reject-01.yaml`):

```yaml
id: 13-LINK-REJECT-01
title: Second writer on a single-writer inlet is rejected without disturbing the first
covers: [13-LINK-05]
profile: core
kind: example
graph:
  cells:
    - {id: w1, type: set-source, of: string}
    - {id: w2, type: set-source, of: string}
    - {id: sink, type: set-view, inlet-mode: single-writer}
  links:
    - {from: w1, to: sink}
script:
  - {connect: {from: w2, to: sink}, expect: rejected}
  - {on: w1, op: add, value: still-flows}
checks:
  - {check: final-view, view: sink, equals: [still-flows]}
  - {check: no-dead-letters}
```

**(e) Distribution — hosts and placement are scenario data** (`corpus/42-replication/repl-converge-01.yaml`):

```yaml
id: 42-REPL-01
title: Two replicas converge under concurrent writes on both sides
covers: [42-REPL-04]
profile: dist
kind: example
graph:
  hosts: [h1, h2]
  cells:
    - {id: r1, type: set-source, of: string, host: h1, replica-of: shared}
    - {id: r2, type: set-source, of: string, host: h2, replica-of: shared}
    - {id: v1, type: set-view, host: h1}
    - {id: v2, type: set-view, host: h2}
  links:
    - {from: r1, to: v1}
    - {from: r2, to: v2}
script:
  - {on: r1, op: add, value: from-h1}
  - {on: r2, op: add, value: from-h2}
  - {on: r1, op: remove, value: from-h2}   # concurrent remove of the other side's add
checks:
  - {check: replicas-converge, logical: shared}
  - {check: views-converge, views: [v1, v2]}
```

**(f) Generative — same shape, generator instead of a fixed graph**
(`corpus/24-data-cells/gen-pipelines-01.yaml`):

```yaml
id: 24-GEN-01
title: Random pipelines from the operator vocabulary keep the standard properties
covers: [24-OP-ALL, 21-PROP-01, 21-CATCHUP-02]
profile: core
kind: generative
generator:
  pipeline-depth: [1, 4]
  vocabulary: [set-source, filter, map, union, intersect, join, group-by, count-view]
  ops: 200
  late-joiner: true
  instances: 100
checks:
  - {check: incremental-equals-batch, view: '*'}
  - {check: views-converge, views: '*'}
  - {check: late-join-equals-early}
  - {check: no-dead-letters}
```

### 1.3 L2 — Corpus organization

Directories mirror **spec chapters, not code packages**:

```
concord/corpus/
  12-ports/  13-links/  15-lifecycle/
  21-propagation/  22-consistency/  23-ownership/  24-data-cells/
  34-cycles/
  41-location/  42-replication/          # profile: dist
  15-durability/ (journal scenarios)      # profile: dur
  controls/                               # kind: control
```

This is deliberate: `doc/RESTRUCTURE-PLAN.md` reshuffles packages
(`cell.link`, `cell.data.op`, …) with no behavior change — a corpus keyed to spec
chapters and contract ids does not notice. The only Concord code that restructure
touches is the kernel driver's imports (one file's worth; accept the churn, or land
W1-A after RS-2 — either order works).

### 1.4 L3 — Execution: checks, oracles, drivers

**Check vocabulary** (closed; implemented once in the harness, in terms of driver
verbs only):

| Check | Semantics |
|---|---|
| `final-view(view, equals)` | at quiescence, `readView` equals the golden value |
| `views-converge(views)` | all listed views' values are equal at quiescence |
| `incremental-equals-batch(view)` | view equals the harness-side batch oracle: catalog semantics applied to the script's accepted-op multiset. The oracle lives in the harness, so no per-implementation duplication |
| `late-join-equals-early` | folds of late-linked and early-linked views are equal |
| `observations-all-satisfy(view, fn)` | every event on the observation stream satisfies a catalog predicate (glitch-freedom, monotonicity) |
| `observations-monotone(view, order)` | stream never regresses under the stated order |
| `replicas-converge(logical)` | all live replicas of the logical id hold equal folds |
| `no-dead-letters` | zero dead letters across all hosts |
| `effect-count(sink, key, exactly)` | an effectful sink acted exactly N times per key (durability dedup) |
| inline `expect:` on script steps | construction-time results: `connected`, `rejected` |

**Schedule quantification**: a scenario passes only if all checks hold on **every
run of a sweep** (default 20 runs, `runs:` overridable). Each run hands the driver
an opaque run index; the kernel driver maps it to a `SimulationController` seed.
Cross-implementation reproducibility is *per implementation* (seed reproduces a
schedule locally); the cross-implementation contract is only "all schedules pass" —
interleaving goldens are forbidden by principle P2.

**Driver SPI** (~12 verbs; the entire per-implementation surface):

```
createHost(hostId)                          spawn(hostId, cellId, type, params)
connect(from, to, inlet?, role?) → result   disconnect(linkRef)
apply(cellId, op)                           quiesce(budget) → QuiesceReport
readView(cellId) → Value                    observationLog(cellId) → [Value]
snapshot(cellId) → blob                     restore(hostId, cellId, blob)
despawn(cellId)                             deadLetters() / effectLog(cellId)
```

Binding #1 (in-process, W1-A): `spawn`/`connect` via `Use<HostManagementApi>` +
`GraphSpec` steps; catalog→cell via `ContractRegistry` descriptors; `quiesce` via
budgeted `SimulationController.runToIdle`; `readView`/`observationLog` via
`ObservationSink`/`View`. Binding #2 (cross-process, deferred to W5): the same
verbs as JSON-lines over stdio — *that* subprocess adapter is the "thin shim" a
Rust implementation writes.

### 1.5 L4 — Provenance: the concordance

A generator (Gradle task in `:concord`) scans L0 ids and L2 `covers:` tags and
emits `doc/spec/CONCORDANCE.md`:

```
| Requirement | Scenarios | Last run |
| 21-CATCHUP-02 | 21-CATCHUP-01, 24-GEN-01 | ✅ 39e9636 |
| 22-GF-01      | 22-GF-DIAMOND-01         | ✅ 39e9636 |
| 42-REPL-04    | 42-REPL-01               | — (dist profile, not in gate) |
```

Lints, failing the build: a `covers:` id that matches no L0 requirement
(dangling); a scenario with an empty `covers:` (orphan). Reported but non-fatal: a
`Specified`-status requirement with no covering scenario (gap — the testing
agent's worklist).

---

## 2. Principles — what belongs in the corpus

- **P1 Boundary-observable only.** A scenario may reference nothing an external
  driver cannot see: no internal state, no scheduler steps, no protocol frames, no
  progress acks. If a requirement is only checkable internally, it is kernel unit
  test material, not Concord material.
- **P2 Properties, never traces.** Assertions hold at quiescence or over an
  observation stream. Interleaving goldens are forbidden; scheduling freedom is
  quantified by the sweep, not pinned by the test.
- **P3 Smallest complete graph.** Every scenario is a whole graph (sources →
  operators → views), but the smallest one that exercises the requirement. A
  single-cell graph is legitimate when the cell *is* the unit of specified behavior
  (e.g. `24-OP-*`); isolation happens at graph granularity, never below it.
- **P4 Cross-implementation meaningful.** Excluded outright: concurrency colors
  (30/32), virtual-thread vs coroutine scheduling, KSP/codegen DX, JVM
  serialization formats, attention/stride internals (30/34), exact error text.
  These are per-implementation concerns; the kernel keeps its own tests for them.
- **P5 Neutral vocabulary.** Scenarios reference catalog ids and catalog functions
  only — no Kotlin types, lambdas, or class names. Growing the catalog is a
  deliberate act with its own review, because every entry costs each future driver.
- **P6 Full lineage.** Every scenario covers ≥1 requirement id; coverage gaps for
  `Specified` requirements are the standing worklist. One scenario covers a small
  cluster (1–3 ids), not a chapter.
- **P7 The harness must be able to fail.** `kind: control` scenarios carry
  deliberately wrong expectations and MUST fail; the runner asserts their failure.
  (Sabotage controls that break the *implementation* — e.g. removing wave alignment
  — stay in kernel tests; a conformance suite can't ask a foreign implementation to
  mis-implement itself.)
- **P8 Interactions get their own scenarios.** Where 93's interaction matrix
  resolved a non-trivial pairing observably (late-join × replication, glitch-free ×
  fan-in, snapshot × in-flight waves), that pairing gets a dedicated scenario —
  feature-combination coverage is the point of whole-graph testing.
- **P9 Profiles gate optional capability.** `core` runs in every build;
  `dist` and `dur` are conformance levels a second implementation can adopt
  incrementally. A profile is claimed wholly or not at all.
- **P10 Exclusions are recorded.** When a requirement is deliberately not covered
  (P1/P4 grounds), the concordance says so with the reason — silence is
  indistinguishable from oversight otherwise.

---

## 3. The corpus, built out

Exemplars (a)–(f) above are members. Full initial set, by chapter — each row is one
scenario file; `covers` ids are assigned when L0 ids land (W1-C):

### 12/13 — Ports & links (core)

| Id | Scenario | Checks |
|---|---|---|
| 13-FANOUT-01 | one source, three consumers; each view equals source fold | views-converge, final-view |
| 13-TAP-01 | observe-role tap alongside a consumer; tap fold equals consumer fold | views-converge |
| 13-LINK-REJECT-01 | exemplar (d): single-writer inlet rejects second writer | expect:rejected, final-view |
| 13-UNLINK-01 | phased: unlink mid-script; downstream keeps last fold, receives nothing after | final-view per phase |
| 13-RELINK-01 | unlink then relink; catch-up brings downstream current | late-join-equals-early |
| 13-FANIN-01 | two producers into one merging inlet; fold equals multiset merge | incremental-equals-batch |
| 12-NEGOTIATE-01 | contract-incompatible connect refused; topology undisturbed | expect:rejected, no-dead-letters |

### 15 — Lifecycle (core)

| Id | Scenario | Checks |
|---|---|---|
| 15-DESPAWN-01 | graceful despawn+unlink mid-flow; remainder converges, no dead letters | no-dead-letters, incremental-equals-batch |
| 15-SNAPSHOT-01 | snapshot → despawn → restore mid-script; final equals batch oracle | incremental-equals-batch, views-converge |
| 15-RESTART-01 | restart with re-baseline; downstream equals batch despite the restart | incremental-equals-batch |

### 21 — Propagation (core)

| Id | Scenario | Checks |
|---|---|---|
| 21-PIPE-01 | linear source→map→view push | final-view, incremental-equals-batch |
| 21-REBASE-01 | source re-baselines mid-stream; final equals delta-only twin | views-converge (twin views) |
| 21-CATCHUP-01 | exemplar (c): late subscriber | views-converge, final-view |
| 21-PULL-01 | view linked after quiescence with no further ops reaches current state | final-view |

### 22 — Consistency (core)

| Id | Scenario | Checks |
|---|---|---|
| 22-GF-DIAMOND-01 | exemplar (b): even-sum diamond | observations-all-satisfy |
| 22-GF-NESTED-01 | double diamond (fork of forks); product invariant over stream | observations-all-satisfy |
| 22-LIVE-01 | two independent sources into one join; silent source B does not block A's updates from appearing at quiescence | final-view (guards over-alignment) |
| 22-WAVE-FANIN-01 | multi-inlet operator exposes only whole waves under fan-in | observations-all-satisfy |
| 22-SOURCE-ID-01 | interleaved waves from two sources fold to equal views on two observers | views-converge |

### 23 — Ownership (core, minimal — most of 23 is P1-excluded type discipline)

| Id | Scenario | Checks |
|---|---|---|
| 23-SPSC-01 | second consume-link on an exclusive outlet rejected; observe-tap on the same outlet admitted *(tap half deferred until G-47 lands)* | expect:rejected |

### 24 — Data cells (core; the operator block)

One scenario per operator: a golden mini-example plus `incremental-equals-batch`
under the sweep. Sources: `24-OP-SET-01`, `24-OP-MAP-01`, `24-OP-LIST-01`,
`24-OP-COUNTER-01` (concurrent increments, two writers), `24-OP-PNCOUNTER-01`,
`24-OP-KEYEDSET-01`, `24-OP-QUORUM-01` (k-of-n admission observable). Operators:
`24-OP-FILTER-01`, `24-OP-MAPFN-01`, `24-OP-FLATMAP-01`, `24-OP-UNION-01`
(exemplar (a), plus commutativity twin: swapped inputs converge),
`24-OP-INTERSECT-01`, `24-OP-JOIN-01/-SEMI-01/-LOOKUP-01`, `24-OP-GROUPBY-01`
(+ mergeable variant), `24-OP-COMBINE-01` (doubles as 22 interplay, P8),
`24-OP-COUNT-01`, `24-OP-PRESENCE-01`, `24-OP-WINDOW-01` (step-windows only —
nothing timer-driven exists, per 52), `24-OP-PARTITION-01` (partitioned view equals
unpartitioned twin). Plus `24-GEN-01`, exemplar (f).

### 34 — Cycles (core)

| Id | Scenario | Checks |
|---|---|---|
| 34-CYCLE-01 | damped feedback loop reaches the closed-form fixpoint | final-view |
| 34-CYCLE-REJECT-01 | cycle admission without a damping witness rejected at construction (FU-8) | expect:rejected |

### 41/42/33 — Distribution (profile: dist)

| Id | Scenario | Checks |
|---|---|---|
| 41-SPLIT-01 | pipeline split across two hosts equals single-host twin | views-converge |
| 42-REPL-01 | exemplar (e): concurrent writes both sides | replicas-converge |
| 42-REPL-LATE-01 | replica joining mid-run converges (late-join × replication, P8) | replicas-converge, late-join-equals-early |
| 42-REPL-DEPART-01 | orderly replica departure doesn't fail convergence (G-45 departed-stream rule) | replicas-converge |
| 42-INTEREST-01 | interest-scoped replica holds exactly the filtered subset | final-view vs filtered oracle |
| 33-MIGRATE-01 | cell migrates hosts mid-script; final equals stay-put twin | views-converge, no-dead-letters |

### Durability (profile: dur)

| Id | Scenario | Checks |
|---|---|---|
| DUR-REPLAY-01 | crash → journal replay → continue; converges to batch, effectful sink fired exactly once per key | incremental-equals-batch, effect-count |
| DUR-SNAPTAIL-01 | restore from snapshot + journal tail equals uninterrupted twin | views-converge |

### Controls (kind: control — must fail; the runner asserts failure)

| Id | Scenario |
|---|---|
| CTL-GOLDEN-01 | correct graph, deliberately wrong `final-view` golden |
| CTL-CONVERGE-01 | `views-converge` across views fed *different* filters |
| CTL-GF-01 | `observations-all-satisfy(even)` on a deliberately non-glitch-free join |

~45 named scenarios + the generative sweep. Everything excluded by P4 (colors,
attention/scheduling, mobility internals, security membranes — 43 is Exploratory)
is listed in the concordance as excluded-with-reason (P10), revisited when the
spec makes them observable.

---

## 4. Milestones — wave-parallel

The dependency structure is a **short serial spine plus wide fan-out**. Only three
things are inherently serial: freezing the L1 interfaces (everything hangs off
them), one integration step, and single-writer files. Everything else — spec
editing per chapter, corpus authoring per chapter, oracle vs driver, tooling — is
disjoint-file work that runs as concurrent agents.

```
W0 spine freeze
 ├── W1-A kernel driver + runner ─┐
 ├── W1-B checks + batch oracle ──┤
 ├── W1-C1 EARS ids 21+22 ────────┼── W2 integration ── W3-1 corpus 21+22
 ├── W1-C2 EARS ids 24 ───────────┤        │            W3-2 corpus 24
 ├── W1-C3 EARS ids 12/13/15 ─────┘        │            W3-3 corpus 12/13/15/34/23/CTL
 └── W1-D concordance generator ───────────┘                 │
                                           W4-A dist ◄───────┤
                                           W4-B dur  ◄───────┤
                                           W4-C generative ◄─┘
```

Critical path: W0 → W1-A → W2 → W3 → W4 — five wave-latencies, with up to six
agents wide in W1 and three in W3/W4, versus seven serial sessions in the naive
ordering. Wall clock per wave is the slowest ticket, so keep tickets in a wave
comparably sized.

**Conflict seams, engineered away up front** (this is what makes the fan-out safe):

- `concord/schema/**`, the driver SPI, and check signatures are written once in W0
  and single-writer thereafter; changes only via a dedicated schema-change ticket
  *between* waves. Corollary: **W0's schema must be script-verb-complete** for
  everything §3 needs (`quiesce`, `connect`/`disconnect`, `snapshot`/`restore`,
  `despawn`, `expect:`) and W1-A implements all verbs — so corpus waves never touch
  code, only add YAML.
- Spec chapters and corpus directories: one ticket per chapter block, disjoint
  files by construction.
- Driver: core lands in W1-A; dist and dur support are **separate capability
  files** (`KernelDriverDist.kt`, `KernelDriverDur.kt`) so W4-A and W4-B don't
  edit the same code. Profile filtering lands in W2 (runner), ahead of both.

**Orchestration mechanics** (house pattern, cf. `ORCHESTRATION.md` /
`PERNODE-ORCH.md`): one branch per ticket (`concord/W1-A`), all wave tickets
launched together, merge at wave end, full gate `./gradlew :concord:test` per
merge. Concurrent agents on this repo share the git index — give each agent its
own worktree, commit by pathspec, never amend. Commit style `concord(Wn-X): …`.
All parallel tickets are FRESH by definition; each carries its own onboard list.

### W0 — Spine freeze (serial, one session, FRESH)
**Onboard:** this plan; `doc/spec/README.md`; `kernel/.../graph/GraphDsl.kt`,
`host/SimulationController.kt`, `host/Observe.kt`.
**Do:** New module `:concord` (depends on `:kernel`, test-scope kaml — the one new
dependency). Scenario schema as kotlinx-serialization types +
`concord/schema/scenario.md`, **verb-complete per the seam rule above**. Cell
catalog v1 + function catalog v1 (~15 entries) in `concord/schema/`. Driver SPI
interfaces (§2.4). Check vocabulary as typed signatures with stub bodies. EARS id
scheme + concordance format note (§1.1, §1.5 distilled for the W1 agents). Author
the four pilot scenario files (`24-OP-UNION-01`, `21-PIPE-01`, `22-GF-DIAMOND-01`,
`CTL-GOLDEN-01`) with a schema round-trip parse test — no execution yet.
**Verify:** `./gradlew :concord:test` (parse tests green)
**Commit:** `concord(W0): spine — module, schema, catalogs, SPI, pilot files`

### W1 — Fan-out (parallel, up to six agents)

| Ticket | Scope (disjoint) | Verify |
|---|---|---|
| **W1-A** driver + runner | Implement the SPI in `civictech.concord.driver.kernel` (the only package importing kernel types) — all verbs, single- and multi-run; JUnit dynamic-test runner discovering `concord/corpus/**.yaml`; execute the pilots with only `final-view` checked (rest stubbed pending W1-B). Onboard adds `verify/GenerativeGraphTest.kt`, `data/DataTestSupport.kt`. | `./gradlew :concord:test` |
| **W1-B** checks + oracle | Pure implementations of the full check table (§2.4) and the batch oracle over catalog semantics. No kernel dependency; unit-tested against hand-computed fixtures. | `./gradlew :concord:test` |
| **W1-C1** EARS ids 21+22 | Spec-editing hat: ids into chapters 21 and 22; tighten prose into EARS templates only where ambiguous; *flag, don't decide* statements that resist a template. | doc review |
| **W1-C2** EARS ids 24 | Same, chapter 24. | doc review |
| **W1-C3** EARS ids 12/13/15 | Same, chapters 12, 13, 15. | doc review |
| **W1-D** concordance | Generator + lints as a `:concord` Gradle task, tested against a stub corpus plus the pilot files (dangling/orphan cases covered). | `./gradlew :concord:concordance` |

### W2 — Integration (serial, short; CONTINUE from the W1-A agent)
**Do:** Wire runner → W1-B checks → W1-A driver; fill the pilots' `covers:` from
W1-C ids; runner asserts `CTL-GOLDEN-01` fails (P7); concordance task into
`check`; profile filter flag (`-Pconcord.profiles=…`). Gate: pilots green under
the 20-run sweep.
**Verify:** `./gradlew :concord:test :concord:concordance`
**Commit:** `concord(W2): integration — pilots green, concordance in check`

### W3 — Corpus fan-out (parallel, three agents; full execution now available)
Corpus-only tickets — YAML additions in disjoint directories, zero code (the seam
rule). Each ticket authors its §3 block, runs it against the kernel, and files —
never self-resolves — any spec dispute per §5.

| Ticket | Scope |
|---|---|
| **W3-1** | `21-propagation/` + `22-consistency/` remainder (21-REBASE, 21-PULL, 22-GF-NESTED, 22-LIVE, 22-WAVE-FANIN, 22-SOURCE-ID) |
| **W3-2** | `24-data-cells/` operator block (all `24-OP-*`) |
| **W3-3** | `12-ports/` + `13-links/` + `15-lifecycle/` + `34-cycles/` + `23-ownership/` + remaining `controls/` |

**Verify each:** `./gradlew :concord:test`; wave-end merge runs the full gate.

### W4 — Profiles + generative (parallel, three agents)

| Ticket | Scope |
|---|---|
| **W4-A** dist | Multi-host driver capability (`KernelDriverDist.kt`; SimulationController already drives N hosts); EARS ids into 41/42; author 41/42/33 corpus. Onboard: `kernel/.../replication/`, `wire/BridgeCells.kt`, `DistributedCollaborativeAppTest.kt`. Verify with `-Pconcord.profiles=core,dist`. |
| **W4-B** dur | Journal/restart driver capability (`KernelDriverDur.kt`: `Journal` injection + host restart); author DUR corpus (`effect-count` check already exists from W1-B). Verify with `-Pconcord.profiles=core,dur`. |
| **W4-C** generative | Harness support for the `generator:` block; author `24-GEN-01`; re-point `GenerativeGraphTest`'s generator to emit corpus-shaped scenarios through the driver (the kernel test keeps its kernel-internal extras, e.g. migration, on top). |

### W5 — Cross-process driver (**deferred: starts with implementation #2, not before**)
JSON-lines-over-stdio binding of the same 12 verbs; `concord/IMPLEMENTERS.md`
(how to claim a profile); CI matrix per implementation. Nothing in W0…W4 may
presuppose its transport details beyond keeping the corpus Kotlin-free (P5).

**Interplay with other running work:** corpus and spec-editing tickets touch no
kernel code, so they can run concurrently with unrelated kernel work, including
the restructure run — only W1-A/W4-A/W4-B (driver code) care about kernel package
moves (§1.3: land W1-A after RS-2, or accept one file's import churn).

---

## 5. The two-agent workflow this enables

- **Freeze point**: a spec chapter with EARS ids is the shared input.
- **Testing agent**: reads `doc/spec/` + `concord/schema/` only — never kernel
  source. Output: corpus files + concordance gap closures.
- **Implementation agent**: reads `doc/spec/` (and kernel code as needed). May
  *run* the corpus; may not edit it. A failing scenario is resolved by fixing the
  implementation, or by filing a spec/scenario dispute — never by patching the
  scenario in the implementation branch.
- **Dispute rule**: when the two disagree, the requirement id is the arbiter; if
  the requirement itself is ambiguous, the fix lands in `doc/spec/` first, then
  flows down to whichever side was wrong. This is the mechanism that converts
  implementation surprises into spec precision.

## 6. Non-goals

Performance/throughput conformance, wall-clock timing, scheduling fairness,
attention/stride behavior, security membranes (43, Exploratory), wire-format
compatibility between implementations (each implementation's interior transport is
its own), and any assertion on internal protocols. The corpus tests *what the
graph computes and exposes*, not *how fast or by what internal choreography*.
