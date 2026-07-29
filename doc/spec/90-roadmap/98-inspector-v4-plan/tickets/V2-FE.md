# V2-FE — activity log, attention band, and suspended emphasis in the UI

**Status**: Implemented — merged
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session.
**Wave:** 4 · **Branches:** `ticket/v2-fe` · **Parallel with:** `V2-KERNEL` and
`V2-BE` (you own `inspect/ui/**` only; code against the shapes fixed below plus
your own fixtures — do **not** wait for the backend, and do **not** read or edit
`inspect/src/**`)

## Context

`inspect/ui/` is the Inspector frontend: SolidJS + Vite, npm only (not wired
into Gradle), zero runtime dependencies beyond `solid-js`, ~6.1 kLOC. It talks
to a read-only backend over `GET /api/inspect/*` plus one SSE stream at
`/api/inspect/events`.

How the app is put together:

- `src/app.tsx` — two screens switched on `screen()`: Navigator (home) and the
  graph screen (`ToggleBar` + `.app-body` holding `Canvas` and `DetailPanel`,
  `:30-53`).
- `src/sync/client.ts` — framework-free `TopologyClient`: SSE parse, `seq`-gap
  refetch, and a `KNOWN_EVENT_KINDS` gate (`:155-160`) that silently ignores
  event kinds it does not know (additive evolution).
- `src/api/types.ts` — every server shape, the `InspectEvent` union (`:376`)
  and `KNOWN_EVENT_KINDS` (`:404-415`).
- `src/solid/state.ts:69-135` — the SSE event switch: one `case` per kind,
  each forwarding into a pure store.
- The store pattern, per feed: a pure class in `src/sync/` +
  a thin Solid wrapper in `src/solid/` exporting the store and a version
  signal. `src/sync/errorStore.ts` + `src/solid/errors.ts` are the reference
  pair (`solid/errors.ts:11-15` — store, `subscribe`, version signal; `:24-38`
  — snapshot fetch on connect with a swappable transport test seam).
- `src/components/DetailPanel.tsx` — four stacked sections on selection
  (`:42-45`); `DescriptorSection` renders the descriptor grid (`:66-152`).
- `src/components/Canvas.tsx` — hybrid SVG + absolutely-positioned DOM node
  cards; `EdgeLine` (`:399-500`) draws edges, node cards are built at
  `:230-260`.
- Tests: 24 vitest suites in `inspect/ui/test/`, **node environment, pure
  modules only — there are no DOM/component tests** (that is a separate wave-6
  ticket, `FE-TESTS`; do not start it here). Fixtures live in
  `inspect/ui/fixtures/` and are imported directly by tests
  (`test/errors-fixture.test.ts:5-9` is the pattern).

This ticket is the UI half of the **V2 activity vertical**
(`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md:44-50`).

## Problem

1. **No activity history in the UI.** The client learns a cell's *current*
   lifecycle from `lifecycle` events and nothing about its past — no record
   that a cell was passivated, drained, woken or restarted, or when.
2. **Attention is a permanent em dash.** `DetailPanel.tsx:139-140` renders
   `{d().attention ?? '—'}`, and the server has answered null for that field
   since M1. `V2-BE` populates it in this wave.
3. **Suspended cells are only partly emphasized.** Some styling exists
   (`Canvas.css:129-132`, applied at `Canvas.tsx:236-241`); the v2 mock-up's
   treatment is stronger. See §3 — you must verify before claiming anything is
   missing.

## The contract shapes you code against

Fixed by `V2-BE` in this same wave and **flagged** to the orchestrator for
`20-api-contract.md` (never edit that file). Code against exactly this:

```jsonc
// GET /api/inspect/activity   — catch-up, oldest first, at most 200 entries
{ "entries": [ /* ActivityEntry */ ] }

// ActivityEntry
{
  "ref": "<encoded CellRef>",   // same encoding as every other row (Node.ref)
  "kind": "activated",          // activated | passivated | drained | woken | restarted
  "atMs": 1730000000000,        // wall clock at capture
  "generation": 3               // optional; present on "restarted", absent otherwise
}

// SSE frame: a new event kind, payload = one ActivityEntry
{ "seq": 42, "kind": "activity", "payload": { /* ActivityEntry */ } }
```

Kind meanings, for your labels and tooltips: `passivated` = the cell was
suspended (explicitly or by supervision); `activated` = it resumed;
`drained` = its host finished draining; `woken` = a user pressed the wake
button and this cell was in the blast radius; `restarted` = supervision
restarted it, and `generation` is the new generation. A wake legitimately
produces both a `woken` and an `activated` entry — render both, do not
de-duplicate.

Do not invent additional fields, query parameters, or endpoints. If a shape
here proves unworkable, say so in the report; the orchestrator relays it — you
must not resolve it by editing `inspect/src/**` or the contract.

## Implement (prescriptive — stay within this scope)

### 1. Activity store + feed wiring

