# V0-FE — Fix the net-toggle URL bug, delete dead toggle code, add a canvas legend

**Status**: Implemented — merged. (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first
word of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`
**Wave:** 1 · **Branches:** `ticket/v0-fe`

## Context

`inspect/ui` (`inspect/ui/src/`) is the ComputeNet Inspector's SolidJS/Vite
frontend, ~6.1 kLOC, zero runtime dependencies beyond `solid-js`. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full before
starting — it is this ticket's binding design and lists constraints that
apply below. This ticket is Wave 1 ("V0 — doorstep") of that plan; it does
four independent, already-scoped things against `inspect/ui/**` only (the
backend half of V0 — `V0-BE` — runs in parallel this wave against
`inspect/src/**`, a disjoint file claim).

The app has five overlay toggles (`10-target-v3.md`'s toggle table):
Process hosts, Network hosts, Flow, Errors, State — module-level signals in
`inspect/ui/src/solid/toggles.ts:10-14` (`showHosts`/`showNet`/`showFlow`/
`showErrors`/`showState`, each with its setter). All five became functional
across M1–M5-NET (`inspect/ui/README.md`'s per-milestone sections record
when); `ToggleBar.tsx` renders them and the URL hash
(`inspect/ui/src/nav/route.ts`) persists which ones are on across reload and
deep-link, the same way it persists the selected graph/cell. Network hosts
is the *last* toggle to go live (M5-NET) — and its hash round-trip was never
finished.

## Problem

**(a) The net toggle is lost on reload/deep-link — in two files, not one.**

- `inspect/ui/src/nav/route.ts:20-26`: `ToggleKey` is
  `'hosts' | 'flow' | 'errors' | 'state'` and `TOGGLE_KEYS` is
  `['hosts', 'flow', 'errors', 'state']` — `'net'` is absent from both. The
  comment above them (lines 20-24) says: *"'Network hosts' stays
  disabled/always-false through M5 ... so it has no real signal to
  serialize and is deliberately absent here rather than faked."* That claim
  is stale: M5-NET (`inspect/ui/README.md`'s "M5-NET" section) made the
  toggle functional and `ToggleBar.tsx` no longer disables it
  (`inspect/ui/src/components/ToggleBar.tsx:37`, `FUNCTIONAL` already
  includes `'net'` — see (b) below). Since `parseHash`
  (`route.ts:47-67`) builds its toggle set by filtering `TOGGLE_KEYS`
  against what is present in the hash (line 64), and `formatHash`
  (`route.ts:88-95`) writes toggles in `TOGGLE_KEYS` order, `showNet`'s
  value is silently dropped on every hash write and never restored on
  parse — reload or paste a deep link and Network hosts always comes back
  off, regardless of what it was set to.
- `inspect/ui/src/solid/route.ts:17-28`: `TOGGLE_GETTERS` and
  `TOGGLE_SETTERS` — the `Record<ToggleKey, ...>` maps `activeToggles()`
  (line 30-32) and `applyToggles()` (line 34-37) use to read/write the five
  signals by `ToggleKey` — also omit `net`/`showNet`/`setShowNet`. This is
  the second half of the same bug: even after `route.ts`'s `ToggleKey`
  union gains `'net'`, this file's two `Record`s must gain a `net:
  showNet` / `net: setShowNet` entry too, or `activeToggles()` never
  includes `'net'` in what gets written to the hash and `applyToggles()`
  never applies a parsed `'net'` back onto the `showNet` signal. (TypeScript
  will in fact refuse to compile a `Record<ToggleKey, T>` literal missing a
  variant once `'net'` joins the union — so `tsc --noEmit` catches an
  incomplete fix here, but the omission must still be reasoned about and
  fixed, not just patched until the compiler stops complaining.)

**(b) Dead code in `ToggleBar.tsx`.**
`inspect/ui/src/components/ToggleBar.tsx:37`: `const FUNCTIONAL = new
Set(['hosts', 'net', 'state', 'errors', 'flow'])` — already every key in
`TOGGLES` (lines 30-34), so the ternary at lines 44-54 (`FUNCTIONAL.has(t.key)
? <label class="toggle toggle--active">... : <label class="toggle"
title={\`Coming in ${t.milestone}\`}>...`) always takes the true branch; the
`false` branch (lines 49-54, the disabled/"Coming in {milestone}" label) and
the `milestone` field it alone reads (declared in the `TOGGLES` array's
inline type, lines 23-29, and every entry, lines 30-34) are unreachable.

**(c) No legend.** `inspect/ui/src/components/Canvas.tsx` renders several
encodings with no on-screen key: the color chip glyph (P/B/S —
`util/badges.ts`'s `colorGlyph`, `--cell-pure`/`--cell-blocking`/
`--cell-suspending` tokens), manifest badges (D/GF/R/PT —
`util/badges.ts`'s `manifestBadge`), edge role/fused rendering
(`Canvas.tsx`'s `EdgeLine`, lines 399-509: solid `.edge--consume` vs dashed
`.edge--observe`, and the doubled-line `is-fused` rendering for a fused
edge regardless of role), hull styles (`Canvas.css:40-42,58-60`: process
hulls solid, network hulls dashed — the comments there literally quote
`10-target-v3.md`'s toggle-table wording for this), and the state chip's
three-part reading (`Canvas.tsx:301-308`, `title="cardinality · frontier ·
staleness"`). A first-time viewer has no way to learn what any of this
means without reading the source. `10-design-notes.md`'s "Mock-up
references" paragraph lists "per-perspective legends" among the features
the v1/v2/v3 mock-ups (design targets, not specs) carry that the shipped UI
does not yet have.

**(d) `inspect/ui/README.md` has several statements superseded by
milestones that have since landed:**

- Line 11: `"Against a real `:inspect` server (once M0-BE lands):"` — M0-BE
  landed long ago (`../97-inspector-plan/90-progress-log.md`).
- Line 73 (in the "## M1" section): `"State, and Flow/Errors placeholders
  ('arrives with the ... milestone')."` — Flow and Errors are no longer
  placeholders; `DetailPanel.tsx` has had real Flow (M3) and Errors (M2)
  subsections for several milestones (see the README's own "## M3" section,
  which documents the Flow subsection "replacing the M1/M2 placeholder").
- Line 91 (in the "## M1" section): `'"Network hosts" stays disabled
  (M5).'` — superseded by M5-NET (the README's own "## M5-NET" section:
  *"The Network hosts toggle is functional, the last of the five; nothing
  in `ToggleBar` is disabled any more."*) — and doubly stale once (b) above
  lands.
- Lines 165-166 (in the "## M4" section): `"data mode's chip is disabled
  with an 'arrives in M5' tooltip and never issues a request (ahead of the
  BE's own 501)."` — superseded by M5-SEARCH; `Navigator.tsx`'s search chip
  is not disabled today (no `disabled`/tooltip logic remains in
  `src/components/Navigator.tsx` or `src/nav/search.ts`).
- Line 162 (in the "## M4" section, same paragraph as the data-search one):
  `'lifecycle: "cold" dims the card (M5 — always "hot" today)'` — also
  superseded, by M5-COLD (`Cold.kt`/`Waker.kt` on the backend; the frontend
  has a real `ColdScreen.tsx` and `solid/cold.ts` today). Not explicitly
  named in this ticket's brief, but it is the same class of staleness as
  the others in this section and sits in a paragraph this ticket is already
  touching — fix it too, or flag it in your report if you decide not to.

## Solution direction

**(a)** Add `'net'` to `ToggleKey` and `TOGGLE_KEYS` in `nav/route.ts`,
and add the matching `net: showNet` / `net: setShowNet` entries to
`TOGGLE_GETTERS`/`TOGGLE_SETTERS` in `solid/route.ts`. Update or remove the
now-false "stays disabled/always-false through M5 ... deliberately absent"
comment above `TOGGLE_KEYS` — leaving it in place would misdescribe the
code right below it. **Backward compatibility, stated explicitly in the
ticket brief:** an old hash written before this fix (four toggle tokens,
never `net`) must still parse correctly — `parseHash`'s existing filter-
by-`TOGGLE_KEYS` logic (`route.ts:63-64`) already achieves this for free
(a hash simply missing the `net` token parses with `net` absent from
`toggles`, which `applyToggles` then reads as `false`); confirm this with a
test rather than assuming it, since `TOGGLE_KEYS`' insertion position for
`'net'` affects nothing semantically but is worth pinning.

**(b)** Delete the ternary's disabled branch and `FUNCTIONAL` entirely;
render the `toggle toggle--active` label unconditionally for every entry in
`TOGGLES`. Remove the now-unused `milestone` field from `TOGGLES`' inline
type and every entry (it was read only by the branch you are deleting).
Leaving `ToggleBar.css`'s base `.toggle` rule (the `cursor: not-allowed`
styling for the state the deleted branch used to render) is fine — it is
dead in the sense that the disabled variant no longer renders, but the
class itself remains part of the shared `.toggle`/`.toggle--active` pair
every label still uses; touching `ToggleBar.css` is optional and at your
judgment, not required.

**(c)** Add a legend. Two parts, deliberately split so the interesting half
is testable without a DOM (this repo has zero DOM/component tests today —
see `10-design-notes.md`'s current-facts list — so anything you write that
needs one is untested until Wave 6's `FE-TESTS` ticket, not by this one):

1. A pure, framework-free module (new file, following the existing
   `src/util/*.ts` pattern — `util/badges.ts`, `util/errors.ts`,
   `util/flow.ts` are your closest exemplars) that decides *which* legend
   entries apply for a given toggle state. The cell-color/manifest-badge/
   edge-role entries are intrinsic to every node card and are not gated by
   any toggle (always present); the process-hull, network-hull, edge-fused/
   flow-color, error-badge/pill, and state-chip entries each only make
   sense when their corresponding toggle (`showHosts`/`showNet`/`showFlow`/
   `showErrors`/`showState`) is on — mirror the existing toggle-aware
   derivation style already used for badges/pills
   (`Canvas.tsx`'s `cellBadges`/`edgeParked`/`flowOverlays` memos each take
   their toggle's boolean as a plain parameter, e.g. `cellErrorBadges(...,
   showErrors())`) rather than importing the signals directly into the pure
   module. Exact function/type shape is your call; keep it importable and
   callable with plain boolean arguments so the unit test needs no Solid
   runtime.
2. A `Legend` component (new file under `src/components/`) that renders
   that module's output using the existing design tokens
   (`src/styles/tokens.css` — `--cell-pure`/`--cell-blocking`/
   `--cell-suspending`/`--cell-unknown`, `--edge-consume`/`--edge-observe`,
   `--error`/`--parked`, `--flow-pulse`, plus the neutral chrome tokens for
   its own chrome) and mounted somewhere reasonable on the Graph screen
   (`app.tsx`, alongside `<ToggleBar />`, or inside `Canvas.tsx` as its own
   overlay — your call; note where you put it and why in your report).
   Content: cell colors P/B/S, manifest badge abbreviations (D/GF/R/PT
   plus a note that others exist — `util/badges.ts`'s `manifestBadge`
   already documents the fallback), edge styles (consume solid, observe
   dashed if you choose to include it, fused double-line), hull styles
   (process solid, network dashed), and what the state chip's three
   segments mean. Must render correctly in both light and dark theme via
   the existing token mechanism (`tokens.css`'s `:root[data-theme]` /
   `@media (prefers-color-scheme: dark)` split, `demo/agora/ui` precedent
   already followed there) — do not hardcode colors.

The mock-ups referenced in `10-design-notes.md` ("Mock-up references") are
design targets, not a pixel spec; match their intent (a compact key, not a
full redesign), not their exact layout.

**(d)** Fix the five README spots listed in Problem (d): update the "Run"
section's stale M0-BE gate, and correct the four "arrives in M5"/"stays
disabled"/"still placeholders" statements to describe what is actually
shipped today (Flow/Errors are real subsections; Network hosts is
functional — and after (a) lands, its state also survives reload; data
search is not gated; cold lifecycle detection is real). Keep the
milestone-labeled section structure (`## M1`, `## M3`, `## M4`, `## M5-NET`)
— it is a useful history of what each ticket added — only correct the
individual sentences that are now factually wrong, not the whole document's
shape.

## Files expected to touch

- `inspect/ui/src/nav/route.ts` — `ToggleKey`/`TOGGLE_KEYS` (a).
- `inspect/ui/src/solid/route.ts` — `TOGGLE_GETTERS`/`TOGGLE_SETTERS` (a).
- `inspect/ui/src/components/ToggleBar.tsx` — dead-code deletion (b).
- A new pure module under `inspect/ui/src/util/` (or wherever you judge
  fits the existing layout best) and a new `inspect/ui/src/components/
  Legend.tsx` (or similar name) plus its `.css` — the legend (c).
- `inspect/ui/src/app.tsx` — only if you mount the legend there rather than
  inside `Canvas.tsx` (c).
- `inspect/ui/README.md` — the five stale statements (d).
- `inspect/ui/test/route.test.ts` — extended for the new toggle key (a).
- A new `inspect/ui/test/legend*.test.ts` (or similarly named) — unit tests
  for the pure legend module (c).

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — binding
  constraints (below) and V0's scope statement; "Mock-up references"
  paragraph for what the legend is drawing on.
- `inspect/ui/src/nav/route.ts` (whole file, 96 lines) — the hash
  parse/format pair and its round-trip test contract.
- `inspect/ui/src/solid/route.ts:1-37` — the Solid-facing wiring the pure
  module in `nav/route.ts` feeds into; `TOGGLE_GETTERS`/`TOGGLE_SETTERS`
  are the second half of bug (a).
- `inspect/ui/src/solid/toggles.ts` — the five toggle signals.
- `inspect/ui/src/components/ToggleBar.tsx` and `.css` — the dead-code
  target.
- `inspect/ui/src/components/Canvas.tsx:1-30,399-509` — imports (what
  encodings exist to explain) and `EdgeLine`'s consume/observe/fused
  rendering.
- `inspect/ui/src/components/Canvas.css:40-89,106-172` — hull and node-card
  styling, with the 10-target-v3.md quotes already in its comments.
- `inspect/ui/src/util/badges.ts` (whole file) — `colorGlyph`/
  `manifestBadge`, the exemplar for a small pure lookup module and the
  closest sibling to what the legend's data module should look like.
- `inspect/ui/src/util/errors.ts`, `inspect/ui/src/util/flow.ts` — the
  toggle-aware pure-derivation style (`fn(..., toggleBoolean)`) to mirror.
- `inspect/ui/src/styles/tokens.css` (whole file) — every color token
  available; theme handling (`:root[data-theme]` / `@media
  (prefers-color-scheme: dark)`).
- `inspect/ui/test/route.test.ts` (whole file) — the existing hash
  round-trip test shape to extend.
- `inspect/ui/README.md` (whole file) — the five spots to fix (Problem
  (d)), and the surrounding milestone-section structure to preserve.

Do not modify: `inspect/src/**` (owned by `V0-BE`, running in parallel this
wave), `kernel/**`, `demo/**`, `concord/**`, or
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (contract edits
are orchestrator-only). This ticket needs no contract change — everything
here is presentation-only.

## Binding constraints this ticket touches (from `10-design-notes.md`)

1. `inspect/ui` stays npm/Vite, not wired into Gradle (constraint 10) — not
   touched by this ticket, but do not add anything that changes that.
2. No edits under `concord/`; not applicable here but stated for the
   record.
3. This ticket makes no backend/contract change of any kind — everything
   in scope is `inspect/ui/**` presentation and URL-state handling.

## Acceptance criteria

- [ ] `TOGGLE_KEYS` (`nav/route.ts`) includes `'net'`; `TOGGLE_GETTERS`/
      `TOGGLE_SETTERS` (`solid/route.ts`) include a `net` entry mapped to
      `showNet`/`setShowNet`.
- [ ] A hash with `showNet` on round-trips through `formatHash`/`parseHash`
      with `'net'` present in `toggles`.
- [ ] A hash written by the *old* (pre-fix) format — four toggle tokens, no
      `net` — still parses without error, with `net` simply absent/false.
- [ ] `ToggleBar.tsx` has no disabled/"Coming in {milestone}" branch and no
      `FUNCTIONAL` set; every toggle renders as the active/checked variant
      it already does today for the other four.
- [ ] A pure module computes which legend entries are visible for a given
      toggle-state input, unit-tested directly (no DOM) for at least: all
      toggles off (only the always-on entries appear), each toggle on
      individually (its entry appears), and all toggles on.
- [ ] A `Legend` component renders that module's output using
      `tokens.css` custom properties only (no hardcoded hex/rgb color in
      the new component's styles) and is reachable from the Graph screen.
- [ ] The five stale README spots in Problem (d) read correctly against
      what is actually shipped (or are flagged in the report as
      deliberately left, with reasons).
- [ ] No unrelated files in the diff.

## Verify

```bash
cd inspect/ui
npm test
npm run typecheck
npm run build
```

Run the whole `npm test` suite (not just the new/extended test files) —
the toggle-key change is a type-level change to `ToggleKey` that
`solid/route.ts` and anything else importing it must still compile against.

## Report on completion

- Checks run and their results (`npm test`, `npm run typecheck`, `npm run
  build` — paste pass/fail summary).
- Files actually touched, and any not in the claim above.
- Where you mounted the `Legend` component and why.
- Whether you fixed the `lifecycle: "cold"` staleness at README line 162
  (Problem (d), not explicitly named in the ticket brief) or left it, and
  why.
- Anything specified here you could not do, and why.
