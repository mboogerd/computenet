# V1C-FE — the detail panel walks a big cell page by page, says where its bytes came from, and the cold screen stops promising nothing

**Status**: Implemented — merged (`09e869e`)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 10 · **Branches:** `ticket/v1c-fe`

Runs in parallel with `V1C-BE` (owns `inspect/src/**`) and `V4-PILOT` (owns
`demo/**`). **Your file claim is `inspect/ui/**` and nothing else.** Branches
from `main` after wave 9 merged.

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS + Vite app
(npm, deliberately **not** wired into Gradle — `10-design-notes.md` §"Binding
constraints" 10), zero runtime dependencies beyond `solid-js`, rendering a
read-only view of a live ComputeNet host's dataflow graph from the `:inspect`
backend's HTTP + SSE API. Read `10-design-notes.md` in full first: §"Binding
constraints" governs this ticket absolutely, and constraints 2 (P6 — observation
is causal), 6 (viz never blocks), 8 (the API contract is orchestrator-owned and
additive) and 10 are the four that touch every line you write.

### How state reaches the panel today

- `DetailController` (`inspect/ui/src/sync/detailClient.ts:95-382`) owns the
  observation lifecycle. `select(ref, mode)` (`:169-198`) opens at most one
  observation for the selected cell — generalized by `V1B-FE` to a **pin set**
  plus the selection (`pin` `:236-246`, `unpin` `:255-260`, `isObservedLive`
  `:297-299`) — and `openLiveObservation` (`:327-340`) issues
  `POST /cell/{ref}/observe` and then, in its `.then`, exactly one
  `GET /cell/{ref}/state` (`loadState`, `:374-381`). **Fetching state is
  currently welded to opening an observation.** That coupling is the thing
  slice 1 must not inherit; see "P6 and its spirit" below.
- `mode: 'descriptor'` (`:169-198`, doc at `:148-168`) is the M5-COLD gate:
  inside a cold graph, selection fetches the descriptor and **nothing else** —
  no observe, no `GET state`.
- `solid/detail.ts` bridges the controller into Solid: `initDetail()`
  (`:135-165`) drives `select`/`deselect` off `selection()` and
  `currentGraphCold()` (cold branch at `:149-154`), and `onState` (`:98-111`)
  drops any response whose ref is not the current selection.
- `DetailPanel.tsx`'s `StateSection` (`:216-285`) renders whatever landed in
  `cellState()`: a meta row of frontier / `staleMs` / `kind` (`:257-263`), then
  either `ValueView` (`:268`) or the one-line "State unavailable for this cell."
  (`:264-269`). The cold branch (`:245-248`) replaces the whole thing with
  `COLD_NOTICE`; the remote branch (`:249`) with `REMOTE_NOTICE`.
- `ValueView.tsx` renders the contract's `Value`: `$table` as a table
  (`:44-60`, `TableView` `:123-146`), a standalone `$truncated` as "showing N of
  M" (`:74-81`), `$opaque` as a code block (`:62-70`).
- `sync/valueDiff.ts`'s `diffRows` (`:21-30`) computes the row-flash sets by
  diffing the newly fetched value against the previously rendered one;
  `StateSection`'s `flash` memo (`:226-236`) holds "previous value" across
  renders and resets it on selection change.
- `nav/cold.ts` holds the cold copy: `COLD_NOTICE` (`:7-13`) and
  `formatColdSkipHint` (`:38-49`). Both are rendered in two places each —
  `ColdScreen.tsx:34`, `DetailPanel.tsx:247`, `Navigator.tsx:92`.
- Tests: 36 pure-module vitest suites in `test/*.test.ts` (node environment)
  plus six jsdom DOM suites under `test/dom/**` built by `FE-TESTS` (wave 6) on
  the shared harness `test/dom/harness.tsx`. `fixtures/` holds 25 JSON fixtures,
  every one strict-decoded by the backend's `FixtureContractTest`.

### What `V1C-BE` is building in parallel, this wave

`V1C-BE` (`tickets/V1C-BE.md`) turns `GET /cell/{ref}/state` into a **paged**
read backed by the kernel's `BoundedStateful` primitive (`V1C-KERNEL`, wave 8;
`V1C-CELLS`/`V1C-OPS`, wave 9), makes suspended and drained cells readable
instead of `unavailable`, and says *which* nothing an `unavailable` is.

**`V1C-BE.md`'s Part 2 block is the source of truth.** If anything below
disagrees with it, that ticket wins and you flag the divergence loudly in your
report. It is reproduced here — the parts you consume, verbatim — so you never
have to open it mid-task:

