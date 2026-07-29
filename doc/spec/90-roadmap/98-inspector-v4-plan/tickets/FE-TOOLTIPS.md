# FE-TOOLTIPS — native `title=` becomes one positioned, structured, accessible tooltip layer

**Status**: Specified — not-started
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 6 · **Branches:** `ticket/fe-tooltips`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS app (npm +
Vite, **not** wired into Gradle) rendering a read-only view of a live
ComputeNet host's dataflow graph. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full first —
it is the decided design for this run and its "Binding constraints" section
governs this ticket.

**You run after FE-CANVAS, forking its session.** FE-CANVAS introduced a
viewport on the canvas: one shared CSS transform
(`translate(x, y) scale(s)`, `transform-origin: 0 0`) on a `.canvas__pan`
wrapper that contains *both* the SVG layer and the absolutely-positioned DOM
card layer, with `.canvas` itself `overflow: hidden`. Everything drawn on the
canvas therefore now sits at a client-space position that is **not** its
layout coordinate. That is the single most important fact for this ticket:
tooltip positioning must never be computed from layout coordinates again.

If for any reason your base does not contain FE-CANVAS, stop and say so in the
report rather than building positioning against `overflow: auto` scroll
offsets.

Line numbers below were read on `main` at 2026-07-29, before waves 1–5 and
FE-CANVAS landed. They point at *symbols*; search by name.

### Every tooltip in the app today is a native `title=`

Canvas (`src/components/Canvas.tsx`):

| Site | What it says |
|---|---|
| `:216-219` port dot `<title>` | `name (DIR)` |
| `:262` node card colour chip | the `CellColor` or `color unknown` |
| `:268` node card type row | the full `typeFqn` |
| `:275` node card manifest badge | the manifest string |
| `:303` state chip | the literal string `cardinality · frontier · staleness` |
| `:329` error badge | `N errors (dead letters + restarts)` |
| `:356` parked pill | `N parked` |
| `:478-480` `.edge-hit` SVG `<title>` | `util/flow.ts`'s `flowTooltip(...)` |

Elsewhere: `Header.tsx:37,47,69`, `ToggleBar.tsx:50`, `Navigator.tsx:56,133`,
`DetailPanel.tsx:36,77,83,92,185,307`.