1. `src/api/types.ts`: add `ActivityEntry`, the `GET /activity` body type, an
   `'activity'` member of the `InspectEvent` union, and `'activity'` to
   `KNOWN_EVENT_KINDS` (`:404-415`) — without it the client drops the frame
   (`sync/client.ts:155-157`).
2. `src/sync/activityStore.ts`: a pure class, mirroring
   `sync/errorStore.ts` — `applySnapshot(body)`, `apply(entry)`, a
   `subscribe`-driven change notification, `entries` newest-first, and a
   per-ref accessor (`entriesFor(ref)`). **Bounded: keep at most 200 entries,
   evicting oldest first**, matching the server ring, so a long session cannot
   grow it without bound.
3. `src/solid/activity.ts`: the Solid wrapper — exported store, version signal,
   `fetchActivitySnapshot()` with a swappable transport (copy
   `solid/errors.ts:11-38` and `sync/errorsClient.ts`). Fetch on connect from
   the same place errors are fetched (`solid/state.ts`'s topology
   `onSnapshot` handler) so a reconnect re-syncs the log.
4. `src/solid/state.ts:69-135`: one new `case 'activity'` forwarding into the
   store, exactly like `case 'error.restart'` (`:121`).

### 2. Activity log panel

5. `src/components/ActivityLog.tsx` + `ActivityLog.css`: a timestamped log,
   newest first, one row per entry: local time (`HH:MM:SS`), a per-kind badge
   (distinct colour/glyph per kind, using existing tokens in
   `src/styles/tokens.css` — do not add a palette), the cell's name where the
   topology store knows it and the short ref otherwise, and the generation on a
   `restarted` row.
6. **Filter**: host-wide by default, with a control ("Only selected cell") that
   restricts rows to `selection()` (`solid/state.ts`). Inert/disabled with a
   clear label when nothing is selected. The filter is component-local state —
   not in the URL, not in `solid/toggles.ts`.
7. **Bounded rendering**: render at most the newest 100 rows regardless of what
   the store holds, with a one-line note when rows are hidden. Never render an
   unbounded list.
8. **Empty state**: a plain sentence when the log is empty ("No lifecycle
   activity yet."), never a spinner or an error.
9. Mount it in `src/app.tsx` inside the graph screen only, below `.app-body`
   (`app.tsx:38-50`), as a collapsible bottom strip that starts collapsed —
   the canvas and detail panel keep their current sizes when it is collapsed.
   Do not touch the Navigator screen.

### 3. Attention band in the detail panel

10. `DetailPanel.tsx:139-140`: render the band when `attention` is non-null
    (capitalize the server's string for display — the server sends lowercase
    band names; render whatever string arrives, do not switch exhaustively on
    a closed set, so an unknown future value still displays). Keep the `'—'`
    fallback for null, and add a short `title=` explaining that null means the
    cell's host runs without an attention policy.

### 4. Suspended emphasis on the canvas

11. **Verify first, then scope to the delta.** `.node-card.is-suspended`
    already applies `opacity: var(--ghost-opacity)` + `border-style: dashed`
    (`Canvas.css:129-132`), driven by `rec()!.lifecycle === 'SUSPENDED'`
    (`Canvas.tsx:236-241`) — `Node.lifecycle` is already in the model. Do not
    re-add what is there.
12. The delta to add: (a) a small `suspended` tag inside the node card's top
    row (`Canvas.tsx:257-262` area), styled like `DetailPanel`'s `.detail-tag`;
    (b) the same ghosting for **edges incident on a suspended cell** — pass a
    `dimmed` prop into `EdgeLine` (`Canvas.tsx:188-199`, component at `:399`)
    computed from either endpoint's lifecycle, and style it in `Canvas.css`
    beside the existing `.edge` rules.
13. If your verification shows one of those two is already present, say so in
    the report and skip it. Do not claim a gap you did not check.

### 5. Fixtures

14. Add **exactly** these two files, at these names — `V2-BE`'s
    `FixtureContractTest` strict-decodes every file in this directory against a
    hand-written map, so the names are load-bearing:
    - `inspect/ui/fixtures/activity.json` — the `GET /activity` body: at least
      one entry of **each** of the five kinds, `generation` present only on the
      `restarted` entry, refs that are real nodes in
      `inspect/ui/fixtures/topology.json` (`test/errors-fixture.test.ts:25-31`
      asserts exactly this property for the errors fixture — mirror it).
    - `inspect/ui/fixtures/activity-event.json` — one SSE envelope
      (`{"seq":…,"kind":"activity","payload":{…}}`), matching the shape of
      `fixtures/error-event-restart.json`.
15. Do not add any other fixture, and do not rename or edit existing ones.
16. Optionally extend `mock/serve.mjs` so the offline dev backend serves
    `/api/inspect/activity` and emits `activity` frames — useful for a manual
    screenshot, and it stays inside your file claim.

## Exclusions

No zoom/pan/fit, no tooltip rework, no DOM/component test harness (all wave 6).
No new toggle in `ToggleBar`/`solid/toggles.ts`. No changes to the error, flow,
state or cold feeds. No new npm dependency. No changes to routing/deep-linking.
Nothing under `inspect/src/**`, `kernel/**`, `concord/**`, or any file under
`doc/spec/` other than this ticket's `**Status**:` line.

## Files expected to touch

- `inspect/ui/src/api/types.ts` — new types, union member, `KNOWN_EVENT_KINDS`.
- `inspect/ui/src/sync/activityStore.ts`, `inspect/ui/src/sync/activityClient.ts` — new.
- `inspect/ui/src/solid/activity.ts` — new.
- `inspect/ui/src/solid/state.ts` — one new SSE `case`, one snapshot fetch call.
- `inspect/ui/src/components/ActivityLog.tsx`, `ActivityLog.css` — new.
- `inspect/ui/src/app.tsx` — mount the panel on the graph screen.
- `inspect/ui/src/components/DetailPanel.tsx` — the attention row.
- `inspect/ui/src/components/Canvas.tsx`, `Canvas.css` — the suspended delta.
- `inspect/ui/fixtures/activity.json`, `activity-event.json` — new.
- `inspect/ui/test/**` — new vitest suites (see Acceptance criteria).
- `inspect/ui/mock/serve.mjs` — optional.

Touching files outside `inspect/ui/**`: don't. Note any need to in the report
instead — `V2-BE` owns `inspect/src/**` and runs concurrently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Verticals →
  V2" and §"Binding constraints" (2 — observation is causal, so nothing you add
  may trigger an observe; 6 — viz never blocks; 8 — the contract is
  orchestrator-owned; 10 — `inspect/ui` stays npm/Vite).
