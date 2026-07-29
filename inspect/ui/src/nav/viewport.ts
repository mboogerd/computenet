// FE-CANVAS ticket Solution direction §1: the pure canvas viewport module.
// Framework-free (no DOM, no Solid import) — the same split `nav/route.ts`
// has against `solid/route.ts`, and for the same reason: this is where the
// zoom/pan/fit maths (and its bugs) live, and it must be directly
// unit-testable in the existing node-environment vitest setup
// (`test/viewport.test.ts`). `solid/viewport.ts` is the thin Solid-signal
// wiring on top of this.
//
// Coordinate convention: `Viewport.x`/`.y` is the scene->client translation
// in CSS px, `.scale` the zoom factor, applied as a single CSS transform
// with `transform-origin: 0 0` — `client = scene * scale + (x, y)`. This is
// the exact mapping `Canvas.tsx` applies on `.canvas__pan`, so every
// function here operates in "client px relative to the `.canvas` element's
// own top-left corner" for anchors/view rectangles, and "layout px" (the
// `layout/layered.ts` coordinate space) for scene rectangles.

export interface Viewport {
  readonly x: number;
  readonly y: number;
  readonly scale: number;
}

export interface Size {
  readonly w: number;
  readonly h: number;
}

export interface Point {
  readonly x: number;
  readonly y: number;
}

export const MIN_SCALE = 0.1;
export const MAX_SCALE = 3;
/** `fitBounds` never blows a small graph up past 100% — a three-cell graph
 *  is centred at 100%, not zoomed to fill the window. */
export const MAX_FIT_SCALE = 1;
/** `fitBounds`' default margin around the fitted scene, in CSS px. */
export const FIT_MARGIN = 24;
/** The pan/zoom clamp's floor: after any gesture, at least this many px of
 *  the *scaled* scene must remain inside the view rectangle on each axis —
 *  losing the graph entirely, with no way back, is the failure mode this
 *  prevents. */
export const MIN_VISIBLE = 64;

export const IDENTITY_VIEWPORT: Viewport = { x: 0, y: 0, scale: 1 };

