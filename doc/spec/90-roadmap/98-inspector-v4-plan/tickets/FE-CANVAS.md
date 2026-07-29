# FE-CANVAS — the canvas gets a viewport: cursor-anchored zoom, drag-pan, fit-to-screen

**Status**: Specified — not-started
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 6 · **Branches:** `ticket/fe-canvas`

## Context

You are working on `inspect/ui`, the Inspector frontend: a SolidJS app (npm +
Vite, **not** wired into Gradle) that renders a read-only view of a live
ComputeNet host's dataflow graph, served by the `:inspect` backend over
HTTP/SSE. Read `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in
full first — it is the decided design for this run and its "Binding
constraints" section governs this ticket (constraint 10 in particular:
`inspect/ui` stays npm/Vite).

Your branch starts from `main` after checkpoint C5, so waves 1–5 (V0, V1a,
V1b, V1-DEMO, V2, V3) are already merged into your base. **Every line number
in this ticket was read on `main` at 2026-07-29, before those waves landed.**
Treat them as pointers to a *symbol*, not as coordinates: search for the named
function/class/selector, and expect the surrounding file to have grown.

How the canvas renders today (`inspect/ui/src/components/Canvas.tsx`, 509 LOC
at time of writing):

- Layout is **analytic, not measured**: `layout/layered.ts`'s
  `createLayeredLayout()` computes a Sugiyama-style layered placement and
  returns `{ nodes: Map<Ref, LayoutNode>, width, height }` in an abstract
  scene-coordinate space (`layered.ts:93-117`). `NODE_W = 208`, `NODE_H = 80`,
  column gap 96, row gap 28, margin 32. One shared, persistent engine instance
  lives in `solid/layout.ts` so slot assignments survive recomputation.
- Rendering is **hybrid**, and this is the constraint that shapes this whole
  ticket. Inside `.canvas__scene` (sized to `layout().width/height` in px)
  there are two co-registered layers:
  1. one `<svg class="canvas__svg">` (absolutely positioned at 0,0, sized to
     the same width/height) carrying net hulls, host hulls, edge lines, the
     `.edge-hit` hover lines, the flow pulses and the port dots;
  2. a set of **absolutely-positioned DOM elements** — `.node-card`,
     `.node-state-chip`, `.node-error-badge`, `.edge-parked-pill`,
     `.edge-flow-label` — each positioned with `left`/`top` in px computed
     from the same layout coordinates (`Canvas.tsx:243-248`, `:302`, `:328`,
     `:352-355`, `:383-385`).

  The two layers agree today only because both consume the same numbers in the
  same units. Any viewport transform that applies to one and not the other
  makes edges detach from the cards they connect.
- There is no viewport at all: `.canvas { flex: 1; overflow: auto; position:
  relative }` (`Canvas.css:1-6`). The browser's own scrollbars are the only
  navigation.
- Interaction: a card is `role="button" tabIndex={0}`, selects on click
  (`e.stopPropagation()` then `setSelection(ref)`, `Canvas.tsx:252-255`) and on
  Enter/Space (`onCardKeyDown`, `:126-131`). `onSceneClick` (`:135-137`)
  deselects, but **only** when `e.currentTarget === e.target` — i.e. only when
  the click landed on the scene background itself and did not bubble up from a
  card. Both behaviours must survive this ticket.
- Per-graph view state today: the toggle signals (`solid/toggles.ts`) are
  module-level and survive navigation — `enterGraph` (`solid/route.ts:91-99`)
  deliberately does not reset them ("thumbnail click-through preserves
  toggles") — while `selection` is reset per entry. The URL hash carries graph
  id + selected ref + the toggle set and nothing else (`nav/route.ts`, with
  `TOGGLE_KEYS` at `:26`; wave 1's V0-FE ticket adds the missing `net` key).
- Tests: 24 vitest suites under `test/`, all **pure-module**, `environment:
  'node'` (`vite.config.ts`). There is no DOM test infrastructure — that is
  FE-TESTS' job, later in this same wave. Your own tests must therefore be
  pure-module tests, which is why the solution direction below insists on a
  framework-free viewport module.

## Problem

1. **A graph larger than the window can only be scrolled.** Scene width grows
   as `2·32 + (layers+1)·208 + layers·96` (`layered.ts:114`), so a ten-layer
   pipeline is ~3 000 px wide before hulls. There is no way to see the shape of
   a graph, no way to get back to a node you scrolled away from, and no
   overview→detail move at all. The v2/v3 mock-ups assume one.
2. **No fit-to-screen on entry.** Entering a graph from the navigator drops the
   user at scroll offset (0, 0) of an arbitrarily large scene. The
   constellation thumbnail they clicked showed the whole component
   (`layout/constellation.ts` reuses the layout engine at thumbnail scale); the
   canvas then shows its top-left corner.
3. **The hybrid layer split makes the naive fixes wrong.** Zooming via the
   SVG's `viewBox` scales the SVG layer only, leaving every card, chip, badge,
   pill and flow label at its original position and size. Scaling each layer
   with its own transform re-introduces the same divergence one refactor
   later. Whatever you build must make "the two layers cannot disagree" a
   structural property, not a discipline.
4. **Keyboard users have no viewport control.** Cards are focusable and
   selectable by keyboard, but a focused card outside the scroll window is
   reachable only through the browser's own scroll-into-view; there is no
   zoom-out to orient with.

## Solution direction

Decided. Deviate only with a reason recorded in your completion report.

### 1. A pure viewport module — `src/nav/viewport.ts` (new)

Framework-free, no DOM, no Solid import — the same split `nav/route.ts` has
against `solid/route.ts`, and for the same reason: the maths is where the bugs
live, and it must be unit-testable in the existing node-environment vitest
setup. Export at least:

```ts
export interface Viewport { readonly x: number; readonly y: number; readonly scale: number }
```

`x`/`y` are the scene→client translation in CSS px, `scale` the zoom factor;
the mapping is `client = scene * scale + (x, y)` with `transform-origin: 0 0`.

- `MIN_SCALE = 0.1`, `MAX_SCALE = 3` (exported constants).
- `clampScale(scale): number`.
- `zoomAt(vp, anchor: {x, y}, factor, ...): Viewport` — **cursor-anchored**:
  the scene point under `anchor` (a point in the canvas element's client
  coordinate space) is the same scene point under `anchor` afterwards. This is
  the property to test directly, not the algebra you used to get it.
- `fitBounds(scene: {w, h}, view: {w, h}, opts?): Viewport` — scale to fit with
  a margin (prescribe ~24 px), **clamped to `MAX_FIT_SCALE = 1`** so a
  three-cell graph is centred at 100 % rather than blown up, then centred on
  both axes. An empty scene (`w === 0 || h === 0`) returns the identity
  viewport rather than dividing by zero.
- `panBy(vp, dx, dy): Viewport`, and a clamp that keeps the scene from being
  lost: after any pan or zoom, at least `MIN_VISIBLE = 64` px of the scaled
  scene must remain inside the view rectangle on each axis. Losing the graph
  entirely and having no way back is the failure mode this prevents.

Test it in `test/viewport.test.ts` — zoom-at-point invariance (including at
the clamp boundaries, where the anchor must *still* hold or the zoom must be a
no-op), fit-bounds (wide scene, tall scene, tiny scene, empty scene),
scale clamping, pan clamping.

### 2. Per-graph viewport state — `src/solid/viewport.ts` (new)

Module-level state, session-local, keyed by graph id — matching how toggles
already survive navigation:

- a `Map<string, Viewport>` of last-seen viewports per graph id, plus the
  current `viewport()` signal and `setViewport`;
- `fitToScreen()`, `zoomBy(factor, anchor?)` (anchor defaults to the view
  centre), `resetZoom()` (scale 1, re-centred);
- entering a graph with **no** stored viewport fits it to screen once the
  layout first reports a non-zero size (the topology fetch is async, so the
  first render is an empty scene — a `createEffect` gated on
  `layout().width > 0` is the natural hook, and it must fire **once** per
  graph, not on every subsequent structural change);
- entering a graph **with** a stored viewport restores it verbatim.

**The viewport does not go in the URL hash.** Decided: no. Record the reason
in the module's doc comment — the hash is the shareable identity of *what* is
being looked at (graph, selection, toggles: `nav/route.ts`), a viewport is a
continuous per-session ergonomic that would call `history.replaceState` on
every wheel tick, and `formatHash`'s canonicalized token list has no sensible
encoding for floats. If you conclude otherwise while implementing, do not
change it unilaterally — flag it in the report.

### 3. One shared transform — `Canvas.tsx` + `Canvas.css`

Introduce exactly one new element between `.canvas` and the existing scene:

```
.canvas            (overflow: hidden; position: relative)  ← the viewport window
  └ .canvas__pan   (transform: translate(Xpx, Ypx) scale(S); transform-origin: 0 0)
      └ .canvas__scene   (unchanged: width/height in layout px)
          ├ svg.canvas__svg      (unchanged)
          └ .node-card, .node-state-chip, … (unchanged)
