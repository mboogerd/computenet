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
 *  chip (10-target-v3.md: "cardinality · frontier wave · staleness"). In M1
 *  this only ever holds (at most) one entry — the observed/selected ref —
 *  since only selection subscribes; kept keyed by ref rather than a single
 *  slot so it needs no change if a later milestone observes more than one
 *  cell at once. Cleared for a ref as soon as it stops being observed, so
 *  "cells without an active observation simply show no chip" (ticket). */
const [stateSummaries, setStateSummaries] = createStore<Record<Ref, StateSummaryPayload>>({});

/** V1A-FE ticket Implement §3: the onChange log for the currently selected
 *  cell — at most one cell is ever observed at a time in M1 (same as
 *  `DetailController`/`stateSummaries` above), so a single instance is
 *  cleared and re-fed on every selection change rather than keyed by ref. */
const changeLog = new ChangeLog();
const [changeLogVersion, setChangeLogVersion] = createSignal(0);
changeLog.subscribe(() => setChangeLogVersion((v) => v + 1));

export { cellDetail, cellState, changeLog, changeLogVersion, detailError, detailLoading, stateError, stateLoading, stateSummaries };

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
});

function clearSummary(ref: Ref | null): void {
  if (ref && ref in unwrap(stateSummaries)) {
    setStateSummaries(ref, undefined!);
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

    clearSummary(prev);
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
  });
}

/** Fed from `solid/state.ts`'s SSE event router on every `state.summary`
 *  event, regardless of ref. */
export function onStateSummary(payload: StateSummaryPayload): void {
  controller.onSummary(payload);
  if (payload.ref === selection()) {
    setStateSummaries(payload.ref, payload);
    changeLog.onSummary(payload);
  }
}