```jsonc
// CellState (M1) — V1C-BE additions
{
  "ref": "uuid:0",
  "frontier": { "source": "a3f2…", "counter": 412 } | null,
                                   // UNCHANGED, and deliberately still null for "page"/"snapshot": this is
                                   // a WAVE position, and only an observation's fold has one. A bounded
                                   // read's currency is a TagFrontier — a different clock, never a wave
                                   // position — so stamping a paged read with a wave would be exactly the
                                   // lie this field exists to prevent. What a paged read offers instead is
                                   // "page.walkStable".
  "kind": "view" | "snapshot" | "page" | "unavailable",
                                   // "view"        — M1, unchanged: an open observation's materialized fold.
                                   // "snapshot"    — M1, unchanged in MEANING: one whole copy of the cell's
                                   //                 state. Now also the answer for a cell that does not
                                   //                 implement the kernel's BoundedStateful, which is why it
                                   //                 is not a legacy value.
                                   // "page"        — V1C-BE: one bounded page of a walk. Carries "page".
                                   // "unavailable" — M1, unchanged: nothing honest to report. Now carries
                                   //                 "unreadable", which says WHICH nothing.
  "value": /* Value — the same encoder, the same $table/$opaque/$truncated shapes */,
  "staleMs": 120,                  // UNCHANGED semantics: milliseconds since the reported value last
                                   // effectively changed, which only a "view" can know. 0 for
                                   // "page"/"snapshot" — a read is as fresh as the instant it was taken,
                                   // which is a different claim, not the same one with a better number.

  "provenance": "live" | "liveSuspended" | "checkpoint" | null,
                                   // WHERE THE BYTES CAME FROM. Non-null exactly when "kind" is "page" or
                                   // "snapshot"; null for "view" (a fold materialized in the inspector's own
                                   // heap is neither a live cell read nor a checkpoint, and claiming "live"
                                   // would blur the one distinction this field exists to make) and for
                                   // "unavailable".
                                   //   "live"          — read from the running cell on its own execution
                                   //                     context.
                                   //   "liveSuspended" — read from a SUSPENDED cell's own fold. Quiescent by
                                   //                     construction, so this is the most stable read in the
                                   //                     graph, not a degraded one. Reading it resumed
                                   //                     nothing, woke nothing and raised no attention.
                                   //   "checkpoint"    — read from the blob a DRAINED host already retains
                                   //                     from its drain. State as of the drain, not as of
                                   //                     now, and no cell thread was scheduled to produce it.
                                   //                     This is the one provenance that is stale by
                                   //                     construction; clients must label it.

  "page": {                        // present iff kind == "page"; null otherwise
    "cursor": "p-7f3a…" | null,    // OPAQUE. Echo it back verbatim as ?cursor= to fetch the next page; null
                                   // means the walk is complete. Never parse it, never construct one, never
                                   // reuse one: each response mints a fresh cursor and retires the one that
                                   // produced it. A stale, unknown, expired or wrong-cell cursor answers 410
                                   // — a client that gets one drops it and restarts the walk from page 1.
    "limit": 200,                  // the limit actually applied. The server clamps ?limit= to 1..1000, so
                                   // this is how a client learns its request was reduced.
    "entries": 200,                // entries in THIS page, as the cell counted them before encoding. Every
                                   // one of them is rendered in "value": the server never serves a page whose
                                   // entries the encoder's byte budget cut — it re-reads a smaller page
                                   // instead. So a "$truncated" marker inside "value" means one VALUE was
                                   // abbreviated, never that entries went missing. "page.cursor" is the one
                                   // and only signal that more state exists.
    "exclusivesElided": 0,         // entries on this page whose value is an Owned/Leased payload. The kernel
                                   // pages a presence descriptor (key, declared type, disposition) for those
                                   // and never a copy of the payload, so > 0 means this page is deliberately
                                   // incomplete in a way no further page will ever fill in. Render it: it is
                                   // a fact about the data, not a diagnostic about the read.
    "walkStable": true | false | null
                                   //   true  — every page of this walk so far carried the same tag frontier,
                                   //           so the union of the pages fetched so far is exactly a snapshot
                                   //           of that fold. Always true on page 1.
                                   //   false — the fold changed mid-walk. The union is a SMEARED read: it
                                   //           contains every entry present for the whole walk, may contain
                                   //           entries added mid-walk, and may miss entries removed mid-walk
                                   //           after being passed over. It is never torn at entry granularity
                                   //           and never returns an entry twice.
                                   //   null  — the cell reports no tag frontier, so neither claim can be
                                   //           checked. Render it as neither; it is not a "false".
  } | null,

  "unreadable": "migrating" | "remote" | "notStateful" | "unanswered" | "unknown" | null
                                   // present iff kind == "unavailable" — WHY there is nothing to report.
                                   //   "migrating"   — held for a repartition flip, or already migrated. The
                                   //                   authoritative instance is another host's.
                                   //   "remote"      — no local host. A wave-neutral read does not cross a
                                   //                   bridge. Unchanged from M5, and deliberate.
                                   //   "notStateful" — the cell holds no readable state at all.
                                   //   "unanswered"  — the read did not land inside the server's bounded
                                   //                   wait. Nothing was read; a retry may succeed.
                                   //   "unknown"     — a kernel Unavailable reason this server build does not
                                   //                   map. Forward-compatibility, never a guess.
}
```

Query parameters, from the same ticket: `?cursor=<opaque>` (absent = start of a
fresh walk) and `?limit=<int>` (1..1000, clamped, default 200). **A malformed
`limit` is 400**; an unknown, expired, already-consumed or wrong-cell `cursor`
is **410**. Both are ignored for a cell with an open observation, which keeps
answering `kind: "view"` from its already-materialized fold.

What remains `unavailable` after `V1C-BE` (its Part 4 table — mirror this in the
cold copy, and **do not promise more than it does**):

| Case | Answer |
|---|---|
| Held for a migration flip, or already migrated away | `unavailable` / `"migrating"` |
| Not locally hosted — unpublished or peer-mirrored | `unavailable` / `"remote"` |
| Not `Stateful` | `unavailable` / `"notStateful"` |
| Wedged or slow past the server's bounded wait | `unavailable` / `"unanswered"` |
| Attention-parked cone | reads as `HOT` and answers normally |

## Problem

1. **A big cell is a cliff.** One `GET .../state` on selection, one render of
   whatever came back, and the encoder's `$truncated` marker as the only hint
   that anything was cut. There is no way to see entry 201, and the marker does
   not distinguish "this one value was abbreviated" from "the rest of the cell
   is not here".
2. **The panel cannot say where a value came from.** Every value is rendered in
   the same register whether it was read from a running cell, from a quiescent
   suspended fold, or from a checkpoint blob written when the host drained an
   hour ago. `staleMs` cannot carry that distinction — for a paged read it is
   `0`, which is true about the read and actively misleading about the data.
