/** FE-TESTS ticket: the shared DOM test harness.
 *
 *  Every DOM suite under `test/dom/**` seeds the app through the **real**
 *  data path — `connect()` / `fetchGraphs()` / `initDetail()`, exactly as
 *  `src/app.tsx` calls them — rather than reaching into `solid/state.ts`'s
 *  store directly. That exercises `sync/client.ts` -> `sync/store.ts` ->
 *  `solid/state.ts` -> components, the seam the ticket's own "why this gap
 *  has already cost something" section names as the one that actually
 *  breaks. Components with plain props (`ValueView`, `Tooltip`) skip this
 *  harness entirely and are rendered directly with a fixture value.
 *
 *  ## What's stubbed, and where a future test extends it
 *
 *  - `globalThis.fetch` — a tiny router (`installFetch` below) answering
 *    `/topology`, `/graphs`, `/cell/{ref}`, `/cell/{ref}/state`,
 *    `/cell/{ref}/observe` (POST/DELETE — needed so `DetailController`'s
 *    real observe-then-fetch-state path succeeds; not explicitly named in
 *    the ticket's endpoint list but required for `initDetail()` to work end
 *    to end), `/errors`, `/activity` and `/search`, every one served from a
 *    checked-in `fixtures/*.json` file. It does NOT honour the `?graph=`
 *    filter query param on `/topology` — `fixtures/topology.json` is a
 *    single component, so a real, correctly-filtering server would answer
 *    identically with or without the filter for every graph id this suite
 *    ever selects. A future fixture with more than one component would need
 *    this router to actually filter by the query param.
 *  - `globalThis.EventSource` — an inert stub (`InertEventSource`): stores
 *    the handlers `sync/client.ts` assigns (`onopen`/`onmessage`/`onerror`)
 *    but never calls them. `conn()` (`solid/state.ts`) therefore stays
 *    `'connecting'` for the life of every DOM test — fine today, since none
 *    of the five suites render `Header.tsx` (the only reader). A future test
 *    that needs a live/reconnecting transition, or to push a `state.summary`
 *    /`error.*`/`activity` SSE frame, extends this stub to actually invoke
 *    `.onopen()`/`.onmessage()` — or, more simply, calls the exported
 *    `onStateSummary`/`onError*`/`onActivity` bridges directly (the same
 *    terminal handlers a real SSE frame would reach), the way
 *    `pushStateSummary` below already does for the state chip.
 *  - `globalThis.ResizeObserver` — an inert stub (`InertResizeObserver`):
 *    `.observe()`/`.unobserve()`/`.disconnect()` are no-ops, and the
 *    constructor's callback is never invoked. `Canvas.tsx`'s viewport code
 *    tolerates a `view` size of `{0, 0}` throughout (`nav/viewport.ts`'s
 *    `clampViewport` no-ops on a zero-area view/scene) — zoom still changes
 *    `viewport().scale` correctly with no real measurement, which is what
 *    `canvas.test.tsx`'s zoom-control assertions rely on. A future test
 *    that needs a genuine fit-to-screen (a non-identity `x`/`y`) would need
 *    this stub to synchronously invoke its callback with a real
 *    `contentRect`.
 *
 *  ## Non-obvious Vite/Vitest wiring this needed
 *
 *  None beyond what `vite.config.ts` already had: `resolve.conditions:
 *  ['browser']` under `VITEST` (already present for the node-environment
 *  suites, for the identical "solid-js resolves to its SSR build" reason)
 *  turned out to be the exact same fix the jsdom suites needed too — see
 *  that file's comment. No `test.server.deps.inline` entry, no additional
 *  jsdom polyfill beyond `EventSource`/`ResizeObserver` above, was required.
 *
 *  ## Reset discipline
 *
 *  Vitest isolates modules **per file**, not per test — every module-level
 *  singleton (`solid/state.ts`'s `store`, `solid/toggles.ts`'s five
 *  signals, `solid/detail.ts`'s `DetailController`, ...) survives from one
 *  `it()` to the next inside the same file. `startApp()` below is therefore
 *  meant to run exactly ONCE per test file (a `beforeAll`); `resetAppState()`
 *  resets the mutable bits between tests (a `beforeEach`) without re-running
 *  `connect()`/`fetchGraphs()`/`initDetail()` a second time — doing that
 *  would stack a second `createEffect` inside `initDetail()` on top of the
 *  first, double-firing every observe/release call. `cleanup()` (unmounting
 *  whatever the previous test rendered, and running its `onCleanup`s) is
 *  registered once here, in an `afterEach` at module scope, so every DOM
 *  suite gets it for free just by importing this file.
 */
