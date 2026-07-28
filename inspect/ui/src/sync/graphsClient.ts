import type { GraphList } from '../api/types';

/** `GET /api/inspect/graphs` (20-api-contract.md "Endpoints"). A separate
 *  request/response interface, not folded into `TopologyClient` — same
 *  reasoning as `ErrorsTransport`/`DetailTransport`: a plain fetch, not a
 *  stream, kept behind an interface so `solid/graphs.ts` is unit-testable
 *  with a mock transport. */
export interface GraphsTransport {
  fetchList(): Promise<GraphList>;
}

export const defaultGraphsTransport: GraphsTransport = {
  fetchList: async () => {
    const res = await fetch('/api/inspect/graphs');
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as GraphList;
  },
};
