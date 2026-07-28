import { createSignal } from 'solid-js';
import type { GraphSummary } from '../api/types';
import { defaultGraphsTransport, type GraphsTransport } from '../sync/graphsClient';

/** GraphList state for the Home/Navigator cards (M4-FE ticket Implement §2),
 *  mirroring `solid/errors.ts`'s shape: a plain signal (there is no per-item
 *  delta protocol for this feed, unlike topology/errors — the contract's
 *  only refresh trigger is `graphs.changed`, "hint to refetch GraphList",
 *  so a full replace on every fetch is the whole story). */
const [graphs, setGraphs] = createSignal<readonly GraphSummary[]>([]);
const [graphsLoading, setGraphsLoading] = createSignal(false);
const [graphsError, setGraphsError] = createSignal<unknown>(null);
export { graphs, graphsError, graphsLoading };

let transport: GraphsTransport = defaultGraphsTransport;

/** Test seam: swap the transport before calling {@link fetchGraphs}. */
export function setGraphsTransport(t: GraphsTransport): void {
  transport = t;
}

/** `GET /api/inspect/graphs` — called once on app boot (`app.tsx`) and again
 *  on every `graphs.changed` SSE event (`solid/state.ts`'s event switch). */
export function fetchGraphs(): void {
  setGraphsLoading(true);
  void transport.fetchList().then(
    (list) => {
      setGraphsLoading(false);
      setGraphs(list.graphs);
      setGraphsError(null);
    },
    (err) => {
      setGraphsLoading(false);
      setGraphsError(err);
      console.error('inspect: graph list fetch failed', err);
    },
  );
}
