import { createSignal } from 'solid-js';
import {
  fitBounds,
  IDENTITY_VIEWPORT,
  panBy as panByPure,
  resetViewport,
  zoomAt,
  type Size,
  type Viewport,
} from '../nav/viewport';

// FE-CANVAS ticket Solution direction §2: per-graph viewport state, session-
// local — module-level, matching how `solid/toggles.ts`'s five overlay
// signals already survive navigation. Kept deliberately thin: every actual
// computation (clamping, cursor-anchored zoom, fit, centring) lives in the
// framework-free `nav/viewport.ts`; this module is just the signal(s), the
// per-graph `Map`, and the "first entry fits, later entries restore" wiring
// `Canvas.tsx` drives.
//
// **Not in the URL hash.** Decided (ticket Solution direction §2): the hash
// (`nav/route.ts`) is the shareable identity of *what* is being looked at —
// graph, selection, toggles — durable and meaningful to paste into a new
// tab. A viewport is a continuous per-session ergonomic (every wheel tick
// would otherwise call `history.replaceState`) with no sensible encoding in
// `formatHash`'s canonicalized integer/string token list for the floating-
// point `x`/`y`/`scale` here. Nothing found while implementing this ticket
// argues against that — see the completion report.

const [viewport, setViewportSignal] = createSignal<Viewport>(IDENTITY_VIEWPORT);
export { viewport };

const [sceneSize, setSceneSizeSignal] = createSignal<Size>({ w: 0, h: 0 });
const [viewSize, setViewSizeSignal] = createSignal<Size>({ w: 0, h: 0 });
// `viewSize` (the getter) is exported alongside the setter — not just for
// symmetry. `Canvas.tsx`'s first-entry-fit effect must read it *directly and
// unconditionally* on every run (not only indirectly, inside
// `ensureFirstFit`'s own conditional call): `ResizeObserver` typically
// notifies exactly once for a `.canvas` element whose box size never changes
// again afterward (no further resize happens), and if that one notification
// lands *before* the topology fetch has settled — a real, observed race, not
// a hypothetical one — an effect that only reads this signal from inside a
// branch gated on "the scene is ready" never ends up subscribed to it before
// the one-and-only update already fired, and the fit deadlocks forever. See
// the effect in `Canvas.tsx` for the fix this getter exists for.
export { viewSize };

/** `Canvas.tsx` calls these from its layout memo (scene) and its
 *  `ResizeObserver` on `.canvas` (view) — the two live inputs every
 *  fit/zoom/pan computation needs, kept here rather than threaded through
 *  every action's argument list. */
export function setSceneSize(size: Size): void {
  setSceneSizeSignal(size);
}
export function setViewSize(size: Size): void {
  setViewSizeSignal(size);
}

/** Last-seen viewport per graph id — the thing that makes "leaving and
 *  re-entering the same graph restores the viewport the user left it at"
 *  true. `activeGraphId` is whichever graph the current `viewport()` value
 *  belongs to, so every mutating action below persists into the right map
 *  entry without being told the graph id again. */
const savedByGraph = new Map<string, Viewport>();
let activeGraphId: string | null = null;

/** Graph ids that have already been resolved this session — either restored
 *  from `savedByGraph` or given their one first-entry fit
 *  (`ensureFirstFit`). Guards the "fit once, not on every subsequent
 *  structural change" rule (ticket Solution direction §2). */
const resolvedGraphIds = new Set<string>();

function commit(vp: Viewport): void {
  setViewportSignal(vp);
  if (activeGraphId !== null) savedByGraph.set(activeGraphId, vp);
}

/** Call once per `Canvas.tsx` mount (i.e. once per graph entry — Canvas only
 *  mounts while the Graph screen shows a hot graph, so its mount/unmount
 *  boundary already coincides with "entering"/"leaving" a graph). Restores
 *  a stored viewport verbatim, or — if this graph has never been seen this
 *  session — resets to identity and leaves it `unresolved` so the layout-
 *  size effect's `ensureFirstFit` performs the one-time fit once the async
 *  topology fetch has actually produced a non-empty scene. */
export function enterGraphViewport(graphId: string): void {
  activeGraphId = graphId;
  const saved = savedByGraph.get(graphId);
  if (saved) {
    resolvedGraphIds.add(graphId);
    setViewportSignal(saved);
  } else {
    resolvedGraphIds.delete(graphId);
    setViewportSignal(IDENTITY_VIEWPORT);
  }
}

/** `Canvas.tsx`'s layout-size effect (gated on `layout().width > 0`) and its
 *  `ResizeObserver` callback both call this — whichever of "the topology
 *  fetch resolved" and "the canvas element has been measured" happens last
 *  is the one that actually fits. No-ops once `graphId` is resolved (either
 *  by this or by a restore in `enterGraphViewport`), so a later structural
 *  change (a node added to a graph already being viewed) never re-fits and
 *  discards a pan/zoom the user has since made. */
export function ensureFirstFit(graphId: string): void {
  if (resolvedGraphIds.has(graphId)) return;
  const scene = sceneSize();
  const view = viewSize();
  if (scene.w <= 0 || scene.h <= 0 || view.w <= 0 || view.h <= 0) return;
  resolvedGraphIds.add(graphId);
  commit(fitBounds(scene, view));
}

export function fitToScreen(): void {
  commit(fitBounds(sceneSize(), viewSize()));
}

export function resetZoom(): void {
  commit(resetViewport(sceneSize(), viewSize()));
}

/** `anchor` defaults to the view centre (keyboard `+`/`-`, and the zoom
 *  controls' `-`/`+` buttons); the wheel handler always passes the pointer
 *  position explicitly. */
export function zoomBy(factor: number, anchor?: { x: number; y: number }): void {
  const view = viewSize();
  const at = anchor ?? { x: view.w / 2, y: view.h / 2 };
  commit(zoomAt(viewport(), at, factor, sceneSize(), view));
}

/** Pan by client `(dx, dy)` — wheel "scroll" semantics (see `nav/viewport.ts`
 *  `panBy`'s own doc comment); drag-to-pan negates its own deltas at the
 *  call site in `Canvas.tsx` before calling this. */
export function panByAmount(dx: number, dy: number): void {
  commit(panByPure(viewport(), dx, dy, sceneSize(), viewSize()));
}