```

Both layers are descendants of the one transformed element, so they cannot
diverge — that is the whole point, and it is why the transform goes on a
wrapper rather than on each layer. `.canvas` changes from `overflow: auto` to
`overflow: hidden`; the browser's scrollbars are replaced by pan.

- Do **not** zoom via the SVG's `viewBox`, and do not scale the SVG and the
  card layer separately.
- Do **not** put `will-change: transform` or `transform: translateZ(0)` on the
  transformed wrapper. Those promote it to a GPU layer rasterized once at one
  scale, which is precisely how scaled DOM text goes blurry; without them
  browsers re-rasterize text at the composited scale. That is the cheap fix.
  If text still degrades at high zoom, accept it and say so in the report — do
  not counter-scale font sizes per element.
- Overlay chips/badges/pills/labels scale with everything else. Counter-scaling
  them (constant-size labels at any zoom) is explicitly **out of scope**.
- No CSS `transition` on the transform: it would fight per-frame wheel updates
  and add motion no one asked for. Fit and reset apply instantly, which also
  makes `prefers-reduced-motion` a non-question here.

### 4. Pointer interaction

- **Wheel**: plain wheel/trackpad scroll **pans** (`deltaX`/`deltaY`);
  `ctrlKey`- or `metaKey`-modified wheel **zooms**, cursor-anchored. macOS
  trackpad pinch arrives as a wheel event with `ctrlKey: true`, so pinch is
  covered by the same path. Register the listener explicitly in `onMount` with
  `addEventListener('wheel', handler, { passive: false })` and remove it in
  `onCleanup` — you must be able to `preventDefault()` the browser's own
  page-zoom/scroll, which a passive listener cannot do. Coalesce updates to at
  most one per animation frame.
- **Drag to pan**: `pointerdown` on the scene background starts a pan — same
  background test `onSceneClick` already uses (`e.currentTarget === e.target`),
  so a pointerdown on a card never starts one. Use pointer events with
  `setPointerCapture` so a drag that leaves the window still ends cleanly.
- **Click vs drag**: apply a movement threshold (prescribe 4 px). Below it the
  gesture is a click and the existing deselect-on-background-click must still
  fire; at or above it, suppress the resulting `click` so panning never
  deselects. Card selection by click is unaffected (the card handler already
  calls `stopPropagation`).
- Cursor: `grab` on the background, `grabbing` while panning; cards keep
  `pointer`.

### 5. Fit-to-screen, zoom controls, keyboard

- **Fit** happens: on first entry to a graph (§2), on the Fit control, and on
  the `0` key. It reads `layout().width/height` and the live client size of
  `.canvas` — observe the latter with a `ResizeObserver` created in `onMount`
  and disconnected in `onCleanup`, so a window resize or a detail-panel width
  change does not leave a stale fit basis.
- **Zoom controls** — a new `src/components/ZoomControls.tsx` (+ `.css`),
  rendered inside `.canvas` but **outside** `.canvas__pan` so it does not
  scale. Absolutely positioned in a corner, styled from
  `src/styles/tokens.css` variables only (it must read correctly in light and
  dark; never introduce a raw hex). Real `<button>` elements with `aria-label`
  and `title`: zoom out (`−`), a percentage readout that resets to 100 % when
  clicked, zoom in (`+`), and `Fit`. A separate component file, not inline JSX
  — FE-TESTS renders it directly.
- **Keyboard**: `+` / `=` zoom in, `-` / `_` zoom out, `0` fit to screen, all
  anchored at the view centre. Bind on the Graph screen only, ignore the event
  when the target is an `<input>`, `<textarea>` or anything
  `isContentEditable`, and do not steal keys while a modifier that means
  something else (Ctrl/Cmd) is held. Enter/Space on a focused card must still
  select it.
- Empty graph: the `canvas__empty` fallback path stays as it is — no controls
  over an empty scene, or controls that are inert; either is acceptable, say
  which you chose.

### Exclusions

No minimap. No zoom-to-selection or animated fly-to. No layout changes
(`layout/**` geometry is untouched). No new runtime dependency — no `d3-zoom`,
no `panzoom` library; `package.json`'s `dependencies` stays exactly
`solid-js`. No backend changes. No DOM/component test infrastructure (that is
FE-TESTS; do not add jsdom here). No counter-scaled labels.

## Files expected to touch

- `inspect/ui/src/nav/viewport.ts` — **new**: the pure viewport module.
- `inspect/ui/src/solid/viewport.ts` — **new**: per-graph viewport state and
  the fit/zoom/reset actions.
- `inspect/ui/src/components/ZoomControls.tsx` + `ZoomControls.css` — **new**.
- `inspect/ui/src/components/Canvas.tsx` — the `.canvas__pan` wrapper, wheel /
  pointer / keyboard wiring, `ResizeObserver`, first-entry fit effect.
- `inspect/ui/src/components/Canvas.css` — `.canvas` becomes
  `overflow: hidden`; `.canvas__pan`; cursor states.
- `inspect/ui/test/viewport.test.ts` — **new**: the pure-maths suite.
- `inspect/ui/src/solid/route.ts` — only if the first-entry fit is genuinely
  cleaner hooked into `enterGraph`/`goHome` than into a Canvas effect.
- `inspect/ui/README.md` — one short paragraph on the canvas controls.

File claim: `inspect/ui/**` only. Touching anything outside it: note it in the
completion report rather than expanding silently. FE-TOOLTIPS forks your
session and edits `Canvas.tsx`/`Canvas.css` after you; leave those files in a
state you would want to inherit.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — §"Binding
  constraints" (all ten), §"Verticals → FE track", §"Standing file split".
- `inspect/ui/src/components/Canvas.tsx` (whole file) — especially the two
  co-registered layers (`:139-393`), `onSceneClick` (`:135-137`), the card
  click/keyboard handlers (`:126-131`, `:249-256`), and the four
  absolutely-positioned overlay layers (`:288-392`).
- `inspect/ui/src/components/Canvas.css:1-22` — `.canvas` / `.canvas__scene` /
  `.canvas__svg`, and `.edge-hit`'s `pointer-events: stroke` opt-in
  (`:281-291`), which is the one element under the SVG that takes pointer
  events today.
- `inspect/ui/src/layout/layered.ts:74-120` — the scene coordinate space and
  the `width`/`height` you fit against.
- `inspect/ui/src/nav/route.ts` and `inspect/ui/src/solid/route.ts` — the
  framework-free/Solid split to mirror, `TOGGLE_KEYS`, `enterGraph`/`goHome`,
  and the `history.replaceState` effect your viewport must stay out of.
- `inspect/ui/src/solid/toggles.ts` + `solid/layout.ts` — the module-level
  singleton pattern for state that survives navigation.
- `inspect/ui/src/styles/tokens.css` — every colour you use comes from here.
- `inspect/ui/test/layout.test.ts` — the pure-module test idiom to follow.
- `doc/spec/90-roadmap/97-inspector-plan/tickets/M3-FE.md` — the house ticket
  voice, and the overlay work your transform now has to carry.

Do not modify: `inspect/src/**`, `kernel/**`, `concord/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`
(orchestrator-owned), any plan document other than this ticket's `**Status**:`
line.

## Acceptance criteria

- [ ] Wheel-with-modifier (and trackpad pinch) zooms about the cursor: the
      scene point under the pointer does not move. Covered by a pure test in
      `test/viewport.test.ts`, not only by eye.
- [ ] Plain wheel pans; dragging the background pans; dragging never
      deselects; a background click below the drag threshold still deselects;
      clicking a card still selects it; Enter/Space on a focused card still
      selects it.
- [ ] `+` / `-` / `0` work on the Graph screen and do nothing while typing in
      a text field.
- [ ] Entering a graph for the first time fits it to screen; leaving and
      re-entering the same graph restores the viewport the user left it at;
      a different graph gets its own.
- [ ] The SVG layer and the DOM card layer never diverge: they are children of
      one transformed element, and no second transform is applied to either.
      State this explicitly in the report with the element that carries it.
- [ ] Scale is clamped to [0.1, 3]; fit never scales above 1; after any
      gesture at least part of the scene is still on screen.
- [ ] The zoom controls are `<button>`s with accessible names, live outside
      the transformed wrapper (they do not scale), and are styled from
      `tokens.css` variables — verified in both light and dark.
- [ ] No new entry in `package.json` `dependencies`; no jsdom, no DOM test
      runner added by this ticket.
- [ ] `npm test`, `npm run build` and `npm run typecheck` are green.
- [ ] No unrelated files in the diff; nothing outside `inspect/ui/**`; no
      `node_modules/` or build output.

## Verify

```bash
cd inspect/ui
npm ci            # only if node_modules is absent in a fresh worktree
npm test
npm run typecheck
npm run build
```

Manual pass (required — this is a visual feature). The mock backend needs no
`:inspect` server and no free default port is guaranteed, so pick your own:

```bash
cd inspect/ui
PORT=7191 npm run mock &            # fixture backend
INSPECT_BACKEND=http://localhost:7191 npm run dev
```

Exercise: enter a graph from Home (fits), zoom in/out at the cursor, pan by
drag, `0` to refit, the four controls, back to Home and re-enter (viewport
restored), a window resize followed by a fit, and both colour themes.

## Report on completion

- Checks run and their results; the manual pass, with what you saw.
- The exact element carrying the shared transform, and how you convinced
  yourself the two layers cannot diverge.
- Text rendering under zoom: readable, or degraded — and what you did or
  deliberately did not do about it.
- The URL-hash decision as implemented (default: viewport not in the hash) and
  whether anything you found argues against it.
- Whether the first-entry fit is hooked in `Canvas.tsx` or `solid/route.ts`,
  and why.
- Anything FE-TOOLTIPS (which forks your session) needs to know about
  `Canvas.tsx`/`Canvas.css` as you leave them — in particular how a hovered
  element's client-space position now relates to layout coordinates.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
