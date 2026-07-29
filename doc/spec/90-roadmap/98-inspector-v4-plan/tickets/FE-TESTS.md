# FE-TESTS — the rendering layer gets DOM tests: real Solid components, real fixtures

**Status**: Specified — not-started
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 6 · **Branches:** `ticket/fe-tests`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS app (npm +
Vite, **not** wired into Gradle, ~6.1 kLOC, zero runtime dependencies beyond
`solid-js`) rendering a read-only view of a live ComputeNet host's dataflow
graph. Read `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in
full first — it is the decided design for this run and its "Binding
constraints" section governs this ticket (constraint 10 in particular).

Your branch starts from `main` after FE-CANVAS and FE-TOOLTIPS have merged
(same wave, sequenced ahead of you), so the canvas has a viewport with zoom
controls and there is a single fixed-position tooltip layer. Write tests
against the code you actually find; if either ticket was dropped, cover what
exists and say so in the report.

### The testing situation today

- `vite.config.ts` configures vitest as `{ environment: 'node', include:
  ['test/**/*.test.ts'] }`. Note both halves: the environment is **node**, and
  the include glob is **`.test.ts` only** — a `.test.tsx` file is not picked
  up at all today.
- `test/` holds 24 suites. Every one of them tests a **pure module**:
  `layout/layered.ts`, `layout/hulls.ts`, `layout/ports.ts`,
  `layout/constellation.ts`, `nav/route.ts`, `nav/health.ts`, `nav/search.ts`,
  `nav/cold.ts`, `sync/store.ts`, `sync/diff.ts`, `sync/client.ts`,
  `sync/detailClient.ts`, `sync/flowStore.ts`, `sync/errorStore.ts`,
  `util/badges.ts`, `util/errors.ts`, `util/flow.ts`, and the fixture-shape
  suites. **Not one line of `src/components/**` is exercised.**
- `fixtures/` holds 21 JSON fixtures, and they are trustworthy inputs: the
  backend's `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt`
  strict-decodes every one of them against the real DTOs, so a fixture that
  drifts from the contract fails the Kotlin build, not just a hand-written
  assertion here.
- `mock/serve.mjs` is a real, dependency-free offline backend over Node's
  `http` — `npm run mock` — used for manual passes.

### Why this gap has already cost something

The defects that reached evaluation in the v3 run were rendering-layer
defects that a DOM test would have caught at authoring time. Two recorded in
the code itself:

- `Navigator.tsx:225-227` — "Without this the button's only content is a
  decorative SVG, so it exposes no accessible name at all (M4-EVAL)". A
  constellation card with no accessible name.
- `Navigator.tsx:52-56` — every graph card announced itself as "0 restarts"
  because its `title` (which doubles as its accessible name) led with the
  wrong thing (M4-EVAL).

And the interaction that carries the most behaviour per line —
`Canvas.tsx`'s `onSceneClick` deselect (`:135-137`, guarded on
`e.currentTarget === e.target`) versus the card's `stopPropagation` +
`setSelection` (`:249-256`) — has never been verified except by hand.

Line numbers were read on `main` at 2026-07-29, before waves 1–5 and the two
FE tickets ahead of you landed. Search by symbol.

## Problem

The entire rendering layer — `Canvas.tsx`, `DetailPanel.tsx`, `Navigator.tsx`,
`ToggleBar.tsx`, `ValueView.tsx`, `Header.tsx`, `ColdScreen.tsx`, and now
`ZoomControls.tsx` and `Tooltip.tsx` — has **zero** automated coverage. The
pure modules under it are well tested, which makes the gap sharper, not
softer: every bug that survives to a user now lives in the layer between the
tested derivation and the screen. Concretely, nothing today catches:

- a component that renders nothing because a `<Show>` guard inverted;
- a section that renders `undefined` instead of its empty state;
- a click handler wired to the wrong ref;
- an interactive element that exposes no accessible name;
- a toggle that flips a signal nothing reads.

There is also no infrastructure to *write* such a test: no DOM environment, no
component render helper, and an include glob that excludes JSX test files.

## Solution direction

Decided: **vitest + jsdom + `@solidjs/testing-library`**, per-file opt-in.

The alternative — a Playwright smoke suite against `mock/serve.mjs` — is
**rejected**, and the reasons belong in your report if you re-open it: it
needs a browser download in every fresh worktree (workers here run without a
CI image), it is an order of magnitude slower than the <30 s budget, it tests
the mock server's fake dataset as much as the components, and it cannot render
a component in isolation, which is exactly what the empty-state and
value-shape cases need. `@solidjs/testing-library` + jsdom is the conventional
Solid setup and renders the real components with real reactivity.

### 1. Setup — minimal and pinned

- Add to `devDependencies` only, **pinned exactly** (no `^`, no `~`): `jsdom`
  and `@solidjs/testing-library` (plus `@testing-library/dom` if npm does not
  resolve it as a transitive dependency). Do **not** add
  `@testing-library/jest-dom`, `user-event`, or any assertion-sugar package —
  plain vitest `expect` against `textContent`, `getAttribute`, `classList` and
  `querySelectorAll` is enough and keeps the surface small. `dependencies`
  stays exactly `solid-js`.
- Commit the resulting `package-lock.json`.
- **Keep the default environment `node`.** Opt each DOM suite in with the
  per-file docblock `/** @vitest-environment jsdom */` at the top of the file.
  The 24 existing suites keep running in node, unchanged and fast; no global
  config churn.
- Extend `test.include` to pick up JSX suites: `['test/**/*.test.ts',
  'test/**/*.test.tsx']`.
- Known gotchas, so you do not spend the ticket on them: `vite-plugin-solid`
  must transform the test files (it is already a configured plugin, so JSX in
  `test/**` compiles); if components render as empty, the cause is Solid's
  dual browser/server build being resolved to the server condition under
  vitest — resolve it via vitest's `resolve.conditions` / `test.server.deps`
  inlining rather than by mocking Solid. `jsdom` provides no `EventSource` and
  no `ResizeObserver`; both are used by the app (`sync/client.ts:99`,
  FE-CANVAS's canvas) and must be stubbed in the harness. Whatever you needed,
  document it in the harness file and in your report.

### 2. A shared harness — `test/dom/harness.tsx`

The app's Solid modules are **module-level singletons** (`solid/state.ts`,
`solid/toggles.ts`, `solid/detail.ts`, `solid/route.ts`, `solid/layout.ts`),
so state leaks between tests in the same file. Vitest isolates per file by
default — rely on that, and additionally reset explicitly in `beforeEach`
(toggles off, selection null, screen home). Call
`@solidjs/testing-library`'s `cleanup()` in `afterEach`.

Seed through the **real** data path rather than reaching into stores: stub
`globalThis.fetch` with a small router that answers `/topology`, `/graphs`,
`/cell/{ref}`, `/cell/{ref}/state`, `/errors` and `/search` from the checked-in
fixtures, stub `globalThis.EventSource` with an inert class, then call
`connect()` / `fetchGraphs()` / `initDetail()` exactly as `app.tsx` does and
wait for readiness with `@solidjs/testing-library`'s `waitFor` (bounded — no
sleeps, no fixed timeouts). This exercises `sync/client.ts` → `sync/store.ts`
→ `solid/state.ts` → components, which is the seam that actually breaks.
Where a component takes plain props (`ValueView`, `Tooltip`), render it
directly with a fixture value and skip the harness entirely.

### 3. The suites (minimum coverage)

Under `test/dom/`:

1. **`canvas.test.tsx`** — seeded from `fixtures/topology.json` (16 cells, 18
   edges): one `.node-card` per node and one edge element per edge; clicking a
   card selects it (`selection()` is that ref, the card gains `is-selected`
   and `aria-pressed`); clicking the scene background deselects; Enter on a
   focused card selects. If FE-CANVAS landed: the zoom controls render with
   accessible names and pressing `+` changes the transform on the pan wrapper.
2. **`detail-panel.test.tsx`** — with a selection and
   `fixtures/cell-detail.json`: all four section headings render
   ("Descriptor & placement", "State", "Flow", "Errors"), the descriptor grid
   shows class/color/ports/host/net/generation/lifecycle/links, and the F-5
   footnote is present. Empty states: no selection → "Select a node to inspect
   it."; no errors for the cell → "No local errors"; state from
   `fixtures/cell-state-unavailable.json` → the unavailable line, not a crash
   and not a blank.
3. **`toggle-bar.test.tsx`** — each toggle checkbox flips its
   `solid/toggles.ts` signal; with the canvas mounted, enabling Errors and
   State renders the corresponding overlay layers (badges / chips) and
   disabling them removes those elements.
4. **`navigator.test.tsx`** — from `fixtures/graphs.json` +
   `fixtures/topology.json`: one graph card per summary with its name, counts
   and health pills; one constellation card per component, each with a
   non-empty accessible name (the M4-EVAL defect, now pinned); clicking a card
   enters that graph (`screen()` becomes `'graph'`, `currentGraphId()` is that
   id).
5. **`value-view.test.tsx`** — pure component, one case per encoder shape,
   using `fixtures/cell-state-*.json`: `$table` (headers, rows, a tombstoned
   row marked), `$truncated` ("showing N of M"), `$opaque` (type and text), a
   scalar, and a nested object/array tree.

Assert **semantics** — text, roles, accessible names, class state, signal
values. No DOM snapshots: the layout is analytic and coordinate assertions
will churn on every geometry tweak for no signal.

### 4. Budget and determinism

The whole `npm test` run (existing 24 suites + the new DOM suites) stays under
**30 s** wall on a normal laptop, with no network, no real SSE, no wall-clock
sleeps, and no dependence on the order tests run in. Report the measured time.

### 5. If a test finds a bug

Report it. Fix it inline **only** if it is a one-line label/accessibility
defect of the M4-EVAL kind; anything larger is a finding for the orchestrator,
not a refactor smuggled into a test ticket. A test that documents a real
current behaviour you disagree with is fine — say so in the report rather than
changing the component.

### Exclusions

No Playwright, no browser downloads, no visual-regression tooling. No DOM
snapshot assertions. No changes to component behaviour beyond the narrow
exception above. No backend changes and no fixture edits — `FixtureContractTest`
owns fixture shape, and a fixture edited to suit a frontend test is a contract
change in disguise. No new runtime dependency.

## Files expected to touch

- `inspect/ui/package.json` — pinned `devDependencies` additions, nothing else.
- `inspect/ui/package-lock.json` — committed.
- `inspect/ui/vite.config.ts` — `test.include` extended for `.test.tsx`; any
  resolve/deps setting the Solid+jsdom combination genuinely requires
  (comment why, in the file).
- `inspect/ui/test/dom/harness.tsx` — **new**: render helper, fetch/EventSource
  /ResizeObserver stubs, reset + cleanup.
- `inspect/ui/test/dom/canvas.test.tsx`, `detail-panel.test.tsx`,
  `toggle-bar.test.tsx`, `navigator.test.tsx`, `value-view.test.tsx` — **new**.
- `inspect/ui/README.md` — how to run the DOM suites and where they live.

File claim: `inspect/ui/**` only. Touching anything outside it: note it in the
completion report rather than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — §"Binding
  constraints" (esp. 10), §"Verticals → FE track", §"Current implementation
  facts" (the frontend inventory).
- `inspect/ui/vite.config.ts` — the current vitest block you are extending.
- `inspect/ui/test/fixture.test.ts` and `test/layout.test.ts` — the existing
  test idiom, and how fixtures are imported (`import fixture from
  '../fixtures/topology.json'`).
- `inspect/ui/src/app.tsx` — the exact init order (`initTheme`, `initDetail`,
  then `onMount`: `initRoute`, `connect`, `fetchGraphs`) your harness mirrors.
- `inspect/ui/src/solid/state.ts` — the singleton store, `connect()`, and the
  `TopologyClient` construction your `fetch`/`EventSource` stubs must satisfy;
  `inspect/ui/src/sync/client.ts:76-110` for the request URLs and the
  `EventSource` usage.
- `inspect/ui/src/solid/detail.ts` — `initDetail()` and the hardwired
  `defaultDetailTransport` (`sync/detailClient.ts:35-41`), i.e. why the detail
  panel is seeded by stubbing `fetch` rather than by injecting a transport.
- `inspect/ui/src/components/` — the five components under test, in full.
- `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt` — why the
  fixtures are a trustworthy input and must not be edited here.
- `doc/spec/90-roadmap/97-inspector-plan/tickets/M4-FE.md` and the M4-EVAL
  notes quoted in `Navigator.tsx` — the class of defect this suite exists to
  catch.

Do not modify: `inspect/src/**`, `kernel/**`, `concord/**`,
`inspect/ui/fixtures/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`
(orchestrator-owned), any plan document other than this ticket's `**Status**:`
line.

## Acceptance criteria

- [ ] `npm test` runs the 24 existing node-environment suites **and** the new
      DOM suites, all green, in under 30 s; the run is deterministic across
      three consecutive invocations.
- [ ] The DOM suites render real Solid components in jsdom via
      `@solidjs/testing-library` — no hand-rolled render shim, no mocked
      `solid-js`.
- [ ] Canvas: nodes and edges render from `fixtures/topology.json`; card click
      selects; background click deselects; keyboard select works.
- [ ] DetailPanel: all four sections render for a fixture cell, including the
      no-selection, no-errors and state-unavailable empty states.
- [ ] ToggleBar: each toggle flips its signal and the corresponding canvas
      overlay appears and disappears.
- [ ] Navigator: graph cards and constellation cards render from fixtures,
      every interactive card has a non-empty accessible name, and clicking one
      enters the graph.
- [ ] ValueView: `$table` (with a tombstoned row), `$truncated`, `$opaque`,
      scalar and tree shapes each covered.
- [ ] New `devDependencies` are pinned to exact versions and limited to what
      jsdom + Solid rendering actually needs; `dependencies` is unchanged;
      `package-lock.json` is committed.
- [ ] No fixture file is modified; no component behaviour is changed beyond a
      reported one-line accessibility fix, if any.
- [ ] `npm run typecheck` and `npm run build` are green.
- [ ] No unrelated files in the diff; nothing outside `inspect/ui/**`; no
      `node_modules/` in the diff.

## Verify

```bash
cd inspect/ui
npm ci
npm test
npm run typecheck
npm run build

# determinism + budget
time npm test && time npm test
```

## Report on completion

- Checks run and their results; the measured `npm test` wall time.
- The exact vitest/Vite configuration the Solid + jsdom combination required,
  and which of it was non-obvious (future readers of `vite.config.ts` should
  not have to rediscover it).
- Exact `devDependencies` added, with pinned versions and one line each on why.
- What the harness stubs (`fetch`, `EventSource`, `ResizeObserver`, …) and
  where a future test would extend it.
- Every defect the new tests found, whether you fixed it, and why.
- Any component you could not test without changing it — that is a design
  finding for the orchestrator, so name the component and the obstacle.
- Whether FE-CANVAS and FE-TOOLTIPS were present in your base, and what you
  covered of each.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
