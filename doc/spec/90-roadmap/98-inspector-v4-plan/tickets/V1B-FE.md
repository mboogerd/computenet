# V1B-FE — Pin multiple cells for simultaneous observation, surface the observation cost

**Status**: Implemented — merged
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`
**Wave:** 3 · **Branches:** `ticket/v1b-fe`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS + Vite app
(npm, deliberately **not** wired into Gradle) that renders a live ComputeNet
dataflow graph from the `:inspect` backend's HTTP + SSE API. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Verticals →
V1b" and §"Binding constraints" (especially 2/P6, 6, 8, 9, 10) first.

How the observation lifecycle works today (M1, unchanged since):

- `DetailController` (`inspect/ui/src/sync/detailClient.ts:63-154`) allows
  **at most one** observed cell at a time — the selected one. `select(ref,
  mode)` (`:92-113`) issues one `POST /cell/{ref}/observe`, releases the
  previous selection's observation, and fetches detail + state; `deselect()`
  (`:115-123`) releases it. `mode: 'descriptor'` is the M5-COLD gate: inside a
  cold graph, selection fetches only the descriptor and opens **no**
  observation (`:92-105`) — subscribing raises attention and can un-park a
  cone (P6), so a graph the UI has just called parked must not be woken by
  looking at it.
- `solid/detail.ts` wires this to the `selection()` signal
  (`inspect/ui/src/solid/selection.ts:1-11`): `initDetail()` (`:52-78`) drives
  `controller.select`/`deselect` off `selection()` and `currentGraphCold()`.
  `stateSummaries` (`:16-25`, a `Record<Ref, StateSummaryPayload>` — already
  keyed by ref, not a single slot, specifically so a later milestone that
  observes more than one cell needs no shape change) is written **only** for
  the currently-selected ref: `onStateSummary` (`:82-87`) guards `if
  (payload.ref === selection())`.
- The canvas state-chip layer (`inspect/ui/src/components/Canvas.tsx:288-312`)
  renders one chip per node keyed off `stateSummaries[ref]`; since that store
  only ever holds the selected ref's entry, at most one chip shows today
  (`Canvas.tsx:288-293`'s own comment says so).
- Selection is released on graph navigation: `solid/route.ts`'s `enterGraph`
  (`:91-99`) and `goHome` (`:101-111`) both call `setSelection(...)`/
  `setSelection(null)`, alongside `clearWake()` (`solid/cold.ts:82-87`) —
  the pattern any session-local, per-graph UI state follows.
- `V1A-FE` (`doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1A-FE.md`),
  the ticket immediately before this one in Wave 2, changes
  `DetailController.onSummary` from `onSummary(ref: Ref)` to
  `onSummary(payload: StateSummaryPayload)`, gated by a new pure predicate
  `indicatesChange` (`inspect/ui/src/sync/summaryChange.ts`) so a quiet
  publish-even-when-quiet window (V1A-BE) does not trigger a `GET .../state`
  refetch. **By the time you start, V1A-FE will already be merged to `main`**
  (Wave 2 → checkpoint C2 → Wave 3). Read its landed diff before touching
  `detailClient.ts`/`solid/detail.ts` — you are extending its shape (the
  per-ref last-seen-payload cache, `indicatesChange` reuse, the onChange log),
  not the pre-V1a shape described above, which this ticket's citations use
  only because it is what exists in this worktree today.

Server side needs **no changes**. `Observations` (server:
`inspect/src/main/kotlin/civictech/inspect/Observations.kt:88-117`'s class
doc) already supports any number of concurrent observations — each `POST
/cell/{ref}/observe` spawns its own `ObserveCell` sink independent of any
other open one (`Observations.kt:118-149`'s `start`), and each is tracked and
released independently (`stop`, `:206-210`; idle sweep, `:237-245`). The only
per-cell limit is semantic, not a concurrency cap: `POST .../observe` answers
`409` when the target has no built-in fold to observe — "no delta outlet, or
an outlet kind with no `View`" (`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:25`;
server: `InspectorServer.kt:417-429`'s `startObserving`, refusal built from
`Observations.viewFor`'s documented cases at `Observations.kt:321-337`). The
contract's own note: "a client that ignores the 409 still behaves correctly,
since `GET .../state` reports `kind: 'unavailable'` for that cell"
(`InspectorServer.kt:399-415`'s `serveState`, which falls back to
`observations.snapshotReading(ref)` — the `SnapshotSource` seam `V0-BE`
(Wave 1, already merged by your start) wires to `ManagedHost.snapshotOf`).
Whether `GET .../state` for a refused cell answers `kind: 'snapshot'` (V0-BE
wired) or `kind: 'unavailable'` (defensively, if it were not) is irrelevant to
this ticket's own behavior: your "snapshot only" signal is driven by the
**observe response itself** being refused, not by inspecting `kind` — see
Solution direction §3.

## Problem

1. **Only one cell can be watched at a time.** Selecting cell B silently
   drops the observation on cell A (`detailClient.ts:102`, unconditional
   `observeStop(prev)`), even though the server would happily hold both open.
   A user comparing two cells' live state has to keep re-selecting.
2. **The state chip is capped at one.** `Canvas.tsx:288-312` renders from
   `stateSummaries`, which `onStateSummary`'s selection-only guard
   (`solid/detail.ts:84`) never lets hold more than one entry.
3. **No cost signal.** P6 ("observation is causal") is enforced today by
   there being only one possible subscription; once more than one is
   possible, the user needs to see how many cones they are currently
   touching (`10-design-notes.md` §Binding constraints 2) — nothing today
   shows this.
4. **A 409'd cell has no distinct treatment.** A pin on a no-fold cell must
   not silently look broken or indistinguishable from a healthy live pin.

## Solution direction

Prescriptive on behavior and the public shape; the exact class/file split
(rename `DetailController` in place, split it into two collaborating classes,
or something else in that vein) is your judgment call — state which you chose
in the report.

### 1. Generalize the observed-ref set

Today exactly one ref can be observed, and it is always `selection()`. Change
this to: an explicit **pinned set** (`Set<Ref>`, user-controlled via `pin`/
`unpin`), plus the current **selection**, which behaves as an *implicit* pin
that follows the cursor. The full observed set is `pinned ∪ {selection}`
(when selection is non-null and not cold/descriptor-only).

Rules (each independently testable against a mock `DetailTransport`, mirroring
`test/detailClient.test.ts`'s existing style):

- `pin(ref)`: adds `ref` to the pinned set. If `ref` was not already observed
  (not already pinned, not already the live-observed selection), issue
  exactly one `POST .../observe` for it (see §3 for the 409 case) and one
  initial `GET .../state`. A no-op (no transport call) if `ref` is already
  pinned.
- `unpin(ref)`: removes `ref` from the pinned set. If `ref` is **not** the
  current selection, and it had an open (non-refused) observation, issue
  exactly one `DELETE .../observe` and drop its cached state/summary. If
  `ref` **is** the current selection, leave the observation and cached state
  untouched — the implicit selection-pin keeps it alive. A no-op if `ref` was
  not pinned.
- `unpinAll()`: releases every pinned ref that is not the current selection
  (same rule as `unpin`, applied to each), in one pass, then clears the
  pinned set. Notify listeners once, not once per ref.
- Selecting a ref: mirror `DetailController.select`'s existing shape
  (no-op on re-selecting the same ref+mode, cold-graph gating via `mode:
  'descriptor'` opens no observation at all — same as today). The new rule:
  releasing the **previous** selection's observation is now conditional —
  skip the `DELETE` if the previous ref is still in the pinned set. Opening
  the **new** selection's observation is also now conditional — skip the
  `POST` if the new ref is already pinned (or already observed for any other
  reason your chosen shape tracks).
- Deselecting: mirror `deselect()`'s existing shape, with the same
  conditional release — skip the `DELETE` if the deselected ref is pinned.
- Each observed ref needs its own async-response guard (today one `epoch`
  counter; you need one per ref, e.g. a `Map<Ref, number>`) so a state
  response for a ref that has since been unpinned/deselected is discarded —
  the same property `detailClient.test.ts:116-131` already pins for the
  single-ref case, now per ref.
- `onSummary(payload)`: route to whichever ref it names, if that ref is
  currently live-observed (pinned or selected, **not** refused/snapshot-only
  — see §3); reuse V1A-FE's `indicatesChange` predicate per ref (its own
  per-ref last-seen-payload cache) rather than duplicating the logic.

Descriptor fetching (`GET /cell/{ref}`, for the detail panel) stays tied to
`selection()` alone — it is not P6-gated (a plain read, not a subscription)
and only the selected cell has a panel to show it in; do not fetch descriptors
for every pinned ref.

### 2. Wire it into Solid

Bridge the framework-free controller into reactive state the same way
`solid/detail.ts:27-38` already bridges `onDetail`/`onState` — add an
`onPinsChanged(pinned: ReadonlySet<Ref>)` (or equivalent) handler and expose:

- `pinned(): ReadonlySet<Ref>` — the explicit pin set.
- `observed(): ReadonlySet<Ref>` — `pinned() ∪ (selection() ? {selection()} :
  ∅)`, filtered to live (non-refused) observations for anything that counts
  toward the cost indicator (§4).
- `pin(ref)`, `unpin(ref)`, `unpinAll()`, `isPinned(ref)` as plain exported
  functions (mirrors `setSelection` being exported directly from
  `solid/selection.ts`).
- Generalize `stateSummaries`' write gate (`solid/detail.ts:84`, currently
  `if (payload.ref === selection())`) to "ref is in the observed set."

**Session-local, released on navigation, never in the URL hash:** call your
pin-clearing function from `solid/route.ts`'s `enterGraph` (`:91-99`) and
`goHome` (`:106-111`), alongside the existing `clearWake()`/`setSelection`
calls — the same place selection itself is released on navigation. Do
**not** add pins to `Route`/`formatHash` (`inspect/ui/src/nav/route.ts`) —
selection already isn't the only thing excluded from the hash's toggle
payload treatment that matters here, but be explicit that pins get zero hash
representation.

**Idle release (server sweeps after 5 min idle,
`Observations.IDLE_RELEASE_MS`, `Observations.kt:291-292`):** do not build a
client-side keep-alive/touch loop or a re-observe-on-silence detector — that
is more machinery than this ticket needs. The existing `staleMs` display
already surfaces a reading that has stopped advancing; document (a short code
comment, mirroring `flowStore.ts`'s `DECAY_AFTER_MISSED_WINDOWS` comment style
cited in `V1A-FE.md`) that a pin can go stale if the server silently releases
it (e.g. the cell's host suspends) and the fix is to unpin/re-pin, not an
automatic mechanism. This is the "pick the simpler behavior" option named in
the design notes.

### 3. The 409 case: "pinned, snapshot only"

`POST .../observe` returning 409 must not block the pin. Today
`defaultDetailTransport.observeStart` (`detailClient.ts:39`) discards the
response entirely (`.then(() => undefined)`) — it cannot currently distinguish
a 409 from success. Change `DetailTransport.observeStart`'s return type from
`Promise<void>` to something that carries this, e.g.:

```ts
export type ObserveOutcome = 'started' | 'refused';
// ...
observeStart(ref: Ref): Promise<ObserveOutcome>;
```

`defaultDetailTransport.observeStart` inspects `res.status`: `409` resolves
`'refused'`; any other non-ok status still throws (a real error, not a
handled refusal); `204` resolves `'started'`. The existing call site in
`DetailController.select` (`detailClient.ts:107-112`,
`.then(() => this.loadState(ref, epoch))`) already ignores the resolved
value, so this is backward compatible there — update
`test/detailClient.test.ts`'s mock transports to resolve `'started'` instead
of `undefined`.

On `'refused'`: keep `ref` in the pinned/observed set, mark it
`snapshotOnly: true` for that ref, issue **one** `GET .../state` (whatever it
answers — `kind: 'snapshot'` or `kind: 'unavailable'`, both are valid,
already-handled shapes), and never treat a later summary as relevant to it
(none will ever arrive — no server-side sink exists for a refused ref). This
flag comes from the observe response, **not** from inspecting `CellState.kind`
— `kind: 'unavailable'` can also occur for unrelated reasons, and conflating
the two would mislabel a genuinely-failed pin as "snapshot only."

### 4. UI: chips, pin controls, cost indicator

- **State chips for every observed cell.** Generalize the canvas chip layer
  (`Canvas.tsx:288-312`) to iterate `observed()` (or equivalently, render
  whenever a ref has a live `stateSummaries` entry OR is marked
  `snapshotOnly`), not just the selected one. A `snapshotOnly` ref never gets
  a `stateSummaries` entry (no summaries ever arrive for it) — render a
  visually distinct variant for it (e.g. a `data-mode="snapshot"` chip
  reading "pinned · snapshot only", no cardinality/frontier since those are
  summary-only fields) rather than reusing the live chip's markup verbatim.
- **Pin control.** Add a pin toggle both on the node card
  (`Canvas.tsx:236-282`, near `.node-card__chip`; `stopPropagation` so it does
  not also trigger card selection) and in the detail panel head
  (`DetailPanel.tsx:32-41`, near the existing close button — same `icon-btn`
  pattern). Both call the same `pin`/`unpin`/`isPinned`.
- **Cold-graph gating.** Pinning inside a cold graph must not open an
  observation, mirroring selection's existing `mode: 'descriptor'` gate
  (`currentGraphCold()`, `solid/cold.ts:19-21`) — disable or hide the pin
  control while cold. A cell already pinned before its graph goes cold is an
  edge case the design notes do not resolve; force-release it or leave it
  open is your judgment call — document whichever you choose.
- **Cost indicator.** A small "N cells observed" affordance in the header
  (`Header.tsx`), mirroring `ErrorCounters`' pattern (`Header.tsx:54-77`: a
  button, always visible, click toggles a related overlay) — here, click
  triggers `unpinAll()`. Count = the size of the **live** observed set
  (`pinned ∪ {selection}`, excluding `snapshotOnly` refs — a refused observe
  never opened a real server-side subscription, so it should not count
  toward "cones you are touching").

## Files expected to touch

- `inspect/ui/src/sync/detailClient.ts` — generalize the observed-ref
  lifecycle (pin/unpin/select/deselect over a set, per-ref epoch guard,
  `ObserveOutcome`-aware `observeStart`).
- `inspect/ui/src/solid/detail.ts` — bridge `pin`/`unpin`/`unpinAll`/
  `pinned`/`observed` into Solid signals; generalize the `stateSummaries`
  write gate.
- `inspect/ui/src/solid/route.ts` — clear pins in `enterGraph`/`goHome`.
- `inspect/ui/src/components/Canvas.tsx` + `Canvas.css` — pin control on the
  node card; chip layer over the full observed set, with a distinct
  snapshot-only variant.
- `inspect/ui/src/components/DetailPanel.tsx` + `DetailPanel.css` — pin
  toggle near the close button.
- `inspect/ui/src/components/Header.tsx` + `Header.css` — the "N observed"
  indicator + unpin-all.
- `inspect/ui/test/detailClient.test.ts` — update existing mocks for the new
  `observeStart` resolution type; extend with pin-set coverage (or add a new
  `test/*.test.ts` file if you split the class — your choice, consistent with
  the file split you pick).
- `inspect/ui/mock/serve.mjs` — optional. It currently supports one observed
  ref at a time by design (`mock/serve.mjs:159-163`'s comment); extending it
  to multiple refs (for manual verification) is not required — `npm test`
  does not depend on it — but note in the report whether you did.

Touching files outside `inspect/ui/**`: not permitted — note it in the
completion report instead of expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §Verticals
  (V1b), §Binding constraints 2, 6, 8, 9, 10.
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1A-FE.md` — the ticket
  whose landed shape (`onSummary(payload)`, `indicatesChange`, the row-flash/
  change-log additions) you are building on. Read its actual merged diff, not
  just this plan file, before editing `detailClient.ts`/`solid/detail.ts`.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:25` — the 409
  semantics for `POST .../observe`.
- `inspect/ui/src/sync/detailClient.ts` (whole file) — the class you are
  generalizing; the epoch-guard and P6 discipline it already enforces for one
  ref.
- `inspect/ui/src/solid/detail.ts`, `inspect/ui/src/solid/selection.ts`,
  `inspect/ui/src/solid/cold.ts` — the selection signal and cold-graph gate
  pins must respect identically.
- `inspect/ui/src/solid/route.ts:91-111` — `enterGraph`/`goHome`, where pins
  must be released alongside selection.
- `inspect/ui/src/components/Canvas.tsx:230-312` — the node card and the
  existing (single-slot) state-chip layer.
- `inspect/ui/src/components/DetailPanel.tsx:26-51` — the panel head and its
  close-button precedent.
- `inspect/ui/src/components/Header.tsx:54-77` — `ErrorCounters`, the
  precedent for a header cost/count affordance.
- `inspect/ui/test/detailClient.test.ts` (whole file) — the mock-transport
  test pattern, including the existing rapid-reselection/epoch and
  descriptor-only-cold coverage you must preserve for the (now per-ref)
  guard.
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt:88-149,290-359`
  — server-side proof that concurrent observations are already supported, and
  the exact "no built-in fold" refusal rule.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:399-429` —
  `serveState`/`startObserving`, the 409 response and the snapshot fallback.

Do not modify: `inspect/src/**` (no backend change is needed or permitted —
concurrent observations already work server-side), `concord/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`, any plan document
other than this ticket's own `**Status**:` line.

## Acceptance criteria

- [ ] Pinning an unobserved cell issues exactly one `POST .../observe`;
      pinning an already-pinned or already-selected cell issues none.
- [ ] Unpinning a cell that is not the current selection issues exactly one
      `DELETE .../observe`; unpinning a cell that **is** the current selection
      issues none, and its observation stays open.
- [ ] `unpinAll()` releases every pinned, non-selected cell in one pass and
      notifies once.
- [ ] Selecting a cell not already observed opens exactly one observation;
      deselecting it releases that observation **unless** it is pinned, in
      which case the observation survives deselection.
- [ ] A stale response for a ref that has since been unpinned/deselected is
      discarded (per-ref epoch guard), mirroring
      `test/detailClient.test.ts:116-131`'s existing single-ref case.
- [ ] Pinning inside a cold graph opens no observation (mirrors the existing
      `mode: 'descriptor'` gate for selection).
- [ ] A 409 from `POST .../observe` keeps the ref pinned, fetches state
      exactly once, and is flagged "snapshot only" from the observe-refusal
      signal itself — not derived from `CellState.kind`. No further transport
      call is ever made for that ref in response to a `state.summary` (none
      arrive for it).
- [ ] Pins are cleared on `enterGraph`/`goHome` and never appear in
      `formatHash`'s output.
- [ ] The canvas shows a state chip for every entry in the observed set, not
      only the selected cell; a snapshot-only pin renders a visually distinct
      chip.
- [ ] The header shows a live count of the observed set (excluding
      snapshot-only refs) and an affordance that unpins all.
- [ ] `npm test`, `npm run typecheck`, `npm run build` green.
- [ ] No file outside `inspect/ui/**` in the diff.

## Verify

```bash
cd inspect/ui
npm ci          # first run in a fresh worktree
npm test
npm run typecheck
npm run build
```

Manual pass optional (`mock/serve.mjs` supports one observed ref by design —
see §"Files expected to touch"; if you extend it, record what you saw):

```bash
cd inspect/ui && npm run mock   # terminal 1
cd inspect/ui && npm run dev    # terminal 2
```

## Report on completion

- Checks run and their results.
- Files actually touched, and any not in the claim above.
- **Flag to the orchestrator:**
  1. Whether you generalized `DetailController` in place, renamed it, or
     split observe/state lifecycle into a separate class from descriptor
     fetching — and why.
  2. How the `ObserveOutcome`-typed `observeStart` change was threaded
     through `test/detailClient.test.ts`'s existing mocks (any call sites
     beyond the ones cited here).
  3. Which edge you chose for "a cell already pinned when its graph goes
     cold" (force-release vs. leave open).
  4. Whether you extended `mock/serve.mjs` for multi-pin manual verification.
  5. Whether V1A-FE's actually-landed shape differed from what this ticket
     describes in a way that changed your integration.
- Anything specified here you could not do, and why.
