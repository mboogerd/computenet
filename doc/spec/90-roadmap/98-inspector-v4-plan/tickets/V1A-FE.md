# V1A-FE — The observed cell's value goes live: change-gated refetch, row-flash, onChange log

**Status**: Partial — in-progress. (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first word
of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`
**Wave:** 2 · **Branches:** `ticket/v1a-fe`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS + Vite app
(npm, deliberately **not** wired into Gradle) that renders a live ComputeNet
dataflow graph from the `:inspect` backend's HTTP + SSE API. Zero runtime
dependencies beyond `solid-js`; 24 pure-module vitest suites in `test/`, node
environment, no DOM/component tests. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Verticals →
V1a" and §"Binding constraints" first.

How the state vertical works today:

- Selecting a node drives `DetailController`
  (`inspect/ui/src/sync/detailClient.ts:63-154`): one `POST /cell/{ref}/observe`
  per selection, one `DELETE` for the previously selected ref, `GET /cell/{ref}`
  for the descriptor and `GET /cell/{ref}/state` for the value. An `epoch`
  counter discards responses that land after a re-selection
  (`detailClient.ts:92-113`, `:142-153`). Framework-free by design, so it is
  unit-testable against a mock transport (`test/detailClient.test.ts`).
- The SSE router (`inspect/ui/src/solid/state.ts:105-110`) forwards every
  `state.summary` event to `solid/detail.ts:80-87`, which calls
  `controller.onSummary(payload.ref)` and stores the payload per ref for the
  canvas state chip (`solid/detail.ts:16-23`, rendered at
  `src/components/Canvas.tsx:288-300`).
- `DetailController.onSummary` (`detailClient.ts:125-131`) refetches state
  **unconditionally** for the selected, non-descriptor-only ref.
- `DetailPanel.tsx`'s `StateSection` (`:159-206`) renders the frontier chip,
  `staleMs`, `kind`, and hands the value to `ValueView`
  (`src/components/ValueView.tsx:22-24`), which renders `$table` as a table
  (`:99-118`), arrays/objects as an indented tree, `$truncated` as a note and
  `$opaque` as a code block. `ValueView` is explicitly non-reactive: each fetch
  replaces the whole value.
- Offline development runs against `mock/serve.mjs`, a real fake backend. It
  emits a `state.summary` only when it mutates its fake table, every 2 s
  (`mock/serve.mjs:185-205`), unlike its flow feed which emits every second
  (`mock/serve.mjs:289-309`).

The backend ticket V1A-BE runs **in parallel with you on this wave**. It is
making `state.summary` a coalesced 1 Hz window per observed cell that publishes
on change *and* publishes even when quiet while the observation is open, plus
one trailing window after release. You code against the contract's existing
payload fields only — `ref`, `cardinality`, `frontier`, `staleMs`
(`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:172`,
`src/api/types.ts:295-300`) — and must ignore unknown fields, per the contract's
additive-evolution rule.

## Problem

1. **Refetch is not change-gated.** `detailClient.ts:128-131` issues a
   `GET .../state` for *every* summary naming the selected ref. Once V1A-BE
   publishes quiet windows every second, that turns into a 1 Hz polling loop
   against a cell whose value has not changed — the exact thing the coalescing
   work exists to avoid.
2. **The value shows no change.** `ValueView` replaces its content wholesale
   (`ValueView.tsx:22-24`); a row appearing or a cell changing is visually
   indistinguishable from a re-render. The v1 mock-up's row-flash is the cue.
3. **There is no history.** The panel shows the current value and nothing about
   the sequence of changes that produced it — the v1 mock-up's third column
   (`10-design-notes.md` §Verticals V1a: "an onChange log panel — one entry per
   settled effective change").
4. **Offline dev cannot exercise any of it.** The mock emits change-only
   summaries at 2 s, so quiet-window handling, the change predicate and the
   flash path have nothing to run against under `npm run dev`.

## Solution direction

Prescriptive. Implement all four items.

### 1. Change-gated live refetch

- Add a pure module — `src/sync/summaryChange.ts` — exporting a predicate:

  ```ts
  export function indicatesChange(
    prev: StateSummaryPayload | undefined,
    next: StateSummaryPayload,
  ): boolean
  ```

  Return `true` when **any** of the following holds, else `false`:
  - `prev` is `undefined` (first summary seen for this ref since selection);
  - `next.frontier` differs from `prev.frontier` (compare `source` **and**
    `counter`; a null/non-null transition counts as different);
  - `next.cardinality !== prev.cardinality`;
  - `next.staleMs < prev.staleMs`.

  The last clause is the load-bearing one and is V1A-BE's stated guarantee:
  `staleMs` is computed at publish time from the last effective change, so it
  decreases exactly when a change settled in that window and grows monotonically
  across quiet windows. Do not invent additional fields; unknown fields on the
  payload must be ignored, never required.

- Change `DetailController.onSummary(ref: Ref)` to
  `onSummary(payload: StateSummaryPayload)` (`detailClient.ts:125-131`). Keep
  the existing guards exactly as they are — not the current ref, or
  `descriptorOnly`, still returns immediately. Then hold the last-seen payload
  for the current ref and call `loadState(ref, this.epoch)` only when
  `indicatesChange` says so. Clear the held payload on `select()` and
  `deselect()` so a re-selection always refetches once. The existing epoch guard
  (`detailClient.ts:151-153`) continues to discard responses that land after a
  re-selection — do not add a second staleness mechanism.
- Update the one caller, `solid/detail.ts:82-87`, to pass the whole payload.
  Keep storing every summary (quiet ones included) in `stateSummaries` so the
  canvas chip's staleness stays honest — only the *refetch* is gated.
- No polling loops, no timers: the SSE summary is the only trigger.

### 2. Row-flash on changed and added rows

- Add a pure module — `src/sync/valueDiff.ts` — that diffs the previously
  rendered `Value` against the new one and returns the row keys to flash:

  ```ts
  export interface RowFlash { added: ReadonlySet<string>; changed: ReadonlySet<string> }
  export function diffRows(prev: Value | undefined, next: Value): RowFlash
  ```

  Prescribed rules:
  - Only `$table` values and plain arrays produce keys; everything else returns
    two empty sets.
  - `$table` row key: the first cell's scalar rendering when the table has more
    than one column, otherwise the whole row's stringified cells. Use
    `rowCells()` / `isTombstoneRow()` from `src/api/types.ts` rather than
    re-deriving row shape — a row is either a cell array or
    `{ cells, tombstoned }`.
  - Plain-array element key: the element's stringified content. Skip a
    `$truncated` marker element.
  - `added` = keys present in `next` and absent from `prev`. `changed` = keys
    present in both whose remaining cell contents differ.
  - `prev === undefined` (first render after selection) returns two empty
    sets — a first paint is not a change.
  - Duplicate keys within one value: keep the first occurrence; never throw.
- `DetailPanel.tsx`'s `StateSection` holds the previously rendered value and
  passes the computed `RowFlash` to `ValueView` as an optional prop. `ValueView`
  stamps `data-flash="added" | "changed"` on the matching `<tr>` / `<li>`
  (`ValueView.tsx:99-118` for the table path). Do not make `ValueView` reactive
  or stateful beyond this — it stays a pure render of its props.
- Animation lives in `src/components/ValueView.css`: a brief highlight
  (~600–900 ms, single iteration) keyed off `[data-flash]`, using existing
  tokens from `src/styles/tokens.css`. Disable it under
  `@media (prefers-reduced-motion: reduce)` — follow the precedent at
  `src/components/Canvas.css:248-262`. `src/solid/motion.ts:15` exposes
  `prefersReducedMotion()` if you also need the JS-side read; CSS alone is
  sufficient here and is preferred.

### 3. onChange log panel

- Add a pure module — `src/sync/changeLog.ts` — a bounded per-ref log:
  - Entry: `{ atMs: number; cardinality: string | null; frontier: Frontier | null }`.
  - Append **only** for summaries where `indicatesChange` is true (reuse the
    module from item 1 — one definition of "changed", not two).
  - Cap at the last 50 entries, newest first when read.
  - `clear()` on selection change; the log is per selected cell and does not
    survive selecting a different one.
  - Framework-free with a `subscribe()`/version notification, mirroring
    `src/sync/flowStore.ts:27-66`'s shape (its `DECAY_AFTER_MISSED_WINDOWS`
    comment is the house style for explaining a client-side rule).
- Render it inside `DetailPanel.tsx`'s `StateSection` (`:159-206`), below the
  value: a compact list of timestamp · cardinality · frontier, with an empty
  state ("no changes observed yet"). Reuse `formatTime`
  (`DetailPanel.tsx:356-358`) and the existing frontier-chip formatting
  (`DetailPanel.tsx:160-163`). Respect the section's existing cold/remote
  guards — a cold or remote selection is not observed, so it has no log.

### 4. Mock server: coalesced summaries offline

Update `mock/serve.mjs` so offline dev exercises the real path:

- While a ref is observed, broadcast a `state.summary` **every 1000 ms**
  (mirroring the flow feed's cadence at `mock/serve.mjs:289-309`), not only when
  it mutates.
- Mutate the fake table on roughly every 4th window, so most published windows
  are quiet and the change predicate is genuinely exercised.
- `staleMs` must reset to ~0 on a change window and grow by ~1000 per quiet
  window — the FE's predicate depends on that shape, so a mock that always sends
  `staleMs: 0` (as today, `mock/serve.mjs:185-205`) would silently mask a bug.
- On `DELETE .../observe` (`stopObserving`, `mock/serve.mjs:207-212`), emit
  exactly one trailing summary, then stop.

## Files expected to touch

- `inspect/ui/src/sync/summaryChange.ts` — new; the change predicate.
- `inspect/ui/src/sync/valueDiff.ts` — new; the row differ.
- `inspect/ui/src/sync/changeLog.ts` — new; the bounded per-cell change log.
- `inspect/ui/src/sync/detailClient.ts` — `onSummary` takes the payload and
  gates the refetch.
- `inspect/ui/src/solid/detail.ts` — pass the payload through; feed the change
  log; clear it on selection change.
- `inspect/ui/src/components/DetailPanel.tsx` + `DetailPanel.css` — the log
  panel in the State section; hold the previous value for the differ.
- `inspect/ui/src/components/ValueView.tsx` + `ValueView.css` — the optional
  flash prop and its animation.
- `inspect/ui/mock/serve.mjs` — coalesced summary emission.
- `inspect/ui/test/*.test.ts` — new suites for the three pure modules, plus
  updates to `test/detailClient.test.ts` for the new `onSummary` signature.

Touching files outside `inspect/ui/**`: not permitted — note it in the
completion report instead. V1A-BE owns `inspect/src/**` and runs concurrently on
this wave.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §Verticals
  (V1a), §"Binding constraints" 6, 8, 10 — viz never blocks, contract is
  orchestrator-owned, `inspect/ui` stays npm/Vite.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:172` — the
  `state.summary` payload, and the file's header rule that unknown fields must
  be ignored by the client.
- `inspect/ui/src/sync/detailClient.ts` (whole file) — the epoch guard and P6
  subscription discipline you are extending, not replacing.
- `inspect/ui/src/sync/flowStore.ts` — the house pattern for a framework-free,
  bounded, unit-tested client store fed purely from an SSE feed.
- `inspect/ui/test/detailClient.test.ts` — the mock-transport test pattern
  (`:172` already asserts a summary for a descriptor-only selection does not
  pull state in).
- `inspect/ui/src/api/types.ts:293-306` (`StateSummaryPayload`) and its `Value`
  helpers (`tableOf`, `rowCells`, `isTombstoneRow`, `truncatedOf`).
- `inspect/ui/src/components/Canvas.css:248-262` — the reduced-motion
  precedent.

Do not modify: `inspect/src/**` (V1A-BE owns it, and it is another worker's
branch this wave), `concord/**`, `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`,
any plan document other than this ticket's `**Status**:` line.

Known, out of scope: `./gradlew :concord:docLints` currently reports fatal
Status-header findings on this plan folder's own documents (`00-orchestration.md`
and `10-design-notes.md` use the word `Planned`, which is outside the lint's
vocabulary). They are pre-existing, not yours to fix.

**Do not add a new file under `inspect/ui/fixtures/`.** `FixtureContractTest`
(`inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:88-95`)
asserts that the fixture directory's contents and its Kotlin decoder map are
*exactly* equal, so a new fixture filename requires a paired `inspect/src` edit
this ticket may not make — it would turn `./gradlew :inspect:test` red at merge.
Put sample `state.summary` payloads inline in your vitest suites instead.
Extending an **existing** fixture in place is allowed, and its shape must stay
strictly contract-conformant (that test strict-decodes every fixture, rejecting
unknown fields). If you conclude a new checked-in fixture is genuinely
necessary, flag it in the report rather than adding it.

## Acceptance criteria

- [ ] `indicatesChange` is a pure, exported, unit-tested function; its four
      true-cases and the quiet-window false-case are each covered, including
      null↔non-null frontier transitions and a monotonically growing `staleMs`
      across several quiet windows.
- [ ] A run of quiet summaries for the selected ref triggers **zero**
      `fetchState` calls (asserted against a mock transport); the next summary
      that indicates change triggers exactly one.
- [ ] A summary for a non-selected or descriptor-only ref still triggers
      nothing (existing behavior preserved — `test/detailClient.test.ts:172`).
- [ ] A response that lands after a re-selection is still discarded by the
      existing epoch guard.
- [ ] `diffRows` is pure and unit-tested: added rows, changed rows, unchanged
      rows, first render (both sets empty), single-column vs multi-column
      tables, tombstoned rows, plain arrays, `$truncated` markers, and
      duplicate keys.
- [ ] Flashed rows carry `data-flash` in the rendered output and the animation
      is suppressed under `prefers-reduced-motion: reduce`.
- [ ] The change log is bounded to 50 entries, appends only on indicated
      changes, clears on selection change, and renders in the State section
      with an empty state.
- [ ] `npm run dev` against the mock shows: a live-updating value, flashes on
      changed/added rows only, a growing change log, and no request storm during
      quiet windows (state check the network panel or the mock's own log).
- [ ] No new runtime dependency; no DOM-test framework introduced (component
      tests are FE-TESTS' ticket in wave 6) — the new tests are pure-module
      vitest suites.
- [ ] No file outside `inspect/ui/**` in the diff; no new file under
      `inspect/ui/fixtures/`.

## Verify

```bash
cd inspect/ui
npm ci          # first run in a fresh worktree
npm test
npm run typecheck
npm run build
```

Manual pass (record what you saw in the report):

```bash
cd inspect/ui && npm run mock   # terminal 1
cd inspect/ui && npm run dev    # terminal 2 — select a node, watch the State section
```

## Report on completion

- Checks run and their results, including the manual mock pass.
- Files actually touched, and any not in the claim above.
- **Flag to the orchestrator:**
  1. Your refetch gate depends on V1A-BE's guarantee that `staleMs` is computed
     at publish time (decreases on change, grows across quiet windows). Say
     explicitly that you relied on it, so C2 can verify the two halves agree
     against a real server.
  2. If V1A-BE adds an additive marker field (a per-window change count or a
     `changed` flag), the predicate should later prefer it over the `staleMs`
     heuristic. Note this as a follow-up rather than guessing a field name now.
  3. Whether you needed a checked-in `state.summary` fixture and were blocked by
     the `FixtureContractTest` coupling described above (it needs a paired
     `inspect/src` decoder-map entry, which is out of this ticket's claim).
  4. The change log records one entry per *changed window*, not per underlying
     settled change — coalescing makes those differ under load. Confirm whether
     the panel's wording should say so.
- Anything specified here you could not do, and why.
