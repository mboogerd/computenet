import { createEffect } from 'solid-js';
import { formatHash, TOGGLE_KEYS, type Route, type ToggleKey } from '../nav/route';
import { selection, setSelection } from './selection';
import { currentGraphId, initialRoute, screen, setCurrentGraphId, setScreen } from './routeState';
import { setGraphFilter } from './state';
import { setShowErrors, setShowFlow, setShowHosts, setShowState, showErrors, showFlow, showHosts, showState } from './toggles';

// Re-exported so components (`app.tsx`, `Header.tsx`, `Navigator.tsx`) keep
// importing screen/currentGraphId from here — the M4 navigation module —
// while `solid/state.ts` imports the raw signal from `./routeState`
// directly to avoid a `state.ts` <-> `route.ts` cycle (see that file's own
// doc comment, and `solid/selection.ts`'s identical precedent).
export { currentGraphId, screen };

const TOGGLE_GETTERS: Record<ToggleKey, () => boolean> = {
  hosts: showHosts,
  flow: showFlow,
  errors: showErrors,
  state: showState,
};
const TOGGLE_SETTERS: Record<ToggleKey, (v: boolean) => void> = {
  hosts: setShowHosts,
  flow: setShowFlow,
  errors: setShowErrors,
  state: setShowState,
};

function activeToggles(): ToggleKey[] {
  return TOGGLE_KEYS.filter((k) => TOGGLE_GETTERS[k]());
}

function applyToggles(toggles: readonly ToggleKey[]): void {
  const active = new Set(toggles);
  for (const k of TOGGLE_KEYS) TOGGLE_SETTERS[k](active.has(k));
}

/** Wire the M4 navigation lifecycle (10-target-v3.md Navigator; M4-FE ticket
 *  Implement §1). Call once, on app mount — mirrors `solid/state.ts`'s
 *  `connect()` / `solid/detail.ts`'s `initDetail()`. Must run before
 *  `connect()` so the very first topology fetch already carries the right
 *  `?graph=` filter for a deep-linked graph URL. */
export function initRoute(): void {
  if (initialRoute.screen === 'graph') {
    setSelection(initialRoute.ref);
    applyToggles(initialRoute.toggles);
  }

  // currentGraphId -> the topology fetch filter. Fires once immediately with
  // the value `routeState.ts` already seeded from the boot-time hash, before
  // this function's caller (app.tsx) goes on to call connect() — so
  // start()'s own first fetch already carries the right `?graph=` filter.
  createEffect(() => setGraphFilter(currentGraphId()));

  // App state -> the URL hash (deep-linkable; no router dependency — agora
  // precedent, demo/agora/ui/src/app.tsx). Only the Graph screen carries a
  // ref/toggle payload; Home always collapses to '#/'.
  createEffect(() => {
    const s = screen();
    const route: Route =
      s === 'home'
        ? { screen: 'home' }
        : { screen: 'graph', graphId: currentGraphId() ?? '', ref: selection(), toggles: activeToggles() };
    const h = formatHash(route);
    if (location.hash !== h) history.replaceState(null, '', h);
  });
}

/** Enter a graph — thumbnail/card click, or a search hit (10-target-v3.md
 *  Navigator: "click to enter a graph's canvas"; M4-FE ticket Implement §4:
 *  a problems-hit "opens the graph with the Errors toggle forced on"). The
 *  existing (M0-M3) toggle signals are untouched otherwise — "thumbnail
 *  click-through preserves toggles" (ticket Tests) holds for free, since
 *  they are module-level state that navigation never resets. */
export function enterGraph(graphId: string, opts: { ref?: string | null; forceErrors?: boolean } = {}): void {
  setCurrentGraphId(graphId);
  setSelection(opts.ref ?? null);
  if (opts.forceErrors) setShowErrors(true);
  setScreen('graph');
}

/** Back to the navigator (10-target-v3.md Navigator; M4-FE ticket Implement
 *  §1: "Back returns to Home"). Deselecting first lets the existing M1 P6
 *  lifecycle (`solid/detail.ts`) release whatever was observed; clearing
 *  `currentGraphId` switches the shared topology fetch back to unfiltered,
 *  refreshing the data Home's constellation reads from. */
export function goHome(): void {
  setSelection(null);
  setCurrentGraphId(null);
  setScreen('home');
}