3. **Elided exclusives have nowhere to go.** A page that deliberately omits
   `Owned`/`Leased` payloads is *complete for what it can honestly copy*. With
   no surface for that fact the UI either loses it or, worse, folds it into the
   truncation story, where it reads as "fetch more and you'll get it" — and no
   further page ever will.
4. **The cold screen makes a promise that is about to be false.**
   `nav/cold.ts:7-13` hard-codes
   `COLD_NOTICE = 'cold — parked; state/flow unavailable without waking'`, and
   its own comment names cold reads from a checkpoint as "a tracked kernel gap".
   `V1C-KERNEL` Decision 7 closed that gap and `V1C-BE` consumes it. Half of
   that sentence becomes a lie of the opposite kind: telling the user nothing is
   available when the backend will now serve them a labelled checkpoint.
5. **`unavailable` says nothing.** `DetailPanel.tsx:264-269` renders one
   sentence for four different facts.

## Solution direction

Prescriptive on behaviour and on the wire shape (which is `V1C-BE`'s, above).
The module/file split — one new `sync/` module or two, a component or a
sub-component — is your judgment call; state which you chose and why in the
report.

### 0. Types (`src/api/types.ts:76-85`)

Extend `StateKind` with `'page'` and add `StateProvenance`, `Unreadable` and the
`page` object, keeping the file's existing comment style. **All new fields are
optional-tolerant on read** — an older server that omits them must not break the
client (constraint 8: unknown fields ignored, never required):

```ts
export type StateKind = 'view' | 'snapshot' | 'page' | 'unavailable';
export type StateProvenance = 'live' | 'liveSuspended' | 'checkpoint';
export type Unreadable = 'migrating' | 'remote' | 'notStateful' | 'unanswered' | 'unknown';

export interface StatePage {
  cursor: string | null;
  limit: number;
  entries: number;
  exclusivesElided: number;
  walkStable: boolean | null;
}

export interface CellState {
  ref: Ref;
  frontier: Frontier | null;
  kind: StateKind;
  value: Value;
  staleMs: number;
  provenance?: StateProvenance | null;   // non-null exactly for 'page' | 'snapshot'
  page?: StatePage | null;               // non-null exactly for 'page'
  unreadable?: Unreadable | null;        // non-null exactly for 'unavailable'
}
```

**Forward tolerance, not exhaustiveness.** Render `provenance` and `unreadable`
through a lookup with a fallback, never an exhaustive `switch` that throws or a
map access that yields `undefined` into the DOM — the same rule `V2-FE` applied
to the attention band (`DetailPanel.tsx:177-191`): a value this client has never
seen must still display something truthful, not crash and not render blank.

**Never default `provenance` to `'live'`.** `provenance ?? 'live'` anywhere in
the diff is a defect: null means "this is a `view`, a fold materialized in the
inspector's own heap", and calling that a live cell read erases the exact
distinction the field was added for.

### 1. A paged state view for big cells

**Where a walk can arise.** The selected cell normally has an open observation,
so it answers `kind: 'view'` and pages never apply. `kind: 'page'` reaches the
panel when the cell has no fold to observe (the 409 / `snapshotOnly` path,
`detailClient.ts:327-340`), when the selection is in a cold graph (slice 3), or
whenever the server otherwise answers without a view. Gate every walk affordance
on `kind === 'page' && page.cursor !== null` — never on cell size, never on
`$truncated`.

**Transport.** Add one method to `DetailTransport` (`detailClient.ts:9-21`) —
e.g. `fetchStatePage(ref, opts: { cursor?: string; limit?: number })` — building
`?cursor=`/`?limit=` with `URLSearchParams` and echoing the cursor **verbatim**
(opaque: never parsed, never reconstructed, never cached across refs). It
returns a discriminated outcome, in the register `V1B-FE` established for
`ObserveOutcome` (`detailClient.ts:23-29`):

```ts
export type PageOutcome =
  | { status: 'ok'; state: CellState }
  | { status: 'staleCursor' };          // HTTP 410 — drop the cursor, restart the walk
```

Any other non-ok status still **throws** (a real error). The FE only ever sends
a constant limit, so a 400 means a client bug and must surface as an error, not
be swallowed.

**The walk itself** is a small framework-free class or reducer (`src/sync/` —
`statePages.ts` or similar), unit-testable against a mock transport exactly like
`sync/detailClient.ts` is by `test/detailClient.test.ts`. It holds: the ref, the
accumulated value, the pages fetched so far, the live cursor, the running
`exclusivesElided` total, the latest `walkStable`, and a per-walk epoch guard
(the same device `detailClient.ts:305-320` uses per ref).

Required properties:

- **User-driven only.** One page per explicit user action ("Load next page" or
  equivalent). **No automatic background fetch-all, no prefetch, no
  fetch-on-scroll-to-bottom.** An inspector that walks a 10⁵-entry cell on its
  own has moved "viz blocks the graph" to the client, which is the exact failure
  mode `20-wave-neutral-read-design.md` §4.2 rejects ("it moves the cost from
  the graph to the instrument by making the instrument a participant").
- **Abandonable mid-flight, with nothing left behind.** Selecting another cell,
  deselecting, closing the panel or navigating away resets the walk: the
  accumulator is cleared, the cursor is dropped (never re-sent), any in-flight
  response is discarded by the epoch guard, and no timer/interval survives.
  Assert the discard, not just the reset.
- **Says what it has, invents nothing.** The counter reads from real fields
  only: pages fetched, `Σ page.entries`, and whether `page.cursor` is non-null.
  Wording in the register of "1 200 entries loaded — more available" /
  "1 200 entries — complete". **Never print a total the server did not give
  you**, and never reuse `$truncated`'s "showing N of M" phrasing for it: those
  numbers come from the encoder abbreviating one value, not from the walk.
- **Accumulate honestly.** Successive pages append: `$table` pages with matching
  `columns` concatenate their `rows`; plain-array pages concatenate. If two
  pages' shapes do not match (different columns, table then tree), **do not
  fabricate a merge** — render the pages in sequence and say so. Put this in a
  pure exported helper with its own unit test; it is the one place a
  hard-to-see correctness bug can hide.
- **A page append is not a change.** `StateSection`'s `flash` memo
  (`DetailPanel.tsx:226-236`) diffs the new value against the previous one, so
  appending a page would flash the whole page as "added" — a lie: those entries
  were always there, you merely had not fetched them. **Suppress row-flash for
  `kind === 'page'`** (pass an empty `RowFlash`, do not feed the appended value
  into `diffRows`), and say so in a code comment citing this clause.
- **410 restarts, silently and once.** On `{ status: 'staleCursor' }`: drop the
  cursor, clear the accumulator, refetch page 1 with no `?cursor=`, and surface
  **no error to the user** — at most a neutral inline note that the walk
  restarted because the cell changed. Bound it: at most one automatic restart
  per user-initiated page request; a second consecutive 410 stops and shows the
  neutral restarted state rather than looping.
- **`walkStable`.** `false` renders a visible note on the accumulated value —
  the fold changed mid-walk, so this is a smeared read, not a snapshot (use the
  contract block's own vocabulary). `true` renders nothing or a quiet marker.
  `null` renders **neither**: it is not a `false`, and rendering it as one would
  invent a defect.
- **Read `page.limit` back**, do not assume the limit you sent was applied.

**P6 and its spirit.** P6 is a backend property, but this is where a frontend
can violate its spirit: **a paged read is a read, not an observation.** The walk
must never call `observeStart`/`observeStop`, must never route through
`openLiveObservation` (`detailClient.ts:327-340`, where a state fetch is today
chained onto a `POST observe`), must never be triggered by an incoming
`state.summary` (`onSummary`, `:288-295`), and must not extend, renew or touch
an observation's lifetime. Keep the two paths structurally apart — a separate
transport method and a separate call site — so the separation is visible in the
diff, not merely intended. `test/detailClient.test.ts`'s call-accounting style
(`expect(transport.observeStart).not.toHaveBeenCalled()`) is how you prove it.

### 2. Provenance and elision, rendered honestly

Three facts the backend now tells the truth about. None of them may be flattened
into the existing meta row alone (`DetailPanel.tsx:257-263`), and none of them
may live only in a `title=` tooltip.

- **`checkpoint` is stale by construction** — state as of the drain, not as of
  now. It must be **visibly labelled at the value**: a persistent line
  immediately above `ValueView`, not a chip in the meta row and not a tooltip.
  Suggested register (final wording is yours, and the report must give it):
  *"checkpoint — this is the cell's state as of the host's drain, not as of
  now."*
- **`liveSuspended` is the most stable read in the graph, not a degraded one.**
  A quiescent cell read without waking it: nothing resumed, nothing woken, no
  attention raised. Render it neutrally or positively. **Do not** reuse the
  amber/red registers of `DetailPanel.css`'s `.wave-health-row` /
  `.dead-letter-card`, and do not word it as a warning or a caveat.
- **`live`** is the unremarkable case: a quiet marker or nothing.
- **`provenance == null`** (a `view`, or an `unavailable`) renders **nothing**.
  See §0.
- **`staleMs` stops being rendered as a freshness claim for `page`/`snapshot`.**
  The contract pins it to `0` there, so today's `{s().staleMs}ms stale` line
  (`DetailPanel.tsx:261`) would print "0ms stale" over a checkpoint written an
  hour ago. Render the staleness chip only for `kind === 'view'`, or relabel it
  so it cannot be read as "this data is 0 ms old". Getting this wrong makes
  slice 2's whole point unreadable.

**The elided-exclusives count is the hardest wording in this ticket.** The field
is `page.exclusivesElided` (read it in the block above; do not guess the name).
`> 0` means: entries exist on this page whose values are `Owned`/`Leased`, and
the kernel paged a presence descriptor for them — key, declared type,
disposition — never a copy of the payload, because copying an exclusive payload
is the prohibition itself (`20-wave-neutral-read-design.md` §3.3: "exclusive
values are described, never paged"). Requirements:

- It is an **ownership fact**, not an error and not a truncation. It gets its
  own register — not the error/amber styling, not `ValueView`'s
  `.value-view__truncated` styling.
- It must **never be conflated with `$truncated`** and must never be phrased as
  "showing N of M" or "load more to see them". No further page will ever fill
  them in; that is the point.
- Candidate wording, in the register of the rest of the panel (final wording is
  yours, and the report must give it verbatim): *"3 entries hold exclusive
  values (Owned/Leased) — described, never copied. No further page will contain
  them."*
- Accumulate it across the walk (a running total) and render it against the
  accumulated value, so a user who walked five pages sees one honest number.

**`unavailable` gains its reason.** Map each `unreadable` value to one plain
sentence stating the fact and, where there is one, the remedy — `"unanswered"`
is worth retrying, `"migrating"`/`"remote"`/`"notStateful"` are not.
**Keep the existing sentence "State unavailable for this cell." verbatim for
`unreadable == null`** (older server, or a field-less response) — that keeps
`test/dom/detail-panel.test.tsx:61-69` green unmodified and preserves the
older-server path. `"remote"` should reconcile with the existing
`REMOTE_NOTICE` (`util/placement.ts`, rendered at `DetailPanel.tsx:249`) rather
than contradict it.

### 3. The cold screen stops promising nothing

You own `nav/cold.ts`'s copy. `COLD_NOTICE` (`:7-13`) is rendered on the cold
screen banner (`ColdScreen.tsx:34`) and as the State section's cold fallback
(`DetailPanel.tsx:247`); splitting it into two constants (a banner sentence and
a state-line sentence) is permitted and probably right — keep both in
`nav/cold.ts`, the file's own "one place, so a card, a thumbnail and the cold
screen all wear the same tag" discipline.

**Cold selection now reads state.** Rewording the notice without fetching would
make the screen claim a capability it does not exercise. So: a selection inside
a cold graph now issues **exactly one `GET /cell/{ref}/state`** — a plain read —
and renders it with its provenance, exactly as slice 2 requires. Absolutely
unchanged: **no `POST observe`, ever, while cold.** Subscribing raises attention
and can un-park a cone (P6); a read does not, and `V1C-BE` guarantees a
suspended cell's read resumes nothing and a drained host's read schedules no
cell thread at all. Update `DetailController.select`'s doc comment (`:148-168`)
and `initDetail`'s cold branch (`solid/detail.ts:149-154`, which sets
`setStateLoading(!cold)`) accordingly.

Consequences you must handle, not discover:

- `test/detailClient.test.ts:260-268` ("fetches the descriptor and issues no
  observe POST and **no state fetch**") is now half wrong. Rewrite it as its
  inverse for the state half — exactly one `fetchState`, still zero
  `observeStart`, still zero `observeStop` — and **keep the no-subscribe
  assertions verbatim**. `:270-280` and `:292-299` stay green unchanged.
  `:282-290` ("a `state.summary` does not pull its state in through the back
  door") must **also stay green unchanged**: a descriptor-only selection is not
  in `isObservedLive` (`:297-299`), so no summary ever triggers a refetch.
- **Flow is still unavailable and must still say so.** `FlowSection`
  (`DetailPanel.tsx:326-372`) has no cold gate at all today — while cold it
  renders a port table of em-dashes. Once the notice stops saying "state/flow
  unavailable" as one clause, add an explicit cold line to the Flow section:
  no messages flow in a parked cone, and there is nothing honest to show.
- **A suspended-but-not-drained cell has no checkpoint** — it is read from its
  own live fold (`liveSuspended`). The copy must not say "checkpoint" for the
  cold case generally; the provenance field is what distinguishes them, per
  response.
- **Remote cells are still `unavailable`** (`unreadable: "remote"`). Render the
  absence; do not work around it.
- `test/cold.test.ts:70-74` asserts `COLD_NOTICE` contains
  `'unavailable without waking'` and does **not** match
  `/checkpoint|last known|cached/i`. Both assertions must be rewritten — but
  rewrite them to preserve the *intent*, which is still binding: **no fake
  preview, no last-known value dressed up as current.** A truthful mention of
  "checkpoint" is now permitted precisely because the value is labelled with its
  provenance; a notice implying the cold value is *current* is not. Write the
  replacement assertions so they would still fail on the dishonest sentence.
- **`formatColdSkipHint` (`nav/cold.ts:45-49`) offers a remedy that stops
  working.** `V1C-BE` narrows `SearchCost.coldSkipped` to genuinely unreadable
  cells (held for a migration flip), and waking a held cell does nothing
  (`Cold.kt`'s "never the inspector"). "wake their graph to include" becomes
  false. Reword it. **Note the honesty tension and resolve it in the wording,
  not by ignoring it:** an older server still means "parked, and waking would
  help", and the client cannot tell which server it is talking to — so prefer
  wording that is true under both (state the fact; drop or soften the remedy
  rather than naming one that may be a dead end). Update
  `test/cold.test.ts:48-68` and `test/graphs-fixture.test.ts:168-172`, which
  both assert the exact string. **Do not edit
  `fixtures/search-data-cold.json`**: its notice-hit `detail` text is
  *server-authored* copy that `V1C-BE` is rewriting this same wave; preempting
  it would collide.
- The state line and the banner must, between them, say exactly: state is
  readable (from the parked fold or from the drain checkpoint) and labelled with
  where it came from; flow is not; some cells still answer nothing and say why.
  **Mirror `V1C-BE.md`'s Part 4 table, reproduced in §Context above. Do not
  invent a broader promise than the backend makes.**

### 4. Fixtures, and the cross-ticket coupling

`inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:64-120`
asserts that its hand-written `decoders` map covers **exactly** the contents of
`inspect/ui/fixtures/`. `V1C-BE` adds decoder entries for **exactly these two
filenames** — use them verbatim; a mismatch fails that test in both branches:

- `inspect/ui/fixtures/cell-state-page.json` — a live paged read:
  `kind: "page"`, `provenance: "live"`, `frontier: null`, `staleMs: 0`, a
  non-null `page.cursor`, `page.entries == page.limit`, `walkStable: true`, and
  a `$table` `value` whose row count equals `page.entries`.
- `inspect/ui/fixtures/cell-state-page-checkpoint.json` — a drained host's
  checkpoint read: `provenance: "checkpoint"`, and (your choice, stated in the
  report) a completed walk (`page.cursor: null`) or a `kind: "snapshot"` with
  `page: null`, matching whichever arm `V1C-KERNEL` shipped for Decision 7 — if
  you cannot determine that from `V1C-KERNEL.md`'s completion report, pick the
  `page` arm and say so.

Add **exactly these two files and no others.** Every other shape this ticket
needs — `provenance: "liveSuspended"`, `walkStable: false`, a non-zero
`exclusivesElided`, each `unreadable` value, the 410 response — uses **inline
test samples**, per `00-orchestration.md` §"Standing rules": *"A fixture for a
new feed lands with its BE ticket, or the FE ticket uses inline test samples
instead."* A third fixture is a cross-ticket change neither branch may make
unilaterally, and `test/dom/value-view.test.tsx:30-40` is the standing precedent
for constructing a `Value`/DTO literal inline rather than editing fixture shape.

`fixtures/cell-state-unavailable.json` may be extended in place with an
`unreadable` field (its decoder entry already exists) — but if you do, **keep
its `ref` unchanged**: it is `harness.tsx:107`'s `UNAVAILABLE_STATE_REF` and
`test/dom/detail-panel.test.tsx:62` selects on it. Preferring an inline sample
here is also fine, and slightly safer.

**Expected cross-branch friction, which you must not "fix":** until both
branches merge, `:inspect:test` run from a worktree holding only yours fails
`FixtureContractTest`'s directory-equality assertion on the two new files. That
is the wave's known coupling — `V3-BE`/`V3-FE` and `V2-BE`/`V2-FE` both hit it.
Your gate is `npm test` + `npm run typecheck` + `npm run build`. Do **not**
touch `inspect/src/**` to make it pass, and do not omit fixture content to avoid
it. Report it.

### 5. Offline dev backend (`mock/serve.mjs`) — recommended

`stateFor(ref)` (`mock/serve.mjs:173-188`) answers one fixed `kind: 'view'`
body, and the route (`:561-563`) ignores query parameters. Extending it is the
only way to exercise this ticket end to end before `V1C-BE` merges, and it stays
inside your file claim. Minimum useful extension: one large cell that answers
`kind: 'page'` with real cursors across ≥3 pages (including a final page with
`cursor: null`), a `410` arm for an unknown/expired cursor, one cell with
`provenance: 'checkpoint'`, one with `'liveSuspended'`, one page with
`exclusivesElided > 0`, and one `unavailable` with an `unreadable` reason. Keep
the file dependency-free (Node `http` only) and keep its existing P6-mirroring
comment (`:163-166`) truthful.

## Files expected to touch

- `inspect/ui/src/api/types.ts` — `StateKind`, `StateProvenance`, `Unreadable`,
  `StatePage`, the extended `CellState` (`:76-85`).
- `inspect/ui/src/sync/detailClient.ts` — the `fetchStatePage` transport method
  and `PageOutcome` (`:9-29`, `:50-60`); the descriptor-mode state read
  (`:169-198`, doc `:148-168`); **no coupling of paging to observe**.
- `inspect/ui/src/sync/statePages.ts` (new, or your chosen name) — the walk
  state machine and the pure page-append helper.
- `inspect/ui/src/solid/detail.ts` — bridge the walk into signals; the cold
  branch of `initDetail` (`:135-165`).
- `inspect/ui/src/components/DetailPanel.tsx` + `DetailPanel.css` — the
  provenance line, the checkpoint banner, the elided-exclusives line, the
  `walkStable` note, the page counter and the "load next page" control, the
  reason-specific `unavailable` sentences, the `staleMs` gating, the Flow
  section's cold line.
- `inspect/ui/src/components/ValueView.tsx` + `ValueView.css` — only if the
  accumulated/paged value needs a rendering affordance the current component
  cannot express. Prefer leaving it alone; `normalize` keeps page entries in the
  same `$table` shape it already renders.
- `inspect/ui/src/nav/cold.ts` — the rewritten notice(s) and
  `formatColdSkipHint`.
- `inspect/ui/src/components/ColdScreen.tsx` + `ColdScreen.css` — the banner
  copy and its doc comment (`:19-24`, whose "the kernel does not have it" claim
  is now out of date).
- `inspect/ui/fixtures/cell-state-page.json`,
  `inspect/ui/fixtures/cell-state-page-checkpoint.json` — exactly these two.
- `inspect/ui/test/**` — extend `detailClient.test.ts`, `cold.test.ts`,
  `graphs-fixture.test.ts`; add a pure suite for the walk/append helpers; extend
  `test/dom/detail-panel.test.tsx` and `test/dom/harness.tsx` (see below).
- `inspect/ui/mock/serve.mjs` — recommended (§5).

**The DOM harness needs two extensions**, and they are load-bearing for three
acceptance criteria: `jsonResponse` (`test/dom/harness.tsx:124-136`) hardcodes
`ok: true, status: 200`, so a **410** cannot be expressed — add a status-aware
variant; and the `/cell/{ref}/state` route (`:149-153`) ignores query
parameters, so a **two-page walk** cannot be expressed — make the per-ref
override (`stateByRef`, `:118-122`, `setStateFixture` `:120-122`) cursor-aware
(a responder function, or a per-ref sequence). Clear whatever you add in
`resetAppState` (`:247-258`) so one test's walk never leaks into the next.
Follow the harness's own reset discipline (`:56-71`): `startApp()` once per
file, `resetAppState()` per test.

Touching files outside `inspect/ui/**`: **not permitted** — `V1C-BE` owns
`inspect/src/**` and `V4-PILOT` owns `demo/**`, both concurrently. Note any need
to in the report rather than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-BE.md` — the **whole**
  file, especially Part 2 (the block above is a copy; that file is the source of
  truth) and Part 4 (what remains unreadable). If it has already merged by the
  time you start, read its **completion report** too: it records any deviation
  from Part 2, and the deviation wins.
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" — all ten; 2, 6, 8 and 10 govern you directly — and §"Standing
  file split".
- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` §3.3
  (ownership: described, never paged — the source of the elided-exclusives
  wording), §3.4 (cursor, limit, and why a walk is not a snapshot — the source
  of `walkStable`'s three values), §3.6 (suspended/drained/migrating), §4.2 (the
  inspector-as-participant failure mode a background prefetch would be).
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — the shipped
  contract you are extending: `CellState` `:92-99`, `Value` and its truncation
  marker `:101-112`, the endpoint table `:19-31`, `SearchResult`/`SearchCost`
  `:182-196`. **Orchestrator-owned — never edit it**; propose wording in your
  report.
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/FE-TESTS.md` — wave 6, which
  built `test/dom/**` and the harness. Use that harness; do not invent a second
  one.
- `inspect/ui/src/sync/detailClient.ts` — the whole file. The observe/read
  coupling at `:327-340` is what slice 1 must not inherit; the per-ref epoch
  guard `:305-320` is the pattern the walk's guard copies.
- `inspect/ui/src/solid/detail.ts` — the whole file; `onState`'s
  selection-only guard `:98-111`, `initDetail` `:135-165`.
- `inspect/ui/src/components/DetailPanel.tsx:205-285` (`StateSection`, the meta
  row, the cold/remote gates, the flash memo) and `:326-372` (`FlowSection`).
- `inspect/ui/src/components/ValueView.tsx` — the whole file; `$truncated`'s two
  render sites (`:44-60`, `:74-81`) are what the page counter must not imitate.
- `inspect/ui/src/sync/valueDiff.ts:21-30` — `diffRows`, and why a page append
  must not go through it.
- `inspect/ui/src/nav/cold.ts` — the whole file, including its comments' own
  reasoning about not inventing numbers or previews.
- `inspect/ui/src/components/ColdScreen.tsx` and
  `inspect/ui/src/components/Canvas.tsx:678-740` (the state-chip layer, for
  context on what a "browse-everything state chip" would eventually need — it is
  **not** in this ticket's scope, see Exclusions).
- `inspect/ui/test/dom/harness.tsx` — the whole file, especially its module doc
  (`:1-71`) on what is stubbed and how a future test extends it. This ticket is
  that future test.
- `inspect/ui/test/dom/detail-panel.test.tsx`,
  `inspect/ui/test/dom/value-view.test.tsx`,
  `inspect/ui/test/detailClient.test.ts`, `inspect/ui/test/cold.test.ts` — the
  four suites you extend or rewrite.
- `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:55-120` —
  **read-only for you**: the decoder map and the directory-equality assertion
  that makes the two fixture filenames load-bearing.
- `inspect/ui/README.md` — how to run dev, mock and tests.

Do not modify: `inspect/src/**` (`V1C-BE`), `demo/**` (`V4-PILOT`), `kernel/**`,
`concord/**`, `wire/**`, `gen/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`, and any plan
document other than this ticket's own `**Status**:` line.

## Explicitly out of scope

- **Anything under `inspect/src/**`, `kernel/**`, `demo/**`, `concord/**`** —
  other tickets own them, two of them concurrently.
- **Editing `20-api-contract.md`** — orchestrator-owned (constraint 8). Propose,
  never edit.
- **Remote cells' state.** Still `unavailable` / `"remote"`, deferred at the
  C-replan checkpoint on disclosure grounds (`20-wave-neutral-read-design.md`
  §3.7: a wave-neutral read has no emission and so passes through no disclosure
  filter). **Render the absence; do not work around it** — no speculative fetch
  through a peer, no cached last-known value.
- **A client-side index, a materialized mirror, or a background prefetch of all
  pages.** That is the "the inspector becomes a participant" failure mode
  §4.2 rejects by name. Every page is one explicit user action.
- **Browse-everything state chips on the canvas** (a state summary on many cells
  at once). It is one of the three capabilities the V1c chain unblocks, but it
  needs a fan-out read the backend does not expose this wave; `Canvas.tsx`'s
  chip layer stays driven by `state.summary` for observed cells only. If you
  find yourself adding a fetch loop over `nodeRefs()`, stop.
- **`View`/observation paging.** `V1C-KERNEL` Decision 9 excluded it; an
  observed cell keeps answering `kind: 'view'` with the encoder's existing
  200-row budget, and the walk terminates on page 1 there. Note the consequence
  in your report; do not work around it.
- **New npm dependencies.** Zero runtime dependencies beyond `solid-js` has held
  for six waves. No fetch/query/pagination library — `fetch` +
  `URLSearchParams` + a class is the whole mechanism.
- **Wiring `inspect/ui` into Gradle** (constraint 10). Tests are vitest, run by
  `npm test`.

## Acceptance criteria

**Paging**

- [ ] A cell answering `kind: 'page'` with a non-null `page.cursor` renders a
      walk affordance; a completed walk (`cursor: null`), a `snapshot`, a `view`
      and an `unavailable` render none.
- [ ] Each user action fetches **exactly one** page, echoing `page.cursor`
      verbatim as `?cursor=`; successive pages accumulate into one rendered
      value; the applied `page.limit` is read back rather than assumed.
- [ ] **No automatic multi-page fetching anywhere**: no prefetch, no
      fetch-on-scroll, no retry loop, no summary-driven page fetch. Asserted by
      transport call counts.
- [ ] Abandoning a walk mid-flight (select another cell / deselect / close)
      clears the accumulator, drops the cursor, and **discards the in-flight
      response** when it lands — asserted, not assumed.
- [ ] A 410 on a stale cursor restarts the walk from page 1 with no error
      surfaced to the user, and a second consecutive 410 stops rather than
      looping.
- [ ] The counter states only what the response supports (pages, `Σ entries`,
      "more available" iff `cursor != null`) and never invents a total or reuses
      `$truncated`'s "showing N of M" phrasing.
- [ ] Row-flash is suppressed for `kind: 'page'` — an appended page never
      renders as "added rows".
- [ ] `page.walkStable === false` renders a visible smeared-read note;
      `null` renders neither claim.
- [ ] **A paged fetch never opens, extends or releases an observation**: across
      a full multi-page walk, `observeStart` and `observeStop` are called zero
      times.

**Provenance, elision, unavailability**

- [ ] All three `provenance` values render distinctly; `checkpoint` is marked
      stale **at the value** (not in a tooltip, not only in the meta row);
      `liveSuspended` is not styled or worded as a warning; `provenance == null`
      renders nothing and is never defaulted to `'live'`.
- [ ] `staleMs` is no longer rendered as a freshness claim for
      `kind: 'page'`/`'snapshot'` (where the contract pins it to 0).
- [ ] `page.exclusivesElided > 0` renders as an ownership fact, in its own
      register, accumulated across the walk, never conflated with `$truncated`
      and never phrased as "load more to see them". **Final wording is quoted in
      the report.**
- [ ] Each `unreadable` value renders its own sentence; `unreadable == null`
      keeps the existing "State unavailable for this cell." verbatim, so
      `test/dom/detail-panel.test.tsx:61-69` passes unmodified.
- [ ] An unknown future `provenance`/`unreadable`/`kind` string renders
      something truthful rather than crashing or rendering blank.

**Cold**

- [ ] A cold-graph selection issues exactly one `GET .../state` and **zero**
      `POST/DELETE .../observe`; the rewritten
      `test/detailClient.test.ts:260-268` asserts both halves, and `:270-280`,
      `:282-290`, `:292-299` stay green unchanged.
- [ ] The cold notice states what is now served (state, labelled with its
      provenance) and what is not (flow; and the Part 4 unreadable cases),
      matching `V1C-BE.md`'s Part 4 table and no more. The Flow section says so
      too rather than rendering an empty rate table.
- [ ] `test/cold.test.ts:70-74`'s replacement assertions still fail on a notice
      that implies the cold value is current — the no-fake-preview intent
      survives the rewrite.
- [ ] `formatColdSkipHint` no longer offers a remedy that does not work for a
      held cell; `test/cold.test.ts:48-68` and
      `test/graphs-fixture.test.ts:168-172` are updated, and
      `fixtures/search-data-cold.json` is **not** edited.
- [ ] The cold copy was checked against a real response — a merged `V1C-BE`
      backend if one is available, otherwise the extended `mock/serve.mjs`
      serving each arm of the Part 4 table — **not** against this ticket's
      prose. The report says exactly which, and defers the live check to C10 if
      no backend was available.

**Tests and hygiene**

- [ ] DOM/component tests cover: a two-page walk, an abandoned walk, a 410
      restart, each of the three `provenance` values, and a non-zero
      `exclusivesElided` — using `test/dom/harness.tsx` (extended for query
      params and non-200 statuses), not a new harness.
- [ ] The walk state machine and the page-append helper are pure and unit-tested
      against a mock transport, in the `test/detailClient.test.ts` style.
- [ ] Exactly two new fixture files, named `cell-state-page.json` and
      `cell-state-page-checkpoint.json`. No third file, no rename; every other
      shape uses inline samples.
- [ ] `npm test`, `npm run typecheck` and `npm run build` are green.
- [ ] No new runtime dependency in `package.json`; `inspect/ui` still untouched
      by Gradle.
- [ ] Nothing outside `inspect/ui/**` in the diff. No `node_modules`, no `dist`,
      no unrelated files.

## Verify

```bash
cd inspect/ui
npm ci          # first run in a fresh worktree
npm test
npm run typecheck
npm run build
```

Offline manual pass (two shells):

```bash
cd inspect/ui && npm run mock          # the extended offline backend, §5
cd inspect/ui && npm run dev -- --port 5199
```

Live end-to-end pass, if `V1C-BE` has merged by the time you run it. The
inspector is opt-in on the pilot demos via `--inspect-port`, and Vite proxies
`/api/inspect` to whatever `INSPECT_BACKEND` names (`vite.config.ts:10,14`):

```bash
./gradlew :demo:skillmatch:run --args='--inspect-port 7231'      # shell 1
cd inspect/ui && INSPECT_BACKEND=http://localhost:7231 \
  npm run dev -- --port 5199                                     # shell 2
```

**Port rule** (`00-orchestration.md` §Sandbox): any live server must bind an
**ephemeral or explicitly chosen non-default** port. Concurrent sessions squat
7071 (inspector default), 8080 (demo default) and 5173 (Vite default) — the
numbers above are examples, pick your own free ones and record which you used.

## Report on completion

- Checks run and their results, plus screenshots of: a mid-walk paged view with
  its counter, a `checkpoint`-provenance value with its stale label, a
  `liveSuspended` value (the visual distinction from `checkpoint` should be
  obvious from the screenshot alone), a non-zero elided-exclusives line, and the
  rewritten cold screen.
- **Any deviation from `V1C-BE.md`'s Part 2 contract block — flagged loudly, at
  the top of the report.** The BE ran in parallel against that same block and
  the orchestrator must reconcile the two before C10. Say precisely which field,
  which shape, and what you rendered instead.
- **The exact final wording of (a) the cold notice(s), (b) the
  elided-exclusives label, (c) the `formatColdSkipHint` replacement, (d) the
  `walkStable: false` note, and (e) each `unreadable` sentence** — these are the
  ticket's user-visible judgment calls, and the orchestrator folds (a)–(c) into
  its view of what the product now claims.
- **Whether the paged walk needed anything the contract did not provide** — a
  total, a per-page frontier, an entry key, a way to tell "no more pages" apart
  from "cursor expired". Name it; do not work around it silently.
- Which arm you assumed for `cell-state-page-checkpoint.json` (`page` vs
  `snapshot`), and on what evidence.
- The module/file split you chose for the walk, and why.
- Whether `:inspect:test` failed in your worktree on `FixtureContractTest`'s
  directory-equality assertion (expected until `V1C-BE` merges — report it, do
  not work around it).
- Whether you extended `mock/serve.mjs`, and what a manual session showed.
- Which existing tests you rewrote, and confirmation that each rewrite preserved
  the original assertion's intent rather than loosening it — especially
  `test/cold.test.ts:70-74`'s no-fake-preview check and
  `test/detailClient.test.ts:260-268`'s no-subscribe check.
- The `View`-paging residual: what a user sees when an observed big cell's value
  is cut at the encoder's 200-row budget with no cursor to walk.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
