import { createEffect, createSignal } from 'solid-js';
import { createStore, unwrap } from 'solid-js/store';
import type { CellDetail, CellState, Ref, StateSummaryPayload } from '../api/types';
import { ChangeLog } from '../sync/changeLog';
import { DetailController, defaultDetailTransport } from '../sync/detailClient';
import { currentGraphCold } from './cold';
import { selection } from './selection';

const [cellDetail, setCellDetail] = createSignal<CellDetail | null>(null);
const [detailError, setDetailError] = createSignal<unknown>(null);
const [detailLoading, setDetailLoading] = createSignal(false);

const [cellState, setCellState] = createSignal<CellState | null>(null);
const [stateError, setStateError] = createSignal<unknown>(null);
const [stateLoading, setStateLoading] = createSignal(false);

/** Latest `state.summary` per ref, for the canvas "State" toggle's per-cell
 *  chip (10-target-v3.md: "cardinality · frontier wave · staleness"). V1B-FE
 *  ticket: generalized from "at most one entry (the selected ref)" to one
 *  entry per ref currently in {@link observed} — pinning a second cell now
 *  really does add a second chip. Pruned for a ref as soon as it stops being
 *  observed, so "cells without an active observation simply show no chip"
 *  (M1-FE ticket) still holds, now per ref rather than just for selection. */
const [stateSummaries, setStateSummaries] = createStore<Record<Ref, StateSummaryPayload>>({});

/** V1B-FE ticket Solution direction §1/§2: the explicit pinned set and the
 *  refs whose observe request came back 409 ("no fold to observe" — see
 *  `sync/detailClient.ts`'s `ObserveOutcome`), bridged from
 *  `DetailController`'s `onPinsChanged` handler below. */
const [pinned, setPinned] = createSignal<ReadonlySet<Ref>>(new Set());
const [snapshotOnly, setSnapshotOnly] = createSignal<ReadonlySet<Ref>>(new Set());

/** V1A-FE ticket Implement §3: the onChange log for the currently selected
 *  cell — unlike `stateSummaries` above, this one is NOT generalized to the
 *  full observed set by V1B-FE: only the selected cell has a detail panel to
 *  show a log in (Solution direction §1's "descriptor fetching stays tied
 *  to selection() alone" reasoning applies here too), so a single instance
 *  cleared and re-fed on every selection change remains correct. */
const changeLog = new ChangeLog();
const [changeLogVersion, setChangeLogVersion] = createSignal(0);
changeLog.subscribe(() => setChangeLogVersion((v) => v + 1));

export {
  cellDetail,
  cellState,
  changeLog,
  changeLogVersion,
  detailError,
  detailLoading,
  pinned,
  snapshotOnly,
  stateError,
  stateLoading,
  stateSummaries,
};

/** `pinned() ∪ {selection()}` — everything currently tracked for
 *  observation (V1B-FE ticket Solution direction §1's "observed set"), used
 *  by the canvas chip layer and the header's cost indicator. Selection only
 *  contributes while it is not descriptor-only (cold graph) — mirrors
 *  `DetailController`'s own `isObservedLive`. */
export function observed(): ReadonlySet<Ref> {
  const p = pinned();
  const sel = selection();
  if (sel === null || currentGraphCold() || p.has(sel)) return p;
  return new Set([...p, sel]);
}

export function isPinned(ref: Ref): boolean {
  return pinned().has(ref);
}

export function pin(ref: Ref): void {
  controller.pin(ref);
}

export function unpin(ref: Ref): void {
  controller.unpin(ref);
}

/** Release every pinned ref not currently selected — the header's "N
 *  observed" affordance (click to unpin all) and the navigation
 *  pin-clearing call (`solid/route.ts`). */
export function unpinAll(): void {
  controller.unpinAll();
}

const controller = new DetailController(defaultDetailTransport, {
  onDetail: (_ref, detail, error) => {
    setDetailLoading(false);
    setCellDetail(detail ?? null);
    setDetailError(error ?? null);
  },
  onState: (_ref, state, error) => {
    setStateLoading(false);
    setCellState(state ?? null);
    setStateError(error ?? null);
  },
  onPinsChanged: (nextPinned, nextSnapshotOnly) => {
    setPinned(new Set(nextPinned));
    setSnapshotOnly(new Set(nextSnapshotOnly));
    pruneStateSummaries();
  },
});

/** Drop any `stateSummaries` entry for a ref that has fallen out of the
 *  observed set (unpinned and not selected, or deselected and not pinned) —
 *  generalizes the old single-ref `clearSummary(prev)` helper. */
function pruneStateSummaries(): void {
  const obs = observed();
  for (const ref in unwrap(stateSummaries)) {
    if (!obs.has(ref)) setStateSummaries(ref, undefined!);
  }
}

/** Wire the M1 subscription lifecycle to `selection()`. Call once, on app
 *  mount (mirrors `sync/state.ts`'s `connect()`).
 *
 *  M5-COLD: the effect also depends on `currentGraphCold()`, so a graph that
 *  goes cold (or wakes) under a standing selection re-runs it — releasing the
 *  observation on the way into cold, and opening one on the way back out. */
export function initDetail(): void {
  let prev: Ref | null = null;
  let prevCold = false;
  createEffect(() => {
    const ref = selection();
    const cold = currentGraphCold();
    if (ref === prev && cold === prevCold) return;

    changeLog.clear();
    setCellDetail(null);
    setCellState(null);
    setDetailError(null);
    setStateError(null);

    if (ref) {
      setDetailLoading(true);
      // a cold graph's state is not fetched at all — the panel says
      // "unavailable without waking" rather than spinning forever
      setStateLoading(!cold);
      controller.select(ref, cold ? 'descriptor' : 'live');
    } else {
      controller.deselect();
    }
    prev = ref;
    prevCold = cold;
    // the old selection (if it fell out of the observed set — i.e. wasn't
    // also pinned) no longer has a chip; V1B-FE generalizes the old
    // single-ref `clearSummary(prev)` call to this
    pruneStateSummaries();
  });
}

/** Fed from `solid/state.ts`'s SSE event router on every `state.summary`
 *  event, regardless of ref. V1B-FE ticket Solution direction §2: the
 *  `stateSummaries` write gate generalizes from "only the selected ref" to
 *  "any ref in the observed set" — the chip layer now renders one per
 *  pinned/selected cell, not just the selected one. The onChange log stays
 *  selection-only (see the `changeLog` doc comment above). */
export function onStateSummary(payload: StateSummaryPayload): void {
  controller.onSummary(payload);
  if (observed().has(payload.ref)) setStateSummaries(payload.ref, payload);
  if (payload.ref === selection()) changeLog.onSummary(payload);
}