- `inspect/ui/src/sync/errorStore.ts` and `inspect/ui/src/solid/errors.ts` —
  the exact store pattern to copy.
- `inspect/ui/src/sync/client.ts:115-160` — SSE dispatch and the
  `KNOWN_EVENT_KINDS` gate.
- `inspect/ui/src/solid/state.ts:69-135` — the event switch you extend.
- `inspect/ui/src/components/DetailPanel.tsx:62-152` — the descriptor grid and
  its `.detail-tag` idiom (`:127-130`).
- `inspect/ui/src/components/Canvas.tsx:178-262` (edges then node cards) and
  `:399-500` (`EdgeLine`); `inspect/ui/src/components/Canvas.css:118-140`.
- `inspect/ui/test/errors-fixture.test.ts` and `test/errorStore.test.ts` — the
  fixture-plus-store test pattern, including cross-checking refs against
  `fixtures/topology.json`.
- `inspect/ui/README.md` — how to run dev, mock and tests.

Do not modify: `inspect/src/**` (`V2-BE`), `kernel/**` (`V2-KERNEL`),
`concord/**`, `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`.

## Acceptance criteria

- [ ] `fixtures/activity.json` loads through `ActivityStore` and yields the
      five kinds; every `ref` in it is a node in `fixtures/topology.json`;
      `generation` is present only on the `restarted` entry.
- [ ] `fixtures/activity-event.json` applies through the same store path the
      SSE `case` uses, and appends one entry.
- [ ] The store is bounded at 200 and evicts oldest-first; entries are ordered
      newest-first for rendering; `entriesFor(ref)` returns only that ref's.
- [ ] `'activity'` is in `KNOWN_EVENT_KINDS`; an unknown/absent
      `activity` feed changes nothing else (the panel shows its empty state,
      no error surfaces).
- [ ] Row derivation is a pure, unit-tested module: given entries plus the
      selected ref and the filter flag, it returns the rows to render, capped
      at 100, with labels and timestamps — tested without a DOM.
- [ ] The attention row renders the server's string when non-null and `'—'`
      when null; an unrecognized future value still displays rather than
      crashing or rendering blank.
- [ ] Suspended emphasis: verified what already exists, added only the delta,
      and the report says which of the two items were needed.
- [ ] `npm run typecheck`, `npm test` and `npm run build` are green.
- [ ] No new runtime dependency in `package.json`; no files outside
      `inspect/ui/**` in the diff; no `node_modules`/`dist` in the diff.

## Verify

```bash
cd inspect/ui
npm ci          # first time in a fresh worktree
npm run typecheck
npm test
npm run build
```

Optional manual check (recommended, and cheap): `npm run mock` in one shell and
`npm run dev` in another, then screenshot the activity panel with the filter on
and off. Use a non-default port if the usual one is taken — concurrent sessions
squat common ports.

## Report on completion

- Checks run and their results; screenshots if you did the manual check.
- The exact JSON of both fixtures (they are the contract's only concrete
  witness until `V2-BE` lands, and the orchestrator folds the shape into
  `20-api-contract.md`).
- What suspended emphasis already existed vs. what you added, with the
  file/line evidence you checked.
- Anything in §"The contract shapes you code against" that proved unworkable —
  flag it; do not fix it in `inspect/src/**`.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