import { cleanup, waitFor } from '@solidjs/testing-library';
import { createRoot } from 'solid-js';
import { afterEach } from 'vitest';
import type { Ref } from '../../src/api/types';
import { initDetail, unpinAll } from '../../src/solid/detail';
import { fetchGraphs, graphsLoaded } from '../../src/solid/graphs';
import { setCurrentGraphId, setScreen } from '../../src/solid/routeState';
import { setSelection } from '../../src/solid/selection';
import { connect, ready } from '../../src/solid/state';
import { setShowErrors, setShowFlow, setShowHosts, setShowNet, setShowState } from '../../src/solid/toggles';

import cellDetailFixture from '../../fixtures/cell-detail.json';
import cellStateTableFixture from '../../fixtures/cell-state-table.json';
import errorsFixture from '../../fixtures/errors.json';
import activityFixture from '../../fixtures/activity.json';
import graphsFixture from '../../fixtures/graphs.json';
import searchDataFixture from '../../fixtures/search-data.json';
import searchNameFixture from '../../fixtures/search-name.json';
import searchProblemsFixture from '../../fixtures/search-problems.json';
import topologyFixture from '../../fixtures/topology.json';

// --- fixture-derived refs, named once so every suite reads them from one
// place instead of re-typing uuids (`fixtures/topology.json`'s skillmatch
// pilot graph) ---------------------------------------------------------

/** `candSkills` — a `SetCell` source; `fixtures/cell-state-table.json`'s
 *  ref, the default `/cell/{ref}/state` fixture below. No errors of its own
 *  in `fixtures/errors.json`. */
export const CAND_SKILLS_REF: Ref = 'e7651ef0-8140-48c3-b9a9-647bac311c4c:0';
/** `matches` — a `JoinSetCell`; the one ref `fixtures/errors.json` actually
 *  carries a dead letter / parked row / restart / wave-health entry for, and
 *  `fixtures/cell-state-truncated.json`'s ref. */
export const MATCHES_REF: Ref = '016eda8f-96de-40e1-a04c-f997395ade62:0';
/** An `ObserveCell` sink (`name: null` in the topology fixture) —
 *  `fixtures/cell-state-unavailable.json`'s ref. */
export const UNAVAILABLE_STATE_REF: Ref = '1875486c-b83b-4fea-a9b1-f6339794cfd5:0';

// --- fetch stub ---------------------------------------------------------

/** Per-ref override for `GET /cell/{ref}/state`, keyed by ref — lets a test
 *  select a specific cell-state shape (`$table`, `$truncated`, `$opaque`,
 *  scalar, tree, `unavailable`) for whichever ref it selects, without a
 *  second fixture-router special case per shape. Defaults to
 *  `cell-state-table.json` for any ref not registered here. Cleared by
 *  {@link resetAppState} so a fixture set by one test never leaks into the
 *  next. */
const stateByRef = new Map<Ref, unknown>();

export function setStateFixture(ref: Ref, value: unknown): void {
  stateByRef.set(ref, value);
}

function jsonResponse(body: unknown): Response {
  // A plain object satisfying the subset of the `Response` interface this
  // app's code actually reads (`.ok`, `.status`, `.json()`, `.url` only in
  // an error-message string) — not a real `Response`/`Headers` construction,
  // which jsdom does not provide and this harness has no need to imitate in
  // full.
  return {
    ok: true,
    status: 200,
    url: '',
    json: async () => body,
  } as Response;
}

