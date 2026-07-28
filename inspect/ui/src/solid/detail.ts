import { createEffect, createSignal } from 'solid-js';
import { createStore, unwrap } from 'solid-js/store';
import type { CellDetail, CellState, Ref, StateSummaryPayload } from '../api/types';
import { DetailController, defaultDetailTransport } from '../sync/detailClient';
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

export { cellDetail, cellState, detailError, detailLoading, stateError, stateLoading, stateSummaries };

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
 *  mount (mirrors `sync/state.ts`'s `connect()`). */
export function initDetail(): void {
  let prev: Ref | null = null;
  createEffect(() => {
    const ref = selection();
    if (ref === prev) return;

    clearSummary(prev);
    setCellDetail(null);
    setCellState(null);
    setDetailError(null);
    setStateError(null);

    if (ref) {
      setDetailLoading(true);
      setStateLoading(true);
      controller.select(ref);
    } else {
      controller.deselect();
    }
    prev = ref;
  });
}

/** Fed from `solid/state.ts`'s SSE event router on every `state.summary`
 *  event, regardless of ref. */
export function onStateSummary(payload: StateSummaryPayload): void {
  controller.onSummary(payload.ref);
  if (payload.ref === selection()) {
    setStateSummaries(payload.ref, payload);
  }
}
