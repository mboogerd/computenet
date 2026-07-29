import type { ActivitySnapshot } from '../api/types';

/** `GET /api/inspect/activity` (V2-FE ticket contract). A separate
 *  request/response interface, not folded into `TopologyClient` — same
 *  reasoning as `ErrorsTransport` in `sync/errorsClient.ts`: a plain fetch,
 *  not a stream, kept behind an interface so `solid/activity.ts` is
 *  unit-testable with a mock transport. */
export interface ActivityTransport {
  fetchSnapshot(): Promise<ActivitySnapshot>;
}

export const defaultActivityTransport: ActivityTransport = {
  fetchSnapshot: async () => {
    const res = await fetch('/api/inspect/activity');
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as ActivitySnapshot;
  },
};