function installFetch(): void {
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const method = (init?.method ?? 'GET').toUpperCase();
    const { pathname, searchParams } = new URL(raw, 'http://localhost');

    if (pathname === '/api/inspect/topology') return jsonResponse(topologyFixture);
    if (pathname === '/api/inspect/graphs') return jsonResponse(graphsFixture);
    if (pathname === '/api/inspect/errors') return jsonResponse(errorsFixture);
    if (pathname === '/api/inspect/activity') return jsonResponse(activityFixture);

    const stateMatch = pathname.match(/^\/api\/inspect\/cell\/([^/]+)\/state$/);
    if (stateMatch) {
      const ref = decodeURIComponent(stateMatch[1]);
      return jsonResponse(stateByRef.get(ref) ?? cellStateTableFixture);
    }

    const observeMatch = pathname.match(/^\/api\/inspect\/cell\/([^/]+)\/observe$/);
    if (observeMatch && (method === 'POST' || method === 'DELETE')) return jsonResponse({});

    const detailMatch = pathname.match(/^\/api\/inspect\/cell\/([^/]+)$/);
    if (detailMatch) return jsonResponse(cellDetailFixture);

    if (pathname === '/api/inspect/search') {
      const mode = searchParams.get('mode');
      if (mode === 'problems') return jsonResponse(searchProblemsFixture);
      if (mode === 'data') return jsonResponse(searchDataFixture);
      return jsonResponse(searchNameFixture);
    }

    throw new Error(`test harness: unhandled fetch ${method} ${pathname}`);
  }) as typeof fetch;
}

// --- EventSource stub ----------------------------------------------------

class InertEventSource {
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  constructor(public readonly url: string) {}
  close(): void {
    // inert: nothing was ever really open
  }
  addEventListener(): void {}
  removeEventListener(): void {}
  dispatchEvent(): boolean {
    return true;
  }
}

// --- ResizeObserver stub ---------------------------------------------------

class InertResizeObserver {
  constructor(_callback: ResizeObserverCallback) {}
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

let stubsInstalled = false;

function installStubs(): void {
  if (stubsInstalled) return;
  stubsInstalled = true;
  installFetch();
  globalThis.EventSource = InertEventSource as unknown as typeof EventSource;
  globalThis.ResizeObserver = InertResizeObserver as unknown as typeof ResizeObserver;
}

// --- app bootstrap ---------------------------------------------------------

let appStarted = false;

/** Call once per test file (a `beforeAll`). Wires the sync layer exactly as
 *  `app.tsx` does — `connect()` (topology + SSE), `fetchGraphs()` (the Home
 *  cards' `GraphList`), `initDetail()` (the selection -> observe/state
 *  lifecycle) — then waits (bounded, no sleeps) for the topology snapshot
 *  and the graph list to both have landed, so every test's first render
 *  already has real, fixture-derived data instead of an empty boot frame. */
export async function startApp(): Promise<void> {
  if (appStarted) return;
  appStarted = true;
  installStubs();
  // `initDetail()` registers a `createEffect` (the selection -> observe/
  // state lifecycle); Solid warns ("computations created outside a
  // `createRoot` or `render` will never be disposed") if that happens
  // outside an owner. In the real app, `App()` itself runs inside
  // `render()`'s root; here there is no such root until a component under
  // test is rendered, so this one is created explicitly and deliberately
  // never disposed — it, like every other singleton this harness wires up,
  // is meant to live for the whole test file.
  createRoot(() => initDetail());
  connect();
  fetchGraphs();
  await waitFor(() => {
    if (!ready()) throw new Error('waiting for the topology snapshot to land');
  });
  await waitFor(() => {
    if (!graphsLoaded()) throw new Error('waiting for the graph list to land');
  });
}

/** Call in every test's `beforeEach`. Resets exactly the module-level
 *  singleton state the ticket calls out — toggles off, selection null,
 *  screen home — plus the two knock-on bits a leftover selection/pin would
 *  otherwise carry into the next test: `unpinAll()` (so a pin from one test
 *  never survives into the next) and the per-ref state-fixture overrides.
 *  Does NOT re-run `startApp()` — see the module doc comment for why. */
export function resetAppState(): void {
  setSelection(null);
  unpinAll();
  setCurrentGraphId(null);
  setScreen('home');
  setShowHosts(false);
  setShowNet(false);
  setShowFlow(false);
  setShowErrors(false);
  setShowState(false);
  stateByRef.clear();
}

afterEach(() => cleanup());