export function clampScale(scale: number): number {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

/** The pan-loss clamp shared by every gesture (`zoomAt`, `panBy`,
 *  `resetViewport` all route through this): after the gesture, at least
 *  `min(MIN_VISIBLE, <the scaled scene's own extent>)` px of the scaled
 *  scene must still overlap the view rectangle on each axis. The `min(...)`
 *  against the scene's own scaled extent means a scene that is itself
 *  smaller than `MIN_VISIBLE` (e.g. fully zoomed out) is never held to a
 *  floor it cannot satisfy — the whole (small) thing being on screen is
 *  already enough.
 *
 *  A zero-area `scene` or `view` (an empty graph, or a canvas that has not
 *  been measured yet) makes the clamp a no-op rather than a division/NaN
 *  hazard — there is nothing meaningful to clamp against yet. */
export function clampViewport(vp: Viewport, scene: Size, view: Size): Viewport {
  if (scene.w <= 0 || scene.h <= 0 || view.w <= 0 || view.h <= 0) return vp;

  const scaledW = scene.w * vp.scale;
  const scaledH = scene.h * vp.scale;
  const visW = Math.min(MIN_VISIBLE, scaledW);
  const visH = Math.min(MIN_VISIBLE, scaledH);

  // At least `visW`/`visH` px of [x, x+scaledW]/[y, y+scaledH] must overlap
  // [0, view.w]/[0, view.h]: x >= visW - scaledW and x <= view.w - visW (and
  // the same shape for y). If the view is narrower than both floors at once
  // (minX > maxX — a pathologically small view), leave that axis alone
  // rather than force an unsatisfiable clamp.
  const minX = visW - scaledW;
  const maxX = view.w - visW;
  const minY = visH - scaledH;
  const maxY = view.h - visH;
  const x = minX <= maxX ? Math.min(Math.max(vp.x, minX), maxX) : vp.x;
  const y = minY <= maxY ? Math.min(Math.max(vp.y, minY), maxY) : vp.y;

  return x === vp.x && y === vp.y ? vp : { x, y, scale: vp.scale };
}

/** Cursor-anchored zoom: the scene point under `anchor` (a point in the
 *  `.canvas` element's own client coordinate space) is the same scene point
 *  under `anchor` afterwards. Solve `client = scene * scale + (x, y)` for
 *  the scene point under the anchor at the current viewport
 *  (`sceneAtAnchor = (anchor - vp) / vp.scale`), then for the new `(x, y)`
 *  that keeps that same scene point under the same anchor at the new scale
 *  (`newXY = anchor - sceneAtAnchor * newScale`).
 *
 *  At a scale-clamp boundary (already at `MIN_SCALE`/`MAX_SCALE` and
 *  `factor` pushes further that direction) `clampScale` leaves the scale
 *  unchanged, and this returns `vp` verbatim — a true no-op, which trivially
 *  keeps the anchor invariant (nothing moved) rather than attempting a zero-
 *  effect translation. */
export function zoomAt(vp: Viewport, anchor: Point, factor: number, scene: Size, view: Size): Viewport {
  const newScale = clampScale(vp.scale * factor);
  if (newScale === vp.scale) return vp;

  const sceneX = (anchor.x - vp.x) / vp.scale;
  const sceneY = (anchor.y - vp.y) / vp.scale;
  const unclamped: Viewport = {
    x: anchor.x - sceneX * newScale,
    y: anchor.y - sceneY * newScale,
    scale: newScale,
  };
  return clampViewport(unclamped, scene, view);
}

/** Scale `scene` to fit inside `view` with a margin, clamped to
 *  `MAX_FIT_SCALE` so a small graph is centred at 100% rather than blown up,
 *  then centred on both axes. An empty scene (`w <= 0 || h <= 0` — the
 *  async-topology-fetch boot frame, before the first snapshot lands) returns
 *  the identity viewport rather than dividing by zero. */
export function fitBounds(scene: Size, view: Size, opts?: { margin?: number }): Viewport {
  if (scene.w <= 0 || scene.h <= 0) return IDENTITY_VIEWPORT;

  const margin = opts?.margin ?? FIT_MARGIN;
  const availW = Math.max(view.w - margin * 2, 1);
  const availH = Math.max(view.h - margin * 2, 1);
  const scale = clampScale(Math.min(MAX_FIT_SCALE, availW / scene.w, availH / scene.h));
  const x = (view.w - scene.w * scale) / 2;
  const y = (view.h - scene.h * scale) / 2;
  return { x, y, scale };
}

/** Scale fixed at 100%, centred over `view` — the zoom controls' percentage
 *  readout's "reset to 100%" action, distinct from `fitBounds` (which is
 *  <=100% and driven by the scene's own size). Routed through the same
 *  pan-loss clamp as everything else, though a scene smaller than the view
 *  at 100% never needs it. */
export function resetViewport(scene: Size, view: Size): Viewport {
  const scale = clampScale(1);
  const x = (view.w - scene.w * scale) / 2;
  const y = (view.h - scene.h * scale) / 2;
  return clampViewport({ x, y, scale }, scene, view);
}

/** Pan by `(dx, dy)` client px — the same sign convention as a wheel
 *  event's own `deltaX`/`deltaY` ("scroll" semantics: a positive `deltaY`,
 *  scrolling down, reveals content below by moving the viewport's content
 *  up, i.e. `y` decreases). Drag-to-pan ("grab" semantics: content follows
 *  the pointer 1:1) is the same function called with the pointer's client
 *  delta negated at the call site (`Canvas.tsx`) — one clamp, two callers,
 *  opposite-signed by convention rather than two near-duplicate functions. */
export function panBy(vp: Viewport, dx: number, dy: number, scene: Size, view: Size): Viewport {
  return clampViewport({ x: vp.x - dx, y: vp.y - dy, scale: vp.scale }, scene, view);
}
