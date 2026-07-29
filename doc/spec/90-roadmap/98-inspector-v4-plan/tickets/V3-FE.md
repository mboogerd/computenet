# V3-FE — The error surfaces gain a wave-health class, a supervision timeline, and dead-letter cards that say what failed

**Status**: Implemented — merged. (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first word
of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`
**Wave:** 5 · **Branches:** `ticket/v3-fe`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS + Vite app
(npm, deliberately **not** wired into Gradle) that renders a live ComputeNet
dataflow graph from the `:inspect` backend's HTTP + SSE API. Zero runtime
dependencies beyond `solid-js`; pure-module vitest suites in `test/`, node
environment, no DOM/component tests. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Verticals → V3"
and §"Binding constraints" first.

### How the error surface works today

- **Store.** `src/sync/errorStore.ts` is a framework-free `ErrorStore`: one
  `applySnapshot` (`:69-98`) plus one apply method per SSE kind
  (`applyDeadLetter` `:100-106`, `applyParked` `:108-121`, `applyRestart`
  `:123-129`), everything indexed by ref for O(1) per-node reads
  (`:33-56`). Two update disciplines coexist and are documented at `:5-24`:
  `deadLetters`/`restarts` are append-only occurrence logs whose counters
  increment by one per event; `parked` is *current state*, where an event with
  `count: 0` **clears** the (ref, port) row and the counter is recomputed as the
  live sum. **The parked discipline is the one this ticket's new class copies.**
- **Solid glue.** `src/solid/errors.ts` holds the singleton store, an
  `errorVersion()` signal bumped on every store change (`:11-15`), the
  snapshot fetch (`:33-38`) and one `onError*` router function per kind
  (`:41-50`). The SSE switch in `src/solid/state.ts:112-123` dispatches to them.
- **Types.** `src/api/types.ts:314-372` — `DeadLetterEntry`, `ParkedEntry`,
  `RestartEntry`, `ErrorCounters`, `ErrorSnapshot`, and the three
  `Error*Event` envelope types that feed the `InspectEvent` discriminated union.
- **Header counters.** `src/components/Header.tsx:54-75` renders a three-item
  strip (dead / parked / restarts) from the store totals; clicking it flips the
  Errors canvas toggle.
- **Per-cell Errors section.** `src/components/DetailPanel.tsx:274-354`: dead-
  letter cards (`:298-316`), parked rows (`:318-333`), a flat "Restart history"
  list of `generation` + time (`:335-349`), and a "No local errors" placeholder.
  Styles in `src/components/DetailPanel.css`.
- **Canvas overlay derivations.** `src/util/errors.ts` — `cellErrorBadges` and
  `deriveEdgeParkedCounts`, pure and toggle-gated.
- **Fixtures.** `fixtures/errors.json` plus `fixtures/error-event-{dead-letter,
  parked,restart}.json`, exercised by `test/errors-fixture.test.ts` and
  `test/errorStore.test.ts`, and served by the offline dev backend
  `mock/serve.mjs` (errors state and its mutators at `:213-260`, the `/errors`
  route at `:359-360`).

### What the backend is adding, in parallel, this wave

V3-BE runs concurrently on `inspect/src/**`. It adds a **new error class** —
wave health — and enriches two existing row types. Code against the shapes in
this ticket; they are pinned identically in V3-BE's ticket and are what the
server will emit.

Wave health is a **heuristic diagnostic**, deliberately not kernel-grade
detection. It is computed inspector-side by correlating the last wave observed
on a tapped edge with the frontier stamp of an observed downstream cell. A
lagging frontier is frequently *correct* — an absorbed delta, a filtering
operator, a fresh emission epoch after a restart — which is why every row
carries `heuristic: true` and why the UI must present these rows as
"worth a look", never as "this is broken". Do not render them in the same
visual register as a dead letter.

## Problem

1. There is no surface at all for the new class: no type, no store handling, no
   counter, no rows, no fixtures.
2. The per-cell Errors section shows restarts as a flat list of generation
   numbers (`DetailPanel.tsx:335-349`). The v1 mock-up's ERRORS tab (see
   `10-design-notes.md` §"Mock-up references", and §Verticals V3 "supervision
   timeline per cell") shows a causal sequence — crash → restart → re-baseline
   — which the data will now support and the current list cannot express.
3. Dead-letter cards (`DetailPanel.tsx:298-316`) show cause, description, wave
   and time; once the backend reports *what call failed* and *what happened to
   its exclusive payload*, that is the first thing an operator wants and there
   is nowhere for it to go.
4. Offline dev (`npm run dev` against `mock/serve.mjs`) cannot exercise any of
   it, so none of the new rendering can be checked without a live backend.

## Solution direction

Prescriptive. Implement all five items.

### 0. Types (`src/api/types.ts`)

Add, in the M2 errors block (`:314-372`), keeping the file's existing comment
style. **These shapes are contract-binding — match them exactly; the backend's
`FixtureContractTest` strict-decodes your fixtures against its Kotlin DTOs, so a
renamed or extra field fails their build, not yours.**

```ts
export type WaveHealthKind = 'frontierLag' | 'stalledWave';

/** A heuristic wave-health diagnostic — NOT kernel-grade detection. A lagging
 *  frontier is often legitimate (an absorbed delta, a filtering operator, a
 *  fresh emission epoch after a restart), so `heuristic` is always true and the
 *  UI must present these as "worth a look", never as a defect claim. */
export interface WaveHealthEntry {
  /** Stable per (kind, edge, ref): the open row, its updates and its clear all
   *  carry this id. The store keys on it. */
  id: string;
  kind: WaveHealthKind;
  /** `'cleared'` removes the row with this `id` — the same discipline
   *  `ParkedEntry`'s `count: 0` already established. */
  state: 'open' | 'cleared';
  ref: Ref;
  /** The `Edge.id` whose last observed wave the comparison used. */
  edge: string;
  wave: Frontier | null;
  frontier: Frontier | null;
  /** Counter delta; null when the two stamps do not share a source. */
  lagWaves: number | null;
  heldMs: number;
  atMs: number;
  heuristic: boolean;
  description: string;
}

export interface ErrorWaveHealthEvent {
  seq: number;
  kind: 'error.waveHealth';
  payload: WaveHealthEntry;
}
```

Extend the existing interfaces (all fields optional-tolerant on read — an older
server that omits them must not break the client):

- `ErrorCounters` gains `waveHealth: number` — the count of currently **open**
  rows. A gauge that falls as conditions resolve, like `parked`, unlike the
  monotonic `deadLetters`/`restarts`. Say so in the comment.
- `ErrorSnapshot` gains `waveHealth: readonly WaveHealthEntry[]` — open rows
  only, never a history log.
- `DeadLetterEntry` gains:
  ```ts
  invocation: {
    port: string;
    type: 'PORT_API' | 'PORT_MANAGEMENT' | 'PORT_PROTOCOL';
    method: string;
    parameterTypes: readonly string[];
    argCount: number;
    hop: number | null;
  } | null;                     // null: a plain host-level drop, no invocation
  disposition: readonly {
    index: number;
    /** What the kernel's dead-letter sanitization did to this argument:
     *  an `Owned` arrives frozen, a `Leased` arrives released-and-redacted. */
    ownership: 'frozen' | 'redacted' | 'borrowed' | 'owned' | 'leased' | 'plain';
    reason: string | null;
  }[];                          // [] when there was no invocation or no args
  ```
- `RestartEntry` gains `cause: string | null`, `causeAtMs: number | null`,
  `reBaselineAtMs: number | null`. Document in the comment: `cause` is a
  **time-window correlation** with the dead letter that preceded the generation
  bump, not a kernel-reported restart cause; and `reBaselineAtMs: null` means
  **not observed**, never "did not happen".

Add `ErrorWaveHealthEvent` to the `InspectEvent` union.

### 1. Store (`src/sync/errorStore.ts`)

- Hold open wave-health rows keyed by `id`, and also indexed by `ref` so the
  detail panel reads O(1) like every other accessor. Add
  `waveHealthFor(ref: Ref): readonly WaveHealthEntry[]` and
  `allWaveHealth(): readonly WaveHealthEntry[]`, mirroring `parkedFor` /
  `allParked` (`:41-56`).
- `applySnapshot` (`:69-98`): rebuild the wave-health index from
  `snapshot.waveHealth`, skipping any row whose `state` is not `'open'`
  (defensive, exactly as the parked branch skips `count <= 0` at `:82`). Tolerate
  a missing `waveHealth` field (older server) as an empty list.
- New `applyWaveHealth(entry: WaveHealthEntry): void`, following `applyParked`
  (`:108-121`) rather than `applyDeadLetter`: `state === 'cleared'` **deletes**
  the row with that `id`; otherwise it upserts. Recompute
  `counters.waveHealth` as the live size of the open set after every update —
  never increment it — so it cannot drift from what the server reports.
- Immutable-update style throughout (new `Map` per change, then `notify()`),
  matching the existing methods exactly.

### 2. Solid glue and routing

- `src/solid/errors.ts`: add `onErrorWaveHealth(entry)` beside its siblings
  (`:41-50`).
- `src/solid/state.ts`: add the `case 'error.waveHealth'` branch beside the
  other `error.*` cases (`:112-123`), with a comment in the file's established
  style noting the class is heuristic and, like the other error kinds, is not
  gated on selection.

### 3. Rendering

**a. Header counters** (`src/components/Header.tsx:54-75`). Add a fourth item to
the strip — a distinct **wave** category, e.g. `{n} wave`, with its own
`error-counters__item--wave` class. It must read as informational, not as a
failure count sitting beside "dead": amber/neutral, not the dead item's red.
Extend the `title` text to name it and to say it is heuristic. Keep the whole
strip a single button that toggles the Errors overlay — do not add a second
control.

**b. Wave-health rows in the Errors section** (`DetailPanel.tsx:274-354`). A new
group above the existing ones, rendered only when the selected cell has open
rows, titled so the class is unmistakable (e.g. "Wave health (heuristic)").
Each row shows:
- a kind label — "frontier lag" / "stalled wave";
- `description` verbatim (the server writes it, and it already contains the
  word "heuristic" — do not paraphrase or strip it);
- the wave stamps in the panel's existing `mono` style, formatted like the dead-
  letter card's wave chip (`:307-309`): `source.slice(0, 8) · counter`, with
  `wave` → `frontier` shown as a pair so the gap is legible, and `lagWaves` when
  non-null;
- how long it has held (`heldMs`).

Visually distinct from dead letters: **informational/amber**, never the dead-
letter card's red, and carrying a visible "heuristic" label of its own so the
distinction survives someone reading only the row. Add the styles to
`DetailPanel.css` beside the existing `.dead-letter-card` / `.error-group`
rules.

`hasAny` (`DetailPanel.tsx:292`) must include wave-health rows, so a cell with
only a wave-health row no longer shows "No local errors".

Rows disappear when the backend clears them — that falls out of the store's
delete plus the existing `errorVersion()` memo gating (`:277-291`); make sure
your new memo follows the same pattern and does not cache across refs.

**c. Supervision timeline** (replacing the flat restart list,
`DetailPanel.tsx:335-349`). A vertical timeline per cell, newest-first,
consistent with the v1 mock-up's ERRORS tab. Each restart contributes an ordered
group:

```
✕  crash — IllegalStateException            14:22:07     (from cause/causeAtMs)
↻  restart — generation 2                   14:22:07     (from generation/atMs)
⟳  re-baseline                              14:22:08     (from reBaselineAtMs)
```

Rules:
- Omit the crash step when `cause` is null; omit the re-baseline step when
  `reBaselineAtMs` is null. **Never render "no re-baseline" or any negative
  claim** — null means not observed.
- Build it in a pure, testable helper (a new `src/util/supervision.ts` or an
  addition to `src/util/errors.ts`) that takes the cell's `RestartEntry[]` and
  its `DeadLetterEntry[]` and returns an ordered list of timeline steps. The
  component renders that list and nothing more — this is what `npm test` covers.
- Dead letters that did **not** precede a restart stay in the dead-letter card
  group where they are today; they are not silently absorbed into the timeline.
  The `errorStore` data already available (dead letters, restarts, parked) plus
  the new capture fields are the only inputs — no new endpoint, no new fetch.
- Use the existing `formatTime` (`DetailPanel.tsx:356-358`).

**d. Dead-letter cards** (`DetailPanel.tsx:298-316`). When `invocation` is
present, show the failing call compactly — `port` · `method` · `type`, with
`parameterTypes`/`argCount`/`hop` available (a `title` tooltip is acceptable for
the long parameter list; native `title` is still the house pattern until
FE-TOOLTIPS lands in wave 6). When `disposition` is non-empty, show one small
chip per entry — the `ownership` word, with `reason` as its tooltip — and give
`frozen`/`redacted` a visually stronger treatment than `plain`: those are the
exclusive-payload cases, the whole reason the field exists. Cards without the
new fields (older server, or a plain drop) must render exactly as they do today.

### 4. Fixtures

The backend's `FixtureContractTest` asserts that its hand-written decoder map
covers **exactly** the contents of `fixtures/`. V3-BE is adding decoder entries
for precisely two new filenames, so add exactly these two and no others:

- `fixtures/error-event-wave-health.json` — one SSE envelope
  (`{ "seq": …, "kind": "error.waveHealth", "payload": { …WaveHealthEntry, "state": "open" } }`),
  same envelope shape as `fixtures/error-event-parked.json`.
- `fixtures/error-event-wave-health-cleared.json` — the same row `id` with
  `"state": "cleared"`.

And extend `fixtures/errors.json` **in place** (its decoder entry already
exists): add `counters.waveHealth`, a `waveHealth: [...]` array of open rows,
the new `invocation`/`disposition` fields on its dead-letter rows, and the new
`cause`/`causeAtMs`/`reBaselineAtMs` fields on its restart rows. Keep the
existing fixture invariants that `test/errors-fixture.test.ts:19-30` checks —
counters equal to what the rows carry, every `ref` a real node in
`fixtures/topology.json` — and add the analogous one:
`counters.waveHealth === waveHealth.length`. Every `edge` id in a wave-health
row must be a real edge in `fixtures/topology.json`.

**Expected cross-ticket friction, which you must not "fix":** until V3-BE
merges, its Kotlin DTOs do not carry the new fields, so `:inspect:test` run from
a worktree holding only your branch can fail on `errors.json`. That is the
wave's known coupling; your gate is `npm test` + `npm run build`. Do **not**
touch `inspect/src/**` to make it pass, and do not omit fixture content to avoid
it.

### 5. Offline dev backend (`mock/serve.mjs`)

Extend the errors simulation (`:213-260`) so `npm run dev` exercises the new
surface end to end: emit an `error.waveHealth` open row on one of the fixture's
edges, then, a few seconds later, its `state: 'cleared'` counterpart with the
same `id`, on a loop — so open **and** clear are both observable in a short
manual session, matching the existing staggered-mutator style. Keep the file's
existing property that `GET /errors` never runs ahead of what the SSE stream has
announced (`:216-220`). Add the new fields to the mock's dead-letter and restart
emissions too, so the cards and the timeline have something to draw.

## Files expected to touch

- `inspect/ui/src/api/types.ts` — new and extended types; the `InspectEvent` union.
- `inspect/ui/src/sync/errorStore.ts` — the wave-health index and `applyWaveHealth`.
- `inspect/ui/src/solid/errors.ts` — `onErrorWaveHealth`.
- `inspect/ui/src/solid/state.ts` — the new SSE case.
- `inspect/ui/src/components/Header.tsx` — the fourth counter item.
- `inspect/ui/src/components/DetailPanel.tsx` — wave-health group, supervision
  timeline, enriched dead-letter cards.
- `inspect/ui/src/components/DetailPanel.css`, `src/components/Header.css` — styles.
- `inspect/ui/src/util/supervision.ts` (new) or `src/util/errors.ts` — the pure
  timeline builder.
- `inspect/ui/fixtures/errors.json`, `fixtures/error-event-wave-health.json`,
  `fixtures/error-event-wave-health-cleared.json`.
- `inspect/ui/mock/serve.mjs` — the simulated feed.
- `inspect/ui/test/**` — extend `errorStore.test.ts`, `errors-fixture.test.ts`,
  and add coverage for the timeline builder.

Touching anything outside `inspect/ui/**`: note it in the completion report
rather than expanding silently. V3-BE owns `inspect/src/**` and runs
concurrently on this wave.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Verticals →
  V3", §"Binding constraints" (constraint 8 in particular: the API contract is
  orchestrator-owned and additive; unknown fields are ignored by the client,
  never required).
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:110-118` (the
  `ErrorSnapshot` block you are extending) and `:173-175` (the `error.*` SSE
  rows, including `error.parked`'s `count: 0` clearing convention — the model
  for `state: 'cleared'`). **Do not edit this file.**
- `inspect/ui/src/sync/errorStore.ts` (whole file — its `:5-24` doc states the
  two update disciplines; yours is the parked one).
- `inspect/ui/src/components/DetailPanel.tsx:274-358` — the section you are
  extending, including the `errorVersion()` memo pattern and `formatTime`.
- `inspect/ui/src/components/Header.tsx:54-75` — the counter strip.
- `inspect/ui/test/errorStore.test.ts` and `test/errors-fixture.test.ts` — the
  test style and the fixture invariants to preserve.
- `inspect/ui/mock/serve.mjs:213-262` — the errors simulation and its
  staggered-mutator style.
- `inspect/ui/src/util/errors.ts` — how pure, toggle-gated derivations are
  written here, if you add any.

Do not modify: `inspect/src/**` (V3-BE owns it), `kernel/**`, `concord/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned),
any plan document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `applyWaveHealth` upserts an `open` row by `id` and **deletes** it on
      `state: 'cleared'`; `counters.waveHealth` is recomputed as the live open
      count after every update and after `applySnapshot`.
- [ ] A snapshot containing a non-`open` row does not add it; a snapshot with no
      `waveHealth` field yields an empty index and a zero counter (older-server
      tolerance).
- [ ] `waveHealthFor(ref)` returns only that ref's open rows and `[]` for an
      unknown ref, matching `parkedFor`'s contract.
- [ ] The two new fixtures and the extended `errors.json` decode against the
      declared types, satisfy the existing `errors-fixture.test.ts` invariants,
      satisfy `counters.waveHealth === waveHealth.length`, and every wave-health
      `edge` names a real edge in `fixtures/topology.json`.
- [ ] The fixtures directory gains **exactly** the two agreed filenames — no
      third file, no rename.
- [ ] The supervision timeline builder is pure and unit-tested: it emits crash →
      restart → re-baseline in that order per restart, omits the crash step when
      `cause` is null, omits the re-baseline step when `reBaselineAtMs` is null,
      never emits a negative claim for a null, and orders restarts newest-first.
- [ ] Dead-letter cards render the invocation summary and disposition chips when
      present, and are byte-for-byte unchanged in the absence of the new fields.
- [ ] Wave-health rows render in the per-cell Errors section, are visually
      distinct from dead letters (informational/amber, not red), carry a visible
      "heuristic" label, and include the server's `description` verbatim.
- [ ] `hasAny` accounts for wave-health rows: a cell with only such a row does
      not show "No local errors".
- [ ] The header strip shows a distinct wave category, styled apart from the
      dead counter, and still toggles the Errors overlay on click.
- [ ] `npm run dev` against the mock shows a wave-health row appear and then
      disappear within one short session, and shows the enriched dead-letter and
      restart data.
- [ ] `npm test` and `npm run build` are green; `npm run typecheck` is clean.
- [ ] No files outside `inspect/ui/**` in the diff. No `node_modules`, no build
      output, no unrelated files.

## Verify

```bash
cd inspect/ui
npm ci          # if node_modules is absent in this worktree
npm test
npm run typecheck
npm run build
```

Manual check (screenshots in the report):

```bash
cd inspect/ui && npm run mock   # offline backend
cd inspect/ui && npm run dev    # then select a cell with error rows
```

If you start anything that binds a port, use an explicitly chosen non-default
one — concurrent sessions squat the usual ports (`00-orchestration.md`
§Sandbox).

## Report on completion

- Checks run and their results, plus screenshots of: the header strip with the
  wave category, a per-cell Errors section showing a wave-health row beside a
  dead letter (the visual distinction should be obvious from the screenshot
  alone), the supervision timeline, and an enriched dead-letter card.
- Files actually touched, and any not in the claim above.
- Confirmation that you added exactly the two agreed fixture filenames, and
  whether `:inspect:test` failed in your worktree on `errors.json` (expected
  until V3-BE merges — report it, do not work around it).
- **Flag to the orchestrator** any place where the shapes in this ticket did not
  survive contact with the rendering — a field the timeline needed and did not
  have, an ambiguity in the disposition vocabulary, or a wave-health field that
  turned out unrenderable. Contract changes are orchestrator-owned; propose,
  never edit `20-api-contract.md`.
- Anything specified here you could not do, and why.