The edge tooltip is the interesting one. `util/flow.ts` already derives
everything the v2 mock-up's flow tooltip shows — `formatRoute(from, to,
nameOf)` (`:104-107`) builds `producer.port → consumer.port`, and
`flowTooltip(route, overlay)` (`:117-125`) flattens the route plus last wave
(`source·counter`), hop and rate into **one string**, or the fused sentence
("fused — no observable messages"), or "no observed traffic". The structure
exists in `EdgeFlowOverlay` (`util/flow.ts:40-42`) and is thrown away at the
last step because a `title` attribute is all a native tooltip can take.

The `.edge-hit` line that carries it is the one element under
`.canvas__svg`'s blanket `pointer-events: none` that opts back in
(`Canvas.css:281-291`, `pointer-events: stroke`), and it is rendered **only
when the Flow toggle is on** (`Canvas.tsx:185-199`: `tooltip` is `undefined`
otherwise, and `<Show when={props.tooltip}>` gates the line).

Supporting pieces you will reuse rather than rebuild: `solid/motion.ts`'s
`prefersReducedMotion()` signal (live, via `matchMedia`), `src/styles/
tokens.css` (every colour, in light and dark, plus `--font-mono` for wave
stamps), `util/placement.ts`'s `isRemotePlacement`/`REMOTE_NOTICE` (the agreed
wording for a peer-hosted cell with no local host), and `util/badges.ts`'s
`shortType`/`manifestBadge`/`colorGlyph`.

## Problem

1. **A native `title` cannot render the content the design calls for.** It is
   a single unstyled string, shown after a browser-chosen delay (~1 s), with
   OS-controlled truncation, no line structure, no monospace for a wave stamp,
   and no appearance on keyboard focus or touch. `flowTooltip`'s
   string-flattening (`util/flow.ts:117-125`) exists purely because of that
   ceiling: the v2 mock-up shows route / role / last wave / hop / rate as
   labelled rows.
2. **Edges are only hoverable while the Flow toggle is on.** Route and role —
   *which port feeds which port, by which dispatch class* — are structural
   facts, true whether or not anyone is watching rates, and today they are
   unreachable unless a rate overlay is enabled.
3. **After FE-CANVAS, any hand-rolled positioning is wrong.** A tooltip
   positioned from `layout().nodes.get(ref)` coordinates lands in the wrong
   place at any scale ≠ 1 or pan ≠ 0. A tooltip rendered *inside* the
   transformed wrapper is itself scaled — 0.4× zoom gives unreadable tooltip
   text, 3× gives a tooltip the size of the window.
4. **Nothing is announced.** `title` is inconsistently exposed to screen
   readers and never on focus; the node cards are focusable
   (`tabIndex={0}`) and have nothing to describe them beyond their visible
   text.

## Solution direction

Decided. Deviate only with a reason recorded in your completion report.

### 1. Pure placement maths — `src/nav/tooltip.ts` (new)

Framework-free, no DOM, no Solid — same split as `nav/route.ts` vs
`solid/route.ts`, so the part that has edge cases is testable in the existing
node-environment vitest setup (there is no jsdom in this repo yet; FE-TESTS
adds it after you).

```ts
export interface Rect { x: number; y: number; w: number; h: number }
export type Placement = 'top' | 'bottom' | 'left' | 'right';
export function placeTooltip(
  anchor: Rect,            // client-space; a point anchor is a zero-size rect
  tip: { w: number; h: number },
  view: { w: number; h: number },
  opts?: { gap?: number; prefer?: Placement },
): { x: number; y: number; placement: Placement }
```

Rules: prefer the requested side, **flip** to the opposite side when it does
not fit, **shift** along the cross axis to stay inside the viewport, and never
return a position that overflows — when nothing fits, clamp and say so via the
returned placement. Gap constant ~8 px. Cover in `test/tooltip.test.ts`:
each of the four preferences, flip at each edge, cross-axis shift at each
corner, a tooltip larger than the viewport, a zero-size anchor (cursor mode).

### 2. One tooltip layer — `src/components/Tooltip.tsx` (+ `.css`, new)

- **Exactly one** tooltip element exists in the DOM at a time. Not one per
  edge, not one per node. It is rendered once near the app root
  (`src/app.tsx`) and driven by a controller signal.
- `position: fixed` in **client space**, mounted **outside** `.canvas__pan`.
  This is the structural answer to the transform: a fixed-position element is
  unaffected by any ancestor transform, and the anchor rect it is placed
  against is read with `getBoundingClientRect()` — already post-transform.
  Never convert layout coordinates to client coordinates by hand.
- Content is JSX, not a string: a title line plus labelled rows. Provide a
  small internal row primitive so every tooltip in the app looks the same.
- Styling exclusively from `src/styles/tokens.css` (`--surface-raised`,
  `--hairline-strong`, `--text`, `--text-secondary`, `--font-mono`,
  `--radius`), max-width ~22rem, `pointer-events: none` (a tooltip is never
  interactive — no links, no buttons, nothing focusable inside it), high
  `z-index` above the canvas and the detail panel.
- Motion: a short fade/offset-in only when `prefersReducedMotion()` is false
  (`solid/motion.ts`); otherwise it appears instantly.

### 3. The controller — `src/solid/tooltip.ts` (new)

One global "current tooltip" state: `{ content, anchor, prefer }` or null,
with `showTooltip(...)` / `hideTooltip()`.

- **Hover intent**: ~120 ms delay before showing, near-immediate hide.
  Re-targeting between two anchors while one is already shown skips the delay.
- **Anchoring**: node cards, badges, chips and pills anchor to their element's
  client rect (`getBoundingClientRect()`); edges **follow the cursor** — an
  edge's bounding box is a useless anchor for a long diagonal line — updating
  from `pointermove`, coalesced to at most one update per animation frame.
- **Dismiss** on: pointer leave, `Escape`, blur, selection change, and any
  viewport change (wheel, pan drag, zoom, fit) — a tooltip anchored to a rect
  that has since moved is worse than no tooltip. Wire the viewport-change
  dismissal off FE-CANVAS's viewport signal rather than by re-measuring.
- **Accessibility**: the tooltip element carries `role="tooltip"` and a stable
  id; when the anchor is focusable (the node cards are `tabIndex={0}`), set
  `aria-describedby` to that id while shown, show on `focus`/`focusin`, hide
  on `blur`/`Escape`. SVG edge hit-lines are not focusable; their tooltip is
  hover-only — state that limitation plainly in the report rather than faking
  a focus stop.

### 4. What the tooltips say

**Edge** (the v2 mock-up's flow tooltip, now structured):

- route — `producer.port → consumer.port`, reusing `formatRoute`;
- role — `CONSUME` / `OBSERVE`;
- then, depending on the flow overlay for that edge: last wave
  (`source·counter`, monospace), hop, and rate; **or** the fused explanation
  ("fused — no observable messages"); **or** the quiet explanation ("no
  observed traffic").

Move the *derivation* into `util/flow.ts` as a pure function returning
structured rows (e.g. `flowTooltipRows(route, role, overlay): {label, value}[]`)
so it stays unit-tested there — `test/flow-derive.test.ts` is the existing
home. Whether `flowTooltip` (the string form) survives is your call; if you
delete it, delete its tests with it and say so, and if any other caller
appears, keep it.

**Edges become hoverable regardless of the Flow toggle.** Render `.edge-hit`
always; when the toggle is off the tooltip shows route + role, and the flow
rows are simply absent. Consequences to preserve deliberately: `.edge-hit`
keeps `pointer-events: stroke` and everything else under `.canvas__svg` keeps
`pointer-events: none`; a click on a hit-line hits neither a card nor the
scene background, so it selects nothing and deselects nothing — today's
behaviour, now reachable more often. Confirm it still holds after FE-CANVAS's
drag-pan (a pointerdown on a hit-line must not start a pan, and must not
suppress one either — pick the behaviour that feels right and document it).

**Node card**: name, full `typeFqn`, colour class, manifests, lifecycle +
generation, and placement — process host and network host, using
`util/placement.ts`'s vocabulary for a peer-hosted cell (`host: null` reads as
"not reported (remote)", never a bare dash).

**Overlay chips**: the state chip's cryptic `cardinality · frontier ·
staleness` legend becomes labelled rows over the real values; the error badge
lists its dead-letter/restart split; the parked pill names the port and count.

### 5. What stays a native `title`

Keep `title=` where a rich tooltip adds nothing: the V0-FE canvas legend
badges, `Header.tsx`'s icon buttons ("Back to graphs", "Toggle light / dark"),
`ToggleBar.tsx`, `Navigator.tsx`'s card/mode buttons (their `title` doubles as
the accessible name — do not break that), and the whole of
`DetailPanel.tsx` (dense text already on screen; out of scope). Replace only
the canvas sites listed in the Context table. A bounded diff is part of the
deliverable.

### Exclusions

No tooltip library, no floating-ui, no popper — `package.json`
`dependencies` stays exactly `solid-js`. No interactive/pinnable tooltips, no
context menus. No changes to what the backend sends. No DOM/component test
infrastructure (FE-TESTS owns that, and runs after you). No changes to
`DetailPanel.tsx` or `Navigator.tsx` beyond leaving them alone.

## Files expected to touch

- `inspect/ui/src/nav/tooltip.ts` — **new**: pure placement maths.
- `inspect/ui/src/solid/tooltip.ts` — **new**: the controller signal, hover
  intent, dismissal wiring.
- `inspect/ui/src/components/Tooltip.tsx` + `Tooltip.css` — **new**: the
  single fixed-position layer.
- `inspect/ui/src/app.tsx` — mount the layer once, outside the canvas.
- `inspect/ui/src/components/Canvas.tsx` — anchor wiring; `.edge-hit` always
  rendered; the listed `title=` attributes removed.
- `inspect/ui/src/components/Canvas.css` — `.edge-hit` gating changes.
- `inspect/ui/src/util/flow.ts` — structured tooltip rows replacing/besides
  `flowTooltip`.
- `inspect/ui/test/tooltip.test.ts` — **new**; `test/flow-derive.test.ts` —
  extended for the structured rows.
- `inspect/ui/README.md` — a line, if the controls section warrants it.

File claim: `inspect/ui/**` only. Touching anything outside it: note it in the
completion report rather than expanding silently. FE-TESTS follows you in this
wave and will render `Tooltip.tsx` directly — keep its props plain and its
content pure.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — §"Binding
  constraints", §"Verticals → FE track", and the mock-up feature list
  ("positioned edge tooltips (route / last wave / hop)").
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/FE-CANVAS.md` — the
  transform you must position against, and its completion report in your
  session history.
- `inspect/ui/src/util/flow.ts:40-42` (`EdgeFlowOverlay`), `:104-107`
  (`formatRoute`), `:117-125` (`flowTooltip`) — the derivation to restructure.
- `inspect/ui/src/components/Canvas.tsx:179-203` (edge rendering and the
  tooltip gate), `:399-509` (`EdgeLine`, including the `.edge-hit` `<Show>`),
  and the `title=` sites listed in the Context table.
- `inspect/ui/src/components/Canvas.css:281-291` — why `.edge-hit` is the one
  pointer-events opt-in under the SVG.
- `inspect/ui/src/solid/motion.ts` — the reduced-motion signal, and its own
  note on why it stays live rather than read-once.
- `inspect/ui/src/styles/tokens.css` — light and dark; never a raw hex.
- `inspect/ui/src/util/placement.ts` — the remote-placement wording.
- `doc/spec/90-roadmap/97-inspector-plan/tickets/M3-FE.md` §3 — the original
  edge-tooltip requirement this supersedes, and the house ticket voice.

Do not modify: `inspect/src/**`, `kernel/**`, `concord/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`
(orchestrator-owned), any plan document other than this ticket's `**Status**:`
line.

## Acceptance criteria

- [ ] Exactly one tooltip element exists at a time, `position: fixed`, mounted
      outside the canvas transform; it does not scale with zoom and its
      placement is correct at scale ≠ 1 and after panning.
- [ ] It never leaves the viewport: flips and shifts at every edge and corner,
      proven by pure tests in `test/tooltip.test.ts`.
- [ ] Edge hover shows route (`from.port → to.port`), role, and — when there
      is flow data — last wave (`source·counter`), hop and rate; a fused edge
      shows the fused explanation; a quiet edge says so. Edges are hoverable
      with the Flow toggle **off**.
- [ ] Node hover shows type, host/net (peer wording for a remote cell), and
      lifecycle + generation.
- [ ] Keyboard/focus: focusing a node card shows its tooltip and sets
      `aria-describedby`; `Escape` and blur dismiss it. The tooltip itself is
      never focusable and contains nothing interactive.
- [ ] Dismissed on scroll/zoom/pan/selection change — no tooltip is ever left
      pointing at a stale position.
- [ ] `prefers-reduced-motion: reduce` removes the appearance animation.
- [ ] Legible in both light and dark, entirely from `tokens.css` variables.
- [ ] The listed canvas `title=` attributes are gone; the deliberately-kept
      ones (legend, Header, ToggleBar, Navigator, DetailPanel) are untouched.
- [ ] Selection behaviour is unchanged: card click selects, background click
      deselects, drag-pan does neither.
- [ ] No new entry in `package.json` `dependencies`.
- [ ] `npm test`, `npm run typecheck`, `npm run build` green.
- [ ] No unrelated files in the diff; nothing outside `inspect/ui/**`.

## Verify

```bash
cd inspect/ui
npm ci            # only if node_modules is absent in a fresh worktree
npm test
npm run typecheck
npm run build
```

Manual pass (required — this is a hover feature). Choose your own ports;
concurrent sessions squat the defaults:

```bash
cd inspect/ui
PORT=7192 npm run mock &
INSPECT_BACKEND=http://localhost:7192 npm run dev
```

Exercise: hover an edge with the Flow toggle off and on; hover a fused edge;
hover a node card, a state chip, an error badge, a parked pill; hover near all
four screen edges; hover while zoomed to 0.4× and to 2.5×, and while panned;
tab to a card; press `Escape`; toggle the OS reduced-motion setting; both
colour themes.

## Report on completion

- Checks run and their results; the manual pass, with what you saw.
- How positioning accounts for the canvas transform (which rect you measure,
  and why no scene→client conversion is needed).
- Whether `flowTooltip`'s string form survives, and what happened to its tests.
- What a click on an always-rendered `.edge-hit` now does, and how that
  interacts with FE-CANVAS's drag-pan threshold.
- The accessibility limitation on SVG edges (hover-only, no focus stop),
  stated plainly.
- Which `title=` attributes you kept and why, if you departed from the list.
- Anything FE-TESTS should know to render `Tooltip.tsx` in isolation.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
