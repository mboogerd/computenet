import type { ErrorSnapshot } from '../api/types';

/** `GET /api/inspect/errors` (20-api-contract.md "Endpoints"). A separate
 *  request/response interface, not folded into `TopologyClient` — same
 *  reasoning as `DetailTransport` in `sync/detailClient.ts`: it is a plain
 *  fetch, not a stream, and keeping it behind an interface makes
 *  `solid/errors.ts` unit-testable with a mock transport. */
export interface ErrorsTransport {
  fetchSnapshot(): Promise<ErrorSnapshot>;
}

export const defaultErrorsTransport: ErrorsTransport = {
  fetchSnapshot: async () => {
    const res = await fetch('/api/inspect/errors');
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as ErrorSnapshot;
  },
};
